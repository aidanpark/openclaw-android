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

# node: from PATH, else the wrapper the installer puts in ~/.openclaw-android/bin (a shell
# without the oa PATH, e.g. non-interactive SSH, does not see it). The real binary in
# node/bin is not tried: it cannot run without the wrapper's glibc loader.
_oa_find_node() {
    local c
    if c=$(command -v node 2>/dev/null) && [ -n "$c" ]; then
        printf '%s' "$c"
        return 0
    fi
    c="$HOME/.openclaw-android/bin/node"
    if [ -x "$c" ] && "$c" --version &>/dev/null; then
        printf '%s' "$c"
        return 0
    fi
    return 1
}
# Resolved on first use, not when this file is sourced: oa.sh sources it for every
# command, and starting node just to look for it would slow all of them down.
OA_NODE=""
OA_NODE_RESOLVED=false
_oa_resolve_node() {
    if [ "$OA_NODE_RESOLVED" = false ]; then
        OA_NODE="$(_oa_find_node || true)"
        OA_NODE_RESOLVED=true
    fi
}

# True if GNU tar (needed for --transform / --strip-components / --wildcards).
_have_gnu_tar() {
    tar --version 2>/dev/null | head -1 | grep -q "GNU tar"
}

# True if node can load node:sqlite (SQLite snapshots need it).
_have_node_sqlite() {
    [ -n "$OA_NODE" ] && "$OA_NODE" -e 'require("node:sqlite")' &>/dev/null
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
    local pkg
    pkg="$(npm root -g 2>/dev/null || true)/openclaw/package.json"
    if [ -f "$pkg" ] && [ -n "$OA_NODE" ]; then
        "$OA_NODE" -p "require('$pkg').version" 2>/dev/null && return 0
    fi
    echo "unknown"
}

