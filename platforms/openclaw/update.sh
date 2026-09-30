#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../../scripts/lib.sh"

export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

echo "=== Updating OpenClaw Platform ==="
echo ""

pkg install -y binutils 2>/dev/null || true
if [ ! -e "$PREFIX/bin/ar" ] && [ -x "$PREFIX/bin/llvm-ar" ]; then
    ln -s "$PREFIX/bin/llvm-ar" "$PREFIX/bin/ar"
fi

# Version pin (SSOT: config.env). This script runs as a child process of
# update-core.sh, so it loads the pin itself.
load_platform_config openclaw "$SCRIPT_DIR/../.."
if ! [[ "${PLATFORM_NPM_PACKAGE_VERSION:-}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo -e "${RED}[FAIL]${NC} Invalid OpenClaw version pin in config.env: '${PLATFORM_NPM_PACKAGE_VERSION:-}'"
    exit 1
fi
PIN_VER="$PLATFORM_NPM_PACKAGE_VERSION"

# Node gate (defense in depth): update-core.sh stops before this step when Node
# is not pinned, but a cached older update-core.sh without that gate can run
# this newer script. Never install the pinned OpenClaw on the wrong Node.
NODE_FOUND="$(node --version 2>/dev/null || echo none)"
if [ "$NODE_FOUND" != "v${PLATFORM_NODE_VERSION:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Node.js v${PLATFORM_NODE_VERSION:-?} is required (found: $NODE_FOUND) — OpenClaw was not changed"
    echo "       Run 'oa --update' again."
    exit 1
fi
OPENCLAW_DIR="$(npm root -g)/openclaw"

# Converge to the pinned version: install it whenever the installed version
# differs, in either direction. Read package.json rather than running
# `openclaw --version`, which exits early when the installed release needs a
# newer Node.js than ours.
CURRENT_VER=""
if [ -f "$OPENCLAW_DIR/package.json" ]; then
    CURRENT_VER=$(node -p "require('$OPENCLAW_DIR/package.json').version" 2>/dev/null || echo "")
fi
OPENCLAW_UPDATED=false

if [ "$CURRENT_VER" = "$PIN_VER" ]; then
    echo -e "${GREEN}[OK]${NC}   openclaw $CURRENT_VER matches the pinned version"
else
    echo "Installing pinned openclaw... (${CURRENT_VER:-none} → $PIN_VER)"
    echo "  (This may take several minutes depending on network speed)"
    # A leftover version guard with no package behind it (a plain file, not an
    # npm link) makes npm fail with EEXIST. When the package exists npm replaces
    # the bin itself, so leave the guard alone.
    if [ ! -f "$OPENCLAW_DIR/package.json" ] && [ -e "$PREFIX/bin/openclaw" ] && [ ! -L "$PREFIX/bin/openclaw" ]; then
        rm -f "$PREFIX/bin/openclaw"
    fi
    if npm install -g "$PLATFORM_NPM_PACKAGE@$PIN_VER" --no-fund --no-audit --ignore-scripts; then
        echo -e "${GREEN}[OK]${NC}   openclaw $PIN_VER installed"
        OPENCLAW_UPDATED=true
    else
        # Keep the existing install usable and guarded if npm left it in place
        bash "$SCRIPT_DIR/openclaw-shim.sh" || true
        echo -e "${RED}[FAIL]${NC} Could not install openclaw $PIN_VER"
        echo "       Check your network and run: oa --update"
        exit 1
    fi
fi

# Run the package postinstall that --ignore-scripts skipped (prunes stale dist
# files, applies bundled hotfixes) — only needed after a fresh package install.
# npm_config_ignore_scripts=true keeps any nested npm call from running native
# builds that fail on Termux.
if [ "$OPENCLAW_UPDATED" = true ] && [ -d "$OPENCLAW_DIR" ]; then
    echo "Running OpenClaw postinstall..."
    (cd "$OPENCLAW_DIR" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs 2>/dev/null) || true
fi

bash "$SCRIPT_DIR/patches/openclaw-apply-patches.sh"

# Always re-check the `openclaw update` guard — a manual `npm install -g openclaw`
# or the app's platform installer replaces $PREFIX/bin/openclaw.
bash "$SCRIPT_DIR/openclaw-shim.sh"

if command -v clawdhub &>/dev/null; then
    CLAWDHUB_CURRENT_VER=$(npm list -g clawdhub 2>/dev/null | grep 'clawdhub@' | sed 's/.*clawdhub@//' | tr -d '[:space:]' || true)
    CLAWDHUB_LATEST_VER=$(npm view clawdhub version 2>/dev/null || echo "")
    if [ -n "$CLAWDHUB_CURRENT_VER" ] && [ -n "$CLAWDHUB_LATEST_VER" ] && [ "$CLAWDHUB_CURRENT_VER" = "$CLAWDHUB_LATEST_VER" ]; then
        echo -e "${GREEN}[OK]${NC}   clawdhub $CLAWDHUB_CURRENT_VER is already the latest"
    elif [ -n "$CLAWDHUB_LATEST_VER" ]; then
        echo "Updating clawdhub... ($CLAWDHUB_CURRENT_VER → $CLAWDHUB_LATEST_VER)"
        if npm install -g clawdhub@latest --no-fund --no-audit; then
            echo -e "${GREEN}[OK]${NC}   clawdhub $CLAWDHUB_LATEST_VER updated"
        else
            echo -e "${YELLOW}[WARN]${NC} clawdhub update failed (non-critical)"
        fi
    else
        echo -e "${YELLOW}[WARN]${NC} Could not check clawdhub latest version"
    fi
else
    if ask_yn "clawdhub (skill manager) is not installed. Install it?"; then
        echo "Installing clawdhub..."
        if npm install -g clawdhub --no-fund --no-audit; then
            echo -e "${GREEN}[OK]${NC}   clawdhub installed"
        else
            echo -e "${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
        fi
    else
        echo -e "${YELLOW}[SKIP]${NC} Skipping clawdhub"
    fi
fi

CLAWHUB_DIR="$(npm root -g)/clawdhub"
if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
    echo "Installing undici dependency for clawdhub..."
    if (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit); then
        echo -e "${GREEN}[OK]${NC}   undici installed for clawdhub"
    else
        echo -e "${YELLOW}[WARN]${NC} undici installation failed"
    fi
else
    UNDICI_VER=$(cd "$CLAWHUB_DIR" && node -e "console.log(require('undici/package.json').version)" 2>/dev/null || echo "")
    echo -e "${GREEN}[OK]${NC}   undici ${UNDICI_VER:-available}"
fi

OLD_SKILLS_DIR="$HOME/skills"
CORRECT_SKILLS_DIR="$HOME/.openclaw/workspace/skills"
if [ -d "$OLD_SKILLS_DIR" ] && [ "$(ls -A "$OLD_SKILLS_DIR" 2>/dev/null)" ]; then
    echo ""
    echo "Migrating skills from ~/skills/ to ~/.openclaw/workspace/skills/..."
    mkdir -p "$CORRECT_SKILLS_DIR"
    for skill in "$OLD_SKILLS_DIR"/*/; do
        [ -d "$skill" ] || continue
        skill_name=$(basename "$skill")
        if [ ! -d "$CORRECT_SKILLS_DIR/$skill_name" ]; then
            if mv "$skill" "$CORRECT_SKILLS_DIR/$skill_name" 2>/dev/null; then
                echo -e "  ${GREEN}[OK]${NC}   Migrated $skill_name"
            else
                echo -e "  ${YELLOW}[WARN]${NC} Failed to migrate $skill_name"
            fi
        else
            echo -e "  ${YELLOW}[SKIP]${NC} $skill_name already exists in correct location"
        fi
    done
    if rmdir "$OLD_SKILLS_DIR" 2>/dev/null; then
        echo -e "${GREEN}[OK]${NC}   Removed empty ~/skills/"
    else
        echo -e "${YELLOW}[WARN]${NC} ~/skills/ not empty after migration — check manually"
    fi
fi

python -c "import yaml" 2>/dev/null || pip install pyyaml -q || true
