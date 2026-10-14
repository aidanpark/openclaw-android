#!/usr/bin/env bash
# limits.sh - calls of node and npm with a time limit, for the OpenClaw platform scripts (update.sh, install.sh,
# verify.sh). Source it right after scripts/lib.sh.
#
# A node or npm that stops answering (a stalled Android process, a database on a slow flash) must not stop the whole
# update, and must not turn into an empty answer that a script reads as "the installed version is different" (that
# would install an OpenClaw that is already right). The limit itself is oa_node_limited of scripts/lib.sh
# (class probe: OA_NODE_PROBE_TIMEOUT, 30 s; class work: OA_NODE_WORK_TIMEOUT, 300 s; exit status 75 = no answer;
# 130 / 143 = the call was ended by Ctrl-C / SIGTERM of this script: see oc_end_on_signal).
# A lib.sh that does not have it (an older copy left in a cache) makes every call here run without a limit, as it
# did before: nothing in this file is allowed to fail because the function is missing.
#
# Functions (all quiet; none of them exits):
#   oc_limited <probe|work> <command...>   the command's own exit status, 75 when it did not answer in time
#   oc_limit_seconds <probe|work>          the limit in seconds as lib.sh reads it (the value a message may name)
#   oc_end_on_signal <status>              130 / 143 (the call was ended by Ctrl-C / SIGTERM): this script ends as the
#                                          signal would have ended it (lib.sh's oa_end_on_signal, when it has one).
#                                          The functions below call it themselves: the call runs in a command
#                                          substitution, in which the signal does not reach the main shell
#   oc_capture <probe|work> <command...>   prints the command's standard output (through a file, so that a process
#                                          that outlives it cannot keep the caller waiting), exit status as oc_limited;
#                                          what was printed before a stop at the limit is printed too
#   oc_node_version                        prints v<x.y.z>, "none" (no node), or "no answer within <N>s". A version
#                                          that was printed before the call stopped at the limit is an answer
#                                          (the rule of _oa_node_version of update-core.sh: two gates, one verdict)
#   oc_npm_root                            prints `npm root -g`, or $PREFIX/lib/node_modules when npm does not answer
#   oc_pkg_version <package.json>          prints the "version" of a package.json (no node involved), or nothing
#   oc_installed_version <package.json>    the installed version: oc_pkg_version, then a reading that also takes a
#                                          one-line (minified) file, then node with the probe limit. Prints the version
#                                          (exit 0); exit 1 when the file has none (node answered and found nothing);
#                                          exit 75 when node did not answer: the version is UNKNOWN, which is not
#                                          the same as "not installed" and must never lead to a reinstall
#   oc_postinstall <package dir>           OpenClaw's own bundled-plugins postinstall step; a stall is only a warning
#   oc_node_work <node args...>            node with the work limit (a command word for oa_repair_state_paths and
#                                          oa_repair_config_paths of lib.sh, which take the node command as an argument)
#   oc_node_runs_in <dir> <js code>        0 = the code ran and succeeded, 75 = no answer, other = it failed

oc_limited() {
    local class="${1:-probe}"
    shift
    if declare -f oa_node_limited >/dev/null 2>&1; then
        oa_node_limited "$class" "$@"
    else
        "$@"
    fi
}

# The limit in seconds as lib.sh reads it (digits only, 5 to 999999, else the default): a message that names the
# limit names the one that was applied, not the text of the environment variable. The one place that knows how
# lib.sh gives the value: oa_node_limit_seconds (this lib.sh), _oa_limit_value (the first version of the limit
# function), the default (no limit function: the number is never shown with a stop).
oc_limit_seconds() {
    local class="probe" raw="" def=30
    case "${1:-probe}" in
        work) class=work; raw="${OA_NODE_WORK_TIMEOUT:-}"; def=300 ;;
        *)    raw="${OA_NODE_PROBE_TIMEOUT:-}" ;;
    esac
    if declare -f oa_node_limit_seconds >/dev/null 2>&1; then
        oa_node_limit_seconds "$class"
    elif declare -f _oa_limit_value >/dev/null 2>&1; then
        _oa_limit_value "$raw" "$def"
    else
        printf '%s' "$def"
    fi
}

oc_end_on_signal() {
    if declare -f oa_end_on_signal >/dev/null 2>&1; then
        oa_end_on_signal "${1:-}"
    fi
    return 0
}

