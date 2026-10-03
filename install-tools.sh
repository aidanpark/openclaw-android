#!/usr/bin/env bash
# =============================================================================
# install-tools.sh — 번들 제공 도구 설치
#
# oa --install 로 실행. 초기 설치 시 설치하지 않은 도구를 나중에 설치할 수 있다.
# 이미 설치된 도구는 [INSTALLED]로 표시하고 건너뛴다.
# =============================================================================
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

PROJECT_DIR="$HOME/.openclaw-android"
PLATFORM_MARKER="$PROJECT_DIR/.platform"
OA_VERSION="1.1.1"
REPO_TARBALL="https://github.com/AidanPark/openclaw-android/archive/refs/heads/main.tar.gz"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Install Tools${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

# --- Pre-checks ---
if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    exit 1
fi

if ! command -v curl &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
    exit 1
fi

if [ -f "$PROJECT_DIR/scripts/lib.sh" ]; then
    source "$PROJECT_DIR/scripts/lib.sh"
fi
command -v resolve_npm_registry >/dev/null 2>&1 && resolve_npm_registry || true

if ! declare -f ask_yn &>/dev/null; then
    ask_yn() {
        local prompt="$1"
        local reply
        read -rp "$prompt [Y/n] " reply < /dev/tty
        [[ "${reply:-}" =~ ^[Nn]$ ]] && return 1
        return 0
    }
fi

IS_GLIBC=false
if [ -f "$PROJECT_DIR/.glibc-arch" ]; then
    IS_GLIBC=true
fi

# --- Detect installed tools ---
echo -e "${BOLD}Checking installed tools...${NC}"
echo ""

declare -A TOOL_STATUS

check_tool() {
    local name="$1"
    local cmd="$2"
    if command -v "$cmd" &>/dev/null; then
        TOOL_STATUS["$name"]="installed"
        echo -e "  ${GREEN}[INSTALLED]${NC} $name"
    else
        TOOL_STATUS["$name"]="not_installed"
        echo -e "  ${YELLOW}[NOT INSTALLED]${NC} $name"
    fi
}

check_tool "tmux" "tmux"
check_tool "ttyd" "ttyd"
check_tool "dufs" "dufs"
check_tool "android-tools" "adb"
check_tool "Chromium" "chromium-browser"
if command -v npm &>/dev/null && npm list -g playwright-core &>/dev/null 2>&1; then
    TOOL_STATUS["Playwright"]="installed"
    echo -e "  ${GREEN}[INSTALLED]${NC} Playwright"
else
    TOOL_STATUS["Playwright"]="not_installed"
    echo -e "  ${YELLOW}[NOT INSTALLED]${NC} Playwright"
fi
check_tool "code-server" "code-server"
if [ "$IS_GLIBC" = true ]; then
    check_tool "OpenCode" "opencode"
fi
check_tool "Claude Code" "claude"
check_tool "Gemini CLI" "gemini"
check_tool "Codex CLI (Termux)" "codex"

echo ""

# --- Check if anything to install ---
HAS_UNINSTALLED=false
for status in "${TOOL_STATUS[@]}"; do
    if [ "$status" = "not_installed" ]; then
        HAS_UNINSTALLED=true
        break
    fi
done

if [ "$HAS_UNINSTALLED" = false ]; then
    echo -e "${GREEN}All available tools are already installed.${NC}"
    echo ""
    exit 0
fi

# --- Collect selections ---
echo -e "${BOLD}Select tools to install:${NC}"
echo ""

INSTALL_TMUX=false
INSTALL_TTYD=false
INSTALL_DUFS=false
INSTALL_ANDROID_TOOLS=false
INSTALL_CODE_SERVER=false
INSTALL_OPENCODE=false
INSTALL_CLAUDE_CODE=false
INSTALL_GEMINI_CLI=false
INSTALL_CODEX_CLI=false
INSTALL_CHROMIUM=false
INSTALL_PLAYWRIGHT=false

if [ "${TOOL_STATUS[tmux]}" = "not_installed" ] && ask_yn "  Install tmux (terminal multiplexer)?"; then INSTALL_TMUX=true; fi
if [ "${TOOL_STATUS[ttyd]}" = "not_installed" ] && ask_yn "  Install ttyd (web terminal)?"; then INSTALL_TTYD=true; fi
if [ "${TOOL_STATUS[dufs]}" = "not_installed" ] && ask_yn "  Install dufs (file server)?"; then INSTALL_DUFS=true; fi
if [ "${TOOL_STATUS[android-tools]}" = "not_installed" ] && ask_yn "  Install android-tools (adb)?"; then INSTALL_ANDROID_TOOLS=true; fi
if [ "${TOOL_STATUS[Chromium]}" = "not_installed" ] && ask_yn "  Install Chromium (browser automation, ~400MB)?"; then INSTALL_CHROMIUM=true; fi
if [ "${TOOL_STATUS[Playwright]}" = "not_installed" ] && ask_yn "  Install Playwright (browser automation library, requires Chromium)?"; then INSTALL_PLAYWRIGHT=true; fi
if [ "${TOOL_STATUS[code-server]}" = "not_installed" ] && ask_yn "  Install code-server (browser IDE)?"; then INSTALL_CODE_SERVER=true; fi
if [ "$IS_GLIBC" = true ] && [ "${TOOL_STATUS[OpenCode]}" = "not_installed" ]; then
    if ask_yn "  Install OpenCode (AI coding assistant)?"; then INSTALL_OPENCODE=true; fi
fi
if [ "${TOOL_STATUS[Claude Code]}" = "not_installed" ] && ask_yn "  Install Claude Code CLI?"; then INSTALL_CLAUDE_CODE=true; fi
if [ "${TOOL_STATUS[Gemini CLI]}" = "not_installed" ] && ask_yn "  Install Gemini CLI?"; then INSTALL_GEMINI_CLI=true; fi
if [ "${TOOL_STATUS[Codex CLI (Termux)]}" = "not_installed" ] && ask_yn "  Install Codex CLI (Termux)?"; then INSTALL_CODEX_CLI=true; fi

# --- Check if anything selected ---
ANYTHING_SELECTED=false
for var in INSTALL_TMUX INSTALL_TTYD INSTALL_DUFS INSTALL_ANDROID_TOOLS \
           INSTALL_CHROMIUM INSTALL_PLAYWRIGHT INSTALL_CODE_SERVER INSTALL_OPENCODE \
           INSTALL_CLAUDE_CODE INSTALL_GEMINI_CLI INSTALL_CODEX_CLI; do
    if [ "${!var}" = true ]; then
        ANYTHING_SELECTED=true
        break
    fi
done

if [ "$ANYTHING_SELECTED" = false ]; then
    echo ""
    echo "No tools selected."
    exit 0
fi

# --- Download scripts (needed for code-server and OpenCode) ---
NEEDS_TARBALL=false
if [ "$INSTALL_CODE_SERVER" = true ] || [ "$INSTALL_OPENCODE" = true ] || [ "$INSTALL_CHROMIUM" = true ] || [ "$INSTALL_PLAYWRIGHT" = true ]; then
    NEEDS_TARBALL=true
fi

if [ "$NEEDS_TARBALL" = true ]; then
    echo ""
    echo "Downloading install scripts..."
    mkdir -p "$PREFIX/tmp"
    RELEASE_TMP=$(mktemp -d "$PREFIX/tmp/oa-install.XXXXXX") || {
        echo -e "${RED}[FAIL]${NC} Failed to create temp directory"
        exit 1
    }
    trap 'rm -rf "$RELEASE_TMP"' EXIT

    if curl -sfL "$REPO_TARBALL" | tar xz -C "$RELEASE_TMP" --strip-components=1; then
        echo -e "${GREEN}[OK]${NC}   Downloaded install scripts"
    else
        echo -e "${RED}[FAIL]${NC} Failed to download scripts"
        exit 1
    fi
fi

# --- Install selected tools ---
echo ""
echo -e "${BOLD}Installing selected tools...${NC}"
echo ""

if [ "$INSTALL_TMUX" = true ]; then echo "Installing tmux..."; if pkg install -y tmux; then echo -e "${GREEN}[OK]${NC}   tmux installed"; fi; fi
if [ "$INSTALL_TTYD" = true ]; then echo "Installing ttyd..."; if pkg install -y ttyd; then echo -e "${GREEN}[OK]${NC}   ttyd installed"; fi; fi
if [ "$INSTALL_DUFS" = true ]; then echo "Installing dufs..."; if pkg install -y dufs; then echo -e "${GREEN}[OK]${NC}   dufs installed"; fi; fi
if [ "$INSTALL_ANDROID_TOOLS" = true ]; then echo "Installing android-tools..."; if pkg install -y android-tools; then echo -e "${GREEN}[OK]${NC}   android-tools installed"; fi; fi

if [ "$INSTALL_CODE_SERVER" = true ]; then
    mkdir -p "$PROJECT_DIR/patches"
    cp "$RELEASE_TMP/patches/argon2-stub.js" "$PROJECT_DIR/patches/argon2-stub.js"
    if bash "$RELEASE_TMP/scripts/install-code-server.sh" install; then
        echo -e "${GREEN}[OK]${NC}   code-server installed"
    else
        echo -e "${YELLOW}[WARN]${NC} code-server installation failed (non-critical)"
    fi
fi

if [ "$INSTALL_OPENCODE" = true ]; then
    if bash "$RELEASE_TMP/scripts/install-opencode.sh"; then
        echo -e "${GREEN}[OK]${NC}   OpenCode installed"
    else
        echo -e "${YELLOW}[WARN]${NC} OpenCode installation failed (non-critical)"
    fi
fi

if [ "$INSTALL_CHROMIUM" = true ]; then
    if bash "$RELEASE_TMP/scripts/install-chromium.sh" install; then
        echo -e "${GREEN}[OK]${NC}   Chromium installed"
    else
        echo -e "${YELLOW}[WARN]${NC} Chromium installation failed (non-critical)"
    fi
fi

if [ "$INSTALL_PLAYWRIGHT" = true ]; then
    if bash "$RELEASE_TMP/scripts/install-playwright.sh" install; then
        echo -e "${GREEN}[OK]${NC}   Playwright installed"
    else
        echo -e "${YELLOW}[WARN]${NC} Playwright installation failed (non-critical)"
    fi
fi

if [ "$INSTALL_CLAUDE_CODE" = true ]; then
    echo "Installing Claude Code..."
    if npm install -g @anthropic-ai/claude-code; then
        if timeout 30 claude --version >/dev/null 2>&1; then
            echo -e "${GREEN}[OK]${NC}   Claude Code installed"
        else
            echo -e "${YELLOW}[WARN]${NC} Claude Code installed, but its native binary does not run on this setup yet (support is planned)"
        fi
    else
        echo -e "${YELLOW}[WARN]${NC} Claude Code installation failed (non-critical) — skipped"
    fi
fi
if [ "$INSTALL_GEMINI_CLI" = true ]; then
    echo "Installing Gemini CLI..."
    if npm install -g @google/gemini-cli; then
        echo -e "${GREEN}[OK]${NC}   Gemini CLI installed"
    else
        echo -e "${YELLOW}[WARN]${NC} Gemini CLI installation failed (non-critical) — skipped"
    fi
fi
if [ "$INSTALL_CODEX_CLI" = true ]; then
    npm uninstall -g @openai/codex 2>/dev/null || true
    echo "Installing Codex CLI (Termux)..."
    if npm install -g @mmmbuto/codex-cli-termux; then
        # Create codex CLI wrapper (DioNanos fork launcher fix)
        _codex_bin="$PREFIX/bin/codex"
        _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
        if [ -f "$_codex_pkg/codex.bin" ]; then
            [ -L "$_codex_bin" ] && rm -f "$_codex_bin"
            printf '#!%s/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="$PKG_BIN:${LD_LIBRARY_PATH:-}"\nexec "$PKG_BIN/codex.bin" "$@"\n' \
                "$PREFIX" "$_codex_pkg" > "$_codex_bin"
            chmod +x "$_codex_bin"
        fi
        echo -e "${GREEN}[OK]${NC}   Codex CLI (Termux) installed"
    else
        echo -e "${YELLOW}[WARN]${NC} Codex CLI (Termux) installation failed (non-critical) — skipped"
        echo "       The package targets Termux's Android Node.js; support for this setup is planned."
    fi
fi

command -v fix_npm_global_shebangs >/dev/null 2>&1 && fix_npm_global_shebangs || true

echo ""
echo -e "${GREEN}${BOLD}  Installation Complete!${NC}"
echo ""
