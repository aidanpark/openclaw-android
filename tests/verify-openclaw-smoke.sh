#!/usr/bin/env bash
# verify-openclaw-smoke.sh - stage 1 of the new-version check: a new install of an OpenClaw version under the
# Android system-call trap, in linux/arm64 containers (before a pin bump; after tests/verify-openclaw-fit.sh)
#
# What it does: (1) runs the stage-0 check (tests/verify-openclaw-fit.sh) and stops when that could not judge,
# (2) runs tests/openclaw-smoke/stage1.sh in containers of the official node image of the pinned Node.js version
# (platforms/openclaw/config.env), in two phases that share one Docker volume:
#   install: a container WITH network that runs no package code (npm install --ignore-scripts), and
#   run:     a container WITHOUT network (--network none) in which the package's code runs: gateway starts and
#            restarts with the PID namespace file denied (ENOENT and EACCES), doctor, backup, one agent turn
#            through the mock model, and controls that each remove one cause.
# Package code therefore cannot reach the internet, the host's loopback services (adb server, local git server, ...)
# or any file of this repository except the two read-only directories it is given (tests/openclaw-smoke, which
# holds the mock model, and platforms/openclaw). The rest of the working tree (signing keys, local notes) is not
# mounted. This script never reads a file that a container could have written:
# a container's result is its exit status and its standard output, which this script stores.
# The install phase is different: npm has the network there, and package METADATA (a dependency given as a URL)
# steers npm's own requests, which can reach services of the host. The answer is not kept in the volume (npm's
# cache is inside the install container), but a tarball that answers is installed into the package's tree like any
# dependency. npm is told not to use git (npm_config_git=/bin/false; a GitHub tarball by commit still installs).
# Run this on a machine whose loopback services you would let an unknown package send a GET request to (a GitHub
# runner is one). The package's code also runs under the harness's own user: this checks candidates from the npm
# registry for regressions, it does not judge a hostile package (such a package could forge a verdict).
# Not limited: CPU, memory and disk of the containers (only the number of processes), so a hostile package can
# fill the disk of the host through the output directory.
#
# A PASS is NOT a go-ahead for a pin bump: the trap imitates three system calls and one denied file; SELinux, API
# level differences and Termux's own layer are for the QA devices (see .agent/note/openclaw-pin-bump.md).
#
# Usage:
#   bash tests/verify-openclaw-smoke.sh <version|tag> [--baseline <version|tag>] [--out <dir>]
#   bash tests/verify-openclaw-smoke.sh latest --baseline 2026.10.1
#   A version is a full X.Y.Z[-pre], a partial one such as 2026.10 (npm resolves it to the highest match) or a
#   dist-tag (latest, beta, ...). --baseline runs the same checks for a second version and prints which rows differ
#   (information; it does not change the exit status). A baseline equal to the candidate is skipped with a note.
#   --out <dir>: an existing directory; the run creates a new subdirectory in it (default: under ${TMPDIR:-/tmp})
#   that holds stage0.log, stage1-<version>.log (what is printed), and artifacts-<version>/ (what the container
#   wrote: gateway logs, doctor and backup output; read them by hand, treat them as untrusted).
# Writes: that new directory, stage 0's cache (OA_FIT_CACHE, default ${TMPDIR:-/tmp}/oa-openclaw-fit), npm's own
#   cache (tag resolution), one Docker volume per version (oa-smoke-w-*, removed at the end; remove a leftover of a
#   killed run by hand with `docker volume rm`), and the node image (1.6 GB, pulled once). Nothing that this run
#   did not create is changed or deleted.
# Needs: bash, docker (an aarch64 daemon: the trap filters aarch64 system call numbers), npm and node (stage 0),
#   network (npm registry, Docker Hub).
# Exit: 0 = every row ok   1 = a row is not ok (or stage 0 failed)   2 = could not judge (bad arguments, no Docker,
#   stage 0 could not judge, a step that did not run, a phase that printed no RESULT line)   130 = interrupted
# Time: about 6 minutes per version once the image is there (every gateway start that has to work is watched 20
#   seconds, every SIGTERM stop has to end with exit status 0).

set -uo pipefail
unset CDPATH
export LC_ALL=C   # every line printed here is ASCII; a locale's multi-byte rules must not hide lines on a host

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# one line, ASCII only, nothing that a workflow runner or a terminal would read as a command
CLEAN() { printf '%s' "$1" | tr '\n\r\t' '   ' | tr -c ' -~' '?' | sed -e 's/##\[/# #[/g' -e ':a' -e 's/::/: :/' -e 'ta'; }
die() { echo "verify-openclaw-smoke.sh: $*" >&2; exit 2; }

VERSION=""; BASELINE=""; OUT_PARENT=""; HAVE_OUT=0
while [ $# -gt 0 ]; do
    case "$1" in
        --baseline) [ $# -ge 2 ] || die "--baseline needs a value"; BASELINE=$2; shift 2 ;;
        --out) [ $# -ge 2 ] || die "--out needs a value"; OUT_PARENT=$2; HAVE_OUT=1; shift 2 ;;
        -h|--help) sed -n '2,/^# Time:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) die "unknown option: $(CLEAN "$1")" ;;
        *) [ -z "$VERSION" ] || die "more than one version given"; VERSION=$1; shift ;;
    esac
