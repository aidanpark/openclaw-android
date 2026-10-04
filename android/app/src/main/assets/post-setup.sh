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

# ─── npm version-pin guard ────────────────────
# Fallback pin for the guard in the npm wrapper (the guard reads config.env at
# run time once it exists; a first install has none yet).
OA_GUARD_PKG="openclaw"
OA_GUARD_PIN="$PLATFORM_NPM_PACKAGE_VERSION"

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

# ─── gpgv signature helpers ───────────────────
# For downloads that are checked against a signed index (the glibc package
# database today). Return codes — the caller picks the message from the code:
#    0 good signature        1 bad signature
#    2 no good signature, and signed only by keys the keyring does not have
#    3 expired signature/key or a wrong clock
#    4 any other failure to reach a verdict
#   10 gpgv is missing      11 keyring missing or unreadable
#   12 gpgv cannot run here (not executable, killed, no usable home directory)
# The verdict comes from gpgv's machine-readable status lines (--status-fd), never
# from its free text: that text can carry strings chosen by whoever made the
# signature. rc 0 alone is not enough either (gpgv also exits 0 for a signature
# from an expired key): success needs GOODSIG and VALIDSIG and none of the failure
# lines. Nothing here falls back to an unchecked file; the clearsigned output file
# is deleted on every non-zero result. $OA_GPGV_ERR holds gpgv's text for debugging
# (do not print it raw). Callers running under `set -e` must capture the code with
# `|| rc=$?`.
OA_GPGV_ERR=""
# 0 when gpgv and the keyring $1 are usable, else 10/11/12
oa_gpgv_check_tools() {
    local gpgv="$PREFIX/bin/gpgv" keyring="$1"
    if [ ! -e "$gpgv" ]; then OA_GPGV_ERR="gpgv not found at $gpgv"; return 10; fi
    if [ ! -x "$gpgv" ] || [ -d "$gpgv" ]; then OA_GPGV_ERR="gpgv is not executable: $gpgv"; return 12; fi
    if [ ! -s "$keyring" ] || [ ! -r "$keyring" ]; then
        OA_GPGV_ERR="keyring missing or unreadable: $keyring"
        return 11
    fi
    return 0
}
_oa_gpgv() {
    local out="$1" keyring="$2" gpgv="$PREFIX/bin/gpgv" home="$TMPDIR/gnupg" err="" rc=0 status
    shift 2
    OA_GPGV_ERR=""
    oa_gpgv_check_tools "$keyring" || return $?
    if ! mkdir -p "$home" || ! chmod 700 "$home"; then
        OA_GPGV_ERR="cannot create $home"
        return 12
    fi
    if ! status=$(mktemp "$home/status.XXXXXX"); then
        OA_GPGV_ERR="cannot create a status file in $home"
        return 12
    fi
    if [ -n "$out" ]; then
        rm -f "$out"
        err=$(LC_ALL=C "$gpgv" --homedir "$home" --keyring "$keyring" --status-fd 3 --output "$out" "$@" 2>&1 3>"$status") || rc=$?
    else
        err=$(LC_ALL=C "$gpgv" --homedir "$home" --keyring "$keyring" --status-fd 3 "$@" 2>&1 3>"$status") || rc=$?
    fi
    OA_GPGV_ERR="$err"
    local verdict=4
    if [ "$rc" -eq 126 ] || [ "$rc" -eq 127 ] || [ "$rc" -gt 128 ]; then
        verdict=12
    elif grep -Eq '^\[GNUPG:\] BADSIG ' "$status"; then
        verdict=1
    elif grep -Eq '^\[GNUPG:\] (EXPSIG|EXPKEYSIG|KEYEXPIRED|SIGEXPIRED) ' "$status"; then
        verdict=3
    elif ! { grep -Eq '^\[GNUPG:\] GOODSIG ' "$status" && grep -Eq '^\[GNUPG:\] VALIDSIG ' "$status"; }; then
        # no good signature from a key in the keyring
        if grep -Eq '^\[GNUPG:\] NO_PUBKEY ' "$status"; then
            # A key created "in the future" looks like a missing key in the status lines;
            # only gpgv's own line (anchored at the start of the line) tells them apart.
            if printf '%s\n' "$err" | grep -Eq '^gpgv: key [0-9A-Fa-f]+ was created .* in the future \(time warp or clock problem\)$'; then
                verdict=3
            else
                verdict=2
            fi
        fi
    elif grep -Eq '^\[GNUPG:\] (REVKEYSIG|FAILURE|NODATA|ERROR) ' "$status" \
        || awk '$1 == "[GNUPG:]" && $2 == "ERRSIG" && $8 != 9 { f = 1 } END { exit !f }' "$status"; then
        verdict=4
    elif [ "$rc" -eq 0 ] || { [ "$rc" -eq 2 ] && grep -Eq '^\[GNUPG:\] NO_PUBKEY ' "$status"; }; then
        # A good signature from a key in the keyring counts even when the file is also
        # signed by keys this keyring lacks (gpgv then exits 2; apt accepts this too, e.g.
        # while Termux signs with an old and a new key). Any other failure line, and the
        # absence of a good signature, were handled above. Note: gpgv 2.5 stops at the first
        # signature it cannot check, so a signature that comes after an unknown-key one
        # is never evaluated (apt has the same limit); a good signature from the keyring
        # over exactly the checked data is what this accepts.
        verdict=0
        if [ -n "$out" ] && [ ! -s "$out" ]; then verdict=4; fi
    fi
    rm -f "$status"
    if [ "$verdict" -ne 0 ] && [ -n "$out" ]; then rm -f "$out"; fi
    return "$verdict"
}
# oa_gpgv_clearsigned <keyring> <clearsigned file> <out>: on success <out> holds the signed text
oa_gpgv_clearsigned() { _oa_gpgv "$3" "$1" "$2"; }
# oa_gpgv_detached <keyring> <signature file> <data file>
oa_gpgv_detached() { _oa_gpgv "" "$1" "$2" "$3"; }
# ─── end gpgv signature helpers ───────────────

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

    # The package must match the checksum in the package list whose signature was
    # verified (cached files too). Nothing is unpacked before this check passes.
    local want have
    want=$(get_deb_sha256 "$filename") || want=""
    have=$(sha256sum "$deb_file" 2>/dev/null | awk '{ print $1 }') || have=""
    if [ "${#want}" -ne 64 ] || [[ "$want" == *[!0-9a-f]* ]] || [ "$have" != "$want" ]; then
        rm -f "$deb_file"
        echo -e "    ${RED}[FAIL]${NC} $name: verification failed (it does not match the signed package list) — not installed" >&2
        echo "           Check your network and try again; the package source may be unavailable or the download corrupted." >&2
        return 1
    fi

    rm -rf "$EXTRACT_DIR"
    mkdir -p "$EXTRACT_DIR"
    if ! dpkg-deb -x "$deb_file" "$EXTRACT_DIR" 2>/dev/null; then
        rm -f "$deb_file"
        rm -rf "$EXTRACT_DIR"
        return 1
    fi

    # Relocate: data/data/com.termux/files/usr/* → $PREFIX/
    # --remove-destination replaces an existing file with a new inode instead of
    # truncating and rewriting it in place, so a process that has the old file open
    # or mapped keeps reading the old content (dpkg also gives a replaced file a new inode).
    if [ -d "$EXTRACT_DIR/$TERMUX_INNER" ]; then
        cp -a --remove-destination "$EXTRACT_DIR/$TERMUX_INNER/"* "$PREFIX/" 2>/dev/null || true
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

