#!/usr/bin/env bash
# release-verify.sh - check a release tree against its signed manifest (RELEASE.manifest + RELEASE.manifest.sig)
#
# This is the reference implementation of the check that clients make before they run a downloaded script
# (see release/README.md). It is also what the deploy step and the CI of the public repository run.
#
# Usage:
#   bash release/release-verify.sh --tree <dir> [--manifest <file>] [--sig <file> --keyring <file>] [--no-sig]
#                                  [--min-oa-version <X.Y.Z>] [--quiet]
#   defaults: --manifest <dir>/RELEASE.manifest, --sig <dir>/RELEASE.manifest.sig, --keyring <dir>/release/oa-release.gpg
#   --no-sig: check the manifest against the tree only (the signature does not exist yet: the deploy step before signing)
#
# The checks, in this order (the first one that fails ends the run):
#   sig       the signature of the manifest is good, made by a key of the keyring (gpgv)
#   grammar   the manifest has exactly the v1 form (see below), paths strictly increasing in LC_ALL=C order
#   name      every file and directory of the tree (outside android/) uses only [A-Za-z0-9._/@+-]; no "..", no empty
#             component
#   perm      every directory of the tree can be read (a directory that cannot be listed hides its files)
#   link      the tree holds no symbolic link, device or FIFO (outside android/; "android" itself has to be a directory
#             or it is a file in scope)
#   set       the files of the tree (outside android/, without the two manifest files) are exactly the manifest's paths
#   hash      every file has the sha256 of its manifest line
#   version   oa_version >= --min-oa-version (when given)
#
# Manifest v1 (LF lines only; later changes need a new file name, old clients reject unknown lines):
#   # openclaw-android release manifest v1
#   oa_version=<N.N.N>
#   issued=<YYYY-MM-DDTHH:MM:SSZ>
#   <64 hex>  <path>            one per file, paths strictly increasing (LC_ALL=C)
#
# Exit: 0 = verified   1 = verification failed (the line "FAIL <class>: <detail>" names the class)
#       2 = could not verify (a tool or a file is missing: "ERROR <class>: <detail>")
# Signature classes (the verdict numbers are those of _oa_gpgv in post-setup.sh):
#   sig-bad (1)  sig-nokey (2)  sig-clock (3)  sig-revoked (4, also: any other failure line)  sig-tool (10..12)
#
# Needs: bash, find, sort, diff, comm, sed, awk, grep, head, tail, od, tr, wc, cut, mktemp, sha256sum or shasum, and
# gpgv (only for the signature check). A missing tool ends with exit 2, never with a pass.

set -u
export LC_ALL=C
unset CDPATH

TREE=""; MANIFEST=""; SIG=""; KEYRING=""; NOSIG=0; MINV=""; QUIET=0
while [ $# -gt 0 ]; do
    case "$1" in
        --tree) TREE=${2:-}; shift 2 ;;
        --manifest) MANIFEST=${2:-}; shift 2 ;;
        --sig) SIG=${2:-}; shift 2 ;;
        --keyring) KEYRING=${2:-}; shift 2 ;;
        --no-sig) NOSIG=1; shift ;;
        --min-oa-version) MINV=${2:-}; shift 2 ;;
        --quiet) QUIET=1; shift ;;
        -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
        *) echo "ERROR usage: unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -n "$TREE" ] && [ -d "$TREE" ] || { echo "ERROR usage: --tree <dir> is required and has to be a directory" >&2; exit 2; }
