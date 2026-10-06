#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../../scripts/lib.sh"

export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

echo "=== Updating OpenClaw Platform ==="
echo ""

pkg install -y binutils 2>/dev/null || true
if [ ! -e "$PREFIX/bin/ar" ] && [ -x "$PREFIX/bin/llvm-ar" ]; then
    ln -s "$PREFIX/bin/llvm-ar" "$PREFIX/bin/ar"
fi

# Version pin (SSOT: config.env). This script runs as a child process of
# update-core.sh, so it loads the pin itself.
load_platform_config openclaw "$SCRIPT_DIR/../.."
if ! [[ "${PLATFORM_NPM_PACKAGE_VERSION:-}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo -e "${RED}[FAIL]${NC} Invalid OpenClaw version pin in config.env: '${PLATFORM_NPM_PACKAGE_VERSION:-}'"
    exit 1
fi
PIN_VER="$PLATFORM_NPM_PACKAGE_VERSION"

# Node gate (defense in depth): update-core.sh stops before this step when Node
# is not pinned, but a cached older update-core.sh without that gate can run
# this newer script. Never install the pinned OpenClaw on the wrong Node.
NODE_FOUND="$(node --version 2>/dev/null || echo none)"
if [ "$NODE_FOUND" != "v${PLATFORM_NODE_VERSION:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Node.js v${PLATFORM_NODE_VERSION:-?} is required (found: $NODE_FOUND) — OpenClaw was not changed"
    echo "       Run 'oa --update' again."
    exit 1
fi
OPENCLAW_DIR="$(npm root -g)/openclaw"

# Converge to the pinned version: install it whenever the installed version
# differs, in either direction. Read package.json rather than running
# `openclaw --version`, which exits early when the installed release needs a
# newer Node.js than ours.
CURRENT_VER=""
if [ -f "$OPENCLAW_DIR/package.json" ]; then
    CURRENT_VER=$(node -p "require('$OPENCLAW_DIR/package.json').version" 2>/dev/null || echo "")
fi
OPENCLAW_UPDATED=false

# Never go back to an older OpenClaw by accident: a newer release may already have
# migrated the data, and the older one cannot be trusted to read it. (An old cached
# copy of the scripts is the usual cause.) Going back on purpose: set
# OA_ALLOW_OPENCLAW_DOWNGRADE=1 (and OA_ALLOW_NODE_DOWNGRADE=1 for the Node.js guard in update-core.sh).
if [ -n "$CURRENT_VER" ] && [ "$CURRENT_VER" != "$PIN_VER" ] \
    && declare -f oa_version_gt >/dev/null && oa_version_gt "$CURRENT_VER" "$PIN_VER" \
    && [ "${OA_ALLOW_OPENCLAW_DOWNGRADE:-0}" != 1 ]; then
    echo -e "${RED}[FAIL]${NC} Installed OpenClaw $CURRENT_VER is newer than the pinned $PIN_VER: not going back."
    echo "       A newer version may have migrated your data, and an older one may not be able to read it."
    echo "       This usually means an old cached copy of the scripts was used: wait a few minutes and run 'oa --update' again."
    echo "       OpenClaw was not changed. ('oa --restore' only brings back your data, not the program.)"
    echo "       For a deliberate rollback release, set OA_ALLOW_OPENCLAW_DOWNGRADE=1 and OA_ALLOW_NODE_DOWNGRADE=1."
    exit 1
fi

# Changing the installed OpenClaw needs the current updater (it exports OA_UPDATE_CORE_PROTOCOL=2 and has made
# the free-space, gateway and session checks and the data backup before this runs). Right after a release the
# raw update-core.sh is cached for a few minutes while the tarball is already new: an older update-core
# would install the new OpenClaw without those. Stop before anything is changed. (install-nodejs.sh does the
# same for a Node.js change; OA_ALLOW_UNMARKED_OPENCLAW_CHANGE=1 skips this check.)
if [ -n "$CURRENT_VER" ] && [ "$CURRENT_VER" != "$PIN_VER" ] && [ "${OA_ALLOW_UNMARKED_OPENCLAW_CHANGE:-}" != "1" ]; then
    case "${OA_UPDATE_CORE_PROTOCOL:-}" in ''|*[!0-9]*) OC_PROTO=0 ;; *) OC_PROTO="$OA_UPDATE_CORE_PROTOCOL" ;; esac
    if [ "$OC_PROTO" -lt 2 ]; then
        echo -e "${RED}[FAIL]${NC} The updater downloaded an older copy of itself (cache). Nothing was changed."
        echo "       Run 'oa --update' again in a few minutes."
        exit 1
    fi
fi

if [ "$CURRENT_VER" = "$PIN_VER" ]; then
    echo -e "${GREEN}[OK]${NC}   openclaw $CURRENT_VER matches the pinned version"
else
    # Defense in depth (update-core.sh checks first): never start the install without room
    if declare -f oa_check_free_space >/dev/null && ! oa_check_free_space "${OA_MIN_FREE_UPDATE_MB:-2000}" "install OpenClaw $PIN_VER"; then
        bash "$SCRIPT_DIR/openclaw-shim.sh" || true
        echo "       OpenClaw was not changed."
        exit 1
    fi
    echo "Installing pinned openclaw... (${CURRENT_VER:-none} → $PIN_VER)"
    echo "  (This may take several minutes depending on network speed)"
    # A leftover version guard with no package behind it (a plain file, not an
    # npm link) makes npm fail with EEXIST. When the package exists npm replaces
    # the bin itself, so leave the guard alone.
    if [ ! -f "$OPENCLAW_DIR/package.json" ] && [ -e "$PREFIX/bin/openclaw" ] && [ ! -L "$PREFIX/bin/openclaw" ]; then
        rm -f "$PREFIX/bin/openclaw"
    fi
    if npm install -g "$PLATFORM_NPM_PACKAGE@$PIN_VER" --no-fund --no-audit --ignore-scripts; then
        echo -e "${GREEN}[OK]${NC}   openclaw $PIN_VER installed"
        OPENCLAW_UPDATED=true
    else
        # Keep the existing install usable and guarded if npm left it in place
        bash "$SCRIPT_DIR/openclaw-shim.sh" || true
        echo -e "${RED}[FAIL]${NC} Could not install openclaw $PIN_VER"
        echo "       Check your network and run: oa --update"
        exit 1
    fi
fi

# Run the package postinstall that --ignore-scripts skipped (prunes stale dist
# files, applies bundled hotfixes) — only needed after a fresh package install.
# npm_config_ignore_scripts=true keeps any nested npm call from running native
# builds that fail on Termux.
if [ "$OPENCLAW_UPDATED" = true ] && [ -d "$OPENCLAW_DIR" ]; then
    echo "Running OpenClaw postinstall..."
    (cd "$OPENCLAW_DIR" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs 2>/dev/null) || true
fi

bash "$SCRIPT_DIR/patches/openclaw-apply-patches.sh"

# Always re-check the `openclaw update` guard — a manual `npm install -g openclaw`
# or the app's platform installer replaces $PREFIX/bin/openclaw.
bash "$SCRIPT_DIR/openclaw-shim.sh"

# Health check: a new OpenClaw can refuse the data of the old one until it has migrated
# it ("openclaw doctor --fix"). Say so now, instead of finishing with "Update Complete!"
# and a gateway that does not start.
# Two checks, both must pass: `config validate` (the config file) and
# `doctor --non-interactive` (exit code 1 when a data migration is pending; it changes
# nothing without --fix). The exit code decides, not the text. A doctor that does not
# answer in time counts as "could not check", not as a failure.
# oc_health_check: 0 healthy, 1 not usable yet (output in OC_CHECK_OUT), 2 could not check
oc_health_check() {
    local rc=0
    OC_CHECK_OUT=$(timeout 90 openclaw config validate 2>&1) || return 1
    OC_CHECK_OUT=$(timeout 150 openclaw doctor --non-interactive < /dev/null 2>&1) || rc=$?
    [ "$rc" -eq 124 ] && return 2
    [ "$rc" -ne 0 ] && return 1
    # A pending move of the chat history into SQLite does not change doctor's exit code (0), only its
    # text ("Found N session SQLite issue(s)"); the gateway refuses to start with it (exit 78).
    # "Found N" alone is not enough: after a migration that worked, doctor can still report one issue
    # (a transcript whose header differs from its file name; OpenClaw's own output, patched or not).
    # It is a pending migration only while the old session index (sessions.json) is still there, or while
    # doctor still counts legacy entries ("Legacy entries: N", N > 0; 0 after a migration that worked).
    if printf '%s' "$OC_CHECK_OUT" | grep -qE 'Legacy session store requires migration|session store migration'; then
        return 1
    fi
    if printf '%s' "$OC_CHECK_OUT" | grep -qE 'Found [1-9][0-9]* session SQLite issue' \
        && { oa_has_indexed_sessions "${PLATFORM_DATA_DIR:-$HOME/.openclaw}" \
            || printf '%s' "$OC_CHECK_OUT" | grep -qE 'Legacy entries:[[:space:]]*[1-9]'; }; then
        return 1
    fi
    return 0
}

# The few lines of a check/doctor output that say what is wrong (the full output is long)
oc_brief() {
    local out first
    # a pending chat-history migration first (it is the cause when an interrupted migration left
    # files behind; the generic error lines then only show the symptom), then the lines that say
    # why (fail/denied/error), then the ones that name the step
    first=$(printf '%s\n' "$1" | grep -iE -m2 'session SQLite issue|Legacy session store|session store migration|requires migration' || true)
    out=$(printf '%s\n' "$1" | grep -iE -m4 'fail|denied|EACCES|error|cannot|could not' || true)
    if [ -n "$first" ]; then
        out=$(printf '%s\n%s\n' "$first" "$out" | grep -v '^[[:space:]]*$' | awk '!seen[$0]++' | head -4)
    fi
    [ -n "$out" ] || out=$(printf '%s\n' "$1" | grep -iE -m4 'migrat|invalid|unrecognized|required|needs|--fix' || true)
    [ -n "$out" ] || out=$(printf '%s\n' "$1" | grep -v '^[[:space:]]*$' | head -4)
    printf '%s\n' "$out" | sed 's/^/         /'
}

# (Only when there is a config file: validate exits non-zero for a user who has not
# set OpenClaw up yet, which is not a failed update.)
# State copied from another environment (see oa_repair_state_paths / oa_repair_config_paths in lib.sh) makes
# the 9.x migration stop with EACCES on a path of the other app. Repair those paths first; nothing else
# is touched, and a copy of what is changed is kept next to it.
if declare -f oa_repair_state_paths >/dev/null && declare -f oa_repair_config_paths >/dev/null && command -v node &>/dev/null; then
    for OC_REPAIR_FN in oa_repair_state_paths oa_repair_config_paths; do
        OC_REPAIR=$("$OC_REPAIR_FN" "${PLATFORM_DATA_DIR:-$HOME/.openclaw}" node)
        read -r OC_REPAIRED OC_REPAIR_LEFT OC_REPAIR_COPY <<< "$OC_REPAIR"
        case "$OC_REPAIRED" in
            error:*)
                if [ "$OC_REPAIR_FN" = oa_repair_config_paths ]; then
                    echo -e "${YELLOW}[WARN]${NC} Could not fix the folder paths in your OpenClaw config (${OC_REPAIR#error: }). Nothing was changed."
                    echo "       Check ${PLATFORM_DATA_DIR:-$HOME/.openclaw}/openclaw.json: the workspace and agentDir values under agents may point to another app's folder."
                else
                    echo -e "${YELLOW}[WARN]${NC} Could not check the other-app paths in your OpenClaw state (${OC_REPAIR#error: })."
                    echo "       If the data migration stops with 'EACCES ... realpath', run oa --update again with the gateway stopped."
                fi
                ;;
            0) ;;
            *)
                echo -e "${GREEN}[OK]${NC}   Repaired $OC_REPAIRED path(s) in your OpenClaw data that pointed to another app or device"
                echo "       (a copy before the change: $OC_REPAIR_COPY)"
                ;;
        esac
        if [ "${OC_REPAIR_LEFT:-0}" -gt 0 ] 2>/dev/null; then
            echo -e "${YELLOW}[WARN]${NC} $OC_REPAIR_LEFT path(s) point into another app's folder and could not be repaired automatically."
        fi
    done