# ─── glibc package database: signed, from the first mirror that checks out ───
OA_KEYRING_DIR="$PREFIX/share/termux-keyring"
_GPKG_REASON=""
_GPKG_RC=0
# Download <mirror>/gpkg.db and gpkg.db.sig and verify the signature. On any
# failure the database is deleted so nothing can read an unverified one.
# _GPKG_REASON: unreachable | nosig | badsig | clock | tool   (_GPKG_RC = helper code)
fetch_signed_gpkg_db() {
    local mirror="$1" rc=0
    _GPKG_REASON=""
    _GPKG_RC=0
    rm -f "$GPKG_DB" "$GPKG_DB.sig"
    if ! curl -fsSL --max-time 60 -o "$GPKG_DB" "$mirror/gpkg.db"; then
        rm -f "$GPKG_DB"
        _GPKG_REASON="unreachable"
        return 1
    fi
    if ! curl -fsSL --max-time 60 -o "$GPKG_DB.sig" "$mirror/gpkg.db.sig"; then
        rm -f "$GPKG_DB" "$GPKG_DB.sig"
        _GPKG_REASON="nosig"
        return 1
    fi
    oa_gpgv_detached "$OA_KEYRING_DIR/termux-pacman.gpg" "$GPKG_DB.sig" "$GPKG_DB" || rc=$?
    _GPKG_RC=$rc
    if [ "$rc" -eq 0 ]; then
        return 0
    fi
    rm -f "$GPKG_DB" "$GPKG_DB.sig"
    case "$rc" in
        1|2|4) _GPKG_REASON="badsig" ;;
        3) _GPKG_REASON="clock" ;;
        *) _GPKG_REASON="tool" ;;
    esac
    return 1
}

