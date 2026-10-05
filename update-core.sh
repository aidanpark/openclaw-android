#!/usr/bin/env bash
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

PROJECT_DIR="$HOME/.openclaw-android"
PLATFORM_MARKER="$PROJECT_DIR/.platform"
OA_VERSION="1.1.4"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Updater v${OA_VERSION}${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/5] $2${NC}"
    echo "----------------------------------------"
}

step 1 "Pre-flight Check"

if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   Termux detected"

if ! command -v curl &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
    exit 1
fi

OLD_DIR="$HOME/.openclaw-lite"
if [ -d "$OLD_DIR" ] && [ ! -d "$PROJECT_DIR" ]; then
    mv "$OLD_DIR" "$PROJECT_DIR"
    echo -e "${GREEN}[OK]${NC}   Migrated $OLD_DIR -> $PROJECT_DIR"
elif [ -d "$OLD_DIR" ] && [ -d "$PROJECT_DIR" ]; then
    cp -rn "$OLD_DIR"/. "$PROJECT_DIR"/ 2>/dev/null || true
    rm -rf "$OLD_DIR"
    echo -e "${GREEN}[OK]${NC}   Merged $OLD_DIR into $PROJECT_DIR"
else
    mkdir -p "$PROJECT_DIR"
fi

if [ -f "$PROJECT_DIR/scripts/lib.sh" ]; then
    source "$PROJECT_DIR/scripts/lib.sh"
fi
command -v resolve_npm_registry >/dev/null 2>&1 && resolve_npm_registry || true

# Define REPO_TARBALL after sourcing lib.sh to prevent old installs from overwriting it
REPO_TARBALL="https://github.com/AidanPark/openclaw-android/archive/refs/heads/main.tar.gz"

if ! declare -f detect_platform &>/dev/null; then
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
fi

PLATFORM=$(detect_platform) || {
    echo -e "${RED}[FAIL]${NC} No platform detected"
    exit 1
}
if [ -z "$PLATFORM" ]; then
    echo -e "${RED}[FAIL]${NC} No platform detected"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   Platform: $PLATFORM"

IS_GLIBC=false
if [ -f "$PROJECT_DIR/.glibc-arch" ]; then
    IS_GLIBC=true
    echo -e "${GREEN}[OK]${NC}   Architecture: glibc"
else
    echo -e "${YELLOW}[INFO]${NC} Architecture: Bionic (migration required)"
fi

SDK_INT=$(getprop ro.build.version.sdk 2>/dev/null || echo "0")
if [ "$SDK_INT" -ge 31 ] 2>/dev/null; then
    echo -e "${YELLOW}[INFO]${NC} Android 12+ detected — if background processes get killed (signal 9),"
    echo "       see: https://github.com/AidanPark/openclaw-android/blob/main/docs/disable-phantom-process-killer.md"
fi

step 2 "Download Latest Release (tarball)"

mkdir -p "$PREFIX/tmp"
RELEASE_TMP=$(mktemp -d "$PREFIX/tmp/oa-update.XXXXXX") || {
    echo -e "${RED}[FAIL]${NC} Failed to create temp directory"
    exit 1
}
trap 'rm -rf "$RELEASE_TMP"' EXIT

echo "Downloading latest scripts..."
echo "  (This may take a moment depending on network speed)"
if curl -sfL "$REPO_TARBALL" | tar xz -C "$RELEASE_TMP" --strip-components=1; then
    echo -e "${GREEN}[OK]${NC}   Downloaded latest release"
else
    echo -e "${RED}[FAIL]${NC} Failed to download release"
    exit 1
fi

REQUIRED_FILES=(
    "scripts/lib.sh"
    "scripts/setup-env.sh"
    "scripts/install-nodejs.sh"
    "platforms/$PLATFORM/config.env"
    "platforms/$PLATFORM/update.sh"
    "platforms/$PLATFORM/openclaw-shim.sh"
)
for f in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "$RELEASE_TMP/$f" ]; then
        echo -e "${RED}[FAIL]${NC} Missing required file: $f"
        echo "       The downloaded release may be corrupted. Try again."
        exit 1
    fi
done
echo -e "${GREEN}[OK]${NC}   All required files verified"

source "$RELEASE_TMP/scripts/lib.sh"

# Version pin (PLATFORM_NODE_VERSION etc.) comes from the downloaded config.env
if ! load_platform_config "$PLATFORM" "$RELEASE_TMP"; then
    exit 1
