#!/usr/bin/env bash
# verify-openclaw-fit.sh - does a new OpenClaw version fit our patches and checks? (stage 0, before a pin bump)
#
# OpenClaw is pinned (platforms/openclaw/config.env). The pin may only be raised after the new version has been
# looked at; this script is the first, cheap look. It works on the npm tarball (plus the @openclaw/fs-safe package
# it depends on): nothing is installed and no code of the package is run, no device is needed.
#
#   G1  the hard-link patch script (platforms/openclaw/patches/openclaw-patch-hardlink.sh --check) fits: it exits
#       0 or 3, its last summary line says problems=0, and it reports no NOTARGET / NOMATCH / WRITEFAIL / incomplete.
#       A patch script that crashes or prints no summary is an ERROR (could not judge), not a PASS. So is a
#       broken judgment machinery of ours (canaries, marks in scripts/lib.sh): that is a defect of this repository
#   G2  oa_openclaw_incomplete (scripts/lib.sh) calls a fresh, good tree complete. The tree has no dependencies
#       installed, so the `--version` sign cannot be judged here (a stand-in answers it; stage 1 judges it). Two
#       small canary trees (one complete, one with a file missing from its inventory) have to be judged complete
#       and incomplete first, and a tree made of the candidate's own inventory without its files has to be judged
#       incomplete (the candidate's inventory is still in a form the function understands): the judgment
#       machinery itself is checked on every run. (The canaries' openclaw.mjs fails like a package without its
#       dependencies; if the function is ever changed to run a package file directly, the canaries need the same
#       execute permission and shebang a real one has.)
#   G0  facts against the baseline version: engines.node must contain the Node.js pin (else FAIL); everything
#       else is information or REVIEW (a person reads it; REVIEW does not change the exit code):
#       fs-safe version, dependencies (a dependency added or removed is a REVIEW), /proc/... strings (new ones, and
#       ones that more files read than before), counts of files with hard-coded /tmp, /bin/sh, /bin/bash,
#       /usr/bin/env, direct hard-link calls
#
# A PASS is NOT a go-ahead for the pin bump. This stage only recognizes the shapes we already know. A new kind
# of Android difference (the gateway lock's PID namespace of 2026.10.1 was one) shows up here, at best, as a
# REVIEW line. Stage 1 (gateway, doctor, backup under the Android trap) and the QA device runs decide.
#
# Usage:
#   bash tests/verify-openclaw-fit.sh <version|tag> [<baseline version|tag>]
#   bash tests/verify-openclaw-fit.sh latest            # the newest release against the pin
#   bash tests/verify-openclaw-fit.sh 2026.10.2 2026.9.8
#   bash tests/verify-openclaw-fit.sh --tree <dir> [<baseline version|tag> | --baseline-tree <dir>]   # offline
#   baseline default: the pin in platforms/openclaw/config.env
#   A version is a full X.Y.Z[-pre] or a tag (latest, beta, ...); a partial one such as 2026.10 is resolved by npm.
# Needs: bash, npm, node, tar, awk, network (for the npm registry; not for --tree with --baseline-tree).
# Exit: 0 = no judged item failed   1 = a judged item failed (G1, G2, engines.node)
#       2 = could not judge (bad arguments, no network, a patch script that crashed, a broken canary, ...)
# Environment: OA_TEST_ROOT (tree to test, default: this repository) · OA_FIT_CACHE (default
#   ${TMPDIR:-/tmp}/oa-openclaw-fit; unpacked packages and npm's own cache are kept there, delete it when it is
#   not wanted)
# Writes only under that cache directory and a temporary directory that is removed on exit (the patch script's own
# temporary files go there too; npm's logs and compile cache are switched off). Whatever is not a plain file or a
# directory (links, FIFOs, devices) is removed from the downloaded trees before anything is looked at, and a
# tarball that holds hard-linked files is refused (npm drops such entries; after unpacking they cannot be told).
# A cached tree is used again only when its mark (number of files and their total size under package/) still
# matches and it holds nothing but plain files and directories; damage that keeps both numbers is not seen.
# Concurrent runs are fine; the downloaded trees are put in place whole or not at all.
# An interrupted run can leave a .part.* directory in the cache: delete it by hand if it is in the way (the
# cleaning of the operating system's temporary directory removes it too).

set -uo pipefail
unset CDPATH

ROOT="${OA_TEST_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
CACHE="${OA_FIT_CACHE:-${TMPDIR:-/tmp}/oa-openclaw-fit}"

if [ -t 1 ]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; NC=''
fi