# Try the mirrors in turn; for the first one whose database signature verifies,
# download and sha256-check every package in GLIBC_PKGS from that same mirror.
# Sets GLIBC_FILES and returns 0, or prints what went wrong and what to do and
# returns 1. There is no unsigned fallback. gpgv and the keyring are checked
# before anything is downloaded.
fetch_glibc_packages() {
    local _mirror _pkg _info _file _sha _fails="" _stop="" _key_unknown=false _verified=false _rc=0
    GLIBC_FILES=()
    _GPKG_RC=0
    oa_gpgv_check_tools "$OA_KEYRING_DIR/termux-pacman.gpg" || _rc=$?
    if [ "$_rc" -ne 0 ]; then
        _stop="tool"
        _GPKG_RC=$_rc
    else
        for _mirror in "${PACMAN_MIRRORS[@]}"; do
            GLIBC_FILES=()
            echo "  Mirror: ${_mirror%/gpkg/aarch64}"
            if ! fetch_signed_gpkg_db "$_mirror"; then
                _fails="$_fails $_GPKG_REASON"
                case "$_GPKG_REASON" in
                    unreachable) echo -e "  ${YELLOW}[WARN]${NC} Package database not reachable" ;;
                    nosig) echo -e "  ${YELLOW}[WARN]${NC} This mirror has no signature for the package database" ;;
                    badsig)
                        [ "$_GPKG_RC" -eq 2 ] && _key_unknown=true
                        echo -e "  ${YELLOW}[WARN]${NC} Signature verification FAILED for the package database on this mirror (code $_GPKG_RC) — not using it" ;;
                    *) _stop="$_GPKG_REASON"; break ;;   # same on every mirror: stop here
                esac
                continue
            fi
            _verified=true
            echo -e "  ${GREEN}✓${NC} Package database signature verified"
            for _pkg in "${GLIBC_PKGS[@]}"; do
                if ! _info=$(gpkg_lookup "$_pkg"); then
                    echo -e "  ${YELLOW}[WARN]${NC} $_pkg: no valid entry in package database"
                    break
                fi
                read -r _file _sha <<< "$_info"
                fetch_pacman_pkg "$_mirror" "$_file" "$_sha" || break
                GLIBC_FILES+=("$_file")
            done
            if [ "${#GLIBC_FILES[@]}" -eq "${#GLIBC_PKGS[@]}" ]; then
                return 0
            fi
        done
    fi
    GLIBC_FILES=()
    echo -e "  ${RED}✗${NC} Could not install glibc."
    if [ -n "$_stop" ]; then
        case "$_stop" in
            tool)
                case "$_GPKG_RC" in
                    10) echo "    Cannot verify package signatures: gpgv is missing. Reinstall the app and try again, or report this." ;;
                    11) echo "    Cannot verify package signatures: the Termux keyring is missing. Reinstall the app and try again, or report this." ;;
                    *) echo "    gpgv cannot run on this device, so package signatures cannot be checked. Installation was stopped for safety. Please report this (error code $_GPKG_RC)." ;;
                esac ;;
            *)
                echo "    Signature check failed. Check the phone's date and time (turn on automatic date & time); if they are right, the signing key may have changed. Restart the app to retry, or report this." ;;
        esac
    elif [ "$_verified" = true ]; then
        echo "    A package list was verified, but the packages could not be downloaded and checked from those mirrors."
        echo "    Check your network connection and restart the app to retry."
    else
        case "$_fails" in
            *badsig*)
                if [ "$_key_unknown" = true ]; then
                    echo "    The package list is signed by a key this app does not know (the signing key may have changed)."
                    echo "    Installation was stopped for safety. Restart the app later (it fetches the latest setup on each start), or report this."
                else
                    echo "    The package list failed its signature check on every mirror that answered. Something may be tampering with the download."
                    echo "    Installation was stopped for safety. Try another network and restart the app to retry."
                fi ;;
            *nosig*)
                echo "    No reachable mirror offered a signed package list, so nothing could be verified."
                echo "    Installation was stopped for safety. Restart the app to retry later, or report this." ;;
            *)
                echo "    Could not download glibc from any mirror:"
                printf '      %s\n' "${PACMAN_MIRRORS[@]}"
                echo "    Check your network connection and restart the app to retry." ;;
        esac
    fi
    return 1
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

# Download the Packages index to resolve .deb filenames, and prove it is the one
# Termux signed:  InRelease (signed)  →  size + sha256 of Packages  →  Packages
# →  sha256 of each .deb (checked in install_deb). There is no unsigned fallback:
# if the list cannot be verified, the installation stops here.
echo "  Fetching package index..."
PACKAGES_FILE="$TMPDIR/Packages"
OA_INRELEASE="$TMPDIR/InRelease"
OA_RELEASE_VERIFIED="$TMPDIR/Release.verified"
OA_DEB_KEYRING="$OA_KEYRING_DIR/termux-autobuilds.gpg"

# oa_fail_index <reason|helper code>: say what went wrong and what to do, then stop
oa_fail_index() {
    rm -f "$OA_INRELEASE" "$OA_RELEASE_VERIFIED" "$PACKAGES_FILE"
    echo -e "  ${RED}✗${NC} Could not verify the Termux package list."
    case "$1" in
        download)
            echo "    The package list could not be downloaded. Check your network connection and restart the app to retry." ;;
        mismatch)
            echo "    The downloaded package list does not match its signed checksum. The download may be corrupted or tampered with, or the server may be updating: wait a minute, try another network, and restart the app to retry." ;;
        noentry)
            echo "    The signed package list has no checksum for the package index. Try again later and restart the app to retry; if it keeps failing, report this." ;;
        1)
            echo "    The package list failed its signature check. Something may be tampering with the download. Try another network and restart the app to retry." ;;
        2)
            echo "    The package list is signed by a key this app does not know (the Termux signing key may have changed). Get the latest version of the app and try again, or report this." ;;
        3)
            echo "    The signature check failed because of a date problem. Check that the phone's date and time are correct (automatic date & time) and restart the app to retry. It can also mean the Termux signing key changed." ;;
        10)
            echo "    Cannot verify package signatures: gpgv is missing. Reinstall the app and try again, or report this." ;;
        11)
            echo "    Cannot verify package signatures: the Termux keyring is missing. Reinstall the app and try again, or report this." ;;
        12)
            echo "    gpgv cannot run on this device, so package signatures cannot be checked. Please report this (error code 12)." ;;
        *)
            echo "    The signature check did not give a clear answer (error code $1). Restart the app to retry; if it keeps failing, report this." ;;
    esac
    echo "    Installation was stopped for safety: nothing from the unverified package list was installed."
    exit 1
}

