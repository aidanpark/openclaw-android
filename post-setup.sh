#!/usr/bin/env bash
# OpenClaw Android — Post-Bootstrap Setup
# Runs in the terminal after Termux bootstrap extraction.
# Installs: git, glibc, Node.js, OpenClaw
#
# Strategy:
#   - Termux .deb packages: dpkg-deb -x + relocate (bypasses dpkg hardcoded paths)
#   - Pacman .pkg.tar.xz packages: tar -xJf + relocate (bypasses pacman entirely)
#   - Both have files under data/data/com.termux/files/usr/ which we relocate to $PREFIX
#
# Why not apt-get/dpkg/pacman?
#   All three have hardcoded /data/data/com.termux/... paths that libtermux-exec
#   cannot rewrite (it only intercepts execve, not open/opendir).

set -eo pipefail

# ─── Paths ────────────────────────────────────
: "${PREFIX:?PREFIX not set}"
: "${HOME:?HOME not set}"
: "${TMPDIR:=$(dirname "$PREFIX")/tmp}"

OCA_DIR="$HOME/.openclaw-android"
NODE_DIR="$OCA_DIR/node"
BIN_DIR="$OCA_DIR/bin"

# ─── Version pin (pair) ───────────────────────
# Same keys and values as platforms/openclaw/config.env (the SSOT). This script
# runs as a single file and does not read config.env; .githooks/pre-commit
# checks that these lines match it. Bump both files together.
PLATFORM_NPM_PACKAGE_VERSION="2026.9.8"
PLATFORM_NODE_VERSION="24.21.0"
NODE_VERSION="$PLATFORM_NODE_VERSION"

GLIBC_LDSO="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
MARKER="$OCA_DIR/.post-setup-done"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Mode: a normal run installs everything. "--tools-only <tool>..." (used by the app's
# tools screen) only installs the listed optional tools: see run_tools_only below.
OA_MODE="full"
OA_TOOL_ARGS=()
OA_TOOL_IDS="tmux ttyd dufs android-tools playwright claude-code gemini-cli codex-cli"
if [ "${1:-}" = "--tools-only" ]; then
    OA_MODE="tools"
    shift
    # "--tools-only --list": print the supported ids, one per line, and stop (no lock,
    # no network, no result file). An older copy of this script ignores the arguments
    # (it prints "Post-setup already completed." or starts a full install), so the
    # caller must accept only lines that are known ids and must call it only once
    # the post-setup marker exists.
    if [ "${1:-}" = "--list" ] && [ "$#" -eq 1 ]; then
        for _oa_id in $OA_TOOL_IDS; do echo "$_oa_id"; done
        exit 0
    fi
    OA_TOOL_ARGS=("$@")
fi

# ─── run lock ───
# One run at a time: the full setup, --tools-only here and `oa --update` (update-core.sh, same
# block) share $OCA_DIR/.tools.lock. This block is kept identical in both files (pre-commit).
# Take $OCA_DIR/.tools.lock (mkdir is atomic); the lock holds the owner's pid. A lock
# whose owner is gone (SIGKILL cannot be trapped) is stale and is taken over; a lock
# with no pid (owner died between mkdir and the pid write) is stale after a minute.
# Takeovers are serialized by a second mkdir lock so two runs cannot both take over.
# Taking over and releasing both happen under that second lock (the "guard"): while a
# taker holds it, the owner it is judging cannot release, so the lock it removes is the
# lock it judged.
# Returns 1 when another run holds the lock, 2 when the lock cannot be made or its pid recorded.
acquire_tools_lock() {
    local lk="$OCA_DIR/.tools.lock" tk="$OCA_DIR/.tools.lock.takeover" owner="" stale=false rc=1
    # (no lock there after a failed mkdir: it may have been released just now, so one more try)
    if mkdir "$lk" 2>/dev/null || { [ ! -e "$lk" ] && sleep 0.1 && mkdir "$lk" 2>/dev/null; }; then
        if { echo "$$" > "$lk/pid"; } 2>/dev/null; then
            return 0
        fi
        rm -f "$lk/pid" 2>/dev/null || true
        rmdir "$lk" 2>/dev/null || true
        return 2
    fi
    # a taker that was killed leaves its guard behind: drop it after a minute
    if [ -d "$tk" ] && [ -n "$(find "$tk" -maxdepth 0 -mmin +1 2>/dev/null)" ]; then
        rmdir "$tk" 2>/dev/null || true
    fi
    # no lock there and none could be made (disk full, no write access): nothing holds it, so not "busy"
    [ -e "$lk" ] || return 2
    mkdir "$tk" 2>/dev/null || return 1
    owner=$(cat "$lk/pid" 2>/dev/null) || owner=""
    if [ -n "$owner" ]; then
        kill -0 "$owner" 2>/dev/null || stale=true
    elif [ -d "$lk" ] && [ -n "$(find "$lk" -maxdepth 0 -mmin +1 2>/dev/null)" ]; then
        stale=true
    elif [ -e "$lk" ] && [ ! -d "$lk" ]; then
        stale=true      # a plain file in the lock's place
    fi
    # Only a lock judged stale is removed; a lock that vanished meanwhile is just retaken
    [ "$stale" = false ] || rm -rf "$lk" 2>/dev/null || true
    if mkdir "$lk" 2>/dev/null; then
        if { echo "$$" > "$lk/pid"; } 2>/dev/null; then
            rc=0
        else
            # only our own, still empty lock
            rm -f "$lk/pid" 2>/dev/null || true
            rmdir "$lk" 2>/dev/null || true
            rc=2
        fi
    fi
    rmdir "$tk" 2>/dev/null || true
    return "$rc"
}

# Free our lock under the guard (waits about 5.5 s at most). Without the guard nothing is removed: our
# pid is dead once we exit, so the next call takes the lock over.
release_tools_lock() {
    local lk="$OCA_DIR/.tools.lock" tk="$OCA_DIR/.tools.lock.takeover" tries=0 held=""
    while [ "$tries" -lt 50 ]; do
        if mkdir "$tk" 2>/dev/null; then
            held=$(cat "$lk/pid" 2>/dev/null) || held=""
            if [ "$held" = "$$" ]; then
                rm -f "$lk/pid" 2>/dev/null || true
                rmdir "$lk" 2>/dev/null || true
            fi
            rmdir "$tk" 2>/dev/null || true
            return 0
        fi
        sleep 0.1
        tries=$((tries + 1))
    done
    return 0
}
# ─── end run lock ───

# ─── Full run: result file ───────────────────
# The full setup leaves $OCA_DIR/post-setup-result.conf for the app (same format rules as
# tools-result.conf: KEY=VALUE lines, never executed). It is written when the run starts
# and at every stage, so a file WITHOUT an "exit" key belongs to a run still going (or one that
# was killed with SIGKILL); the final write adds exit=<install's own exit code> and, on failure,
# error=<code>. Keys: schema, run (start epoch), stage (1..7|done, the last one started),
# error, need_mb/have_mb (error=free-space), warn (comma list of non-fatal problems), exit.
# Exit code 2 = another run holds the lock (nothing is written then: the other run's result stays).
OA_FULL_LOCK_OWNED=false
OA_FULL_FINAL=false
OA_FULL_RUN=""
OA_FULL_STAGE=""
OA_FULL_WARN=""
OA_FULL_NEED_MB=""
OA_FULL_HAVE_MB=""
OA_FULL_SIGNAL_CODE=""

# oa_full_result [exit code]: rewrite the result file (no exit key while the run goes on)
oa_full_result() {
    local f="$OCA_DIR/post-setup-result.conf"
    [ "$OA_FULL_LOCK_OWNED" = true ] || return 0
    rm -rf "$f.tmp" 2>/dev/null || true
    {
        printf 'schema=1\nrun=%s\n' "$OA_FULL_RUN"
        [ -z "$OA_FULL_STAGE" ] || printf 'stage=%s\n' "$OA_FULL_STAGE"
        [ -z "${OA_TOOLS_ERROR:-}" ] || printf 'error=%s\n' "$OA_TOOLS_ERROR"
        [ -z "$OA_FULL_NEED_MB" ] || printf 'need_mb=%s\n' "$OA_FULL_NEED_MB"
        [ -z "$OA_FULL_HAVE_MB" ] || printf 'have_mb=%s\n' "$OA_FULL_HAVE_MB"
        [ -z "$OA_FULL_WARN" ] || printf 'warn=%s\n' "$OA_FULL_WARN"
        [ -z "${1:-}" ] || printf 'exit=%s\n' "$1"
    } > "$f.tmp" 2>/dev/null && mv -f "$f.tmp" "$f" 2>/dev/null || true
}
oa_stage() { OA_FULL_STAGE="$1"; oa_full_result; }
# oa_warn <id>: a non-fatal problem (tools:<id>, clawdhub, oa-cli, backup-scripts, compat-js, hardlink-patch, checkOnStart)
oa_warn() {
    case ",$OA_FULL_WARN," in *",$1,"*) ;; *) OA_FULL_WARN="${OA_FULL_WARN:+$OA_FULL_WARN,}$1" ;; esac
    oa_full_result
}

# The install finished: write the final result and free the lock now, before the
# interactive onboard (its exit code is not the install's)
oa_full_finalize() {
    [ "$OA_FULL_LOCK_OWNED" = true ] && [ "$OA_FULL_FINAL" != true ] || return 0
    trap '' TERM HUP INT    # not half way through the marker, the final write and the unlock
    touch "$MARKER"
    OA_FULL_STAGE="done"
    oa_full_result 0
    OA_FULL_FINAL=true
    release_tools_lock
    trap - TERM HUP INT     # the onboard that may follow is as it was before: no trap of ours
}

# Runs when the full setup ends, however it ends
oa_full_finish() {
    local code=$?
    trap '' TERM HUP INT    # a second signal must not cut the result file short
    trap - EXIT
    [ -z "$OA_FULL_SIGNAL_CODE" ] || code=$OA_FULL_SIGNAL_CODE
    if [ "$OA_FULL_LOCK_OWNED" = true ] && [ "$OA_FULL_FINAL" != true ]; then
        # exit code 2 means "another run holds the lock" (the app reads it so): a failure that ends
        # with some other command's 2 (tar, ...) is reported as 1; signals keep their codes.
        # (After the install is final the code is the onboard's: left alone.)
        case "$code" in 0|1|129|130|143) ;; *) code=1 ;; esac
        # a failure with no code of its own (set -e stopped it) is "unknown", never a success
        [ "$code" -eq 0 ] || [ -n "${OA_TOOLS_ERROR:-}" ] || OA_TOOLS_ERROR=unknown
        oa_full_result "$code"
        release_tools_lock
    fi
    exit "$code"
}

# The storage message (the install needs about 2000 MB); false when there is no shortage
OA_FULL_NOSPACE=false
oa_full_nospace_message() {
    [ "$OA_FULL_NOSPACE" = true ] || return 1
    echo -e "${RED}[FAIL]${NC} Not enough free storage to install OpenClaw: 2000 MB needed, ${OA_FULL_HAVE_MB} MB available."
    echo "       Nothing was changed. Free some space (clear other apps' caches, delete unused files) and open the app again."
    return 0
}

# Start of a full run: signals, the lock (exit 2 when busy), the first result file
oa_full_begin() {
    local _lock_rc=0
    trap 'OA_TOOLS_ERROR=interrupted; OA_FULL_SIGNAL_CODE=143; exit 143' TERM
    trap 'OA_TOOLS_ERROR=interrupted; OA_FULL_SIGNAL_CODE=129; exit 129' HUP
    trap 'OA_TOOLS_ERROR=interrupted; OA_FULL_SIGNAL_CODE=130; exit 130' INT
    acquire_tools_lock || _lock_rc=$?
    if [ "$_lock_rc" -eq 1 ]; then
        echo "Another setup, update or tools run is in progress. Try again when it has finished." >&2
        exit 2
    elif [ "$_lock_rc" -ne 0 ]; then
        # (exit 1, not 2: the app reads 2 as "another run is going")
        # On a full disk the lock cannot be made either: then the storage message is the right one
        oa_full_nospace_message || echo "Could not create the run lock in $OCA_DIR (error=lock)." >&2
        exit 1
    fi
    OA_FULL_LOCK_OWNED=true
    OA_FULL_RUN=$(date +%s)
    trap oa_full_finish EXIT
    rm -f "$OCA_DIR/post-setup-result.conf"
    oa_full_result
}

# ─── npm version-pin guard ────────────────────
# Fallback pin for the guard in the npm wrapper (the guard reads config.env at
# run time once it exists; a first install has none yet).
OA_GUARD_PKG="openclaw"
OA_GUARD_PIN="$PLATFORM_NPM_PACKAGE_VERSION"

# Splice the version-pin guard into a generated npm wrapper: the guard text
# replaces the "# __NPM_GUARD__" line. $1 = wrapper file. OA_GUARD_PKG / OA_GUARD_PIN
# are the pin this installer ships (the guard also reads the platform config at
# run time). The guard text must stay identical in scripts/install-nodejs.sh and
# post-setup.sh.
oa_inject_npm_guard() {
    local f="$1" g
    IFS= read -r -d '' g << 'NPMGUARD' || true
# ── Version-pin guard ──
# Refuses a GLOBAL install/update of the pinned platform package at any version
# other than the pin: 'npm install -g openclaw@latest' (typed by a user or run by
# an agent) would replace the verified OpenClaw + Node.js pair. Two pins are
# accepted: the one in the platform config on disk, and the one this wrapper was
# generated with — an installer that ships a newer pin than the config on disk
# (a re-install or recovery run) must still be able to install its own pin.
# Fails open: whatever this block cannot parse is passed to npm unchanged. There
# is no override on purpose. Limits: npm run by absolute path, abbreviated option
# names, 'npm exec'/'npx' are not covered.
_oa_pkg="__OA_PKG__"
_oa_pin="__OA_PIN__"
_oa_cfgpin=""
_oa_cfg="$HOME/.openclaw-android/platforms/openclaw/config.env"
if [ -f "$_oa_cfg" ]; then
    _oa_v=$(grep -m1 '^PLATFORM_NPM_PACKAGE_VERSION=' "$_oa_cfg" 2>/dev/null | cut -d'"' -f2)
    case "$_oa_v" in [0-9]*.[0-9]*.[0-9]*) _oa_cfgpin="$_oa_v" ;; esac
    _oa_n=$(grep -m1 '^PLATFORM_NPM_PACKAGE=' "$_oa_cfg" 2>/dev/null | cut -d'"' -f2)
    if [[ "$_oa_n" =~ ^[a-z0-9][a-z0-9._-]*$ ]]; then _oa_pkg="$_oa_n"; fi