fi

OC_HEALTHY=true
OC_HEALTH_RC=0
if [ -f "${PLATFORM_DATA_DIR:-$HOME/.openclaw}/openclaw.json" ] && command -v openclaw &>/dev/null; then
    oc_health_check || OC_HEALTH_RC=$?
fi
if [ "$OC_HEALTH_RC" -eq 2 ]; then
    echo -e "${YELLOW}[WARN]${NC} Could not check that OpenClaw can use your data (the check took too long)."
    echo "       If the gateway does not start, run:  openclaw doctor --fix"
elif [ "$OC_HEALTH_RC" -ne 0 ]; then
    OC_HEALTHY=false
    # The migration is the official step for this: run it once by itself, but only when
    # update-core.sh saved a backup in this run (OA_PRE_UPDATE_BACKUP) to go back to.
    # With chat history to move, the migration needs the session-archive patch: without it the
    # migration fails half way and OpenClaw cannot start. Do not even try then.
    OC_SESSION_PATCH_OK=true
    if oa_has_indexed_sessions "${PLATFORM_DATA_DIR:-$HOME/.openclaw}" \
        && ! bash "$SCRIPT_DIR/patches/openclaw-patch-hardlink.sh" --check >/dev/null 2>&1; then
        OC_SESSION_PATCH_OK=false
    fi
    if [ "$OC_SESSION_PATCH_OK" = false ]; then
        echo -e "${RED}[FAIL]${NC} OpenClaw ${PIN_VER} is installed, but the patch that moves your chat history is not in place:"
        # (|| true: the check exits non-zero by design, and a pipeline failure must not end this script)
        { bash "$SCRIPT_DIR/patches/openclaw-patch-hardlink.sh" --check 2>&1 || true; } | sed 's/\x1b\[[0-9;]*m//g' | { grep -E 'TODO|MISSING|NOMATCH|WARN' || true; } | head -4 | sed 's/^/         /'
        echo "       Nothing was migrated and your data is untouched. Run 'oa --update' again; if it keeps failing, report it."
        if [ -n "${OA_PRE_UPDATE_BACKUP:-}" ]; then
            echo "       Your data as it was before the update: $OA_PRE_UPDATE_BACKUP (restore with 'oa --restore')."
        fi
    elif [ -n "${OA_PRE_UPDATE_BACKUP:-}" ] && [ -f "$OA_PRE_UPDATE_BACKUP" ] \
        && [ "${OA_SKIP_AUTO_DOCTOR:-0}" != 1 ]; then
        echo "Migrating your OpenClaw data for $PIN_VER (a backup was saved at $OA_PRE_UPDATE_BACKUP)..."
        OC_DOCTOR_RC=0
        OC_DOCTOR_OUT=$(timeout 120 openclaw doctor --fix --non-interactive < /dev/null 2>&1) || OC_DOCTOR_RC=$?
        # Node now and then dies at start-up with an OpenSSL configuration error (seen on the
        # emulator, cause unknown); the same command works a moment later: try once more.
        if [ "$OC_DOCTOR_RC" -ne 0 ] && [ "$OC_DOCTOR_RC" -ne 124 ] \
            && printf '%s' "$OC_DOCTOR_OUT" | grep -q 'OpenSSL configuration error'; then
            echo "  Node stopped at start-up (OpenSSL configuration error); trying the migration once more..."
            sleep 2
            OC_DOCTOR_RC=0
            OC_DOCTOR_OUT=$(timeout 120 openclaw doctor --fix --non-interactive < /dev/null 2>&1) || OC_DOCTOR_RC=$?
        fi
        OC_RECHECK_RC=0
        [ "$OC_DOCTOR_RC" -ne 0 ] || oc_health_check || OC_RECHECK_RC=$?
        if [ "$OC_DOCTOR_RC" -eq 0 ] && [ "$OC_RECHECK_RC" -ne 1 ]; then
            OC_HEALTHY=true
            echo -e "${GREEN}[OK]${NC}   Your OpenClaw data was migrated for $PIN_VER"
        else
            if [ "$OC_DOCTOR_RC" -eq 124 ]; then
                echo -e "${RED}[FAIL]${NC} The data migration did not finish within 2 minutes."
            elif [ "$OC_DOCTOR_RC" -ne 0 ]; then
                echo -e "${RED}[FAIL]${NC} The data migration did not succeed (exit code $OC_DOCTOR_RC):"
            else
                echo -e "${RED}[FAIL]${NC} The data migration ran, but OpenClaw still cannot use your data:"
                OC_DOCTOR_OUT="$OC_CHECK_OUT"
            fi
            oc_brief "$OC_DOCTOR_OUT"
            if printf '%s\n' "$OC_DOCTOR_OUT" > "$PROJECT_DIR/doctor-fix.log" 2>/dev/null; then
                echo "       The full output is in $PROJECT_DIR/doctor-fix.log"
            fi
            echo "       Your data as it was before the update: $OA_PRE_UPDATE_BACKUP (restore with 'oa --restore')."
            echo "       To try again yourself (stop the gateway first if it is running):  openclaw doctor --fix"
        fi
    else
        echo -e "${RED}[FAIL]${NC} OpenClaw ${PIN_VER} is installed, but it cannot use your existing data yet:"
        oc_brief "$OC_CHECK_OUT"
        echo "       Stop the gateway if it is running, then run:  openclaw doctor --fix"
        echo "       and start the gateway again."
        if [ -n "${OA_PRE_UPDATE_BACKUP:-}" ] && [ -f "$OA_PRE_UPDATE_BACKUP" ]; then
            echo "       Your data as it was before the update: $OA_PRE_UPDATE_BACKUP (restore with 'oa --restore')."
        fi
    fi