_idx_rc=0
oa_gpgv_check_tools "$OA_DEB_KEYRING" || _idx_rc=$?
[ "$_idx_rc" -eq 0 ] || oa_fail_index "$_idx_rc"
curl -fsSL --max-time 60 -o "$OA_INRELEASE" \
    "${TERMUX_DEB_REPO}/dists/stable/InRelease" || oa_fail_index download
oa_gpgv_clearsigned "$OA_DEB_KEYRING" "$OA_INRELEASE" "$OA_RELEASE_VERIFIED" || _idx_rc=$?
[ "$_idx_rc" -eq 0 ] || oa_fail_index "$_idx_rc"
echo -e "  ${GREEN}✓${NC} Package list signature verified"

# Size and sha256 of Packages as stated in the signed Release (its SHA256: block)
_want_sha="" _want_size=""
_rel_line=$(awk '
    /^SHA256:/ { f = 1; next }
    /^[^ ]/ { f = 0 }
    f && $3 == "main/binary-aarch64/Packages" { print $1, $2; exit }
' "$OA_RELEASE_VERIFIED") || _rel_line=""
read -r _want_sha _want_size <<< "$_rel_line" || true
if [ "${#_want_sha}" -ne 64 ] || [[ "$_want_sha" == *[!0-9a-f]* ]] || [[ "$_want_size" == *[!0-9]* ]] || [ -z "$_want_size" ]; then
    oa_fail_index noentry
fi
curl -fsSL --max-time 60 -o "$PACKAGES_FILE" \
    "${TERMUX_DEB_REPO}/dists/stable/main/binary-aarch64/Packages" || oa_fail_index download
_got_sha=$(sha256sum "$PACKAGES_FILE" | awk '{ print $1 }')
_got_size=$(wc -c < "$PACKAGES_FILE" | tr -d ' ')
if [ "$_got_sha" != "$_want_sha" ] || [ "$_got_size" != "$_want_size" ]; then
    oa_fail_index mismatch
fi
rm -f "$OA_INRELEASE" "$OA_RELEASE_VERIFIED"
echo -e "  ${GREEN}✓${NC} Package list matches the signed checksum"

# sha256 of the .deb whose "Filename:" is $1, from the (verified) Packages index
get_deb_sha256() {
    awk -v fn="$1" '
        /^$/ { if (fname == fn && sha != "") { print sha; done = 1; exit } fname = ""; sha = ""; next }
        /^Filename: / { fname = $2 }
        /^SHA256: / { sha = $2 }
        END { if (!done && fname == fn && sha != "") print sha }
    ' "$PACKAGES_FILE"
}

# Resolve package filename from Packages index
get_deb_filename() {
    local pkg="$1"
    awk -v pkg="$pkg" '
        /^Package: / { found = ($2 == pkg) }
        found && /^Filename:/ { print $2; exit }
    ' "$PACKAGES_FILE"
}

# Print the packages $1 depends on, one per line (version constraints dropped,
# first alternative of "a | b")
get_deb_depends() {
    local pkg="$1"
    awk -v pkg="$pkg" '
        /^Package: / { found = ($2 == pkg) }
        found && /^Depends:/ {
            sub(/^Depends: /, "")
            n = split($0, parts, / *, */)
            for (i = 1; i <= n; i++) {
                d = parts[i]
                sub(/ *\|.*/, "", d)
                sub(/ *\(.*/, "", d)
                gsub(/ /, "", d)
                if (d != "") print d
            }
            exit
        }
    ' "$PACKAGES_FILE"
}

# True if the bootstrap's dpkg database lists package $1 as installed
dpkg_has() {
    local pkg="$1"
    awk -v pkg="$pkg" '
        /^Package: / { found = ($2 == pkg) }
        found && /^Status: / { if ($0 ~ / installed$/) ok = 1; exit }
        END { exit !ok }
    ' "$PREFIX/var/lib/dpkg/status" 2>/dev/null
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
    install_deb "$filename" || {
        echo -e "  ${RED}✗${NC} Could not install '$pkg' (required). Check your network connection and restart the app to retry."
        exit 1
    }
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
    # The package database must carry a valid signature (checked with the bootstrap's
    # gpgv and the termux-pacman key); mirrors are tried in turn, and database and
    # packages come from the same mirror. There is no unsigned fallback.
    GLIBC_PKGS=(glibc gcc-libs-glibc)
    GLIBC_FILES=()
    echo "  Downloading glibc + gcc-libs (~34MB)..."
    if ! fetch_glibc_packages; then
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
# __NPM_GUARD__
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
        oa_inject_npm_guard "$BIN_DIR/npm.tmp"
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
    mv -f "$BIN_DIR/node.tmp" "$BIN_DIR/node"

    # Create npm/npx wrappers in BIN_DIR
    if [ -f "$NODE_DIR/lib/node_modules/npm/bin/npm-cli.js" ]; then
        cat > "$BIN_DIR/npm.tmp" << NPMWRAP
#!$PREFIX/bin/bash
# __NPM_GUARD__
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
        oa_inject_npm_guard "$BIN_DIR/npm.tmp"
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

# ─── git wrapper functions ────────────────────
# The Termux git package ships the real binary as $PREFIX/bin/git (a regular file)
# and libexec/git-core/git as a symlink to it; older layouts had it the other way
# round. Our wrapper replaces bin/git, so the real binary is first kept as
# bin/git.real — it must never be the only copy that gets deleted, and the wrapper
# must never exec something that resolves back to itself.
GIT_BIN="$PREFIX/bin/git"
GIT_REAL="$PREFIX/bin/git.real"
GIT_MARKER="$PREFIX/bin/git.wrapper-installed"
# true when $1 is one of our wrappers (this version or an older one)
git_is_wrapper() { [ -f "$1" ] && grep -q 'is_clone=false' "$1" 2>/dev/null; }
# $1 is a regular file that is NOT a wrapper (callers check): true when it prints a git version.
# Never call this on something that might be a wrapper — the old wrapper loops forever.
git_runs() {
    local out
    [ -f "$1" ] || return 1
    git_is_wrapper "$1" && return 1
    # git reads the system/global config even for --version: probe it without them, so a
    # broken ~/.gitconfig cannot make a good binary look dead
    if command -v timeout >/dev/null 2>&1; then
        out=$(env HOME=/nonexistent XDG_CONFIG_HOME=/nonexistent GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null timeout 10 "$1" --version 2>/dev/null) || return 1
    else
        out=$(env HOME=/nonexistent XDG_CONFIG_HOME=/nonexistent GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null "$1" --version 2>/dev/null) || return 1
    fi
    case "$out" in "git version"*) return 0 ;; esac
    return 1
}
# print the path of the real git binary: a regular file, after resolving symlinks,
# that is not one of our wrappers. Fails when there is none. A binary freshly unpacked
# at bin/git (or via libexec/git-core/git) wins over an older bin/git.real.
git_find_real() {
    local c r
    for c in "$GIT_BIN" "$PREFIX/libexec/git-core/git" "$GIT_REAL"; do
        r=$(readlink -f "$c" 2>/dev/null) || continue
        if [ -f "$r" ] && [ ! -L "$r" ] && ! git_is_wrapper "$r" && git_runs "$r"; then
            printf '%s\n' "$r"
            return 0
        fi
    done
    return 1
}
# write the wrapper over $GIT_BIN (temp file + rename)
git_write_wrapper() {
    local tmp="$GIT_BIN.new.$$"
    {
        printf '#!%s/bin/bash\n' "$PREFIX"
        printf 'PFX="%s"\n' "$PREFIX"
        cat << 'ENDWRAP'
# openclaw-android git wrapper: drops --recurse-submodules (it opens a hardcoded
# com.termux path) and clears an existing clone target in a temp/cache dir (npm creates it first)
unset CDPATH
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
      *)
        # only temp/cache directories (npm creates its clone target first); a directory
        # the user named (a source repo, a --reference) must never be deleted
        if [ -d "$a" ]; then
          # compare resolved paths with resolved bases (/data/data is a link to /data/user/0)
          d=$(cd -P "$a" 2>/dev/null && pwd -P) || d=""
          ok=false
          for base in "$HOME/.npm" "$PFX/tmp" "${TMPDIR:-}" "${npm_config_cache:-}"; do
            [ -n "$base" ] || continue
            b=$(cd "$base" 2>/dev/null && pwd -P) || continue
            case "$d" in "$b"/*) ok=true ;; esac
          done
          if $ok; then
            case "$d" in
              */_cacache/tmp/git-clone*)
                # npm's own throw-away clone dir (it reuses it for its https -> ssh fallbacks,
                # so it may hold a failed attempt's files): safe to empty. Only the directory
                # directly under _cacache/tmp/ -- nothing nested below it
                case "${d##*/_cacache/tmp/}" in
                  */*) ;;
                  *) rm -rf "$a" ;;
                esac ;;
              *)
                # anything else only if it is EMPTY: a directory that already has content is
                # never deleted, whatever it is called
                if [ -z "$(ls -A "$a" 2>/dev/null)" ]; then rmdir "$a" 2>/dev/null; fi ;;
            esac
          fi
        fi ;;
    esac
  done
fi
ENDWRAP
        # exec -a keeps the name git was called by: git decides built-in commands
        # (git-upload-pack ...) from argv[0], and those names are links to this wrapper
        printf 'exec -a "${0##*/}" "%s" "${filtered[@]}"\n' "$GIT_REAL"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod +x "$tmp" && mv -f "$tmp" "$GIT_BIN" || { rm -f "$tmp"; return 1; }
}
# true when the wrapper is in place and points at an existing real binary
git_wrapper_ok() {
    git_is_wrapper "$GIT_BIN" && [ -x "$GIT_REAL" ] && ! git_is_wrapper "$GIT_REAL" \
        && grep -qF "exec -a \"\${0##*/}\" \"$GIT_REAL\"" "$GIT_BIN" \
        && git_real_runs
}
# the saved real binary prints a git version
git_real_runs() { git_runs "$GIT_REAL"; }
# run the installed wrapper once: it must print a git version (bounded when `timeout` exists)
git_selfcheck() {
    local out
    if command -v timeout >/dev/null 2>&1; then
        out=$(env HOME=/nonexistent XDG_CONFIG_HOME=/nonexistent GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null timeout 10 "$GIT_BIN" --version 2>/dev/null) || return 1
    else
        out=$(env HOME=/nonexistent XDG_CONFIG_HOME=/nonexistent GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null "$GIT_BIN" --version 2>/dev/null) || return 1
    fi
    case "$out" in "git version"*) return 0 ;; esac
    return 1
}
# keep the real binary as bin/git.real, write the wrapper, check it works.
# Returns 0 ok, 2 when there is no real binary to keep (caller must get one), 1 on failure.
git_install_wrapper() {
    local real
    real=$(git_find_real) || return 2
    if [ "$real" != "$GIT_REAL" ]; then
        cp -p "$real" "$GIT_REAL.tmp" && chmod +x "$GIT_REAL.tmp" && mv -f "$GIT_REAL.tmp" "$GIT_REAL" \
            || { rm -f "$GIT_REAL.tmp"; return 1; }
    fi
    chmod +x "$GIT_REAL" 2>/dev/null || true
    git_write_wrapper || return 1
    if ! git_selfcheck; then
        # The wrapper does not run: put the real binary back as bin/git so git still
        # works (without the wrapper) and the failure does not make things worse.
        if git_real_runs; then
            cp -p "$GIT_REAL" "$GIT_BIN.rb" && mv -f "$GIT_BIN.rb" "$GIT_BIN" || rm -f "$GIT_BIN.rb"
        fi
        return 1
    fi
    touch "$GIT_MARKER"
    return 0
}
# ─── end git wrapper functions ────────────────