fi
_oa_is_pin() { [ "$1" = "$_oa_pin" ] || { [ -n "$_oa_cfgpin" ] && [ "$1" = "$_oa_cfgpin" ]; }; }
_oa_check_spec() {
    local a="$1" x
    case "$a" in
        @*) return ;;
        file:*) _oa_check_spec "${a#file:}"; return ;;
        "$_oa_pkg") _oa_bad="$a"; return ;;
        "$_oa_pkg"@*) _oa_is_pin "${a#*@}" || _oa_bad="$a"; return ;;
    esac
    x="${a#*npm:}"
    if [ "$x" != "$a" ]; then
        case "$x" in
            "$_oa_pkg") _oa_bad="$a"; return ;;
            "$_oa_pkg"@*) _oa_is_pin "${x#*@}" || _oa_bad="$a"; return ;;
        esac
    fi
    case "$a" in
        */"$_oa_pkg"|*/"$_oa_pkg".git|*/"$_oa_pkg"#*|*/"$_oa_pkg".git#*|*/"$_oa_pkg"/archive/*|*/"$_oa_pkg"/tarball/*) _oa_bad="$a" ;;
        "$_oa_pkg"-[v0-9]*.tgz|*/"$_oa_pkg"-[v0-9]*.tgz|"$_oa_pkg"-[v0-9]*.tar.gz|*/"$_oa_pkg"-[v0-9]*.tar.gz|*/"$_oa_pkg"/tar.gz/*|*/"$_oa_pkg"/zip/*) _oa_bad="$a" ;;
        .|..|./*|../*|/*|"~"/*|\~/*)
            case "$a" in "~"/*|\~/*) a="$HOME/${a#*/}" ;; esac
            if [ -f "$a/package.json" ] && grep -Eq "\"name\"[[:space:]]*:[[:space:]]*\"$_oa_pkg\"" "$a/package.json" 2>/dev/null; then
                _oa_bad="$a"
            fi ;;
    esac
}
if [ -n "$_oa_pkg" ] && { [ -n "$_oa_pin" ] || [ -n "$_oa_cfgpin" ]; }; then
    _oa_global=false; _oa_cmd=""; _oa_pos=0; _oa_names=0; _oa_bad=""; _oa_skip=false; _oa_prev=""
    _oa_e="${npm_config_global:-${NPM_CONFIG_GLOBAL:-}}"
    case "$_oa_e" in ""|false|0|null) ;; *) _oa_global=true ;; esac
    case "${npm_config_location:-${NPM_CONFIG_LOCATION:-}}" in global) _oa_global=true ;; esac
    _oa_rcs=("$HOME/.npmrc" "${PREFIX:-}/etc/npmrc")
    _oa_d="$PWD"
    _oa_i=0
    # PWD can be stale or relative when the directory was deleted: only walk real absolute paths
    case "$_oa_d" in /*) ;; *) _oa_d="" ;; esac
    while [ -n "$_oa_d" ] && [ "$_oa_d" != "/" ] && [ "$_oa_i" -lt 64 ]; do
        _oa_rcs+=("$_oa_d/.npmrc")
        [ -f "$_oa_d/package.json" ] && break
        _oa_d="${_oa_d%/*}"
        _oa_i=$((_oa_i + 1))
    done
    for _oa_rc in "${_oa_rcs[@]}"; do
        if [ -f "$_oa_rc" ] && grep -Eiq '^[[:space:]]*(global[[:space:]]*=[[:space:]]*"?(true|1|yes|on)"?|location[[:space:]]*=[[:space:]]*"?global"?)' "$_oa_rc" 2>/dev/null; then
            _oa_global=true
        fi
    done
    for _oa_a in "$@"; do
        if [ "$_oa_skip" = true ]; then
            _oa_skip=false
            [ "$_oa_prev" = "location" ] && [ "$_oa_a" = "global" ] && _oa_global=true
            continue
        fi
        case "$_oa_a" in
            -*)
                _oa_n="$_oa_a"
                while [ "${_oa_n#-}" != "$_oa_n" ]; do _oa_n="${_oa_n#-}"; done
                _oa_prev="$_oa_n"
                case "$_oa_n" in
                    L) _oa_skip=true; _oa_prev="location" ;;
                    location|prefix|registry|cache|userconfig|globalconfig|loglevel|tag|scope|omit|include|otp|workspace|w|C|script-shell|save-prefix|install-strategy|depth|proxy|https-proxy|noproxy|fetch-timeout|fetch-retries|before|access|audit-level|auth-type|logs-dir|logs-max|node-options) _oa_skip=true ;;
                    g|global|no-no-global|l*=global) _oa_global=true ;;
                    g=*|global=*)
                        # '--global=true' (and '--global=<spec>', which npm reads as a package)
                        _oa_v="${_oa_n#*=}"
                        case "$_oa_v" in false) ;; *) _oa_global=true; _oa_check_spec "$_oa_v" ;; esac ;;
                    *)
                        if [[ "$_oa_a" =~ ^-[gSDOEBfdspyqlhavnPcmLHwC]+$ ]]; then
                            case "$_oa_n" in *g*) _oa_global=true ;; esac
                            case "$_oa_n" in *L) _oa_skip=true; _oa_prev="location" ;; *[wCcm]) _oa_skip=true ;; esac
                        fi ;;
                esac ;;
            *)
                _oa_pos=$((_oa_pos + 1))
                if [ -z "$_oa_cmd" ]; then
                    case "$_oa_a" in
                        link|lin|ln)
                            _oa_cmd=install; _oa_global=true; continue ;;
                        i|in|ins|inst|insta|instal|install|isnt|isnta|isntal|isntall|add|ci|clean-install|install-t|install-te|install-tes|install-test|installT|installTe|installTes|installTest|it|cit|install-ci-test|installCiTest)
                            _oa_cmd=install; continue ;;
                        up|ud|upd|upda|updat|update|upg|upgr|upgra|upgrad|upgrade|udp|udpa|udpat|udpate)
                            _oa_cmd=update; continue ;;
                    esac
                fi
                case "$_oa_a" in always|true|false|*://*) ;; *) if [[ "$_oa_a" =~ ^@?[a-z] ]]; then _oa_names=$((_oa_names + 1)); fi ;; esac
                _oa_check_spec "$_oa_a" ;;
        esac
    done
    if [ "$_oa_global" = true ] && [ -n "$_oa_cmd" ]; then
        # 'npm install -g' / 'npm link' with no name installs the package in the current directory
        if [ -z "$_oa_bad" ] && [ "$_oa_cmd" = install ] && [ "$_oa_pos" -le 1 ]; then _oa_check_spec "."; fi
        if [ -n "$_oa_bad" ]; then
            echo "[BLOCKED] $_oa_pkg is pinned to ${_oa_cfgpin:-$_oa_pin} (the version verified by OpenClaw on Android)." >&2
            echo "          '$_oa_bad' would replace it. Run 'oa --update' to update safely." >&2
            exit 1
        fi
        if [ "$_oa_cmd" = update ] && [ "$_oa_names" -eq 0 ]; then
            echo "[BLOCKED] 'npm update -g' would also upgrade $_oa_pkg, which is pinned to ${_oa_cfgpin:-$_oa_pin}." >&2
            echo "          Name the packages to update, or run 'oa --update' to update safely." >&2
            exit 1
        fi
    fi
fi
NPMGUARD
    g="${g//__OA_PKG__/${OA_GUARD_PKG:-}}"
    g="${g//__OA_PIN__/${OA_GUARD_PIN:-}}"
    if ! G="$g" awk '$0 == "# __NPM_GUARD__" { print ENVIRON["G"]; next } { print }' "$f" > "$f.g" || ! mv -f "$f.g" "$f"; then
        rm -f "$f.g"
        echo -e "${YELLOW}[WARN]${NC} npm version-pin guard could not be added to the npm wrapper"
    fi
}

# ─── gpgv signature helpers ───────────────────
# For downloads that are checked against a signed index (the glibc package
# database today). Return codes — the caller picks the message from the code:
#    0 good signature        1 bad signature
#    2 no good signature, and signed only by keys the keyring does not have
#    3 expired signature/key or a wrong clock
#    4 any other failure to reach a verdict
#   10 gpgv is missing      11 keyring missing or unreadable
#   12 gpgv cannot run here (not executable, killed, no usable home directory)
# The verdict comes from gpgv's machine-readable status lines (--status-fd), never
# from its free text: that text can carry strings chosen by whoever made the
# signature. rc 0 alone is not enough either (gpgv also exits 0 for a signature
# from an expired key): success needs GOODSIG and VALIDSIG and none of the failure
# lines. Nothing here falls back to an unchecked file; the clearsigned output file
# is deleted on every non-zero result. $OA_GPGV_ERR holds gpgv's text for debugging
# (do not print it raw). Callers running under `set -e` must capture the code with
# `|| rc=$?`.
OA_GPGV_ERR=""
# 0 when gpgv and the keyring $1 are usable, else 10/11/12
oa_gpgv_check_tools() {
    local gpgv="$PREFIX/bin/gpgv" keyring="$1"
    if [ ! -e "$gpgv" ]; then OA_GPGV_ERR="gpgv not found at $gpgv"; return 10; fi
    if [ ! -x "$gpgv" ] || [ -d "$gpgv" ]; then OA_GPGV_ERR="gpgv is not executable: $gpgv"; return 12; fi
    if [ ! -s "$keyring" ] || [ ! -r "$keyring" ]; then
        OA_GPGV_ERR="keyring missing or unreadable: $keyring"
        return 11
    fi
    return 0
}
_oa_gpgv() {
    local out="$1" keyring="$2" gpgv="$PREFIX/bin/gpgv" home="$TMPDIR/gnupg" err="" rc=0 status
    shift 2
    OA_GPGV_ERR=""
    oa_gpgv_check_tools "$keyring" || return $?
    if ! mkdir -p "$home" || ! chmod 700 "$home"; then
        OA_GPGV_ERR="cannot create $home"
        return 12
    fi
    if ! status=$(mktemp "$home/status.XXXXXX"); then
        OA_GPGV_ERR="cannot create a status file in $home"
        return 12
    fi
    if [ -n "$out" ]; then
        rm -f "$out"
        err=$(LC_ALL=C "$gpgv" --homedir "$home" --keyring "$keyring" --status-fd 3 --output "$out" "$@" 2>&1 3>"$status") || rc=$?
    else
        err=$(LC_ALL=C "$gpgv" --homedir "$home" --keyring "$keyring" --status-fd 3 "$@" 2>&1 3>"$status") || rc=$?
    fi
    OA_GPGV_ERR="$err"
    local verdict=4
    if [ "$rc" -eq 126 ] || [ "$rc" -eq 127 ] || [ "$rc" -gt 128 ]; then
        verdict=12
    elif grep -Eq '^\[GNUPG:\] BADSIG ' "$status"; then
        verdict=1
    elif grep -Eq '^\[GNUPG:\] (EXPSIG|EXPKEYSIG|KEYEXPIRED|SIGEXPIRED) ' "$status"; then
        verdict=3
    elif ! { grep -Eq '^\[GNUPG:\] GOODSIG ' "$status" && grep -Eq '^\[GNUPG:\] VALIDSIG ' "$status"; }; then
        # no good signature from a key in the keyring
        if grep -Eq '^\[GNUPG:\] NO_PUBKEY ' "$status"; then
            # A key created "in the future" looks like a missing key in the status lines;
            # only gpgv's own line (anchored at the start of the line) tells them apart.
            if printf '%s\n' "$err" | grep -Eq '^gpgv: key [0-9A-Fa-f]+ was created .* in the future \(time warp or clock problem\)$'; then
                verdict=3
            else
                verdict=2
            fi
        fi
    elif grep -Eq '^\[GNUPG:\] (REVKEYSIG|FAILURE|NODATA|ERROR) ' "$status" \
        || awk '$1 == "[GNUPG:]" && $2 == "ERRSIG" && $8 != 9 { f = 1 } END { exit !f }' "$status"; then
        verdict=4
    elif [ "$rc" -eq 0 ] || { [ "$rc" -eq 2 ] && grep -Eq '^\[GNUPG:\] NO_PUBKEY ' "$status"; }; then
        # A good signature from a key in the keyring counts even when the file is also
        # signed by keys this keyring lacks (gpgv then exits 2; apt accepts this too, e.g.
        # while Termux signs with an old and a new key). Any other failure line, and the
        # absence of a good signature, were handled above. Note: gpgv 2.5 stops at the first
        # signature it cannot check, so a signature that comes after an unknown-key one
        # is never evaluated (apt has the same limit); a good signature from the keyring
        # over exactly the checked data is what this accepts.
        verdict=0
        if [ -n "$out" ] && [ ! -s "$out" ]; then verdict=4; fi
    fi
    rm -f "$status"
    if [ "$verdict" -ne 0 ] && [ -n "$out" ]; then rm -f "$out"; fi
    return "$verdict"
}
# oa_gpgv_clearsigned <keyring> <clearsigned file> <out>: on success <out> holds the signed text
oa_gpgv_clearsigned() { _oa_gpgv "$3" "$1" "$2"; }
# oa_gpgv_detached <keyring> <signature file> <data file>
oa_gpgv_detached() { _oa_gpgv "" "$1" "$2" "$3"; }
# ─── end gpgv signature helpers ───────────────

# ─── GitHub mirror fallback (for China/restricted networks) ──
REPO_BASE_ORIGIN="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
REPO_BASE="$REPO_BASE_ORIGIN"
resolve_repo_base() {
    if curl -sI --connect-timeout 3 "$REPO_BASE_ORIGIN/oa.sh" >/dev/null 2>&1; then
        REPO_BASE="$REPO_BASE_ORIGIN"; return 0
    fi
    local mirrors=(
        "https://ghfast.top/$REPO_BASE_ORIGIN"
        "https://ghproxy.net/$REPO_BASE_ORIGIN"
        "https://mirror.ghproxy.com/$REPO_BASE_ORIGIN"
    )
    for m in "${mirrors[@]}"; do
        if curl -sI --connect-timeout 3 "$m/oa.sh" >/dev/null 2>&1; then
            echo -e "  ${YELLOW}[MIRROR]${NC} Using mirror for GitHub downloads"
            REPO_BASE="$m"; return 0
        fi
    done
    return 1
}

# Kept in sync with scripts/lib.sh resolve_npm_registry()
NPM_REGISTRY_ORIGIN="https://registry.npmjs.org/"
NPM_REGISTRY_MIRROR="https://registry.npmmirror.com/"
resolve_npm_registry() {
    local choice
    local cache_file="$OCA_DIR/.npm-registry"
    local reachable=0
    if curl -sI --connect-timeout 5 "$NPM_REGISTRY_ORIGIN" >/dev/null 2>&1; then
        choice="$NPM_REGISTRY_ORIGIN"
        reachable=1
    elif curl -sI --connect-timeout 5 "$NPM_REGISTRY_MIRROR" >/dev/null 2>&1; then
        echo -e "  ${YELLOW}[MIRROR]${NC} Using npm mirror: ${NPM_REGISTRY_MIRROR}"
        choice="$NPM_REGISTRY_MIRROR"
        reachable=1
    else
        choice="$NPM_REGISTRY_ORIGIN"
    fi
    mkdir -p "$(dirname "$cache_file")"
    printf '%s' "$choice" > "$cache_file.tmp" && mv "$cache_file.tmp" "$cache_file"
    export NPM_CONFIG_REGISTRY="$choice"
    if [ "$reachable" -eq 1 ]; then
        return 0
    fi
    return 1
}

# SSL cert for curl (bootstrap curl looks at hardcoded com.termux path)
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"

# Git system config has hardcoded com.termux path — skip it
export GIT_CONFIG_NOSYSTEM=1

# Git exec path (git looks for helpers like git-remote-https here)
export GIT_EXEC_PATH="$PREFIX/libexec/git-core"

# Git template dir (hardcoded /data/data/com.termux path workaround)
export GIT_TEMPLATE_DIR="$PREFIX/share/git-core/templates"

if [ "$OA_MODE" = "full" ]; then
    if [ -f "$MARKER" ]; then
        echo -e "${GREEN}Post-setup already completed.${NC}"
        exit 0
    fi

    # Before anything is installed: OpenClaw takes about 1 GB while it is installed
    # (about 720 MB + about 310 MB npm cache), Node.js and the runtime come on top.
    # Same numbers as scripts/lib.sh (OA_MIN_FREE_INSTALL_MB / oa_check_free_space).
    # (the Termux df does not start in the app terminal: "bad interpreter"; the system's df is tried next)
    # Measured first, before the lock folder is made: on a full disk that folder cannot be made
    # either, and the user must be told about the storage, not about "another run".
    _oa_have_mb=""
    for _oa_df in df /system/bin/df; do
        _oa_have_mb=$({ "$_oa_df" -Pk "$PREFIX" 2>/dev/null || "$_oa_df" -k "$PREFIX" 2>/dev/null || true; } | awk 'NR==2 {print int($4/1024)}')
        [[ "$_oa_have_mb" =~ ^[0-9]+$ ]] && break
    done
    # (A re-run after OpenClaw was already installed needs far less: not checked then.)
    if [ ! -f "$PREFIX/lib/node_modules/openclaw/package.json" ] \
        && [[ "$_oa_have_mb" =~ ^[0-9]+$ ]] && [ "$_oa_have_mb" -lt 2000 ]; then
        OA_FULL_NOSPACE=true; OA_FULL_HAVE_MB="$_oa_have_mb"
    fi

    # One run at a time (the lock and the result file live in $OCA_DIR)
    mkdir -p "$OCA_DIR" || { oa_full_nospace_message || echo "Cannot create $OCA_DIR." >&2; exit 1; }
    oa_full_begin

    echo ""
    echo "══════════════════════════════════════════════"
    echo "  OpenClaw Android — Installing components"
    echo "══════════════════════════════════════════════"
    echo ""

    # Not enough room: the lock is ours, so the result file can say so
    if oa_full_nospace_message; then
        OA_TOOLS_ERROR=free-space; OA_FULL_NEED_MB=2000
        exit 1
    fi
fi

if [ "$OA_MODE" = tools ]; then
    # exit code 2 + no result file: the app reads it as "did not run"
    mkdir -p "$OCA_DIR" "$OCA_DIR/patches" "$TMPDIR" || { echo "Cannot create $OCA_DIR (error=env)." >&2; exit 2; }
else
    mkdir -p "$OCA_DIR" "$OCA_DIR/patches" "$TMPDIR"
fi

TERMUX_DEB_REPO="https://packages-cf.termux.dev/apt/termux-main"
# termux-pacman primary + officially recognized mirrors (https://termux-pacman.dev/mirrors/).
# service. is listed last: it currently redirects to sync.
PACMAN_MIRRORS=(
    "https://sync.termux-pacman.dev/gpkg/aarch64"
    "https://ftp.agdsn.de/termux-pacman/gpkg/aarch64"
    "https://mirror.clarkson.edu/termux-pacman/gpkg/aarch64"
    "https://service.termux-pacman.dev/gpkg/aarch64"
)
GPKG_DB="$TMPDIR/gpkg.db"
TERMUX_INNER="data/data/com.termux/files/usr"
DEB_DIR="$TMPDIR/debs"
PKG_DIR="$TMPDIR/pkgs"
EXTRACT_DIR="$TMPDIR/pkg-extract"

# ─── Helper: install_deb ──────────────────────
# Downloads a .deb from Termux repo and extracts into $PREFIX
install_deb() {
    local filename="$1"
    local name
    name=$(basename "$filename" | sed 's/_[0-9].*//')
    local url="${TERMUX_DEB_REPO}/${filename}"
    local deb_file
    deb_file="${DEB_DIR}/$(basename "$filename")"

    if [ -f "$deb_file" ]; then
        echo "    (cached) $name"
    else
        echo "    downloading $name..."
        # Return the failure explicitly: callers inside `if` run without errexit.
        # Drop a partial download so the next run does not reuse it as "cached".
        if ! curl -fsSL --max-time 120 -o "$deb_file" "$url"; then
            rm -f "$deb_file"
            return 1
        fi
    fi

    # The package must match the checksum in the package list whose signature was
    # verified (cached files too). Nothing is unpacked before this check passes.
    local want have
    want=$(get_deb_sha256 "$filename") || want=""
    have=$(sha256sum "$deb_file" 2>/dev/null | awk '{ print $1 }') || have=""
    if [ "${#want}" -ne 64 ] || [[ "$want" == *[!0-9a-f]* ]] || [ "$have" != "$want" ]; then
        rm -f "$deb_file"
        echo -e "    ${RED}[FAIL]${NC} $name: verification failed (it does not match the signed package list) — not installed" >&2
        echo "           Check your network and try again; the package source may be unavailable or the download corrupted." >&2
        return 1
    fi

    rm -rf "$EXTRACT_DIR"
    mkdir -p "$EXTRACT_DIR"
    if ! dpkg-deb -x "$deb_file" "$EXTRACT_DIR" 2>/dev/null; then
        rm -f "$deb_file"
        rm -rf "$EXTRACT_DIR"
        return 1
    fi

    # Relocate: data/data/com.termux/files/usr/* → $PREFIX/
    # --remove-destination replaces an existing file with a new inode instead of
    # truncating and rewriting it in place, so a process that has the old file open
    # or mapped keeps reading the old content (dpkg also gives a replaced file a new inode).
    if [ -d "$EXTRACT_DIR/$TERMUX_INNER" ]; then
        cp -a --remove-destination "$EXTRACT_DIR/$TERMUX_INNER/"* "$PREFIX/" 2>/dev/null || true
    fi
    rm -rf "$EXTRACT_DIR"
}