# Workspace directory when it lives outside the state directory (default setups
# keep it inside, where it is already covered). Prints nothing otherwise.
_backup_external_workspace() {
    local data_dir="$1"
    local ws
    command -v openclaw &>/dev/null || return 0
    ws=$(timeout 30 openclaw config get agents.defaults.workspace 2>/dev/null) || return 0
    ws="${ws%$'\r'}"
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
                if (u !== t) { fs.writeFileSync(f, u); n++; }
            }
            console.log(n);
        });'
    local changed
    if ! changed=$({ find "$root/agents" -type f \( -name 'sessions.json' -o -name '*.trajectory-path.json' \) -print0 2>/dev/null || true; } \
            | "$OA_NODE" -e "$js" "$from" "$to" 2>/dev/null); then
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
        printf '%s' "$manifest" | "$OA_NODE" -e '
            let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
                let m; try { m = JSON.parse(s); } catch (e) { process.exit(1); }
                const assets = Array.isArray(m.assets) ? m.assets : [];
                const posix = assets.some(a => String(a.archivePath || "").includes("/payload/posix/"));
                console.log("LAYOUT " + (posix ? "posix" : "legacy"));
                console.log("ROOT " + (m.archiveRoot || ""));
                for (const a of assets) console.log("ASSET " + [a.kind, a.sourcePath, a.archivePath].join("\t"));
            });' 2>/dev/null
        return $?
    fi

    # No node: only the old layout can be recognised without a JSON parser
    if printf '%s' "$manifest" | grep -q '/payload/posix/'; then
        return 1
    fi
    echo "LAYOUT legacy"
    echo "ROOT $(printf '%s' "$manifest" | grep '"archiveRoot"' | sed 's/.*"archiveRoot"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')"
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
    if _have_node_sqlite; then
        pairs=()
        while IFS= read -r db; do
            rel="${db#"$real_dir"/}"
            mkdir -p "$snap/$base/$(dirname "$rel")"
            pairs+=("$db" "$snap/$base/$rel.oa-snap")
        done < <(find "$real_dir" -path "$real_dir/extensions/*/node_modules" -prune -o -type f -name '*.sqlite' -print)

        failed=""
        if [ ${#pairs[@]} -gt 0 ]; then
            failed=$(NODE_NO_WARNINGS=1 "$OA_NODE" -e '
                const { DatabaseSync } = require("node:sqlite");
                const fs = require("node:fs");
                const a = process.argv.slice(1);
                for (let i = 0; i < a.length; i += 2) {
                    try {
                        const db = new DatabaseSync(a[i], { readOnly: true });
                        db.exec("PRAGMA busy_timeout = 30000;");
                        db.prepare("VACUUM INTO ?").run(a[i + 1]);
                        db.close();
                        const snap = new DatabaseSync(a[i + 1]);
                        const q = snap.prepare("SELECT 1 AS ok FROM sqlite_master WHERE type = ? AND name = ?")
                            .get("table", "delivery_queue_entries");
                        if (q && q.ok === 1) { snap.exec("DELETE FROM delivery_queue_entries; VACUUM;"); }
                        snap.close();
                        fs.chmodSync(a[i + 1], 0o600);
                    } catch (e) { console.log(a[i]); }
                }' "${pairs[@]}" 2>/dev/null) || true
        fi
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
    node_version="$("$OA_NODE" --version 2>/dev/null || echo unknown)"
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

    echo -e "Packing archive…"
    tar -cf - "${transforms[@]}" \
        --exclude="$base/logs" --exclude="$base/delivery-queue" --exclude="$base/session-delivery-queue" \
        --exclude='*.pid' --exclude='*.sock' --exclude='*.tmp' \
        --exclude="$base/extensions/*/node_modules" "${sqlite_excludes[@]}" \
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

# ── cmd_backup ──────────────────────────────────────────────────────────────

cmd_backup() {
    _oa_resolve_node
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

    _backup_create "$data_dir" "$archive_path" "$basename" || exit 1

    echo -e "${GREEN}[OK]${NC}   Archive created: $archive_path"
    echo ""

    # ── Integrity verification ──
    echo -e "Verifying integrity…"

    # Try openclaw backup verify first (preferred — full manifest check)
    if command -v openclaw &>/dev/null && openclaw backup verify "$archive_path" &>/dev/null 2>&1; then
        echo -e "${GREEN}[OK]${NC}   Integrity check passed (openclaw backup verify)"
    else
        # Fallback: tar -tzf structural check
        local file_count
        file_count=$(gzip -dc "$archive_path" 2>/dev/null | tar -tf - 2>/dev/null | wc -l)
        if [ "$file_count" -gt 0 ]; then
            echo -e "${GREEN}[OK]${NC}   Integrity check passed (tar structural, $file_count entries)"
        else
            echo -e "${RED}[FAIL]${NC} Integrity check failed — archive may be corrupt"
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
    _oa_resolve_node
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
    done < <(ls -t "$BACKUP_DIR"/*.tar.gz 2>/dev/null || true; ls -t "$BACKUP_DIR"/pre-restore/*.tar.gz 2>/dev/null || true)

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
    local info layout="" archive_root=""
    local -a asset_kinds=() asset_sources=() asset_paths=()
    info=$(_backup_manifest_info "$selected") || {
        echo -e "${RED}[FAIL]${NC} Could not read the backup manifest (this backup needs node to be read)."
        exit 1
    }
    while IFS= read -r line; do
        case "$line" in
            "LAYOUT "*) layout="${line#LAYOUT }" ;;
            "ROOT "*) archive_root="${line#ROOT }" ;;
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
        local pre_ready=true
        mkdir -p "$pre_dir" 2>/dev/null || pre_ready=false
        echo ""
        echo -e "Saving a safety backup of the current data…"
        if [ "$pre_ready" = true ] && _backup_create "$restore_root" "$pre_archive" "$pre_root"; then
            echo -e "${GREEN}[OK]${NC}   Safety backup: $pre_archive"
            echo "       To undo this restore, run oa --restore again and pick it (it is listed as pre-restore/…)."

        elif [ "$force_no_safety" = true ]; then
            echo -e "${YELLOW}[WARN]${NC} Could not save a safety backup of the current data — continuing because you asked for --force-no-safety."
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
        local i restored=0 state_moved=false
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
        done
        if [ "$restored" -eq 0 ]; then
            echo -e "${RED}[FAIL]${NC} Nothing to restore in this backup."
            exit 1
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
            ws_cfg=$(timeout 30 openclaw config get agents.defaults.workspace 2>/dev/null || true)
            ws_cfg="${ws_cfg%$'\r'}"
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
    # shellcheck disable=SC2012
    # (|| true: with no safety backups ls fails, and that must not end the restore)
    ls -t "$BACKUP_DIR"/pre-restore/*.tar.gz 2>/dev/null | tail -n +6 \
        | while IFS= read -r old; do [ "$old" = "$selected" ] || rm -f "$old"; done || true

    echo ""
    echo -e "  Restored to: $restore_root"
    echo ""
    echo -e "${YELLOW}[NOTE]${NC} Files created after this backup were kept."
    echo -e "${YELLOW}[NOTE]${NC} Restart the OpenClaw gateway for changes to take effect."
    echo ""
}