# Git wrapper: replace $PREFIX/bin/git with a wrapper that:
#   1. Strips --recurse-submodules (triggers open() on hardcoded com.termux path)
#   2. Cleans existing target dirs before clone (npm's withTempDir creates dir first)
# npm caches git path at module load via which.sync('git'), so we must replace the binary.
# The real binary is kept as $PREFIX/bin/git.real (see the git wrapper functions above).
if git_wrapper_ok; then
    touch "$GIT_MARKER"
    echo -e "  ${GREEN}[SKIP]${NC} git wrapper already installed"
else
    echo "  Installing git wrapper (strips --recurse-submodules)..."
    git_rc=0
    git_install_wrapper || git_rc=$?
    if [ "$git_rc" -eq 2 ]; then
        # No real git binary on disk (an earlier broken install deleted it): fetch it again.
        echo "  Real git binary missing — downloading it again..."
        if _git_deb=$(get_deb_filename git) && [ -n "$_git_deb" ] && install_deb "$_git_deb"; then
            git_rc=0
            git_install_wrapper || git_rc=$?
        else
            git_rc=3
        fi
    fi
    if [ "$git_rc" -eq 0 ]; then
        echo -e "  ${GREEN}✓${NC} git wrapper installed"
    else
        if [ "$git_rc" -eq 3 ]; then
            echo -e "  ${RED}✗${NC} Could not download git. Check your network connection and restart the app to retry."
        else
            echo -e "  ${RED}✗${NC} Could not set up git: the git wrapper would not run."
            echo "    Restart the app to try again, or report this."
        fi
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