usage() {
    awk '/^# Usage:/{f=1} f && /^# Writes only/{print substr($0, 3); exit} f{print substr($0, 3)}' "${BASH_SOURCE[0]}"
}
die() { echo "$*" >&2; exit 2; }
# package data is shown only after control characters are gone and workflow-command openers are broken up
clean() { printf '%s' "$1" | tr '\n\r\t' '   ' | tr -d '\000-\010\013\014\016-\037\177' | sed 's/##\[/# #[/g; s/::/: :/g'; }

CAND=""
BASE=""
CAND_TREE=""
BASE_TREE=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --tree) [ $# -ge 2 ] || die "--tree needs a directory"; CAND_TREE="$2"; shift 2 ;;
        --baseline-tree) [ $# -ge 2 ] || die "--baseline-tree needs a directory"; BASE_TREE="$2"; shift 2 ;;
        -*) die "unknown option: $1" ;;
        *) if [ -z "$CAND" ]; then CAND="$1"; elif [ -z "$BASE" ]; then BASE="$1"; else die "too many arguments"; fi; shift ;;
    esac
done
# with --tree the one positional argument is the baseline
if [ -n "$CAND_TREE" ] && [ -n "$CAND" ]; then
    [ -z "$BASE" ] || die "too many arguments"
    BASE="$CAND"
    CAND=""
fi
if [ -z "$CAND" ] && [ -z "$CAND_TREE" ]; then
    usage >&2
    exit 2
fi
[ -z "$BASE_TREE" ] || [ -z "$BASE" ] || die "give a baseline version or --baseline-tree, not both"

CFG="$ROOT/platforms/openclaw/config.env"
PATCH="$ROOT/platforms/openclaw/patches/openclaw-patch-hardlink.sh"
LIB="$ROOT/scripts/lib.sh"
for f in "$CFG" "$PATCH" "$LIB"; do
    [ -f "$f" ] || die "not found: $f"
done
for t in node tar awk; do
    command -v "$t" >/dev/null 2>&1 || die "$t not found"
done

PIN=$(sed -n 's/^PLATFORM_NPM_PACKAGE_VERSION="\(.*\)"/\1/p' "$CFG" | head -1)
NODE_PIN=$(sed -n 's/^PLATFORM_NODE_VERSION="\(.*\)"/\1/p' "$CFG" | head -1)
[ -n "$PIN" ] && [ -n "$NODE_PIN" ] || die "the pins are not in $CFG"

case "$CACHE" in *"
"*) die "the cache path has a line break in it" ;; esac
mkdir -p "$CACHE" || die "cannot create $CACHE"
CACHE=$(cd "$CACHE" && pwd) || die "cannot enter $CACHE"
case "$CACHE" in *"
"*) die "the cache path has a line break in it" ;; esac
WORK=$(mktemp -d "${TMPDIR:-/tmp}/oa-openclaw-fit.XXXXXX") || die "cannot create a temporary directory"
WORK=$(cd "$WORK" && pwd) || die "cannot enter the temporary directory"
FETCH_TMP=""
# a tree with unreadable directories (mode 0111 in a tarball) cannot be removed before it is made writable
# (a link given as the operand is left alone; chmod -R does not follow links found inside a tree. No -P: GNU
# coreutils up to 9.4 do not know it. A directory that stays closed is caught by unpack_clean.)
fixperm() { [ -L "$1" ] || chmod -R u+rwX "$1" 2>/dev/null || true; }
cleanup() {
    fixperm "$WORK"
    rm -rf -- "${WORK:?}"
    if [ -n "$FETCH_TMP" ]; then
        fixperm "$FETCH_TMP"
        rm -rf -- "$FETCH_TMP"
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# npm keeps its cache under the cache directory too (nothing is written in ~/.npm; no logs; no compile cache).
# Its error output goes to $WORK/npm.err and is shown when a fetch fails.
NPM_ERR="$WORK/npm.err"
npmq() {
    NODE_DISABLE_COMPILE_CACHE=1 npm "$@" --cache "$CACHE/.npm-cache" --logs-max=0 --no-update-notifier --no-fund --no-audit 2>>"$NPM_ERR"
}
npm_why() { tail -n 2 "$NPM_ERR" 2>/dev/null | tr '\n' ' '; }

# resolve <version|tag>: prints a full version. Only plain words are passed to the registry.
resolve() {
    local spec="$1"
    case "$spec" in
        *[!0-9A-Za-z._-]*|''|.*|-*) return 1 ;;
    esac
    if printf '%s\n' "$spec" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'; then
        printf '%s\n' "$spec"
        return 0
    fi
    command -v npm >/dev/null 2>&1 || return 1
    # a tag or a partial version: `version --json` is a string, or a list (in publication order) for a partial
    # version: the highest release is taken (a pre-release only when there is no release)
    npmq view "openclaw@$spec" version --json | node -e '
        let s = ""; process.stdin.on("data", (c) => { s += c; }).on("end", () => {
            try {
                let v = JSON.parse(s);
                if (!Array.isArray(v)) v = [v];
                let ok = v.filter((x) => typeof x === "string" && /^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$/.test(x));
                if (ok.some((x) => !x.includes("-"))) ok = ok.filter((x) => !x.includes("-"));
                const key = (x) => x.split("-")[0].split(".").map(Number);
                ok.sort((a, b) => { const ka = key(a), kb = key(b); for (let i = 0; i < 3; i++) if (ka[i] !== kb[i]) return ka[i] - kb[i]; return 0; });
                if (ok.length) console.log(ok[ok.length - 1]);
            } catch (e) { /* nothing */ }
        });' | head -1
}

