#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

ERRORS=0

echo "=== OpenClaw on Android - Environment Check ==="
echo ""

if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    echo "       This script is designed for Termux on Android."
    exit 1
else
    echo -e "${GREEN}[OK]${NC}   Termux detected (PREFIX=$PREFIX)"
fi

ARCH=$(uname -m)
echo -n "       Architecture: $ARCH"
if [ "$ARCH" = "aarch64" ]; then
    echo -e " ${GREEN}(recommended)${NC}"
elif [ "$ARCH" = "armv7l" ] || [ "$ARCH" = "arm" ]; then
    echo -e " ${YELLOW}(supported, but aarch64 recommended)${NC}"
elif [ "$ARCH" = "x86_64" ] || [ "$ARCH" = "i686" ]; then
    echo -e " ${YELLOW}(emulator detected)${NC}"
else
    echo -e " ${YELLOW}(unknown, may not work)${NC}"
fi

AVAILABLE_MB=$(oa_free_mb "$PREFIX")
if [ -n "$AVAILABLE_MB" ] && [ "$AVAILABLE_MB" -lt "$OA_MIN_FREE_INSTALL_MB" ]; then
    echo -e "${RED}[FAIL]${NC} Insufficient disk space: ${AVAILABLE_MB}MB available (need ${OA_MIN_FREE_INSTALL_MB}MB+)"
    ERRORS=$((ERRORS + 1))
else
    echo -e "${GREEN}[OK]${NC}   Disk space: ${AVAILABLE_MB:-unknown}MB available"
fi

if command -v node &>/dev/null; then
    NODE_VER=$(node -v 2>/dev/null || echo "unknown")
    echo -e "${GREEN}[OK]${NC}   Node.js found: $NODE_VER"
    NODE_MAJOR="${NODE_VER%%.*}"
    NODE_MAJOR="${NODE_MAJOR#v}"
    if [ "$NODE_MAJOR" -lt 24 ] 2>/dev/null; then
        echo -e "${YELLOW}[WARN]${NC} Node.js >= 24 required. Will be upgraded to the pinned version during install."
    fi
else
    echo -e "${YELLOW}[INFO]${NC} Node.js not found. Will be installed via glibc environment."
fi

SDK_INT=$(getprop ro.build.version.sdk 2>/dev/null || echo "0")
if [ "$SDK_INT" -ge 31 ] 2>/dev/null; then
    echo -e "${YELLOW}[INFO]${NC} Android 12+ detected — if background processes get killed (signal 9),"
    echo "       see: https://github.com/AidanPark/openclaw-android/blob/main/docs/disable-phantom-process-killer.md"
fi

echo ""
if [ "$ERRORS" -gt 0 ]; then
    echo -e "${RED}Environment check failed with $ERRORS error(s).${NC}"
    exit 1
else
    echo -e "${GREEN}Environment check passed.${NC}"
fi