# ─── Helpers: pacman packages (glibc) ─────────
# The pacman repo is rolling: package file names change (glibc-2.42-0 is gone),
# so they are read from the repo database (gpkg.db), never hardcoded. The
# database lists each package's file name and sha256.

# Print "FILENAME SHA256" for package $1 from $GPKG_DB (fails unless both look valid)
gpkg_lookup() {
    local pkg="$1"
    local entry
    entry=$(tar -tzf "$GPKG_DB" 2>/dev/null | grep -E "^${pkg}-[0-9][^/]*/desc$" | head -1) || true
    [ -n "$entry" ] || return 1
    tar -xzOf "$GPKG_DB" "$entry" 2>/dev/null | awk '
        /^%FILENAME%$/  { getline; f = $0 }
        /^%SHA256SUM%$/ { getline; s = $0 }
        END {
            if (f ~ /^[A-Za-z0-9._+-]+\.pkg\.tar\.xz$/ && length(s) == 64 && s ~ /^[0-9a-f]+$/) print f, s
            else exit 1
        }'
}

# Download package file $2 from mirror $1 into $PKG_DIR and check it against
# sha256 $3. A cached copy is reused only if it matches.
fetch_pacman_pkg() {
    local mirror="$1"
    local filename="$2"
    local sha="$3"
    local pkg_file="${PKG_DIR}/${filename}"

    if [ -f "$pkg_file" ] && [ "$(sha256sum "$pkg_file" | awk '{ print $1 }')" = "$sha" ]; then
        echo "    (cached) $filename"
        return 0
    fi
    echo "    downloading $filename..."
    rm -f "$pkg_file"
    if ! curl -fsSL --max-time 300 -o "$pkg_file" "${mirror}/${filename}"; then
        rm -f "$pkg_file"
        return 1
    fi
    if [ "$(sha256sum "$pkg_file" | awk '{ print $1 }')" != "$sha" ]; then
        echo -e "    ${YELLOW}[WARN]${NC} sha256 mismatch: $filename"
        rm -f "$pkg_file"
        return 1
    fi
}

# ─── glibc package database: signed, from the first mirror that checks out ───
OA_KEYRING_DIR="$PREFIX/share/termux-keyring"
_GPKG_REASON=""
_GPKG_RC=0
# Download <mirror>/gpkg.db and gpkg.db.sig and verify the signature. On any
# failure the database is deleted so nothing can read an unverified one.
# _GPKG_REASON: unreachable | nosig | badsig | clock | tool   (_GPKG_RC = helper code)
fetch_signed_gpkg_db() {
    local mirror="$1" rc=0
    _GPKG_REASON=""
    _GPKG_RC=0
    rm -f "$GPKG_DB" "$GPKG_DB.sig"
    if ! curl -fsSL --max-time 60 -o "$GPKG_DB" "$mirror/gpkg.db"; then
        rm -f "$GPKG_DB"
        _GPKG_REASON="unreachable"
        return 1
    fi
    if ! curl -fsSL --max-time 60 -o "$GPKG_DB.sig" "$mirror/gpkg.db.sig"; then
        rm -f "$GPKG_DB" "$GPKG_DB.sig"
        _GPKG_REASON="nosig"
        return 1
    fi
    oa_gpgv_detached "$OA_KEYRING_DIR/termux-pacman.gpg" "$GPKG_DB.sig" "$GPKG_DB" || rc=$?
    _GPKG_RC=$rc
    if [ "$rc" -eq 0 ]; then
        return 0
    fi
    rm -f "$GPKG_DB" "$GPKG_DB.sig"
    case "$rc" in
        1|2|4) _GPKG_REASON="badsig" ;;
        3) _GPKG_REASON="clock" ;;
        *) _GPKG_REASON="tool" ;;
    esac
    return 1
}

# Try the mirrors in turn; for the first one whose database signature verifies,
# download and sha256-check every package in GLIBC_PKGS from that same mirror.
# Sets GLIBC_FILES and returns 0, or prints what went wrong and what to do and
# returns 1. There is no unsigned fallback. gpgv and the keyring are checked
# before anything is downloaded.
fetch_glibc_packages() {
    local _mirror _pkg _info _file _sha _fails="" _stop="" _key_unknown=false _verified=false _rc=0
    GLIBC_FILES=()
    _GPKG_RC=0
    oa_gpgv_check_tools "$OA_KEYRING_DIR/termux-pacman.gpg" || _rc=$?
    if [ "$_rc" -ne 0 ]; then
        _stop="tool"
        _GPKG_RC=$_rc
    else
        for _mirror in "${PACMAN_MIRRORS[@]}"; do
            GLIBC_FILES=()
            echo "  Mirror: ${_mirror%/gpkg/aarch64}"
            if ! fetch_signed_gpkg_db "$_mirror"; then
                _fails="$_fails $_GPKG_REASON"
                case "$_GPKG_REASON" in
                    unreachable) echo -e "  ${YELLOW}[WARN]${NC} Package database not reachable" ;;
                    nosig) echo -e "  ${YELLOW}[WARN]${NC} This mirror has no signature for the package database" ;;
                    badsig)
                        [ "$_GPKG_RC" -eq 2 ] && _key_unknown=true
                        echo -e "  ${YELLOW}[WARN]${NC} Signature verification FAILED for the package database on this mirror (code $_GPKG_RC) — not using it" ;;
                    *) _stop="$_GPKG_REASON"; break ;;   # same on every mirror: stop here
                esac
                continue
            fi
            _verified=true
            echo -e "  ${GREEN}✓${NC} Package database signature verified"
            for _pkg in "${GLIBC_PKGS[@]}"; do
                if ! _info=$(gpkg_lookup "$_pkg"); then
                    echo -e "  ${YELLOW}[WARN]${NC} $_pkg: no valid entry in package database"
                    break
                fi
                read -r _file _sha <<< "$_info"
                fetch_pacman_pkg "$_mirror" "$_file" "$_sha" || break
                GLIBC_FILES+=("$_file")
            done
            if [ "${#GLIBC_FILES[@]}" -eq "${#GLIBC_PKGS[@]}" ]; then
                return 0
            fi
        done
    fi
    GLIBC_FILES=()
    echo -e "  ${RED}✗${NC} Could not install glibc."
    if [ -n "$_stop" ]; then
        case "$_stop" in
            tool)
                case "$_GPKG_RC" in
                    10) echo "    Cannot verify package signatures: gpgv is missing. Reinstall the app and try again, or report this." ;;
                    11) echo "    Cannot verify package signatures: the Termux keyring is missing. Reinstall the app and try again, or report this." ;;
                    *) echo "    gpgv cannot run on this device, so package signatures cannot be checked. Installation was stopped for safety. Please report this (error code $_GPKG_RC)." ;;
                esac ;;
            *)
                echo "    Signature check failed. Check the phone's date and time (turn on automatic date & time); if they are right, the signing key may have changed. Restart the app to retry, or report this." ;;
        esac
    elif [ "$_verified" = true ]; then
        echo "    A package list was verified, but the packages could not be downloaded and checked from those mirrors."
        echo "    Check your network connection and restart the app to retry."
    else
        case "$_fails" in
            *badsig*)
                if [ "$_key_unknown" = true ]; then
                    echo "    The package list is signed by a key this app does not know (the signing key may have changed)."
                    echo "    Installation was stopped for safety. Restart the app later (it fetches the latest setup on each start), or report this."
                else
                    echo "    The package list failed its signature check on every mirror that answered. Something may be tampering with the download."
                    echo "    Installation was stopped for safety. Try another network and restart the app to retry."
                fi ;;
            *nosig*)
                echo "    No reachable mirror offered a signed package list, so nothing could be verified."
                echo "    Installation was stopped for safety. Restart the app to retry later, or report this." ;;
            *)
                echo "    Could not download glibc from any mirror:"
                printf '      %s\n' "${PACMAN_MIRRORS[@]}"
                echo "    Check your network connection and restart the app to retry." ;;
        esac
    fi
    return 1
}

# Extract a downloaded .pkg.tar.xz from $PKG_DIR into target dir
install_pacman_pkg() {
    local filename="$1"
    local target="$2"  # e.g., $PREFIX/glibc
    local pkg_file="${PKG_DIR}/${filename}"

    rm -rf "$EXTRACT_DIR"
    mkdir -p "$EXTRACT_DIR"
    tar -xJf "$pkg_file" -C "$EXTRACT_DIR" 2>/dev/null || {
        echo -e "  ${RED}✗${NC} Could not unpack $filename. Restart the app to retry."
        OA_TOOLS_ERROR=glibc
        exit 1
    }

    # Pacman packages also extract under data/data/com.termux/files/usr/...
    local inner="$EXTRACT_DIR/$TERMUX_INNER"
    if [ -d "$inner/glibc" ]; then
        # glibc packages go under $PREFIX/glibc/
        cp -a "$inner/glibc/"* "$target/" 2>/dev/null || true
    elif [ -d "$inner" ]; then
        cp -a "$inner/"* "$target/" 2>/dev/null || true
    fi
    rm -rf "$EXTRACT_DIR"
}

# ─── Termux package list: signed index, checksums, dependency helpers ─────────
# The Packages index is proven to be the one Termux signed:
#   InRelease (signed)  →  size + sha256 of Packages  →  Packages
#   →  sha256 of each .deb (checked in install_deb).
# There is no unsigned fallback: if the list cannot be verified, the run stops.
PACKAGES_FILE="$TMPDIR/Packages"
OA_INRELEASE="$TMPDIR/InRelease"
OA_RELEASE_VERIFIED="$TMPDIR/Release.verified"
OA_DEB_KEYRING="$OA_KEYRING_DIR/termux-autobuilds.gpg"
OA_INDEX_FAIL_EXIT=1     # --tools-only uses 2 (the app tells "could not verify" apart)
OA_TOOLS_ERROR=""        # --tools-only: why the run could not start/finish (result file)

# oa_fail_index <reason|helper code>: say what went wrong and what to do, then stop
oa_fail_index() {
    rm -f "$OA_INRELEASE" "$OA_RELEASE_VERIFIED" "$PACKAGES_FILE"
    OA_TOOLS_ERROR="index-$1"
    echo -e "  ${RED}✗${NC} Could not verify the Termux package list."
    case "$1" in
        download)
            echo "    The package list could not be downloaded. Check your network connection and restart the app to retry." ;;
        mismatch)
            echo "    The downloaded package list does not match its signed checksum. The download may be corrupted or tampered with, or the server may be updating: wait a minute, try another network, and restart the app to retry." ;;
        noentry)
            echo "    The signed package list has no checksum for the package index. Try again later and restart the app to retry; if it keeps failing, report this." ;;
        1)
            echo "    The package list failed its signature check. Something may be tampering with the download. Try another network and restart the app to retry." ;;
        2)
            echo "    The package list is signed by a key this app does not know (the Termux signing key may have changed). Get the latest version of the app and try again, or report this." ;;
        3)
            echo "    The signature check failed because of a date problem. Check that the phone's date and time are correct (automatic date & time) and restart the app to retry. It can also mean the Termux signing key changed." ;;
        10)
            echo "    Cannot verify package signatures: gpgv is missing. Reinstall the app and try again, or report this." ;;
        11)
            echo "    Cannot verify package signatures: the Termux keyring is missing. Reinstall the app and try again, or report this." ;;
        12)
            echo "    gpgv cannot run on this device, so package signatures cannot be checked. Please report this (error code 12)." ;;
        *)
            echo "    The signature check did not give a clear answer (error code $1). Restart the app to retry; if it keeps failing, report this." ;;
    esac
    echo "    Installation was stopped for safety: nothing from the unverified package list was installed."
    exit "$OA_INDEX_FAIL_EXIT"
}

# Download the Packages index and verify it (see above); stops the run on any failure
fetch_verified_index() {
    local _idx_rc=0 _want_sha="" _want_size="" _rel_line _got_sha _got_size
    echo "  Fetching package index..."
    oa_gpgv_check_tools "$OA_DEB_KEYRING" || _idx_rc=$?
    [ "$_idx_rc" -eq 0 ] || oa_fail_index "$_idx_rc"
    curl -fsSL --max-time 60 -o "$OA_INRELEASE" \
        "${TERMUX_DEB_REPO}/dists/stable/InRelease" || oa_fail_index download
    oa_gpgv_clearsigned "$OA_DEB_KEYRING" "$OA_INRELEASE" "$OA_RELEASE_VERIFIED" || _idx_rc=$?
    [ "$_idx_rc" -eq 0 ] || oa_fail_index "$_idx_rc"
    echo -e "  ${GREEN}✓${NC} Package list signature verified"

    # Size and sha256 of Packages as stated in the signed Release (its SHA256: block)
    _rel_line=$(awk '
        /^SHA256:/ { f = 1; next }
        /^[^ ]/ { f = 0 }
        f && $3 == "main/binary-aarch64/Packages" { print $1, $2; exit }
    ' "$OA_RELEASE_VERIFIED") || _rel_line=""
    read -r _want_sha _want_size <<< "$_rel_line" || true
    if [ "${#_want_sha}" -ne 64 ] || [[ "$_want_sha" == *[!0-9a-f]* ]] || [[ "$_want_size" == *[!0-9]* ]] || [ -z "$_want_size" ]; then
        oa_fail_index noentry
    fi
    curl -fsSL --max-time 60 -o "$PACKAGES_FILE" \
        "${TERMUX_DEB_REPO}/dists/stable/main/binary-aarch64/Packages" || oa_fail_index download
    _got_sha=$(sha256sum "$PACKAGES_FILE" | awk '{ print $1 }')
    _got_size=$(wc -c < "$PACKAGES_FILE" | tr -d ' ')
    if [ "$_got_sha" != "$_want_sha" ] || [ "$_got_size" != "$_want_size" ]; then
        oa_fail_index mismatch
    fi
    rm -f "$OA_INRELEASE" "$OA_RELEASE_VERIFIED"
    echo -e "  ${GREEN}✓${NC} Package list matches the signed checksum"
}

# sha256 of the .deb whose "Filename:" is $1, from the (verified) Packages index
get_deb_sha256() {
    awk -v fn="$1" '
        /^$/ { if (fname == fn && sha != "") { print sha; done = 1; exit } fname = ""; sha = ""; next }
        /^Filename: / { fname = $2 }
        /^SHA256: / { sha = $2 }
        END { if (!done && fname == fn && sha != "") print sha }
    ' "$PACKAGES_FILE"
}

# Resolve package filename from Packages index
get_deb_filename() {
    local pkg="$1"
    awk -v pkg="$pkg" '
        /^Package: / { found = ($2 == pkg) }
        found && /^Filename:/ { print $2; exit }
    ' "$PACKAGES_FILE"
}

# Print the packages $1 depends on, one per line (version constraints dropped,
# first alternative of "a | b")
get_deb_depends() {
    local pkg="$1"
    awk -v pkg="$pkg" '
        /^Package: / { found = ($2 == pkg) }
        found && /^Depends:/ {
            sub(/^Depends: /, "")
            n = split($0, parts, / *, */)
            for (i = 1; i <= n; i++) {
                d = parts[i]
                sub(/ *\|.*/, "", d)
                sub(/ *\(.*/, "", d)
                gsub(/ /, "", d)
                if (d != "") print d
            }
            exit
        }
    ' "$PACKAGES_FILE"
}

# True if the bootstrap's dpkg database lists package $1 as installed
dpkg_has() {
    local pkg="$1"
    awk -v pkg="$pkg" '
        /^Package: / { found = ($2 == pkg) }
        found && /^Status: / { if ($0 ~ / installed$/) ok = 1; exit }
        END { exit !ok }
    ' "$PREFIX/var/lib/dpkg/status" 2>/dev/null
}

# Packages handled so far / that failed, space-delimited (guard against cycles
# and repeats; a package that failed stays failed for every later dependent)
DEB_SEEN=" "
DEB_FAILED=" "

# Helper: install a .deb after all of its dependencies, recursively.
# Packages already listed as installed in the bootstrap's dpkg status are
# neither extracted nor followed: the tool runs against the bootstrap's copies
# of them, and the closure does not grow into base packages.
# Fails if the package or any dependency could not be installed.
install_with_deps() {
    local pkg="$1"
    case "$DEB_FAILED" in *" $pkg "*) return 1 ;; esac
    case "$DEB_SEEN" in *" $pkg "*) return 0 ;; esac
    DEB_SEEN="$DEB_SEEN$pkg "

    local filename
    filename=$(get_deb_filename "$pkg")
    if [ -z "$filename" ]; then
        DEB_FAILED="$DEB_FAILED$pkg "
        return 1
    fi

    local deps dep rc=0
    deps=$(get_deb_depends "$pkg")
    while IFS= read -r dep; do
        [ -z "$dep" ] && continue
        dpkg_has "$dep" && continue
        # Not in the index = virtual package: nothing to install
        [ -n "$(get_deb_filename "$dep")" ] || continue
        if ! install_with_deps "$dep"; then
            echo -e "    ${YELLOW}[WARN]${NC} dependency $dep of $pkg could not be installed"
            rc=1
        fi
    done <<< "$deps"

    # A tool whose dependencies could not be installed would be unpacked but
    # could not run (missing libraries): leave it out instead of leaving a
    # broken tool behind.
    if [ "$rc" -ne 0 ]; then
        echo -e "    ${YELLOW}[WARN]${NC} $pkg was not installed because a dependency could not be installed"
        DEB_FAILED="$DEB_FAILED$pkg "
        return 1
    fi

    install_deb "$filename" || rc=1
    [ "$rc" -eq 0 ] || DEB_FAILED="$DEB_FAILED$pkg "
    return $rc
}

# Remove everything downloaded for this run
cleanup_downloads() {
    rm -rf "$DEB_DIR" "$PKG_DIR" "$PACKAGES_FILE" "$OA_INRELEASE" "$OA_RELEASE_VERIFIED" "$TMPDIR/gpkg.db" "$TMPDIR/gpkg.db.sig" "$TMPDIR/gnupg" 2>/dev/null || true
}