# fetch <full version>: sets FETCHED to the package tree (package/ with fs-safe under node_modules), FETCH_WHY to
# the reason when it fails. It is not run in a subshell: the temporary directory is removed by the exit trap.
FETCHED=""
FETCH_WHY=""
# tree_sig <dir>: "<files> <bytes>" of <dir>/package (the number the completion mark holds). Only package/ is
# looked at: a racing run may have left a .part directory next to it, and that must not change the number.
tree_sig() {
    find "$1/package" -type f -exec wc -c {} + 2>/dev/null | awk '$2 != "total" { n++; b += $1 } END { printf "%d %.0f\n", n, b }'
}
# a cached tree that has anything but plain files and directories in it is not used (it may have been written to)
tree_plain() { [ -z "$(find "$1" ! -type f ! -type d 2>/dev/null | head -1)" ]; }
# unpack_clean <dir>: only plain files and directories stay (npm drops links; a tar keeps links, FIFOs, devices:
# a FIFO would stop every read for good). Hard-linked files cannot be told from the original: refuse them.
unpack_clean() {
    fixperm "$1"
    if [ -n "$(find "$1" -type d ! -perm -u+rwx 2>/dev/null | head -1)" ]; then
        FETCH_WHY="a directory of the tarball could not be made accessible (chmod)"
        return 1
    fi
    find "$1" ! -type f ! -type d -exec rm -f {} + 2>/dev/null
    if [ -n "$(find "$1" -type f -links +1 2>/dev/null | head -1)" ]; then
        FETCH_WHY="the tarball holds hard-linked files"
        return 1
    fi
    return 0
}
fetch() {
    local v="$1" d fsv tgz old n
    FETCH_WHY=""
    d="$CACHE/$v"
    # a link in place of the cache entry is never followed (nor is its content judged): it is removed
    if [ -L "$d" ]; then rm -f -- "$d"; fi
    # a cached tree is used only whole: the completion mark holds its number of files and their size
    if [ -f "$d/.oa-fit-complete" ] && [ ! -L "$d/package" ] && [ -f "$d/package/package.json" ] && [ ! -L "$d/package/package.json" ] \
        && [ "$(cat "$d/.oa-fit-complete" 2>/dev/null)" = "$(tree_sig "$d")" ] && tree_plain "$d"; then
        FETCHED="$d/package"
        return 0
    fi
    command -v npm >/dev/null 2>&1 || { FETCH_WHY="npm not found"; return 1; }
    FETCH_TMP=$(mktemp -d "$CACHE/.part.XXXXXX") || { FETCH_WHY="no temporary directory"; return 1; }
    tgz=$(cd "$FETCH_TMP" && npmq pack "openclaw@$v" --silent --ignore-scripts | tail -1)
    [ -n "$tgz" ] && [ -f "$FETCH_TMP/$tgz" ] || { FETCH_WHY="npm pack of openclaw@$v failed"; return 1; }
    (cd "$FETCH_TMP" && tar xzf "$tgz" && rm -f -- "$tgz") || { FETCH_WHY="the tarball could not be unpacked"; return 1; }
    unpack_clean "$FETCH_TMP" || return 1
    [ -f "$FETCH_TMP/package/package.json" ] && [ ! -L "$FETCH_TMP/package/package.json" ] \
        || { FETCH_WHY="the tarball has no package/package.json"; return 1; }
    # the version string of fs-safe is read as data and has to be a plain version (no range, git or file spec)
    fsv=$(node -e 'try{const p=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));const v=(p.dependencies||{})["@openclaw/fs-safe"]||"";console.log(v)}catch(e){console.log("")}' "$FETCH_TMP/package/package.json" 2>/dev/null)
    if [ -n "$fsv" ]; then
        printf '%s\n' "$fsv" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' \
            || { FETCH_WHY="@openclaw/fs-safe is given as \"$(clean "$fsv")\", not as one exact version"; return 1; }
        mkdir -p "$FETCH_TMP/fs" || return 1
        tgz=$(cd "$FETCH_TMP/fs" && npmq pack "@openclaw/fs-safe@$fsv" --silent --ignore-scripts | tail -1)
        [ -n "$tgz" ] && [ -f "$FETCH_TMP/fs/$tgz" ] || { FETCH_WHY="npm pack of @openclaw/fs-safe@$fsv failed"; return 1; }
        (cd "$FETCH_TMP/fs" && tar xzf "$tgz") || { FETCH_WHY="the fs-safe tarball could not be unpacked"; return 1; }
        unpack_clean "$FETCH_TMP/fs" || return 1
        [ -f "$FETCH_TMP/fs/package/package.json" ] || { FETCH_WHY="the fs-safe tarball has no package/package.json"; return 1; }
        mkdir -p "$FETCH_TMP/package/node_modules/@openclaw" || return 1
        mv "$FETCH_TMP/fs/package" "$FETCH_TMP/package/node_modules/@openclaw/fs-safe" || return 1
        rm -rf -- "${FETCH_TMP:?}/fs"
    fi
    n=$(tree_sig "$FETCH_TMP")
    printf '%s\n' "$n" > "$FETCH_TMP/.oa-fit-complete"
    # put it in place whole: another run may have been faster (then its tree is used and ours is dropped)
    if [ -e "$d" ] || [ -L "$d" ]; then
        if [ -L "$d" ]; then
            rm -f -- "$d"
        elif [ "$(cat "$d/.oa-fit-complete" 2>/dev/null)" != "$(tree_sig "$d")" ] || ! tree_plain "$d"; then
            old="$d.old.$$"
            fixperm "$d"
            mv "$d" "$old" 2>/dev/null && { fixperm "$old"; rm -rf -- "$old"; }
        fi
    fi
    if [ ! -f "$d/.oa-fit-complete" ]; then
        mv "$FETCH_TMP" "$d" 2>/dev/null || true
    fi
    [ -f "$d/.oa-fit-complete" ] && [ -f "$d/package/package.json" ] || { FETCH_WHY="could not put the tree in place"; return 1; }
    # (our directory may have been moved into the other run's tree by that mv, or be still here)
    rm -rf -- "${d:?}/${FETCH_TMP##*/}" "$FETCH_TMP"
    FETCH_TMP=""
    FETCHED="$d/package"
}

