#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/scripts/lib.sh"

echo "=== Installing OpenClaw Platform Package ==="
echo ""

export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

# Version pin (SSOT: config.env). This script runs as a child process, so it
# loads the pin itself instead of relying on the parent installer's variables.
load_platform_config openclaw "$SCRIPT_DIR/../.."
if ! [[ "${PLATFORM_NPM_PACKAGE_VERSION:-}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo -e "${RED}[FAIL]${NC} Invalid OpenClaw version pin in config.env: '${PLATFORM_NPM_PACKAGE_VERSION:-}'"
    exit 1
fi
OC_PIN="$PLATFORM_NPM_PACKAGE@$PLATFORM_NPM_PACKAGE_VERSION"

python -c "import yaml" 2>/dev/null || { command -v pip >/dev/null 2>&1 && pip install pyyaml -q; } || true

mkdir -p "$PROJECT_DIR/patches"
cp "$SCRIPT_DIR/../../patches/glibc-compat.js" "$PROJECT_DIR/patches/glibc-compat.js"

cp "$SCRIPT_DIR/../../patches/systemctl" "$PREFIX/bin/systemctl"
chmod +x "$PREFIX/bin/systemctl"

# Clean up existing installation for smooth reinstall
if npm list -g openclaw &>/dev/null 2>&1 || [ -d "$PREFIX/lib/node_modules/openclaw" ]; then
    echo "Existing installation detected \u2014 cleaning up for reinstall..."
    npm uninstall -g openclaw 2>/dev/null || true
    rm -rf "$PREFIX/lib/node_modules/openclaw" 2>/dev/null || true
    npm uninstall -g clawdhub 2>/dev/null || true
    rm -rf "$PREFIX/lib/node_modules/clawdhub" 2>/dev/null || true
    rm -rf "$HOME/.npm/_cacache" 2>/dev/null || true
    echo -e "${GREEN}[OK]${NC}   Previous installation cleaned"
fi

# A leftover version guard (a plain file, not an npm link) makes npm fail with EEXIST
if [ -e "$PREFIX/bin/openclaw" ] && [ ! -L "$PREFIX/bin/openclaw" ]; then
    rm -f "$PREFIX/bin/openclaw"
fi

# Our own mark that the install is under way (outside the package, which npm replaces): removed only after the
# checks below have passed; while it is there the package counts as incomplete (see oa_openclaw_incomplete in lib.sh)
OC_PENDING_MARK="$PROJECT_DIR/.openclaw-install-pending"
mkdir -p "$PROJECT_DIR" 2>/dev/null || true
touch "$OC_PENDING_MARK" 2>/dev/null || true

echo "Running: npm install -g $OC_PIN --ignore-scripts"
echo "This may take several minutes..."
echo ""
npm install -g "$OC_PIN" --ignore-scripts

echo ""
echo -e "${GREEN}[OK]${NC}   OpenClaw installed"

# Run the package postinstall that --ignore-scripts skipped (prunes stale dist
# files, applies bundled hotfixes). npm_config_ignore_scripts=true keeps any
# nested npm call from running native builds that fail on Termux.
OPENCLAW_DIR="$(npm root -g)/openclaw"
if [ -d "$OPENCLAW_DIR" ]; then
    echo "Running OpenClaw postinstall..."
    (cd "$OPENCLAW_DIR" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs 2>/dev/null) || true
fi

# npm exited 0, yet the package could be incomplete (a cut-off install): say so instead of finishing. Looked at
# before the patches are applied: with files missing the patch step would only report that "this OpenClaw
# version does not match the patch", which is not the cause. The patch script of this folder is asked
# directly (a copy under ~/.openclaw-android may be missing or an older one).
OC_HL_CHECK=$(timeout 120 bash "$SCRIPT_DIR/patches/openclaw-patch-hardlink.sh" --check 2>&1 || true)
if printf '%s' "$OC_HL_CHECK" | grep -q 'MISSING' || oa_openclaw_incomplete "$OPENCLAW_DIR" fresh; then
    echo -e "${RED}[FAIL]${NC} OpenClaw $PLATFORM_NPM_PACKAGE_VERSION was installed but is incomplete (files are missing or it does not start)."
    echo "       Run the installer again."
    exit 1
fi

bash "$SCRIPT_DIR/patches/openclaw-apply-patches.sh"

# the install and everything checked after it went through: the mark goes
rm -f "$OC_PENDING_MARK" 2>/dev/null || true

# Block `openclaw update` so the pin holds (npm just rewrote $PREFIX/bin/openclaw)
bash "$SCRIPT_DIR/openclaw-shim.sh"

echo ""
echo "Installing clawdhub (skill manager)..."
if npm install -g clawdhub --no-fund --no-audit; then
    echo -e "${GREEN}[OK]${NC}   clawdhub installed"
    CLAWHUB_DIR="$(npm root -g)/clawdhub"
    if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
        echo "Installing undici dependency for clawdhub..."
        if (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit); then
            echo -e "${GREEN}[OK]${NC}   undici installed for clawdhub"
        else
            echo -e "${YELLOW}[WARN]${NC} undici installation failed (clawdhub may not work)"
        fi
    fi
else
    echo -e "${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
    echo "       Retry manually: npm i -g clawdhub"
fi

mkdir -p "$HOME/.openclaw"

# Turn off the gateway's "update available" notice: the OpenClaw version is pinned
# here, so the notice only points at an update that oa blocks. A value the user
# already set (true or false) is left alone. Same block as update.sh and the end
# of post-setup.sh.
if timeout 60 openclaw config get update.checkOnStart >/dev/null 2>&1; then
    echo -e "${GREEN}[SKIP]${NC} update.checkOnStart is already set"
elif timeout 60 openclaw config set update.checkOnStart false >/dev/null 2>&1; then
    echo -e "${GREEN}[OK]${NC}   OpenClaw update notice turned off (update.checkOnStart=false)"
else
    echo -e "${YELLOW}[WARN]${NC} Could not turn off the OpenClaw update notice (non-critical)"
fi