fi
if [ -z "${PLATFORM_NODE_VERSION:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Version pin missing in platforms/$PLATFORM/config.env"
    echo "       The downloaded release may be incomplete. Run 'oa --update' again."
    exit 1
fi

step 3 "Update Core Infrastructure"

mkdir -p "$PROJECT_DIR/platforms" "$PROJECT_DIR/scripts" "$PROJECT_DIR/patches"

rm -rf "$PROJECT_DIR/platforms/$PLATFORM"
cp -r "$RELEASE_TMP/platforms/$PLATFORM" "$PROJECT_DIR/platforms/"

cp "$RELEASE_TMP/scripts/lib.sh" "$PROJECT_DIR/scripts/lib.sh"
cp "$RELEASE_TMP/scripts/setup-env.sh" "$PROJECT_DIR/scripts/setup-env.sh"
if [ -f "$RELEASE_TMP/scripts/backup.sh" ]; then
    cp "$RELEASE_TMP/scripts/backup.sh" "$PROJECT_DIR/scripts/backup.sh"
fi

cp "$RELEASE_TMP/patches/glibc-compat.js" "$PROJECT_DIR/patches/glibc-compat.js"
cp "$RELEASE_TMP/patches/argon2-stub.js" "$PROJECT_DIR/patches/argon2-stub.js"
cp "$RELEASE_TMP/patches/spawn.h" "$PROJECT_DIR/patches/spawn.h"
cp "$RELEASE_TMP/patches/systemctl" "$PROJECT_DIR/patches/systemctl"

# Deploy supplementary glibc libraries (e.g., libcap.so.2 for native binary support)
if [ -d "$RELEASE_TMP/patches/glibc-libs" ] && [ -d "$PREFIX/glibc/lib" ]; then
    for _lib in "$RELEASE_TMP/patches/glibc-libs"/*.so.*; do
        [ -f "$_lib" ] || continue
        _fn=$(basename "$_lib")
        if [ ! -f "$PREFIX/glibc/lib/$_fn" ]; then
            cp "$_lib" "$PREFIX/glibc/lib/$_fn"
            _sn=$(echo "$_fn" | sed -E 's/^(lib[^.]+\.so\.[0-9]+)\..*/\1/')
            [ "$_sn" != "$_fn" ] && ln -sf "$_fn" "$PREFIX/glibc/lib/$_sn"
            echo -e "${GREEN}[OK]${NC}   Installed glibc lib: $_sn"
        fi
    done
fi

# Ensure glibc /etc/hosts exists (localhost resolution)
if [ -d "$PREFIX/glibc/etc" ] && [ ! -f "$PREFIX/glibc/etc/hosts" ]; then
    cat > "$PREFIX/glibc/etc/hosts" <<'HOSTS'
127.0.0.1 localhost localhost.localdomain
::1 localhost ip6-localhost ip6-loopback
HOSTS
    echo -e "${GREEN}[OK]${NC}   Created glibc /etc/hosts"
fi

cp "$RELEASE_TMP/oa.sh" "$PREFIX/bin/oa"
chmod +x "$PREFIX/bin/oa"

cp "$RELEASE_TMP/update.sh" "$PREFIX/bin/oaupdate"
chmod +x "$PREFIX/bin/oaupdate"

cp "$RELEASE_TMP/uninstall.sh" "$PROJECT_DIR/uninstall.sh"
chmod +x "$PROJECT_DIR/uninstall.sh"

# App installs: repair a git wrapper that exec's itself (non-fatal; no-op elsewhere)
if [ -f "$RELEASE_TMP/scripts/repair-app-git.sh" ]; then
    bash "$RELEASE_TMP/scripts/repair-app-git.sh" || echo -e "${YELLOW}[WARN]${NC} git repair did not finish (non-critical)"
elif [ -f "$PREFIX/bin/git.wrapper-installed" ]; then
    # optional on purpose (a cached tarball older than this updater must not block the whole update)
    echo -e "${YELLOW}[WARN]${NC} The git repair script is missing from the downloaded copy (cached download?). Run 'oa --update' again in a few minutes."
fi

if [ "$IS_GLIBC" = false ]; then
    echo ""
    echo -e "${BOLD}[MIGRATE] Bionic -> glibc Architecture${NC}"
    echo "----------------------------------------"
    if bash "$RELEASE_TMP/scripts/install-glibc.sh" && bash "$RELEASE_TMP/scripts/install-nodejs.sh" "$PLATFORM_NODE_VERSION" "${PLATFORM_NPM_PACKAGE:-}" "${PLATFORM_NPM_PACKAGE_VERSION:-}"; then
        IS_GLIBC=true
        echo -e "${GREEN}[OK]${NC}   glibc migration complete"
    else
        echo -e "${YELLOW}[WARN]${NC} glibc migration failed (non-critical)"
    fi
fi

# Converge Node.js to the pinned version (checked by the Node gate before step 4)
if [ "$IS_GLIBC" = true ]; then
    bash "$RELEASE_TMP/scripts/install-nodejs.sh" "$PLATFORM_NODE_VERSION" "${PLATFORM_NPM_PACKAGE:-}" "${PLATFORM_NPM_PACKAGE_VERSION:-}" || true
fi

bash "$RELEASE_TMP/scripts/setup-env.sh"

GLIBC_NODE_DIR="$PROJECT_DIR/node"
GLIBC_BIN_DIR="$PROJECT_DIR/bin"
if [ "$IS_GLIBC" = true ]; then
    # Migrate wrappers from node/bin/ to bin/ (safe from npm overwrites)
    if [ ! -d "$GLIBC_BIN_DIR" ] || [ ! -x "$GLIBC_BIN_DIR/node" ]; then
        echo ""
        echo -e "${BOLD}[MIGRATE] Moving wrappers to $GLIBC_BIN_DIR${NC}"
        bash "$RELEASE_TMP/scripts/install-nodejs.sh" "$PLATFORM_NODE_VERSION" "${PLATFORM_NPM_PACKAGE:-}" "${PLATFORM_NPM_PACKAGE_VERSION:-}" || true
        echo -e "${GREEN}[OK]${NC}   Wrapper migration complete"
    fi
    export PATH="$GLIBC_BIN_DIR:$GLIBC_NODE_DIR/bin:$HOME/.local/bin:$PATH"
    export OA_GLIBC=1
fi
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
# Load platform-specific environment variables for current session
PLATFORM_ENV_SCRIPT="$RELEASE_TMP/platforms/$PLATFORM/env.sh"
if [ -f "$PLATFORM_ENV_SCRIPT" ]; then
    eval "$(bash "$PLATFORM_ENV_SCRIPT")"
fi

# Node gate: the pinned platform version is only verified with the pinned
# Node.js. If Node.js did not converge, stop here and leave the platform as is
# (installing the pinned platform on another Node.js could break a working setup).
CURRENT_NODE_VER=$(node --version 2>/dev/null || echo "")
if [ "$CURRENT_NODE_VER" != "v$PLATFORM_NODE_VERSION" ]; then
    echo ""
    echo -e "${RED}[FAIL]${NC} Node.js v${PLATFORM_NODE_VERSION} is required (found: ${CURRENT_NODE_VER:-none}) — update stopped before $PLATFORM."
    echo "       $PLATFORM itself was not changed."
    if [ "$IS_GLIBC" = false ]; then
        echo "       The glibc migration did not complete — see the messages above."
    else
        echo "       See the Node.js messages above for the cause (network, checksum, disk space)."
    fi
    echo "       Then run 'oa --update' again."
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   Node.js $CURRENT_NODE_VER (pinned)"

step 4 "Update Platform"

if [ -f "$RELEASE_TMP/platforms/$PLATFORM/update.sh" ]; then
    bash "$RELEASE_TMP/platforms/$PLATFORM/update.sh"
else
    echo -e "${YELLOW}[WARN]${NC} Platform update script not found"
fi

step 5 "Update Optional Tools"

if command -v code-server &>/dev/null; then
    if bash "$RELEASE_TMP/scripts/install-code-server.sh" update; then
        echo -e "${GREEN}[OK]${NC}   code-server update step complete"
    else
        echo -e "${YELLOW}[WARN]${NC} code-server update failed (non-critical)"
    fi
else
    echo -e "${YELLOW}[SKIP]${NC} code-server not installed"
fi

if command -v chromium-browser &>/dev/null || command -v chromium &>/dev/null; then
    if [ -f "$RELEASE_TMP/scripts/install-chromium.sh" ]; then
        bash "$RELEASE_TMP/scripts/install-chromium.sh" update || true
    fi
else
    echo -e "${YELLOW}[SKIP]${NC} Chromium not installed"
fi

if [ "$IS_GLIBC" = false ]; then
    echo -e "${YELLOW}[SKIP]${NC} OpenCode requires glibc architecture"
else
    OPENCODE_INSTALLED=false
    command -v opencode &>/dev/null && OPENCODE_INSTALLED=true

    if [ "$OPENCODE_INSTALLED" = true ]; then
        CURRENT_OC_VER=$(opencode --version 2>/dev/null || echo "")
        LATEST_OC_VER=$(npm view opencode-ai version 2>/dev/null || echo "")

        if [ -n "$CURRENT_OC_VER" ] && [ -n "$LATEST_OC_VER" ] && [ "$CURRENT_OC_VER" = "$LATEST_OC_VER" ]; then
            echo -e "${GREEN}[OK]${NC}   OpenCode $CURRENT_OC_VER is already the latest"
        else
            if [ -n "$CURRENT_OC_VER" ] && [ -n "$LATEST_OC_VER" ] && [ "$CURRENT_OC_VER" != "$LATEST_OC_VER" ]; then
                echo "OpenCode update available: $CURRENT_OC_VER -> $LATEST_OC_VER"
            fi
            echo "  (This may take a few minutes for package download and binary processing)"
            if bash "$RELEASE_TMP/scripts/install-opencode.sh"; then
                echo -e "${GREEN}[OK]${NC}   OpenCode ${LATEST_OC_VER:-} updated"
            else
                echo -e "${YELLOW}[WARN]${NC} OpenCode update failed (non-critical)"
            fi
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} OpenCode not installed"
    fi
