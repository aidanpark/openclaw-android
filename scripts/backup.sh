#!/usr/bin/env bash
# backup.sh — oa --backup / oa --restore implementation
# Sourced by oa.sh after lib.sh is loaded.
#
# Archive layout (compatible with `openclaw backup verify`):
#   <root>/manifest.json
#   <root>/payload/posix/<absolute source path>/...
# Older oa backups used <root>/payload/<relative path>; restore still reads both.
#
# `openclaw backup create` is not used: on Android it fails (hard links are
# blocked, EACCES) and it always leaves out the conversation transcripts
# (agents/<id>/sessions/*.jsonl). This script archives the whole state directory
# itself, with SQLite databases as consistent snapshots (VACUUM INTO).

# ── Constants ──
BACKUP_DIR="$PROJECT_DIR/backup"
BACKUP_SCHEMA_VERSION=1
BACKUP_GATEWAY_PORT=18789

# ── Helpers ──

# Build an ISO-8601 timestamp matching OpenClaw's naming rule:
# colons replaced with dashes, e.g. 2026-03-14T00-00-00.000Z
_backup_timestamp() {
    date -u +"%Y-%m-%dT%H-%M-%S.000Z"
}

# Escape a string for use inside a JSON string literal.
_json_escape() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    s="${s//$'\n'/\\n}"
    s="${s//$'\t'/\\t}"
    printf '%s' "$s"
}

# Escape a literal string for use in the pattern of a tar --transform expression
# (basic regex, ',' is the delimiter).
_regex_escape() {
    printf '%s' "$1" | sed 's/[][\.*^$,]/\\&/g'
}

# Number of path components in an absolute path (/a/b/c -> 3).
_path_segments() {
    local p="${1#/}"
    local -a parts
    IFS=/ read -ra parts <<< "$p"
    echo "${#parts[@]}"
}

# Yes/no prompt that defaults to "no" (for questions where yes loses data).
_ask_yn_default_no() {
    local prompt="$1"
    local reply
    if (echo -n "" > /dev/tty) 2>/dev/null; then
        read -rp "$prompt [y/N] " reply < /dev/tty
    else
        read -rp "$prompt [y/N] " reply
    fi
    [[ "${reply:-}" =~ ^[Yy]$ ]]
}

# ── node calls under a time limit ──
# A Node.js process started through the glibc loader was seen to stay alive after it had done its work
# (QA, Script v1.2.5), so nothing here waits for node without a limit. Two classes: a probe starts, answers and
# ends (OA_NODE_PROBE_TIMEOUT, 30 s); work reads or writes data (OA_NODE_WORK_TIMEOUT, 300 s). A call that the
# limit stopped returns 124 — which is also what a stop of "node reported its result, then did not exit" looks
# like, so callers pass a marker that their node script prints last: with it the result is taken.
# (Same shape as the hard-link patch: --foreground keeps Ctrl-C working, -k is used where timeout knows it.)
_backup_limit_value() {
    local v="$1" d="$2"
    case "$v" in ''|*[!0-9]*) printf '%s' "$d"; return 0 ;; esac
    v=${v#"${v%%[!0]*}"}
    # (below 5 s a kill from outside near the limit could not be told from the limit: SECONDS counts whole seconds)
    if [ -z "$v" ] || [ "${#v}" -gt 6 ] || [ "$v" -lt 5 ]; then
        printf '%s' "$d"
    else
        printf '%s' "$v"
    fi
}
BACKUP_NODE_PROBE_LIMIT="$(_backup_limit_value "${OA_NODE_PROBE_TIMEOUT:-}" 30)"
BACKUP_NODE_WORK_LIMIT="$(_backup_limit_value "${OA_NODE_WORK_TIMEOUT:-}" 300)"
_BACKUP_TIMEOUT=()
if command -v timeout >/dev/null 2>&1; then
    if timeout --foreground -k 1 10 true >/dev/null 2>&1; then
        _BACKUP_TIMEOUT=(timeout --foreground -k 5)
    elif timeout -k 1 10 true >/dev/null 2>&1; then
        _BACKUP_TIMEOUT=(timeout -k 5)
    elif timeout 10 true >/dev/null 2>&1; then
        _BACKUP_TIMEOUT=(timeout)
    fi
fi

# Run a command once under the limit of its class (probe|work). Returns its exit code, or 124 when the limit
# stopped it (timeout's own 124; 137 or 143 only after the whole time — after less they came from somewhere else,
# e.g. the system out of memory).
# Usage: _backup_limited <probe|work> <command> [args…]
_backup_limited() {
    local class="$1" limit t0 rc=0
    shift
    if [ "$class" = work ]; then limit="$BACKUP_NODE_WORK_LIMIT"; else limit="$BACKUP_NODE_PROBE_LIMIT"; fi
    if [ "${#_BACKUP_TIMEOUT[@]}" -eq 0 ]; then
        "$@" || rc=$?
        return "$rc"
    fi
    t0=$SECONDS
    "${_BACKUP_TIMEOUT[@]}" "$limit" "$@" || rc=$?
    if [ "$rc" -eq 124 ]; then
        return 124
    fi
    if { [ "$rc" -eq 137 ] || [ "$rc" -eq 143 ]; } && [ $((SECONDS - t0 + 1)) -ge "$limit" ]; then
        return 124
    fi
    return "$rc"
}

# A call whose output is the answer: runs up to <tries> times (a stop of the limit only; standard input is not
# read again, so a call that reads it uses 1), prints the output of the last run and returns its exit code.
# When the limit stopped a run that had already printed the marker (a line of the output matching the regex),
# the run counts as done: a Note goes to standard error and 0 is returned. node's own standard error is dropped.
# Usage: _backup_node_call <probe|work> <marker regex> <tries> <node> [args…]
_backup_node_call() {
    local class="$1" marker="$2" tries="$3" out rc n=0
    shift 3
    while :; do
        n=$((n + 1))
        rc=0
        out=$(_backup_limited "$class" "$@" 2>/dev/null) || rc=$?
        [ "$rc" -eq 124 ] || break
        if printf '%s\n' "$out" | grep -Eq "$marker"; then
            echo "Note: Node.js had reported its result but did not exit within the limit; the result is used." >&2
            rc=0
            break
        fi
        [ "$n" -lt "$tries" ] || break
        echo "Note: Node.js did not finish within the limit; trying once more." >&2
    done
    printf '%s\n' "$out"
    return "$rc"
}

# The `node` argument of oa_repair_state_paths / oa_repair_config_paths (lib.sh): they call "$node" with their
# own arguments and read the last line of what it prints. A stop of the limit after that line is a finished run.
_backup_node_repair() {
    local out rc=0 last
    out=$(_backup_limited work "$OA_NODE" "$@") || rc=$?
    printf '%s\n' "$out"
    if [ "$rc" -eq 124 ]; then
        last=$(printf '%s\n' "$out" | tail -1)
        case "$last" in
            [0-9]*" "[0-9]*|error:*) return 0 ;;
        esac
    fi
    return "$rc"
}