oc_capture() {
    local class="${1:-probe}" tmp="" rc=0
    shift
    tmp="$(mktemp "${TMPDIR:-${PREFIX:-/tmp}/tmp}/oc-capture.XXXXXX" 2>/dev/null)" || tmp=""
    if [ -z "$tmp" ]; then
        oc_limited "$class" "$@" 2>/dev/null </dev/null || rc=$?
        oc_end_on_signal "$rc"
        return "$rc"
    fi
    # shellcheck disable=SC2016  # the quoted program is read by sh, not here
    oc_limited "$class" sh -c 'exec "$@" >"$0" 2>/dev/null </dev/null' "$tmp" "$@" || rc=$?
    cat "$tmp" 2>/dev/null || true
    rm -f "$tmp"
    oc_end_on_signal "$rc"
    return "$rc"
}

oc_node_version() {
    local out="" rc=0 try
    for try in 1 2; do
        rc=0
        out="$(oc_limited probe node --version 2>/dev/null </dev/null)" || rc=$?
        oc_end_on_signal "$rc"
        case "$rc" in 130|143) return "$rc" ;; esac
        # a version that was printed before the stop is an answer (the call hung while it ended)
        if [ "$rc" = 75 ] && printf '%s\n' "$out" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
            rc=0
        fi
        [ "$rc" = 75 ] || break
    done
    if [ "$rc" = 75 ]; then
        printf 'no answer within %ss' "$(oc_limit_seconds probe)"
    elif [ "$rc" = 0 ] && [ -n "$out" ]; then
        printf '%s' "$out"
    else
        printf 'none'
    fi
}

oc_npm_root() {
    local out="" rc=0
    out="$(oc_capture probe npm root -g)" || rc=$?
    [ "$rc" = 0 ] || out=""
    out="${out%%$'\n'*}"
    if [ -n "$out" ]; then
        printf '%s' "$out"
    else
        printf '%s' "${PREFIX:-/usr}/lib/node_modules"
    fi
}

# The same reading as update-core.sh uses for its pin comparison: the first "version" line of package.json.
oc_pkg_version() {
    [ -f "${1:-}" ] || return 0
    sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$1" 2>/dev/null | head -1 || true
}

oc_installed_version() {
    local f="${1:-}" v="" rc=0
    [ -f "$f" ] || return 1
    v="$(oc_pkg_version "$f")"
    if [ -z "$v" ]; then
        # a minified package.json is one line: the key is not at the start of a line. The FIRST "version" is the
        # package's own (a plugin block further in has its own)
        v="$(grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" 2>/dev/null | head -1 \
            | sed 's/^"version"[[:space:]]*:[[:space:]]*"\(.*\)"$/\1/' || true)"
    fi
    if [ -z "$v" ]; then
        v="$(oc_limited probe node -p "require('$f').version" 2>/dev/null </dev/null)" || rc=$?
        oc_end_on_signal "$rc"
        case "$rc" in 130|143) return "$rc" ;; esac
        # a version printed before the stop is an answer, as for oc_node_version
        if [ "$rc" = 75 ] && printf '%s\n' "$v" | grep -Eq '^[0-9][0-9A-Za-z.+-]*$'; then
            rc=0
        fi
        [ "$rc" != 75 ] || return 75
        [ "$rc" = 0 ] || v=""
        v="${v%%$'\n'*}"
    fi
    [ -n "$v" ] || return 1
    printf '%s' "$v"
}

oc_postinstall() {
    local dir="${1:-}" rc=0
    [ -d "$dir" ] || return 0
    (
        cd "$dir" || exit 0
        export npm_config_ignore_scripts=true
        oc_limited work node scripts/postinstall-bundled-plugins.mjs >/dev/null 2>&1 </dev/null
    ) || rc=$?
    oc_end_on_signal "$rc"
    if [ "$rc" = 75 ]; then
        echo -e "${YELLOW:-}[WARN]${NC:-} OpenClaw's postinstall step did not finish within $(oc_limit_seconds work)s; going on"
    fi
    return 0
}

oc_node_work() {
    oc_limited work node "$@"
}

oc_node_runs_in() {
    local dir="${1:-}" code="${2:-}" rc=0
    ( cd "$dir" 2>/dev/null && oc_limited probe node -e "$code" >/dev/null 2>&1 </dev/null ) || rc=$?
    oc_end_on_signal "$rc"
    return "$rc"
}