# Install clawdhub (skill manager) — only when it is missing: re-running this script
# (e.g. after removing the done-marker to pick up a fixed post-setup.sh) must not
# reinstall it.
CLAWHUB_DIR="$(npm root -g 2>/dev/null || true)/clawdhub"
if [ -f "$CLAWHUB_DIR/package.json" ]; then
    echo -e "  ${GREEN}[SKIP]${NC} clawdhub already installed"
else
    echo "  Installing clawdhub..."
    if npm install -g clawdhub --no-fund --no-audit; then
        echo -e "  ${GREEN}✓${NC} clawdhub installed"
    else
        echo -e "  ${YELLOW}[WARN]${NC} clawdhub installation failed (non-critical)"
    fi
fi
if [ -d "$CLAWHUB_DIR" ] && ! (cd "$CLAWHUB_DIR" && node -e "require('undici')" 2>/dev/null); then
    echo "  Installing undici dependency for clawdhub..."
    (cd "$CLAWHUB_DIR" && npm install undici --no-fund --no-audit) || true
fi

# PyYAML (for .skill packaging)
command -v python &>/dev/null && { python -c "import yaml" 2>/dev/null || pip install pyyaml -q || true; }

# ─── [5/7] Patches ──────────────────────────
echo -e "▸ ${YELLOW}[5/7]${NC} Applying patches..."

