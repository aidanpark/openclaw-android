#!/usr/bin/env bash
# lib.sh — Shared function library for all orchestrators
# Usage: source "$SCRIPT_DIR/scripts/lib.sh"  (from repo)
#        source "$PROJECT_DIR/scripts/lib.sh"  (from installed copy)

# ── Color constants ──
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

# ── Project constants ──
PROJECT_DIR="$HOME/.openclaw-android"
BIN_DIR="$PROJECT_DIR/bin"
PLATFORM_MARKER="$PROJECT_DIR/.platform"
REPO_BASE_ORIGIN="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
REPO_BASE_MIRRORS=(
    "https://ghfast.top/https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
    "https://ghproxy.net/https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
    "https://mirror.ghproxy.com/https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
)
NPM_REGISTRY_ORIGIN="https://registry.npmjs.org/"
NPM_REGISTRY_MIRROR="https://registry.npmmirror.com/"
NPM_REGISTRY_CACHE="$PROJECT_DIR/.npm-registry"

# Detect reachable REPO_BASE (origin first, then mirrors)
resolve_repo_base() {
    if curl -sI --connect-timeout 3 "$REPO_BASE_ORIGIN/oa.sh" >/dev/null 2>&1; then
        REPO_BASE="$REPO_BASE_ORIGIN"
        return 0
    fi
    for mirror in "${REPO_BASE_MIRRORS[@]}"; do
        if curl -sI --connect-timeout 3 "$mirror/oa.sh" >/dev/null 2>&1; then
            echo -e "  ${YELLOW}[MIRROR]${NC} Using mirror: ${mirror%%/oa.sh*}"
            REPO_BASE="$mirror"
            return 0
        fi
    done
    # Fallback to origin even if unreachable
    REPO_BASE="$REPO_BASE_ORIGIN"
    return 1
}

