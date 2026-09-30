#!/usr/bin/env bash
# install-nodejs.sh - Install Node.js linux-arm64 with grun wrapper (L2 conditional)
# Extracted from install-glibc-env.sh — Node.js only, assumes glibc already installed.
# Called by orchestrator when config.env PLATFORM_NEEDS_NODEJS=true.
#
# Usage: install-nodejs.sh <version>
#   <version> is the pinned Node.js version (X.Y.Z). Callers pass
#   PLATFORM_NODE_VERSION from platforms/<platform>/config.env (the SSOT);
#   this script has no default of its own.
#
# What it does:
#   1. Converge to the pinned version (upgrade or downgrade; same version = repair wrappers only)
#   2. Download Node.js linux-arm64 and verify its sha256 against SHASUMS256.txt from the same source
#   3. Extract into a staging dir, test-run it, then swap it in (previous install restored on failure)
#   4. Create grun-style wrapper scripts (ld.so direct execution)
#   5. Configure npm and verify
#
# patchelf is NOT used — Android seccomp causes SIGSEGV on patchelf'd binaries.
# All glibc binaries are executed via: exec ld.so binary "$@"
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

OPENCLAW_DIR="$HOME/.openclaw-android"
NODE_DIR="$OPENCLAW_DIR/node"
NODE_NEW="$OPENCLAW_DIR/node.new"
NODE_OLD="$OPENCLAW_DIR/node.old"
NODE_TRASH="$OPENCLAW_DIR/node.trash"
BIN_DIR="$OPENCLAW_DIR/bin"
GLIBC_LDSO="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"

# Pinned Node.js version (from caller)
NODE_VERSION="${1:-}"
if [ -z "$NODE_VERSION" ]; then
    # Only an older updater calls this without a version (e.g. a cached
    # update-core.sh served right after a release).
    echo -e "${RED}[FAIL]${NC} The updater is out of date (a cached copy was downloaded)."
    echo "       Run 'oa --update' again in a few minutes."
    exit 1
fi
if [[ ! "$NODE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo -e "${RED}[FAIL]${NC} Invalid Node.js version pin: '${NODE_VERSION}'"
    echo "       The downloaded release may be incomplete. Run 'oa --update' again."
    exit 1
fi
NODE_DIST_BASE="https://nodejs.org/dist/v${NODE_VERSION}"
NODE_TARBALL="node-v${NODE_VERSION}-linux-arm64.tar.xz"

echo "=== Installing Node.js (glibc) ==="
echo ""

# ── Pre-checks ───────────────────────────────

if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    exit 1
fi

if [ ! -x "$GLIBC_LDSO" ]; then
    echo -e "${RED}[FAIL]${NC} glibc dynamic linker not found — run install-glibc.sh first"
    exit 1
fi

# ── Wrapper scripts ───────────────────────────
#
# Wrappers are placed in BIN_DIR (~/.openclaw-android/bin/), separate from
# NODE_DIR/bin/ which npm manages. This prevents npm from overwriting our
# wrappers when users run 'npm install -g npm' or similar commands.
# Wrapper content does not depend on the Node.js version. Each wrapper is written
# to a .tmp file and moved into place, so a failed write never leaves it empty.

# Node wrapper: grun-style execution — ld.so directly loads the binary.
# LD_PRELOAD must be unset to prevent Bionic libtermux-exec.so from
# being loaded into the glibc process (causes version mismatch crash)
# glibc-compat.js is auto-loaded to fix Android kernel quirks (os.cpus() returns 0,
# os.networkInterfaces() throws EACCES) that affect native module builds and runtime.
write_node_wrapper() {
    cat > "$BIN_DIR/node.tmp" << WRAPPER
#!${PREFIX}/bin/bash
[ -n "\$LD_PRELOAD" ] && export _OA_ORIG_LD_PRELOAD="\$LD_PRELOAD"
unset LD_PRELOAD
export _OA_WRAPPER_PATH="$BIN_DIR/node"
_OA_COMPAT="\$HOME/.openclaw-android/patches/glibc-compat.js"
if [ -f "\$_OA_COMPAT" ]; then
    case "\${NODE_OPTIONS:-}" in
        *"\$_OA_COMPAT"*) ;;
        *) export NODE_OPTIONS="\${NODE_OPTIONS:+\$NODE_OPTIONS }-r \$_OA_COMPAT" ;;
    esac
fi
_LEADING_OPTS=""
_COUNT=0
for _arg in "\$@"; do
    case "\$_arg" in --*) _COUNT=\$((_COUNT + 1)) ;; *) break ;; esac