# node: from PATH, else the wrapper the installer puts in ~/.openclaw-android/bin (a shell
# without the oa PATH, e.g. non-interactive SSH, does not see it). The real binary in
# node/bin is not tried: it cannot run without the wrapper's glibc loader.
# Returns 0 with the path, 1 when there is none, 75 when the wrapper is there and does not answer.
_oa_find_node() {
    local c rc=0
    if c=$(command -v node 2>/dev/null) && [ -n "$c" ]; then
        printf '%s' "$c"
        return 0
    fi
    c="$HOME/.openclaw-android/bin/node"
    if [ -x "$c" ]; then
        _backup_node_call probe '^v?[0-9]+\.[0-9]+' 2 "$c" --version >/dev/null || rc=$?
        if [ "$rc" -eq 124 ]; then
            return 75
        fi
        if [ "$rc" -eq 0 ]; then
            printf '%s' "$c"
            return 0
        fi
    fi
    return 1
}
# Resolved on first use, not when this file is sourced: oa.sh sources it for every
# command, and starting node just to look for it would slow all of them down.
OA_NODE=""
OA_NODE_RESOLVED=false
OA_NODE_STUCK=false
_oa_resolve_node() {
    if [ "$OA_NODE_RESOLVED" = false ]; then
        local rc=0
        OA_NODE="$(_oa_find_node)" || rc=$?
        [ "$rc" -ne 75 ] || OA_NODE_STUCK=true
        OA_NODE_RESOLVED=true
    fi
}

# True if GNU tar (needed for --transform / --strip-components / --wildcards).
_have_gnu_tar() {
    tar --version 2>/dev/null | head -1 | grep -q "GNU tar"
}

# 0 if node can load node:sqlite (SQLite snapshots need it), 1 if it cannot (or there is no node),
# 75 if node did not answer within the limit.
_have_node_sqlite() {
    [ -n "$OA_NODE" ] || return 1
    local out rc=0
    out=$(_backup_node_call probe '^OK$' 2 "$OA_NODE" -e 'require("node:sqlite");console.log("OK")') || rc=$?
    [ "$rc" -ne 124 ] || return 75
    [ "$rc" -eq 0 ] && [ "$(printf '%s\n' "$out" | tail -1)" = OK ]
}

# True if the OpenClaw gateway looks like it is running.
_gateway_running() {
    if command -v pgrep &>/dev/null; then
        # Trust pgrep when it exists (a port answer could be another install's gateway)
        pgrep -f "openclaw.*gateway" &>/dev/null
        return $?
    fi
    command -v curl &>/dev/null \
        && curl -s --noproxy '*' --max-time 2 -o /dev/null "http://127.0.0.1:${BACKUP_GATEWAY_PORT}/" 2>/dev/null
}

# Installed OpenClaw version, read from the package (no CLI start-up).
_backup_runtime_version() {
    local direct="" pkg="" root="" tmp v
    [ -z "${PREFIX:-}" ] || direct="$PREFIX/lib/node_modules/openclaw/package.json"
    # Where the installers put it. `npm root -g` (it starts node, so under the limit) when it is not there, or when
    # the environment or an npmrc says npm uses another prefix. Its answer goes through a file: the npm wrapper runs node as
    # a child, and a stopped wrapper would leave that child holding a pipe (and this call waiting for it).
    local f own_prefix=false
    [ -z "${npm_config_prefix:-}${NPM_CONFIG_PREFIX:-}" ] || own_prefix=true
    for f in "$HOME/.npmrc" "${PREFIX:-/nonexistent}/etc/npmrc" "${npm_config_userconfig:-}" "${NPM_CONFIG_USERCONFIG:-}"; do
        if [ -n "$f" ] && [ -f "$f" ] && grep -q '^[[:space:]]*prefix[[:space:]]*=' "$f" 2>/dev/null; then own_prefix=true; fi
    done
    if [ -n "$direct" ] && [ "$own_prefix" = false ] && [ -f "$direct" ]; then
        pkg="$direct"
    else
        if command -v npm >/dev/null 2>&1 && tmp=$(mktemp "${TMPDIR:-/tmp}/oa-npmroot.XXXXXX" 2>/dev/null); then
            _backup_limited probe npm root -g >"$tmp" 2>/dev/null </dev/null || true
            root=$(head -1 "$tmp" 2>/dev/null) || root=""
            rm -f "$tmp"
        fi
        if [ -n "$root" ] && [ -f "$root/openclaw/package.json" ]; then
            pkg="$root/openclaw/package.json"
        elif [ -n "$direct" ] && [ -f "$direct" ]; then
            pkg="$direct"
        fi
    fi
    if [ -n "$pkg" ]; then
        v=$(grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' "$pkg" 2>/dev/null | head -1 | sed 's/.*"\([^"]*\)"$/\1/') || v=""
        if [ -n "$v" ]; then
            printf '%s\n' "$v"
            return 0
        fi
    fi
    echo "unknown"
}

# Small temporary files of an earlier run that was cut off (Ctrl-C, killed): older than an hour. There is no trap for
# them here (this file is sourced by oa.sh and update-core.sh, whose own traps a trap in a function would replace).
_backup_sweep_temp() {
    find "${TMPDIR:-/tmp}" -maxdepth 1 -type f \( -name 'oa-ws.*' -o -name 'oa-verify.*' -o -name 'oa-verify-out.*' \
        -o -name 'oa-npmroot.*' \) -mmin +60 -exec rm -f {} + 2>/dev/null || true
}

# The first line of `openclaw config get <key>`; nothing when openclaw is not there, fails or does not answer.
# (Through a file, under the probe limit with -k: openclaw restarts itself as a detached child that holds a pipe,
# and a plain `timeout` without -k leaves an openclaw that ignores TERM running; see cmd_backup.)
_backup_openclaw_get() {
    local key="$1" f val=""
    command -v openclaw &>/dev/null || return 0
    f=$(mktemp "${TMPDIR:-/tmp}/oa-ws.XXXXXX" 2>/dev/null) || return 0
    if { _backup_limited probe sh -c 'exec "$@" >"$0" 2>/dev/null </dev/null' "$f" openclaw config get "$key"; } 2>/dev/null; then
        val=$(head -1 "$f" 2>/dev/null) || val=""
    fi
    rm -f "$f"
    printf '%s' "${val%$'\r'}"
}

# Workspace directory when it lives outside the state directory (default setups
# keep it inside, where it is already covered). Prints nothing otherwise.
_backup_external_workspace() {
    local data_dir="$1"
    local ws
    ws=$(_backup_openclaw_get agents.defaults.workspace)
    case "$ws" in
        "$data_dir"|"$data_dir"/*) return 0 ;;
        /*) [ -d "$ws" ] && printf '%s' "$ws" ;;
    esac
    return 0
}

# Detect which runtime owns a backup by inspecting its manifest.json.
# Echoes the platform name (e.g. "openclaw"), or "" on failure.
_detect_backup_platform() {
    local archive="$1"

    # Extract manifest.json from the tarball without unpacking everything
    local manifest
    manifest=$(gzip -dc "$archive" 2>/dev/null | tar -xf - --wildcards --no-wildcards-match-slash "*/manifest.json" -O 2>/dev/null | head -c 65536)

    if [ -z "$manifest" ]; then
        echo ""
        return 1
    fi

    # Quick heuristic: look for known platform fingerprints in sourcePath values
    if echo "$manifest" | grep -q '"\.openclaw"'; then
        echo "openclaw"
        return 0
    fi
    if echo "$manifest" | grep -q '\.openclaw'; then
        echo "openclaw"
        return 0
    fi

    echo ""
    return 1
}