fi

update_ai_tool() {
    local cmd="$1"
    local pkg="$2"
    local label="$3"

    if ! command -v "$cmd" &>/dev/null; then
        return 1
    fi

    local current_ver latest_ver
    current_ver=$(npm list -g "$pkg" 2>/dev/null | grep "${pkg##*/}@" | sed 's/.*@//' | tr -d '[:space:]')
    latest_ver=$(npm view "$pkg" version 2>/dev/null || echo "")

    if [ -n "$current_ver" ] && [ -n "$latest_ver" ] && [ "$current_ver" = "$latest_ver" ]; then
        echo -e "${GREEN}[OK]${NC}   $label $current_ver is already the latest"
    elif [ -n "$latest_ver" ]; then
        echo "Updating $label... ($current_ver -> $latest_ver)"
        echo "  (This may take a few minutes depending on network speed)"
        # --ignore-scripts keeps native builds (keytar, node-pty) from failing the update,
        # but Claude Code's launcher is a placeholder that its postinstall replaces with
        # the native binary: without the script the update leaves `claude` broken.
        local scripts_flag="--ignore-scripts"
        [ "$pkg" = "@anthropic-ai/claude-code" ] && scripts_flag=""
        # shellcheck disable=SC2086
        if npm install -g "$pkg@latest" --no-fund --no-audit $scripts_flag; then
            echo -e "${GREEN}[OK]${NC}   $label $latest_ver updated"
        else
            echo -e "${YELLOW}[WARN]${NC} $label update failed (non-critical)"
        fi
    else
        echo -e "${YELLOW}[WARN]${NC} Could not check $label latest version"
    fi
    return 0
}

