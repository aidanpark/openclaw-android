#!/usr/bin/env bash
set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="$HOME/.openclaw-android/patch.log"

echo "=== Applying OpenClaw Patches ==="
echo ""

mkdir -p "$(dirname "$LOG_FILE")"
echo "Patch application started: $(date)" > "$LOG_FILE"

if [ -f "$SCRIPT_DIR/openclaw-patch-paths.sh" ]; then
    bash "$SCRIPT_DIR/openclaw-patch-paths.sh" 2>&1 | tee -a "$LOG_FILE"
else
    echo -e "${RED}[FAIL]${NC} openclaw-patch-paths.sh not found in $SCRIPT_DIR"
    echo "  FAILED: openclaw-patch-paths.sh not found" >> "$LOG_FILE"
    exit 1
fi

# The package folder that update.sh / install.sh already found (with a time limit); empty = each patch script asks
# npm itself, as it always did.
OC_PKG_DIR="${OA_OPENCLAW_DIR:-}"
if [ -z "$OC_PKG_DIR" ] || [ ! -f "$OC_PKG_DIR/package.json" ]; then OC_PKG_DIR=""; fi

# Hard links are denied on Android: let OpenClaw copy instead (see the script's header)
if [ -f "$SCRIPT_DIR/openclaw-patch-hardlink.sh" ]; then
    bash "$SCRIPT_DIR/openclaw-patch-hardlink.sh" ${OC_PKG_DIR:+"$OC_PKG_DIR"} 2>&1 | tee -a "$LOG_FILE"
else
    echo -e "${RED}[FAIL]${NC} openclaw-patch-hardlink.sh not found in $SCRIPT_DIR"
    echo "  FAILED: openclaw-patch-hardlink.sh not found" >> "$LOG_FILE"
    exit 1
fi

echo ""
echo "Patch log saved to: $LOG_FILE"
echo -e "${GREEN}OpenClaw patches applied.${NC}"
echo "Patch application completed: $(date)" >> "$LOG_FILE"
