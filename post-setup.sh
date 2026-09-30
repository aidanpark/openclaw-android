#!/usr/bin/env bash
# OpenClaw Android — Post-Bootstrap Setup
# Runs in the terminal after Termux bootstrap extraction.
# Installs: git, glibc, Node.js, OpenClaw
#
# Strategy:
#   - Termux .deb packages: dpkg-deb -x + relocate (bypasses dpkg hardcoded paths)
#   - Pacman .pkg.tar.xz packages: tar -xJf + relocate (bypasses pacman entirely)
#   - Both have files under data/data/com.termux/files/usr/ which we relocate to $PREFIX
#
# Why not apt-get/dpkg/pacman?
#   All three have hardcoded /data/data/com.termux/... paths that libtermux-exec
#   cannot rewrite (it only intercepts execve, not open/opendir).

set -eo pipefail

# ─── Paths ────────────────────────────────────
: "${PREFIX:?PREFIX not set}"
: "${HOME:?HOME not set}"
: "${TMPDIR:=$(dirname "$PREFIX")/tmp}"

OCA_DIR="$HOME/.openclaw-android"
NODE_DIR="$OCA_DIR/node"
BIN_DIR="$OCA_DIR/bin"

# ─── Version pin (pair) ───────────────────────
# Same keys and values as platforms/openclaw/config.env (the SSOT). This script
# runs as a single file and does not read config.env; .githooks/pre-commit
# checks that these lines match it. Bump both files together.
PLATFORM_NPM_PACKAGE_VERSION="2026.7.35"
PLATFORM_NODE_VERSION="22.23.3"
NODE_VERSION="$PLATFORM_NODE_VERSION"

GLIBC_LDSO="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
MARKER="$OCA_DIR/.post-setup-done"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ─── GitHub mirror fallback (for China/restricted networks) ──
REPO_BASE_ORIGIN="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
REPO_BASE="$REPO_BASE_ORIGIN"
resolve_repo_base() {
    if curl -sI --connect-timeout 3 "$REPO_BASE_ORIGIN/oa.sh" >/dev/null 2>&1; then
        REPO_BASE="$REPO_BASE_ORIGIN"; return 0
    fi
    local mirrors=(
        "https://ghfast.top/$REPO_BASE_ORIGIN"
        "https://ghproxy.net/$REPO_BASE_ORIGIN"
        "https://mirror.ghproxy.com/$REPO_BASE_ORIGIN"
    )
    for m in "${mirrors[@]}"; do
        if curl -sI --connect-timeout 3 "$m/oa.sh" >/dev/null 2>&1; then
            echo -e "  ${YELLOW}[MIRROR]${NC} Using mirror for GitHub downloads"
            REPO_BASE="$m"; return 0
        fi
    done
    return 1
}

# Kept in sync with scripts/lib.sh resolve_npm_registry()
NPM_REGISTRY_ORIGIN="https://registry.npmjs.org/"
NPM_REGISTRY_MIRROR="https://registry.npmmirror.com/"
resolve_npm_registry() {
    local choice
    local cache_file="$OCA_DIR/.npm-registry"
    local reachable=0
    if curl -sI --connect-timeout 5 "$NPM_REGISTRY_ORIGIN" >/dev/null 2>&1; then
        choice="$NPM_REGISTRY_ORIGIN"
        reachable=1
    elif curl -sI --connect-timeout 5 "$NPM_REGISTRY_MIRROR" >/dev/null 2>&1; then
        echo -e "  ${YELLOW}[MIRROR]${NC} Using npm mirror: ${NPM_REGISTRY_MIRROR}"
        choice="$NPM_REGISTRY_MIRROR"
        reachable=1
    else
        choice="$NPM_REGISTRY_ORIGIN"
    fi
    mkdir -p "$(dirname "$cache_file")"
    printf '%s' "$choice" > "$cache_file.tmp" && mv "$cache_file.tmp" "$cache_file"
    export NPM_CONFIG_REGISTRY="$choice"
    if [ "$reachable" -eq 1 ]; then
        return 0
    fi
    return 1
}

# SSL cert for curl (bootstrap curl looks at hardcoded com.termux path)
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"

# Git system config has hardcoded com.termux path — skip it
export GIT_CONFIG_NOSYSTEM=1

# Git exec path (git looks for helpers like git-remote-https here)
export GIT_EXEC_PATH="$PREFIX/libexec/git-core"

# Git template dir (hardcoded /data/data/com.termux path workaround)
export GIT_TEMPLATE_DIR="$PREFIX/share/git-core/templates"

if [ -f "$MARKER" ]; then
    echo -e "${GREEN}Post-setup already completed.${NC}"
    exit 0
fi

echo ""
echo "══════════════════════════════════════════════"
echo "  OpenClaw Android — Installing components"
echo "══════════════════════════════════════════════"
echo ""

mkdir -p "$OCA_DIR" "$OCA_DIR/patches" "$TMPDIR"

TERMUX_DEB_REPO="https://packages-cf.termux.dev/apt/termux-main"
# termux-pacman primary + officially recognized mirrors (https://termux-pacman.dev/mirrors/).
# service. is listed last: it currently redirects to sync.
PACMAN_MIRRORS=(
    "https://sync.termux-pacman.dev/gpkg/aarch64"
    "https://ftp.agdsn.de/termux-pacman/gpkg/aarch64"
    "https://mirror.clarkson.edu/termux-pacman/gpkg/aarch64"
    "https://service.termux-pacman.dev/gpkg/aarch64"
)
GPKG_DB="$TMPDIR/gpkg.db"
TERMUX_INNER="data/data/com.termux/files/usr"
DEB_DIR="$TMPDIR/debs"
PKG_DIR="$TMPDIR/pkgs"
EXTRACT_DIR="$TMPDIR/pkg-extract"