AI_FOUND=false
# Migrate codex from upstream (static musl, broken DNS on Android) to Termux fork (dynamic Bionic)
if npm list -g @openai/codex &>/dev/null 2>&1; then
    echo -e "  ${YELLOW}[MIGRATE]${NC} Replacing @openai/codex with Termux-optimized @mmmbuto/codex-cli-termux..."
    npm uninstall -g @openai/codex 2>/dev/null || true
fi
update_ai_tool "claude" "@anthropic-ai/claude-code" "Claude Code" && AI_FOUND=true
update_ai_tool "gemini" "@google/gemini-cli" "Gemini CLI" && AI_FOUND=true
update_ai_tool "codex" "@mmmbuto/codex-cli-termux" "Codex CLI (Termux)" && AI_FOUND=true
# Create/refresh codex CLI wrapper (DioNanos fork launcher fix)
_codex_bin="$PREFIX/bin/codex"
_codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
if [ -f "$_codex_pkg/codex.bin" ]; then
    [ -L "$_codex_bin" ] && rm -f "$_codex_bin"
    printf '#!%s/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="$PKG_BIN:${LD_LIBRARY_PATH:-}"\nexec "$PKG_BIN/codex.bin" "$@"\n' \
        "$PREFIX" "$_codex_pkg" > "$_codex_bin"
    chmod +x "$_codex_bin"
fi
if [ "$AI_FOUND" = false ]; then
    echo -e "${YELLOW}[SKIP]${NC} No AI CLI tools installed"
fi

command -v fix_npm_global_shebangs >/dev/null 2>&1 && fix_npm_global_shebangs || true

echo ""
echo -e "${GREEN}${BOLD}  Update Complete!${NC}"
echo ""
echo -e "${YELLOW}Run this to apply changes to the current session:${NC}"
echo ""
echo "  source ~/.bashrc"

# A gateway that was already running keeps the runtime it started with (the old
# node wrapper's options), so it does not benefit from this update until it is
# restarted. We never restart it ourselves (it belongs to the user's session or
# the app). Detection is best-effort: if it fails, only this notice is skipped.
_gw_running=false
if command -v pgrep &>/dev/null; then
    # Trust pgrep when it exists (a port answer could be another install's gateway)
    if pgrep -f 'openclaw.*gateway' &>/dev/null; then
        _gw_running=true
    fi
elif curl -s --noproxy '*' --max-time 2 -o /dev/null "http://127.0.0.1:18789/" 2>/dev/null; then
    _gw_running=true
fi
if [ "$_gw_running" = true ]; then
    echo ""
    echo -e "${YELLOW}The OpenClaw gateway may still be using the previous runtime.${NC}"
    echo "  Restart the gateway to use the updated runtime: stop it and run 'openclaw gateway' again (or restart the app)."
fi