# ─── Optional tools: one installer per tool ───────────────────
# Each prints its own progress lines and returns 0 on success, 1 on failure (the
# normal run ignores the code; --tools-only reports it). Same text as always.
tool_install_tmux() {
    echo "  Installing tmux..."
    if install_with_deps tmux; then
        echo -e "  ${GREEN}✓${NC} tmux"
    else
        echo -e "  ${YELLOW}[WARN]${NC} tmux installation failed (non-critical) — skipped"
        return 1
    fi
}
tool_install_ttyd() {
    echo "  Installing ttyd..."
    if install_with_deps ttyd; then
        echo -e "  ${GREEN}✓${NC} ttyd"
    else
        echo -e "  ${YELLOW}[WARN]${NC} ttyd installation failed (non-critical) — skipped"
        return 1
    fi
}
tool_install_dufs() {
    echo "  Installing dufs..."
    if install_with_deps dufs; then
        echo -e "  ${GREEN}✓${NC} dufs"
    else
        echo -e "  ${YELLOW}[WARN]${NC} dufs installation failed (non-critical) — skipped"
        return 1
    fi
}
tool_install_android_tools() {
    echo "  Installing android-tools (adb, fastboot)..."
    # The repository builds it against a newer libc++ than the one in the app's
    # bootstrap (adb: cannot locate symbol std::__ndk1::__hash_memory), and the
    # dependency walk skips libc++ because the bootstrap already has it: refresh it.
    local _libcxx _lib="$PREFIX/lib/libc++_shared.so" _bak="$TMPDIR/libc++_shared.so.orig" _had=false
    _libcxx=$(get_deb_filename libc++)
    if [ -n "$_libcxx" ]; then
        DEB_SEEN="$DEB_SEEN""libc++ "
        # Keep the current library: if the replacement does not end up as a usable
        # file (install_deb ignores copy errors, e.g. a full disk), put it back, so a
        # failed refresh never leaves the shared C++ runtime missing or truncated.
        if [ -f "$_lib" ] && cp -p "$_lib" "$_bak" 2>/dev/null; then _had=true; fi
        if ! install_deb "$_libcxx" || [ ! -s "$_lib" ] || [ "$(head -c 4 "$_lib" 2>/dev/null | od -An -c | tr -d ' ')" != '177ELF' ]; then
            [ "$_had" = true ] && cp -p --remove-destination "$_bak" "$_lib" 2>/dev/null
            rm -f "$_bak"
            echo -e "  ${YELLOW}[WARN]${NC} android-tools installation failed (non-critical) — skipped"
            return 1
        fi
        rm -f "$_bak"
    fi
    if install_with_deps android-tools; then
        echo -e "  ${GREEN}✓${NC} android-tools"
    else
        echo -e "  ${YELLOW}[WARN]${NC} android-tools installation failed (non-critical) — skipped"
        return 1
    fi
}
tool_install_code_server() {
    # Only the old tool-selections.conf can reach this. The npm install of code-server
    # fails everywhere (its postinstall runs a nested npm install that breaks), so it is
    # not tried: the standalone release is installed by `oa --install` (Termux terminal).
    echo -e "  ${YELLOW}[WARN]${NC} code-server cannot be installed here yet — run 'oa --install' in the terminal to install it"
    return 1
}
tool_install_playwright() {
    local rc=0 bin CHROMIUM_BIN=""
    echo "  Installing Playwright (playwright-core)..."
    if ! npm install -g playwright-core 2>&1; then
        echo -e "  ${YELLOW}[WARN]${NC} playwright-core installation failed (non-critical)"
        rc=1
    fi
    # Set Playwright environment variables if Chromium is available
    for bin in "$PREFIX/bin/chromium-browser" "$PREFIX/bin/chromium"; do
        [ -x "$bin" ] && CHROMIUM_BIN="$bin" && break
    done
    if [ -n "$CHROMIUM_BIN" ]; then
        local PW_MARKER_START="# >>> Playwright >>>"
        local PW_MARKER_END="# <<< Playwright <<<"
        # --tools-only never edits ~/.bashrc
        if [ "$OA_MODE" = full ] && ! grep -qF "$PW_MARKER_START" "$HOME/.bashrc" 2>/dev/null; then
            cat >> "$HOME/.bashrc" << PWENV

${PW_MARKER_START}
export PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH="$CHROMIUM_BIN"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
${PW_MARKER_END}
PWENV
        fi
        echo -e "  ${GREEN}✓${NC} Playwright (env: PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=$CHROMIUM_BIN)"
    else
        echo -e "  ${GREEN}✓${NC} Playwright (install Chromium later via 'oa --install' for full setup)"
    fi
    return $rc
}
tool_install_claude_code() {
    echo "  Installing Claude Code..."
    if npm install -g @anthropic-ai/claude-code 2>&1; then
        # The native build is a glibc binary: Android has no /lib/ld-linux-aarch64.so.1,
        # so run it through the glibc loader of this setup (LD_PRELOAD, i.e. termux-exec, must
        # not be inherited by the glibc process).
        local _cc_exe _cc_ld="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
        _cc_exe="$(npm root -g)/@anthropic-ai/claude-code/bin/claude.exe"
        if ! timeout 30 claude --version >/dev/null 2>&1 && [ -f "$_cc_exe" ] && [ -x "$_cc_ld" ]; then
            printf '#!%s/bin/bash\nexec env -u LD_PRELOAD "%s" --library-path "%s" "%s" "$@"\n' \
                "$PREFIX" "$_cc_ld" "$PREFIX/glibc/lib" "$_cc_exe" > "$PREFIX/bin/claude.tmp" \
                && chmod +x "$PREFIX/bin/claude.tmp" && mv -f "$PREFIX/bin/claude.tmp" "$PREFIX/bin/claude"
        fi
        if timeout 30 claude --version >/dev/null 2>&1; then
            echo -e "  ${GREEN}✓${NC} Claude Code"
        else
            echo -e "  ${YELLOW}[WARN]${NC} Claude Code installed, but its native binary does not run on this setup yet (support is planned)"
            # installed, but not usable: --tools-only reports this as "verify" (tool_verify decides)
            return 0
        fi
    else
        echo -e "  ${YELLOW}[WARN]${NC} Claude Code installation failed (non-critical) — skipped"
        return 1
    fi
}
tool_install_gemini_cli() {
    echo "  Installing Gemini CLI..."
    if npm install -g @google/gemini-cli 2>&1; then
        echo -e "  ${GREEN}✓${NC} Gemini CLI"
    else
        echo -e "  ${YELLOW}[WARN]${NC} Gemini CLI installation failed (non-critical) — skipped"
        return 1
    fi
}
tool_install_codex_cli() {
    echo "  Installing Codex CLI (Termux)..."
    # The package declares os=android, but this setup's glibc Node reports "linux", so
    # npm refuses it (EBADPLATFORM). The binary inside is a bionic build for this
    # prefix: retry with --force for this one package only.
    local _codex_out _codex_ok=false
    if _codex_out=$(npm install -g @mmmbuto/codex-cli-termux 2>&1); then
        printf '%s\n' "$_codex_out"
        _codex_ok=true
    elif printf '%s' "$_codex_out" | grep -q EBADPLATFORM; then
        npm install -g --force @mmmbuto/codex-cli-termux 2>&1 && _codex_ok=true
    else
        printf '%s\n' "$_codex_out"
    fi
    if [ "$_codex_ok" = true ]; then
        # Create codex CLI wrapper (DioNanos fork launcher fix)
        local _codex_bin="$PREFIX/bin/codex"
        local _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
        if [ -f "$_codex_pkg/codex.bin" ]; then
            [ -L "$_codex_bin" ] && rm -f "$_codex_bin"
            printf '#!%s/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="$PKG_BIN:${LD_LIBRARY_PATH:-}"\nexec "$PKG_BIN/codex.bin" "$@"\n' \
                "$PREFIX" "$_codex_pkg" > "$_codex_bin"
            chmod +x "$_codex_bin"
        fi
        echo -e "  ${GREEN}✓${NC} Codex CLI (Termux)"
    else
        echo -e "  ${YELLOW}[WARN]${NC} Codex CLI (Termux) installation failed (non-critical) — skipped"
        echo "         The package targets Termux's Android Node.js; support for this setup is planned."
        return 1
    fi
}

# Fix shebangs in npm global CLIs (kept in sync with scripts/lib.sh fix_npm_global_shebangs())
fix_npm_shebangs() {
    local _js
    for _js in "$PREFIX/lib/node_modules"/*/bin/*.js \
               "$PREFIX/lib/node_modules"/@*/*/bin/*.js; do
        [ -f "$_js" ] || continue
        head -1 "$_js" | grep -q '^#!/usr/bin/env node$' || continue
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$_js"
    done
}

# ─── --tools-only: the app's tools screen installs tools through the same code ───
# Usage: post-setup.sh --tools-only <tool>...   (ids: see OA_TOOL_IDS)
# Output: progress lines, then one "TOOL_RESULT <id> ok" or "TOOL_RESULT <id> fail <reason>"
# per tool. Result file (the source of truth for the app; an older bundled copy of this
# script does not know this mode): $OCA_DIR/tools-result.conf, KEY=VALUE lines, never
# executed — schema, run (start time), <id>=ok|failed:<reason>, error=<reason> when the run
# could not start or finish, exit=<code>. "ok" means the tool ran (<tool> --version), not
# just that its files were unpacked. Exit code: 0 all ok, 1 some failed, 2 usage / package
# list could not be verified / another run is active. Asks no questions.
OA_TOOLS_RESULTS=""
OA_TOOLS_RUN=""
OA_TOOLS_LOCK_OWNED=false
OA_TOOLS_SIGNAL_CODE=""
OA_TOOLS_CURRENT=""      # id being installed (its leftover links are cleaned if the run is interrupted)

# An interrupted or failed `npm install -g` can leave the tool's bin link behind with
# no package behind it. Remove such a link, and only such a link: it must be one of the
# tool's own bin names, a symlink, broken, and point into the tool's own package folder.
# A regular file, a working link or another tool's link is never touched.
clean_broken_npm_links() {
    local entries e bin pkg link target
    case "$1" in
        code-server) entries="code-server|code-server" ;;
        playwright) entries="playwright-core|playwright-core" ;;
        claude-code) entries="claude|@anthropic-ai/claude-code" ;;
        gemini-cli) entries="gemini|@google/gemini-cli" ;;
        codex-cli) entries="codex|@mmmbuto/codex-cli-termux codex-exec|@mmmbuto/codex-cli-termux" ;;
        *) return 0 ;;
    esac
    for e in $entries; do
        bin=${e%%|*}
        pkg=${e#*|}
        link="$PREFIX/bin/$bin"
        { [ -L "$link" ] && [ ! -e "$link" ]; } || continue
        target=$(readlink "$link" 2>/dev/null) || continue
        case "$target" in
            */node_modules/"$pkg"/*)
                rm -f "$link" 2>/dev/null && echo "  Removed a leftover link from an interrupted install: $link" ;;
        esac
    done
    return 0
}

tool_install() {
    local rc=0
    clean_broken_npm_links "$1"      # leftovers of an earlier interrupted run
    case "$1" in
        tmux) tool_install_tmux || rc=$? ;;
        ttyd) tool_install_ttyd || rc=$? ;;
        dufs) tool_install_dufs || rc=$? ;;
        android-tools) tool_install_android_tools || rc=$? ;;
        playwright) tool_install_playwright || rc=$? ;;
        claude-code) tool_install_claude_code || rc=$? ;;
        gemini-cli) tool_install_gemini_cli || rc=$? ;;
        codex-cli) tool_install_codex_cli || rc=$? ;;
        *) return 1 ;;
    esac
    [ "$rc" -eq 0 ] || clean_broken_npm_links "$1"
    return "$rc"
}

# 0 when the tool runs (or, for playwright-core, is installed as a library)
tool_verify() {
    case "$1" in
        tmux) timeout 10 tmux -V ;;
        ttyd) timeout 10 ttyd --version ;;
        dufs) timeout 10 dufs --version ;;
        android-tools) timeout 10 adb version ;;
        code-server) timeout 60 code-server --version ;;
        playwright) [ -f "$(npm root -g)/playwright-core/package.json" ] ;;
        claude-code) timeout 30 claude --version ;;
        gemini-cli) timeout 30 gemini --version ;;
        codex-cli) timeout 30 codex --version ;;
        *) return 1 ;;
    esac >/dev/null 2>&1
}

# Runs when --tools-only ends, however it ends: write the result file, free the lock
tools_finish() {
    local code=$?
    trap '' TERM HUP INT    # a second signal must not cut the result file short
    trap - EXIT
    [ -z "$OA_TOOLS_SIGNAL_CODE" ] || code=$OA_TOOLS_SIGNAL_CODE
    if [ "$OA_TOOLS_LOCK_OWNED" = true ]; then
        local f="$OCA_DIR/tools-result.conf"
        rm -rf "$f.tmp" 2>/dev/null || true
        {
            printf 'schema=1\nrun=%s\n' "$OA_TOOLS_RUN"
            [ -z "$OA_TOOLS_ERROR" ] || printf 'error=%s\n' "$OA_TOOLS_ERROR"
            printf '%s' "$OA_TOOLS_RESULTS"
            printf 'exit=%s\n' "$code"
        } > "$f.tmp" 2>/dev/null && mv -f "$f.tmp" "$f" 2>/dev/null || true
        [ -z "$OA_TOOLS_CURRENT" ] || clean_broken_npm_links "$OA_TOOLS_CURRENT"
        cleanup_downloads
        release_tools_lock
    fi
    exit "$code"
}

run_tools_only() {
    local id ids=() inst_rc ok_count=0 fail_count=0 index_ready=false npm_ready=false
    OA_INDEX_FAIL_EXIT=2

    # A signal before the lock exists just ends the run (no result file to write yet)
    trap 'OA_TOOLS_ERROR=interrupted; OA_TOOLS_SIGNAL_CODE=143; exit 143' TERM
    trap 'OA_TOOLS_ERROR=interrupted; OA_TOOLS_SIGNAL_CODE=129; exit 129' HUP
    trap 'OA_TOOLS_ERROR=interrupted; OA_TOOLS_SIGNAL_CODE=130; exit 130' INT
    # One run at a time; a busy run leaves the other run's result alone
    local _lock_rc=0
    acquire_tools_lock || _lock_rc=$?
    if [ "$_lock_rc" -eq 1 ]; then
        echo "Another tools run is in progress. Try again when it has finished." >&2
        exit 2
    elif [ "$_lock_rc" -ne 0 ]; then
        echo "Could not create the tools lock in $OCA_DIR (error=lock)." >&2
        exit 2
    fi
    OA_TOOLS_LOCK_OWNED=true
    trap tools_finish EXIT
    OA_TOOLS_RUN=$(date +%s)
    rm -f "$OCA_DIR/tools-result.conf"

    # From here every outcome, including a bad call, leaves a fresh result file
    if [ "${#OA_TOOL_ARGS[@]}" -eq 0 ]; then
        echo "Usage: post-setup.sh --tools-only <tool>...   (tools: $OA_TOOL_IDS)" >&2
        OA_TOOLS_ERROR="usage"
        exit 2
    fi
    for id in "${OA_TOOL_ARGS[@]}"; do
        local _known=false _k
        for _k in $OA_TOOL_IDS; do [ "$_k" = "$id" ] && _known=true; done
        if [ "$_known" = false ]; then
            echo "Unknown tool: $id (tools: $OA_TOOL_IDS)" >&2; OA_TOOLS_ERROR="usage"; exit 2
        fi
        case " ${ids[*]:-} " in *" $id "*) ;; *) ids+=("$id") ;; esac
    done

    mkdir -p "$DEB_DIR" "$PKG_DIR" || { OA_TOOLS_ERROR="env"; exit 2; }
    export PATH="$BIN_DIR:$NODE_DIR/bin:$PATH"
    echo -e "▸ ${YELLOW}Installing tools${NC}: ${ids[*]}"
    # Verify the package list before any result line when a package tool still has to be
    # installed, so a failed signature chain ends the run with exit 2 and no TOOL_RESULT
    for id in "${ids[@]}"; do
        case "$id" in
            tmux|ttyd|dufs|android-tools)
                if [ "$index_ready" = false ] && ! tool_verify "$id"; then
                    fetch_verified_index
                    index_ready=true
                fi
                ;;
        esac
    done
    for id in "${ids[@]}"; do
        if tool_verify "$id"; then
            echo "  $id is already installed and runs"
            echo "TOOL_RESULT $id ok"
            OA_TOOLS_RESULTS="${OA_TOOLS_RESULTS}${id}=ok"$'\n'
            ok_count=$((ok_count + 1))
            continue
        fi
        case "$id" in
            tmux|ttyd|dufs|android-tools)
                if [ "$index_ready" = false ]; then
                    fetch_verified_index
                    index_ready=true
                fi
                ;;
            *)
                if [ "$npm_ready" = false ]; then
                    resolve_npm_registry || true
                    npm_ready=true
                fi
                ;;
        esac
        inst_rc=0
        OA_TOOLS_CURRENT="$id"
        tool_install "$id" || inst_rc=1
        OA_TOOLS_CURRENT=""
        if tool_verify "$id"; then
            echo "TOOL_RESULT $id ok"
            OA_TOOLS_RESULTS="${OA_TOOLS_RESULTS}${id}=ok"$'\n'
            ok_count=$((ok_count + 1))
        else
            local reason="verify"
            [ "$inst_rc" -eq 0 ] || reason="install"
            echo "TOOL_RESULT $id fail $reason"
            OA_TOOLS_RESULTS="${OA_TOOLS_RESULTS}${id}=failed:${reason}"$'\n'
            fail_count=$((fail_count + 1))
        fi
    done
    fix_npm_shebangs || true      # tools mode: a failing sed -i must not change the exit code
    echo "  Done: $ok_count ok, $fail_count failed"
    [ "$fail_count" -eq 0 ] || exit 1
    exit 0
}

if [ "$OA_MODE" = "tools" ]; then
    run_tools_only
fi

# ─── [1/7] Install essential packages ─────────
oa_stage 1
echo -e "▸ ${YELLOW}[1/7]${NC} Installing essential packages..."
mkdir -p "$DEB_DIR" "$PKG_DIR"
fetch_verified_index

# Packages to install via dpkg-deb (dependency order, only those missing from bootstrap)
DEB_PACKAGES=(
    libexpat          # git dep
    pcre2             # git dep
    git               # for npm/openclaw
)

TOTAL=${#DEB_PACKAGES[@]}
COUNT=0
for pkg in "${DEB_PACKAGES[@]}"; do
    COUNT=$((COUNT + 1))
    filename=$(get_deb_filename "$pkg")
    if [ -z "$filename" ]; then
        echo -e "  ${RED}✗${NC} Package '$pkg' not found in index"
        continue
    fi
    echo "  [$COUNT/$TOTAL] $pkg"
    install_deb "$filename" || {
        echo -e "  ${RED}✗${NC} Could not install '$pkg' (required). Check your network connection and restart the app to retry."
        OA_TOOLS_ERROR=deb
        exit 1
    }
done

# Make sure newly extracted binaries are executable
chmod +x "$PREFIX/bin/"* 2>/dev/null || true

# Verify git
if [ -f "$PREFIX/bin/git" ]; then
    echo -e "  ${GREEN}✓${NC} git $(git --version 2>/dev/null | head -1)"
else
    echo -e "  ${RED}✗${NC} git not found after extraction"
    OA_TOOLS_ERROR=git
    exit 1
fi

# ─── [2/7] glibc runtime ─────────────────────
oa_stage 2
echo -e "▸ ${YELLOW}[2/7]${NC} Installing glibc runtime..."

if [ -x "$GLIBC_LDSO" ]; then
    echo -e "  ${GREEN}[SKIP]${NC} glibc already installed"
else
    mkdir -p "$PREFIX/glibc"

    # Download glibc packages directly from the pacman repo (no pacman needed).
    # gcc-libs-glibc provides libstdc++.so.6 needed by Node.js.
    # The package database must carry a valid signature (checked with the bootstrap's
    # gpgv and the termux-pacman key); mirrors are tried in turn, and database and
    # packages come from the same mirror. There is no unsigned fallback.
    GLIBC_PKGS=(glibc gcc-libs-glibc)
    GLIBC_FILES=()
    echo "  Downloading glibc + gcc-libs (~34MB)..."
    if ! fetch_glibc_packages; then
        OA_TOOLS_ERROR=glibc
        exit 1
    fi
    for _file in "${GLIBC_FILES[@]}"; do
        install_pacman_pkg "$_file" "$PREFIX/glibc"
    done

    # Verify linker
    if [ ! -f "$GLIBC_LDSO" ]; then
        echo -e "  ${RED}✗${NC} glibc linker not found at $GLIBC_LDSO"
        OA_TOOLS_ERROR=glibc
        exit 1
    fi
    chmod +x "$GLIBC_LDSO"
    mkdir -p "$OCA_DIR"
    touch "$OCA_DIR/.glibc-arch"
    echo -e "  ${GREEN}✓${NC} glibc installed"
fi

# Install supplementary glibc libraries (libcap etc.)
_GLIBC_LIBS_SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/patches/glibc-libs"
if [ -d "$PREFIX/glibc/lib" ] && [ -d "$_GLIBC_LIBS_SRC" ]; then
    for _lib in "$_GLIBC_LIBS_SRC"/*.so.*; do
        [ -f "$_lib" ] || continue
        _fn=$(basename "$_lib")
        if [ ! -f "$PREFIX/glibc/lib/$_fn" ]; then
            cp "$_lib" "$PREFIX/glibc/lib/$_fn"
            _sn=$(echo "$_fn" | sed -E 's/^(lib[^.]+\.so\.[0-9]+)\..*/\1/')
            [ "$_sn" != "$_fn" ] && ln -sf "$_fn" "$PREFIX/glibc/lib/$_sn"
            echo -e "  ${GREEN}✓${NC} Installed $_sn"
        fi
    done
fi

# Ensure glibc /etc/hosts exists (localhost resolution)
if [ -d "$PREFIX/glibc/etc" ] && [ ! -f "$PREFIX/glibc/etc/hosts" ]; then
    cat > "$PREFIX/glibc/etc/hosts" <<'HOSTS'
127.0.0.1 localhost localhost.localdomain
::1 localhost ip6-localhost ip6-loopback
HOSTS
    echo -e "  ${GREEN}✓${NC} Created glibc /etc/hosts"
fi
echo -e "  Linker: $GLIBC_LDSO"

# ─── [3/7] Node.js ──────────────────────────
oa_stage 3
echo -e "▸ ${YELLOW}[3/7]${NC} Installing Node.js v${NODE_VERSION}..."
NODE_NEW="$OCA_DIR/node.new"
NODE_OLD="$OCA_DIR/node.old"
NODE_TRASH="$OCA_DIR/node.trash"

# Recover an unfinished swap: node.old exists only until the new install is
# verified, so if it is still here the previous run stopped mid-swap — go back to it.
if [ -d "$NODE_OLD" ]; then
    rm -rf "${NODE_DIR:?}"
    mv "$NODE_OLD" "$NODE_DIR"
fi
rm -rf "${NODE_NEW:?}" "${NODE_TRASH:?}"

# Node wrapper: grun-style execution (same text as scripts/install-nodejs.sh write_node_wrapper).
write_node_wrapper() {
    mkdir -p "$BIN_DIR"
    # ─── node wrapper ───
    cat > "$BIN_DIR/node.tmp" << WRAPPER
#!${PREFIX}/bin/bash
[ -n "\$LD_PRELOAD" ] && export _OA_ORIG_LD_PRELOAD="\$LD_PRELOAD"
unset LD_PRELOAD
export _OA_WRAPPER_PATH="$BIN_DIR/node"
# OpenClaw 2026.9.x's native fs-safe helper probes the openat2 system call, which
# Android's app sandbox answers by killing the process (SIGSYS). This switch of the
# helper makes it use its own fallback for that one call and keep everything else
# native (no-clobber moves, state migrations). A mode the user set stays untouched.
# (The helper only recognises the value 1 here; to turn native off use FS_SAFE_NATIVE_MODE=off.)
export FS_SAFE_TEST_NO_OPENAT2="\${FS_SAFE_TEST_NO_OPENAT2:-1}"
# OpenClaw's gateway can start a self-update through this wrapper (not through the
# openclaw command), which would replace the version pair this app was verified with.
export OPENCLAW_NO_AUTO_UPDATE="\${OPENCLAW_NO_AUTO_UPDATE:-1}"
_oa_found=false
_oa_rest=()
for _oa_arg in "\$@"; do
    if [ "\$_oa_found" = true ]; then
        _oa_rest+=("\$_oa_arg")
    else
        case "\$_oa_arg" in
            */openclaw/openclaw.mjs|*/openclaw/dist/index.js|*/openclaw/dist/index.mjs|*/openclaw/dist/entry.js|*/openclaw/dist/entry.mjs|*/bin/openclaw) _oa_found=true ;;
        esac
    fi
done
if [ "\$_oa_found" = true ]; then
    _oa_block=false
    for _oa_arg in "\${_oa_rest[@]}"; do
        [ "\$_oa_arg" = "--update" ] && _oa_block=true && break
    done
    if [ "\$_oa_block" = false ]; then
        _oa_skip=false
        _oa_next=false
        for _oa_arg in "\${_oa_rest[@]}"; do
            if [ "\$_oa_next" = true ]; then
                [ "\$_oa_arg" = "status" ] && _oa_block=false
                break
            fi
            if [ "\$_oa_skip" = true ]; then _oa_skip=false; continue; fi
            case "\$_oa_arg" in
                --profile|--container|--log-level) _oa_skip=true ;;
                -*) ;;
                update) _oa_block=true; _oa_next=true ;;
                *) break ;;
            esac
        done
    fi
    if [ "\$_oa_block" = true ]; then
        echo "[BLOCKED] OpenClaw is pinned to the version verified by OpenClaw on Android." >&2
        echo "          Run 'oa --update' to update safely. ('openclaw update status' is allowed.)" >&2
        exit 1
    fi
