#!/usr/bin/env bash
# patch-paths.sh - Patch hardcoded paths in installed OpenClaw modules
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo "=== Patching Hardcoded Paths ==="
echo ""

# Ensure required environment variables are set (for standalone use)
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"

# Find OpenClaw installation directory
NPM_ROOT=$(npm root -g 2>/dev/null)
OPENCLAW_DIR="$NPM_ROOT/openclaw"

if [ ! -d "$OPENCLAW_DIR" ]; then
    echo -e "${RED}[FAIL]${NC} OpenClaw not found at $OPENCLAW_DIR"
    exit 1
fi

echo "OpenClaw found at: $OPENCLAW_DIR"

PATCHED=0

# Patch /tmp references to $PREFIX/tmp
echo "Patching /tmp references..."
# Only the files that still have one of the five forms replaced below. (A plain '/tmp' also finds the files that
# are patched already - their "$PREFIX/tmp" has it too - and a repeated run then rewrote hundreds of files with
# five sed passes each: 36 s on a phone, every update.)
TMP_FILES=$(grep -rlF -e '"/tmp/' -e "'/tmp/" -e '`/tmp/' -e '"/tmp"' -e "'/tmp'" "$OPENCLAW_DIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null || true)

for f in $TMP_FILES; do
    if [ -f "$f" ]; then
        # Patch /tmp/ prefix paths (e.g. "/tmp/openclaw") — must run before exact match
        sed -i "s|\"\/tmp/|\"$PREFIX/tmp/|g" "$f"
        sed -i "s|'\/tmp/|'$PREFIX/tmp/|g" "$f"
        sed -i "s|\`\/tmp/|\`$PREFIX/tmp/|g" "$f"
        # Patch exact /tmp references (e.g. "/tmp")
        sed -i "s|\"\/tmp\"|\"$PREFIX/tmp\"|g" "$f"
        sed -i "s|'\/tmp'|'$PREFIX/tmp'|g" "$f"
        echo -e "  ${GREEN}[PATCHED]${NC} $f (tmp path)"
        PATCHED=$((PATCHED + 1))
    fi
done

# Patch /bin/sh references
echo "Patching /bin/sh references..."
SH_FILES=$(grep -rl '"/bin/sh"' "$OPENCLAW_DIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null || true)
SH_FILES2=$(grep -rl "'/bin/sh'" "$OPENCLAW_DIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null || true)

for f in $SH_FILES $SH_FILES2; do
    if [ -f "$f" ]; then
        sed -i "s|\"\/bin\/sh\"|\"$PREFIX/bin/sh\"|g" "$f"
        sed -i "s|'\/bin\/sh'|'$PREFIX/bin/sh'|g" "$f"
        echo -e "  ${GREEN}[PATCHED]${NC} $f (bin/sh)"
        PATCHED=$((PATCHED + 1))
    fi
done

# Patch /bin/bash references
echo "Patching /bin/bash references..."
BASH_FILES=$(grep -rl '"/bin/bash"' "$OPENCLAW_DIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null || true)
BASH_FILES2=$(grep -rl "'/bin/bash'" "$OPENCLAW_DIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null || true)

for f in $BASH_FILES $BASH_FILES2; do
    if [ -f "$f" ]; then
        sed -i "s|\"\/bin\/bash\"|\"$PREFIX/bin/bash\"|g" "$f"
        sed -i "s|'\/bin\/bash'|'$PREFIX/bin/bash'|g" "$f"
        echo -e "  ${GREEN}[PATCHED]${NC} $f (bin/bash)"
        PATCHED=$((PATCHED + 1))
    fi
done

# Patch /usr/bin/env references
echo "Patching /usr/bin/env references..."
ENV_FILES=$(grep -rl '"/usr/bin/env"' "$OPENCLAW_DIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null || true)
ENV_FILES2=$(grep -rl "'/usr/bin/env'" "$OPENCLAW_DIR" --include='*.js' --include='*.mjs' --include='*.cjs' 2>/dev/null || true)

for f in $ENV_FILES $ENV_FILES2; do
    if [ -f "$f" ]; then
        sed -i "s|\"\/usr\/bin\/env\"|\"$PREFIX/bin/env\"|g" "$f"
        sed -i "s|'\/usr\/bin\/env'|'$PREFIX/bin/env'|g" "$f"
        echo -e "  ${GREEN}[PATCHED]${NC} $f (usr/bin/env)"
        PATCHED=$((PATCHED + 1))
    fi
done

echo ""
if [ "$PATCHED" -eq 0 ]; then
    echo -e "${YELLOW}[INFO]${NC} No hardcoded paths found to patch."
else
    echo -e "${GREEN}Patched $PATCHED file(s).${NC}"
fi