# a package tree given by --tree: absolute path, a real package.json that is not a symlink
tree_arg() {
    local dir="$1" abs
    [ -d "$dir" ] || return 1
    abs=$(cd "$dir" && pwd) || return 1
    case "$abs" in *"
"*) return 1 ;; esac
    [ -f "$abs/package.json" ] && [ ! -L "$abs/package.json" ] || return 1
    printf '%s\n' "$abs"
}

# a package.json that is not valid JSON is "could not judge"
pkg_version() { # <tree> -> prints the version, fails when package.json is not readable JSON
    node -e 'try{const v=JSON.parse(require("fs").readFileSync(process.argv[1]+"/package.json","utf8")).version;if(typeof v!=="string")process.exit(1);console.log(v)}catch(e){process.exit(1)}' "$1" 2>/dev/null
}

if [ -n "$CAND_TREE" ]; then
    NEW=$(tree_arg "$CAND_TREE") || die "not a package tree (no real package.json, or a line break in its path): $CAND_TREE"
else
    CAND_V=$(resolve "$CAND") || true
    [ -n "$CAND_V" ] || die "cannot resolve $CAND"
    fetch "$CAND_V" || die "cannot fetch openclaw@$CAND_V: ${FETCH_WHY:-failed} $(npm_why)"
    NEW="$FETCHED"
fi
if [ -n "$BASE_TREE" ]; then
    OLD=$(tree_arg "$BASE_TREE") || die "not a package tree (no real package.json, or a line break in its path): $BASE_TREE"
else
    BASE_V=$(resolve "${BASE:-$PIN}") || true
    [ -n "$BASE_V" ] || die "cannot resolve ${BASE:-$PIN}"
    fetch "$BASE_V" || die "cannot fetch openclaw@$BASE_V: ${FETCH_WHY:-failed} $(npm_why)"
    OLD="$FETCHED"
fi
VER=$(pkg_version "$NEW") || die "package.json of the candidate is not readable: $NEW"
OLDV=$(pkg_version "$OLD") || die "package.json of the baseline is not readable: $OLD"

PASS=0; FAILED=0; REVIEWS=0; ERRORS=0
# package data is shown only after control characters are gone and workflow-command openers are broken up

