#!/usr/bin/env bash
# install-nodejs.sh - Install Node.js linux-arm64 with grun wrapper (L2 conditional)
# Extracted from install-glibc-env.sh — Node.js only, assumes glibc already installed.
# Called by orchestrator when config.env PLATFORM_NEEDS_NODEJS=true.
#
# Usage: install-nodejs.sh <version> [<platform npm package> <its pinned version>]
#   <version> is the pinned Node.js version (X.Y.Z). Callers pass
#   PLATFORM_NODE_VERSION from platforms/<platform>/config.env (the SSOT);
#   this script has no default of its own. The optional package/pin pair is the
#   fallback pin for the npm wrapper's version-pin guard.
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
# Optional 2nd/3rd arguments: the platform package and its pinned version. They
# are only the fallback pin of the npm wrapper's version-pin guard (the guard
# reads the platform's config.env at run time). Missing or malformed = no fallback.
OA_GUARD_PKG="${2:-}"
OA_GUARD_PIN="${3:-}"
if [[ ! "$OA_GUARD_PKG" =~ ^[a-z0-9][a-z0-9._-]*$ ]] || [[ ! "$OA_GUARD_PIN" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    OA_GUARD_PKG=""
    OA_GUARD_PIN=""
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
    _WRAPPER_CHANGED=true
    cat > "$BIN_DIR/node.tmp" << WRAPPER
#!${PREFIX}/bin/bash
[ -n "\$LD_PRELOAD" ] && export _OA_ORIG_LD_PRELOAD="\$LD_PRELOAD"
unset LD_PRELOAD
export _OA_WRAPPER_PATH="$BIN_DIR/node"
_OA_COMPAT="\$HOME/.openclaw-android/lib/glibc-compat.js"
[ -s "\$_OA_COMPAT" ] || _OA_COMPAT="\$HOME/.openclaw-android/patches/glibc-compat.js"
if [ -f "\$_OA_COMPAT" ]; then
    case "\${NODE_OPTIONS:-}" in
        *glibc-compat.js*) ;;
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
    # --if-changed: leave an identical wrapper alone (sets _WRAPPER_CHANGED=false).
    # Never signal "unchanged" through the return code: callers must run this as a
    # plain command (not in if/||/&&), or set -e is off inside it and a failed
    # write could be moved over the working wrapper.
    if [ "${1:-}" = "--if-changed" ] && cmp -s "$BIN_DIR/node.tmp" "$BIN_DIR/node"; then
        rm -f "$BIN_DIR/node.tmp"
        _WRAPPER_CHANGED=false
        return 0
    fi
    mv -f "$BIN_DIR/node.tmp" "$BIN_DIR/node"
}

# glibc-compat.js lives in its own directory (lib/) that the Android app never
# writes. The app overwrites patches/glibc-compat.js with its bundled copy on
# every APK upgrade, so the node wrapper reads lib/ first and only falls back
# to patches/ when lib/ has no copy. Safe to call repeatedly.
install_compat_shim() {
    local src dest
    src="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/patches/glibc-compat.js"
    dest="$OPENCLAW_DIR/lib/glibc-compat.js"
    if [ ! -s "$src" ]; then
        echo -e "${YELLOW}[WARN]${NC} glibc-compat.js not found next to this script — lib/ left as it is"
        return 0
    fi
    if [ -s "$dest" ] && cmp -s "$src" "$dest"; then
        return 0
    fi
    if mkdir -p "$OPENCLAW_DIR/lib" && cp "$src" "$dest.tmp" && mv -f "$dest.tmp" "$dest"; then
        echo -e "${GREEN}[OK]${NC}   glibc-compat.js installed to $dest"
    else
        rm -f "$dest.tmp"
        echo -e "${YELLOW}[WARN]${NC} Could not install glibc-compat.js to lib/ — lib/ left as it is"
    fi
    return 0
}

