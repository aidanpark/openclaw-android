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

# Hard links are denied on Android: let OpenClaw copy instead (see the script's header)
if [ -f "$SCRIPT_DIR/openclaw-patch-hardlink.sh" ]; then
    bash "$SCRIPT_DIR/openclaw-patch-hardlink.sh" 2>&1 | tee -a "$LOG_FILE"
else
    echo -e "${RED}[FAIL]${NC} openclaw-patch-hardlink.sh not found in $SCRIPT_DIR"
    echo "  FAILED: openclaw-patch-hardlink.sh not found" >> "$LOG_FILE"
    exit 1
fi

echo ""
echo "Patch log saved to: $LOG_FILE"
echo -e "${GREEN}OpenClaw patches applied.${NC}"
echo "Patch application completed: $(date)" >> "$LOG_FILE"