# glibc-compat.js — the node wrapper reads lib/glibc-compat.js, a directory the
# Android app never writes (the app overwrites patches/glibc-compat.js with its
# bundled copy on every APK upgrade). patches/ keeps a copy for the app and for
# older wrappers.
mkdir -p "$OCA_DIR/lib" "$OCA_DIR/patches"
COMPAT_SRC="$(dirname "$0")/glibc-compat.js"
COMPAT_TMP="$OCA_DIR/lib/glibc-compat.js.tmp"
if [ -f "$COMPAT_SRC" ]; then
    cp "$COMPAT_SRC" "$COMPAT_TMP" || rm -f "$COMPAT_TMP"
else
    # Fallback: download from repo
    curl -fsSL "$REPO_BASE/patches/glibc-compat.js" \
        -o "$COMPAT_TMP" 2>/dev/null || rm -f "$COMPAT_TMP"
fi
if [ -s "$COMPAT_TMP" ]; then
    mv -f "$COMPAT_TMP" "$OCA_DIR/lib/glibc-compat.js"
    cp "$OCA_DIR/lib/glibc-compat.js" "$OCA_DIR/patches/glibc-compat.js"
elif [ ! -s "$OCA_DIR/lib/glibc-compat.js" ] && [ -s "$OCA_DIR/patches/glibc-compat.js" ]; then
    # Download failed and lib/ has none yet: use the copy the app bundled
    # (what the wrapper used before). An existing lib/ copy is never replaced by it.
    cp "$OCA_DIR/patches/glibc-compat.js" "$OCA_DIR/lib/glibc-compat.js"
    echo -e "  ${YELLOW}[WARN]${NC} Could not download glibc-compat.js — using the bundled copy. Run 'oa --update' later."
fi
rm -f "$COMPAT_TMP"

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
# Downloaded to a temp file, checked (a script starts with "#!"), made executable and
# only then renamed into place — an interrupted download must not leave a partial oa or
# one without the execute bit (the app's umask makes new files 0600). A working oa that
# is already there stays untouched when the download fails.
_oa_tmp="$PREFIX/bin/.oa.tmp.$$"
if curl -fsSL "$REPO_BASE/oa.sh" -o "$_oa_tmp" 2>/dev/null \
        && [ "$(head -c 2 "$_oa_tmp" 2>/dev/null)" = "#!" ] \
        && chmod +x "$_oa_tmp" && mv -f "$_oa_tmp" "$PREFIX/bin/oa"; then
    echo -e "  ${GREEN}✓${NC} oa CLI installed"
else
    rm -f "$_oa_tmp"
    echo -e "  ${YELLOW}[WARN]${NC} oa CLI installation failed (non-critical)"
fi

# Files that oa --backup / oa --restore need (the same ones oa --update installs).
# All three are fetched first and put in place together, so a failed download never
# leaves a half set; oa --update installs them later if this step is skipped.
_oa_dl_ok=true
mkdir -p "$OCA_DIR/scripts" "$OCA_DIR/platforms/openclaw"
for _oa_f in scripts/lib.sh scripts/backup.sh platforms/openclaw/config.env; do
    if ! curl -fsSL "$REPO_BASE/$_oa_f" -o "$OCA_DIR/$_oa_f.tmp" 2>/dev/null || [ ! -s "$OCA_DIR/$_oa_f.tmp" ]; then
        _oa_dl_ok=false
        break
    fi
    # A mirror can answer 200 with an HTML page: scripts must start with #!, config.env must define the data dir
    case "$_oa_f" in
        *.sh) head -c 2 "$OCA_DIR/$_oa_f.tmp" | grep -q '^#!' || { _oa_dl_ok=false; break; } ;;
        *.env) grep -q '^PLATFORM_DATA_DIR=' "$OCA_DIR/$_oa_f.tmp" || { _oa_dl_ok=false; break; } ;;
    esac
done
if [ "$_oa_dl_ok" = true ]; then
    for _oa_f in scripts/lib.sh scripts/backup.sh platforms/openclaw/config.env; do
        mv -f "$OCA_DIR/$_oa_f.tmp" "$OCA_DIR/$_oa_f"
    done
    echo -e "  ${GREEN}✓${NC} backup/restore scripts installed (oa --backup, oa --restore)"
else
    rm -f "$OCA_DIR/scripts/lib.sh.tmp" "$OCA_DIR/scripts/backup.sh.tmp" "$OCA_DIR/platforms/openclaw/config.env.tmp"
    echo -e "  ${YELLOW}[WARN]${NC} Could not download the backup/restore scripts (non-critical) — run oa --update later to get them"
fi