# Return the restore root directory for a given platform name.
_restore_root_for_platform() {
    local platform="$1"
    case "$platform" in
        openclaw)
            echo "$HOME/.openclaw"
            ;;
        *)
            # Future platforms: extend here
            echo ""
            ;;
    esac
}

# A backup made on another device keeps that device's absolute paths in the
# session metadata (sessions.json, *.trajectory-path.json); a restored session
# would try to write there. Point them at this device's state folder.
# Usage: _restore_rewrite_paths <state dir> <old state dir> <new state dir>
_restore_rewrite_paths() {
    local root="$1" from="$2" to="$3"
    if [ -z "$OA_NODE" ]; then
        echo -e "${YELLOW}[WARN]${NC} node not found — session paths from $from were not updated."
        return 0
    fi
    [ -d "$root/agents" ] || return 0
    # (Every file is written to a temporary file next to it and renamed, so a run that is stopped leaves each
    # file either as it was or finished; the run can simply be done again.)
    # Replace the old path as a literal (escaped), only where it is not the start of a
    # longer name. The file list goes through stdin (NUL separated), not the command
    # line, so any number of session files works.
    local js='
        const fs = require("fs");
        const [from, to] = process.argv.slice(1);
        const jsonEsc = (v) => JSON.stringify(v).slice(1, -1);
        const esc = jsonEsc(from).replace(/[.*+?^$(){}|\[\]\\]/g, "\\$&");
        const re = new RegExp(esc + "(?![A-Za-z0-9_.-])", "g");
        const toJson = jsonEsc(to);
        let buf = "";
        process.stdin.setEncoding("utf8");
        process.stdin.on("data", (d) => { buf += d; });
        process.stdin.on("end", () => {
            let n = 0;
            for (const f of buf.split("\0").filter(Boolean)) {
                const t = fs.readFileSync(f, "utf8");
                const u = t.replace(re, () => toJson);
                if (u !== t) {
                    const real = fs.realpathSync(f);
                    const mode = fs.statSync(real).mode & 0o777;
                    const tmp = real + ".oa-tmp." + process.pid;
                    try {
                        fs.writeFileSync(tmp, u);
                        fs.chmodSync(tmp, mode);
                        fs.renameSync(tmp, real);
                    } catch (e) {
                        try { fs.unlinkSync(tmp); } catch (e2) { /* not made */ }
                        throw e;
                    }
                    n++;
                }
            }
            console.log(n);
        });'
    local changed rewrite_ok=true
    # (the count on the last line is the marker of a finished run; standard input is read once: 1 try)
    if ! changed=$({ find "$root/agents" -type f \( -name 'sessions.json' -o -name '*.trajectory-path.json' \) -print0 2>/dev/null || true; } \
            | _backup_node_call work '^[0-9]+$' 1 "$OA_NODE" -e "$js" "$from" "$to") \
        || ! [[ "$changed" =~ ^[0-9]+$ ]]; then
        rewrite_ok=false
    fi
    # A run that was stopped (the limit, the system) can leave the temporary file of the file it was writing;
    # it is not a session file and must not be archived later.
    find "$root/agents" -type f \( -name 'sessions.json.oa-tmp.*' -o -name '*.trajectory-path.json.oa-tmp.*' \) \
        -exec rm -f {} + 2>/dev/null || true
    if [ "$rewrite_ok" = false ]; then
        echo -e "${YELLOW}[WARN]${NC} Could not update the session paths from $from — restored sessions may point to the other device."
        return 0
    fi
    if [ "${changed:-0}" -gt 0 ]; then
        echo -e "${GREEN}[OK]${NC}   Updated the session paths in $changed file(s) ($from → $to)"
    fi
}

# Print what a backup's manifest says, one record per line:
#   LAYOUT <posix|legacy>
#   ROOT <archive root>
#   ASSET <kind> <TAB> <sourcePath> <TAB> <archivePath>
# Fails if the manifest cannot be read.
_backup_manifest_info() {
    local archive="$1"
    local manifest
    manifest=$(gzip -dc "$archive" 2>/dev/null | tar -xf - --wildcards --no-wildcards-match-slash "*/manifest.json" -O 2>/dev/null | head -c 1048576)
    [ -n "$manifest" ] || return 1

    if [ -n "$OA_NODE" ]; then
        # (END is printed last: the marker of a finished run. The manifest comes from a variable, so a run
        # that the limit stopped can be done once more.)
        local out rc try=0
        while [ "$try" -lt 2 ]; do
            try=$((try + 1))
            rc=0
            out=$(printf '%s' "$manifest" | _backup_node_call probe '^END$' 1 "$OA_NODE" -e '
                let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
                    let m; try { m = JSON.parse(s); } catch (e) { process.exit(1); }
                    const assets = Array.isArray(m.assets) ? m.assets : [];
                    const posix = assets.some(a => String(a.archivePath || "").includes("/payload/posix/"));
                    console.log("LAYOUT " + (posix ? "posix" : "legacy"));
                    console.log("ROOT " + (m.archiveRoot || ""));
                    console.log("RUNTIME " + (m.runtimeVersion || ""));
                    for (const a of assets) console.log("ASSET " + [a.kind, a.sourcePath, a.archivePath].join("\t"));
                    console.log("END");
                });') || rc=$?
            [ "$rc" -eq 124 ] || break
            [ "$try" -ge 2 ] || echo "Note: Node.js did not answer within the limit; trying once more." >&2
        done
        [ "$rc" -ne 124 ] || return 75
        [ "$rc" -eq 0 ] || return 1
        [ "$(printf '%s\n' "$out" | tail -1)" = END ] || return 1
        printf '%s\n' "$out" | grep -vx END
        return 0
    fi

    # No node: only the old layout can be recognised without a JSON parser
    if printf '%s' "$manifest" | grep -q '/payload/posix/'; then
        return 1
    fi
    echo "LAYOUT legacy"
    echo "ROOT $(printf '%s' "$manifest" | grep '"archiveRoot"' | sed 's/.*"archiveRoot"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')"
    echo "RUNTIME $(printf '%s' "$manifest" | grep '"runtimeVersion"' | sed 's/.*"runtimeVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')"
}