TREE=$(cd "$TREE" && pwd)
[ -n "$MANIFEST" ] || MANIFEST=$TREE/RELEASE.manifest
[ -n "$SIG" ] || SIG=$TREE/RELEASE.manifest.sig
[ -n "$KEYRING" ] || KEYRING=$TREE/release/oa-release.gpg
# absolute paths: gpgv runs with a temporary home and the checks run inside the tree
CWD0=$(pwd)
case "$MANIFEST" in /*) ;; *) MANIFEST=$CWD0/$MANIFEST ;; esac
case "$SIG" in /*) ;; *) SIG=$CWD0/$SIG ;; esac
case "$KEYRING" in /*) ;; *) KEYRING=$CWD0/$KEYRING ;; esac
# a place for temporary files (Termux has no /tmp)
TMPBASE=${TMPDIR:-}
[ -n "$TMPBASE" ] || { [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/tmp" ] && TMPBASE=$PREFIX/tmp; }
[ -n "$TMPBASE" ] || TMPBASE=/tmp

say() { [ "$QUIET" = 1 ] || printf '%s\n' "$*"; }
# (the detail can hold text of the tree or of the manifest: one line, no control characters)
clean() { printf '%s' "$1" | LC_ALL=C tr -c '[:print:]' '?'; }
fail() { printf 'FAIL %s: %s\n' "$1" "$(clean "$2")"; exit 1; }
err() { printf 'ERROR %s: %s\n' "$1" "$(clean "$2")" >&2; exit 2; }

# a tool that is not there must end the run, not turn into an empty result that passes
for t in find sort diff comm sed awk grep head tail od tr wc cut mktemp; do
    command -v "$t" >/dev/null 2>&1 || err tool "$t is not available"
done

[ -f "$MANIFEST" ] || err manifest "no manifest at $MANIFEST"

# ---- hash tool: sha256sum first (the devices have it, shasum is not there), shasum -a 256 as the fallback
# (the list is given as a file argument: the macOS sha256sum does not read it from standard input)
if command -v sha256sum >/dev/null 2>&1; then
    HASHCHECK() { sha256sum -c "$1" 2>&1; }
elif command -v shasum >/dev/null 2>&1; then
    HASHCHECK() { shasum -a 256 -c "$1" 2>&1; }
else
    err tool "neither sha256sum nor shasum is available"
fi

# ---- 1. signature
if [ "$NOSIG" = 0 ]; then
    [ -f "$SIG" ] || err sig-tool "no signature file at $SIG"
    [ -s "$KEYRING" ] && [ -r "$KEYRING" ] || err sig-tool "keyring missing or unreadable: $KEYRING"
    GPGV=$(command -v gpgv 2>/dev/null) || err sig-tool "gpgv is not available"
    SH=$(mktemp -d "$TMPBASE/oa-rv.XXXXXX") || err sig-tool "cannot create a temporary directory"
    trap 'rm -rf "$SH"' EXIT
    chmod 700 "$SH"
    rc=0
    errtxt=$("$GPGV" --homedir "$SH" --keyring "$KEYRING" --status-fd 3 "$SIG" "$MANIFEST" 2>&1 3>"$SH/status") || rc=$?
    status="$SH/status"
    has() { grep -Eq "$1" "$status"; }
    # classification: only lines that concern the key that made the signature decide; a KEYEXPIRED line alone does
    # not (GnuPG prints it for expired subkeys that were not used, e.g. when the keyring holds one)
    if [ "$rc" -eq 126 ] || [ "$rc" -eq 127 ] || [ "$rc" -gt 128 ]; then
        err sig-tool "gpgv could not run (rc=$rc)"
    elif has '^\[GNUPG:\] BADSIG '; then
        fail sig-bad "the signature does not match the manifest"
    elif has '^\[GNUPG:\] REVKEYSIG '; then
        fail sig-revoked "the signing key is revoked"
    elif has '^\[GNUPG:\] (EXPKEYSIG|EXPSIG) '; then
        fail sig-clock "the signing key or the signature is expired (the device clock may be wrong)"
    elif ! { has '^\[GNUPG:\] GOODSIG ' && has '^\[GNUPG:\] VALIDSIG '; }; then
        if has '^\[GNUPG:\] NO_PUBKEY '; then
            if printf '%s\n' "$errtxt" | grep -Eq 'was created .* in the future'; then
                fail sig-clock "the signing key was created in the future (the device clock may be wrong)"
            fi
            fail sig-nokey "the signature was made by a key that is not in the keyring"
        fi
        fail sig-revoked "no good signature (gpgv: $(printf '%s' "$errtxt" | head -1 | cut -c1-120))"
    elif awk '$1 == "[GNUPG:]" && $2 == "ERRSIG" && $8 != 9 { f = 1 } END { exit !f }' "$status"; then
        fail sig-revoked "another signature on the file is damaged"
    fi
    say "ok sig: good signature (key ...$(sed -n 's/^\[GNUPG:\] VALIDSIG \([0-9A-F]*\) .*/\1/p' "$status" | head -1 | awk '{print substr($0, length($0)-7)}'))"