# ─── [7/7] Optional Tools ──────────────────
TOOL_CONF="$OCA_DIR/tool-selections.conf"
if [ -f "$TOOL_CONF" ]; then
    # Read the selections as data, never as code: only the known keys, and only the
    # exact values true/false. The file is written by the app's setup screen, so
    # it must not be executed (a value with a newline or $(...) would run).
    _tool_lineno=0
    while IFS= read -r _tool_line || [ -n "$_tool_line" ]; do
        _tool_lineno=$((_tool_lineno + 1))
        # Check the length first: string operations on a huge line are very slow
        if [ "${#_tool_line}" -gt 80 ]; then
            echo -e "  ${YELLOW}[WARN]${NC} Ignoring line $_tool_lineno of tool-selections.conf (too long)"
            continue
        fi
        _tool_line="${_tool_line%$'\r'}"
        [ -z "$_tool_line" ] && continue
        _tool_key="${_tool_line%%=*}"
        _tool_val="${_tool_line#*=}"
        case "$_tool_key" in
            INSTALL_TMUX|INSTALL_TTYD|INSTALL_DUFS|INSTALL_CODE_SERVER|INSTALL_PLAYWRIGHT|INSTALL_CLAUDE_CODE|INSTALL_GEMINI_CLI|INSTALL_CODEX_CLI)
                if [ "$_tool_line" != "$_tool_key" ] && { [ "$_tool_val" = "true" ] || [ "$_tool_val" = "false" ]; }; then
                    printf -v "$_tool_key" '%s' "$_tool_val"
                    continue
                fi
                ;;
        esac
        echo -e "  ${YELLOW}[WARN]${NC} Ignoring line $_tool_lineno of tool-selections.conf (unknown option or invalid value)"
    done < "$TOOL_CONF"

    HAS_TOOLS=false
    for var in INSTALL_TMUX INSTALL_TTYD INSTALL_DUFS INSTALL_CODE_SERVER INSTALL_PLAYWRIGHT INSTALL_CLAUDE_CODE INSTALL_GEMINI_CLI INSTALL_CODEX_CLI; do
        eval "val=\${$var:-false}"
        # shellcheck disable=SC2154
        [ "$val" = "true" ] && HAS_TOOLS=true && break
    done

    if $HAS_TOOLS; then
        echo -e "▸ ${YELLOW}[7/7]${NC} Installing optional tools..."

        # Packages handled so far / that failed, space-delimited (guard against cycles
        # and repeats; a package that failed stays failed for every later dependent)
        DEB_SEEN=" "
        DEB_FAILED=" "

        # Helper: install a .deb after all of its dependencies, recursively.
        # Packages already listed as installed in the bootstrap's dpkg status are
        # neither extracted nor followed: the tool runs against the bootstrap's copies
        # of them, and the closure does not grow into base packages.
        # Fails if the package or any dependency could not be installed.
        install_with_deps() {
            local pkg="$1"
            case "$DEB_FAILED" in *" $pkg "*) return 1 ;; esac
            case "$DEB_SEEN" in *" $pkg "*) return 0 ;; esac
            DEB_SEEN="$DEB_SEEN$pkg "

            local filename
            filename=$(get_deb_filename "$pkg")
            if [ -z "$filename" ]; then
                DEB_FAILED="$DEB_FAILED$pkg "
                return 1
            fi

            local deps dep rc=0
            deps=$(get_deb_depends "$pkg")
            while IFS= read -r dep; do
                [ -z "$dep" ] && continue
                dpkg_has "$dep" && continue
                # Not in the index = virtual package: nothing to install
                [ -n "$(get_deb_filename "$dep")" ] || continue
                if ! install_with_deps "$dep"; then
                    echo -e "    ${YELLOW}[WARN]${NC} dependency $dep of $pkg could not be installed"
                    rc=1
                fi
            done <<< "$deps"

            install_deb "$filename" || rc=1
            [ "$rc" -eq 0 ] || DEB_FAILED="$DEB_FAILED$pkg "
            return $rc
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
rm -rf "$DEB_DIR" "$PKG_DIR" "$PACKAGES_FILE" "$OA_INRELEASE" "$OA_RELEASE_VERIFIED" "$TMPDIR/gpkg.db" "$TMPDIR/gpkg.db.sig" "$TMPDIR/gnupg" 2>/dev/null || true

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

# Turn off the gateway's "update available" notice: the OpenClaw version is pinned
# here, so the notice only points at an update that oa blocks. A value the user
# already set (true or false) is left alone. Same block as platforms/openclaw
# install.sh and update.sh.
if timeout 60 openclaw config get update.checkOnStart >/dev/null 2>&1; then
    echo -e "  ${GREEN}[SKIP]${NC} update.checkOnStart is already set"
elif timeout 60 openclaw config set update.checkOnStart false >/dev/null 2>&1; then
    echo -e "  ${GREEN}✓${NC} OpenClaw update notice turned off (update.checkOnStart=false)"
else
    echo -e "  ${YELLOW}[WARN]${NC} Could not turn off the OpenClaw update notice (non-critical)"
fi
echo ""
echo "  Starting OpenClaw onboard..."
echo ""
openclaw onboard