# Splice the version-pin guard into a generated npm wrapper: the guard text
# replaces the "# __NPM_GUARD__" line. $1 = wrapper file. OA_GUARD_PKG / OA_GUARD_PIN
# are the pin this installer ships (the guard also reads the platform config at
# run time). The guard text must stay identical in scripts/install-nodejs.sh and
# post-setup.sh.
oa_inject_npm_guard() {
    local f="$1" g
    IFS= read -r -d '' g << 'NPMGUARD' || true
# ── Version-pin guard ──
# Refuses a GLOBAL install/update of the pinned platform package at any version
# other than the pin: 'npm install -g openclaw@latest' (typed by a user or run by
# an agent) would replace the verified OpenClaw + Node.js pair. Two pins are
# accepted: the one in the platform config on disk, and the one this wrapper was
# generated with — an installer that ships a newer pin than the config on disk
# (a re-install or recovery run) must still be able to install its own pin.
# Fails open: whatever this block cannot parse is passed to npm unchanged. There
# is no override on purpose. Limits: npm run by absolute path, abbreviated option
# names, 'npm exec'/'npx' are not covered.
_oa_pkg="__OA_PKG__"
_oa_pin="__OA_PIN__"
_oa_cfgpin=""
_oa_cfg="$HOME/.openclaw-android/platforms/openclaw/config.env"
if [ -f "$_oa_cfg" ]; then
    _oa_v=$(grep -m1 '^PLATFORM_NPM_PACKAGE_VERSION=' "$_oa_cfg" 2>/dev/null | cut -d'"' -f2)
    case "$_oa_v" in [0-9]*.[0-9]*.[0-9]*) _oa_cfgpin="$_oa_v" ;; esac
    _oa_n=$(grep -m1 '^PLATFORM_NPM_PACKAGE=' "$_oa_cfg" 2>/dev/null | cut -d'"' -f2)
    if [[ "$_oa_n" =~ ^[a-z0-9][a-z0-9._-]*$ ]]; then _oa_pkg="$_oa_n"; fi
