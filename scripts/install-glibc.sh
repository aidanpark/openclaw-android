#!/usr/bin/env bash
# install-glibc.sh - Install glibc-runner (L2 conditional)
# Extracted from install-glibc-env.sh — glibc runtime only, no Node.js.
# Called by orchestrator when config.env PLATFORM_NEEDS_GLIBC=true.
#
# What it does:
#   1. Install pacman package
#   2. Initialize pacman and install glibc-runner
#   3. Verify glibc dynamic linker
#   4. Create marker file
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

OPENCLAW_DIR="$HOME/.openclaw-android"
GLIBC_LDSO="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
PACMAN_CONF="$PREFIX/etc/pacman.conf"

echo "=== Installing glibc Runtime ==="
echo ""

# ── Pre-checks ───────────────────────────────

if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    exit 1
fi

ARCH=$(uname -m)
if [ "$ARCH" != "aarch64" ]; then
    echo -e "${RED}[FAIL]${NC} glibc environment requires aarch64 (got: $ARCH)"
    exit 1
fi

# ── Install supplementary glibc libraries (always runs) ──
# glibc-runner provides core libraries but not all libraries that
# third-party binaries may need (e.g., libcap.so.2 for codex-acp).
# This runs on both fresh install and update to ensure libraries are current.

GLIBC_LIB_DIR="$PREFIX/glibc/lib"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GLIBC_LIBS_SRC="$SCRIPT_DIR/../patches/glibc-libs"

if [ -d "$GLIBC_LIB_DIR" ] && [ -d "$GLIBC_LIBS_SRC" ]; then
    for lib in "$GLIBC_LIBS_SRC"/*.so.*; do
        [ -f "$lib" ] || continue
        filename=$(basename "$lib")
        soname=$(echo "$filename" | sed -E 's/^(lib[^.]+\.so\.[0-9]+)\..*/\1/')
        if [ ! -f "$GLIBC_LIB_DIR/$filename" ]; then
            cp "$lib" "$GLIBC_LIB_DIR/$filename"
            [ "$soname" != "$filename" ] && ln -sf "$filename" "$GLIBC_LIB_DIR/$soname"
            echo -e "${GREEN}[OK]${NC}   Installed $soname"
        fi
    done
fi

# ── Ensure glibc /etc/hosts exists (always runs) ──
# glibc's getaddrinfo reads $PREFIX/glibc/etc/hosts for localhost resolution.
# Neither glibc nor glibc-runner packages include this file; it comes from
# resolv-conf (via openssl-glibc) which may not be installed.
# Without it, dns.lookup('localhost') can return 0.0.0.0 → gateway bind failure.

GLIBC_ETC="$PREFIX/glibc/etc"
if [ -d "$GLIBC_ETC" ] && [ ! -f "$GLIBC_ETC/hosts" ]; then
    cat > "$GLIBC_ETC/hosts" <<'HOSTS'
127.0.0.1 localhost localhost.localdomain
::1 localhost ip6-localhost ip6-loopback
HOSTS
    echo -e "${GREEN}[OK]${NC}   Created glibc /etc/hosts"
fi

# An older installer set SigLevel = Never in pacman.conf and kept the original as
# pacman.conf.bak; if that run was interrupted the relaxed file stayed behind.
# This runs before the 'already installed' exit below, so a machine that already has the marker is repaired too.
# The file as it is now is kept first (it may hold edits made since), then the original is put back.
if [ -f "${PACMAN_CONF}.bak" ] && grep -q "^SigLevel = Never" "$PACMAN_CONF" 2>/dev/null; then
    relaxed="${PACMAN_CONF}.oa-relaxed"
    [ -e "$relaxed" ] && relaxed="${relaxed}.$(date +%Y%m%d%H%M%S)"
    cp -f "$PACMAN_CONF" "$relaxed"
    mv -f "${PACMAN_CONF}.bak" "$PACMAN_CONF"
    echo -e "${YELLOW}[INFO]${NC} Restored pacman.conf that an older installer had left with signature checks off"
    echo "       (your previous file is kept as $relaxed)"
fi

# Check if already installed
if [ -f "$OPENCLAW_DIR/.glibc-arch" ] && [ -x "$GLIBC_LDSO" ]; then
    echo -e "${GREEN}[SKIP]${NC} glibc-runner already installed"
    exit 0
fi

# ── Step 1: Install pacman ────────────────────

echo "Installing pacman..."
if ! pkg install -y pacman; then
    echo -e "${RED}[FAIL]${NC} Failed to install pacman"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   pacman installed"

# ── Step 2: Initialize the pacman keyring ─────
#
# Signatures are always checked. The keyring has to be ready BEFORE anything is
# installed from the pacman repo, so a failure here stops the install (it used to
# be ignored, back when signature checking was switched off).

echo ""
echo "Initializing pacman keyring..."
echo "  (This may take a few minutes for GPG key generation)"