# ─── Helper: install_deb ──────────────────────
# Downloads a .deb from Termux repo and extracts into $PREFIX
install_deb() {
    local filename="$1"
    local name
    name=$(basename "$filename" | sed 's/_[0-9].*//')
    local url="${TERMUX_DEB_REPO}/${filename}"
    local deb_file
    deb_file="${DEB_DIR}/$(basename "$filename")"

    if [ -f "$deb_file" ]; then
        echo "    (cached) $name"
    else
        echo "    downloading $name..."
        # Return the failure explicitly: callers inside `if` run without errexit.
        # Drop a partial download so the next run does not reuse it as "cached".
        if ! curl -fsSL --max-time 120 -o "$deb_file" "$url"; then
            rm -f "$deb_file"
            return 1
        fi
    fi

    rm -rf "$EXTRACT_DIR"
    mkdir -p "$EXTRACT_DIR"
    if ! dpkg-deb -x "$deb_file" "$EXTRACT_DIR" 2>/dev/null; then
        rm -f "$deb_file"
        rm -rf "$EXTRACT_DIR"
        return 1
    fi

    # Relocate: data/data/com.termux/files/usr/* → $PREFIX/
    if [ -d "$EXTRACT_DIR/$TERMUX_INNER" ]; then
        cp -a "$EXTRACT_DIR/$TERMUX_INNER/"* "$PREFIX/" 2>/dev/null || true
    fi
    rm -rf "$EXTRACT_DIR"
}

# ─── Helpers: pacman packages (glibc) ─────────
# The pacman repo is rolling: package file names change (glibc-2.42-0 is gone),
# so they are read from the repo database (gpkg.db), never hardcoded. The
# database lists each package's file name and sha256.

# Print "FILENAME SHA256" for package $1 from $GPKG_DB (fails unless both look valid)
gpkg_lookup() {
    local pkg="$1"
    local entry
    entry=$(tar -tzf "$GPKG_DB" 2>/dev/null | grep -E "^${pkg}-[0-9][^/]*/desc$" | head -1) || true
    [ -n "$entry" ] || return 1
    tar -xzOf "$GPKG_DB" "$entry" 2>/dev/null | awk '
        /^%FILENAME%$/  { getline; f = $0 }
        /^%SHA256SUM%$/ { getline; s = $0 }
        END {
            if (f ~ /^[A-Za-z0-9._+-]+\.pkg\.tar\.xz$/ && length(s) == 64 && s ~ /^[0-9a-f]+$/) print f, s
            else exit 1
        }'
}

# Download package file $2 from mirror $1 into $PKG_DIR and check it against
# sha256 $3. A cached copy is reused only if it matches.
fetch_pacman_pkg() {
    local mirror="$1"
    local filename="$2"
    local sha="$3"
    local pkg_file="${PKG_DIR}/${filename}"

    if [ -f "$pkg_file" ] && [ "$(sha256sum "$pkg_file" | awk '{ print $1 }')" = "$sha" ]; then
        echo "    (cached) $filename"
        return 0
    fi
    echo "    downloading $filename..."
    rm -f "$pkg_file"
    if ! curl -fsSL --max-time 300 -o "$pkg_file" "${mirror}/${filename}"; then
        rm -f "$pkg_file"
        return 1
    fi
    if [ "$(sha256sum "$pkg_file" | awk '{ print $1 }')" != "$sha" ]; then
        echo -e "    ${YELLOW}[WARN]${NC} sha256 mismatch: $filename"
        rm -f "$pkg_file"
        return 1
    fi
}

# Extract a downloaded .pkg.tar.xz from $PKG_DIR into target dir
install_pacman_pkg() {
    local filename="$1"
    local target="$2"  # e.g., $PREFIX/glibc
    local pkg_file="${PKG_DIR}/${filename}"

    rm -rf "$EXTRACT_DIR"
    mkdir -p "$EXTRACT_DIR"
    tar -xJf "$pkg_file" -C "$EXTRACT_DIR" 2>/dev/null

    # Pacman packages also extract under data/data/com.termux/files/usr/...
    local inner="$EXTRACT_DIR/$TERMUX_INNER"
    if [ -d "$inner/glibc" ]; then
        # glibc packages go under $PREFIX/glibc/
        cp -a "$inner/glibc/"* "$target/" 2>/dev/null || true
    elif [ -d "$inner" ]; then
        cp -a "$inner/"* "$target/" 2>/dev/null || true
    fi
    rm -rf "$EXTRACT_DIR"
}

# ─── [1/7] Install essential packages ─────────
echo -e "▸ ${YELLOW}[1/7]${NC} Installing essential packages..."
mkdir -p "$DEB_DIR" "$PKG_DIR"

# Download Packages index to resolve .deb filenames
echo "  Fetching package index..."
PACKAGES_FILE="$TMPDIR/Packages"
curl -fsSL --max-time 60 \
    "${TERMUX_DEB_REPO}/dists/stable/main/binary-aarch64/Packages" \
    -o "$PACKAGES_FILE"

# Resolve package filename from Packages index
get_deb_filename() {
    local pkg="$1"
    awk -v pkg="$pkg" '
        /^Package: / { found = ($2 == pkg) }
        found && /^Filename:/ { print $2; exit }
    ' "$PACKAGES_FILE"
}

# Packages to install via dpkg-deb (dependency order, only those missing from bootstrap)
DEB_PACKAGES=(
    libexpat          # git dep
    pcre2             # git dep
    git               # for npm/openclaw
)

