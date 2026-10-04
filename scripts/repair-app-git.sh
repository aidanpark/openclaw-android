#!/usr/bin/env bash
# repair-app-git.sh - Repair the App Install git wrapper (called by update-core.sh)
#
# Older post-setup.sh versions replaced $PREFIX/bin/git with a wrapper that ends in
# `exec libexec/git-core/git`. With the current Termux git package that path is a
# symlink back to bin/git, so the wrapper exec'd itself forever, and the real git
# binary had been deleted. This script puts the real binary back (downloading the
# git package again when it is gone) and installs the corrected wrapper.
#
# Only app installs are touched (they carry the git.wrapper-installed marker);
# on a Termux install this script does nothing.
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

: "${PREFIX:?PREFIX not set}"

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

TERMUX_DEB_REPO="https://packages-cf.termux.dev/apt/termux-main"

# Download the git package again and keep its binary as bin/git.real.
# The package index is not signed (yet): the .deb is checked against the sha256
# the index lists for it. Prints the reason when it fails.
fetch_real_git() {
    local work idx rel sha got bin
    work=$(mktemp -d "$PREFIX/tmp/oa-git.XXXXXX") || { echo "  Could not create a temporary directory."; return 1; }
    idx="$work/Packages"
    if ! curl -fsSL --max-time 60 -o "$idx" "$TERMUX_DEB_REPO/dists/stable/main/binary-aarch64/Packages"; then
        echo "  Could not download the package list. Check your network connection."
        rm -rf "$work"
        return 1
    fi
    rel=$(awk '/^Package: /{ found = ($2 == "git") } found && /^Filename:/{ print $2; exit }' "$idx")
    sha=$(awk '/^Package: /{ found = ($2 == "git") } found && /^SHA256:/{ print $2; exit }' "$idx")
    case "$rel" in
        *..*|*[!A-Za-z0-9._+/~-]*|"") rel="" ;;
        pool/*.deb) ;;
        *) rel="" ;;
    esac
    case "$sha" in
        *[!0-9a-f]*|"") sha="" ;;
    esac
    if [ -z "$rel" ] || [ "${#sha}" -ne 64 ]; then
        echo "  The package list has no usable entry for git."
        rm -rf "$work"
        return 1
    fi
    if ! curl -fsSL --max-time 300 -o "$work/git.deb" "$TERMUX_DEB_REPO/$rel"; then
        echo "  Could not download the git package. Check your network connection."
        rm -rf "$work"
        return 1
    fi
    got=$(sha256sum "$work/git.deb" | awk '{ print $1 }')
    if [ "$got" != "$sha" ]; then
        echo -e "  ${YELLOW}[WARN]${NC} The downloaded git package does not match the package list (sha256) — not used."
        rm -rf "$work"
        return 1
    fi
    mkdir -p "$work/x"
    if ! dpkg-deb -x "$work/git.deb" "$work/x" 2>/dev/null; then
        echo "  The git package could not be unpacked."
        rm -rf "$work"
        return 1
    fi
    bin="$work/x/data/data/com.termux/files/usr/bin/git"
    if [ ! -f "$bin" ] || [ -L "$bin" ] || git_is_wrapper "$bin"; then
        echo "  The git package does not contain a git binary."
        rm -rf "$work"
        return 1
    fi
    cp -p "$bin" "$GIT_REAL.tmp" && chmod +x "$GIT_REAL.tmp" && mv -f "$GIT_REAL.tmp" "$GIT_REAL" \
        || { echo "  Could not store the git binary (disk full?)."; rm -f "$GIT_REAL.tmp"; rm -rf "$work"; return 1; }
    rm -rf "$work"
    return 0
}

# Not an app install (no wrapper was ever installed): nothing to repair.
if [ ! -f "$GIT_MARKER" ]; then
    exit 0
fi
if git_wrapper_ok; then
    echo -e "${GREEN}[OK]${NC}   git wrapper is fine"
    exit 0
fi

# When the wrapper could not be put in place, an old wrapper that loops forever must not stay.
# Best: a git.real that runs becomes bin/git itself (git works, just without the wrapper).
# Otherwise: a stub that fails at once and says what to do. Only our own wrapper is replaced.
git_fallback() {
    local tmp="$GIT_BIN.fb.$$"
    git_is_wrapper "$GIT_BIN" || return 0
    if git_real_runs; then
        # a hard link needs no new data blocks, so this also works when the disk is full
        { ln -f "$GIT_REAL" "$tmp" 2>/dev/null || cp -p "$GIT_REAL" "$tmp"; } && chmod +x "$tmp" && mv -f "$tmp" "$GIT_BIN" || rm -f "$tmp"
    else
        {
            printf '#!%s/bin/bash\n' "$PREFIX"
            printf 'echo "git is not available: it could not be restored. Run oa --update to try again." >&2\n'
            printf 'exit 127\n'
        } > "$tmp" && chmod +x "$tmp" && mv -f "$tmp" "$GIT_BIN" || rm -f "$tmp"
    fi
}

echo "Repairing the git wrapper..."
mkdir -p "$PREFIX/tmp"
rc=0
fetched=false
# A kept git.real that does not run (a broken or half-written copy) is replaced by a fresh download.
if [ -f "$GIT_REAL" ] && ! git_real_runs; then
    echo "  The saved git binary does not run — downloading it again..."
    if ! fetch_real_git; then
        git_fallback
        echo -e "${RED}[FAIL]${NC} Could not restore git (see above). Run 'oa --update' again, or report this."
        exit 1
    fi
    fetched=true
fi
git_install_wrapper || rc=$?
if [ "$rc" -eq 2 ] && [ "$fetched" = false ]; then
    echo "  The real git binary is missing — downloading it again..."
    if fetch_real_git; then
        fetched=true
        rc=0
        git_install_wrapper || rc=$?
    else
        git_fallback
        echo -e "${RED}[FAIL]${NC} Could not restore git (see above). Run 'oa --update' again, or report this."
        exit 1
    fi
fi
if [ "$rc" -ne 0 ]; then
    git_fallback
    echo -e "${RED}[FAIL]${NC} The git wrapper could not be set up (git itself may still work without it). Run 'oa --update' again, or report this."
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   git repaired ($("$GIT_BIN" --version 2>/dev/null | head -1))"