fi
unset _oa_found _oa_rest _oa_arg _oa_block _oa_skip _oa_next
_OA_COMPAT="\$HOME/.openclaw-android/lib/glibc-compat.js"
[ -s "\$_OA_COMPAT" ] || _OA_COMPAT="\$HOME/.openclaw-android/patches/glibc-compat.js"
if [ -f "\$_OA_COMPAT" ]; then
    case "\${NODE_OPTIONS:-}" in
        *glibc-compat.js*) ;;
        *) export NODE_OPTIONS="\${NODE_OPTIONS:+\$NODE_OPTIONS }-r \$_OA_COMPAT" ;;
    esac
fi
# All arguments go to node.real unchanged. ld.so stops parsing its own options at
# the program path, so leading --options (and their values) are not misread; moving
# them to NODE_OPTIONS stripped values such as "--import X" and "--env-file X".
exec "$GLIBC_LDSO" --library-path "$PREFIX/glibc/lib" "$NODE_DIR/bin/node.real" "\$@"
WRAPPER
    # ─── end node wrapper ───
    chmod +x "$BIN_DIR/node.tmp"
    # Leave an identical wrapper alone; replace a stale one (never edit it in place)
    if [ -f "$BIN_DIR/node" ] && cmp -s "$BIN_DIR/node.tmp" "$BIN_DIR/node"; then
        rm -f "$BIN_DIR/node.tmp"
    else
        mv -f "$BIN_DIR/node.tmp" "$BIN_DIR/node"
    fi
}

_NODE_CMD=""
if [ -x "$BIN_DIR/node" ]; then _NODE_CMD="$BIN_DIR/node"
elif [ -f "$NODE_DIR/bin/node.real" ] && [ -x "$NODE_DIR/bin/node" ]; then _NODE_CMD="$NODE_DIR/bin/node"
fi
INSTALLED_VER=""
if [ -n "$_NODE_CMD" ] && "$_NODE_CMD" --version &>/dev/null; then
    INSTALLED_VER=$("$_NODE_CMD" --version 2>/dev/null | sed 's/^v//')
fi
# Converge to the pinned version: reinstall unless exactly the pinned version
if [ "$INSTALLED_VER" = "$NODE_VERSION" ]; then
    echo -e "  ${GREEN}[SKIP]${NC} Node.js already installed (v$INSTALLED_VER)"
    # Repair wrappers in BIN_DIR (safe from npm overwrites)
    mkdir -p "$BIN_DIR"
    # The node wrapper is refreshed too: an interrupted setup that is run again must not keep an old one (a finished app gets it from oa --update)
    [ -f "$NODE_DIR/bin/node.real" ] && write_node_wrapper
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
        cat > "$BIN_DIR/npm.tmp" << NPMWRAP
#!$PREFIX/bin/bash
# __NPM_GUARD__
"$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" "\$@"
_npm_exit=\$?
# Re-patch openclaw CLI wrapper after a global install/update of openclaw.
# The platform's version-pin guard generator writes it when present.
case "\$*" in *-g*openclaw*|*--global*openclaw*|*openclaw*-g*|*openclaw*--global*)
    _oc_write=false
    for _oc_arg in "\$@"; do
        case "\$_oc_arg" in install|i|in|ins|inst|insta|instal|isnt|isnta|isntal|isntall|add|update|up|upgrade|udpate|ci|clean-install|link|ln) _oc_write=true ;; esac
    done
    _oc_bin="$PREFIX/bin/openclaw"
    _oc_mjs="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
    _oc_shim="\$HOME/.openclaw-android/platforms/openclaw/openclaw-shim.sh"
    if [ "\$_oc_write" = true ] && [ -f "\$_oc_mjs" ]; then
        if [ -f "\$_oc_shim" ] && "$PREFIX/bin/bash" "\$_oc_shim" >/dev/null 2>&1; then
            :
        else
            [ -L "\$_oc_bin" ] && rm -f "\$_oc_bin"
            printf '#!$PREFIX/bin/bash\nexec "$BIN_DIR/node" "%s" "\$@"\n' "\$_oc_mjs" > "\$_oc_bin"
            chmod +x "\$_oc_bin"
        fi
    fi
    # The install replaced the OpenClaw package, so the Android patches (hard links are refused
    # here) are gone: apply them again (idempotent and quiet; the result is kept in hardlink-patch.state)
    _oc_hl="\$HOME/.openclaw-android/platforms/openclaw/patches/openclaw-patch-hardlink.sh"
    if [ "\$_oc_write" = true ] && [ -f "\$_oc_mjs" ] && [ -f "\$_oc_hl" ]; then
        PATH="$BIN_DIR:\$PATH" "$PREFIX/bin/bash" "\$_oc_hl" "$PREFIX/lib/node_modules/openclaw" >/dev/null 2>&1 || true
    fi
    ;;
esac
# Re-patch codex CLI wrapper after global install/update (DioNanos fork launcher fix)
case "\$*" in *codex-cli-termux*)
    _codex_bin="$PREFIX/bin/codex"
    _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
    if [ -f "\$_codex_pkg/codex.bin" ]; then
        [ -L "\$_codex_bin" ] && rm -f "\$_codex_bin"
        printf '#!$PREFIX/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="\$PKG_BIN:\${LD_LIBRARY_PATH:-}"\nexec "\$PKG_BIN/codex.bin" "\$@"\n' "\$_codex_pkg" > "\$_codex_bin"
        chmod +x "\$_codex_bin"
    fi
    ;;
esac
# Re-patch Claude Code launcher after global install/update: its native binary is a glibc
# build that Android cannot exec directly, so run it through the glibc loader
case "\$*" in *claude-code*)
    _cc_bin="$PREFIX/bin/claude"
    _cc_exe="$PREFIX/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe"
    _cc_ld="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
    if [ -L "\$_cc_bin" ] && [ -f "\$_cc_exe" ] && [ -x "\$_cc_ld" ]; then
        printf '#!$PREFIX/bin/bash\nexec env -u LD_PRELOAD "%s" --library-path "%s" "%s" "\$@"\n' "\$_cc_ld" "$PREFIX/glibc/lib" "\$_cc_exe" > "\$_cc_bin.tmp" \
            && chmod +x "\$_cc_bin.tmp" && mv -f "\$_cc_bin.tmp" "\$_cc_bin"
    fi
    ;;