# Keep the newest <keep> archives in <dir> and delete the rest. Only ever used on the
# folders that oa itself fills (pre-restore, pre-update), never on backups you made.
# Usage: _backup_prune_dir <dir> <keep> [<archive to keep in any case>]
_backup_prune_dir() {
    local dir="$1" keep="$2" spare="${3:-}" old
    # shellcheck disable=SC2012
    # (|| true: with no archives ls fails, and that must not end the caller)
    ls -t "$dir"/*.tar.gz 2>/dev/null | tail -n +"$((keep + 1))" \
        | while IFS= read -r old; do [ "$old" = "$spare" ] || rm -f "$old"; done || true
    return 0
}

# Create one backup archive. Runs in a subshell so temporary files are always
# cleaned up. Prints progress; returns non-zero on failure.
# Usage: _backup_create <data_dir> <archive_path> <archive_root>
_backup_create() (
    # data_dir is the folder as configured (it names things in the archive and the
    # manifest); real_dir is where the files really are (the folder may be a link).
    data_dir="$1"
    real_dir="$(cd "$1" 2>/dev/null && pwd -P)" || exit 1
    archive_path="$2"
    archive_root="$3"
    umask 077

    parent="$(dirname "$real_dir")"
    base="$(basename "$real_dir")"

    if ! _have_gnu_tar; then
        echo -e "${RED}[FAIL]${NC} GNU tar is required (pkg install tar)"
        exit 1
    fi
    # These characters would break the name rewriting below
    case "$data_dir$archive_root" in
        *,*|*'&'*|*'\'*)
            echo -e "${RED}[FAIL]${NC} Unsupported character in path: $data_dir"
            exit 1
            ;;
    esac

    tmpdir=$(mktemp -d "${TMPDIR:-/tmp}/oa-backup.XXXXXX") || exit 1
    trap 'rm -rf "$tmpdir"' EXIT
    snap="$tmpdir/snap"
    man="$tmpdir/man"
    mkdir -p "$snap/$base" "$man/$archive_root"

    # ── SQLite: consistent snapshots instead of copying live files ──
    sqlite_excludes=()
    sqlite_rc=0
    _have_node_sqlite || sqlite_rc=$?
    if [ "$sqlite_rc" -eq 75 ]; then
        echo "  Node.js did not answer within ${BACKUP_NODE_PROBE_LIMIT}s, twice."
        echo "  A Node.js start that stalls usually works on the next try."
        exit 75
    fi
    if [ "$sqlite_rc" -eq 0 ]; then
        pairs=()
        while IFS= read -r db; do
            rel="${db#"$real_dir"/}"
            mkdir -p "$snap/$base/$(dirname "$rel")"
            pairs+=("$db" "$snap/$base/$rel.oa-snap")
        done < <(find "$real_dir" -path "$real_dir/extensions/*/node_modules" -prune -o -type f -name '*.sqlite' -print)

        # One node run per database. The copy counts only when node printed OK as its last line: a run that
        # was stopped half way (the limit, the system out of memory) leaves a database file that is not whole,
        # and that must never go into the archive. A database that node reports as failed, or that a run
        # ended without OK, is copied as it is below; a run that the limit stopped (twice) ends the backup,
        # so a stuck node does not turn into a quietly weaker backup.
        snap_js='
            const { DatabaseSync } = require("node:sqlite");
            const fs = require("node:fs");
            const [src, dst] = process.argv.slice(1);
            try {
                const db = new DatabaseSync(src, { readOnly: true });
                db.exec("PRAGMA busy_timeout = 30000;");
                db.prepare("VACUUM INTO ?").run(dst);
                db.close();
                const snap = new DatabaseSync(dst);
                const q = snap.prepare("SELECT 1 AS ok FROM sqlite_master WHERE type = ? AND name = ?")
                    .get("table", "delivery_queue_entries");
                if (q && q.ok === 1) { snap.exec("DELETE FROM delivery_queue_entries; VACUUM;"); }
                snap.close();
                fs.chmodSync(dst, 0o600);
                console.log("OK");
            } catch (e) { console.log("FAIL"); }'
        # (busy_timeout: a database that another process holds locked is waited for up to 30 s, which comes on top
        # of the time the copy itself takes, and both count against OA_NODE_WORK_TIMEOUT.)
        export NODE_NO_WARNINGS=1
        failed=""
        k=0
        while [ "$k" -lt "${#pairs[@]}" ]; do
            db="${pairs[$k]}"
            snap_file="${pairs[$((k + 1))]}"
            k=$((k + 2))
            echo "  Snapshotting ${db#"$real_dir"/} …"
            snap_try=0
            while :; do
                snap_try=$((snap_try + 1))
                # (VACUUM INTO refuses a target that exists; a stopped run leaves it, and its journal, behind)
                rm -f "$snap_file" "$snap_file-journal" "$snap_file-wal" "$snap_file-shm"
                snap_rc=0
                snap_out=$(_backup_node_call work '^OK$' 1 "$OA_NODE" -e "$snap_js" "$db" "$snap_file") || snap_rc=$?
                { [ "$snap_rc" -eq 124 ] && [ "$snap_try" -lt 2 ]; } || break
                echo "Note: Node.js did not finish the snapshot within the limit; trying once more." >&2
            done
            if [ "$snap_rc" -eq 124 ]; then
                rm -f "$snap_file" "$snap_file-journal" "$snap_file-wal" "$snap_file-shm"
                echo "  Node.js did not finish the snapshot of ${db#"$real_dir"/} within ${BACKUP_NODE_WORK_LIMIT}s, twice — the database itself was not changed."
                echo "  Try again; if it repeats, the database may be very large for this device (OA_NODE_WORK_TIMEOUT)."
                exit 75
            fi
            if [ "$snap_rc" -ne 0 ] || [ "$(printf '%s\n' "$snap_out" | tail -1)" != OK ]; then
                rm -f "$snap_file" "$snap_file-journal" "$snap_file-wal" "$snap_file-shm"
                failed="${failed}${db}"$'\n'
            fi
        done
        # A database that could not be snapshotted is copied as it is (with its
        # write-ahead log) so it is still in the archive.
        while IFS= read -r db; do
            [ -n "$db" ] || continue
            echo -e "${YELLOW}[WARN]${NC} Could not snapshot ${db#"$real_dir"/} — copying it as is (it may be inconsistent if the gateway is running)"
            rel="${db#"$real_dir"/}"
            # -journal: the rollback journal of a database that was in use when it crashed
            for ext in "" -wal -shm -journal; do
                if [ -f "$db$ext" ] && ! cp -p "$db$ext" "$snap/$base/$rel$ext.oa-snap"; then
                    echo -e "${YELLOW}[WARN]${NC} Could not copy $rel$ext — it is missing from this backup"
                fi
            done
        done <<< "$failed"
        sqlite_excludes=(--exclude='*.sqlite' --exclude='*.sqlite-wal' --exclude='*.sqlite-shm' --exclude='*.sqlite-journal')
    else
        echo -e "${YELLOW}[WARN]${NC} node:sqlite is not available — SQLite files are copied as they are."
        echo "       Stop the OpenClaw gateway before backing up, or conversation data may be inconsistent."
    fi
    # (what a stopped run left beside a snapshot is not part of the backup)
    find "$snap" -type f \( -name '*.oa-snap-journal' -o -name '*.oa-snap-wal' -o -name '*.oa-snap-shm' \) \
        -exec rm -f {} + 2>/dev/null || true
    (cd "$snap" && find "$base" -type f) > "$tmpdir/snaplist"

    # ── Workspace outside the state directory ──
    ws_dir="$(_backup_external_workspace "$data_dir")"
    ws_base=""
    if [ -n "$ws_dir" ]; then
        ws_base="$(basename "$ws_dir")"
        case "$ws_dir" in
            *,*|*'&'*|*'\'*) ws_dir="" ;;
        esac
        if [ "$ws_base" = "$base" ]; then
            ws_dir=""
        fi
        if [ -n "$ws_dir" ]; then
            ws_real="$(cd "$ws_dir" 2>/dev/null && pwd -P || true)"
            case "$data_dir/" in
                "$ws_dir"/*) ws_inside=true ;;
                *) case "$real_dir/" in "${ws_real:-/nonexistent-ws}"/*) ws_inside=true ;; *) ws_inside=false ;; esac ;;
            esac
            if [ "$ws_inside" = true ]; then
                echo -e "${YELLOW}[WARN]${NC} Your workspace folder ($ws_dir) contains the OpenClaw data folder — it is not included as a separate item."
                ws_dir=""
            fi
        fi
    fi

    # ── Manifest ──
    runtime_version="$(_backup_runtime_version)"
    node_version=""
    if [ -n "$OA_NODE" ]; then
        node_version="$(_backup_node_call probe '^v?[0-9]+\.[0-9]+' 2 "$OA_NODE" --version | head -1)" || node_version=""
    fi
    case "$node_version" in v[0-9]*|[0-9]*) ;; *) node_version="unknown" ;; esac
    assets_json="    {
      \"kind\": \"state\",
      \"sourcePath\": \"$(_json_escape "$data_dir")\",
      \"archivePath\": \"$(_json_escape "$archive_root/payload/posix$data_dir")\"
    }"
    ws_json="[]"
    if [ -n "$ws_dir" ]; then
        assets_json="$assets_json,
    {
      \"kind\": \"workspace\",
      \"sourcePath\": \"$(_json_escape "$ws_dir")\",
      \"archivePath\": \"$(_json_escape "$archive_root/payload/posix$ws_dir")\"
    }"
        ws_json="[\"$(_json_escape "$ws_dir")\"]"
    else
        ws_json="[\"$(_json_escape "$data_dir/workspace")\"]"
    fi
    cat > "$man/$archive_root/manifest.json" <<MANIFEST_EOF
{
  "schemaVersion": $BACKUP_SCHEMA_VERSION,
  "createdAt": "$(date -u +"%Y-%m-%dT%H:%M:%S.000Z")",
  "archiveRoot": "$archive_root",
  "runtimeVersion": "$(_json_escape "$runtime_version")",
  "platform": "linux",
  "nodeVersion": "$(_json_escape "$node_version")",
  "options": { "includeWorkspace": true, "onlyConfig": false },
  "paths": {
    "stateDir": "$(_json_escape "$data_dir")",
    "configPath": "$(_json_escape "$data_dir/openclaw.json")",
    "oauthDir": "$(_json_escape "$data_dir/credentials")",
    "workspaceDirs": $ws_json
  },
  "assets": [
$assets_json
  ]
}
MANIFEST_EOF

    # ── Pack: one tar pass; names are rewritten to the archive layout ──
    # tar and gzip are piped explicitly so gzip is resolved through PATH (tar
    # cannot always exec it on Android). tar exits 1 when a file changed or shrank
    # while it was read (a transcript being appended); that is accepted here.
    base_re="$(_regex_escape "$base")"
    transforms=(--transform "s,^${base_re}\(/\|\$\),$archive_root/payload/posix$data_dir\1,"
                --transform 's,\.oa-snap$,,')
    ws_args=()
    if [ -n "$ws_dir" ]; then
        ws_re="$(_regex_escape "$ws_base")"
        transforms+=(--transform "s,^${ws_re}\(/\|\$\),$archive_root/payload/posix$ws_dir\1,")
        ws_args=(-C "$(dirname "$ws_dir")" "$ws_base")
    fi

    # (plugin-skills is left out: OpenClaw classifies it as regenerable — "plugin skills" in dist/backup-shared of
    # 2026.10.1 and 2026.10.2 — and keeps absolute symbolic links there, which its own `backup verify` refuses.
    # Archives made before this are restored as they are. --anchored: only the folder directly in the state
    # folder, not a copy of it inside a workspace.)
    echo -e "Packing archive…"
    tar -cf - "${transforms[@]}" \
        --exclude="$base/logs" --exclude="$base/delivery-queue" --exclude="$base/session-delivery-queue" \
        --exclude='*.pid' --exclude='*.sock' --exclude='*.tmp' --exclude='*.oa-tmp.*' \
        --exclude="$base/extensions/*/node_modules" "${sqlite_excludes[@]}" \
        --anchored --exclude="$base/plugin-skills" \
        -C "$parent" "$base" \
        "${ws_args[@]}" \
        -C "$snap" -T "$tmpdir/snaplist" \
        -C "$man" "$archive_root" \
        | gzip > "$archive_path"
    rcs=("${PIPESTATUS[@]}")
    if [ "${rcs[1]:-1}" -ne 0 ] || [ "${rcs[0]:-2}" -gt 1 ]; then
        rm -f "$archive_path"
        echo -e "${RED}[FAIL]${NC} Failed to create archive: $archive_path"
        exit 1
    fi
    chmod 600 "$archive_path" 2>/dev/null || true
)