# Detect reachable npm registry and export NPM_CONFIG_REGISTRY (origin first, then mirror)
resolve_npm_registry() {
    local choice
    local cache_file="$NPM_REGISTRY_CACHE"
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

# Fix shebangs in npm globally-installed CLI entry points
# Rewrites #!/usr/bin/env node → #!$BIN_DIR/node so CLIs work on Android
fix_npm_global_shebangs() {
    local _js
    for _js in "$PREFIX/lib/node_modules"/*/bin/*.js \
               "$PREFIX/lib/node_modules"/@*/*/bin/*.js; do
        [ -f "$_js" ] || continue
        head -1 "$_js" | grep -q '^#!/usr/bin/env node$' || continue
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$_js"
    done
}

# Initialize REPO_BASE
REPO_BASE="$REPO_BASE_ORIGIN"

BASHRC_MARKER_START="# >>> OpenClaw on Android >>>"
BASHRC_MARKER_END="# <<< OpenClaw on Android <<<"
OA_VERSION="1.3.4"

# ── Platform detection ──
# 1. Explicit marker file (new install and after first update)
# 2. Legacy detection (v1.0.2 and below, one-time)
# 3. Detection failure
detect_platform() {
    if [ -f "$PLATFORM_MARKER" ]; then
        cat "$PLATFORM_MARKER"
        return 0
    fi
    if command -v openclaw &>/dev/null; then
        echo "openclaw"
        mkdir -p "$(dirname "$PLATFORM_MARKER")"
        echo "openclaw" > "$PLATFORM_MARKER"
        return 0
    fi
    echo ""
    return 1
}

# ── Platform name validation ──
validate_platform_name() {
    local name="$1"
    if [ -z "$name" ]; then
        echo -e "${RED}[FAIL]${NC} Platform name is empty"
        return 1
    fi
    # Only lowercase alphanumeric + hyphens/underscores allowed
    if [[ ! "$name" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then
        echo -e "${RED}[FAIL]${NC} Invalid platform name: $name"
        return 1
    fi
    return 0
}

# ── User confirmation prompt ──
# Reads from /dev/tty so it works even in curl|bash mode.
# OA_ASSUME_YES=1 / 0 answers every such question without asking (the Claw app runs the updater as a
# child process with no terminal and sets it); unset keeps the behaviour above. The question is still
# printed, with the answer, so the log shows what was decided.
ask_yn() {
    local prompt="$1"
    local reply
    case "${OA_ASSUME_YES:-}" in
        1) echo "$prompt [Y/n] y (OA_ASSUME_YES=1)"; return 0 ;;
        0) echo "$prompt [Y/n] n (OA_ASSUME_YES=0)"; return 1 ;;
    esac
    if (echo -n "" > /dev/tty) 2>/dev/null; then
        read -rp "$prompt [Y/n] " reply < /dev/tty
    else
        read -rp "$prompt [Y/n] " reply
    fi
    [[ "${reply:-}" =~ ^[Nn]$ ]] && return 1
    return 0
}

# ── Free storage ──
# OpenClaw alone takes about 1 GB while it is installed (about 720 MB installed + about
# 310 MB npm cache, measured for 2026.9.8); a fresh install adds Node.js and the runtime.
: "${OA_MIN_FREE_INSTALL_MB:=2000}"    # fresh install
: "${OA_MIN_FREE_UPDATE_MB:=2000}"     # update that changes the pinned OpenClaw or Node.js (old + new copy + cache)

# Free space in MB on the filesystem that holds $1 (default $PREFIX); empty if unknown.
# The df of the Termux packages does not start in the app terminal ("bad interpreter": its
# shebang names Termux's own path), so the system's df is tried next.
# shellcheck disable=SC2120  # platforms/openclaw/status.sh and scripts/check-env.sh pass a directory; no caller in this file does
oa_free_mb() {
    local dir="${1:-${PREFIX:-/}}" df_cmd mb=""
    for df_cmd in df /system/bin/df; do
        # -P: one line per file system; -k: 1024-byte units. A failing df gives an empty answer, not an abort
        mb=$({ "$df_cmd" -Pk "$dir" 2>/dev/null || "$df_cmd" -k "$dir" 2>/dev/null || true; } | awk 'NR==2 {print int($4/1024)}')
        if [[ "$mb" =~ ^[0-9]+$ ]]; then
            echo "$mb"
            return 0
        fi
    done
    return 0
}

# oa_check_free_space <needed_mb> <what>: 0 when there is room (or the free space
# cannot be read); otherwise says how much is needed and returns 1
oa_check_free_space() {
    local need="$1" what="$2" have
    have=$(oa_free_mb)
    [[ "$have" =~ ^[0-9]+$ ]] || return 0
    [ "$have" -ge "$need" ] && return 0
    echo -e "${RED}[FAIL]${NC} Not enough free storage to ${what}: ${need} MB needed, ${have} MB available."
    echo "       Free some space (clear other apps' caches, delete unused files, or remove ~/.npm/_cacache) and run it again."
    return 1
}

# ── Calls to Node.js under a time limit ──
# A Node.js start that stalls (observed on the app environment) must not stall the update with it.
# oa_node_limited <probe|work|seconds> <command> [args…]
#   probe    the limit of OA_NODE_PROBE_TIMEOUT (default 30 s): a call that answers at once
#   work     the limit of OA_NODE_WORK_TIMEOUT (default 300 s): a call that works through data
#   seconds  that many seconds (at least 5: below that a kill from outside near the limit could not be told from
#            the limit, because SECONDS counts whole seconds)
# Returns 75 when the limit stopped the command (the command's own 124, or 137/143 only after the whole time —
# after less they came from somewhere else, e.g. the system out of memory), 2 when the first argument is none of
# the three or the command is a shell function (timeout starts programs, not functions: wrap the call in a
# function that calls oa_node_limited with the program inside), and the command's own exit code otherwise.
# Without a timeout program the command runs unlimited. Standard input and output pass through unchanged.
# probe stops the whole process group of the command: a script that starts node as a child (npm's wrapper does)
# leaves nothing behind that keeps a pipe open and the caller waiting. The call must not read the terminal
# (a version, a listing). work keeps the group of the caller (--foreground, so that Ctrl-C reaches it): call a
# program that execs node (the node wrapper) with it, not a script that starts node as a child.
# The group of a probe is not the group of the terminal, so Ctrl-C does not reach it. The call therefore catches
# SIGINT and SIGTERM of the caller while it waits, stops the command (its whole group, with the 5 s kill after),
# gives the caller's own handlers back and returns 130 (SIGINT) or 143 (SIGTERM) at once. A caller that has no
# handler of its own and is meant to end on Ctrl-C reads 130 as "interrupted" (it is not 75, and not an answer).
# The environment values follow the rules of scripts/backup.sh (digits only, 5 to 999999, else the default), so
# one OA_NODE_*_TIMEOUT means the same in every script. Callers on a cached older lib.sh check
# `declare -f oa_node_limited` first.
_OA_TIMEOUT_CMD=()
_OA_TIMEOUT_GROUP=()
_OA_TIMEOUT_READY=""
_oa_limit_value() {
    local v="$1" d="$2"
    case "$v" in ''|*[!0-9]*) printf '%s' "$d"; return 0 ;; esac
    v=${v#"${v%%[!0]*}"}
    if [ -z "$v" ] || [ "${#v}" -gt 6 ] || [ "$v" -lt 5 ]; then
        printf '%s' "$d"
    else
        printf '%s' "$v"
    fi
}
# oa_node_limit_seconds <probe|work>: the limit in seconds that class has now (the environment value after the
# rules above), for messages that name it. Prints nothing and returns 2 for another class.
oa_node_limit_seconds() {
    case "${1:-}" in
        probe) _oa_limit_value "${OA_NODE_PROBE_TIMEOUT:-}" 30 ;;
        work)  _oa_limit_value "${OA_NODE_WORK_TIMEOUT:-}" 300 ;;
        *) return 2 ;;
    esac
}
# oa_end_on_signal <status>: for a caller that runs oa_node_limited in a command substitution. When the status is
# 130 or 143 (the call was ended by Ctrl-C / SIGTERM) the script ends as that signal would have ended it, with
# its own handler if it has one; otherwise it returns 0 and nothing happens.
oa_end_on_signal() {
    case "${1:-}" in
        130) kill -s INT "$$" ;;
        143) kill -s TERM "$$" ;;
    esac
    return 0
}
# One command under the group-stopping timeout (the probe class), run in the background so that a signal to the
# caller is not swallowed by the wait: see the comment above oa_node_limited. Standard input is the caller's.
_OA_NL_SIG=0
_oa_run_group() {
    local limit="$1" pid wrc=0 old_int old_term
    shift
    _OA_NL_SIG=0
    old_int=$(trap -p INT 2>/dev/null) || old_int=""
    old_term=$(trap -p TERM 2>/dev/null) || old_term=""
    # (in a command substitution the caller's handlers are not in effect, although `trap -p` reports them: only an
    # ignore carries over into it, so only that is given back)
    if [ "${BASHPID:-$$}" != "$$" ]; then
        case "$old_int" in *"-- ''"*|*'-- ""'*) ;; *) old_int="" ;; esac
        case "$old_term" in *"-- ''"*|*'-- ""'*) ;; *) old_term="" ;; esac
    fi
    # (a signal the caller ignores stays ignored: nothing is caught for it)
    case "$old_int" in *"-- ''"*|*'-- ""'*) ;; *) trap '_OA_NL_SIG=130' INT ;; esac
    case "$old_term" in *"-- ''"*|*'-- ""'*) ;; *) trap '_OA_NL_SIG=143' TERM ;; esac
    "${_OA_TIMEOUT_GROUP[@]}" "$limit" "$@" <&0 &
    pid=$!
    wait "$pid" || wrc=$?
    if [ "$_OA_NL_SIG" -ne 0 ]; then
        kill -TERM "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        wrc=$_OA_NL_SIG
    fi
    trap - INT TERM
    [ -z "$old_int" ] || eval "$old_int"
    [ -z "$old_term" ] || eval "$old_term"
    return "$wrc"
}
oa_node_limited() {
    local class="${1:-}" limit t0 rc=0 group=false
    case "$class" in
        probe) limit=$(_oa_limit_value "${OA_NODE_PROBE_TIMEOUT:-}" 30); group=true ;;
        work)  limit=$(_oa_limit_value "${OA_NODE_WORK_TIMEOUT:-}" 300) ;;
        ''|*[!0-9]*)
            echo "oa_node_limited: the first argument must be probe, work or a number of seconds (got '$class')." >&2
            return 2 ;;
        *)
            limit=${class#"${class%%[!0]*}"}
            if [ -z "$limit" ] || [ "${#limit}" -gt 6 ]; then
                echo "oa_node_limited: the number of seconds must be 5 to 999999 (got '$class')." >&2
                return 2
            fi
            [ "$limit" -ge 5 ] || limit=5 ;;
    esac
    shift
    if [ "$#" -eq 0 ]; then
        echo "oa_node_limited: no command." >&2
        return 2
    fi
    if [ "$(type -t "$1" 2>/dev/null)" = function ]; then
        echo "oa_node_limited: '$1' is a shell function; call oa_node_limited from inside it with the program." >&2
        return 2
    fi
    if [ -z "$_OA_TIMEOUT_READY" ]; then
        _OA_TIMEOUT_READY=1
        if command -v timeout >/dev/null 2>&1; then
            # --foreground keeps Ctrl-C working (work); without it timeout makes its own process group and stops
            # all of it (probe). -k is used where timeout knows it.
            if timeout --foreground -k 1 10 true >/dev/null 2>&1; then
                _OA_TIMEOUT_CMD=(timeout --foreground -k 5)
            elif timeout -k 1 10 true >/dev/null 2>&1; then
                _OA_TIMEOUT_CMD=(timeout -k 5)
            elif timeout 10 true >/dev/null 2>&1; then
                _OA_TIMEOUT_CMD=(timeout)
            fi
            if timeout -k 1 10 true >/dev/null 2>&1; then
                _OA_TIMEOUT_GROUP=(timeout -k 5)
            elif timeout 10 true >/dev/null 2>&1; then
                _OA_TIMEOUT_GROUP=(timeout)
            fi
        fi
    fi
    if [ "${#_OA_TIMEOUT_CMD[@]}" -eq 0 ]; then
        "$@" || rc=$?
        return "$rc"
    fi
    t0=$SECONDS
    if [ "$group" = true ] && [ "${#_OA_TIMEOUT_GROUP[@]}" -gt 0 ]; then
        _oa_run_group "$limit" "$@" || rc=$?
        # (a signal of the caller: not a limit, whatever the elapsed time says)
        [ "$_OA_NL_SIG" -eq 0 ] || return "$rc"
    else
        "${_OA_TIMEOUT_CMD[@]}" "$limit" "$@" || rc=$?
    fi
    if [ "$rc" -eq 124 ]; then
        return 75
    fi
    if { [ "$rc" -eq 137 ] || [ "$rc" -eq 143 ]; } && [ $((SECONDS - t0 + 1)) -ge "$limit" ]; then
        return 75
    fi
    return "$rc"
}

# ── Version comparison ──
# oa_version_gt <a> <b>: 0 when a is newer than b (dotted numbers such as 2026.9.8;
# a suffix after - or + is ignored, a part that is not a number counts as 0)
oa_version_gt() {
    local a="${1%%[-+]*}" b="${2%%[-+]*}" i x y
    local -a pa pb
    IFS=. read -ra pa <<< "$a"
    IFS=. read -ra pb <<< "$b"
    for i in 0 1 2 3; do
        x="${pa[$i]:-0}"; y="${pb[$i]:-0}"
        [[ "$x" =~ ^[0-9]+$ ]] || x=0
        [[ "$y" =~ ^[0-9]+$ ]] || y=0
        [ "$((10#$x))" -gt "$((10#$y))" ] && return 0
        [ "$((10#$x))" -lt "$((10#$y))" ] && return 1
    done
    return 1
}

# ── OpenClaw chat history ──
# oa_has_indexed_sessions [<data dir>]: 0 when the old (JSON) session store has sessions in it.
# OpenClaw 9.x moves them into SQLite when it first runs the data migration.
oa_has_indexed_sessions() {
    local d="${1:-${PLATFORM_DATA_DIR:-$HOME/.openclaw}}" f
    for f in "$d"/agents/*/sessions/sessions.json; do
        [ -f "$f" ] || continue
        grep -q '"sessionId"' "$f" 2>/dev/null && return 0
    done
    return 1
}

# ── Load platform config.env ──
# $1: platform name, $2: base directory (parent of platforms/)
load_platform_config() {
    local platform="$1"
    local base_dir="$2"
    local config_path="$base_dir/platforms/$platform/config.env"

    validate_platform_name "$platform" || return 1

    if [ ! -f "$config_path" ]; then
        echo -e "${RED}[FAIL]${NC} Platform config not found: $config_path"
        return 1
    fi
    # shellcheck source=/dev/null
    source "$config_path"
    return 0
}

# oa_repair_state_paths <state dir> [node]: OpenClaw 9.x keeps the paths of its agent databases in the
# state database (state/openclaw.sqlite, table agent_databases). A state that came from another
# environment (a backup of the Claw app restored into Termux or the reverse, or of another app
# package) has paths there that point into the other app's folder. On Android the other app's folder
# cannot be read (EACCES, where a missing folder would only give ENOENT, which 9.x handles itself),
# and the 9.x data migration stops at the first such path. A row whose path fails with EACCES or
# EPERM and ends in agents/<agent_id>/agent/openclaw-agent.sqlite is moved to this state folder
# (dropped when this state folder has that row already, as an absolute or a relative path). A stale
# row of agent_database_leases with such a path is deleted.
# Before the first change a copy of the state database is made (openclaw.sqlite.oa-before-repair-<time>, 0600;
# of the copies of earlier repairs only the newest 2 stay, the same for openclaw.json).
# Prints one line: "<rows changed> <rows left that need repair> <copy path or ->", or "error: <reason>"
# when the repair could not be done (nothing was changed then); "0 0 -" when there is nothing to do.
oa_repair_state_paths() {
    local state="${1:-${PLATFORM_DATA_DIR:-$HOME/.openclaw}}" node="${2:-node}" out
    [ -f "$state/state/openclaw.sqlite" ] || { echo "0 0 -"; return 0; }
    out=$("$node" --no-warnings -e '
        const fs = require("fs");
        const path = require("path");
        // copies of what a repair changed hold the same data (API keys) as the original: keep the newest 3 per
        // kind, the one just made always among them. Only names this repair makes (prefix + 14 digits + x*)
        // are ever deleted, never another file that starts the same way.
        const keepNewest = (dir, prefix, current) => {
            try {
                const re = new RegExp("^" + prefix.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "[0-9]{14}x*$");
                const others = fs.readdirSync(dir).filter((n) => re.test(n) && dir + "/" + n !== current).sort();
                for (const n of others.slice(0, Math.max(0, others.length - 2))) fs.unlinkSync(dir + "/" + n);
            } catch (e) { /* pruning is best effort */ }
        };
        const state = path.resolve(process.argv[1]);
        const dbPath = state + "/state/openclaw.sqlite";
        let sqlite;
        try { sqlite = require("node:sqlite"); } catch (e) { console.log("0 0 -"); process.exit(0); }
        let db;
        // close the database before leaving: an open connection at exit leaves empty -wal/-shm files behind
        const finish = (msg) => { try { if (db) db.close(); db = null; } catch (e) { /* closed */ } console.log(msg); process.exit(0); };
        try {
            db = new sqlite.DatabaseSync(dbPath);
            db.exec("PRAGMA busy_timeout = 5000");
            const has = db.prepare("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?").get("table", "agent_databases");
            if (!has) finish("0 0 -");
            const rows = db.prepare("SELECT agent_id, path FROM agent_databases").all();
            const denied = (p) => { try { fs.realpathSync.native(p); return false; } catch (e) { return e && (e.code === "EACCES" || e.code === "EPERM"); } };
            const todo = [];
            let left = 0;
            for (const r of rows) {
                const p = String(r.path);
                if (!p.startsWith("/") || p === state || p.startsWith(state + "/")) continue;
                if (!denied(p)) continue;
                const m = /\/agents\/([^/]+)\/agent\/openclaw-agent\.sqlite$/.exec(p);
                if (!m || m[1] !== r.agent_id) { left++; continue; }
                todo.push({ agent: r.agent_id, from: p, to: state + "/agents/" + m[1] + "/agent/openclaw-agent.sqlite", rel: "agents/" + m[1] + "/agent/openclaw-agent.sqlite" });
            }
            // 9.8 also keeps agent_database_leases (runtime bookkeeping of open agent databases): a row
            // of another app that cannot be resolved here is stale (its owner is a process elsewhere)
            const hasLeases = db.prepare("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?").get("table", "agent_database_leases");
            const staleLeases = [];
            if (hasLeases) {
                for (const r of db.prepare("SELECT lease_id, path FROM agent_database_leases").all()) {
                    const p = String(r.path);
                    if (p.startsWith("/") && p !== state && !p.startsWith(state + "/") && denied(p)) staleLeases.push(r.lease_id);
                }
            }
            if (todo.length === 0 && staleLeases.length === 0) finish("0 " + left + " -");
            let copy = dbPath + ".oa-before-repair-" + new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
            while (fs.existsSync(copy)) copy += "x";  // (never touch a copy of an earlier repair)
            const q = String.fromCharCode(39);  // (this script sits in a single-quoted shell string)
            try {
                db.exec("VACUUM INTO " + q + copy.split(q).join(q + q) + q);
                fs.chmodSync(copy, 0o600);
            } catch (e) {
                // a full disk leaves a cut-off copy (and its journal): never keep it
                for (const f of [copy, copy + "-journal"]) { try { fs.unlinkSync(f); } catch (e2) { /* not there */ } }
                throw e;
            }
            try {
                db.exec("BEGIN IMMEDIATE");
                const exists = db.prepare("SELECT 1 FROM agent_databases WHERE agent_id = ? AND path = ?");
                const upd = db.prepare("UPDATE agent_databases SET path = ? WHERE agent_id = ? AND path = ?");
                const del = db.prepare("DELETE FROM agent_databases WHERE agent_id = ? AND path = ?");
                for (const t of todo) {
                    if (exists.get(t.agent, t.to) || exists.get(t.agent, t.rel)) del.run(t.agent, t.from); else upd.run(t.to, t.agent, t.from);
                }
                const delLease = hasLeases ? db.prepare("DELETE FROM agent_database_leases WHERE lease_id = ?") : null;
                for (const id of staleLeases) delLease.run(id);
                db.exec("COMMIT");
            } catch (e) {
                try { db.exec("ROLLBACK"); } catch (e2) { /* no transaction */ }
                try { fs.unlinkSync(copy); } catch (e2) { /* no copy */ }
                throw e;
            }
            keepNewest(path.dirname(dbPath), path.basename(dbPath) + ".oa-before-repair-", copy);
            console.log((todo.length + staleLeases.length) + " " + left + " " + copy);
        } catch (e) {
            console.log("error: " + (e && e.message ? e.message : String(e)).replace(/\s+/g, " ").slice(0, 160));
        } finally { try { if (db) db.close(); } catch (e) { /* closed */ } }
    ' "$state" 2>&1 | tail -1) || out="error: node failed"
    echo "${out:-error: no answer}"
}

# oa_repair_config_paths <state dir> [node]: the same problem in openclaw.json. "openclaw agents add" writes
# absolute paths (agents.list[].agentDir and .workspace), and agents.defaults.workspace can be one too.
# A value that points into the home folder of another Android app (/data/data/<package>/files/home or
# /data/user/<n>/<package>/files/home) of a package other than this app's, that cannot be resolved here
# (the folder is missing, or refused: EACCES, EPERM), is moved to this $HOME (the part after the home
# folder stays; another spelling of this app's own home is left alone). Only those fields change, as a text edit of the
# string value, so the rest of the file stays as it was; the result is parsed again and compared with
# the expected object before it is written (temp file, then rename). A copy is made first
# (openclaw.json.oa-before-repair-<time>, 0600). Output as oa_repair_state_paths: "<changed> <left> <copy>",
# where <changed> is the number of values changed in the file (a path used in several fields counts for each).
oa_repair_config_paths() {
    local state="${1:-${PLATFORM_DATA_DIR:-$HOME/.openclaw}}" node="${2:-node}" out
    [ -f "$state/openclaw.json" ] || { echo "0 0 -"; return 0; }
    out=$("$node" --no-warnings -e '
        const fs = require("fs");
        const path = require("path");
        // copies of what a repair changed hold the same data (API keys) as the original: keep the newest 3 per
        // kind, the one just made always among them. Only names this repair makes (prefix + 14 digits + x*)
        // are ever deleted, never another file that starts the same way.
        const keepNewest = (dir, prefix, current) => {
            try {
                const re = new RegExp("^" + prefix.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "[0-9]{14}x*$");
                const others = fs.readdirSync(dir).filter((n) => re.test(n) && dir + "/" + n !== current).sort();
                for (const n of others.slice(0, Math.max(0, others.length - 2))) fs.unlinkSync(dir + "/" + n);
            } catch (e) { /* pruning is best effort */ }
        };
        const file = path.resolve(process.argv[1]) + "/openclaw.json";
        const home = (process.env.HOME || "").replace(/\/+$/, "");
        try {
            if (!home) { console.log("0 0 -"); process.exit(0); }
            const text = fs.readFileSync(file, "utf8");
            let cfg;
            try { cfg = JSON.parse(text); } catch (e) { console.log("0 0 -"); process.exit(0); }
            const unresolved = (p) => { try { fs.realpathSync.native(p); return false; } catch (e) { return true; } };
            const foreign = /^\/data\/(?:data|user\/[0-9]+)\/([^/]+)\/files\/home(\/.*)?$/;
            const fields = [];
            const agents = cfg && typeof cfg === "object" ? cfg.agents : null;
            if (agents && typeof agents === "object") {
                if (agents.defaults && typeof agents.defaults.workspace === "string") fields.push([agents.defaults, "workspace"]);
                // 7.x writes agents.list (an array), 9.x agents.entries (an object by agent id)
                const agentObjects = [].concat(Array.isArray(agents.list) ? agents.list : [], agents.entries && typeof agents.entries === "object" ? Object.values(agents.entries) : []);
                for (const a of agentObjects) if (a && typeof a === "object") for (const k of ["workspace", "agentDir"]) if (typeof a[k] === "string") fields.push([a, k]);
            }
            // another spelling of the own home of this app (/data/user/0/<pkg>/… for /data/data/<pkg>/…) is no other app
            const own = foreign.exec(home);
            const changes = new Map();
            let left = 0;
            for (const [obj, k] of fields) {
                const v = obj[k];
                const m = foreign.exec(v);
                // a folder of an app that is gone (ENOENT) is moved too: the agent could not create its files there (mkdir EACCES)
                if (!m || (own && m[1] === own[1]) || v === home || v.startsWith(home + "/") || !unresolved(v)) continue;
                changes.set(v, home + (m[2] || ""));
            }
            if (changes.size === 0) { console.log("0 " + left + " -"); process.exit(0); }
            let next = text;
            for (const [from, to] of changes) next = next.split(JSON.stringify(from)).join(JSON.stringify(to));
            // the edit must change exactly the intended values (every exact copy of them) and nothing else
            const expected = JSON.stringify(JSON.parse(text), (key, val) => (typeof val === "string" && changes.has(val) ? changes.get(val) : val));
            let got;
            try { got = JSON.parse(next); } catch (e) { throw new Error("the edited file would not parse"); }
            if (JSON.stringify(got) !== expected) throw new Error("the edit changed more than the path fields");
            const stamp = new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
            let copy = file + ".oa-before-repair-" + stamp;
            while (fs.existsSync(copy)) copy += "x";  // (never touch a copy of an earlier repair)
            try {
                fs.copyFileSync(file, copy);
                fs.chmodSync(copy, 0o600);
            } catch (e) {
                try { fs.unlinkSync(copy); } catch (e2) { /* not there */ }
                throw e;
            }
            const tmp = file + ".oa-tmp";
            try {
                fs.writeFileSync(tmp, next);
                fs.chmodSync(tmp, fs.statSync(file).mode & 0o7777);
                fs.renameSync(tmp, file);
            } catch (e) {
                // nothing was changed: the copy is of no use
                for (const f of [tmp, copy]) { try { fs.unlinkSync(f); } catch (e2) { /* not there */ } }
                throw e;
            }
            keepNewest(path.dirname(file), path.basename(file) + ".oa-before-repair-", copy);
            // (the number of values changed in the file, not of different paths)
            console.log(fields.filter(([obj, k]) => changes.has(obj[k])).length + " " + left + " " + copy);
        } catch (e) {
            console.log("error: " + (e && e.message ? e.message : String(e)).replace(/\s+/g, " ").slice(0, 160));
        }
    ' "$state" 2>&1 | tail -1) || out="error: node failed"
    echo "${out:-error: no answer}"
}

# ─── openclaw complete check ───
# oa_openclaw_incomplete [<package dir>] [fresh]: 0 when the installed OpenClaw package is incomplete.
# An install that was cut off (the app closed, the system stopped the process) can leave package.json in place with
# files missing (npm unpacks it first): the version alone then looks right, and OpenClaw does not start. Four signs:
#  1. our own mark "$HOME/.openclaw-android/.openclaw-install-pending": every installer creates it just before npm
#     runs and removes it only after the install, postinstall, patch and checks all went through. It stays when
#     the run was cut off - also when OpenClaw was started meanwhile (starting it finishes the package's own
#     lifecycle and erases sign 2, whatever is missing).
#  2. the lifecycle-pending mark OpenClaw's package carries until its first start (".openclaw-lifecycle-pending",
#     older: "dist/openclaw-install-guard") is still there. Looked at BEFORE openclaw runs, which erases it.
#     For installs made before sign 1 existed.
#  3. a file that OpenClaw's own list of its package (dist/postinstall-content-inventory.json: path, sha256, mode and
#     size of every file in dist/, present since 2026.9) names is not there; or, outside dist/, the package.json of
#     @openclaw/fs-safe (a dependency the hard-link patch works on), a file its "exports" name, or the .js of a
#     dist/**/*.d.ts it ships (the tar order puts the .d.ts first). Only the existence is looked at: our patches change the content of some of the files. (This sign used to be a MISSING
#     reported by the hard-link patch; that script now finds its targets by content and no longer judges
#     completeness.)
#  4. `openclaw.mjs --version` fails and says so ("lifecycle is incomplete", a module that cannot be found);
#     tried twice. Any other failure (Node does not start, a hiccup) is no sign of it: the health check reports that.
# "fresh" (the caller has just installed, its own marks are still there by design): signs 1 and 2 are not looked at.
# (scripts/lib.sh and post-setup.sh carry the same function between these marks: a pre-commit check keeps them identical.)
oa_openclaw_incomplete() {
    local dir="${1:-$(npm root -g)/openclaw}" fresh="${2:-}" out rc try
    [ -f "$dir/openclaw.mjs" ] || return 0
    if [ "$fresh" != fresh ]; then
        [ ! -e "$HOME/.openclaw-android/.openclaw-install-pending" ] || return 0
        if [ -e "$dir/.openclaw-lifecycle-pending" ] || [ -e "$dir/dist/openclaw-install-guard" ]; then
            return 0
        fi
    fi
    # (a JSON of about 2 MB; a node that fails here says nothing about the package: only a named file counts. Not
    # there, or in a form that is not a list, sign 3 is silent - sign 4 is the one left.)
    out=$(timeout 60 node -e 'const fs=require("fs"),p=require("path"),d=process.argv[1];const has=f=>fs.existsSync(p.join(d,f));const no=f=>{console.log("MISSING "+f);process.exit(0)};let l=[];try{l=JSON.parse(fs.readFileSync(p.join(d,"dist/postinstall-content-inventory.json"),"utf8"))}catch(e){}if(Array.isArray(l))for(const e of l){const f=typeof e==="string"?e:e&&e.path;if(f&&!has(f))no(f)}let dep=false;try{dep=!!JSON.parse(fs.readFileSync(p.join(d,"package.json"),"utf8")).dependencies["@openclaw/fs-safe"]}catch(e){}if(dep){const b="node_modules/@openclaw/fs-safe/";let x;try{x=JSON.parse(fs.readFileSync(p.join(d,b+"package.json"),"utf8")).exports}catch(e){no(b+"package.json")}const w=(v,k)=>{if(typeof v==="string"){if(k!=="types"&&!v.includes("*")&&!has(b+v))no(b+v)}else if(v&&typeof v==="object")for(const k2 of Object.keys(v))w(v[k2],k2)};w(x,"");const r=q=>{for(const n of fs.readdirSync(p.join(d,q),{withFileTypes:true})){const f=q+"/"+n.name;if(n.isDirectory())r(f);else if(n.name.endsWith(".d.ts")&&!has(f.slice(0,-5)+".js"))no(f.slice(0,-5)+".js")}};try{r(b+"dist")}catch(e){}}' "$dir" 2>/dev/null || true)
    case "$out" in MISSING*) return 0 ;; esac
    for try in 1 2; do
        rc=0
        out=$(timeout 90 node "$dir/openclaw.mjs" --version 2>&1) || rc=$?
        [ "$rc" -ne 0 ] || return 1
        printf '%s' "$out" | grep -qE 'lifecycle is incomplete|ERR_MODULE_NOT_FOUND|Cannot find (package|module)' || return 1
        [ "$try" -eq 2 ] || sleep 2
    done
    return 0
}
# ─── end openclaw complete check ───