esac
# Fix shebangs in npm global CLI entry points after global install
case "\$*" in *-g*|*--global*)
    for _js in $PREFIX/lib/node_modules/*/bin/*.js \
               $PREFIX/lib/node_modules/@*/*/bin/*.js; do
        [ -f "\$_js" ] || continue
        head -1 "\$_js" | grep -q '^#!/usr/bin/env node\$' || continue
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "\$_js"
    done
    ;;
esac
exit \$_npm_exit
NPMWRAP
        oa_inject_npm_guard "$BIN_DIR/npm.tmp"
        chmod +x "$BIN_DIR/npm.tmp"
        mv -f "$BIN_DIR/npm.tmp" "$BIN_DIR/npm"
    fi
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" ]; then
        cat > "$BIN_DIR/npx.tmp" << NPXWRAP
#!$PREFIX/bin/bash
exec "$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" "\$@"
NPXWRAP
        chmod +x "$BIN_DIR/npx.tmp"
        mv -f "$BIN_DIR/npx.tmp" "$BIN_DIR/npx"
    fi
    if [ -f "$NODE_DIR/bin/corepack" ] && head -1 "$NODE_DIR/bin/corepack" 2>/dev/null | grep -q '#!/usr/bin/env node'; then
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$NODE_DIR/bin/corepack"
    fi
else
    if [ -n "$INSTALLED_VER" ]; then
        echo "  Node.js v${INSTALLED_VER} -> v${NODE_VERSION} (pinned version)"
    fi
    NODE_DIST_BASE="https://nodejs.org/dist/v${NODE_VERSION}"
    NODE_TAR="node-v${NODE_VERSION}-linux-arm64"
    echo "  Downloading Node.js v${NODE_VERSION} (~25MB)..."
    if ! curl -fSL --max-time 300 \
        "${NODE_DIST_BASE}/${NODE_TAR}.tar.xz" \
        -o "$TMPDIR/${NODE_TAR}.tar.xz"; then
        rm -f "$TMPDIR/${NODE_TAR}.tar.xz"
        echo -e "  ${RED}✗${NC} Node.js download failed — check the network and restart the app to retry"
        OA_TOOLS_ERROR=node-download
        exit 1
    fi

    # Verify sha256 against SHASUMS256.txt from the same source
    if ! curl -fsSL --max-time 60 "${NODE_DIST_BASE}/SHASUMS256.txt" -o "$TMPDIR/node-SHASUMS256.txt"; then
        rm -f "$TMPDIR/${NODE_TAR}.tar.xz" "$TMPDIR/node-SHASUMS256.txt"
        echo -e "  ${RED}✗${NC} Node.js checksum list download failed — check the network and restart the app to retry"
        OA_TOOLS_ERROR=node-download
        exit 1
    fi
    _expected=$(awk -v f="${NODE_TAR}.tar.xz" '$2 == f { print $1; exit }' "$TMPDIR/node-SHASUMS256.txt")
    _actual=$(sha256sum "$TMPDIR/${NODE_TAR}.tar.xz" | awk '{ print $1 }')
    rm -f "$TMPDIR/node-SHASUMS256.txt"
    if [ -z "$_expected" ] || [ "$_expected" != "$_actual" ]; then
        rm -f "$TMPDIR/${NODE_TAR}.tar.xz"
        echo -e "  ${RED}✗${NC} Node.js checksum mismatch — restart the app to retry"
        OA_TOOLS_ERROR=node-checksum
        exit 1
    fi

    # Extract into staging and test-run before touching the current install
    echo "  Extracting..."
    mkdir -p "$NODE_NEW"
    tar -xJf "$TMPDIR/${NODE_TAR}.tar.xz" -C "$NODE_NEW" --strip-components=1
    rm -f "$TMPDIR/${NODE_TAR}.tar.xz"

    # Move original binary → node.real
    mv "$NODE_NEW/bin/node" "$NODE_NEW/bin/node.real"

    _staged=$(env -u LD_PRELOAD -u NODE_OPTIONS \
        "$GLIBC_LDSO" --library-path "$PREFIX/glibc/lib" "$NODE_NEW/bin/node.real" --version 2>/dev/null) || _staged=""
    if [ "$_staged" != "v$NODE_VERSION" ]; then
        rm -rf "${NODE_NEW:?}"
        echo -e "  ${RED}✗${NC} Extracted Node.js does not run (got: '${_staged}')"
        OA_TOOLS_ERROR=node-run
        exit 1
    fi

    # Carry over user additions from a previous install (kept in sync with
    # scripts/install-nodejs.sh) — npm/corepack come fresh from the tarball
    if [ -d "$NODE_DIR" ]; then
        for _entry in "$NODE_DIR/bin"/* "$NODE_DIR/lib/node_modules"/*; do
            [ -e "$_entry" ] || [ -L "$_entry" ] || continue
            _name=$(basename "$_entry")
            case "$_name" in node|node.real|npm|npx|corepack) continue ;; esac
            _dest="$NODE_NEW/${_entry#"$NODE_DIR"/}"
            if [ ! -e "$_dest" ] && [ ! -L "$_dest" ]; then
                cp -a "$_entry" "$_dest"
            fi
        done
        mv "$NODE_DIR" "$NODE_OLD"
    fi
    mv "$NODE_NEW" "$NODE_DIR"

    # Create grun-style node wrapper in BIN_DIR (safe from npm overwrites)
    write_node_wrapper

    # Create npm/npx wrappers in BIN_DIR
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
        cat > "$BIN_DIR/npm.tmp" << NPMWRAP
#!$PREFIX/bin/bash
# __NPM_GUARD__
"$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" "\$@"
_npm_exit=\$?
# Re-patch openclaw CLI wrapper after a global install/update of openclaw.
# The platform's version-pin guard generator writes it when present.
case "\$*" in *-g*openclaw*|*--global*openclaw*|*openclaw*-g*|*openclaw*--global*)
    _oc_write=false
    for _oc_arg in "\$@"; do
        case "\$_oc_arg" in install|i|in|ins|inst|insta|instal|isnt|isnta|isntal|isntall|add|update|up|upgrade|udpate|ci|clean-install|link|ln) _oc_write=true ;; esac
    done
    _oc_bin="$PREFIX/bin/openclaw"
    _oc_mjs="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
    _oc_shim="\$HOME/.openclaw-android/platforms/openclaw/openclaw-shim.sh"
    if [ "\$_oc_write" = true ] && [ -f "\$_oc_mjs" ]; then
        if [ -f "\$_oc_shim" ] && "$PREFIX/bin/bash" "\$_oc_shim" >/dev/null 2>&1; then
            :
        else
            [ -L "\$_oc_bin" ] && rm -f "\$_oc_bin"
            printf '#!$PREFIX/bin/bash\nexec "$BIN_DIR/node" "%s" "\$@"\n' "\$_oc_mjs" > "\$_oc_bin"
            chmod +x "\$_oc_bin"
        fi
    fi
    # The install replaced the OpenClaw package, so the Android patches (hard links are refused
    # here) are gone: apply them again (idempotent and quiet; the result is kept in hardlink-patch.state)
    _oc_hl="\$HOME/.openclaw-android/platforms/openclaw/patches/openclaw-patch-hardlink.sh"
    if [ "\$_oc_write" = true ] && [ -f "\$_oc_mjs" ] && [ -f "\$_oc_hl" ]; then
        PATH="$BIN_DIR:\$PATH" "$PREFIX/bin/bash" "\$_oc_hl" "$PREFIX/lib/node_modules/openclaw" >/dev/null 2>&1 || true
    fi
    ;;
esac
# Re-patch codex CLI wrapper after global install/update (DioNanos fork launcher fix)
case "\$*" in *codex-cli-termux*)
    _codex_bin="$PREFIX/bin/codex"
    _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
    if [ -f "\$_codex_pkg/codex.bin" ]; then
        [ -L "\$_codex_bin" ] && rm -f "\$_codex_bin"
        printf '#!$PREFIX/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="\$PKG_BIN:\${LD_LIBRARY_PATH:-}"\nexec "\$PKG_BIN/codex.bin" "\$@"\n' "\$_codex_pkg" > "\$_codex_bin"
        chmod +x "\$_codex_bin"
    fi
    ;;
esac
# Re-patch Claude Code launcher after global install/update: its native binary is a glibc
# build that Android cannot exec directly, so run it through the glibc loader
case "\$*" in *claude-code*)
    _cc_bin="$PREFIX/bin/claude"
    _cc_exe="$PREFIX/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe"
    _cc_ld="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
    if [ -L "\$_cc_bin" ] && [ -f "\$_cc_exe" ] && [ -x "\$_cc_ld" ]; then
        printf '#!$PREFIX/bin/bash\nexec env -u LD_PRELOAD "%s" --library-path "%s" "%s" "\$@"\n' "\$_cc_ld" "$PREFIX/glibc/lib" "\$_cc_exe" > "\$_cc_bin.tmp" \
            && chmod +x "\$_cc_bin.tmp" && mv -f "\$_cc_bin.tmp" "\$_cc_bin"
    fi
    ;;
esac
# Fix shebangs in npm global CLI entry points after global install
case "\$*" in *-g*|*--global*)
    for _js in $PREFIX/lib/node_modules/*/bin/*.js \
               $PREFIX/lib/node_modules/@*/*/bin/*.js; do
        [ -f "\$_js" ] || continue
        head -1 "\$_js" | grep -q '^#!/usr/bin/env node\$' || continue
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "\$_js"
    done
    ;;
esac
exit \$_npm_exit
NPMWRAP
        oa_inject_npm_guard "$BIN_DIR/npm.tmp"
        chmod +x "$BIN_DIR/npm.tmp"
        mv -f "$BIN_DIR/npm.tmp" "$BIN_DIR/npm"
    fi
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" ]; then
        cat > "$BIN_DIR/npx.tmp" << NPXWRAP
#!$PREFIX/bin/bash
exec "$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" "\$@"
NPXWRAP
        chmod +x "$BIN_DIR/npx.tmp"
        mv -f "$BIN_DIR/npx.tmp" "$BIN_DIR/npx"
    fi
    # corepack: shebang patch only
    if [ -f "$NODE_DIR/bin/corepack" ] && head -1 "$NODE_DIR/bin/corepack" 2>/dev/null | grep -q '#!/usr/bin/env node'; then
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$NODE_DIR/bin/corepack"
    fi

    # Configure npm
    export PATH="$BIN_DIR:$NODE_DIR/bin:$PATH"
    "$BIN_DIR/npm" config set script-shell "$PREFIX/bin/sh" 2>/dev/null || true

    # Verify (restore the previous install if the new one does not run)
    NODE_VER=$("$BIN_DIR/node" --version 2>/dev/null) || NODE_VER=""
    NPM_VER=$("$BIN_DIR/npm" --version 2>/dev/null) || NPM_VER=""
    if [ "$NODE_VER" != "v$NODE_VERSION" ] || [ -z "$NPM_VER" ]; then
        rm -rf "${NODE_DIR:?}"
        if [ -d "$NODE_OLD" ]; then
            mv "$NODE_OLD" "$NODE_DIR"
        fi
        echo -e "  ${RED}✗${NC} Node.js verification failed (node: '${NODE_VER}', npm: '${NPM_VER}')"
        OA_TOOLS_ERROR=node-verify
        exit 1
    fi
    if [ -d "$NODE_OLD" ]; then
        mv "$NODE_OLD" "$NODE_TRASH"
        rm -rf "${NODE_TRASH:?}"
    fi
    echo -e "  ${GREEN}✓${NC} Node.js $NODE_VER (glibc)"
fi

# ─── [4/7] OpenClaw ─────────────────────────
oa_stage 4
echo -e "▸ ${YELLOW}[4/7]${NC} Installing OpenClaw..."
export PATH="$BIN_DIR:$NODE_DIR/bin:$PATH"

# Auto-detect GitHub mirror for restricted networks
resolve_repo_base

# Auto-detect npm registry (session-scoped via NPM_CONFIG_REGISTRY env var).
# Does NOT write to ~/.npmrc — see CHANGELOG v1.0.24.
resolve_npm_registry || true

# Force git to use HTTPS instead of SSH (no SSH client available).
# Preserve any existing user .gitconfig (name, email, aliases); only set our keys.
touch "$HOME/.gitconfig"
git config --global http.sslCAInfo "$PREFIX/etc/tls/cert.pem"
git config --global --unset-all url."https://github.com/".insteadOf 2>/dev/null || true
git config --global --add url."https://github.com/".insteadOf "ssh://git@github.com/"
git config --global --add url."https://github.com/".insteadOf "git@github.com:"

# ─── git wrapper functions ────────────────────
# The Termux git package ships the real binary as $PREFIX/bin/git (a regular file)
# and libexec/git-core/git as a symlink to it; older layouts had it the other way
# round. Our wrapper replaces bin/git, so the real binary is first kept as
# bin/git.real — it must never be the only copy that gets deleted, and the wrapper
# must never exec something that resolves back to itself.
GIT_BIN="$PREFIX/bin/git"
GIT_REAL="$PREFIX/bin/git.real"
GIT_MARKER="$PREFIX/bin/git.wrapper-installed"
# true when $1 is one of our wrappers (this version or an older one)
git_is_wrapper() { [ -f "$1" ] && grep -q 'is_clone=false' "$1" 2>/dev/null; }
# $1 is a regular file that is NOT a wrapper (callers check): true when it prints a git version.
# Never call this on something that might be a wrapper — the old wrapper loops forever.
git_runs() {
    local out
    [ -f "$1" ] || return 1
    git_is_wrapper "$1" && return 1
    # git reads the system/global config even for --version: probe it without them, so a
    # broken ~/.gitconfig cannot make a good binary look dead
    if command -v timeout >/dev/null 2>&1; then
        out=$(env HOME=/nonexistent XDG_CONFIG_HOME=/nonexistent GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null timeout 10 "$1" --version 2>/dev/null) || return 1
    else
        out=$(env HOME=/nonexistent XDG_CONFIG_HOME=/nonexistent GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null "$1" --version 2>/dev/null) || return 1
    fi
    case "$out" in "git version"*) return 0 ;; esac
    return 1
}
# print the path of the real git binary: a regular file, after resolving symlinks,
# that is not one of our wrappers. Fails when there is none. A binary freshly unpacked
# at bin/git (or via libexec/git-core/git) wins over an older bin/git.real.
git_find_real() {
    local c r
    for c in "$GIT_BIN" "$PREFIX/libexec/git-core/git" "$GIT_REAL"; do
        r=$(readlink -f "$c" 2>/dev/null) || continue
        if [ -f "$r" ] && [ ! -L "$r" ] && ! git_is_wrapper "$r" && git_runs "$r"; then
            printf '%s\n' "$r"
            return 0
        fi
    done
    return 1
}
# write the wrapper over $GIT_BIN (temp file + rename)
git_write_wrapper() {
    local tmp="$GIT_BIN.new.$$"
    {
        printf '#!%s/bin/bash\n' "$PREFIX"
        printf 'PFX="%s"\n' "$PREFIX"
        cat << 'ENDWRAP'
# openclaw-android git wrapper: drops --recurse-submodules (it opens a hardcoded
# com.termux path) and clears an existing clone target in a temp/cache dir (npm creates it first)
unset CDPATH
filtered=()
is_clone=false
for a in "$@"; do
  case "$a" in
    --recurse-submodules) ;;
    clone) is_clone=true; filtered+=("$a") ;;
    *) filtered+=("$a") ;;
  esac
done
if $is_clone; then
  for a in "${filtered[@]}"; do
    case "$a" in
      clone|--*|-*|http*|ssh*|git*|[0-9]) ;;
      *)
        # only temp/cache directories (npm creates its clone target first); a directory
        # the user named (a source repo, a --reference) must never be deleted
        if [ -d "$a" ]; then
          # compare resolved paths with resolved bases (/data/data is a link to /data/user/0)
          d=$(cd -P "$a" 2>/dev/null && pwd -P) || d=""
          ok=false
          for base in "$HOME/.npm" "$PFX/tmp" "${TMPDIR:-}" "${npm_config_cache:-}"; do
            [ -n "$base" ] || continue
            b=$(cd "$base" 2>/dev/null && pwd -P) || continue
            case "$d" in "$b"/*) ok=true ;; esac
          done
          if $ok; then
            case "$d" in
              */_cacache/tmp/git-clone*)
                # npm's own throw-away clone dir (it reuses it for its https -> ssh fallbacks,
                # so it may hold a failed attempt's files): safe to empty. Only the directory
                # directly under _cacache/tmp/ -- nothing nested below it
                case "${d##*/_cacache/tmp/}" in
                  */*) ;;
                  *) rm -rf "$a" ;;
                esac ;;
              *)
                # anything else only if it is EMPTY: a directory that already has content is
                # never deleted, whatever it is called
                if [ -z "$(ls -A "$a" 2>/dev/null)" ]; then rmdir "$a" 2>/dev/null; fi ;;
            esac
          fi
        fi ;;
    esac
  done
fi
ENDWRAP
        # exec -a keeps the name git was called by: git decides built-in commands
        # (git-upload-pack ...) from argv[0], and those names are links to this wrapper
        printf 'exec -a "${0##*/}" "%s" "${filtered[@]}"\n' "$GIT_REAL"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod +x "$tmp" && mv -f "$tmp" "$GIT_BIN" || { rm -f "$tmp"; return 1; }
}
# true when the wrapper is in place and points at an existing real binary
git_wrapper_ok() {
    git_is_wrapper "$GIT_BIN" && [ -x "$GIT_REAL" ] && ! git_is_wrapper "$GIT_REAL" \
        && grep -qF "exec -a \"\${0##*/}\" \"$GIT_REAL\"" "$GIT_BIN" \
        && git_real_runs
}
# the saved real binary prints a git version
git_real_runs() { git_runs "$GIT_REAL"; }
# run the installed wrapper once: it must print a git version (bounded when `timeout` exists)
git_selfcheck() {
    local out
    if command -v timeout >/dev/null 2>&1; then
        out=$(env HOME=/nonexistent XDG_CONFIG_HOME=/nonexistent GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null timeout 10 "$GIT_BIN" --version 2>/dev/null) || return 1
    else
        out=$(env HOME=/nonexistent XDG_CONFIG_HOME=/nonexistent GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null "$GIT_BIN" --version 2>/dev/null) || return 1
    fi
    case "$out" in "git version"*) return 0 ;; esac
    return 1
}
# keep the real binary as bin/git.real, write the wrapper, check it works.
# Returns 0 ok, 2 when there is no real binary to keep (caller must get one), 1 on failure.
git_install_wrapper() {
    local real
    real=$(git_find_real) || return 2
    if [ "$real" != "$GIT_REAL" ]; then
        cp -p "$real" "$GIT_REAL.tmp" && chmod +x "$GIT_REAL.tmp" && mv -f "$GIT_REAL.tmp" "$GIT_REAL" \
            || { rm -f "$GIT_REAL.tmp"; return 1; }
    fi
    chmod +x "$GIT_REAL" 2>/dev/null || true
    git_write_wrapper || return 1
    if ! git_selfcheck; then
        # The wrapper does not run: put the real binary back as bin/git so git still
        # works (without the wrapper) and the failure does not make things worse.
        if git_real_runs; then
            cp -p "$GIT_REAL" "$GIT_BIN.rb" && mv -f "$GIT_BIN.rb" "$GIT_BIN" || rm -f "$GIT_BIN.rb"
        fi
        return 1
    fi
    touch "$GIT_MARKER"
    return 0
}
# ─── end git wrapper functions ────────────────

# Git wrapper: replace $PREFIX/bin/git with a wrapper that:
#   1. Strips --recurse-submodules (triggers open() on hardcoded com.termux path)
#   2. Cleans existing target dirs before clone (npm's withTempDir creates dir first)
# npm caches git path at module load via which.sync('git'), so we must replace the binary.
# The real binary is kept as $PREFIX/bin/git.real (see the git wrapper functions above).
if git_wrapper_ok; then
    touch "$GIT_MARKER"
    echo -e "  ${GREEN}[SKIP]${NC} git wrapper already installed"
else
    echo "  Installing git wrapper (strips --recurse-submodules)..."
    git_rc=0
    git_install_wrapper || git_rc=$?
    if [ "$git_rc" -eq 2 ]; then
        # No real git binary on disk (an earlier broken install deleted it): fetch it again.
        echo "  Real git binary missing — downloading it again..."
        if _git_deb=$(get_deb_filename git) && [ -n "$_git_deb" ] && install_deb "$_git_deb"; then
            git_rc=0
            git_install_wrapper || git_rc=$?
        else
            git_rc=3
        fi
    fi
    if [ "$git_rc" -eq 0 ]; then
        echo -e "  ${GREEN}✓${NC} git wrapper installed"
    else
        if [ "$git_rc" -eq 3 ]; then
            echo -e "  ${RED}✗${NC} Could not download git. Check your network connection and restart the app to retry."
            OA_TOOLS_ERROR=git
        else
            echo -e "  ${RED}✗${NC} Could not set up git: the git wrapper would not run."
            echo "    Restart the app to try again, or report this."
            OA_TOOLS_ERROR=git-wrapper
        fi
        exit 1
    fi
fi

# `openclaw update` guard generator — byte-identical copy of
# platforms/openclaw/openclaw-shim.sh (this script is a single file). Saved where
# the npm wrapper looks for it, so any later `npm install -g openclaw` re-guards.
OC_SHIM_GEN="$OCA_DIR/platforms/openclaw/openclaw-shim.sh"
mkdir -p "$(dirname "$OC_SHIM_GEN")"
cat > "$OC_SHIM_GEN" << 'OPENCLAW_SHIM_SH'
#!/usr/bin/env bash
# openclaw-shim.sh — Write the version-pin guard at $PREFIX/bin/openclaw
#
# OpenClaw can replace itself (`openclaw update`, `openclaw --update`) with the
# latest npm release, which may require a newer Node.js than the pinned one and
# then refuses to start. The guard blocks those commands and passes everything
# else through to openclaw.mjs.
#
# `npm install -g openclaw` (and our npm wrapper) rewrite $PREFIX/bin/openclaw,
# so run this after every OpenClaw install (install.sh, update.sh, post-setup.sh).
# $PREFIX/bin is the one directory present in every PATH we launch from
# (Termux shell, app terminal, boot auto-start, app EnvironmentBuilder).
set -euo pipefail