done
[ -n "$VERSION" ] || die "usage: bash tests/verify-openclaw-smoke.sh <version|tag> [--baseline <version|tag>] [--out <dir>]"
# a version, partial version or tag; no range syntax (x, X, *) that npm would resolve to something else
valid() {
    case "$1" in
        ''|.*|-*|*[!0-9A-Za-z._-]*) return 1 ;;
        x|X|x.*|X.*|*.x|*.X|*.x.*|*.X.*) return 1 ;;
    esac
    return 0
}
valid "$VERSION" || die "not a version or tag: $(CLEAN "$VERSION")"
[ -z "$BASELINE" ] || valid "$BASELINE" || die "not a version or tag: $(CLEAN "$BASELINE")"
[ "$HAVE_OUT" = 0 ] || [ -n "$OUT_PARENT" ] || die "--out needs a directory"

command -v docker >/dev/null 2>&1 || die "docker is not installed"
command -v npm >/dev/null 2>&1 || die "npm is not installed"
docker info >/dev/null 2>&1 || die "the Docker daemon does not answer"
arch=$(docker info --format '{{.Architecture}}' 2>/dev/null)
case "$arch" in aarch64|arm64) ;; *) die "the Docker daemon is '$(CLEAN "$arch")', not aarch64: the trap filters aarch64 system call numbers only" ;; esac

# a tag (latest, beta) or a partial version becomes one canonical full version: stage 0 and stage 1 must look at
# the same one (2026.10.02 is not canonical: npm would install 2026.10.2)
resolve() { # <spec> -> prints the version or nothing
    if printf '%s' "$1" | grep -qE '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)([.-][0-9A-Za-z.-]+)?$'; then
        printf '%s\n' "$1"; return 0
    fi
    npm view "openclaw@$1" version 2>/dev/null | tail -1 | sed "s/^.*'\\([0-9][0-9A-Za-z.-]*\\)'\$/\\1/" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$' | head -1
}
CAND=$(resolve "$VERSION") || true
[ -n "$CAND" ] || die "could not resolve '$(CLEAN "$VERSION")' with npm (no network, or no such version)"
BASE=""
if [ -n "$BASELINE" ]; then
    BASE=$(resolve "$BASELINE") || true
    [ -n "$BASE" ] || die "could not resolve '$(CLEAN "$BASELINE")' with npm"
fi
BASELINE_NOTE=""
if [ -n "$BASE" ] && [ "$BASE" = "$CAND" ]; then
    BASELINE_NOTE="the baseline is the candidate itself ($CAND): no comparison"
    BASE=""
fi

NODE_PIN=$(sed -n 's/^PLATFORM_NODE_VERSION="\(.*\)"$/\1/p' "$ROOT/platforms/openclaw/config.env" | head -1)
OC_PIN=$(sed -n 's/^PLATFORM_NPM_PACKAGE_VERSION="\(.*\)"$/\1/p' "$ROOT/platforms/openclaw/config.env" | head -1)
valid "$NODE_PIN" || die "could not read PLATFORM_NODE_VERSION from platforms/openclaw/config.env"
IMAGE="node:${NODE_PIN}-bookworm"

# the output directory is a new one that this run owns (mode 0700: only this user reads what is in it)
if [ -n "$OUT_PARENT" ]; then
    [ -d "$OUT_PARENT" ] || die "--out: not a directory: $(CLEAN "$OUT_PARENT")"
    PARENT=$(cd "$OUT_PARENT" && pwd)
else
    PARENT="${TMPDIR:-/tmp}"; PARENT="${PARENT%/}"
fi
OUT=$(mktemp -d "$PARENT/oa-smoke.XXXXXX") || die "could not create a directory under $PARENT"

# the containers get only what they need, each read-only; a mount of a missing path would create it on the host
MOUNTS=(-v "$ROOT/tests/openclaw-smoke":/repo/tests/openclaw-smoke:ro -v "$ROOT/platforms/openclaw":/repo/platforms/openclaw:ro)

# what is running now, so that a signal stops it (a `wait` returns at once when a trapped signal arrives)
BGPID=""; CONTAINER=""; VOLUME=""
on_signal() {
    echo "verify-openclaw-smoke.sh: interrupted" >&2
    trap '' INT TERM
    [ -z "$BGPID" ] || kill "$BGPID" 2>/dev/null
    [ -z "$CONTAINER" ] || docker kill "$CONTAINER" >/dev/null 2>&1
    [ -z "$VOLUME" ] || { sleep 1; docker volume rm -f "$VOLUME" >/dev/null 2>&1 || echo "    (could not remove the volume $VOLUME: remove it by hand)"; }
    exit 130
}
trap on_signal INT TERM HUP

echo "=== OpenClaw $CAND, stage 1 (Docker $IMAGE, trap) - OpenClaw pin $OC_PIN, Node.js pin $NODE_PIN ==="
echo "    logs: $OUT"
[ -z "$BASELINE_NOTE" ] || echo "    note: $BASELINE_NOTE"
echo