done
if [ \$_COUNT -gt 0 ] && [ \$_COUNT -lt \$# ]; then
    while [ \$# -gt 0 ]; do
        case "\$1" in
            --*) _LEADING_OPTS="\${_LEADING_OPTS:+\$_LEADING_OPTS }\$1"; shift ;;
            *) break ;;
        esac
    done
    export NODE_OPTIONS="\${NODE_OPTIONS:+\$NODE_OPTIONS }\$_LEADING_OPTS"
fi
exec "$GLIBC_LDSO" --library-path "$PREFIX/glibc/lib" "$NODE_DIR/bin/node.real" "\$@"
WRAPPER
    chmod +x "$BIN_DIR/node.tmp"
    mv -f "$BIN_DIR/node.tmp" "$BIN_DIR/node"
}

# npm/npx wrappers + corepack shebang. Sets _WROTE_NPM=true if anything was
# written. Call it as a plain command (not in if/||) so set -e stops the script
# on a failed write instead of moving an empty file into place.
write_npm_wrappers() {
    _WROTE_NPM=false
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
        cat > "$BIN_DIR/npm.tmp" << 'NPMWRAP'
#!__PREFIX__/bin/bash
"__BIN_DIR__/node" "__NODE_DIR__/lib/node_modules/npm/bin/npm-cli.js" "$@"
_npm_exit=$?
# Re-patch openclaw CLI wrapper after a global install/update of openclaw.
# The platform's version-pin guard generator writes it when present.
case "$*" in *-g*openclaw*|*--global*openclaw*|*openclaw*-g*|*openclaw*--global*)
    _oc_write=false
    for _oc_arg in "$@"; do
        case "$_oc_arg" in install|i|in|ins|inst|insta|instal|isnt|isnta|isntal|isntall|add|update|up|upgrade|udpate|ci|clean-install|link|ln) _oc_write=true ;; esac
    done
    _oc_bin="__PREFIX__/bin/openclaw"
    _oc_mjs="__PREFIX__/lib/node_modules/openclaw/openclaw.mjs"
    _oc_shim="$HOME/.openclaw-android/platforms/openclaw/openclaw-shim.sh"
    if [ "$_oc_write" = true ] && [ -f "$_oc_mjs" ]; then
        if [ -f "$_oc_shim" ] && "__PREFIX__/bin/bash" "$_oc_shim" >/dev/null 2>&1; then
            :
        else
            [ -L "$_oc_bin" ] && rm -f "$_oc_bin"
            printf '#!__PREFIX__/bin/bash\nexec "__BIN_DIR__/node" "%s" "$@"\n' "$_oc_mjs" > "$_oc_bin"
            chmod +x "$_oc_bin"
        fi
    fi
    ;;
esac
# Re-patch codex CLI wrapper after global install/update (DioNanos fork launcher fix)
case "$*" in *codex-cli-termux*)
    _codex_bin="__PREFIX__/bin/codex"
    _codex_pkg="__PREFIX__/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
    if [ -f "$_codex_pkg/codex.bin" ]; then
        [ -L "$_codex_bin" ] && rm -f "$_codex_bin"
        printf '#!__PREFIX__/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="$PKG_BIN:${LD_LIBRARY_PATH:-}"\nexec "$PKG_BIN/codex.bin" "$@"\n' "$_codex_pkg" > "$_codex_bin"
        chmod +x "$_codex_bin"
    fi
    ;;