: "${PREFIX:?PREFIX not set}"
: "${HOME:?HOME not set}"

OC_BIN="$PREFIX/bin/openclaw"
OC_MJS="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
OC_NODE="$HOME/.openclaw-android/bin/node"
[ -x "$OC_NODE" ] || OC_NODE="node"

if [ ! -f "$OC_MJS" ]; then
    echo -e "\033[1;33m[WARN]\033[0m openclaw.mjs not found — version guard not installed"
    exit 0
fi

OC_TMP="$OC_BIN.tmp.$$"
trap 'rm -f "$OC_TMP"' EXIT
cat > "$OC_TMP" << SHIM
#!$PREFIX/bin/bash
# openclaw — OpenClaw on Android version-pin guard.
# Generated by platforms/openclaw/openclaw-shim.sh; rewritten on every install/update.
# Blocks OpenClaw self-update; all other commands pass through unchanged.
_oa_block=false
for _oa_arg in "\$@"; do
    [ "\$_oa_arg" = "--update" ] && _oa_block=true && break
done
if [ "\$_oa_block" = false ]; then
    # Find the subcommand: skip global flags (and the values of those that take one).
    _oa_skip=false
    _oa_next=false
    for _oa_arg in "\$@"; do
        if [ "\$_oa_next" = true ]; then
            # Only the read-only "update status" is allowed through.
            [ "\$_oa_arg" = "status" ] && _oa_block=false
            break
        fi
        if [ "\$_oa_skip" = true ]; then _oa_skip=false; continue; fi
        case "\$_oa_arg" in
            --profile|--container|--log-level) _oa_skip=true ;;
            -*) ;;
            update) _oa_block=true; _oa_next=true ;;
            *) break ;;
        esac
    done
fi
if [ "\$_oa_block" = true ]; then
    echo "[BLOCKED] OpenClaw is pinned to the version verified by OpenClaw on Android." >&2
    echo "          Run 'oa --update' to update safely. ('openclaw update status' is allowed.)" >&2
    exit 1
fi
if [ ! -f "$OC_MJS" ]; then
    echo "[FAIL] OpenClaw is not installed ($OC_MJS missing). Run 'oa --update'." >&2
    exit 127
fi
exec "$OC_NODE" "$OC_MJS" "\$@"
SHIM
chmod +x "$OC_TMP"
# rename(2) replaces the npm symlink itself, never the file it points to
mv -f "$OC_TMP" "$OC_BIN"
echo -e "\033[0;32m[OK]\033[0m   openclaw version guard installed ($OC_BIN)"
OPENCLAW_SHIM_SH
chmod +x "$OC_SHIM_GEN"

# Converge to the pinned OpenClaw (same rule as platforms/openclaw/update.sh):
# install it whenever the installed version differs, in either direction.
OPENCLAW_DIR="$(npm root -g)/openclaw"
OC_CURRENT=""
if [ -f "$OPENCLAW_DIR/package.json" ]; then
    OC_CURRENT=$(node -p "require('$OPENCLAW_DIR/package.json').version" 2>/dev/null || echo "")
fi
OC_INSTALLED=false
if [ "$OC_CURRENT" = "$PLATFORM_NPM_PACKAGE_VERSION" ]; then
    echo -e "  ${GREEN}[SKIP]${NC} OpenClaw $OC_CURRENT already installed (pinned version)"
else
    # Clean npm cache tmp dir (leftover from previous failed installs)
    rm -rf "$HOME/.npm/_cacache/tmp" 2>/dev/null || true
    # A leftover guard with no package behind it makes npm fail with EEXIST
    if [ ! -f "$OPENCLAW_DIR/package.json" ] && [ -e "$PREFIX/bin/openclaw" ] && [ ! -L "$PREFIX/bin/openclaw" ]; then
        rm -f "$PREFIX/bin/openclaw"
    fi
    npm install -g "openclaw@$PLATFORM_NPM_PACKAGE_VERSION" --ignore-scripts 2>&1 || {
        echo -e "  ${RED}✗${NC} Could not install OpenClaw $PLATFORM_NPM_PACKAGE_VERSION. Check your network connection and restart the app to retry."
        OA_TOOLS_ERROR=npm-openclaw
        exit 1
    }
    OC_INSTALLED=true
    echo -e "  ${GREEN}✓${NC} OpenClaw $PLATFORM_NPM_PACKAGE_VERSION (${OC_CURRENT:-new install})"
fi

# Run the package postinstall that --ignore-scripts skipped (prunes stale dist
# files, applies bundled hotfixes) — only after a fresh package install.
if [ "$OC_INSTALLED" = true ] && [ -d "$OPENCLAW_DIR" ]; then
    echo "  Running OpenClaw postinstall..."
    (cd "$OPENCLAW_DIR" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs 2>/dev/null) || true
fi

# Hard links are denied on Android: let OpenClaw copy instead (its data migration and file
# publication fail otherwise). Same script as platforms/openclaw/patches/openclaw-patch-hardlink.sh
# (a pre-commit check keeps the two identical); runs after every OpenClaw install, before first use.
OC_HL_GEN="$OCA_DIR/platforms/openclaw/patches/openclaw-patch-hardlink.sh"
mkdir -p "$(dirname "$OC_HL_GEN")"
cat > "$OC_HL_GEN" << 'OPENCLAW_HARDLINK_SH'
#!/usr/bin/env bash
# openclaw-patch-hardlink.sh - make OpenClaw copy a file when a hard link is denied.
#
# Android (app and Termux alike) denies hard links (ln, fs.link, linkat) with EACCES. OpenClaw's
# fs-safe publishes files with strategy "link-or-copy": it tries a hard link first and copies only
# when the error code is in HARDLINK_FALLBACK_CODES (EPERM, EXDEV, ENOTSUP, EOPNOTSUPP, ENOSYS).
# EACCES is not in that list, so every such publication fails - among them the SQLite snapshot of
# `openclaw doctor --fix`, which migrates the data when OpenClaw is updated (the gateway then does
# not start). Two changes:
#   F1  add EACCES to HARDLINK_FALLBACK_CODES and to NATIVE_COPY_FALLBACK_CODES (the clone/reflink step
#       of the copy is refused with EACCES too); both are inlined in 3 bundles and in
#       node_modules/@openclaw/fs-safe
#   F1b dist/backup-create-*.mjs: strategy "link-required" -> "link-or-copy"
#       (so `openclaw backup create` works)
#
#   F3  B3_SESSION_ARCHIVE_PATCH: the session migration of 9.x (`doctor --fix`, "Legacy session store
#       requires migration") archives each old transcript with moveMigrationArtifact: a hard link
#       (strategy "link-required") and an `nlink == 2` check, then it removes the source. Hard links are
#       refused here (EACCES) and a copy would fail the check, so the move is done with rename(2)
#       instead (same file system: atomic, inode/mtime kept, so the later identity checks still hold;
#       "undo" renames back). Applied by wrapping the function in each bundle that defines it. The
#       wrapper only takes over when the link is refused with EACCES (or when an interrupted rename
#       is resumed); every other case runs OpenClaw's own code unchanged.
#
# THIS PATCH IS TIED TO THE PINNED OPENCLAW VERSION. At every pin bump re-run it on the new
# package and make sure nothing is reported as MISSING/NOMATCH (and test under
# .agent/tools/android-trap/trap_android.py). Files are read and written as bytes (latin1): the
# worker bundles are not clean UTF-8. Idempotent. A file that does not match is reported, never
# hidden: the data migration then fails again with the original EACCES message.
# Usage: openclaw-patch-hardlink.sh [--check] [--only-b3] [<openclaw package dir>]   (default: npm root -g)
# --only-b3: look at the session-archive patch only (for a package taken from the npm tarball, before it is installed)
# --check: change nothing, report what is still to do (exit 3 when something is, or does not match)
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

CHECK=0
ONLY_B3=0
ARG_DIR=""
for arg in "$@"; do
    case "$arg" in
        --check) CHECK=1 ;;
        --only-b3) ONLY_B3=1 ;;
        *) [ -n "$ARG_DIR" ] || ARG_DIR="$arg" ;;
    esac
done
OPENCLAW_DIR="${ARG_DIR:-$(npm root -g 2>/dev/null || true)/openclaw}"
STATE_FILE="${HOME:-/tmp}/.openclaw-android/hardlink-patch.state"

if [ ! -f "$OPENCLAW_DIR/package.json" ]; then
    echo -e "${YELLOW}[WARN]${NC} OpenClaw not found at $OPENCLAW_DIR - hard-link patch skipped"
    exit 0
fi
if ! command -v node >/dev/null 2>&1; then
    echo -e "${YELLOW}[WARN]${NC} node not found - hard-link patch skipped"
    exit 0
fi

HARDLINK_TMP=$(mktemp "${TMPDIR:-/tmp}/oa-hardlink-patch.XXXXXX") || HARDLINK_TMP="/tmp/oa-hardlink-patch.$$"
trap 'rm -f "$HARDLINK_TMP"' EXIT
cat > "$HARDLINK_TMP" << 'HARDLINK_JS'
const fs = require('fs');
const path = require('path');
const root = process.argv[2];
const FILES = [
  'node_modules/@openclaw/fs-safe/dist/publish-file.js',
  'dist/package-update-activation-recovery.mjs',
  'dist/worker/worker.mjs',
  'dist/worker/sqlite-store.worker.mjs',
];
// Two classifier sets, each `NAME = [/* @__PURE__ */] new Set([ <quote>CODE<quote>, ... ])` (quotes may be
// ", ' or `, on one line or several; the minified bundles use back quotes on one line):
//   HARDLINK_FALLBACK_CODES    - the hard link is refused  -> copy
//   NATIVE_COPY_FALLBACK_CODES - the clone/reflink step of that copy is refused (ioctl FICLONE: EACCES on
//                                Android, found on the device after the first fix) -> plain copy
const SETS = ['HARDLINK_FALLBACK_CODES', 'NATIVE_COPY_FALLBACK_CODES'];
let patched = 0, already = 0, problems = 0;
const CHECK = process.argv.includes('--check');
const ONLY_B3 = process.argv.includes('--only-b3');
const read = (f) => fs.readFileSync(f, 'latin1');
// Atomic: a full disk must not leave a cut-off bundle (a broken OpenClaw); write a temp file next to it, then
// rename it over the original. A failed write is a problem (counted, reported), the original stays as it was.
const write = (f, s) => {
  if (CHECK) return true;
  const tmp = f + '.oa-tmp';
  try {
    fs.writeFileSync(tmp, s, 'latin1');
    fs.chmodSync(tmp, fs.statSync(f).mode & 0o7777);
    fs.renameSync(tmp, f);
    return true;
  } catch (e) {
    try { fs.unlinkSync(tmp); } catch (e2) { /* nothing to clean */ }
    console.log('WRITEFAIL ' + path.relative(root, f) + ' (' + (e && e.code ? e.code : String(e)) + ')');
    problems++;
    return false;
  }
};
// Add EACCES to one set; returns 'patched' | 'already' | 'nomatch'
function addEacces(text, name) {
  const re = new RegExp('(' + name + '\\s*=\\s*(?:\\/\\*[^*]*\\*\\/\\s*)?new Set\\(\\[\\s*)((["\'`])[^\\]]*)\\]\\)');
  const m = re.exec(text);
  if (!m) return { status: 'nomatch', text };
  if (/EACCES/.test(m[2])) return { status: 'already', text };
  const q = m[3];
  const out = text.slice(0, m.index) + m[1] + q + 'EACCES' + q + ',' + m[2] + '])' + text.slice(m.index + m[0].length);
  return { status: 'patched', text: out };
}
for (const rel of ONLY_B3 ? [] : FILES) {
  const file = path.join(root, rel);
  if (!fs.existsSync(file)) { console.log('MISSING ' + rel); problems++; continue; }
  let s = read(file);
  let changed = false;
  for (const name of SETS) {
    const r = addEacces(s, name);
    if (r.status === 'nomatch') { console.log('NOMATCH ' + rel + ' (' + name + ')'); problems++; continue; }
    if (r.status === 'already') { already++; continue; }
    s = r.text; changed = true; patched++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + rel + ' (' + name + ')');
  }
  if (changed) write(file, s);
}
const dist = path.join(root, 'dist');
const backups = fs.existsSync(dist) && !ONLY_B3 ? fs.readdirSync(dist).filter((n) => /^backup-create-.*\.mjs$/.test(n)) : [];
if (backups.length === 0 && !ONLY_B3) { console.log('MISSING dist/backup-create-*.mjs'); problems++; }
for (const name of backups) {
  const file = path.join(dist, name);
  const s = read(file);
  const n = s.split('strategy: "link-required"').length - 1;
  if (n === 0) { already++; continue; }
  if (!write(file, s.split('strategy: "link-required"').join('strategy: "link-or-copy"'))) continue;
  patched++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + 'dist/' + name + ' (' + n + ' link-required -> link-or-copy)');
}

// ---- F3: moveMigrationArtifact by rename when the hard link is refused (see the header)
const B3_MARK = '__oaMoveMigrationArtifactOrig';
const B3_DEF = /async function moveMigrationArtifact\(/;
function b3Helper(requireSync, syncDir) {
  return `/* B3_SESSION_ARCHIVE_PATCH v2 (OpenClaw on Android): hard links are refused, archive by rename */
async function __oaMoveByRename(sourcePath, targetPath, expected, onPublished, publishSourceRemoval) {
	const sourceThere = !!fs.lstatSync(sourcePath, { bigint: true, throwIfNoEntry: false });
	const targetThere = !!fs.lstatSync(targetPath, { bigint: true, throwIfNoEntry: false });
	if (sourceThere && targetThere) throw new Error("artifact archive path already exists");
	let movedHere = false;
	if (sourceThere) {
		if (!sameMigrationArtifact(readMigrationArtifactIdentity(sourcePath), expected)) throw new Error("artifact changed before publication");
		fs.renameSync(sourcePath, targetPath);
		movedHere = true;
	}
	${requireSync}(await ${syncDir}(path.dirname(targetPath)), "Recovery artifact publication");
	const verifyMoved = () => {
		if (fs.lstatSync(sourcePath, { bigint: true, throwIfNoEntry: false }) || !sameMigrationArtifact(readMigrationArtifactIdentity(targetPath), expected)) throw new Error("artifact changed during publication");
	};
	let published = false;
	const removeSource = () => {
		verifyMoved();
		if (onPublished) {
			onPublished();
			published = true;
			verifyMoved();
		}
		published = true;
	};
	let retainedSource = false;
	const retainSource = () => {
		// as in OpenClaw's own code: a publication made by an earlier run cannot be discarded
		if (!movedHere) throw new Error("Cannot discard a recovery publication created by an earlier run.");
		verifyMoved();
		fs.renameSync(targetPath, sourcePath);
		retainedSource = true;
	};
	try {
		if (publishSourceRemoval) publishSourceRemoval(removeSource, retainSource);
		else removeSource();
	} catch (error) {
		// failed before the move was final: put the file back under its own name (OpenClaw's own code
		// keeps both names then), so that a rerun sees the same state as after a refused hard link
		if (movedHere && !published && !retainedSource) {
			try {
				if (!fs.lstatSync(sourcePath, { bigint: true, throwIfNoEntry: false }) && sameMigrationArtifact(readMigrationArtifactIdentity(targetPath), expected)) fs.renameSync(targetPath, sourcePath);
			} catch (restoreError) { /* keep the original error */ }
		}
		throw error;
	} finally {
		if (retainedSource) ${requireSync}(await ${syncDir}(path.dirname(targetPath)), "Recovery artifact deferral");
	}
	${requireSync}(await ${syncDir}(path.dirname(sourcePath)), "Recovery artifact source");
	if (!sameMigrationArtifact(readMigrationArtifactIdentity(targetPath), expected)) throw new Error("artifact changed during publication");
}
async function moveMigrationArtifact(sourcePath, targetPath, expected, onPublished, publishSourceRemoval) {
	const missing = (p) => !fs.lstatSync(p, { bigint: true, throwIfNoEntry: false });
	// an interrupted rename-based move: the source is gone, the archive copy is there
	if (missing(sourcePath) && !missing(targetPath)) return __oaMoveByRename(sourcePath, targetPath, expected, onPublished, publishSourceRemoval);
	try {
		return await ${B3_MARK}(sourcePath, targetPath, expected, onPublished, publishSourceRemoval);
	} catch (error) {
		// only a refused hard link (the codes of the B2 fallback list), before anything was published
		if (error && ["EACCES", "EPERM", "ENOTSUP", "EOPNOTSUPP", "ENOSYS"].includes(error.code) && !missing(sourcePath) && missing(targetPath)) return __oaMoveByRename(sourcePath, targetPath, expected, onPublished, publishSourceRemoval);
		throw error;
	}
}
`;
}
const B3_TAG = '/* B3_SESSION_ARCHIVE_PATCH';
const B3_TAG_CURRENT = B3_TAG + ' v2 ';
const B3_HELPER_NAMES = /([\w$]+)\(await ([\w$]+)\(path\.dirname\(/;
// A bundle with a second, unwrapped definition would pass on the marker alone: count them (the wrapper is the one)
const b3Defs = (t) => t.split(B3_DEF).length - 1;
function patchB3(text) {
  if (b3Defs(text) > 1) return { status: 'nomatch', text };
  if (text.includes(B3_MARK)) {
    if (text.includes(B3_TAG_CURRENT)) return { status: 'already', text };
    // patched by an earlier version of this helper: replace it (it sits right before the renamed original)
    const a = text.indexOf(B3_TAG);
    const oidx = text.indexOf('async function ' + B3_MARK + '(');
    if (a < 0 || oidx < a) return { status: 'nomatch', text };
    const m = B3_HELPER_NAMES.exec(text.slice(oidx, oidx + 8000));
    if (!m) return { status: 'nomatch', text };
    return { status: 'patched', text: text.slice(0, a) + b3Helper(m[1], m[2]) + text.slice(oidx) };
  }
  const idx = text.search(B3_DEF);
  if (idx < 0) return { status: 'nomatch', text };
  // the names this bundle uses for the directory-sync helpers (minified bundles rename them)
  const m = B3_HELPER_NAMES.exec(text.slice(idx, idx + 8000));
  if (!m) return { status: 'nomatch', text };
  const rest = text.slice(idx).replace(B3_DEF, 'async function ' + B3_MARK + '(');
  return { status: 'patched', text: text.slice(0, idx) + b3Helper(m[1], m[2]) + rest };
}
const b3Files = [];
if (fs.existsSync(path.join(root, 'dist'))) {
  for (const n of fs.readdirSync(path.join(root, 'dist'))) if (/^session-sqlite-migration-manifest-.*\.mjs$/.test(n)) b3Files.push('dist/' + n);
}
b3Files.push('dist/package-update-activation-recovery.mjs', 'dist/worker/worker.mjs', 'dist/worker/sqlite-store.worker.mjs');
let b3Seen = 0;
for (const rel of b3Files) {
  const file = path.join(root, rel);
  if (!fs.existsSync(file)) { console.log('MISSING ' + rel + ' (B3)'); problems++; continue; }
  const r = patchB3(read(file));
  if (r.status === 'already') { already++; b3Seen++; continue; }
  if (r.status === 'nomatch') { console.log('NOMATCH ' + rel + ' (B3 moveMigrationArtifact)'); problems++; continue; }
  if (!write(file, r.text)) continue; patched++; b3Seen++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + rel + ' (B3 moveMigrationArtifact)');
}
console.log('SUMMARY ' + (CHECK ? 'todo=' : 'patched=') + patched + ' already=' + already + ' problems=' + problems);
process.exit(problems || (CHECK && patched) ? 3 : 0);
HARDLINK_JS
rc=0
JS_FLAGS=""
[ "$CHECK" = 1 ] && JS_FLAGS="--check"
[ "$ONLY_B3" = 1 ] && JS_FLAGS="$JS_FLAGS --only-b3"
# shellcheck disable=SC2086
out=$(node "$HARDLINK_TMP" "$OPENCLAW_DIR" $JS_FLAGS) || rc=$?

summary=$(printf '%s\n' "$out" | { grep '^SUMMARY ' || true; } | tail -1 | sed 's/^SUMMARY //')
# (|| true: with nothing to report grep finds no line, and that must not end the script)
{ printf '%s\n' "$out" | grep -v '^SUMMARY ' || true; } | while IFS= read -r line; do
    case "$line" in
        PATCHED*) echo -e "  ${GREEN}[PATCHED]${NC} ${line#PATCHED }" ;;
        TODO*) echo -e "  ${YELLOW}[TODO]${NC} ${line#TODO }" ;;
        MISSING*|NOMATCH*|WRITEFAIL*) echo -e "  ${YELLOW}[WARN]${NC} ${line%% *}: ${line#* } - hard-link fallback NOT applied here" ;;
        *) [ -z "$line" ] || echo "  $line" ;;
    esac