fi

if command -v clawdhub &>/dev/null; then
    CLAWDHUB_CURRENT_VER=$(npm list -g clawdhub 2>/dev/null | grep 'clawdhub@' | sed 's/.*clawdhub@//' | tr -d '[:space:]' || true)
    CLAWDHUB_LATEST_VER=$(npm view clawdhub version 2>/dev/null || echo "")
    if [ -n "$CLAWDHUB_CURRENT_VER" ] && [ -n "$CLAWDHUB_LATEST_VER" ] && [ "$CLAWDHUB_CURRENT_VER" = "$CLAWDHUB_LATEST_VER" ]; then
        echo -e "${GREEN}[OK]${NC}   clawdhub $CLAWDHUB_CURRENT_VER is already the latest"
    elif [ -n "$CLAWDHUB_LATEST_VER" ]; then
        echo "Updating clawdhub... ($CLAWDHUB_CURRENT_VER → $CLAWDHUB_LATEST_VER)"
        if npm install -g clawdhub@latest --no-fund --no-audit; then
            echo -e "${GREEN}[OK]${NC}   clawdhub $CLAWDHUB_LATEST_VER updated"
        else
            echo -e "${YELLOW}[WARN]${NC} clawdhub update failed (non-critical)"
        fi
    else
        echo -e "${YELLOW}[WARN]${NC} Could not check clawdhub latest version"
    fi