show() { # <log file>
    local line
    while IFS= read -r line; do printf '    %s\n' "$(CLEAN "$line")"; done < <(sed 's/\x1b\[[0-9;]*m//g' "$1")
}

# --- stage 0 first (it does not run package code)
echo "--- stage 0: tests/verify-openclaw-fit.sh $CAND"
bash "$ROOT/tests/verify-openclaw-fit.sh" "$CAND" > "$OUT/stage0.log" 2>&1 &
BGPID=$!
wait "$BGPID"; rc0=$?
BGPID=""
show "$OUT/stage0.log"
if [ "$rc0" -ge 2 ]; then
    echo "Result: could not judge - stage 0 could not judge, stage 1 was not started"
    exit 2
fi
echo

# one phase in one container; its standard output is appended to the version's log
phase() { # <version> <install|run> <art dir> <docker network option...>
    local v=$1 ph=$2 art=$3 prc
    shift 3
    CONTAINER="oa-smoke-$$-$RANDOM"
    docker run --rm --init --pids-limit 4096 --platform linux/arm64 --user 1000:1000 --name "$CONTAINER" "$@" \
        "${MOUNTS[@]}" -v "$VOLUME":/home/node -v "$art":/out "$IMAGE" bash /repo/tests/openclaw-smoke/stage1.sh "$v" "$ph" \
        >> "$OUT/stage1-$v.log" 2>&1 &
    BGPID=$!
    wait "$BGPID"; prc=$?
    BGPID=""; CONTAINER=""
    return $prc
}

run_stage1() { # <version>: returns 0 (all ok), 1 (a row is not ok), 2 (could not judge)
    local v=$1 art=$OUT/artifacts-$1 log=$OUT/stage1-$1.log rci rcr ni
    mkdir -p "$art" && chmod 0777 "$art" || return 2     # the containers' user (1000) has to be able to write there
    : > "$log"
    VOLUME="oa-smoke-w-$$-$RANDOM"
    docker volume create "$VOLUME" >/dev/null || { VOLUME=""; return 2; }
    phase "$v" install "$art"; rci=$?
    ni=$(grep -a -c '^RESULT:' "$log")        # RESULT lines of the install phase
    if [ "$rci" = 0 ] || [ "$rci" = 1 ]; then
        phase "$v" run "$art" --network none; rcr=$?
    else
        rcr=2
    fi
    docker volume rm -f "$VOLUME" >/dev/null 2>&1 || echo "    (could not remove the volume $VOLUME: remove it by hand)"
    VOLUME=""
    # a phase's verdict counts only with exactly one RESULT line of its own: an exit status without it is a crashed
    # harness. A judged failure of one phase is a verdict even when the other could not be judged.
    local nr
    nr=$(( $(grep -a -c '^RESULT:' "$log") - ni ))
    if [ "$rci" = 1 ] && [ "$ni" = 1 ]; then return 1; fi
    if [ "$rcr" = 1 ] && [ "$nr" = 1 ]; then return 1; fi
    if [ "$rci" = 0 ] && [ "$ni" = 1 ] && [ "$rcr" = 0 ] && [ "$nr" = 1 ]; then return 0; fi
    return 2
}

echo "--- stage 1: $CAND (install phase with network, run phase without)"
run_stage1 "$CAND"; rc1=$?
show "$OUT/stage1-$CAND.log"
case "$rc1" in 0|1|2) ;; *) echo "    (no judgment)"; rc1=2 ;; esac

if [ -n "$BASE" ]; then
    echo
    echo "--- stage 1 baseline: $BASE"
    run_stage1 "$BASE"; rcb=$?
    echo "    baseline result: exit $rcb ($(CLEAN "$(grep -a '^RESULT:' "$OUT/stage1-$BASE.log" | tail -1 | cut -c1-140)"))"
    rows() { grep -a -E '^\[(ok|NOT OK|SKIPPED)\]' "$1" | sed -E 's/ - .*$//'; }
    if diff <(rows "$OUT/stage1-$BASE.log") <(rows "$OUT/stage1-$CAND.log") > "$OUT/rows.diff"; then
        echo "    judged rows: identical to the baseline"
    else
        echo "    judged rows that differ from the baseline (< baseline, > candidate):"
        while IFS= read -r l; do printf '        %s\n' "$(CLEAN "$l")"; done < "$OUT/rows.diff"
    fi
fi

echo
# a judged failure is a verdict even when something else could not be judged
final=0
if [ "$rc0" = 1 ] || [ "$rc1" = 1 ]; then final=1
elif [ "$rc1" = 2 ]; then final=2; fi
case "$final" in
    0) echo "Result: PASS - stage 0 and stage 1 ok" ;;
    1) echo "Result: FAIL - stage 0 exit $rc0, stage 1 exit $rc1 (see the rows marked NOT OK)" ;;
    *) echo "Result: could not judge completely - stage 1 exit $rc1" ;;
esac
echo "A PASS is not a go-ahead for a pin bump: QA devices decide (SELinux, API levels, Termux)."
exit "$final"
