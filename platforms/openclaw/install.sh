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

python -c "import yaml" 2>/dev/null || pip install pyyaml -q || true

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

bash "$SCRIPT_DIR/patches/openclaw-apply-patches.sh"

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