else
    if ask_yn "clawdhub (skill manager) is not installed. Install it?"; then
        echo "Installing clawdhub..."
        if npm install -g clawdhub --no-fund --no-audit; then
            echo -e "${GREEN}[OK]${NC}   clawdhub installed"
        else
            echo -e "${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} Skipping clawdhub"
    fi
fi

CLAWHUB_DIR="$(npm root -g)/clawdhub"
if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
    echo "Installing undici dependency for clawdhub..."
    if (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit); then
        echo -e "${GREEN}[OK]${NC}   undici installed for clawdhub"
    else
        echo -e "${YELLOW}[WARN]${NC} undici installation failed"
    fi
else
    UNDICI_VER=$(cd "$CLAWHUB_DIR" && node -e "console.log(require('undici/package.json').version)" 2>/dev/null || echo "")
    echo -e "${GREEN}[OK]${NC}   undici ${UNDICI_VER:-available}"
fi

OLD_SKILLS_DIR="$HOME/skills"
CORRECT_SKILLS_DIR="$HOME/.openclaw/workspace/skills"
if [ -d "$OLD_SKILLS_DIR" ] && [ "$(ls -A "$OLD_SKILLS_DIR" 2>/dev/null)" ]; then
    echo ""
    echo "Migrating skills from ~/skills/ to ~/.openclaw/workspace/skills/..."
    mkdir -p "$CORRECT_SKILLS_DIR"
    for skill in "$OLD_SKILLS_DIR"/*/; do
        [ -d "$skill" ] || continue
        skill_name=$(basename "$skill")
        if [ ! -d "$CORRECT_SKILLS_DIR/$skill_name" ]; then
            if mv "$skill" "$CORRECT_SKILLS_DIR/$skill_name" 2>/dev/null; then
                echo -e "  ${GREEN}[OK]${NC}   Migrated $skill_name"
            else
                echo -e "  ${YELLOW}[WARN]${NC} Failed to migrate $skill_name"
            fi
        else
            echo -e "  ${YELLOW}[SKIP]${NC} $skill_name already exists in correct location"
        fi
    done
    if rmdir "$OLD_SKILLS_DIR" 2>/dev/null; then
        echo -e "${GREEN}[OK]${NC}   Removed empty ~/skills/"
    else
        echo -e "${YELLOW}[WARN]${NC} ~/skills/ not empty after migration — check manually"
    fi
fi

python -c "import yaml" 2>/dev/null || { command -v pip >/dev/null 2>&1 && pip install pyyaml -q; } || true

# Turn off the gateway's "update available" notice: the OpenClaw version is pinned
# here, so the notice only points at an update that oa blocks. A value the user
# already set (true or false) is left alone. Same block as install.sh and the end
# of post-setup.sh. (Skipped when the health check above failed: it would only repeat that.)
if [ "$OC_HEALTHY" != true ]; then
    :
elif timeout 60 openclaw config get update.checkOnStart >/dev/null 2>&1; then
    echo -e "${GREEN}[SKIP]${NC} update.checkOnStart is already set"
elif timeout 60 openclaw config set update.checkOnStart false >/dev/null 2>&1; then
    echo -e "${GREEN}[OK]${NC}   OpenClaw update notice turned off (update.checkOnStart=false)"
else
    echo -e "${YELLOW}[WARN]${NC} Could not turn off the OpenClaw update notice (non-critical)"
fi

# The update finished its work, but a gateway that cannot start is not a finished update
[ "$OC_HEALTHY" = true ] || exit 1