TOTAL=${#DEB_PACKAGES[@]}
COUNT=0
for pkg in "${DEB_PACKAGES[@]}"; do
    COUNT=$((COUNT + 1))
    filename=$(get_deb_filename "$pkg")
    if [ -z "$filename" ]; then
        echo -e "  ${RED}✗${NC} Package '$pkg' not found in index"
        continue
    fi
    echo "  [$COUNT/$TOTAL] $pkg"
    install_deb "$filename"
done

# Make sure newly extracted binaries are executable
chmod +x "$PREFIX/bin/"* 2>/dev/null || true

# Verify git
if [ -f "$PREFIX/bin/git" ]; then
    echo -e "  ${GREEN}✓${NC} git $(git --version 2>/dev/null | head -1)"
else
    echo -e "  ${RED}✗${NC} git not found after extraction"
    exit 1
fi

# ─── [2/7] glibc runtime ─────────────────────
echo -e "▸ ${YELLOW}[2/7]${NC} Installing glibc runtime..."

if [ -x "$GLIBC_LDSO" ]; then
    echo -e "  ${GREEN}[SKIP]${NC} glibc already installed"
else
    mkdir -p "$PREFIX/glibc"

    # Download glibc packages directly from the pacman repo (no pacman needed).
    # gcc-libs-glibc provides libstdc++.so.6 needed by Node.js.
    # Mirrors are tried in turn; database and packages come from the same mirror.
    GLIBC_PKGS=(glibc gcc-libs-glibc)
    GLIBC_FILES=()
    echo "  Downloading glibc + gcc-libs (~34MB)..."
    for _mirror in "${PACMAN_MIRRORS[@]}"; do
        GLIBC_FILES=()
        echo "  Mirror: ${_mirror%/gpkg/aarch64}"
        if ! curl -fsSL --max-time 60 -o "$GPKG_DB" "$_mirror/gpkg.db"; then
            echo -e "  ${YELLOW}[WARN]${NC} Package database not reachable"
            continue
        fi
        for _pkg in "${GLIBC_PKGS[@]}"; do
            if ! _info=$(gpkg_lookup "$_pkg"); then
                echo -e "  ${YELLOW}[WARN]${NC} $_pkg: no valid entry in package database"
                break
            fi
            read -r _file _sha <<< "$_info"
            fetch_pacman_pkg "$_mirror" "$_file" "$_sha" || break
            GLIBC_FILES+=("$_file")
        done
        [ "${#GLIBC_FILES[@]}" -eq "${#GLIBC_PKGS[@]}" ] && break
    done
    if [ "${#GLIBC_FILES[@]}" -ne "${#GLIBC_PKGS[@]}" ]; then
        echo -e "  ${RED}✗${NC} Could not download glibc from any mirror:"
        printf '      %s\n' "${PACMAN_MIRRORS[@]}"
        echo "    Check your network connection and restart the app to retry."
        exit 1
    fi
    for _file in "${GLIBC_FILES[@]}"; do
        install_pacman_pkg "$_file" "$PREFIX/glibc"
    done

    # Verify linker
    if [ ! -f "$GLIBC_LDSO" ]; then
        echo -e "  ${RED}✗${NC} glibc linker not found at $GLIBC_LDSO"
        exit 1
    fi
    chmod +x "$GLIBC_LDSO"
    mkdir -p "$OCA_DIR"
    touch "$OCA_DIR/.glibc-arch"
    echo -e "  ${GREEN}✓${NC} glibc installed"
fi

# Install supplementary glibc libraries (libcap etc.)
_GLIBC_LIBS_SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/patches/glibc-libs"
if [ -d "$PREFIX/glibc/lib" ] && [ -d "$_GLIBC_LIBS_SRC" ]; then
    for _lib in "$_GLIBC_LIBS_SRC"/*.so.*; do
        [ -f "$_lib" ] || continue
        _fn=$(basename "$_lib")
        if [ ! -f "$PREFIX/glibc/lib/$_fn" ]; then
            cp "$_lib" "$PREFIX/glibc/lib/$_fn"
            _sn=$(echo "$_fn" | sed -E 's/^(lib[^.]+\.so\.[0-9]+)\..*/\1/')
            [ "$_sn" != "$_fn" ] && ln -sf "$_fn" "$PREFIX/glibc/lib/$_sn"
            echo -e "  ${GREEN}✓${NC} Installed $_sn"
        fi
    done
fi

# Ensure glibc /etc/hosts exists (localhost resolution)
if [ -d "$PREFIX/glibc/etc" ] && [ ! -f "$PREFIX/glibc/etc/hosts" ]; then
    cat > "$PREFIX/glibc/etc/hosts" <<'HOSTS'
127.0.0.1 localhost localhost.localdomain
::1 localhost ip6-localhost ip6-loopback
HOSTS
    echo -e "  ${GREEN}✓${NC} Created glibc /etc/hosts"
fi
echo -e "  Linker: $GLIBC_LDSO"

# ─── [3/7] Node.js ──────────────────────────
echo -e "▸ ${YELLOW}[3/7]${NC} Installing Node.js v${NODE_VERSION}..."
NODE_NEW="$OCA_DIR/node.new"
NODE_OLD="$OCA_DIR/node.old"
NODE_TRASH="$OCA_DIR/node.trash"

# Recover an unfinished swap: node.old exists only until the new install is
# verified, so if it is still here the previous run stopped mid-swap — go back to it.
if [ -d "$NODE_OLD" ]; then
    rm -rf "${NODE_DIR:?}"
    mv "$NODE_OLD" "$NODE_DIR"
fi
rm -rf "${NODE_NEW:?}" "${NODE_TRASH:?}"

_NODE_CMD=""
if [ -x "$BIN_DIR/node" ]; then _NODE_CMD="$BIN_DIR/node"
elif [ -f "$NODE_DIR/bin/node.real" ] && [ -x "$NODE_DIR/bin/node" ]; then _NODE_CMD="$NODE_DIR/bin/node"
fi
INSTALLED_VER=""
if [ -n "$_NODE_CMD" ] && "$_NODE_CMD" --version &>/dev/null; then
    INSTALLED_VER=$("$_NODE_CMD" --version 2>/dev/null | sed 's/^v//')
fi
# Converge to the pinned version: reinstall unless exactly the pinned version
if [ "$INSTALLED_VER" = "$NODE_VERSION" ]; then
    echo -e "  ${GREEN}[SKIP]${NC} Node.js already installed (v$INSTALLED_VER)"
    # Repair wrappers in BIN_DIR (safe from npm overwrites)
    mkdir -p "$BIN_DIR"
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
        cat > "$BIN_DIR/npm.tmp" << NPMWRAP
#!$PREFIX/bin/bash
"$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" "\$@"
_npm_exit=\$?
# Re-patch openclaw CLI wrapper after a global install/update of openclaw.
# The platform's version-pin guard generator writes it when present.
case "\$*" in *-g*openclaw*|*--global*openclaw*|*openclaw*-g*|*openclaw*--global*)
    _oc_write=false
    for _oc_arg in "\$@"; do
        case "\$_oc_arg" in install|i|in|ins|inst|insta|instal|isnt|isnta|isntal|isntall|add|update|up|upgrade|udpate|ci|clean-install|link|ln) _oc_write=true ;; esac
    done
    _oc_bin="$PREFIX/bin/openclaw"
    _oc_mjs="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
    _oc_shim="\$HOME/.openclaw-android/platforms/openclaw/openclaw-shim.sh"
    if [ "\$_oc_write" = true ] && [ -f "\$_oc_mjs" ]; then
        if [ -f "\$_oc_shim" ] && "$PREFIX/bin/bash" "\$_oc_shim" >/dev/null 2>&1; then
            :
        else
            [ -L "\$_oc_bin" ] && rm -f "\$_oc_bin"
            printf '#!$PREFIX/bin/bash\nexec "$BIN_DIR/node" "%s" "\$@"\n' "\$_oc_mjs" > "\$_oc_bin"
            chmod +x "\$_oc_bin"
        fi
    fi
    ;;