line() { # <status> <label> <text>   (control characters of package data are not passed on)
    local st="$1" colour text
    text=$(clean "$3")
    case "$st" in
        PASS) colour="$GREEN"; PASS=$((PASS+1)) ;;
        FAIL) colour="$RED"; FAILED=$((FAILED+1)) ;;
        REVIEW) colour="$YELLOW"; REVIEWS=$((REVIEWS+1)) ;;
        ERROR) colour="$RED"; ERRORS=$((ERRORS+1)) ;;
        *) colour=""; st="INFO" ;;
    esac
    printf '%b[%s]%b %s: %s\n' "$colour" "$st" "$NC" "$2" "$text"
}

echo "=== OpenClaw $(clean "$VER") against $(clean "$OLDV") (baseline) - Node.js pin $NODE_PIN, OpenClaw pin $PIN ==="
echo "    tree: $ROOT"
[ "$VER" != "$PIN" ] || echo "    (the candidate is the current pin)"
echo ""

# ---- G1: the patch script on the candidate (it only reads: --check)
# A HOME that looks like an install: the completeness function looks for the patch script at its installed place.
mkdir -p "$WORK/home/.openclaw-android/platforms/openclaw/patches" "$WORK/bin" || exit 2
cp "$PATCH" "$WORK/home/.openclaw-android/platforms/openclaw/patches/" || exit 2
g1=$(HOME="$WORK/home" TMPDIR="$WORK" bash "$PATCH" --check "$NEW" 2>&1)
g1rc=$?
g1=$(printf '%s\n' "$g1" | sed 's/\x1b\[[0-9;]*m//g')
g1sum=$(printf '%s\n' "$g1" | grep -E 'Hard-link fallback.*todo=[0-9]+ already=[0-9]+ problems=[0-9]+' | tail -1 \
    | grep -oE 'todo=[0-9]+ already=[0-9]+ problems=[0-9]+')
g1bad=$(printf '%s\n' "$g1" | grep -cE '(NOTARGET|NOMATCH|WRITEFAIL|Hard-link fallback incomplete)')
show() { while IFS= read -r l; do printf '        | %s\n' "$(clean "$l")"; done; }
if [ -z "$g1sum" ]; then
    line ERROR "G1 patch fits" "no summary line from the patch script (exit $g1rc): could not judge"
    printf '%s\n' "$g1" | head -5 | show
elif [ "$g1rc" -ne 0 ] && [ "$g1rc" -ne 3 ]; then
    line ERROR "G1 patch fits" "the patch script exited $g1rc ($g1sum): could not judge"
    printf '%s\n' "$g1" | tail -4 | show
elif [ "${g1sum##*problems=}" = 0 ] && [ "$g1bad" -eq 0 ]; then
    line PASS "G1 patch fits" "$g1sum"
else
    line FAIL "G1 patch fits" "$g1sum"
    printf '%s\n' "$g1" | grep -E '(NOTARGET|NOMATCH|WRITEFAIL|incomplete)' | head -6 | show
fi

# ---- G2: completeness. Stand-ins in front of PATH for these calls only: `node <tree>/openclaw.mjs --version`
# cannot run without the dependencies (any call that names an openclaw.mjs is answered, whatever its flags), and a
# macOS host has no `timeout` (the stand-in skips timeout's own options and the duration).
REAL_NODE=$(command -v node)
{
    echo '#!/bin/sh'
    echo 'for a in "$@"; do'
    echo '    case "$a" in */openclaw.mjs|openclaw.mjs) echo "OpenClaw (stand-in)"; exit 0 ;; esac'
    echo 'done'
    printf 'exec "%s" "$@"\n' "$REAL_NODE"
} > "$WORK/bin/node"
cat > "$WORK/bin/timeout" << 'TIMEOUT_SH'
#!/bin/sh
while [ $# -gt 0 ]; do
    case "$1" in
        -k|-s|--kill-after|--signal) shift 2 ;;
        -*) shift ;;
        *) break ;;
    esac
done
shift
exec "$@"
TIMEOUT_SH
chmod +x "$WORK/bin/node" "$WORK/bin/timeout"
# the function, between its marks (each exactly once)
marks_start=$(grep -c -e '# ─── openclaw complete check ───' "$LIB")
marks_end=$(grep -c -e '# ─── end openclaw complete check ───' "$LIB")
if [ "$marks_start" -ne 1 ] || [ "$marks_end" -ne 1 ]; then
    line ERROR "G2 completeness" "the complete-check marks are not in scripts/lib.sh exactly once each (the checks of pre-commit would fail too): could not judge"