esac
# Fix shebangs in npm global CLI entry points after global install
case "$*" in *-g*|*--global*)
    for _js in __PREFIX__/lib/node_modules/*/bin/*.js \
               __PREFIX__/lib/node_modules/@*/*/bin/*.js; do
        [ -f "$_js" ] || continue
        head -1 "$_js" | grep -q '^#!/usr/bin/env node$' || continue
        sed -i "1s|#!/usr/bin/env node|#!__BIN_DIR__/node|" "$_js"
    done
    ;;
esac
exit $_npm_exit
NPMWRAP
        sed -i "s|__PREFIX__|$PREFIX|g; s|__BIN_DIR__|$BIN_DIR|g; s|__NODE_DIR__|$NODE_DIR|g" "$BIN_DIR/npm.tmp"
        chmod +x "$BIN_DIR/npm.tmp"
        mv -f "$BIN_DIR/npm.tmp" "$BIN_DIR/npm"
        _WROTE_NPM=true
    fi
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" ]; then
        cat > "$BIN_DIR/npx.tmp" << 'NPXWRAP'
#!__PREFIX__/bin/bash
exec "__BIN_DIR__/node" "__NODE_DIR__/lib/node_modules/npm/bin/npx-cli.js" "$@"
NPXWRAP
        sed -i "s|__PREFIX__|$PREFIX|g; s|__BIN_DIR__|$BIN_DIR|g; s|__NODE_DIR__|$NODE_DIR|g" "$BIN_DIR/npx.tmp"
        chmod +x "$BIN_DIR/npx.tmp"
        mv -f "$BIN_DIR/npx.tmp" "$BIN_DIR/npx"
        _WROTE_NPM=true
    fi
    # corepack uses a different structure — shebang patch is sufficient
    if [ -f "$NODE_DIR/bin/corepack" ] && head -1 "$NODE_DIR/bin/corepack" 2>/dev/null | grep -q '#!/usr/bin/env node'; then
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$NODE_DIR/bin/corepack"
        _WROTE_NPM=true
    fi
}

# ── Recover an unfinished swap ────────────────
# NODE_OLD exists only until the new install is verified (on success it is
# renamed to NODE_TRASH before deletion). If it is still here, a previous run
# stopped mid-swap — go back to the last verified install.

if [ -d "$NODE_OLD" ]; then
    rm -rf "${NODE_DIR:?}"
    mv "$NODE_OLD" "$NODE_DIR"
    echo -e "${YELLOW}[FIX]${NC}  Restored Node.js from an unfinished update"
fi
rm -rf "${NODE_NEW:?}" "${NODE_TRASH:?}"
rm -f "$BIN_DIR/node.tmp" "$BIN_DIR/npm.tmp" "$BIN_DIR/npx.tmp"

# ── Check installed version ───────────────────
# Check BIN_DIR wrapper first, fall back to NODE_DIR (pre-v1.0.16 layout)

_NODE_CMD=""
if [ -x "$BIN_DIR/node" ]; then
    _NODE_CMD="$BIN_DIR/node"
elif [ -x "$NODE_DIR/bin/node" ]; then
    _NODE_CMD="$NODE_DIR/bin/node"
fi
INSTALLED_VER=""
if [ -n "$_NODE_CMD" ] && "$_NODE_CMD" --version &>/dev/null; then
    INSTALLED_VER=$("$_NODE_CMD" --version 2>/dev/null | sed 's/^v//')
fi

if [ "$INSTALLED_VER" = "$NODE_VERSION" ]; then
    echo -e "${GREEN}[SKIP]${NC} Node.js already installed (v${INSTALLED_VER})"
    # Repair wrappers — ensure they exist in BIN_DIR (npm-safe location)
    mkdir -p "$BIN_DIR"
    _any_fixed=false
    # Ensure node wrapper exists in BIN_DIR (may be missing for pre-v1.0.16 installs)
    if [ ! -x "$BIN_DIR/node" ]; then
        # Ensure node.real exists
        if [ -f "$NODE_DIR/bin/node" ] && [ ! -L "$NODE_DIR/bin/node" ] && file "$NODE_DIR/bin/node" 2>/dev/null | grep -q ELF; then
            mv "$NODE_DIR/bin/node" "$NODE_DIR/bin/node.real"
        fi
        if [ -f "$NODE_DIR/bin/node.real" ]; then
            write_node_wrapper
            _any_fixed=true
        fi
    fi
    write_npm_wrappers
    if [ "$_WROTE_NPM" = true ]; then
        _any_fixed=true
    fi
    if [ "$_any_fixed" = true ]; then
        echo -e "${YELLOW}[FIX]${NC}  wrappers repaired in $BIN_DIR"
    fi
    exit 0
elif [ -n "$INSTALLED_VER" ]; then
    echo -e "${YELLOW}[INFO]${NC} Node.js v${INSTALLED_VER} -> v${NODE_VERSION} (pinned version)"
elif [ -n "$_NODE_CMD" ]; then
    echo -e "${YELLOW}[INFO]${NC} Node.js exists but broken — reinstalling"
fi

# ── Step 1: Download and verify Node.js linux-arm64 ──

echo "Downloading Node.js v${NODE_VERSION} (linux-arm64)..."
echo "  (File size ~25MB — may take a few minutes depending on network speed)"

mkdir -p "$PREFIX/tmp"
TMP_DIR=$(mktemp -d "$PREFIX/tmp/node-install.XXXXXX") || {
    echo -e "${RED}[FAIL]${NC} Failed to create temp directory"
    exit 1
}

# On any exit: drop temp files and a half-built staging dir. If the new install
# was swapped in but not verified (any failure after the swap), put the
# previous install back.
_SWAPPED=false
_VERIFIED=false
cleanup() {
    rm -rf "${TMP_DIR:?}" "${NODE_NEW:?}"
    rm -f "$BIN_DIR/node.tmp" "$BIN_DIR/npm.tmp" "$BIN_DIR/npx.tmp"
    if [ "$_SWAPPED" = true ] && [ "$_VERIFIED" = false ] && [ -d "$NODE_OLD" ]; then
        rm -rf "${NODE_DIR:?}"
    fi
    if [ ! -d "$NODE_DIR" ] && [ -d "$NODE_OLD" ]; then
        mv "$NODE_OLD" "$NODE_DIR"
        echo -e "${YELLOW}[WARN]${NC} Restored previous Node.js installation"
    fi
}
trap cleanup EXIT

if ! curl -fL --max-time 300 "$NODE_DIST_BASE/$NODE_TARBALL" -o "$TMP_DIR/$NODE_TARBALL"; then
    echo -e "${RED}[FAIL]${NC} Failed to download Node.js v${NODE_VERSION}"
    echo "       Check your network connection and try again."
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   Downloaded $NODE_TARBALL"

# Checksum list comes from the same place as the tarball
if ! curl -fsSL --max-time 60 "$NODE_DIST_BASE/SHASUMS256.txt" -o "$TMP_DIR/SHASUMS256.txt"; then
    echo -e "${RED}[FAIL]${NC} Failed to download SHASUMS256.txt for Node.js v${NODE_VERSION}"
    exit 1
fi
EXPECTED_SHA=$(awk -v f="$NODE_TARBALL" '$2 == f { print $1; exit }' "$TMP_DIR/SHASUMS256.txt")
ACTUAL_SHA=$(sha256sum "$TMP_DIR/$NODE_TARBALL" | awk '{ print $1 }')
if [ -z "$EXPECTED_SHA" ] || [ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]; then
    echo -e "${RED}[FAIL]${NC} Checksum mismatch for $NODE_TARBALL"
    echo "       expected: ${EXPECTED_SHA:-<not listed>}"
    echo "       actual:   $ACTUAL_SHA"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   sha256 verified"

# ── Step 2: Extract into staging and test-run ──

echo "Extracting Node.js... (this may take a moment)"
mkdir -p "$NODE_NEW"
if ! tar -xJf "$TMP_DIR/$NODE_TARBALL" -C "$NODE_NEW" --strip-components=1; then
    echo -e "${RED}[FAIL]${NC} Failed to extract Node.js"
    exit 1
fi
rm -f "$TMP_DIR/$NODE_TARBALL"

# Move original node binary to node.real (the wrapper execs node.real)
mv "$NODE_NEW/bin/node" "$NODE_NEW/bin/node.real"

STAGED_VER=$(env -u LD_PRELOAD -u NODE_OPTIONS \
    "$GLIBC_LDSO" --library-path "$PREFIX/glibc/lib" "$NODE_NEW/bin/node.real" --version 2>/dev/null) || STAGED_VER=""
if [ "$STAGED_VER" != "v$NODE_VERSION" ]; then
    echo -e "${RED}[FAIL]${NC} Extracted Node.js does not run (got: '${STAGED_VER}')"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   Node.js $STAGED_VER runs"

# Carry over what users added to the previous install (globals installed
# without PREFIX, corepack shims such as pnpm/yarn). npm and corepack
# themselves come from the new tarball so no stale files are left behind.
if [ -d "$NODE_DIR" ]; then
    for _entry in "$NODE_DIR/bin"/* "$NODE_DIR/lib/node_modules"/*; do
        [ -e "$_entry" ] || [ -L "$_entry" ] || continue
        _name=$(basename "$_entry")
        case "$_name" in node|node.real|npm|npx|corepack) continue ;; esac
        _dest="$NODE_NEW/${_entry#"$NODE_DIR"/}"
        if [ ! -e "$_dest" ] && [ ! -L "$_dest" ]; then
            cp -a "$_entry" "$_dest"
        fi
    done
fi

# ── Step 3: Swap into place ───────────────────

if [ -d "$NODE_DIR" ]; then
    mv "$NODE_DIR" "$NODE_OLD"
fi
mv "$NODE_NEW" "$NODE_DIR"
_SWAPPED=true
echo -e "${GREEN}[OK]${NC}   Installed to $NODE_DIR"

# ── Step 4: Create wrapper scripts ────────────

echo ""
echo "Creating wrapper scripts (grun-style, no patchelf)..."
mkdir -p "$BIN_DIR"
write_node_wrapper
echo -e "${GREEN}[OK]${NC}   node wrapper created ($BIN_DIR/node)"
write_npm_wrappers
[ -x "$BIN_DIR/npm" ] && echo -e "${GREEN}[OK]${NC}   npm wrapper created ($BIN_DIR/npm)"
[ -x "$BIN_DIR/npx" ] && echo -e "${GREEN}[OK]${NC}   npx wrapper created ($BIN_DIR/npx)"

# ── Step 5: Verify ────────────────────────────

echo ""
echo "Verifying glibc Node.js..."

NODE_VER=$("$BIN_DIR/node" --version 2>/dev/null) || NODE_VER=""
NPM_VER=$("$BIN_DIR/npm" --version 2>/dev/null) || NPM_VER=""
if [ "$NODE_VER" != "v$NODE_VERSION" ] || [ -z "$NPM_VER" ]; then
    echo -e "${RED}[FAIL]${NC} Node.js verification failed (node: '${NODE_VER}', npm: '${NPM_VER}') — wrapper script may be broken"
    exit 1
fi
_VERIFIED=true
if [ -d "$NODE_OLD" ]; then
    mv "$NODE_OLD" "$NODE_TRASH"
    rm -rf "${NODE_TRASH:?}"
fi
echo -e "${GREEN}[OK]${NC}   Node.js $NODE_VER (glibc, grun wrapper)"
echo -e "${GREEN}[OK]${NC}   npm $NPM_VER"

# ── Step 6: Configure npm ─────────────────────

echo ""
echo "Configuring npm..."

# Set script-shell to ensure npm lifecycle scripts use the correct shell
# On Android 9+, /bin/sh exists. On 7-8 it doesn't.
# Using $PREFIX/bin/sh is always safe.
export PATH="$BIN_DIR:$NODE_DIR/bin:$PATH"
"$BIN_DIR/npm" config set script-shell "$PREFIX/bin/sh" 2>/dev/null || true
echo -e "${GREEN}[OK]${NC}   npm script-shell set to $PREFIX/bin/sh"

# Quick platform check
PLATFORM=$("$BIN_DIR/node" -e "console.log(process.platform)" 2>/dev/null) || true
if [ "$PLATFORM" = "linux" ]; then
    echo -e "${GREEN}[OK]${NC}   platform: linux (correct)"
else
    echo -e "${YELLOW}[WARN]${NC} platform: ${PLATFORM:-unknown} (expected: linux)"
fi

echo ""
echo -e "${GREEN}Node.js installed successfully.${NC}"
echo "  Node.js: $NODE_VER ($BIN_DIR/node)"
