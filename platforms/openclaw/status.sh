#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../../scripts/lib.sh"
# node with a time limit (this file is also installed alone by the installer: limits.sh may not be next to it)
if [ -f "$SCRIPT_DIR/limits.sh" ]; then
    # shellcheck source=limits.sh
    source "$SCRIPT_DIR/limits.sh"
fi

# st_ver <text when it fails> <command...>: the first line of the command's output, with a time limit (a command
# that does not answer must not stop the whole status screen; the screen says "unknown" for it)
st_ver() {
    local fallback="$1" out="" rc=0
    shift
    if declare -f oc_capture >/dev/null 2>&1; then
        out="$(oc_capture probe "$@")" || rc=$?
    else
        out="$("$@" 2>/dev/null)" || rc=$?
    fi
    out="${out%%$'\n'*}"
    if [ -n "$out" ] && { [ "$rc" = 0 ] || [ "$rc" = 75 ]; }; then
        printf '%s' "$out"
    elif [ "$rc" = 75 ]; then
        printf 'unknown (no answer within %ss)' "$(oc_limit_seconds probe)"
    else
        printf '%s' "$fallback"
    fi
}

echo ""
echo -e "${BOLD}Platform Components${NC}"

if command -v openclaw &>/dev/null; then
    echo "  OpenClaw:    $(st_ver error openclaw --version)"
else
    echo -e "  OpenClaw:    ${RED}not installed${NC}"
fi

# The hard-link patches (OpenClaw copies/renames instead of linking): look at the files themselves
HL_SCRIPT="$SCRIPT_DIR/patches/openclaw-patch-hardlink.sh"
if [ -f "$HL_SCRIPT" ] && command -v openclaw &>/dev/null; then
    # (the package folder is given: the script would ask `npm root -g` itself, without a limit)
    hl_dir=""
    if declare -f oc_npm_root >/dev/null 2>&1; then
        hl_dir="$(oc_npm_root)/openclaw"
    fi
    hl_out=$(bash "$HL_SCRIPT" --check ${hl_dir:+"$hl_dir"} 2>&1 | sed 's/\x1b\[[0-9;]*m//g') || true
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
    if declare -f oc_node_version >/dev/null 2>&1; then
        echo "  Node.js:     $(oc_node_version)"
    else
        echo "  Node.js:     $(st_ver unknown node -v)"
    fi
else
    echo -e "  Node.js:     ${RED}not installed${NC}"
fi

if command -v npm &>/dev/null; then
    echo "  npm:         $(st_ver unknown npm -v)"
else
    echo -e "  npm:         ${RED}not installed${NC}"
fi

if command -v clawdhub &>/dev/null; then
    echo "  clawdhub:    $(st_ver installed clawdhub --version)"
else
    echo -e "  clawdhub:    ${YELLOW}not installed${NC}"
fi

if command -v code-server &>/dev/null; then
    cs_ver="$(st_ver "" code-server --version)"
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
    echo "  OpenCode:    $(st_ver installed opencode --version) ($oc_status)"
else
    echo -e "  OpenCode:    ${YELLOW}not installed${NC}"
fi

if command -v chromium-browser &>/dev/null || command -v chromium &>/dev/null; then
    cr_bin=$(command -v chromium-browser 2>/dev/null || command -v chromium 2>/dev/null)
    cr_ver="$(st_ver installed "$cr_bin" --version)"
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

# (OpenCode is optional, and not supported in the app: no line when it is not installed — the
# Platform Components list above already says so)
if command -v opencode &>/dev/null; then
    if [ -f "${PREFIX:-}/bin/opencode" ]; then
        echo -e "  ${GREEN}[OK]${NC}   opencode command"
    else
        echo -e "  ${YELLOW}[MISS]${NC} opencode command"
    fi
fi


echo ""
echo -e "${BOLD}AI CLI Tools${NC}"
for tool in "claude:Claude Code" "gemini:Gemini CLI" "codex:Codex CLI (Termux)"; do
    cmd="${tool%%:*}"
    label="${tool##*:}"
    if command -v "$cmd" &>/dev/null; then
        version="$(st_ver installed "$cmd" --version)"
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
# (oa_free_mb tries the system df too: the df of the Termux packages does not start in the app terminal)
AVAIL_MB=$(oa_free_mb "${PREFIX:-/}" 2>/dev/null || true)
if [ -n "$AVAIL_MB" ]; then
    echo "  Available:            ${AVAIL_MB}MB"
else
    echo "  Available:            unknown"
fi