else
    awk '/# ─── openclaw complete check ───/{f=1} f{print} /# ─── end openclaw complete check ───/{exit}' "$LIB" > "$WORK/complete-check.sh"
    # judge <dir>: prints the function's return value ("rc=0" incomplete, "rc=1" complete), or another word
    judge() {
        HOME="$WORK/home" PATH="$WORK/bin:$PATH" bash -c '
            . "$1" || { echo "rc=error"; exit 0; }
            oa_openclaw_incomplete "$2" fresh; echo "rc=$?"' _ "$WORK/complete-check.sh" "$1" 2>&1 | tail -1
    }
    # canaries. Their openclaw.mjs fails like a package without its dependencies would: a call that gets past the
    # stand-in node cannot make a tree look complete.
    mkdir -p "$WORK/canary-good/dist" "$WORK/canary-bad/dist" "$WORK/canary-own/dist" || exit 2
    for c in canary-good canary-bad canary-own; do
        echo 'console.error("ERR_MODULE_NOT_FOUND (canary)"); process.exit(1);' > "$WORK/$c/openclaw.mjs"
    done
    echo '{"name":"openclaw","version":"0.0.0"}' > "$WORK/canary-good/package.json"
    echo '{"name":"openclaw","version":"0.0.0"}' > "$WORK/canary-bad/package.json"
    echo '[]' > "$WORK/canary-good/dist/postinstall-content-inventory.json"
    echo '[{"path":"dist/not-there.js"}]' > "$WORK/canary-bad/dist/postinstall-content-inventory.json"
    cg=$(judge "$WORK/canary-good")
    cb=$(judge "$WORK/canary-bad")
    if [ "$cg" != "rc=1" ] || [ "$cb" != "rc=0" ]; then
        line ERROR "G2 completeness" "the judgment itself does not work (canaries: good tree $cg, tree with a missing file $cb; expected rc=1 and rc=0): could not judge"
    else
        g2=$(judge "$NEW")
        # the candidate's own inventory and package.json, without its files: has to be incomplete, or the function
        # no longer understands the candidate's inventory (sign 3 would be silent on a device)
        own=skip
        if [ -f "$NEW/dist/postinstall-content-inventory.json" ] && [ ! -L "$NEW/dist/postinstall-content-inventory.json" ]; then
            entries=$(node -e 'try{const l=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log(Array.isArray(l)?l.length:"x")}catch(e){console.log("x")}' "$NEW/dist/postinstall-content-inventory.json" 2>/dev/null)
            if [ "$entries" != "0" ]; then
                # the candidate's package.json without its dependencies: only the inventory may make this tree
                # incomplete (a dependency on fs-safe would, whatever form the inventory has)
                node -e 'const fs=require("fs");const p=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));delete p.dependencies;fs.writeFileSync(process.argv[2],JSON.stringify(p))' \
                    "$NEW/package.json" "$WORK/canary-own/package.json" 2>/dev/null
                cp "$NEW/dist/postinstall-content-inventory.json" "$WORK/canary-own/dist/"
                own=$(judge "$WORK/canary-own")
            fi
        fi
        case "$g2" in
            rc=1) : ;;
            rc=0) line FAIL "G2 completeness" "judged incomplete (a fresh install of a complete package has to be judged complete)"; g2=handled ;;
            *) line ERROR "G2 completeness" "could not run ($g2)"; g2=handled ;;
        esac
        if [ "$g2" = rc=1 ]; then
            if [ "$own" = "rc=1" ]; then
                line FAIL "G2 completeness" "complete, but the candidate's own inventory with no files was not judged incomplete: its form is not understood (sign 3 would be silent)"
            else
                if [ "$own" = skip ]; then
                    line PASS "G2 completeness" "complete (canaries ok; no inventory to check in the candidate)"
                else
                    line PASS "G2 completeness" "complete (canaries ok; the candidate's inventory is understood)"
                fi
            fi
        fi
    fi
fi

# ---- G0: facts and the difference to the baseline (one Node program; prints STATUS<TAB>label<TAB>text)
cat > "$WORK/facts.js" << 'FACTS_JS'
const fs = require('fs');
const path = require('path');
const [oldDir, newDir, nodePin] = process.argv.slice(2);
const clean = (s) => String(s).replace(/[\x00-\x1f\x7f]/g, ' ');

