#!/usr/bin/env bash
# release-manifest.sh - write the release manifest of a tree (the file that is signed as RELEASE.manifest)
#
# Usage:
#   bash release/release-manifest.sh <tree> [--version <N.N.N>] [--issued <YYYY-MM-DDTHH:MM:SSZ>] > RELEASE.manifest
#
# <tree> is the exact tree that is published: the directory that `git archive <tree-hash>` was unpacked into (a
# worktree has a .git file and is not a tree to hash). The scope, the form and the order are those that
# release/release-verify.sh checks (see its header): every regular file of the tree except android/ and the two
# manifest files, paths in LC_ALL=C order. The version is OA_VERSION of scripts/lib.sh unless --version is given;
# --issued defaults to now (UTC).
#
# The manifest this writes is checked with release-verify.sh --no-sig before it is printed: a tree that the
# verifier would refuse (a link, a bad name) ends with exit 1 and prints nothing.
#
# Exit: 0 ok   1 the tree cannot be a release (the reason is on stderr)   2 a tool or an argument is missing

set -u
export LC_ALL=C
unset CDPATH

TREE=""; VERSION=""; ISSUED=""
while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION=${2:-}; shift 2 ;;
        --issued) ISSUED=${2:-}; shift 2 ;;
        -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
        -*) echo "release-manifest.sh: unknown option: $1" >&2; exit 2 ;;
        *) [ -z "$TREE" ] && TREE=$1 || { echo "release-manifest.sh: one tree only" >&2; exit 2; }; shift ;;
    esac
done
[ -n "$TREE" ] && [ -d "$TREE" ] || { echo "release-manifest.sh: <tree> is required and has to be a directory" >&2; exit 2; }
TREE=$(cd "$TREE" && pwd)
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
VERIFY="$HERE/release-verify.sh"
[ -f "$VERIFY" ] || { echo "release-manifest.sh: release-verify.sh is not next to this script" >&2; exit 2; }

if command -v sha256sum >/dev/null 2>&1; then
    HASHES() { xargs -0 sha256sum; }
elif command -v shasum >/dev/null 2>&1; then
    HASHES() { xargs -0 shasum -a 256; }
else
    echo "release-manifest.sh: neither sha256sum nor shasum is available" >&2; exit 2
fi

if [ -z "$VERSION" ]; then
    VERSION=$(sed -n 's/^OA_VERSION="\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)".*/\1/p' "$TREE/scripts/lib.sh" 2>/dev/null | head -1)
    [ -n "$VERSION" ] || { echo "release-manifest.sh: no OA_VERSION in scripts/lib.sh (give --version)" >&2; exit 1; }
fi
printf '%s\n' "$VERSION" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' || { echo "release-manifest.sh: version is not N.N.N: $VERSION" >&2; exit 2; }
[ -n "$ISSUED" ] || ISSUED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '%s\n' "$ISSUED" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' || { echo "release-manifest.sh: --issued is not YYYY-MM-DDTHH:MM:SSZ" >&2; exit 2; }

TMPBASE=${TMPDIR:-}
[ -n "$TMPBASE" ] || { [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/tmp" ] && TMPBASE=$PREFIX/tmp; }
[ -n "$TMPBASE" ] || TMPBASE=/tmp
OUT=$(mktemp "$TMPBASE/oa-rm.XXXXXX") || exit 2
trap 'rm -f "$OUT" "$OUT.list"' EXIT
{
    printf '# openclaw-android release manifest v1\n'
    printf 'oa_version=%s\n' "$VERSION"
    printf 'issued=%s\n' "$ISSUED"
} > "$OUT"

# a worktree or a clone is not a release tree (it has a .git of its own; unpack `git archive` instead)
if [ -e "$TREE/.git" ]; then
    echo "release-manifest.sh: $TREE has a .git entry: this is a worktree or a clone, not an unpacked release tree" >&2
    exit 1
fi
cd "$TREE" || exit 2
# NUL-separated list of the files in scope; a name with a newline or another bad character is refused below
find . \( -path ./android -type d -prune \) -o -type f ! -path ./RELEASE.manifest ! -path ./RELEASE.manifest.sig -print0 > "$OUT.list"
[ -s "$OUT.list" ] || { echo "release-manifest.sh: the tree holds no file in scope" >&2; exit 1; }
# the list sorted by name (sort -z); the names are checked by the verifier, which sees the finished manifest
LC_ALL=C sort -z "$OUT.list" | HASHES | sed -e 's#^\([0-9a-f]\{64\}\)  \./#\1  #' >> "$OUT"

# the verifier is the judge of the result: the same rules that the clients apply
if ! rc_out=$(bash "$VERIFY" --tree "$TREE" --manifest "$OUT" --no-sig --quiet 2>&1 < /dev/null); then
    echo "release-manifest.sh: the tree is not a valid release: $rc_out" >&2
    exit 1
fi
cat "$OUT"
