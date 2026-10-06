#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$HOME/.openclaw-android"

if [ -f "$HOME/.openclaw-android/scripts/lib.sh" ]; then
    # shellcheck source=/dev/null
    source "$HOME/.openclaw-android/scripts/lib.sh"
    # shellcheck source=/dev/null
    if [ -f "$HOME/.openclaw-android/scripts/backup.sh" ]; then
        source "$HOME/.openclaw-android/scripts/backup.sh"
    fi
else
    OA_VERSION="1.2.0"
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BOLD='\033[1m'
    NC='\033[0m'
    REPO_BASE_ORIGIN="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
    REPO_BASE="$REPO_BASE_ORIGIN"
    PLATFORM_MARKER="$PROJECT_DIR/.platform"

    detect_platform() {
        if [ -f "$PLATFORM_MARKER" ]; then
            cat "$PLATFORM_MARKER"
            return 0
        fi
        return 1
    }

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
fi

show_help() {
    echo ""
    echo -e "${BOLD}oa${NC} — OpenClaw on Android CLI v${OA_VERSION}"
    echo ""
    echo "Usage: oa [option]"
    echo ""
    echo "Options:"
    echo "  --update       Update OpenClaw and Android patches"
    echo "  --install      Install optional tools (tmux, code-server, AI CLIs, etc.)"
    echo "  --uninstall    Remove OpenClaw on Android"
    echo "  --backup       Create a full backup of OpenClaw data"
    echo "  --restore      Restore from a backup (--force-no-safety: even if the safety backup fails)"
    echo "  --status       Show installation status and all components"
    echo "  --version, -v  Show version"
    echo "  --help, -h     Show this help message"
    echo ""
}

show_version() {
    echo "oa v${OA_VERSION} (OpenClaw on Android)"

    local latest
    latest=$(curl -sfL --max-time 3 "$REPO_BASE/scripts/lib.sh" 2>/dev/null \
        | grep -m1 '^OA_VERSION=' | cut -d'"' -f2) || true

    if [ -n "${latest:-}" ]; then
        if [ "$latest" = "$OA_VERSION" ]; then
            echo -e "  ${GREEN}Up to date${NC}"
        else
            echo -e "  ${YELLOW}v${latest} available${NC} - run: oa --update"
        fi
    fi
}

cmd_update() {
    if ! command -v curl &>/dev/null; then
        echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
        exit 1
    fi

    mkdir -p "$PROJECT_DIR"
    local LOGFILE="$PROJECT_DIR/update.log"

    local TMPFILE
    TMPFILE=$(mktemp "${TMPDIR:-${PREFIX:-/tmp}/tmp}/update-core.XXXXXX.sh" 2>/dev/null) \
        || TMPFILE=$(mktemp "/tmp/update-core.XXXXXX.sh")

    if ! curl -sfL "$REPO_BASE/update-core.sh" -o "$TMPFILE"; then
        rm -f "$TMPFILE"
        echo -e "${RED}[FAIL]${NC} Failed to download update-core.sh"
        exit 1
    fi

    # Keep the updater's exit code (pipefail) so callers see a failed update,
    # and still clean up and show where the log is.
    local rc=0
    bash "$TMPFILE" 2>&1 | tee "$LOGFILE" || rc=$?
    rm -f "$TMPFILE"

    echo ""
    echo -e "${YELLOW}Log saved to $LOGFILE${NC}"
    exit "$rc"
}

cmd_uninstall() {
    local UNINSTALL_SCRIPT="$PROJECT_DIR/uninstall.sh"

    if [ ! -f "$UNINSTALL_SCRIPT" ]; then
        echo -e "${RED}[FAIL]${NC} Uninstall script not found at $UNINSTALL_SCRIPT"
        echo ""
        echo "You can download it manually:"
        echo "  curl -sL $REPO_BASE/uninstall.sh -o $UNINSTALL_SCRIPT && chmod +x $UNINSTALL_SCRIPT"
        exit 1
    fi

    bash "$UNINSTALL_SCRIPT"
}