esac
# Re-patch codex CLI wrapper after global install/update (DioNanos fork launcher fix)
case "\$*" in *codex-cli-termux*)
    _codex_bin="$PREFIX/bin/codex"
    _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
    if [ -f "\$_codex_pkg/codex.bin" ]; then
        [ -L "\$_codex_bin" ] && rm -f "\$_codex_bin"
        printf '#!$PREFIX/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="\$PKG_BIN:\${LD_LIBRARY_PATH:-}"\nexec "\$PKG_BIN/codex.bin" "\$@"\n' "\$_codex_pkg" > "\$_codex_bin"
        chmod +x "\$_codex_bin"
    fi
    ;;
esac
# Fix shebangs in npm global CLI entry points after global install
case "\$*" in *-g*|*--global*)
    for _js in $PREFIX/lib/node_modules/*/bin/*.js \
               $PREFIX/lib/node_modules/@*/*/bin/*.js; do
        [ -f "\$_js" ] || continue
        head -1 "\$_js" | grep -q '^#!/usr/bin/env node\$' || continue
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "\$_js"
    done
    ;;
esac
exit \$_npm_exit
NPMWRAP
        chmod +x "$BIN_DIR/npm.tmp"
        mv -f "$BIN_DIR/npm.tmp" "$BIN_DIR/npm"
    fi
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" ]; then
        cat > "$BIN_DIR/npx.tmp" << NPXWRAP
#!$PREFIX/bin/bash
exec "$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" "\$@"
NPXWRAP
        chmod +x "$BIN_DIR/npx.tmp"
        mv -f "$BIN_DIR/npx.tmp" "$BIN_DIR/npx"
    fi
    if [ -f "$NODE_DIR/bin/corepack" ] && head -1 "$NODE_DIR/bin/corepack" 2>/dev/null | grep -q '#!/usr/bin/env node'; then
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$NODE_DIR/bin/corepack"
    fi
else
    if [ -n "$INSTALLED_VER" ]; then
        echo "  Node.js v${INSTALLED_VER} -> v${NODE_VERSION} (pinned version)"
    fi
    NODE_DIST_BASE="https://nodejs.org/dist/v${NODE_VERSION}"
    NODE_TAR="node-v${NODE_VERSION}-linux-arm64"
    echo "  Downloading Node.js v${NODE_VERSION} (~25MB)..."
    if ! curl -fSL --max-time 300 \
        "${NODE_DIST_BASE}/${NODE_TAR}.tar.xz" \
        -o "$TMPDIR/${NODE_TAR}.tar.xz"; then
        rm -f "$TMPDIR/${NODE_TAR}.tar.xz"
        echo -e "  ${RED}✗${NC} Node.js download failed — check the network and restart the app to retry"
        exit 1
    fi

    # Verify sha256 against SHASUMS256.txt from the same source
    if ! curl -fsSL --max-time 60 "${NODE_DIST_BASE}/SHASUMS256.txt" -o "$TMPDIR/node-SHASUMS256.txt"; then
        rm -f "$TMPDIR/${NODE_TAR}.tar.xz" "$TMPDIR/node-SHASUMS256.txt"
        echo -e "  ${RED}✗${NC} Node.js checksum list download failed — check the network and restart the app to retry"
        exit 1
    fi
    _expected=$(awk -v f="${NODE_TAR}.tar.xz" '$2 == f { print $1; exit }' "$TMPDIR/node-SHASUMS256.txt")
    _actual=$(sha256sum "$TMPDIR/${NODE_TAR}.tar.xz" | awk '{ print $1 }')
    rm -f "$TMPDIR/node-SHASUMS256.txt"
    if [ -z "$_expected" ] || [ "$_expected" != "$_actual" ]; then
        rm -f "$TMPDIR/${NODE_TAR}.tar.xz"
        echo -e "  ${RED}✗${NC} Node.js checksum mismatch — restart the app to retry"
        exit 1
    fi

    # Extract into staging and test-run before touching the current install
    echo "  Extracting..."
    mkdir -p "$NODE_NEW"
    tar -xJf "$TMPDIR/${NODE_TAR}.tar.xz" -C "$NODE_NEW" --strip-components=1
    rm -f "$TMPDIR/${NODE_TAR}.tar.xz"

    # Move original binary → node.real
    mv "$NODE_NEW/bin/node" "$NODE_NEW/bin/node.real"

    _staged=$(env -u LD_PRELOAD -u NODE_OPTIONS \
        "$GLIBC_LDSO" --library-path "$PREFIX/glibc/lib" "$NODE_NEW/bin/node.real" --version 2>/dev/null) || _staged=""
    if [ "$_staged" != "v$NODE_VERSION" ]; then
        rm -rf "${NODE_NEW:?}"
        echo -e "  ${RED}✗${NC} Extracted Node.js does not run (got: '${_staged}')"
        exit 1
    fi

    # Carry over user additions from a previous install (kept in sync with
    # scripts/install-nodejs.sh) — npm/corepack come fresh from the tarball
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
        mv "$NODE_DIR" "$NODE_OLD"
    fi
    mv "$NODE_NEW" "$NODE_DIR"

    # Create grun-style node wrapper in BIN_DIR (safe from npm overwrites)
    mkdir -p "$BIN_DIR"
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

    # Create npm/npx wrappers in BIN_DIR
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
        cat > "$BIN_DIR/npm.tmp" << NPMWRAP
#!$PREFIX/bin/bash
"$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" "\$@"
_npm_exit=\$?
# Re-patch openclaw CLI wrapper after a global install/update of openclaw.
# The platform's version-pin guard generator writes it when present.
case "\$*" in *-g*openclaw*|*--global*openclaw*|*openclaw*-g*|*openclaw*--global*)
    _oc_write=false
    for _oc_arg in "\$@"; do
        case "\$_oc_arg" in install|i|in|ins|inst|insta|instal|isnt|isnta|isntal|isntall|add|update|up|upgrade|udpate|ci|clean-install|link|ln) _oc_write=true ;; esac
    done
    _oc_bin="$PREFIX/bin/openclaw"
    _oc_mjs="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
    _oc_shim="\$HOME/.openclaw-android/platforms/openclaw/openclaw-shim.sh"
    if [ "\$_oc_write" = true ] && [ -f "\$_oc_mjs" ]; then
        if [ -f "\$_oc_shim" ] && "$PREFIX/bin/bash" "\$_oc_shim" >/dev/null 2>&1; then
            :
        else
            [ -L "\$_oc_bin" ] && rm -f "\$_oc_bin"
            printf '#!$PREFIX/bin/bash\nexec "$BIN_DIR/node" "%s" "\$@"\n' "\$_oc_mjs" > "\$_oc_bin"
            chmod +x "\$_oc_bin"
        fi
    fi
    ;;
esac
# Re-patch codex CLI wrapper after global install/update (DioNanos fork launcher fix)
case "\$*" in *codex-cli-termux*)
    _codex_bin="$PREFIX/bin/codex"
    _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
    if [ -f "\$_codex_pkg/codex.bin" ]; then
        [ -L "\$_codex_bin" ] && rm -f "\$_codex_bin"
        printf '#!$PREFIX/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="\$PKG_BIN:\${LD_LIBRARY_PATH:-}"\nexec "\$PKG_BIN/codex.bin" "\$@"\n' "\$_codex_pkg" > "\$_codex_bin"
        chmod +x "\$_codex_bin"
    fi
    ;;
esac
# Fix shebangs in npm global CLI entry points after global install
case "\$*" in *-g*|*--global*)
    for _js in $PREFIX/lib/node_modules/*/bin/*.js \
               $PREFIX/lib/node_modules/@*/*/bin/*.js; do
        [ -f "\$_js" ] || continue
        head -1 "\$_js" | grep -q '^#!/usr/bin/env node\$' || continue
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "\$_js"
    done
    ;;
esac
exit \$_npm_exit
NPMWRAP
        chmod +x "$BIN_DIR/npm.tmp"
        mv -f "$BIN_DIR/npm.tmp" "$BIN_DIR/npm"
    fi
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" ]; then
        cat > "$BIN_DIR/npx.tmp" << NPXWRAP
#!$PREFIX/bin/bash
exec "$BIN_DIR/node" "$NODE_DIR/lib/node_modules/npm/bin/npx-cli.js" "\$@"
NPXWRAP
        chmod +x "$BIN_DIR/npx.tmp"
        mv -f "$BIN_DIR/npx.tmp" "$BIN_DIR/npx"
    fi
    # corepack: shebang patch only
    if [ -f "$NODE_DIR/bin/corepack" ] && head -1 "$NODE_DIR/bin/corepack" 2>/dev/null | grep -q '#!/usr/bin/env node'; then
        sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$NODE_DIR/bin/corepack"
    fi

    # Configure npm
    export PATH="$BIN_DIR:$NODE_DIR/bin:$PATH"
    "$BIN_DIR/npm" config set script-shell "$PREFIX/bin/sh" 2>/dev/null || true

    # Verify (restore the previous install if the new one does not run)
    NODE_VER=$("$BIN_DIR/node" --version 2>/dev/null) || NODE_VER=""
    NPM_VER=$("$BIN_DIR/npm" --version 2>/dev/null) || NPM_VER=""
    if [ "$NODE_VER" != "v$NODE_VERSION" ] || [ -z "$NPM_VER" ]; then
        rm -rf "${NODE_DIR:?}"
        if [ -d "$NODE_OLD" ]; then
            mv "$NODE_OLD" "$NODE_DIR"
        fi
        echo -e "  ${RED}✗${NC} Node.js verification failed (node: '${NODE_VER}', npm: '${NPM_VER}')"
        exit 1
    fi
    if [ -d "$NODE_OLD" ]; then
        mv "$NODE_OLD" "$NODE_TRASH"
        rm -rf "${NODE_TRASH:?}"
    fi
    echo -e "  ${GREEN}✓${NC} Node.js $NODE_VER (glibc)"
fi

# ─── [4/7] OpenClaw ─────────────────────────
echo -e "▸ ${YELLOW}[4/7]${NC} Installing OpenClaw..."
export PATH="$BIN_DIR:$NODE_DIR/bin:$PATH"

# Auto-detect GitHub mirror for restricted networks
resolve_repo_base

# Auto-detect npm registry (session-scoped via NPM_CONFIG_REGISTRY env var).
# Does NOT write to ~/.npmrc — see CHANGELOG v1.0.24.
resolve_npm_registry || true

# Force git to use HTTPS instead of SSH (no SSH client available).
# Preserve any existing user .gitconfig (name, email, aliases); only set our keys.
touch "$HOME/.gitconfig"
git config --global http.sslCAInfo "$PREFIX/etc/tls/cert.pem"
git config --global --unset-all url."https://github.com/".insteadOf 2>/dev/null || true
git config --global --add url."https://github.com/".insteadOf "ssh://git@github.com/"
git config --global --add url."https://github.com/".insteadOf "git@github.com:"

# Git wrapper: replace $PREFIX/bin/git with a wrapper that:
#   1. Strips --recurse-submodules (triggers open() on hardcoded com.termux path)
#   2. Cleans existing target dirs before clone (npm's withTempDir creates dir first)
# npm caches git path at module load via which.sync('git'), so we must replace the binary.
# $PREFIX/bin/git is a symlink -> ../libexec/git-core/git (the real ELF binary).
REAL_GIT="$PREFIX/libexec/git-core/git"
if [ -f "$REAL_GIT" ] && [ ! -f "$PREFIX/bin/git.wrapper-installed" ]; then
    echo "  Installing git wrapper (strips --recurse-submodules)..."
    rm -f "$PREFIX/bin/git"
    # Write shebang with absolute path (no LD_PRELOAD = no /bin/bash rewrite)
    echo "#!${PREFIX}/bin/bash" > "$PREFIX/bin/git"
    cat >> "$PREFIX/bin/git" << 'ENDWRAP'
filtered=()
is_clone=false
for a in "$@"; do
  case "$a" in
    --recurse-submodules) ;;
    clone) is_clone=true; filtered+=("$a") ;;
    *) filtered+=("$a") ;;
  esac
done
if $is_clone; then
  for a in "${filtered[@]}"; do
    case "$a" in
      clone|--*|-*|http*|ssh*|git*|[0-9]) ;;
      *) [ -d "$a" ] && rm -rf "$a" ;;
    esac
  done
fi
ENDWRAP
    echo "exec \"$REAL_GIT\" \"\${filtered[@]}\"" >> "$PREFIX/bin/git"
    chmod +x "$PREFIX/bin/git"
    touch "$PREFIX/bin/git.wrapper-installed"
    echo -e "  ${GREEN}\u2713${NC} git wrapper installed"
else
    if [ -f "$PREFIX/bin/git.wrapper-installed" ]; then
        echo -e "  ${GREEN}[SKIP]${NC} git wrapper already installed"
    else
        echo -e "  ${RED}\u2717${NC} Real git not found at $REAL_GIT"
        exit 1
    fi
fi

# `openclaw update` guard generator — byte-identical copy of
# platforms/openclaw/openclaw-shim.sh (this script is a single file). Saved where
# the npm wrapper looks for it, so any later `npm install -g openclaw` re-guards.
OC_SHIM_GEN="$OCA_DIR/platforms/openclaw/openclaw-shim.sh"
mkdir -p "$(dirname "$OC_SHIM_GEN")"
cat > "$OC_SHIM_GEN" << 'OPENCLAW_SHIM_SH'
#!/usr/bin/env bash
# openclaw-shim.sh — Write the version-pin guard at $PREFIX/bin/openclaw
#
# OpenClaw can replace itself (`openclaw update`, `openclaw --update`) with the
# latest npm release, which may require a newer Node.js than the pinned one and
# then refuses to start. The guard blocks those commands and passes everything
# else through to openclaw.mjs.
#
# `npm install -g openclaw` (and our npm wrapper) rewrite $PREFIX/bin/openclaw,
# so run this after every OpenClaw install (install.sh, update.sh, post-setup.sh).
# $PREFIX/bin is the one directory present in every PATH we launch from
# (Termux shell, app terminal, boot auto-start, app EnvironmentBuilder).
set -euo pipefail

: "${PREFIX:?PREFIX not set}"
: "${HOME:?HOME not set}"

OC_BIN="$PREFIX/bin/openclaw"
OC_MJS="$PREFIX/lib/node_modules/openclaw/openclaw.mjs"
OC_NODE="$HOME/.openclaw-android/bin/node"
[ -x "$OC_NODE" ] || OC_NODE="node"

if [ ! -f "$OC_MJS" ]; then
    echo -e "\033[1;33m[WARN]\033[0m openclaw.mjs not found — version guard not installed"
    exit 0
fi

OC_TMP="$OC_BIN.tmp.$$"
trap 'rm -f "$OC_TMP"' EXIT
cat > "$OC_TMP" << SHIM
#!$PREFIX/bin/bash
# openclaw — OpenClaw on Android version-pin guard.
# Generated by platforms/openclaw/openclaw-shim.sh; rewritten on every install/update.
# Blocks OpenClaw self-update; all other commands pass through unchanged.
_oa_block=false
for _oa_arg in "\$@"; do
    [ "\$_oa_arg" = "--update" ] && _oa_block=true && break
done
if [ "\$_oa_block" = false ]; then
    # Find the subcommand: skip global flags (and the values of those that take one).
    _oa_skip=false
    _oa_next=false
    for _oa_arg in "\$@"; do
        if [ "\$_oa_next" = true ]; then
            # Only the read-only "update status" is allowed through.
            [ "\$_oa_arg" = "status" ] && _oa_block=false
            break
        fi
        if [ "\$_oa_skip" = true ]; then _oa_skip=false; continue; fi
        case "\$_oa_arg" in
            --profile|--container|--log-level) _oa_skip=true ;;
            -*) ;;
            update) _oa_block=true; _oa_next=true ;;
            *) break ;;
        esac
    done
fi
if [ "\$_oa_block" = true ]; then
    echo "[BLOCKED] OpenClaw is pinned to the version verified by OpenClaw on Android." >&2
    echo "          Run 'oa --update' to update safely. ('openclaw update status' is allowed.)" >&2
    exit 1
fi
if [ ! -f "$OC_MJS" ]; then
    echo "[FAIL] OpenClaw is not installed ($OC_MJS missing). Run 'oa --update'." >&2
    exit 127
fi
exec "$OC_NODE" "$OC_MJS" "\$@"
SHIM
chmod +x "$OC_TMP"
# rename(2) replaces the npm symlink itself, never the file it points to
mv -f "$OC_TMP" "$OC_BIN"
echo -e "\033[0;32m[OK]\033[0m   openclaw version guard installed ($OC_BIN)"
OPENCLAW_SHIM_SH
chmod +x "$OC_SHIM_GEN"

# Converge to the pinned OpenClaw (same rule as platforms/openclaw/update.sh):
# install it whenever the installed version differs, in either direction.
OPENCLAW_DIR="$(npm root -g)/openclaw"
OC_CURRENT=""
if [ -f "$OPENCLAW_DIR/package.json" ]; then
    OC_CURRENT=$(node -p "require('$OPENCLAW_DIR/package.json').version" 2>/dev/null || echo "")
fi
OC_INSTALLED=false
if [ "$OC_CURRENT" = "$PLATFORM_NPM_PACKAGE_VERSION" ]; then
    echo -e "  ${GREEN}[SKIP]${NC} OpenClaw $OC_CURRENT already installed (pinned version)"
else
    # Clean npm cache tmp dir (leftover from previous failed installs)
    rm -rf "$HOME/.npm/_cacache/tmp" 2>/dev/null || true
    # A leftover guard with no package behind it makes npm fail with EEXIST
    if [ ! -f "$OPENCLAW_DIR/package.json" ] && [ -e "$PREFIX/bin/openclaw" ] && [ ! -L "$PREFIX/bin/openclaw" ]; then
        rm -f "$PREFIX/bin/openclaw"
    fi
    npm install -g "openclaw@$PLATFORM_NPM_PACKAGE_VERSION" --ignore-scripts 2>&1
    OC_INSTALLED=true
    echo -e "  ${GREEN}✓${NC} OpenClaw $PLATFORM_NPM_PACKAGE_VERSION (${OC_CURRENT:-new install})"
fi

# Run the package postinstall that --ignore-scripts skipped (prunes stale dist
# files, applies bundled hotfixes) — only after a fresh package install.
if [ "$OC_INSTALLED" = true ] && [ -d "$OPENCLAW_DIR" ]; then
    echo "  Running OpenClaw postinstall..."
    (cd "$OPENCLAW_DIR" && npm_config_ignore_scripts=true node scripts/postinstall-bundled-plugins.mjs 2>/dev/null) || true
fi

# Block `openclaw update` so the pin holds (after the npm wrappers from [3/7])
bash "$OC_SHIM_GEN" | sed 's/^/  /'

# Install clawdhub (skill manager)
echo "  Installing clawdhub..."
if npm install -g clawdhub --no-fund --no-audit; then
    echo -e "  ${GREEN}✓${NC} clawdhub installed"
    CLAWHUB_DIR="$(npm root -g)/clawdhub"
    if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
        echo "  Installing undici dependency for clawdhub..."
        (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit) || true
    fi
else
    echo -e "  ${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
fi

# PyYAML (for .skill packaging)
command -v python &>/dev/null && { python -c "import yaml" 2>/dev/null || pip install pyyaml -q || true; }

# ─── [5/7] Patches ──────────────────────────
echo -e "▸ ${YELLOW}[5/7]${NC} Applying patches..."

# Copy glibc-compat.js from project (bundled alongside this script)
COMPAT_SRC="$(dirname "$0")/glibc-compat.js"
if [ -f "$COMPAT_SRC" ]; then
    cp "$COMPAT_SRC" "$OCA_DIR/patches/glibc-compat.js"
else
    # Fallback: download from repo
    curl -fsSL "$REPO_BASE/patches/glibc-compat.js" \
        -o "$OCA_DIR/patches/glibc-compat.js" 2>/dev/null || true
fi

# systemctl stub
printf '#!%s/bin/bash\nexit 0\n' "$PREFIX" > "$PREFIX/bin/systemctl"
chmod +x "$PREFIX/bin/systemctl"

echo -e "  ${GREEN}✓${NC} Patches applied"

# ─── [6/7] Environment ──────────────────────
echo -e "▸ ${YELLOW}[6/7]${NC} Configuring environment..."

cat > "$HOME/.bashrc" << BASHRC
# OpenClaw Android environment
export PREFIX="$PREFIX"
export HOME="$HOME"
export TMPDIR="$TMPDIR"
export PATH="$BIN_DIR:$NODE_DIR/bin:\$PREFIX/bin:\$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"
export TERMUX__PREFIX="$PREFIX"
export TERMUX_PREFIX="$PREFIX"
export LANG=en_US.UTF-8
export TERM=xterm-256color
export OA_GLIBC=1
export CONTAINER=1
# Block the gateway auto-updater even if update.auto.enabled is set (version pin)
export OPENCLAW_NO_AUTO_UPDATE=1
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"
export GIT_CONFIG_NOSYSTEM=1
export GIT_EXEC_PATH="$PREFIX/libexec/git-core"
export GIT_TEMPLATE_DIR="$PREFIX/share/git-core/templates"
export CLAWDHUB_WORKDIR="$HOME/.openclaw/workspace"
export CPATH="$PREFIX/include/glib-2.0:$PREFIX/lib/glib-2.0/include"
# npm registry (auto-detected by OpenClaw Android, safe to override manually)
if [ -z "\${NPM_CONFIG_REGISTRY:-}" ] && [ -s "\$HOME/.openclaw-android/.npm-registry" ]; then
    export NPM_CONFIG_REGISTRY="\$(cat "\$HOME/.openclaw-android/.npm-registry")"
fi
BASHRC

echo -e "  ${GREEN}✓${NC} ~/.bashrc configured"

# oa CLI (enables oa --update, oa --backup, etc.)
if curl -fsSL "$REPO_BASE/oa.sh" \
        -o "$PREFIX/bin/oa" 2>/dev/null; then
    chmod +x "$PREFIX/bin/oa"
    echo -e "  ${GREEN}✓${NC} oa CLI installed"
else
    echo -e "  ${YELLOW}[WARN]${NC} oa CLI installation failed (non-critical)"
fi

# ─── [7/7] Optional Tools ──────────────────
TOOL_CONF="$OCA_DIR/tool-selections.conf"
if [ -f "$TOOL_CONF" ]; then
    # shellcheck source=/dev/null
    source "$TOOL_CONF"

    HAS_TOOLS=false
    for var in INSTALL_TMUX INSTALL_TTYD INSTALL_DUFS INSTALL_CODE_SERVER INSTALL_PLAYWRIGHT INSTALL_CLAUDE_CODE INSTALL_GEMINI_CLI INSTALL_CODEX_CLI; do
        eval "val=\${$var:-false}"
        # shellcheck disable=SC2154
        [ "$val" = "true" ] && HAS_TOOLS=true && break
    done

    if $HAS_TOOLS; then
        echo -e "▸ ${YELLOW}[7/7]${NC} Installing optional tools..."

        # Helper: install .deb with direct dependencies
        install_with_deps() {
            local pkg="$1"
            local deps
            deps=$(awk -v pkg="$pkg" '
                /^Package: / { found = ($2 == pkg) }
                found && /^Depends:/ {
                    gsub(/^Depends: /, "")
                    gsub(/ *\([^)]*\)/, "")
                    gsub(/, /, "\n")
                    print; exit
                }
            ' "$PACKAGES_FILE")
            while IFS= read -r dep; do
                dep=$(echo "$dep" | tr -d ' ')
                [ -z "$dep" ] && continue
                local dep_file
                dep_file=$(get_deb_filename "$dep")
                if [ -n "$dep_file" ]; then install_deb "$dep_file" 2>/dev/null || true; fi
            done <<< "$deps"
            local filename
            filename=$(get_deb_filename "$pkg")
            [ -n "$filename" ] || return 1
            install_deb "$filename"
        }

        # Termux packages
        [ "${INSTALL_TMUX:-false}" = "true" ] && {
            echo "  Installing tmux..."
            if install_with_deps tmux; then
                echo -e "  ${GREEN}✓${NC} tmux"
            else
                echo -e "  ${YELLOW}[WARN]${NC} tmux installation failed (non-critical) — skipped"
            fi
        }
        [ "${INSTALL_TTYD:-false}" = "true" ] && {
            echo "  Installing ttyd..."
            if install_with_deps ttyd; then
                echo -e "  ${GREEN}✓${NC} ttyd"
            else
                echo -e "  ${YELLOW}[WARN]${NC} ttyd installation failed (non-critical) — skipped"
            fi
        }
        [ "${INSTALL_DUFS:-false}" = "true" ] && {
            echo "  Installing dufs..."
            if install_with_deps dufs; then
                echo -e "  ${GREEN}✓${NC} dufs"
            else
                echo -e "  ${YELLOW}[WARN]${NC} dufs installation failed (non-critical) — skipped"
            fi
        }

        # npm packages
        [ "${INSTALL_CODE_SERVER:-false}" = "true" ] && {
            echo "  Installing code-server (this may take a while)..."
            # Pinned: 4.133.0+ require Node.js 24. Same version as scripts/install-code-server.sh.
            if npm install -g code-server@4.117.0 2>&1; then
                echo -e "  ${GREEN}✓${NC} code-server 4.117.0"
            else
                echo -e "  ${YELLOW}[WARN]${NC} code-server installation failed (non-critical) — skipped"
            fi
        }
        [ "${INSTALL_PLAYWRIGHT:-false}" = "true" ] && {
            echo "  Installing Playwright (playwright-core)..."
            npm install -g playwright-core 2>&1 || echo -e "  ${YELLOW}[WARN]${NC} playwright-core installation failed (non-critical)"
            # Set Playwright environment variables if Chromium is available
            CHROMIUM_BIN=""
            for bin in "$PREFIX/bin/chromium-browser" "$PREFIX/bin/chromium"; do
                [ -x "$bin" ] && CHROMIUM_BIN="$bin" && break
            done
            if [ -n "$CHROMIUM_BIN" ]; then
                PW_MARKER_START="# >>> Playwright >>>"
                PW_MARKER_END="# <<< Playwright <<<"
                if ! grep -qF "$PW_MARKER_START" "$HOME/.bashrc"; then
                    cat >> "$HOME/.bashrc" << PWENV

${PW_MARKER_START}
export PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH="$CHROMIUM_BIN"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
${PW_MARKER_END}
PWENV
                fi
                echo -e "  ${GREEN}✓${NC} Playwright (env: PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=$CHROMIUM_BIN)"
            else
                echo -e "  ${GREEN}✓${NC} Playwright (install Chromium later via 'oa --install' for full setup)"
            fi
        }
        [ "${INSTALL_CLAUDE_CODE:-false}" = "true" ] && {
            echo "  Installing Claude Code..."
            if npm install -g @anthropic-ai/claude-code 2>&1; then
                if timeout 30 claude --version >/dev/null 2>&1; then
                    echo -e "  ${GREEN}✓${NC} Claude Code"
                else
                    echo -e "  ${YELLOW}[WARN]${NC} Claude Code installed, but its native binary does not run on this setup yet (support is planned)"
                fi
            else
                echo -e "  ${YELLOW}[WARN]${NC} Claude Code installation failed (non-critical) — skipped"
            fi
        }
        [ "${INSTALL_GEMINI_CLI:-false}" = "true" ] && {
            echo "  Installing Gemini CLI..."
            if npm install -g @google/gemini-cli 2>&1; then
                echo -e "  ${GREEN}✓${NC} Gemini CLI"
            else
                echo -e "  ${YELLOW}[WARN]${NC} Gemini CLI installation failed (non-critical) — skipped"
            fi
        }
        [ "${INSTALL_CODEX_CLI:-false}" = "true" ] && {
            echo "  Installing Codex CLI (Termux)..."
            if npm install -g @mmmbuto/codex-cli-termux 2>&1; then
                # Create codex CLI wrapper (DioNanos fork launcher fix)
                _codex_bin="$PREFIX/bin/codex"
                _codex_pkg="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux/bin"
                if [ -f "$_codex_pkg/codex.bin" ]; then
                    [ -L "$_codex_bin" ] && rm -f "$_codex_bin"
                    printf '#!%s/bin/bash\nPKG_BIN="%s"\nexport LD_LIBRARY_PATH="$PKG_BIN:${LD_LIBRARY_PATH:-}"\nexec "$PKG_BIN/codex.bin" "$@"\n' \
                        "$PREFIX" "$_codex_pkg" > "$_codex_bin"
                    chmod +x "$_codex_bin"
                fi
                echo -e "  ${GREEN}✓${NC} Codex CLI (Termux)"
            else
                echo -e "  ${YELLOW}[WARN]${NC} Codex CLI (Termux) installation failed (non-critical) — skipped"
                echo "         The package targets Termux's Android Node.js; support for this setup is planned."
            fi
        }

        # Fix shebangs in npm global CLIs (kept in sync with scripts/lib.sh fix_npm_global_shebangs())
        for _js in "$PREFIX/lib/node_modules"/*/bin/*.js \
                   "$PREFIX/lib/node_modules"/@*/*/bin/*.js; do
            [ -f "$_js" ] || continue
            head -1 "$_js" | grep -q '^#!/usr/bin/env node$' || continue
            sed -i "1s|#!/usr/bin/env node|#!$BIN_DIR/node|" "$_js"
        done
    else
        echo -e "▸ ${YELLOW}[7/7]${NC} No optional tools selected"
    fi
else
    echo -e "▸ ${YELLOW}[7/7]${NC} No optional tools selected"
fi

# ─── Cleanup ────────────────────────────────
rm -rf "$DEB_DIR" "$PKG_DIR" "$PACKAGES_FILE" "$TMPDIR/gpkg.db" 2>/dev/null || true

# ─── Done ────────────────────────────────────
touch "$MARKER"

echo ""
echo "══════════════════════════════════════════════"
echo -e "  ${GREEN}✓ Installation complete!${NC}"
echo "══════════════════════════════════════════════"
echo ""
echo "  Loading environment..."
# ~/.bashrc is written for interactive shells; a non-zero status from any line
# in it must not abort setup before onboard (errexit is off inside `||`).
source "$HOME/.bashrc" || true
echo ""
echo "  Starting OpenClaw onboard..."
echo ""
openclaw onboard