done
ocver=$(sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$OPENCLAW_DIR/package.json" | head -1)
if [ "$CHECK" != 1 ] && [ "$ONLY_B3" != 1 ]; then
    mkdir -p "$(dirname "$STATE_FILE")" 2>/dev/null || true
    echo "openclaw=${ocver:-unknown} ${summary:-error} exit=$rc date=$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$STATE_FILE" 2>/dev/null || true
fi
if [ "$rc" -eq 0 ]; then
    echo "Hard-link fallback: ${summary}"
elif [ "$CHECK" = 1 ] && printf '%s' "$summary" | grep -q 'problems=0'; then
    echo "Hard-link fallback not applied yet: ${summary}"
else
    echo -e "${YELLOW}[WARN]${NC} Hard-link fallback incomplete (${summary:-node failed}): this OpenClaw version does not match the patch, or a file could not be written."
    echo "       Hard links are denied on Android, so the data migration of 'openclaw doctor --fix' can fail with EACCES."
fi
# (a normal run never fails the caller; --check reports through its exit code)
[ "$CHECK" = 1 ] && exit "$rc"
exit 0
OPENCLAW_HARDLINK_SH
chmod +x "$OC_HL_GEN"
_oa_hl_out="$TMPDIR/oa-hl-patch.out"
bash "$OC_HL_GEN" | { tee "$_oa_hl_out" || true; } | sed 's/^/  /'
if grep -qE 'WARN|problems=[1-9]' "$_oa_hl_out" 2>/dev/null; then oa_warn hardlink-patch; fi
rm -f "$_oa_hl_out"

# Block `openclaw update` so the pin holds (after the npm wrappers from [3/7])
bash "$OC_SHIM_GEN" | sed 's/^/  /'

# Install clawdhub (skill manager) — only when it is missing: re-running this script
# (e.g. after removing the done-marker to pick up a fixed post-setup.sh) must not
# reinstall it.
CLAWHUB_DIR="$(npm root -g 2>/dev/null || true)/clawdhub"
if [ -f "$CLAWHUB_DIR/package.json" ]; then
    echo -e "  ${GREEN}[SKIP]${NC} clawdhub already installed"
else
    echo "  Installing clawdhub..."
    if npm install -g clawdhub --no-fund --no-audit; then
        echo -e "  ${GREEN}✓${NC} clawdhub installed"
    else
        echo -e "  ${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
        oa_warn clawdhub
    fi
fi
if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
    echo "  Installing undici dependency for clawdhub..."
    (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit) || true
fi

# PyYAML (for .skill packaging)
command -v python &>/dev/null && { python -c "import yaml" 2>/dev/null || { command -v pip >/dev/null 2>&1 && pip install pyyaml -q; } || true; }

# ─── [5/7] Patches ──────────────────────────
oa_stage 5
echo -e "▸ ${YELLOW}[5/7]${NC} Applying patches..."

# glibc-compat.js — the node wrapper reads lib/glibc-compat.js, a directory the
# Android app never writes (the app overwrites patches/glibc-compat.js with its
# bundled copy on every APK upgrade). patches/ keeps a copy for the app and for
# older wrappers.
mkdir -p "$OCA_DIR/lib" "$OCA_DIR/patches"
COMPAT_SRC="$(dirname "$0")/glibc-compat.js"
COMPAT_TMP="$OCA_DIR/lib/glibc-compat.js.tmp"
if [ -f "$COMPAT_SRC" ]; then
    cp "$COMPAT_SRC" "$COMPAT_TMP" || rm -f "$COMPAT_TMP"
else
    # Fallback: download from repo
    curl -fsSL "$REPO_BASE/patches/glibc-compat.js" \
        -o "$COMPAT_TMP" 2>/dev/null || rm -f "$COMPAT_TMP"
fi
if [ -s "$COMPAT_TMP" ]; then
    mv -f "$COMPAT_TMP" "$OCA_DIR/lib/glibc-compat.js"
    cp "$OCA_DIR/lib/glibc-compat.js" "$OCA_DIR/patches/glibc-compat.js"
elif [ ! -s "$OCA_DIR/lib/glibc-compat.js" ] && [ -s "$OCA_DIR/patches/glibc-compat.js" ]; then
    # Download failed and lib/ has none yet: use the copy the app bundled
    # (what the wrapper used before). An existing lib/ copy is never replaced by it.
    cp "$OCA_DIR/patches/glibc-compat.js" "$OCA_DIR/lib/glibc-compat.js"
    echo -e "  ${YELLOW}[WARN]${NC} Could not download glibc-compat.js — using the bundled copy. Run 'oa --update' later."
    oa_warn compat-js
fi
rm -f "$COMPAT_TMP"

# systemctl stub
printf '#!%s/bin/bash\nexit 0\n' "$PREFIX" > "$PREFIX/bin/systemctl"
chmod +x "$PREFIX/bin/systemctl"

echo -e "  ${GREEN}✓${NC} Patches applied"

# ─── [6/7] Environment ──────────────────────
oa_stage 6
echo -e "▸ ${YELLOW}[6/7]${NC} Configuring environment..."

cat > "$HOME/.bashrc" << BASHRC || { echo -e "  ${RED}✗${NC} Could not write ~/.bashrc"; OA_TOOLS_ERROR="env"; exit 1; }
# OpenClaw Android environment
export PREFIX="$PREFIX"
export HOME="$HOME"
export TMPDIR="$TMPDIR"
export PATH="$BIN_DIR:$NODE_DIR/bin:\$PREFIX/bin:\$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"
export TERMUX__PREFIX="$PREFIX"
export TERMUX_PREFIX="$PREFIX"
export LANG=en_US.UTF-8
export TERM=xterm-256color
export OA_GLIBC=1
export CONTAINER=1
# Block the gateway auto-updater even if update.auto.enabled is set (version pin)
export OPENCLAW_NO_AUTO_UPDATE=1
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"
export GIT_CONFIG_NOSYSTEM=1
export GIT_EXEC_PATH="$PREFIX/libexec/git-core"
export GIT_TEMPLATE_DIR="$PREFIX/share/git-core/templates"
export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"
# npm registry (auto-detected by OpenClaw Android, safe to override manually)
if [ -z "\${NPM_CONFIG_REGISTRY:-}" ] && [ -s "\$HOME/.openclaw-android/.npm-registry" ]; then
    export NPM_CONFIG_REGISTRY="\$(cat "\$HOME/.openclaw-android/.npm-registry")"
fi
BASHRC

echo -e "  ${GREEN}✓${NC} ~/.bashrc configured"

# oa CLI (enables oa --update, oa --backup, etc.)
# Downloaded to a temp file, checked (a script starts with "#!"), made executable and
# only then renamed into place — an interrupted download must not leave a partial oa or
# one without the execute bit (the app's umask makes new files 0600). A working oa that
# is already there stays untouched when the download fails.
_oa_tmp="$PREFIX/bin/.oa.tmp.$$"
if curl -fsSL "$REPO_BASE/oa.sh" -o "$_oa_tmp" 2>/dev/null \
        && [ "$(head -c 2 "$_oa_tmp" 2>/dev/null)" = "#!" ] \
        && chmod +x "$_oa_tmp" && mv -f "$_oa_tmp" "$PREFIX/bin/oa"; then
    echo -e "  ${GREEN}✓${NC} oa CLI installed"
else
    rm -f "$_oa_tmp"
    echo -e "  ${YELLOW}[WARN]${NC} oa CLI installation failed (non-critical)"
    oa_warn oa-cli
fi

# Files that oa --backup / oa --restore need, and the platform part of oa --status (the same
# ones oa --update installs). All of them are fetched first and put in place together, so a
# failed download never leaves a half set; oa --update installs them later if this step is skipped.
_oa_dl_ok=true
mkdir -p "$OCA_DIR/scripts" "$OCA_DIR/platforms/openclaw"
for _oa_f in scripts/lib.sh scripts/backup.sh platforms/openclaw/config.env platforms/openclaw/status.sh; do
    if ! curl -fsSL "$REPO_BASE/$_oa_f" -o "$OCA_DIR/$_oa_f.tmp" 2>/dev/null || [ ! -s "$OCA_DIR/$_oa_f.tmp" ]; then
        _oa_dl_ok=false
        break
    fi
    # A mirror can answer 200 with an HTML page: scripts must start with #!, config.env must define the data dir
    case "$_oa_f" in
        *.sh) head -c 2 "$OCA_DIR/$_oa_f.tmp" | grep -q '^#!' || { _oa_dl_ok=false; break; } ;;
        *.env) grep -q '^PLATFORM_DATA_DIR=' "$OCA_DIR/$_oa_f.tmp" || { _oa_dl_ok=false; break; } ;;
    esac
done
if [ "$_oa_dl_ok" = true ]; then
    for _oa_f in scripts/lib.sh scripts/backup.sh platforms/openclaw/config.env platforms/openclaw/status.sh; do
        mv -f "$OCA_DIR/$_oa_f.tmp" "$OCA_DIR/$_oa_f"
    done
    echo -e "  ${GREEN}✓${NC} backup/restore scripts installed (oa --backup, oa --restore)"
else
    rm -f "$OCA_DIR/scripts/lib.sh.tmp" "$OCA_DIR/scripts/backup.sh.tmp" "$OCA_DIR/platforms/openclaw/config.env.tmp" "$OCA_DIR/platforms/openclaw/status.sh.tmp"
    echo -e "  ${YELLOW}[WARN]${NC} Could not download the backup/restore scripts (non-critical) — run oa --update later to get them"
    oa_warn backup-scripts
fi

# ─── [7/7] Optional Tools ──────────────────
oa_stage 7
TOOL_CONF="$OCA_DIR/tool-selections.conf"
if [ -f "$TOOL_CONF" ]; then
    # Read the selections as data, never as code: only the known keys, and only the
    # exact values true/false. The file is written by the app's setup screen, so
    # it must not be executed (a value with a newline or $(...) would run).
    _tool_lineno=0
    while IFS= read -r _tool_line || [ -n "$_tool_line" ]; do
        _tool_lineno=$((_tool_lineno + 1))
        # Check the length first: string operations on a huge line are very slow
        if [ "${#_tool_line}" -gt 80 ]; then
            echo -e "  ${YELLOW}[WARN]${NC} Ignoring line $_tool_lineno of tool-selections.conf (too long)"
            continue
        fi
        _tool_line="${_tool_line%$'\r'}"
        [ -z "$_tool_line" ] && continue
        _tool_key="${_tool_line%%=*}"
        _tool_val="${_tool_line#*=}"
        case "$_tool_key" in
            INSTALL_TMUX|INSTALL_TTYD|INSTALL_DUFS|INSTALL_CODE_SERVER|INSTALL_PLAYWRIGHT|INSTALL_CLAUDE_CODE|INSTALL_GEMINI_CLI|INSTALL_CODEX_CLI)
                if [ "$_tool_line" != "$_tool_key" ] && { [ "$_tool_val" = "true" ] || [ "$_tool_val" = "false" ]; }; then
                    printf -v "$_tool_key" '%s' "$_tool_val"
                    continue
                fi
                ;;
        esac
        echo -e "  ${YELLOW}[WARN]${NC} Ignoring line $_tool_lineno of tool-selections.conf (unknown option or invalid value)"
    done < "$TOOL_CONF"

    HAS_TOOLS=false
    for var in INSTALL_TMUX INSTALL_TTYD INSTALL_DUFS INSTALL_CODE_SERVER INSTALL_PLAYWRIGHT INSTALL_CLAUDE_CODE INSTALL_GEMINI_CLI INSTALL_CODEX_CLI; do
        eval "val=\${$var:-false}"
        # shellcheck disable=SC2154
        [ "$val" = "true" ] && HAS_TOOLS=true && break
    done

    if $HAS_TOOLS; then
        echo -e "▸ ${YELLOW}[7/7]${NC} Installing optional tools..."

        # Termux packages, then npm packages (the installers are defined above; a
        # failed tool is a warning, never fatal)
        [ "${INSTALL_TMUX:-false}" = "true" ] && { tool_install_tmux || oa_warn "tools:tmux"; }
        [ "${INSTALL_TTYD:-false}" = "true" ] && { tool_install_ttyd || oa_warn "tools:ttyd"; }
        [ "${INSTALL_DUFS:-false}" = "true" ] && { tool_install_dufs || oa_warn "tools:dufs"; }
        [ "${INSTALL_CODE_SERVER:-false}" = "true" ] && { tool_install_code_server || oa_warn "tools:code-server"; }
        [ "${INSTALL_PLAYWRIGHT:-false}" = "true" ] && { tool_install_playwright || oa_warn "tools:playwright"; }
        [ "${INSTALL_CLAUDE_CODE:-false}" = "true" ] && { tool_install_claude_code || oa_warn "tools:claude-code"; }
        [ "${INSTALL_GEMINI_CLI:-false}" = "true" ] && { tool_install_gemini_cli || oa_warn "tools:gemini-cli"; }
        [ "${INSTALL_CODEX_CLI:-false}" = "true" ] && { tool_install_codex_cli || oa_warn "tools:codex-cli"; }

        fix_npm_shebangs
    else
        echo -e "▸ ${YELLOW}[7/7]${NC} No optional tools selected"
    fi
else
    echo -e "▸ ${YELLOW}[7/7]${NC} No optional tools selected"
fi

# ─── Cleanup ────────────────────────────────
cleanup_downloads

# ─── Done ────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════"
echo -e "  ${GREEN}✓ Installation complete!${NC}"
echo "══════════════════════════════════════════════"
echo ""
echo "  Loading environment..."
# ~/.bashrc is written for interactive shells; a non-zero status from any line
# in it must not abort setup before onboard (errexit is off inside `||`).
source "$HOME/.bashrc" || true

# Turn off the gateway's "update available" notice: the OpenClaw version is pinned
# here, so the notice only points at an update that oa blocks. A value the user
# already set (true or false) is left alone. Same block as platforms/openclaw
# install.sh and update.sh.
if timeout 60 openclaw config get update.checkOnStart >/dev/null 2>&1; then
    echo -e "  ${GREEN}[SKIP]${NC} update.checkOnStart is already set"
elif timeout 60 openclaw config set update.checkOnStart false >/dev/null 2>&1; then
    echo -e "  ${GREEN}✓${NC} OpenClaw update notice turned off (update.checkOnStart=false)"
else
    echo -e "  ${YELLOW}[WARN]${NC} Could not turn off the OpenClaw update notice (non-critical)"
    oa_warn checkOnStart
fi
# The install is complete: the done-marker, the final result file and the lock go together
# (the result and the marker must never disagree), before the interactive part
oa_full_finalize

# OA_NO_ONBOARD=1 (the app runs this script as a child and shows its own next step):
# stop here with exit 0 instead of starting the interactive onboard
if [ "${OA_NO_ONBOARD:-}" = "1" ]; then
    echo "  OpenClaw onboard skipped (OA_NO_ONBOARD=1)."
    exit 0
fi

echo ""
echo "  Starting OpenClaw onboard..."
echo ""
openclaw onboard
