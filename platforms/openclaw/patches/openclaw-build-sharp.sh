#!/usr/bin/env bash
# build-sharp.sh - Enable sharp image processing on Android (Termux)
#
# Strategy:
#   1. Check if sharp already works → skip
#   2. Install WebAssembly fallback (@img/sharp-wasm32)
#      Native sharp binaries are built for glibc Linux. Android's Bionic libc
#      cannot dlopen glibc-linked .node addons, so the prebuilt linux-arm64
#      binding never loads. The WASM build uses Emscripten and runs entirely
#      in V8 — zero native dependencies.
#   3. If WASM fails → attempt native rebuild as last resort
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo "=== Building sharp (image processing) ==="
echo ""

# Ensure required environment variables are set (for standalone use)
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export CONTAINER="${CONTAINER:-1}"

# Locate openclaw install directory
OPENCLAW_DIR="$(npm root -g)/openclaw"

if [ ! -d "$OPENCLAW_DIR" ]; then
    echo -e "${RED}[FAIL]${NC} OpenClaw directory not found: $OPENCLAW_DIR"
    exit 0
fi

# OpenClaw 2026.7+ does not depend on sharp (images go through photon). Running
# `npm install` inside the global package would pull its devDependencies and
# rewrite its package.json, so only continue when OpenClaw declares sharp.
if ! node -e "const p=require('$OPENCLAW_DIR/package.json');process.exit({...p.dependencies,...p.optionalDependencies}.sharp?0:1)" 2>/dev/null; then
    echo -e "${GREEN}[SKIP]${NC} OpenClaw does not use sharp — nothing to build"
    exit 0
fi

# Skip rebuild if sharp is already working (e.g. WASM installed on prior run)
if [ -d "$OPENCLAW_DIR/node_modules/sharp" ]; then
    if node -e "require('$OPENCLAW_DIR/node_modules/sharp')" 2>/dev/null; then
        echo -e "${GREEN}[OK]${NC}   sharp is already working — skipping rebuild"
        exit 0
    fi
fi

# ── Strategy 1: WebAssembly fallback (recommended for Android) ──────────
# sharp's JS loader tries these paths in order:
#   1. ../src/build/Release/sharp-{platform}.node  (source build)
#   2. ../src/build/Release/sharp-wasm32.node      (source build)
#   3. @img/sharp-{platform}/sharp.node            (prebuilt native)
#   4. @img/sharp-wasm32/sharp.node                (prebuilt WASM) ← this
# By installing @img/sharp-wasm32, path 4 catches the fallback automatically.

echo "Installing sharp WebAssembly runtime..."
if (cd "$OPENCLAW_DIR" && npm install @img/sharp-wasm32 --force --no-audit --no-fund 2>&1 | tail -3); then
    if node -e "require('$OPENCLAW_DIR/node_modules/sharp')" 2>/dev/null; then
        echo ""
        echo -e "${GREEN}[OK]${NC}   sharp enabled via WebAssembly — image processing ready"
        exit 0
    else
        echo -e "${YELLOW}[WARN]${NC} WASM package installed but sharp still not loading"
    fi
else
    echo -e "${YELLOW}[WARN]${NC} Failed to install WASM package"
fi

# ── Strategy 2: Native rebuild (last resort) ────────────────────────────

echo ""
echo "Attempting native rebuild as fallback..."

# Install required packages
echo "Installing build dependencies..."
if ! pkg install -y libvips binutils; then
    echo -e "${YELLOW}[WARN]${NC} Failed to install build dependencies"
    echo "       Image processing will not be available, but OpenClaw will work normally."
    exit 0
fi
echo -e "${GREEN}[OK]${NC}   libvips and binutils installed"

# Create ar symlink if missing (Termux provides llvm-ar but not ar)
if [ ! -e "$PREFIX/bin/ar" ] && [ -x "$PREFIX/bin/llvm-ar" ]; then
    ln -s "$PREFIX/bin/llvm-ar" "$PREFIX/bin/ar"
    echo -e "${GREEN}[OK]${NC}   Created ar → llvm-ar symlink"
fi

# Install node-gyp globally
echo "Installing node-gyp..."
if ! npm install -g node-gyp; then
    echo -e "${YELLOW}[WARN]${NC} Failed to install node-gyp"
    echo "       Image processing will not be available, but OpenClaw will work normally."
    exit 0
fi
echo -e "${GREEN}[OK]${NC}   node-gyp installed"

# Set build environment variables
# On glibc architecture, these are handled by glibc's standard headers.
# On Bionic (legacy), we need explicit compatibility flags.
if [ ! -f "$HOME/.openclaw-android/.glibc-arch" ]; then
    export CFLAGS="-Wno-error=implicit-function-declaration"
    export CXXFLAGS="-include $HOME/.openclaw-android/patches/termux-compat.h"
    export GYP_DEFINES="OS=linux android_ndk_path=$PREFIX"
fi
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"

echo "Rebuilding sharp in $OPENCLAW_DIR..."
echo "This may take several minutes..."
echo ""

if (cd "$OPENCLAW_DIR" && npm rebuild sharp); then
    echo ""
    echo -e "${GREEN}[OK]${NC}   sharp built successfully — image processing enabled"
else
    echo ""
    echo -e "${YELLOW}[WARN]${NC} sharp could not be enabled (non-critical)"
    echo "       Image processing will not be available, but OpenClaw will work normally."
    echo "       You can retry later: bash ~/.openclaw-android/scripts/build-sharp.sh"
fi
