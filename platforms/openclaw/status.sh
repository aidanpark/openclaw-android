#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../../scripts/lib.sh"

echo ""
echo -e "${BOLD}Platform Components${NC}"

if command -v openclaw &>/dev/null; then
    echo "  OpenClaw:    $(openclaw --version 2>/dev/null || echo 'error')"
else
    echo -e "  OpenClaw:    ${RED}not installed${NC}"
fi

# The hard-link patches (OpenClaw copies/renames instead of linking): look at the files themselves
HL_SCRIPT="$SCRIPT_DIR/patches/openclaw-patch-hardlink.sh"
if [ -f "$HL_SCRIPT" ] && command -v openclaw &>/dev/null; then
    hl_out=$(bash "$HL_SCRIPT" --check 2>&1 | sed 's/\x1b\[[0-9;]*m//g') || true
    hl_sum=$(printf '%s\n' "$hl_out" | grep -E 'todo=|patched=' | tail -1 | sed 's/.*: //')
    if printf '%s' "$hl_sum" | grep -q 'todo=0 already=[0-9]* problems=0'; then
        echo "  Hard-link patch: applied (${hl_sum})"
    else
        echo -e "  Hard-link patch: ${YELLOW}not complete${NC} (${hl_sum:-unknown}) — run: oa --update"
    fi
else
    echo -e "  Hard-link patch: ${YELLOW}unknown${NC} (script or openclaw missing)"
fi

if command -v node &>/dev/null; then
    echo "  Node.js:     $(node -v 2>/dev/null)"
else
    echo -e "  Node.js:     ${RED}not installed${NC}"
fi

if command -v npm &>/dev/null; then
    echo "  npm:         $(npm -v 2>/dev/null)"
else
    echo -e "  npm:         ${RED}not installed${NC}"
fi

if command -v clawdhub &>/dev/null; then
    echo "  clawdhub:    $(clawdhub --version 2>/dev/null || echo 'installed')"
else
    echo -e "  clawdhub:    ${YELLOW}not installed${NC}"
fi

if command -v code-server &>/dev/null; then
    cs_ver=$(code-server --version 2>/dev/null || true)
    cs_ver="${cs_ver%%$'\n'*}"
    cs_status="stopped"
    if pgrep -f "code-server" &>/dev/null; then
        cs_status="running"
    fi
    echo "  code-server: ${cs_ver:-installed} ($cs_status)"
else
    echo -e "  code-server: ${YELLOW}not installed${NC}"
fi

if command -v opencode &>/dev/null; then
    oc_status="stopped"
    if pgrep -f "ld.so.opencode" &>/dev/null; then
        oc_status="running"
    fi
    echo "  OpenCode:    $(opencode --version 2>/dev/null || echo 'installed') ($oc_status)"
else
    echo -e "  OpenCode:    ${YELLOW}not installed${NC}"
fi

if command -v chromium-browser &>/dev/null || command -v chromium &>/dev/null; then
    cr_bin=$(command -v chromium-browser 2>/dev/null || command -v chromium 2>/dev/null)
    cr_ver=$($cr_bin --version 2>/dev/null | head -1 || echo 'installed')
    echo "  Chromium:    $cr_ver"
else
    echo -e "  Chromium:    ${YELLOW}not installed${NC}"
fi

echo ""
echo -e "${BOLD}Architecture${NC}"
if [ -f "$PROJECT_DIR/.glibc-arch" ]; then
    echo -e "  ${GREEN}[OK]${NC}   glibc (v1.0.0+)"
else
    echo -e "  ${YELLOW}[OLD]${NC} Bionic (pre-1.0.0) - run 'oa --update' to migrate"
fi

if [ "${OA_GLIBC:-}" = "1" ]; then
    echo -e "  ${GREEN}[OK]${NC}   OA_GLIBC=1 (environment)"
else
    echo -e "  ${YELLOW}[MISS]${NC} OA_GLIBC not set - run 'source ~/.bashrc'"
fi

echo ""
echo -e "${BOLD}glibc Components${NC}"
# The node wrapper reads lib/glibc-compat.js first; patches/ is the fallback (app, older wrappers)
COMPAT_JS="$PROJECT_DIR/lib/glibc-compat.js"
[ -f "$COMPAT_JS" ] || COMPAT_JS="$PROJECT_DIR/patches/glibc-compat.js"
GLIBC_FILES=(
    "$COMPAT_JS"
    "$PROJECT_DIR/.glibc-arch"
    "${PREFIX:-}/glibc/lib/ld-linux-aarch64.so.1"
)
for file in "${GLIBC_FILES[@]}"; do
    if [ -f "$file" ]; then
        echo -e "  ${GREEN}[OK]${NC}   $(basename "$file")"
    else
        echo -e "  ${RED}[MISS]${NC} $(basename "$file")"
    fi
done

NODE_WRAPPER="$BIN_DIR/node"
if [ -f "$NODE_WRAPPER" ] && grep -q "bash" "$NODE_WRAPPER"; then
    echo -e "  ${GREEN}[OK]${NC}   glibc node wrapper"
else
    echo -e "  ${RED}[MISS]${NC} glibc node wrapper"
fi

if [ -f "${PREFIX:-}/bin/opencode" ]; then
    echo -e "  ${GREEN}[OK]${NC}   opencode command"
else
    echo -e "  ${YELLOW}[MISS]${NC} opencode command"
fi


echo ""
echo -e "${BOLD}AI CLI Tools${NC}"
for tool in "claude:Claude Code" "gemini:Gemini CLI" "codex:Codex CLI (Termux)"; do
    cmd="${tool%%:*}"
    label="${tool##*:}"
    if command -v "$cmd" &>/dev/null; then
        version=$($cmd --version 2>/dev/null || echo "installed")
        version="${version%%$'\n'*}"
        echo -e "  ${GREEN}[OK]${NC}   $label: $version"
    else
        echo "  [--] $label: not installed"
    fi
done

echo ""
echo -e "${BOLD}Skills${NC}"
SKILLS_DIR="${CLAWDHUB_WORKDIR:-$HOME/.openclaw/workspace}/skills"
if [ -d "$SKILLS_DIR" ]; then
    count=$(find "$SKILLS_DIR" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l)
    echo "  Installed: $count"
    echo "  Path:      $SKILLS_DIR"
else
    echo "  No skills directory found"
fi

echo ""
echo -e "${BOLD}Disk${NC}"
if [ -d "$PROJECT_DIR" ]; then
    echo "  ~/.openclaw-android:  $(du -sh "$PROJECT_DIR" 2>/dev/null | cut -f1)"
fi
if [ -d "$HOME/.openclaw" ]; then
    echo "  ~/.openclaw:          $(du -sh "$HOME/.openclaw" 2>/dev/null | cut -f1)"
fi
if [ -d "$HOME/.bun" ]; then
    echo "  ~/.bun:               $(du -sh "$HOME/.bun" 2>/dev/null | cut -f1)"
fi
AVAIL_MB=$(df "${PREFIX:-/}" 2>/dev/null | awk 'NR==2 {print int($4/1024)}') || true
echo "  Available:            ${AVAIL_MB:-unknown}MB"
