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
OA_VERSION="1.1.2"

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
ask_yn() {
    local prompt="$1"
    local reply
    if (echo -n "" > /dev/tty) 2>/dev/null; then
        read -rp "$prompt [Y/n] " reply < /dev/tty
    else
        read -rp "$prompt [Y/n] " reply
    fi
    [[ "${reply:-}" =~ ^[Nn]$ ]] && return 1
    return 0
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