# Key generation can stall on a device with little entropy: bound it when `timeout` exists.
run_limited() {
    local secs="$1"
    shift
    if command -v timeout >/dev/null 2>&1; then
        # --foreground keeps Ctrl-C working (plain timeout moves the command to its own process group)
        if timeout --foreground 1 true >/dev/null 2>&1; then
            timeout --foreground "$secs" "$@"
        else
            timeout "$secs" "$@"
        fi
    else
        "$@"
    fi
}

init_rc=0
run_limited 900 pacman-key --init || init_rc=$?
if [ "$init_rc" -ne 0 ]; then
    if [ "$init_rc" -eq 124 ]; then
        echo -e "${RED}[FAIL]${NC} Initializing the pacman keyring took longer than 15 minutes and was stopped."
    else
        echo -e "${RED}[FAIL]${NC} Could not initialize the pacman keyring (pacman-key --init failed, exit code $init_rc)."
    fi
    echo "       Package signatures are checked with this keyring, so the install was stopped for safety."
    echo "       Use the device for a minute (key generation can stall on a quiet device), then run the install again."
    exit 1
fi
if ! pacman-key --populate; then
    echo -e "${RED}[FAIL]${NC} Could not load the Termux signing keys (pacman-key --populate failed)."
    echo "       The install was stopped for safety. Run 'pkg reinstall pacman' and try again, or report this."
    exit 1
fi

# ── Step 3: Install glibc-runner ──────────────

echo ""
echo "Installing glibc-runner..."

# Always verify signatures, whatever pacman.conf says: install from a copy whose
# SigLevel lines are forced to the value the Termux pacman package ships by default
# (the repository database must carry a valid signature; its checksums then vouch
# for the packages), so a relaxed file (an older installer's, or the user's) cannot
# switch verification off. The install itself never edits the real file (only the
# leftover repair above does).
VERIFY_CONF="$PREFIX/tmp/oa-pacman.conf"
PACMAN_LOG="$PREFIX/tmp/oa-pacman.log"
mkdir -p "$PREFIX/tmp"
trap 'rm -f "$VERIFY_CONF" "$PACMAN_LOG"' EXIT
if [ ! -f "$PACMAN_CONF" ] || ! sed -E 's/^[[:space:]]*SigLevel[[:space:]]*=.*/SigLevel = DatabaseRequired PackageOptional/' "$PACMAN_CONF" > "$VERIFY_CONF"; then
    echo -e "${RED}[FAIL]${NC} Could not prepare the pacman configuration ($PACMAN_CONF)."
    echo "       Run 'pkg reinstall pacman' and try again."
    exit 1
fi

# --assume-installed: these packages are provided by Termux's apt but pacman
# doesn't know about them, causing dependency resolution failures
if pacman --config "$VERIFY_CONF" -Sy glibc-runner --noconfirm --assume-installed bash,patchelf,resolv-conf 2>&1 | tee "$PACMAN_LOG"; then
    echo -e "${GREEN}[OK]${NC}   glibc-runner installed"
else
    echo -e "${RED}[FAIL]${NC} Failed to install glibc-runner"
    if grep -qi 'GPGME' "$PACMAN_LOG" 2>/dev/null; then
        echo "       pacman's signature check reported an error (GPGME). This can be a bad or empty signature download, or a problem on this device."
        echo "       The install was stopped for safety. Try again on another network; if it keeps failing, please report this with the lines above."
    elif grep -qiE 'signature|PGP|unknown trust|corrupted|not signed|keyring' "$PACMAN_LOG" 2>/dev/null; then
        echo "       The package failed its signature check. Something may be tampering with the download, or the keyring is out of date."
        echo "       The install was stopped for safety. Check your network and the device's date and time, then run the install again."
    elif grep -qiE 'retrieving file|resolve host|timed out|connection|download' "$PACMAN_LOG" 2>/dev/null; then
        echo "       The download failed. Check your network connection and run the install again."
    else
        echo "       See the pacman messages above, then run the install again."
    fi
    exit 1
fi

# ── Verify ────────────────────────────────────

if [ ! -x "$GLIBC_LDSO" ]; then
    echo -e "${RED}[FAIL]${NC} glibc dynamic linker not found at $GLIBC_LDSO"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   glibc dynamic linker available"

if command -v grun &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   grun command available"
else
    echo -e "${YELLOW}[WARN]${NC} grun command not found (will use ld.so directly)"
fi

# ── Create marker file ────────────────────────

mkdir -p "$OPENCLAW_DIR"
touch "$OPENCLAW_DIR/.glibc-arch"
echo -e "${GREEN}[OK]${NC}   glibc architecture marker created"

echo ""
echo -e "${GREEN}glibc runtime installed successfully.${NC}"
echo "  ld.so: $GLIBC_LDSO"