# 0 when the archive is whole: gzip's check of the stream passes, and tar lists it to the end with exit status 0
# and at least one entry (a truncated archive can still list a few entries before tar fails).
# Sets BACKUP_VERIFY_COUNT.
BACKUP_VERIFY_COUNT=0
_backup_verify_archive() {
    local archive="$1" list st
    BACKUP_VERIFY_COUNT=0
    gzip -t "$archive" >/dev/null 2>&1 || return 1
    list=$(mktemp "${TMPDIR:-/tmp}/oa-verify.XXXXXX") || return 1
    # (called from an `if`, where set -e does not apply; the stream is whole by now, so what tar says counts)
    gzip -dc "$archive" 2>/dev/null | tar -tf - >"$list" 2>/dev/null
    st=("${PIPESTATUS[@]}")
    BACKUP_VERIFY_COUNT=$(wc -l <"$list" | tr -d ' ')
    rm -f "$list"
    [ "${st[1]:-1}" -eq 0 ] && [ "${BACKUP_VERIFY_COUNT:-0}" -gt 0 ]
}

# ── cmd_backup ──────────────────────────────────────────────────────────────

cmd_backup() {
    _backup_sweep_temp
    _oa_resolve_node
    if [ "$OA_NODE_STUCK" = true ]; then
        echo -e "${RED}[FAIL]${NC} Node.js did not answer within ${BACKUP_NODE_PROBE_LIMIT}s, twice — no backup was made."
        echo "       Your OpenClaw data was not touched. A Node.js start that stalls usually works on the next try."
        exit 75
    fi
    if ! command -v gzip &>/dev/null; then
        echo "  Installing gzip..."
        pkg install -y gzip 2>/dev/null || { echo -e "${RED}[FAIL]${NC} gzip not found and could not be installed"; exit 1; }
    fi

    local output_dir="${1:-}"

    # Resolve output directory
    if [ -z "$output_dir" ]; then
        output_dir="$BACKUP_DIR"
    fi

    echo ""
    echo -e "${BOLD}OpenClaw on Android — Backup${NC}"
    echo -e "────────────────────────────────────────"

    # Load platform config to get PLATFORM_DATA_DIR
    local platform
    platform=$(detect_platform 2>/dev/null) || platform=""

    if [ -z "$platform" ]; then
        echo -e "${RED}[FAIL]${NC} Could not detect installed platform."
        exit 1
    fi

    load_platform_config "$platform" "$PROJECT_DIR" 2>/dev/null || {
        echo -e "${RED}[FAIL]${NC} Could not load platform config for: $platform"
        exit 1
    }

    local data_dir="$PLATFORM_DATA_DIR"

    if [ ! -d "$data_dir" ]; then
        echo -e "${RED}[FAIL]${NC} Platform data directory not found: $data_dir"
        exit 1
    fi

    local data_real
    data_real="$(cd "$data_dir" && pwd -P)"

    local made_dir=false
    [ -d "$output_dir" ] || made_dir=true
    mkdir -p "$output_dir"
    output_dir="$(cd "$output_dir" && pwd -P)"
    case "$output_dir/" in
        "$data_real"/*)
            [ "$made_dir" = true ] && rmdir "$output_dir" 2>/dev/null
            echo -e "${RED}[FAIL]${NC} The backup folder must not be inside $data_dir"
            exit 1
            ;;
    esac

    echo -e "  Platform:    $platform"
    echo -e "  Source:      $data_dir"
    echo -e "  Destination: $output_dir"
    echo ""

    # Build filename — OpenClaw naming rule
    local ts basename archive_path
    ts=$(_backup_timestamp)
    basename="${ts}-openclaw-backup"
    archive_path="$output_dir/${basename}.tar.gz"

    # (75: node did not answer — passed on as it is, so a caller can tell it from any other failure)
    local create_rc=0
    _backup_create "$data_dir" "$archive_path" "$basename" || create_rc=$?
    if [ "$create_rc" -eq 75 ]; then
        echo -e "${RED}[FAIL]${NC} No backup was made."
        echo "       Your OpenClaw data was not touched."
        exit 75
    fi
    [ "$create_rc" -eq 0 ] || exit "$create_rc"

    echo -e "${GREEN}[OK]${NC}   Archive created: $archive_path"
    echo ""

    # ── Integrity verification ──
    echo -e "Verifying integrity…"

    # Try openclaw backup verify first (preferred — full manifest check)
    local verify_out="" verify_rc=1 verify_reason="" verify_known=false
    if command -v openclaw &>/dev/null; then
        # (the output goes to a file, not a pipe: openclaw restarts itself as a detached child that holds its
        # standard output, and a pipe would keep this call waiting for it after the limit stopped the parent)
        local verify_file
        verify_rc=0
        if verify_file=$(mktemp "${TMPDIR:-/tmp}/oa-verify-out.XXXXXX" 2>/dev/null); then
            # (the redirection is made by a shell that execs openclaw, so the shell's own "Killed" notice, which
            # goes to standard error, stays out of the file)
            { _backup_limited work sh -c 'exec "$@" >"$0" 2>&1 </dev/null' "$verify_file" \
                openclaw backup verify "$archive_path"; } 2>/dev/null || verify_rc=$?
            verify_out=$(head -c 65536 "$verify_file" 2>/dev/null) || verify_out=""
            rm -f "$verify_file"
        else
            { _backup_limited work openclaw backup verify "$archive_path" >/dev/null 2>&1 </dev/null; } 2>/dev/null || verify_rc=$?
        fi
    fi
    if [ "$verify_rc" -eq 0 ] && command -v openclaw &>/dev/null; then
        echo -e "${GREEN}[OK]${NC}   Integrity check passed (openclaw backup verify)"
    else
        if command -v openclaw &>/dev/null; then
            # (openclaw's own verify has been seen to refuse archives that are whole; say why, once)
            if [ "$verify_rc" -eq 124 ]; then
                verify_reason="no answer within ${BACKUP_NODE_WORK_LIMIT} s"
            else
                # (the archive path is taken out first: OpenClaw's message starts with it, "Backup archive
                # verification failed: <path>. <reason>", and a long path would use up the length of the line.
                # grep finds nothing in an empty output: that must not end the script under set -e / pipefail)
                verify_out="${verify_out//"$archive_path"/<archive>}"
                verify_reason=$(printf '%s\n' "$verify_out" | sed "s/$(printf '\033')\[[0-9;]*[A-Za-z]//g" \
                    | { grep -v '^[[:space:]]*$' || true; } | tail -1) || verify_reason=""
                verify_reason="${verify_reason#*verification failed: <archive>. }"
                verify_reason="${verify_reason#"${verify_reason%%[![:space:]]*}"}"
                # (an absolute link is named as such only when that is the reason itself; the plugin-skills links
                # OpenClaw makes are left out of the archive, so the link is another one; any other reason is shown
                # as it is)
                case "$verify_reason" in
                    *"symbolic link target must be relative"*) verify_known=true ;;
                esac
                verify_reason="${verify_reason:0:200}"
                [ -n "$verify_reason" ] || verify_reason="exit code $verify_rc"
            fi
            if [ "$verify_known" = true ]; then
                echo "Note: openclaw backup verify does not accept an absolute symbolic link that the archive contains; the archive was checked with gzip and tar instead."
            else
                echo "Note: openclaw backup verify could not be used for this archive ($verify_reason); it was checked with gzip and tar instead."
            fi
        fi
        # Fallback: the compressed stream (gzip -t) and the tar listing, each with its own exit status
        if _backup_verify_archive "$archive_path"; then
            echo -e "${GREEN}[OK]${NC}   Integrity check passed (gzip and tar, $BACKUP_VERIFY_COUNT entries)"
        else
            echo -e "${RED}[FAIL]${NC} Integrity check failed — the archive is not whole: $archive_path"
            exit 1
        fi
    fi

    echo ""
    echo -e "${GREEN}Backup complete.${NC}"
    echo -e "  File: $archive_path"
    echo -e "  Size: $(du -sh "$archive_path" | cut -f1)"
    echo ""
    echo -e "${YELLOW}[NOTE]${NC} This backup contains your API keys and login credentials. Keep it private."
    echo ""
}

# ── cmd_restore ─────────────────────────────────────────────────────────────

cmd_restore() {
    _backup_sweep_temp
    _oa_resolve_node
    if [ "$OA_NODE_STUCK" = true ]; then
        echo -e "${RED}[FAIL]${NC} Node.js did not answer within ${BACKUP_NODE_PROBE_LIMIT}s, twice — nothing was restored."
        echo "       Run: oa --restore   (again; a Node.js start that stalls usually works on the next try)"
        exit 75
    fi
    local force_no_safety=false
    case "${1:-}" in
        --force-no-safety) force_no_safety=true ;;
        "") ;;
        *)
            echo -e "${RED}[FAIL]${NC} Unknown option for --restore: $1"
            echo "       Usage: oa --restore [--force-no-safety]"
            exit 1
            ;;
    esac
    if ! command -v gzip &>/dev/null; then
        echo "  Installing gzip..."
        pkg install -y gzip 2>/dev/null || { echo -e "${RED}[FAIL]${NC} gzip not found and could not be installed"; exit 1; }
    fi

    echo ""
    echo -e "${BOLD}OpenClaw on Android — Restore${NC}"
    echo -e "────────────────────────────────────────"

    # Collect backup files
    if [ ! -d "$BACKUP_DIR" ]; then
        echo -e "${RED}[FAIL]${NC} Backup directory not found: $BACKUP_DIR"
        echo -e "       Run ${BOLD}oa --backup${NC} first."
        exit 1
    fi

    local -a backups=()
    while IFS= read -r f; do
        backups+=("$f")
    done < <(ls -t "$BACKUP_DIR"/*.tar.gz 2>/dev/null || true; ls -t "$BACKUP_DIR"/pre-update/*.tar.gz 2>/dev/null || true; ls -t "$BACKUP_DIR"/pre-restore/*.tar.gz 2>/dev/null || true)

    if [ ${#backups[@]} -eq 0 ]; then
        echo -e "${RED}[FAIL]${NC} No backup files found in $BACKUP_DIR"
        echo -e "       Run ${BOLD}oa --backup${NC} first."
        exit 1
    fi

    # Display numbered list
    echo -e "Available backups:"
    echo ""
    local idx=1
    for f in "${backups[@]}"; do
        local fname size
        fname="${f#"$BACKUP_DIR"/}"
        size=$(du -sh "$f" 2>/dev/null | cut -f1)
        printf "  ${BOLD}[%d]${NC} %s  ${YELLOW}(%s)${NC}\n" "$idx" "$fname" "$size"
        idx=$((idx + 1))
    done

    echo ""
    local choice
    if (echo -n "" > /dev/tty) 2>/dev/null; then
        read -rp "Select backup to restore [1-${#backups[@]}]: " choice < /dev/tty
    else
        read -rp "Select backup to restore [1-${#backups[@]}]: " choice
    fi

    # Validate input
    if ! [[ "$choice" =~ ^[0-9]+$ ]] || [ "$choice" -lt 1 ] || [ "$choice" -gt "${#backups[@]}" ]; then
        echo -e "${RED}[FAIL]${NC} Invalid selection: $choice"
        exit 1
    fi

    local selected="${backups[$((choice - 1))]}"
    echo ""
    echo -e "  Selected: ${BOLD}$(basename "$selected")${NC}"

    if ! _have_gnu_tar; then
        echo -e "${RED}[FAIL]${NC} GNU tar is required (pkg install tar)"
        exit 1
    fi

    # Detect platform from manifest
    echo -e "  Detecting platform…"
    local platform
    platform=$(_detect_backup_platform "$selected") || platform=""

    if [ -z "$platform" ]; then
        echo -e "${RED}[FAIL]${NC} Could not determine backup platform from manifest."
        exit 1
    fi

    local restore_root
    restore_root=$(_restore_root_for_platform "$platform")

    if [ -z "$restore_root" ]; then
        echo -e "${RED}[FAIL]${NC} Unsupported platform in backup: $platform"
        exit 1
    fi

    # Read the manifest: archive layout, archive root and assets
    local info layout="" archive_root="" backup_runtime=""
    local -a asset_kinds=() asset_sources=() asset_paths=()
    local info_rc=0
    info=$(_backup_manifest_info "$selected") || info_rc=$?
    if [ "$info_rc" -eq 75 ]; then
        echo -e "${RED}[FAIL]${NC} Node.js did not answer within ${BACKUP_NODE_PROBE_LIMIT}s, twice — nothing was restored."
        echo "       Run: oa --restore   (again; a Node.js start that stalls usually works on the next try)"
        exit 75
    elif [ "$info_rc" -ne 0 ]; then
        echo -e "${RED}[FAIL]${NC} Could not read the backup manifest (this backup needs node to be read)."
        exit 1
    fi
    while IFS= read -r line; do
        case "$line" in
            "LAYOUT "*) layout="${line#LAYOUT }" ;;
            "ROOT "*) archive_root="${line#ROOT }" ;;
            "RUNTIME "*) backup_runtime="${line#RUNTIME }" ;;
            "ASSET "*)
                IFS=$'\t' read -r _k _s _p <<< "${line#ASSET }"
                asset_kinds+=("$_k")
                asset_sources+=("$_s")
                asset_paths+=("$_p")
                ;;
        esac
    done <<< "$info"

    if [ -z "$archive_root" ]; then
        echo -e "${RED}[FAIL]${NC} Could not read archiveRoot from manifest."
        exit 1
    fi

    echo -e "  Platform:    $platform"
    echo -e "  Restore to:  $restore_root"
    echo ""

    # The gateway keeps its databases open; replacing them underneath it corrupts them.
    if _gateway_running; then
        echo -e "${RED}[FAIL]${NC} The OpenClaw gateway appears to be running."
        echo -e "       Stop it first (Ctrl+C in its terminal, or: pkill -f 'openclaw.*gateway'), then run ${BOLD}oa --restore${NC} again."
        exit 1
    fi

    # A backup made with another OpenClaw version than the installed one
    local installed_runtime restore_note=""
    installed_runtime=$(_backup_runtime_version)
    if [[ "$backup_runtime" =~ ^[0-9]+\.[0-9]+ ]] && [[ "$installed_runtime" =~ ^[0-9]+\.[0-9]+ ]] \
        && declare -f oa_version_gt >/dev/null; then
        if oa_version_gt "$backup_runtime" "$installed_runtime"; then
            echo -e "${YELLOW}[WARN]${NC} This backup was made with a newer OpenClaw ($backup_runtime) than the one installed ($installed_runtime)."
            echo "       The installed version may not be able to read its data, and this restore cannot be undone by"
            echo "       reinstalling the program. Normally only restore backups made by the same or an older version."
            if ! _ask_yn_default_no "Restore it anyway?"; then
                echo -e "Restore cancelled."
                exit 0
            fi
            echo ""
        elif oa_version_gt "$installed_runtime" "$backup_runtime"; then
            restore_note="This backup was made with an older OpenClaw ($backup_runtime). If the gateway says a state migration is required, run: openclaw doctor --fix"
        fi
    fi

    # ── Warning ──
    echo -e "${YELLOW}┌─────────────────────────────────────────────────┐${NC}"
    echo -e "${YELLOW}│  WARNING: This will overwrite your current       │${NC}"
    echo -e "${YELLOW}│  configuration and data in:                      │${NC}"
    echo -e "${YELLOW}│                                                   │${NC}"
    echo -e "${YELLOW}│    $restore_root${NC}"
    echo -e "${YELLOW}│                                                   │${NC}"
    echo -e "${YELLOW}│  Existing files will be replaced. A safety       │${NC}"
    echo -e "${YELLOW}│  backup of the current data is made first.       │${NC}"
    echo -e "${YELLOW}└─────────────────────────────────────────────────┘${NC}"
    echo ""

    if ! ask_yn "Continue with restore?"; then
        echo -e "Restore cancelled."
        exit 0
    fi

    # ── Safety backup of the current state ──
    if [ -d "$restore_root" ]; then
        local pre_dir pre_root pre_archive
        pre_dir="$BACKUP_DIR/pre-restore"
        pre_root="$(_backup_timestamp)-openclaw-backup"
        pre_archive="$pre_dir/${pre_root}.tar.gz"
        local pre_ready=true pre_rc=1
        mkdir -p "$pre_dir" 2>/dev/null || pre_ready=false
        echo ""
        echo -e "Saving a safety backup of the current data…"
        pre_rc=1
        if [ "$pre_ready" = true ]; then
            pre_rc=0
            _backup_create "$restore_root" "$pre_archive" "$pre_root" || pre_rc=$?
        fi
        if [ "$pre_rc" -eq 0 ]; then
            echo -e "${GREEN}[OK]${NC}   Safety backup: $pre_archive"
            echo "       To undo this restore, run oa --restore again and pick it (it is listed as pre-restore/…)."

        elif [ "$force_no_safety" = true ]; then
            echo -e "${YELLOW}[WARN]${NC} Could not save a safety backup of the current data — continuing because you asked for --force-no-safety."
        elif [ "$pre_rc" -eq 75 ]; then
            echo -e "${RED}[FAIL]${NC} Node.js did not answer, so no safety backup of the current data was made and nothing was restored."
            echo "       Run: oa --restore   (again)"
            echo "       To restore without a safety backup (the current data cannot be recovered afterwards): oa --restore --force-no-safety"
            exit 75
        else
            echo -e "${RED}[FAIL]${NC} Could not save a safety backup of the current data, so nothing was restored."
            echo "       Free some space or check that the temporary folder and the backup folder can be written, then run oa --restore again."
            echo "       To restore without a safety backup (the current data cannot be recovered afterwards): oa --restore --force-no-safety"
            exit 1
        fi
    fi

    echo ""
    echo -e "Restoring…"
    mkdir -p "$restore_root"

    # Old write-ahead logs would be replayed onto the restored databases
    find "$restore_root/" -name node_modules -prune -o -type f \
        \( -name '*.sqlite-wal' -o -name '*.sqlite-shm' \) -exec rm -f {} + 2>/dev/null || true

    if [ "$layout" = "posix" ]; then
        # <root>/payload/posix/<source path>/... → strip root, payload, posix and
        # the source path. The state folder is unpacked into this device's own
        # ~/.openclaw (the backup may come from a device with another home path);
        # a workspace kept outside it goes back to where it was if that place
        # exists here, otherwise into ~/.openclaw/workspace.
        local i restored=0 state_moved=false state_restored=false repaired repair_left repair_fn
        for i in "${!asset_kinds[@]}"; do
            local kind="${asset_kinds[$i]}" src="${asset_sources[$i]}" apath="${asset_paths[$i]}" target=""
            case "$kind" in
                state) target="$restore_root" ;;
                workspace)
                    if [ -d "$(dirname "$src")" ]; then
                        target="$src"
                    else
                        target="$restore_root/workspace"
                    fi
                    ;;
                *)
                    echo -e "${YELLOW}[WARN]${NC} Skipping unsupported backup item: $kind ($src)"
                    continue
                    ;;
            esac
            if ! mkdir -p "$target" 2>/dev/null; then
                if [ "$kind" = "workspace" ] && [ "$target" = "$src" ]; then
                    echo -e "${YELLOW}[WARN]${NC} Cannot write to $src — restoring the workspace into $restore_root/workspace instead."
                    target="$restore_root/workspace"
                    mkdir -p "$target"
                else
                    echo -e "${RED}[FAIL]${NC} Cannot create $target"
                    exit 1
                fi
            fi
            echo "  Restoring $kind → $target"
            if ! gzip -dc "$selected" 2>/dev/null | tar -xf - \
                --strip-components=$((3 + $(_path_segments "$src"))) \
                -C "$target" \
                "$apath" 2>/dev/null; then
                echo -e "${RED}[FAIL]${NC} Extraction failed ($kind)."
                exit 1
            fi
            restored=$((restored + 1))
            if [ "$kind" = "state" ] && [ "$src" != "$restore_root" ]; then
                _restore_rewrite_paths "$restore_root" "$src" "$restore_root"
                state_moved=true
            fi
            [ "$kind" = "state" ] && state_restored=true
        done
        if [ "$restored" -eq 0 ]; then
            echo -e "${RED}[FAIL]${NC} Nothing to restore in this backup."
            exit 1
        fi

        if [ "$state_restored" = true ] && [ -n "$OA_NODE" ] && declare -f oa_repair_state_paths >/dev/null && declare -f oa_repair_config_paths >/dev/null; then
            # Paths of another Android app in the restored data (a backup can carry those of a third
            # environment too, so this does not depend on where the backup came from): OpenClaw 9.x stops
            # on them with EACCES
            for repair_fn in oa_repair_state_paths oa_repair_config_paths; do
                # (_backup_node_repair is the "node" of these functions: node under the limit of the work class)
                read -r repaired repair_left _ <<< "$("$repair_fn" "$restore_root" _backup_node_repair)"
                case "$repaired" in
                    error:*)
                        if [ "$repair_fn" = oa_repair_config_paths ]; then
                            echo -e "${YELLOW}[WARN]${NC} Could not fix the folder paths in the restored config. Nothing was changed."
                            echo "       Check $restore_root/openclaw.json: the workspace and agentDir values under agents may point to another app's folder."
                        else
                            echo -e "${YELLOW}[WARN]${NC} Could not check the other-app paths in the restored state."
                        fi
                        ;;
                    0) ;;
                    *) echo -e "${GREEN}[OK]${NC}   Updated $repaired path(s) in the restored data that pointed to another app or device" ;;
                esac
                if [ "${repair_left:-0}" -gt 0 ] 2>/dev/null; then
                    echo -e "${YELLOW}[WARN]${NC} $repair_left path(s) point into another app's folder and were left as they are."
                fi
            done
        fi

        if [ "$state_moved" = true ] && [ -f "$restore_root/openclaw.json" ]; then
            local browser_path
            browser_path=$(sed -n 's/.*"executablePath"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$restore_root/openclaw.json" | head -1)
            if [ -n "$browser_path" ] && [ ! -e "$browser_path" ]; then
                local known new_path=""
                for known in /data/data/com.termux/files/usr /data/user/0/com.termux/files/usr \
                             /data/data/com.openclaw.android/files/usr /data/user/0/com.openclaw.android/files/usr; do
                    case "$browser_path" in
                        "$known"/*)
                            if [ -n "${PREFIX:-}" ] && [ -e "${PREFIX}${browser_path#"$known"}" ]; then
                                new_path="${PREFIX}${browser_path#"$known"}"
                            fi
                            break
                            ;;
                    esac
                done
                if [ -n "$new_path" ] && timeout 30 openclaw config set browser.executablePath "$new_path" &>/dev/null; then
                    echo -e "${GREEN}[OK]${NC}   browser.executablePath updated to $new_path"
                else
                    echo -e "${YELLOW}[WARN]${NC} browser.executablePath in your config ($browser_path) does not exist on this device."
                    echo "       Set it with: openclaw config set browser.executablePath <path>"
                fi
            fi
        fi

        # A workspace path that points to another device's directory
        if command -v openclaw &>/dev/null; then
            local ws_cfg
            ws_cfg=$(_backup_openclaw_get agents.defaults.workspace)
            if [ -n "$ws_cfg" ] && [ ! -d "$ws_cfg" ] && [ -d "$restore_root/workspace" ]; then
                if timeout 30 openclaw config set agents.defaults.workspace "$restore_root/workspace" &>/dev/null; then
                    echo -e "${GREEN}[OK]${NC}   Workspace path updated to $restore_root/workspace"
                else
                    echo -e "${YELLOW}[WARN]${NC} Workspace path in the config points to $ws_cfg (not found on this device)."
                    echo "       Fix it with: openclaw config set agents.defaults.workspace $restore_root/workspace"
                fi
            fi
        fi
    else
        # Older oa backups: <root>/payload/<relative path>
        if ! gzip -dc "$selected" 2>/dev/null | tar -xf - \
            --strip-components=2 \
            --exclude="${archive_root}/manifest.json" \
            -C "$restore_root" \
            "${archive_root}/payload/" 2>/dev/null; then
            echo -e "${RED}[FAIL]${NC} Extraction failed."
            exit 1
        fi
    fi

    echo -e "${GREEN}[OK]${NC}   Restore complete."

    # Keep the newest five safety backups (never the one just restored); backups you
    # made yourself are never touched.
    _backup_prune_dir "$BACKUP_DIR/pre-restore" 5 "$selected"

    echo ""
    echo -e "  Restored to: $restore_root"
    echo ""
    echo -e "${YELLOW}[NOTE]${NC} Files created after this backup were kept."
    [ -z "$restore_note" ] || echo -e "${YELLOW}[NOTE]${NC} $restore_note"
    echo -e "${YELLOW}[NOTE]${NC} Restart the OpenClaw gateway for changes to take effect."
    echo ""
}