cmd_status() {
    echo ""
    echo -e "${BOLD}========================================${NC}"
    echo -e "${BOLD}  OpenClaw on Android — Status${NC}"
    echo -e "${BOLD}========================================${NC}"

    echo ""
    echo -e "${BOLD}Version${NC}"
    echo "  oa:          v${OA_VERSION}"

    local PLATFORM
    PLATFORM=$(detect_platform 2>/dev/null) || PLATFORM=""
    if [ -n "$PLATFORM" ]; then
        echo "  Platform:    $PLATFORM"
    else
        echo -e "  Platform:    ${RED}not detected${NC}"
    fi

    echo ""
    echo -e "${BOLD}Environment${NC}"
    echo "  PREFIX:            ${PREFIX:-not set}"
    echo "  TMPDIR:            ${TMPDIR:-not set}"

    echo ""
    echo -e "${BOLD}Paths${NC}"
    local CHECK_DIRS=("$PROJECT_DIR" "${PREFIX:-}/tmp")
    for dir in "${CHECK_DIRS[@]}"; do
        if [ -d "$dir" ]; then
            echo -e "  ${GREEN}[OK]${NC}   $dir"
        else
            echo -e "  ${RED}[MISS]${NC} $dir"
        fi
    done

    echo ""
    echo -e "${BOLD}Configuration${NC}"
    if grep -qF "OpenClaw on Android" "$HOME/.bashrc" 2>/dev/null; then
        echo -e "  ${GREEN}[OK]${NC}   .bashrc environment block present"
    else
        echo -e "  ${RED}[MISS]${NC} .bashrc environment block not found"
    fi

    local STATUS_SCRIPT="$PROJECT_DIR/platforms/$PLATFORM/status.sh"
    if [ -n "$PLATFORM" ] && [ -f "$STATUS_SCRIPT" ]; then
        bash "$STATUS_SCRIPT"
    fi

    echo ""
}

cmd_install() {
    if ! command -v curl &>/dev/null; then
        echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
        exit 1
    fi

    local TMPFILE
    TMPFILE=$(mktemp "${TMPDIR:-${PREFIX:-/tmp}/tmp}/install-tools.XXXXXX.sh" 2>/dev/null) \
        || TMPFILE=$(mktemp "/tmp/install-tools.XXXXXX.sh")

    if ! curl -sfL "$REPO_BASE/install-tools.sh" -o "$TMPFILE"; then
        rm -f "$TMPFILE"
        echo -e "${RED}[FAIL]${NC} Failed to download install-tools.sh"
        exit 1
    fi

    bash "$TMPFILE"
    rm -f "$TMPFILE"
}

# Resolve mirror before any network operation
case "${1:-}" in
    --update|--install)
        resolve_repo_base || true
        command -v resolve_npm_registry >/dev/null 2>&1 && resolve_npm_registry || true
        ;;
    --version|-v|--uninstall)
        resolve_repo_base || true
        ;;
esac

case "${1:-}" in
    --update)
        cmd_update
        ;;
    --install)
        cmd_install
        ;;
    --uninstall)
        cmd_uninstall
        ;;
    --backup)
        if declare -f cmd_backup > /dev/null 2>&1; then
            cmd_backup "${2:-}"
        else
            echo -e "${RED}[FAIL]${NC} backup.sh not found. Run: oa --update"
            exit 1
        fi
        ;;
    --restore)
        if declare -f cmd_restore > /dev/null 2>&1; then
            cmd_restore "${2:-}"
        else
            echo -e "${RED}[FAIL]${NC} backup.sh not found. Run: oa --update"
            exit 1
        fi
        ;;
    --status)
        cmd_status
        ;;
    --version|-v)
        show_version
        ;;
    --help|-h|"")
        show_help
        ;;
    *)
        echo -e "${RED}Unknown option: $1${NC}"
        echo ""
        show_help
        exit 1
        ;;
esac