fi
_oa_is_pin() { [ "$1" = "$_oa_pin" ] || { [ -n "$_oa_cfgpin" ] && [ "$1" = "$_oa_cfgpin" ]; }; }
_oa_check_spec() {
    local a="$1" x
    case "$a" in
        @*) return ;;
        file:*) _oa_check_spec "${a#file:}"; return ;;
        "$_oa_pkg") _oa_bad="$a"; return ;;
        "$_oa_pkg"@*) _oa_is_pin "${a#*@}" || _oa_bad="$a"; return ;;
    esac
    x="${a#*npm:}"
    if [ "$x" != "$a" ]; then
        case "$x" in
            "$_oa_pkg") _oa_bad="$a"; return ;;
            "$_oa_pkg"@*) _oa_is_pin "${x#*@}" || _oa_bad="$a"; return ;;
        esac
    fi
    case "$a" in
        */"$_oa_pkg"|*/"$_oa_pkg".git|*/"$_oa_pkg"#*|*/"$_oa_pkg".git#*|*/"$_oa_pkg"/archive/*|*/"$_oa_pkg"/tarball/*) _oa_bad="$a" ;;
        "$_oa_pkg"-[v0-9]*.tgz|*/"$_oa_pkg"-[v0-9]*.tgz|"$_oa_pkg"-[v0-9]*.tar.gz|*/"$_oa_pkg"-[v0-9]*.tar.gz|*/"$_oa_pkg"/tar.gz/*|*/"$_oa_pkg"/zip/*) _oa_bad="$a" ;;
        .|..|./*|../*|/*|"~"/*|\~/*)
            case "$a" in "~"/*|\~/*) a="$HOME/${a#*/}" ;; esac
            if [ -f "$a/package.json" ] && grep -Eq "\"name\"[[:space:]]*:[[:space:]]*\"$_oa_pkg\"" "$a/package.json" 2>/dev/null; then
                _oa_bad="$a"
            fi ;;
    esac
}
if [ -n "$_oa_pkg" ] && { [ -n "$_oa_pin" ] || [ -n "$_oa_cfgpin" ]; }; then
    _oa_global=false; _oa_cmd=""; _oa_pos=0; _oa_names=0; _oa_bad=""; _oa_skip=false; _oa_prev=""
    _oa_e="${npm_config_global:-${NPM_CONFIG_GLOBAL:-}}"
    case "$_oa_e" in ""|false|0|null) ;; *) _oa_global=true ;; esac
    case "${npm_config_location:-${NPM_CONFIG_LOCATION:-}}" in global) _oa_global=true ;; esac
    _oa_rcs=("$HOME/.npmrc" "${PREFIX:-}/etc/npmrc")
    _oa_d="$PWD"
    _oa_i=0
    # PWD can be stale or relative when the directory was deleted: only walk real absolute paths
    case "$_oa_d" in /*) ;; *) _oa_d="" ;; esac
    while [ -n "$_oa_d" ] && [ "$_oa_d" != "/" ] && [ "$_oa_i" -lt 64 ]; do
        _oa_rcs+=("$_oa_d/.npmrc")
        [ -f "$_oa_d/package.json" ] && break
        _oa_d="${_oa_d%/*}"
        _oa_i=$((_oa_i + 1))
    done
    for _oa_rc in "${_oa_rcs[@]}"; do
        if [ -f "$_oa_rc" ] && grep -Eiq '^[[:space:]]*(global[[:space:]]*=[[:space:]]*"?(true|1|yes|on)"?|location[[:space:]]*=[[:space:]]*"?global"?)' "$_oa_rc" 2>/dev/null; then
            _oa_global=true
        fi
    done
    for _oa_a in "$@"; do
        if [ "$_oa_skip" = true ]; then
            _oa_skip=false
            [ "$_oa_prev" = "location" ] && [ "$_oa_a" = "global" ] && _oa_global=true
            continue
        fi
        case "$_oa_a" in
            -*)
                _oa_n="$_oa_a"
                while [ "${_oa_n#-}" != "$_oa_n" ]; do _oa_n="${_oa_n#-}"; done
                _oa_prev="$_oa_n"
                case "$_oa_n" in
                    L) _oa_skip=true; _oa_prev="location" ;;
                    location|prefix|registry|cache|userconfig|globalconfig|loglevel|tag|scope|omit|include|otp|workspace|w|C|script-shell|save-prefix|install-strategy|depth|proxy|https-proxy|noproxy|fetch-timeout|fetch-retries|before|access|audit-level|auth-type|logs-dir|logs-max|node-options) _oa_skip=true ;;
                    g|global|no-no-global|l*=global) _oa_global=true ;;
                    g=*|global=*)
                        # '--global=true' (and '--global=<spec>', which npm reads as a package)
                        _oa_v="${_oa_n#*=}"
                        case "$_oa_v" in false) ;; *) _oa_global=true; _oa_check_spec "$_oa_v" ;; esac ;;
                    *)
                        if [[ "$_oa_a" =~ ^-[gSDOEBfdspyqlhavnPcmLHwC]+$ ]]; then
                            case "$_oa_n" in *g*) _oa_global=true ;; esac
                            case "$_oa_n" in *L) _oa_skip=true; _oa_prev="location" ;; *[wCcm]) _oa_skip=true ;; esac
                        fi ;;
                esac ;;
            *)
                _oa_pos=$((_oa_pos + 1))
                if [ -z "$_oa_cmd" ]; then
                    case "$_oa_a" in
                        link|lin|ln)
                            _oa_cmd=install; _oa_global=true; continue ;;
                        i|in|ins|inst|insta|instal|install|isnt|isnta|isntal|isntall|add|ci|clean-install|install-t|install-te|install-tes|install-test|installT|installTe|installTes|installTest|it|cit|install-ci-test|installCiTest)
                            _oa_cmd=install; continue ;;
                        up|ud|upd|upda|updat|update|upg|upgr|upgra|upgrad|upgrade|udp|udpa|udpat|udpate)
                            _oa_cmd=update; continue ;;
                    esac
                fi
                case "$_oa_a" in always|true|false|*://*) ;; *) if [[ "$_oa_a" =~ ^@?[a-z] ]]; then _oa_names=$((_oa_names + 1)); fi ;; esac
                _oa_check_spec "$_oa_a" ;;
        esac
    done
    if [ "$_oa_global" = true ] && [ -n "$_oa_cmd" ]; then
        # 'npm install -g' / 'npm link' with no name installs the package in the current directory
        if [ -z "$_oa_bad" ] && [ "$_oa_cmd" = install ] && [ "$_oa_pos" -le 1 ]; then _oa_check_spec "."; fi
        if [ -n "$_oa_bad" ]; then
            echo "[BLOCKED] $_oa_pkg is pinned to ${_oa_cfgpin:-$_oa_pin} (the version verified by OpenClaw on Android)." >&2
            echo "          '$_oa_bad' would replace it. Run 'oa --update' to update safely." >&2
            exit 1
        fi
        if [ "$_oa_cmd" = update ] && [ "$_oa_names" -eq 0 ]; then
            echo "[BLOCKED] 'npm update -g' would also upgrade $_oa_pkg, which is pinned to ${_oa_cfgpin:-$_oa_pin}." >&2
            echo "          Name the packages to update, or run 'oa --update' to update safely." >&2
            exit 1
        fi
    fi
fi
NPMGUARD
    g="${g//__OA_PKG__/${OA_GUARD_PKG:-}}"
    g="${g//__OA_PIN__/${OA_GUARD_PIN:-}}"
    if ! G="$g" awk '$0 == "# __NPM_GUARD__" { print ENVIRON["G"]; next } { print }' "$f" > "$f.g" || ! mv -f "$f.g" "$f"; then
        rm -f "$f.g"
        echo -e "${YELLOW}[WARN]${NC} npm version-pin guard could not be added to the npm wrapper"
    fi
}

# npm/npx wrappers + corepack shebang. Sets _WROTE_NPM=true if anything was
# written. Call it as a plain command (not in if/||) so set -e stops the script
# on a failed write instead of moving an empty file into place.
write_npm_wrappers() {
    _WROTE_NPM=false
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
        cat > "$BIN_DIR/npm.tmp" << 'NPMWRAP'
#!__PREFIX__/bin/bash
# __NPM_GUARD__
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
# Re-patch Claude Code launcher after global install/update: its native binary is a glibc
# build that Android cannot exec directly, so run it through the glibc loader
case "$*" in *claude-code*)
    _cc_bin="__PREFIX__/bin/claude"
    _cc_exe="__PREFIX__/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe"
    _cc_ld="__PREFIX__/glibc/lib/ld-linux-aarch64.so.1"
    if [ -L "$_cc_bin" ] && [ -f "$_cc_exe" ] && [ -x "$_cc_ld" ]; then
        printf '#!__PREFIX__/bin/bash\nexec env -u LD_PRELOAD "%s" --library-path "%s" "%s" "$@"\n' "$_cc_ld" "__PREFIX__/glibc/lib" "$_cc_exe" > "$_cc_bin.tmp" \
            && chmod +x "$_cc_bin.tmp" && mv -f "$_cc_bin.tmp" "$_cc_bin"
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
        oa_inject_npm_guard "$BIN_DIR/npm.tmp"
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

# The wrapper (written below) loads this copy. Install it before anything that
# can exit early (SKIP path, a failed Node.js download).
install_compat_shim

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
    elif [ -f "$NODE_DIR/bin/node.real" ]; then
        # Wrapper content can change without a Node.js version change
        # (e.g. it now reads lib/glibc-compat.js) — replace an outdated one.
        write_node_wrapper --if-changed
        if [ "$_WRAPPER_CHANGED" = true ]; then
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