function facts(dir) {
  const pj = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
  const pats = {
    tmp: /["'`]\/tmp[\/"'`]/,
    bin_sh: /["'`]\/bin\/sh["'`]/,
    bin_bash: /["'`]\/bin\/bash["'`]/,
    usr_bin_env: /["'`]\/usr\/bin\/env["'`]/,
    link_call: /\b(?:linkSync|fs\.link|fsp\.link|linkat)\b/,
    link_required: /link-required/,
  };
  const counts = {};
  Object.keys(pats).forEach((k) => { counts[k] = 0; });
  const proc = {}; // string -> set of files (relative path)
  const distRoot = path.join(dir, 'dist');
  const walk = (d) => {
    let ents;
    try { ents = fs.readdirSync(d, { withFileTypes: true }); } catch (e) { return; }
    ents.forEach((e) => {
      const p = path.join(d, e.name);
      if (e.isSymbolicLink()) return;
      if (e.isDirectory()) { walk(p); return; }
      if (!/\.(m?js|cjs)$/.test(e.name)) return;
      const text = fs.readFileSync(p, 'latin1');
      Object.keys(pats).forEach((k) => { if (pats[k].test(text)) counts[k]++; });
      const key = path.relative(distRoot, p);
      (text.match(/\/proc\/[A-Za-z0-9_.\/${}\-]*/g) || []).forEach((m) => {
        // minified bundles rename the variables inside ${...}: compare the shape only
        const s = m.replace(/\$\{[^}]*\}/g, '${}');
        (proc[s] = proc[s] || new Set()).add(key);
      });
    });
  };
  walk(distRoot);
  let fsSafe = null;
  try {
    fsSafe = JSON.parse(fs.readFileSync(path.join(dir, 'node_modules', '@openclaw', 'fs-safe', 'package.json'), 'utf8')).version;
  } catch (e) { /* not there */ }
  return {
    version: pj.version,
    engines: (pj.engines || {}).node || '',
    deps: pj.dependencies || {},
    counts,
    proc,
    fsSafe,
    inventory: fs.existsSync(path.join(distRoot, 'postinstall-content-inventory.json')),
  };
}

// A range check for comparators ">=X", "<X", "X.Y", "24.x" ... joined by spaces, alternatives by "||".
// Anything else (^ ~ - ranges, pre-releases) is "cannot parse" (null), which is a REVIEW, never a PASS.
function cmp(a, b) {
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1;
  return 0;
}
// "24", "24.21", "24.x", "*" stand for the half-open range [lo, hi); a full X.Y.Z is exact
function partial(text) {
  const parts = text.split('.');
  if (parts.length > 3) return null;
  const n = [];
  for (const p of parts) {
    if (p === 'x' || p === 'X' || p === '*') break;
    if (!/^(0|[1-9][0-9]*)$/.test(p)) return null;
    n.push(Number(p));
  }
  const given = n.length;
  if (given === 3) return { lo: n, hi: null, exact: true };
  const lo = n.concat([0, 0, 0]).slice(0, 3);
  if (given === 0) return { lo, hi: [Infinity, 0, 0], exact: false };
  const hi = lo.slice();
  hi[given - 1] += 1;
  for (let i = given; i < 3; i++) hi[i] = 0;
  return { lo, hi, exact: false };
}
function satisfied(range, v) {
  for (const alt of range.split('||')) {
    const comps = alt.trim().split(/\s+/);
    if (comps.length === 1 && comps[0] === '') return null;
    let ok = true;
    for (const c of comps) {
      const m = /^(>=|<=|>|<|=)?(\d+(?:\.(?:\d+|x|X|\*)){0,2}|\*|x|X)$/.exec(c);
      if (!m) return null;
      const p = partial(m[2]);
      if (!p) return null;
      const op = m[1] || '=';
      let r;
      if (p.exact) {
        const e = cmp(v, p.lo);
        r = { '=': e === 0, '>=': e >= 0, '<=': e <= 0, '>': e > 0, '<': e < 0 }[op];
      } else {
        const inLo = cmp(v, p.lo) >= 0;
        const belowHi = cmp(v, p.hi) < 0;
        r = { '=': inLo && belowHi, '>=': inLo, '>': !belowHi, '<': !inLo, '<=': belowHi }[op];
      }
      ok = ok && r;
    }
    if (ok) return true;
  }
  return false;
}
const vparts = (s) => (String(s).match(/\d+/g) || []).slice(0, 3).map(Number);

const a = facts(oldDir);
const b = facts(newDir);
const out = (st, label, text) => console.log(st + '\t' + clean(label) + '\t' + clean(text));
let fail = 0;

if (!b.engines) {
  out('REVIEW', 'G0 engines.node', 'the package declares no engines.node');
} else {
  const r = satisfied(b.engines, vparts(nodePin));
  if (r === true) out('PASS', 'G0 engines.node', b.engines + ' contains Node.js ' + nodePin);
  else if (r === false) { out('FAIL', 'G0 engines.node', b.engines + ' does not contain Node.js ' + nodePin); fail = 1; }
  else out('REVIEW', 'G0 engines.node', 'cannot parse "' + b.engines + '"');
  if (a.engines && a.engines !== b.engines) out('REVIEW', 'G0 engines.node changed', a.engines + ' -> ' + b.engines);
}

if (a.fsSafe !== b.fsSafe) {
  out('REVIEW', 'G0 fs-safe', (a.fsSafe || 'absent') + ' -> ' + (b.fsSafe || 'absent') + ' (F1/F1c patch its fallback code)');
} else {
  out('INFO', 'G0 fs-safe', b.fsSafe || 'absent');
}

// @openclaw/ai moves with the OpenClaw version itself: it is left out only while it does
const follows = (t) => t.deps['@openclaw/ai'] === t.version;
const names = Array.from(new Set(Object.keys(a.deps).concat(Object.keys(b.deps)))).sort();
const skip = (k) => k === '@openclaw/ai' && follows(a) && follows(b);
const changed = names.filter((k) => !skip(k) && a.deps[k] !== b.deps[k])
  .map((k) => k + ' ' + (a.deps[k] || '-') + ' -> ' + (b.deps[k] || '-'));
const addedDeps = names.filter((k) => !skip(k) && !a.deps[k] && b.deps[k]);
const removedDeps = names.filter((k) => !skip(k) && a.deps[k] && !b.deps[k]);
const note = [];
if (addedDeps.length) note.push('added: ' + addedDeps.join(', '));
if (removedDeps.length) note.push('removed: ' + removedDeps.join(', '));
out(changed.length >= 6 || addedDeps.length || removedDeps.length ? 'REVIEW' : 'INFO', 'G0 dependencies changed',
  changed.length + (note.length ? ' (' + note.join('; ') + ')' : '') +
  (changed.length ? ': ' + changed.slice(0, 6).join('; ') + (changed.length > 6 ? ' ...' : '') : ''));

// a /proc string is a finding when it is new or when more files read it than before (the gateway lock's pid
// namespace was named in one file of 2026.9.x and is read in nine files of 2026.10). Files are counted by their
// path as it is: only the number of files is compared, so a changed bundle hash does not matter.
const added = Object.keys(b.proc).filter((k) => !a.proc[k]).sort();
const grown = Object.keys(b.proc).filter((k) => a.proc[k] && b.proc[k].size > a.proc[k].size).sort();
if (added.length || grown.length) {
  const parts = [];
  if (added.length) parts.push(added.length + ' new: ' + added.join(', '));
  if (grown.length) parts.push(grown.length + ' read by more files: ' + grown.map((k) => k + ' ' + a.proc[k].size + '->' + b.proc[k].size).join(', '));
  out('REVIEW', 'G0 /proc strings', parts.join('; '));
} else {
  out('INFO', 'G0 /proc strings', 'nothing new (' + Object.keys(b.proc).length + ' strings)');
}

const diff = Object.keys(b.counts).filter((k) => a.counts[k] !== b.counts[k]).map((k) => k + ' ' + a.counts[k] + '->' + b.counts[k]);
out(diff.length ? 'REVIEW' : 'INFO', 'G0 file counts (/tmp, /bin/sh, /bin/bash, /usr/bin/env, link calls)',
  diff.length ? diff.join('; ') : 'unchanged');
if (!b.inventory) out('REVIEW', 'G0 content inventory', 'dist/postinstall-content-inventory.json is gone: completeness uses its other signs only');
process.exit(fail);
FACTS_JS
g0out=$(node "$WORK/facts.js" "$OLD" "$NEW" "$NODE_PIN" 2>"$WORK/facts.err")
g0rc=$?
if [ -z "$g0out" ] || [ "$g0rc" -gt 1 ]; then
    line ERROR "G0 facts" "could not read the package trees: $(clean "$(tr '\n' ' ' < "$WORK/facts.err" | cut -c1-200)")"
else
    TAB=$(printf '\t')
    while IFS="$TAB" read -r st label text; do
        case "$st" in PASS|FAIL|REVIEW|INFO) line "$st" "$label" "$text" ;; esac
    done <<EOF
$g0out
EOF
fi

echo ""
if [ "$FAILED" -gt 0 ]; then
    printf '%bResult: FAIL%b - %d passed, %d failed, %d errors, %d to review\n' "$RED" "$NC" "$PASS" "$FAILED" "$ERRORS" "$REVIEWS"
elif [ "$ERRORS" -gt 0 ]; then
    printf '%bResult: COULD NOT JUDGE%b - %d passed, %d errors, %d to review\n' "$RED" "$NC" "$PASS" "$ERRORS" "$REVIEWS"
else
    printf '%bResult: PASS%b - %d passed, %d failed, %d to review\n' "$GREEN" "$NC" "$PASS" "$FAILED" "$REVIEWS"
fi
echo "A PASS is not a go-ahead for a pin bump: stage 0 only recognizes known shapes (see the header of this script)."
[ "$FAILED" -eq 0 ] || exit 1
[ "$ERRORS" -eq 0 ] || exit 2
exit 0