fi

# ---- 2. grammar
if [ "$(tail -c 1 "$MANIFEST" | od -An -c | tr -d ' ')" != '\n' ]; then fail grammar "the last line has no newline"; fi
if grep -q "$(printf '\r')" "$MANIFEST"; then fail grammar "a carriage return (LF lines only)"; fi
n=$(wc -l < "$MANIFEST" | tr -d ' ')
[ "$n" -ge 3 ] || fail grammar "fewer than the 3 header lines"
l1=$(sed -n 1p "$MANIFEST"); l2=$(sed -n 2p "$MANIFEST"); l3=$(sed -n 3p "$MANIFEST")
[ "$l1" = '# openclaw-android release manifest v1' ] || fail grammar "line 1 is not the v1 header"
printf '%s\n' "$l2" | grep -Eq '^oa_version=(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' || fail grammar "line 2 is not oa_version=N.N.N (no leading zeros)"
printf '%s\n' "$l3" | grep -Eq '^issued=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' || fail grammar "line 3 is not issued=YYYY-MM-DDTHH:MM:SSZ"
OAV=${l2#oa_version=}
body=$(mktemp "$TMPBASE/oa-rv-body.XXXXXX") || err tool "cannot create a temporary file"
paths=$(mktemp "$TMPBASE/oa-rv-paths.XXXXXX") || err tool "cannot create a temporary file"
trap 'rm -rf "${SH:-}" "$body" "$paths" "${tree_list:-}"' EXIT
sed -n '4,$p' "$MANIFEST" > "$body"
if [ -s "$body" ]; then
    if grep -Env '^[0-9a-f]{64}  [A-Za-z0-9._/@+-]+$' "$body" | head -1 | grep -q .; then
        bad=$(grep -Env '^[0-9a-f]{64}  [A-Za-z0-9._/@+-]+$' "$body" | head -1 | cut -c1-100)
        fail grammar "a line of the body has the wrong form (line $(( ${bad%%:*} + 3 )): ${bad#*:})"
    fi
fi
sed 's/^[0-9a-f]\{64\}  //' "$body" > "$paths"
[ -s "$paths" ] || fail grammar "the manifest lists no file"
if ! sort -c -u "$paths" 2>/dev/null; then fail grammar "paths are not strictly increasing in LC_ALL=C order (or a path is listed twice)"; fi

# ---- 3. names of the manifest and of the tree
while IFS= read -r p; do
    case "/$p/" in
        */../*|*//*|/./*|*/./*) fail name "bad path component in the manifest: $p" ;;
    esac
    case "$p" in
        /*) fail name "absolute path in the manifest: $p" ;;
        android/*|RELEASE.manifest|RELEASE.manifest.sig) fail name "path outside the manifest's scope: $p" ;;
    esac
done < "$paths"

cd "$TREE" || err tool "cannot enter $TREE"
# "android" is out of scope only when it is a directory; a file or a link of that name is in scope (and a link fails)
PRUNE=(-path ./android -type d -prune)
FERR=$(mktemp "$TMPBASE/oa-rv-ferr.XXXXXX") || err tool "cannot create a temporary file"
trap 'rm -rf "${SH:-}" "$body" "$body.l" "$paths" "${tree_list:-}" "${tree_list:-}.z" "$FERR"' EXIT
# non-regular entries: a symbolic link, device or FIFO
special=$(find . \( "${PRUNE[@]}" \) -o ! -type f ! -type d -print 2>"$FERR" | head -1)
[ -z "$special" ] || fail link "not a regular file or directory: $(printf '%s' "$special" | cut -c1-120)"
[ ! -s "$FERR" ] || fail perm "a directory cannot be read: $(head -1 "$FERR" | cut -c1-160)"
# a directory that cannot be entered hides its files from the listing below
unreadable=$(find . \( "${PRUNE[@]}" \) -o -type d ! -perm -u+rx -print 2>/dev/null | head -1)
[ -z "$unreadable" ] || fail perm "a directory cannot be read: $(printf '%s' "$unreadable" | cut -c1-120)"

tree_list=$(mktemp "$TMPBASE/oa-rv-tree.XXXXXX") || err tool "cannot create a temporary file"
# NUL-separated, so that a name with a newline cannot pass as two lines. Directories are listed too: their names
# are checked like the names of files (a directory with a newline in its name is still a bad name)
find . \( "${PRUNE[@]}" \) -o -print0 > "$tree_list.z" 2>"$FERR" || fail perm "the tree cannot be listed: $(head -1 "$FERR" | cut -c1-160)"
[ ! -s "$FERR" ] || fail perm "the tree cannot be listed: $(head -1 "$FERR" | cut -c1-160)"
badname=""
: > "$tree_list"
while IFS= read -r -d '' f; do
    [ "$f" = . ] && continue
    f=${f#./}
    case "$f" in
        *[!A-Za-z0-9._/@+-]*) badname=$f; break ;;
    esac
    case "/$f/" in
        */../*|*//*|*/./*) badname=$f; break ;;
    esac
    [ -d "./$f" ] && continue
    case "$f" in RELEASE.manifest|RELEASE.manifest.sig) continue ;; esac
    printf '%s\n' "$f" >> "$tree_list"
done < "$tree_list.z"
rm -f "$tree_list.z"
if [ -n "$badname" ]; then
    fail name "an entry of the tree has a character outside [A-Za-z0-9._/@+-]: $(printf '%s' "$badname" | LC_ALL=C tr -c 'A-Za-z0-9._/@+-' '?' | cut -c1-120)"
fi
LC_ALL=C sort -o "$tree_list" "$tree_list"

# ---- 4. set
if ! diff "$paths" "$tree_list" > /dev/null; then
    only_m=$(LC_ALL=C comm -23 "$paths" "$tree_list" | head -3 | tr '\n' ' ')
    only_t=$(LC_ALL=C comm -13 "$paths" "$tree_list" | head -3 | tr '\n' ' ')
    fail set "the tree and the manifest differ (only in the manifest: ${only_m:-none}; only in the tree: ${only_t:-none})"
fi

# ---- 5. hashes
# every path gets "./": a file named "-" would be read as standard input, one named "-x" as an option
sed 's#^\([0-9a-f]\{64\}\)  #\1  ./#' "$body" > "$body.l"
out=$(HASHCHECK "$body.l"); hrc=$?
rm -f "$body.l"
if [ "$hrc" -ne 0 ]; then
    first=$(printf '%s\n' "$out" | grep -v ': OK$' | head -3 | tr '\n' ' ')
    fail hash "a file does not match its hash: $(printf '%s' "$first" | cut -c1-200)"
fi

# ---- 6. version
if [ -n "$MINV" ]; then
    printf '%s\n' "$MINV" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' || err usage "--min-oa-version has to be N.N.N"
    lowest=$(printf '%s\n%s\n' "$OAV" "$MINV" | sort -t. -k1,1n -k2,2n -k3,3n | head -1)
    [ "$lowest" = "$MINV" ] || fail version "oa_version $OAV is lower than the installed $MINV (an older release)"
fi

say "ok release: $(wc -l < "$paths" | tr -d ' ') files, oa_version $OAV, $(printf '%s' "$l3")"
exit 0
