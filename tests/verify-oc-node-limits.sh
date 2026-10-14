#!/usr/bin/env bash
# verify-oc-node-limits.sh - harness for the time limits of node and npm in platforms/openclaw/*.sh
#
# A node or npm that stops answering must not stop the OpenClaw update, and must not be read as "the installed
# version is different" (that reinstalls an OpenClaw that is already right). This harness runs the real
# platforms/openclaw/update.sh, verify.sh, status.sh and install.sh with stub node / npm / curl in front of PATH; a
# stub stops (sleeps) on the arguments that a case names, the limits are cut to a few seconds, and the case checks
# what the script did: the exit status, the notes it left for the app, and which npm commands it ran.
#
#   C1   node --version never answers          -> the Node.js gate ends the run: "found: no answer within Ns",
#                                                 reason=node_gate changed=false, exit 1, no npm install
#   C1b  node --version stops once             -> one retry, the run goes on
#   C2   npm root -g never answers             -> the fixed folder under $PREFIX is used, npm root -g is asked once
#   C3   the right OpenClaw is installed and `node -p` never answers (the reason the version is read with sed)
#                                              -> no npm install, exit 0
#   C3b  package.json has no "version" line    -> the old behaviour: the install path
#   C4   the postinstall step stops            -> a [WARN] line and the run goes on
#   C5   the repair functions' node stops      -> the two [WARN] lines "(node failed)" of the existing path, each
#                                                 after the work limit, and the run goes on
#   C6   the undici probe stops                -> "skipped", no npm install undici
#   C7   verify.sh reads the version without node; status.sh does not hang on node -v
#   C7d  status.sh and verify.sh with an `openclaw --version`, `npm -v`, `clawdhub --version` that stop: the
#        screen says "unknown" / a warning for each, ends, and the patch check is given the package folder (S5)
#   C8   the cache-skew refusal says what was not changed (OpenClaw), not "Nothing was changed"
#   C9   a lib.sh without oa_node_limited (an older copy from a cache) -> every call runs as before, no error
#   C10  the normal path: the same output and exit status as without the limits
#   C11  the patch scripts get the package folder that was found with the limit (they do not ask npm again)
#   C12  an npm (or node) that is a WRAPPER: it starts the real program as a child and the child stops. The real
#        ~/.openclaw-android/bin wrappers do not exec; the kill of a time limit that reaches only the wrapper leaves
#        the child holding the output pipe, and a command substitution or `| tee` waits for it. The case checks the
#        time that the script needs and that no child is left.
#
#   C13  a node that prints its version and then does not end (_oa_node_version of update-core.sh counts that as an
#        answer): the gate passes; and oc_node_version and _oa_node_version give the same verdict for the same
#        stub (the two gates of one update must not differ)
#   C15  `npm list -g clawdhub` never answers -> "clawdhub check skipped", no npm view, no npm install, exit 0
#   C17  a one-line package.json that has a second "version" key further in: the FIRST is the package's
#   C18  the limit in a message is the one that was applied (OA_NODE_PROBE_TIMEOUT=0005 -> "5s", 4 or abc -> 30s)
#   C19  the version is in the file in a layout that only node reads: node -p's answer is the version (also when it
#        prints it and does not end)
#   C21  the signal reaches only the shell of the call (the command substitution), not the script: the script ends as
#        that signal anyway (oc_end_on_signal -> oa_end_on_signal of lib.sh); without it the script goes on
#   C20  Ctrl-C (SIGINT to the process group) while a probe waits: the script ends at once as that signal (130), not
#        after the limit and not by going on (oa_node_limited returns 130 / 143 since this lib.sh; the callers end
#        the script with oa_end_on_signal)
#
# The limit function itself (oa_node_limited) belongs to scripts/lib.sh and is used as it is. A tree that does not
# have it yet gets a stand-in with the same interface appended to a COPY of lib.sh (class probe|work, exit status 75
# for "no answer"); the case C9 removes the function from a copy. The limits are 5 (probe) and 15 (work) seconds
# (the function does not take less than 5; the two are far apart so that a call of the wrong class shows in the
# time that a case needs). The value is given as 0005 and 0015: a message that names the text of the variable
# instead of the limit that lib.sh read says "0005s".
#
# The timeout program: OA_TEST_TIMEOUT=auto (the default) uses the system's GNU timeout when it has one, and a
# shim (perl) when it has not (macOS); shim forces the shim; system requires GNU timeout; both runs the harness
# twice, once with each, and ends with the worse status ("[NOT RUN]" for the half that this host cannot run).
#
# Usage:
#   bash tests/verify-oc-node-limits.sh
#   OA_TEST_TIMEOUT=both bash tests/verify-oc-node-limits.sh
#   OA_TEST_ROOT=/path/to/tree bash tests/verify-oc-node-limits.sh

set -uo pipefail
unset CDPATH

ROOT="${OA_TEST_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BASH_BIN="${OA_TEST_BASH:-bash}"

sys_gnu_timeout() { command -v timeout >/dev/null 2>&1 && timeout --foreground -k 1 10 true >/dev/null 2>&1; }
TMODE="${OA_TEST_TIMEOUT:-auto}"
case "$TMODE" in auto|shim|system|both) ;; *) echo "OA_TEST_TIMEOUT must be auto, shim, system or both (got '$TMODE')"; exit 2 ;; esac
if [ "$TMODE" = both ]; then
    rc_all=0
    echo "=== timeout: shim ==="
    OA_TEST_TIMEOUT=shim "$BASH_BIN" "${BASH_SOURCE[0]}" || rc_all=1
    if sys_gnu_timeout; then
        echo "=== timeout: system (GNU) ==="
        OA_TEST_TIMEOUT=system "$BASH_BIN" "${BASH_SOURCE[0]}" || rc_all=1
    else
        echo "[NOT RUN] the system timeout half: this host has no GNU timeout"
    fi
    exit "$rc_all"
fi
if [ "$TMODE" = auto ]; then
    if sys_gnu_timeout; then TMODE=system; else TMODE=shim; fi
fi
if [ "$TMODE" = system ] && ! sys_gnu_timeout; then
    echo "OA_TEST_TIMEOUT=system: no GNU timeout on this host"; exit 2
fi

PASS=0; FAIL=0; TOTAL=0
RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); TOTAL=$((TOTAL+1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); TOTAL=$((TOTAL+1)); [ -n "${OUT:-}" ] && printf '       | %s\n' "${OUT//$'\n'/$'\n       | '}" | tail -12; }
check() { local desc="$1"; shift; if "$@"; then pass "$desc"; else fail "$desc"; fi; }

for f in platforms/openclaw/update.sh platforms/openclaw/install.sh platforms/openclaw/verify.sh platforms/openclaw/status.sh \
         platforms/openclaw/limits.sh platforms/openclaw/config.env scripts/lib.sh; do
    [ -f "$ROOT/$f" ] || { echo -e "${RED}[FAIL]${NC} $f not found under $ROOT"; exit 1; }
done

W=$(mktemp -d "${TMPDIR:-/tmp}/oa-oc-limits.XXXXXX") || exit 1
trap 'rm -rf "$W"' EXIT

read -r NODE_PIN OC_PIN < <(
    # shellcheck source=/dev/null
    ( source "$ROOT/platforms/openclaw/config.env" && echo "$PLATFORM_NODE_VERSION $PLATFORM_NPM_PACKAGE_VERSION" )
)

# ── a copy of the tree: the platform scripts and a lib.sh with or without the limit function ─────────────
# (a copy: the stand-in is appended to lib.sh, and nothing of the real tree is changed)
mk_tree() { # <dir> <with|without>   - the limit function: with = the tree's own, or the stand-in when it has none
    local d="$1" mode="$2"
    mkdir -p "$d/scripts" "$d/platforms"
    cp -R "$ROOT/platforms/openclaw" "$d/platforms/openclaw"
    cp "$ROOT/scripts/lib.sh" "$d/scripts/lib.sh"
    if [ "$mode" = with ] && ! grep -q '^oa_node_limited()' "$d/scripts/lib.sh"; then
        cat >> "$d/scripts/lib.sh" <<'EOS'

# --- stand-in for the harness only (the real function is in scripts/lib.sh of the tree under test) ---
oa_node_limited() {
    local class="${1:-probe}" lim rc=0
    shift
    case "$class" in probe) lim="${OA_NODE_PROBE_TIMEOUT:-30}" ;; *) lim="${OA_NODE_WORK_TIMEOUT:-300}" ;; esac
    perl -e 'alarm shift; exec @ARGV or exit 127' "$lim" "$@" || rc=$?
    [ "$rc" = 142 ] && return 75
    return "$rc"
}
EOS
    fi
}
mk_tree "$W/tree-with" with
mk_tree "$W/tree-without" without
# The patch scripts are other units' work: in the copies they are recorders that note the arguments and the
# OA_OPENCLAW_DIR that they were started with (case C11 reads the record of the hard-link patch). The real
# openclaw-apply-patches.sh stays: what it hands on is the thing that is tested.
for t in tree-with tree-without; do
    for pscript in openclaw-patch-hardlink.sh openclaw-patch-paths.sh; do
        cat > "$W/$t/platforms/openclaw/patches/$pscript" <<'EOS'
#!/bin/sh
echo "$0 args=[$*] OA_OPENCLAW_DIR=[${OA_OPENCLAW_DIR:-}]" >> "${PATCH_LOG:-/dev/null}"
case "$*" in *--check*) echo "Hard-link fallback: todo=0 already=1 problems=0" ;; esac
exit 0
EOS
        chmod +x "$W/$t/platforms/openclaw/patches/$pscript"
    done
done
if grep -q '^oa_node_limited()' "$ROOT/scripts/lib.sh"; then
    echo "    limit function: the tree's own oa_node_limited"
else
    echo "    limit function: a stand-in for the harness (the tree has no oa_node_limited yet)"
fi
# the "without" tree must really be without it
sed -i.bak '/^oa_node_limited()/,/^}/d' "$W/tree-without/scripts/lib.sh" 2>/dev/null; rm -f "$W/tree-without/scripts/lib.sh.bak"

# ── stubs ───────────────────────────────────────────────────────────────────────────────────────────────
STUBS="$W/stubs"; mkdir -p "$STUBS"
cat > "$STUBS/curl" <<'EOF'
#!/bin/sh
exit 22
EOF
# node: --version | -p | -e | <script>.mjs. The words of STUB_NODE_HANG are compared with each ARGUMENT as a whole
# (a path such as .../-projects/... must not match "-p"): a call that has one of them stops (exec sleep: no child
# is left holding a pipe). STUB_NODE_HANG_ONCE: stops the first call only (the flag file is its state).
# STUB_NODE_PRINT_HANG: prints the answer and then does not end. STUB_NODE_CHILD_HANG: a wrapper whose child
# stops. STUB_NODE_EXIT: the exit status of a call that answers.
cat > "$STUBS/node" <<'EOF'
#!/bin/sh
echo "node $*" >> "${NODE_LOG:-/dev/null}"
hasword() { W="$1"; shift; for a in "$@"; do [ "$a" = "$W" ] && return 0; done; return 1; }
if [ -n "${STUB_NODE_HANG_ONCE:-}" ] && [ ! -e "$STUB_STATE/once" ] && hasword "$STUB_NODE_HANG_ONCE" "$@"; then
    : > "$STUB_STATE/once"; exec sleep 600
fi
for w in ${STUB_NODE_HANG:-}; do
    hasword "$w" "$@" && exec sleep 600
done
# a wrapper: the real program is a CHILD that stops (it keeps the output pipes of the wrapper)
for w in ${STUB_NODE_CHILD_HANG:-}; do
    hasword "$w" "$@" && { sleep "6002.${STUB_TAG:-0}" & wait; }
done
# STUB_NODE_KILL_GP: SIGINT to the shell that runs the call (the parent of the program that runs this one: the
# timeout), as if only that shell had been signalled, and then no answer
if [ -n "${STUB_NODE_KILL_GP:-}" ] && [ "$1" = "$STUB_NODE_KILL_GP" ]; then
    if [ -r "/proc/$PPID/stat" ]; then gp=$(awk '{print $4}' "/proc/$PPID/stat"); else gp=$(ps -o ppid= -p "$PPID" | tr -d ' '); fi
    [ -z "$gp" ] || kill -INT "$gp"
    exec sleep 600
fi
AFTER=""
for w in ${STUB_NODE_PRINT_HANG:-}; do
    # (the FIRST argument only: `node --version` of the gates, not `node openclaw.mjs --version` of the
    # completeness check, which has a 90 s limit of its own in lib.sh)
    [ "$1" = "$w" ] && AFTER=1
done
case "$1" in
    --version) [ "${STUB_NODE_EXIT:-0}" != 0 ] || echo "v${STUB_NODE_VERSION:-0.0.0}" ;;
    -p)
        if [ -n "${STUB_NODE_P_ANSWER:-}" ]; then
            echo "$STUB_NODE_P_ANSWER"    # node reads the file as JSON (a layout that the text readings do not find)
        else
            f=$(printf '%s' "$2" | sed -n "s/.*require('\(.*\)').*/\1/p")
            [ -f "$f" ] && sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p' "$f" | head -1
        fi
        ;;
    -e) [ -n "$AFTER" ] || exit "${STUB_NODE_EXIT:-0}" ;;
esac
[ -z "$AFTER" ] || exec sleep 600
exit "${STUB_NODE_EXIT:-0}"
EOF
# npm: root -g -> STUB_NPM_ROOT (or stops); install/view/list are logged
cat > "$STUBS/npm" <<'EOF'
#!/bin/sh
echo "npm $*" >> "${NPM_LOG:-/dev/null}"
case "$1" in
    root)
        [ -n "${STUB_NPM_ROOT_HANG:-}" ] && exec sleep 600
        if [ -n "${STUB_NPM_ROOT_CHILD_HANG:-}" ]; then sleep "6001.${STUB_TAG:-0}" & wait; fi
        echo "${STUB_NPM_ROOT:-/nonexistent}" ;;
    list)
        [ -n "${STUB_NPM_LIST_HANG:-}" ] && exec sleep 600
        echo "/stub/lib"; echo "└── clawdhub@1.0.0" ;;
    view) echo "1.0.0" ;;
    -v)
        [ -n "${STUB_NPM_V_HANG:-}" ] && exec sleep 600
        echo "11.0.0" ;;
    install)
        # a "successful" install of the pin: the package folder gets the pin as its version
        d="${STUB_NPM_ROOT}/openclaw"
        mkdir -p "$d/scripts"
        printf '{ "name": "openclaw", "version": "%s" }\n' "${STUB_PIN:-0.0.0}" > "$d/package.json"
        : > "$d/openclaw.mjs"
        ;;
esac
exit 0
EOF
# timeout (used in the shim mode only; the macOS host has none): a shim with the GNU meaning that oa_node_limited uses
# ([--foreground] [-k N] <seconds> <command...>, exit status 124 when the time ran out). Like the real one it ends
# the whole process group of the command, except with --foreground: then only the command itself (so a wrapper
# that started a child leaves the child behind: the case that makes oa_node_limited use no --foreground for probe)
SHIMDIR="$W/shim"; mkdir -p "$SHIMDIR"
cat > "$SHIMDIR/timeout" <<'ENDSHIM'
#!/bin/sh
FG=0
while :; do
    case "$1" in --foreground) FG=1; shift ;; -k) shift 2 ;; *) break ;; esac
done
d="$1"; shift
SHIM_FG=$FG exec perl -e '
    my $d = shift; my $pid = fork();
    if (!$pid) { setpgrp(0, 0) unless $ENV{SHIM_FG}; exec @ARGV or exit 127 }
    my $t = 0; $SIG{ALRM} = sub { $t = 1; kill 9, -$pid unless $ENV{SHIM_FG}; kill 9, $pid };
    # a TERM to timeout itself goes on to the command (to its whole group, as GNU timeout does without --foreground)
    $SIG{TERM} = sub { kill 15, -$pid unless $ENV{SHIM_FG}; kill 15, $pid; waitpid($pid, 0); exit 143 };
    alarm $d; waitpid($pid, 0); my $r = $?;
    exit 124 if $t; exit($r >> 8) if !($r & 127); exit(128 + ($r & 127))
' "$d" "$@"
ENDSHIM
chmod +x "$STUBS/curl" "$STUBS/node" "$STUBS/npm" "$SHIMDIR/timeout"
if [ "$TMODE" = shim ]; then
    TPATH="$STUBS:$SHIMDIR:/usr/bin:/bin"; OUTER_TIMEOUT="$SHIMDIR/timeout"
else
    TPATH="$STUBS:/usr/bin:/bin"; OUTER_TIMEOUT="$(command -v timeout)"
fi
echo "    timeout program: $TMODE ($OUTER_TIMEOUT)"

# ── sandbox ─────────────────────────────────────────────────────────────────────────────────────────────
# new_sandbox <installed openclaw version|none>: HOME/PREFIX with a global node_modules and the package folder
new_sandbox() {
    local ver="$1" sb
    sb=$(mktemp -d "$W/sb.XXXXXX") || return 1
    mkdir -p "$sb/home/.openclaw-android/bin" "$sb/prefix/bin" "$sb/prefix/tmp" "$sb/prefix/lib/node_modules" "$sb/state"
    if [ "$ver" != none ]; then
        mkdir -p "$sb/prefix/lib/node_modules/openclaw/scripts"
        printf '{ "name": "openclaw", "version": "%s" }\n' "$ver" > "$sb/prefix/lib/node_modules/openclaw/package.json"
        : > "$sb/prefix/lib/node_modules/openclaw/openclaw.mjs"    # a complete package for oa_openclaw_incomplete
        printf '#!/bin/sh\nexit 0\n' > "$sb/prefix/bin/openclaw"; chmod +x "$sb/prefix/bin/openclaw"
    fi
    echo "$sb"
}

# run <tree> <sandbox> <script relative to the platform dir> [VAR=value ...]  -> OUT RC
run() {
    local tree="$1" sb="$2" script="$3"; shift 3
    local -a e=(HOME="$sb/home" PREFIX="$sb/prefix" TMPDIR="$sb/prefix/tmp" PATH="$TPATH" TERM=dumb
                OA_RESULT_NOTES="$sb/notes" NODE_LOG="$sb/node.log" NPM_LOG="$sb/npm.log" STUB_STATE="$sb/state" PATCH_LOG="$sb/patch.log"
                STUB_TAG="$$"
                STUB_NODE_VERSION="$NODE_PIN" STUB_NPM_ROOT="$sb/prefix/lib/node_modules" STUB_PIN="$OC_PIN"
                OA_NODE_PROBE_TIMEOUT=0005 OA_NODE_WORK_TIMEOUT=0015 OA_MIN_FREE_UPDATE_MB=1
                OA_UPDATE_CORE_PROTOCOL=3)
    [ "$#" -eq 0 ] || e+=("$@")
    : > "$sb/notes"
    local t0=$SECONDS
    # (an outer limit of 90 s per run: a script that does not stop - which is what a missing limit looks like - fails
    # its case with exit status 124 instead of stopping the harness)
    if [ -n "${RUN_INT_AFTER:-}" ]; then
        # Ctrl-C: the script runs in a process group of its own, and the group gets SIGINT after the given seconds
        # (what a terminal sends to its foreground group); the exit status is 128 + the signal when it was ended by it
        OUT=$(perl -e 'my $d = shift; my $pid = fork();
                if (!$pid) { setpgrp(0, 0); exec @ARGV or exit 127 }
                sleep $d; kill "INT", -$pid; waitpid($pid, 0); my $r = $?;
                exit(($r & 127) ? 128 + ($r & 127) : $r >> 8)' \
            "$RUN_INT_AFTER" env -i "${e[@]}" "$BASH_BIN" "$tree/platforms/openclaw/$script" 2>&1 </dev/null); RC=$?
    else
        OUT=$("$OUTER_TIMEOUT" 90 env -i "${e[@]}" "$BASH_BIN" "$tree/platforms/openclaw/$script" 2>&1 </dev/null); RC=$?
    fi
    ELAPSED=$((SECONDS - t0))
}
# in_window <seconds> <low> <high>: the time that a case needed is at least low and below high. The windows are cut
# from the two limits (P = probe 5, K = work 15): a call of the wrong class, or of no limit at all, falls outside
in_window() { [ "$1" -ge "$2" ] && [ "$1" -lt "$3" ]; }
P=5; K=15
npm_calls() { grep -c "$1" "$2/npm.log" 2>/dev/null || true; }

# ═════════════════════════════════════════════════════════════════════════
echo "--- C1: node --version never answers (the Node.js gate) ---"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" update.sh STUB_NODE_HANG="--version"
check "C1a: exit 1, the gate says \"no answer\" in the known sentence frame ('is required (found: ')" \
    bash -c '[ "$1" = 1 ] && printf "%s" "$2" | grep -q "is required (found: no answer within 5s)"' _ "$RC" "$OUT"
check "C1b: reason=node_gate changed=false was noted for the app" \
    bash -c 'grep -qx "reason=node_gate" "$1/notes" && grep -qx "changed=false" "$1/notes"' _ "$SB"
check "C1c: no npm install was run, and the gate waited for two probe limits and no more (${ELAPSED}s, window 9..20)" \
    bash -c 'n=$(grep -c "^npm install -g openclaw@" "$1/npm.log" 2>/dev/null); [ "${n:-0}" = 0 ] && [ "$2" -ge 9 ] && [ "$2" -lt 20 ]' _ "$SB" "$ELAPSED"
check "C1d: the message names the limit that was applied (OA_NODE_PROBE_TIMEOUT=0005 -> 5s, not 0005s)" \
    bash -c '! printf "%s" "$1" | grep -q "0005s"' _ "$OUT"

echo "--- C1b: node --version stops once ---"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" update.sh STUB_NODE_HANG_ONCE="--version"
check "C1e: one retry: the gate passes and the run goes on (no node_gate note)" \
    bash -c '! grep -qx "reason=node_gate" "$1/notes" && ! printf "%s" "$2" | grep -q "is required (found:"' _ "$SB" "$OUT"

echo "--- C2: npm root -g never answers ---"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" update.sh STUB_NPM_ROOT_HANG=1
check "C2a: the fixed folder under \$PREFIX is used (the installed package is found, no reinstall) and the run ends 0" \
    bash -c '[ "$1" = 0 ] && [ "$(grep -c "^npm install -g openclaw" "$2/npm.log" 2>/dev/null || true)" = 0 ]' _ "$RC" "$SB"
check "C2b: npm root -g was asked once (the answer is reused)" \
    bash -c '[ "$(grep -c "^npm root" "$1/npm.log")" = 1 ]' _ "$SB"

echo "--- C3: the right OpenClaw is installed, node -p never answers ---"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" update.sh STUB_NODE_HANG="-p"
check "C3a: no npm install (the version is read from package.json, not through node)" \
    bash -c 'n=$(grep -c "^npm install -g openclaw@" "$1/npm.log" 2>/dev/null); [ "${n:-0}" = 0 ]' _ "$SB"
check "C3b: exit 0 and node -p was not even called" \
    bash -c '[ "$1" = 0 ] && ! grep -q "^node -p" "$2/node.log" 2>/dev/null' _ "$RC" "$SB"

echo "--- C3b: package.json without a version line ---"
SB=$(new_sandbox "$OC_PIN")
printf '{ "name": "openclaw" }\n' > "$SB/prefix/lib/node_modules/openclaw/package.json"
run "$W/tree-with" "$SB" update.sh
check "C3c: node answers and finds no version: a package.json that is broken, the install path (npm install -g openclaw@$OC_PIN)" \
    bash -c 'grep -q "^npm install -g openclaw@" "$1/npm.log" 2>/dev/null' _ "$SB"

echo "--- C3d: a one-line (minified) package.json of the right version ---"
SB=$(new_sandbox "$OC_PIN")
printf '{"name":"openclaw","version":"%s","bin":{"openclaw":"openclaw.mjs"}}\n' "$OC_PIN" > "$SB/prefix/lib/node_modules/openclaw/package.json"
run "$W/tree-with" "$SB" update.sh STUB_NODE_HANG="-p"
check "C3d: read without node, no npm install (a one-line file is not \"not installed\")" \
    bash -c 'n=$(grep -c "^npm install -g openclaw@" "$1/npm.log" 2>/dev/null); [ "${n:-0}" = 0 ] && ! grep -q "^node -p" "$1/node.log" 2>/dev/null' _ "$SB"

echo "--- C3e: an empty package.json ---"
SB=$(new_sandbox "$OC_PIN")
: > "$SB/prefix/lib/node_modules/openclaw/package.json"
run "$W/tree-with" "$SB" update.sh
check "C3e: node answers and finds nothing: broken package, the install path" \
    bash -c 'grep -q "^npm install -g openclaw@" "$1/npm.log" 2>/dev/null' _ "$SB"

echo "--- C3f: no version in the file and node never answers: the version is UNKNOWN, nothing is reinstalled ---"
SB=$(new_sandbox "$OC_PIN")
printf '{ "name": "openclaw" }\n' > "$SB/prefix/lib/node_modules/openclaw/package.json"
run "$W/tree-with" "$SB" update.sh STUB_NODE_HANG="-p"
check "C3f: exit 1, the known gate sentence frame, reason=node_gate changed=false, no npm install" \
    bash -c '[ "$1" = 1 ] && printf "%s" "$2" | grep -q "is required (found: no answer within 5s)" && grep -qx "reason=node_gate" "$3/notes" && grep -qx "changed=false" "$3/notes" && { n=$(grep -c "^npm install -g openclaw@" "$3/npm.log" 2>/dev/null || true); [ "${n:-0}" = 0 ]; }' _ "$RC" "$OUT" "$SB"

echo "--- C4: the postinstall step stops (a fresh install) ---"
SB=$(new_sandbox none)
run "$W/tree-with" "$SB" update.sh STUB_NODE_HANG="scripts/postinstall-bundled-plugins.mjs"
check "C4a: a [WARN] line says that the step did not finish within the WORK limit (15s, not 0015s), and the run went on past it" \
    bash -c 'printf "%s" "$1" | grep -q "postinstall step did not finish within 15s" && printf "%s" "$1" | grep -q "Applying OpenClaw Patches"' _ "$OUT"
check "C4b: it waited for the work limit (one call of class work: ${ELAPSED}s, window 15..45; probe would end at 5)" \
    bash -c '[ "$1" -ge 15 ] && [ "$1" -lt 45 ]' _ "$ELAPSED"

echo "--- C6: the undici probe stops ---"
SB=$(new_sandbox "$OC_PIN")
mkdir -p "$SB/prefix/lib/node_modules/clawdhub"
run "$W/tree-with" "$SB" update.sh STUB_NODE_HANG="require('undici')"
check "C6a: skipped with a [WARN] that names 5s, and no npm install undici" \
    bash -c 'printf "%s" "$1" | grep -q "undici check skipped (node did not answer within 5s)" && ! grep -q "^npm install undici" "$2/npm.log" 2>/dev/null' _ "$OUT" "$SB"

echo "--- C8: the cache-skew refusal ---"
SB=$(new_sandbox "2020.1.1")
run "$W/tree-with" "$SB" update.sh OA_UPDATE_CORE_PROTOCOL=2
check "C8a: it says that OpenClaw was not changed and the update scripts were replaced (not \"Nothing was changed\")" \
    bash -c '[ "$1" = 1 ] && printf "%s" "$2" | grep -q "OpenClaw was not changed; only the update scripts were replaced" && ! printf "%s" "$2" | grep -q "Nothing was changed"' _ "$RC" "$OUT"
check "C8b: the phrase frame of the app contract is kept ('(cache).' ends the matched part)" \
    bash -c 'printf "%s" "$1" | grep -q "The updater downloaded an older copy of itself (cache)\."' _ "$OUT"

echo "--- C7: verify.sh and status.sh ---"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" verify.sh STUB_NODE_HANG="-p"
check "C7a: verify.sh judges the installed version although node -p never answers" \
    bash -c 'printf "%s" "$1" | grep -q "openclaw package $2 matches the pinned version"' _ "$OUT" "$OC_PIN"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" status.sh STUB_NODE_HANG="--version"
check "C7b: status.sh does not hang on node --version: it ends and says \"no answer\" (${ELAPSED}s, window 9..30)" \
    bash -c 'printf "%s" "$1" | grep -q "Node.js:     no answer within 5s" && [ "$2" -ge 9 ] && [ "$2" -lt 30 ]' _ "$OUT" "$ELAPSED"
# status.sh is installed alone by the installer: it has to work without limits.sh next to it
cp "$W/tree-with/platforms/openclaw/status.sh" "$W/status-alone.sh"
mkdir -p "$W/alone/platforms/openclaw" "$W/alone/scripts"
cp "$W/status-alone.sh" "$W/alone/platforms/openclaw/status.sh"; cp "$W/tree-with/scripts/lib.sh" "$W/alone/scripts/lib.sh"
SB=$(new_sandbox "$OC_PIN")
run "$W/alone" "$SB" status.sh
check "C7c: status.sh works without limits.sh next to it" \
    bash -c '[ "$1" = 0 ] && printf "%s" "$2" | grep -q "Node.js:"' _ "$RC" "$OUT"

echo "--- C9: a lib.sh without oa_node_limited (an older copy from a cache) ---"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-without" "$SB" update.sh
check "C9a: update.sh runs as before: exit 0, no error about a missing function" \
    bash -c '[ "$1" = 0 ] && ! printf "%s" "$2" | grep -qi "command not found"' _ "$RC" "$OUT"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-without" "$SB" verify.sh
check "C9b: verify.sh runs as before" \
    bash -c 'printf "%s" "$1" | grep -q "matches the pinned version"' _ "$OUT"

echo "--- C11: the patch scripts get the package folder that was found with the limit ---"
SB=$(new_sandbox none)
run "$W/tree-with" "$SB" update.sh STUB_NPM_ROOT_HANG=1
check "C11a: openclaw-patch-hardlink.sh was started with the folder as its argument (it does not ask npm itself)" \
    bash -c 'grep -q "openclaw-patch-hardlink.sh args=\[$1/prefix/lib/node_modules/openclaw\]" "$1/patch.log" 2>/dev/null' _ "$SB"
check "C11b: and OA_OPENCLAW_DIR was exported to the patch scripts" \
    bash -c 'grep -q "OA_OPENCLAW_DIR=\[$1/prefix/lib/node_modules/openclaw\]" "$1/patch.log" 2>/dev/null' _ "$SB"

echo "--- C12: wrappers that start the real program as a child, and the child stops ---"
# leftovers are looked for by the unique number of this harness run (the stubs sleep for 6001.<pid> / 6002.<pid> s)
leftover() {
    local c n=0
    if [ -r /proc/self/cmdline ]; then
        for c in /proc/[0-9]*/cmdline; do
            if tr '\0' ' ' < "$c" 2>/dev/null | grep -Eq "sleep 600[12]\.$$ *\$"; then
                c="${c%/cmdline}"; printf '%s ' "${c#/proc/}"
            fi
        done
    elif ps -A -o pid= -o args= >/dev/null 2>&1; then
        ps -A -o pid= -o args= | grep -E "sleep 600[12]\.$$ *\$" | awk '{print $1}' | tr '\n' ' '
    else
        printf '? '    # no way to list processes: the check that reads this fails, it does not pass
    fi
    return "$n"
}
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" update.sh STUB_NPM_ROOT_CHILD_HANG=1
L1=$(leftover)
check "C12a: npm root -g (a wrapper whose child stops): the fixed folder is used, the run ends 0, no child is left (${ELAPSED}s, window 4..30)" \
    bash -c '[ "$1" = 0 ] && [ "$2" -ge 4 ] && [ "$2" -lt 30 ] && [ -z "$3" ]' _ "$RC" "$ELAPSED" "$L1"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" update.sh STUB_NODE_CHILD_HANG="--version"
L2=$(leftover)
check "C12b: node --version (a wrapper whose child stops): the gate says \"no answer\", the run does not wait for the child (${ELAPSED}s, window 9..30), no child is left" \
    bash -c '[ "$1" = 1 ] && printf "%s" "$2" | grep -q "is required (found: no answer within" && [ "$3" -ge 9 ] && [ "$3" -lt 30 ] && [ -z "$4" ]' _ "$RC" "$OUT" "$ELAPSED" "$L2"
for p in $(leftover); do [ "$p" = "?" ] || kill "$p" 2>/dev/null || true; done

echo "--- C5: the node of the repair functions stops (state database and config file are there) ---"
SB=$(new_sandbox "$OC_PIN")
mkdir -p "$SB/home/.openclaw/state"
: > "$SB/home/.openclaw/state/openclaw.sqlite"
printf '{}\n' > "$SB/home/.openclaw/openclaw.json"
run "$W/tree-with" "$SB" update.sh STUB_NODE_HANG="--no-warnings"
check "C5a: both repair steps end with the [WARN] lines of the existing path, with the reason (node failed)" \
    bash -c 'printf "%s" "$1" | grep -q "Could not check the other-app paths in your OpenClaw state (node failed)" && printf "%s" "$1" | grep -q "Could not fix the folder paths in your OpenClaw config (node failed)"' _ "$OUT"
check "C5b: each waited for the work limit (two calls of class work: ${ELAPSED}s, window 29..60) and the run went on to the end (exit 0)" \
    bash -c '[ "$1" = 0 ] && [ "$2" -ge 29 ] && [ "$2" -lt 60 ]' _ "$RC" "$ELAPSED"

echo "--- C7d: status.sh and verify.sh with openclaw / npm / clawdhub that stop ---"
mk_tools() { # <sandbox>: openclaw and clawdhub programs in the sandbox prefix (the PATH of the case gets them last)
    cat > "$1/prefix/bin/openclaw" <<'EOS'
#!/bin/sh
[ -n "$STUB_OC_HANG" ] && exec sleep 600
if [ -n "$STUB_OC_KILL_GP" ]; then
    # (SIGINT to the shell of the call only: the parent of the timeout that runs this)
    if [ -r "/proc/$PPID/stat" ]; then gp=$(awk '{print $4}' "/proc/$PPID/stat"); else gp=$(ps -o ppid= -p "$PPID" | tr -d ' '); fi
    [ -z "$gp" ] || kill -INT "$gp"
    exec sleep 600
fi
echo 2026.1.1
EOS
    printf '#!/bin/sh\n[ -n "$STUB_CH_HANG" ] && exec sleep 600\necho 1.0.0\n' > "$1/prefix/bin/clawdhub"
    chmod +x "$1/prefix/bin/openclaw" "$1/prefix/bin/clawdhub"
}
SB=$(new_sandbox "$OC_PIN"); mk_tools "$SB"
run "$W/tree-with" "$SB" status.sh PATH="$TPATH:$SB/prefix/bin"
check "C7d1: the normal status screen shows the versions" \
    bash -c 'printf "%s" "$1" | grep -q "OpenClaw:    2026.1.1" && printf "%s" "$1" | grep -q "npm:         11.0.0" && printf "%s" "$1" | grep -q "clawdhub:    1.0.0"' _ "$OUT"
SB=$(new_sandbox "$OC_PIN"); mk_tools "$SB"
run "$W/tree-with" "$SB" status.sh PATH="$TPATH:$SB/prefix/bin" STUB_OC_HANG=1 STUB_NPM_V_HANG=1 STUB_CH_HANG=1
check "C7d2: the three that stop say \"unknown (no answer within 5s)\", and the screen ends (${ELAPSED}s, window 14..45)" \
    bash -c 'printf "%s" "$1" | grep -q "OpenClaw:    unknown (no answer within 5s)" && printf "%s" "$1" | grep -q "npm:         unknown (no answer within 5s)" && printf "%s" "$1" | grep -q "clawdhub:    unknown (no answer within 5s)" && [ "$2" -ge 14 ] && [ "$2" -lt 45 ]' _ "$OUT" "$ELAPSED"
check "C7d3: the patch check was given the package folder (it does not ask npm root -g itself)" \
    bash -c 'grep -q "openclaw-patch-hardlink.sh args=\[--check $1/prefix/lib/node_modules/openclaw\]" "$1/patch.log" 2>/dev/null' _ "$SB"
SB=$(new_sandbox "$OC_PIN"); mk_tools "$SB"
run "$W/tree-with" "$SB" verify.sh PATH="$TPATH:$SB/prefix/bin" STUB_OC_HANG=1
check "C7d4: verify.sh warns that openclaw --version did not answer, goes on, and judges the version (${ELAPSED}s, window 4..40)" \
    bash -c 'printf "%s" "$1" | grep -q "openclaw --version did not answer within 5s" && printf "%s" "$1" | grep -q "matches the pinned version" && [ "$2" -ge 4 ] && [ "$2" -lt 40 ]' _ "$OUT" "$ELAPSED"

echo "--- C13: a node that prints its version and does not end ---"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" update.sh STUB_NODE_PRINT_HANG="--version"
check "C13a: update.sh: the printed version is an answer: the gate passes (no node_gate note), the run ends 0 and the pinned OpenClaw stays (${ELAPSED}s, window 4..60)" \
    bash -c '[ "$3" = 0 ] && ! grep -qx "reason=node_gate" "$1/notes" && ! printf "%s" "$2" | grep -q "is required (found:" && { n=$(grep -c "^npm install -g openclaw@" "$1/npm.log" 2>/dev/null || true); [ "${n:-0}" = 0 ]; } && [ "$4" -ge 4 ] && [ "$4" -lt 60 ]' _ "$SB" "$OUT" "$RC" "$ELAPSED"
# the two gates of one update (update-core.sh, then update.sh) must give one verdict for the same node
CORE_FN="$(awk '/^_oa_node_version\(\) \{/,/^\}/' "$ROOT/update-core.sh")"
gate_pair() { # <stub env...>  -> "<limits.sh verdict>|<update-core.sh verdict>"
    local sb
    sb=$(new_sandbox none)
    "$OUTER_TIMEOUT" 90 env -i HOME="$sb/home" PREFIX="$sb/prefix" TMPDIR="$sb/prefix/tmp" PATH="$TPATH" TERM=dumb \
        STUB_STATE="$sb/state" STUB_NODE_VERSION="$NODE_PIN" OA_NODE_PROBE_TIMEOUT=0005 "$@" \
        "$BASH_BIN" -c '
            source "$1/scripts/lib.sh"; source "$1/platforms/openclaw/limits.sh"; eval "$2"
            oc=$(oc_node_version)
            case "$oc" in "no answer"*) oc=noanswer ;; esac
            rc=0; core=$(_oa_node_version node) || rc=$?
            if [ "$rc" = 75 ]; then core=noanswer; elif [ "$rc" != 0 ] || [ -z "$core" ]; then core=none; fi
            printf "%s|%s" "$oc" "$core"
        ' _ "$W/tree-with" "$CORE_FN" 2>/dev/null
}
chk_pair() { # <label> <expected verdict> <stub env...>
    local label="$1" want="$2" got; shift 2
    got="$(gate_pair "$@")"
    OUT="$label: limits.sh|update-core.sh = $got (expected $want|$want)"
    check "C13 $label: the same verdict from both ($want)" test "$got" = "$want|$want"
}
chk_pair "answers"           "v$NODE_PIN"
chk_pair "prints, then hangs" "v$NODE_PIN" STUB_NODE_PRINT_HANG="--version"
chk_pair "hangs"             "noanswer"   STUB_NODE_HANG="--version"
chk_pair "stops once"        "v$NODE_PIN" STUB_NODE_HANG_ONCE="--version"
chk_pair "prints junk, hangs" "noanswer"  STUB_NODE_VERSION=junk STUB_NODE_PRINT_HANG="--version"
chk_pair "exits 1 silently"  "none"       STUB_NODE_EXIT=1

echo "--- C19: the version is in the file in a layout that only node reads (the key and its value on separate lines) ---"
SB=$(new_sandbox "$OC_PIN")
printf '{\n  "name": "openclaw",\n  "version"\n    : "%s"\n}\n' "$OC_PIN" > "$SB/prefix/lib/node_modules/openclaw/package.json"
run "$W/tree-with" "$SB" update.sh STUB_NODE_P_ANSWER="$OC_PIN"
check "C19a: node -p answers: that is the installed version, no reinstall" \
    bash -c '[ "$2" = 0 ] && grep -q "^node -p" "$1/node.log" && { n=$(grep -c "^npm install -g openclaw@" "$1/npm.log" 2>/dev/null || true); [ "${n:-0}" = 0 ]; }' _ "$SB" "$RC"
SB=$(new_sandbox "$OC_PIN")
printf '{\n  "name": "openclaw",\n  "version"\n    : "%s"\n}\n' "$OC_PIN" > "$SB/prefix/lib/node_modules/openclaw/package.json"
run "$W/tree-with" "$SB" update.sh STUB_NODE_P_ANSWER="$OC_PIN" STUB_NODE_PRINT_HANG="-p"
check "C19b: node -p prints the version and does not end: still an answer (same rule as the gate), no reinstall (${ELAPSED}s)" \
    bash -c '[ "$2" = 0 ] && { n=$(grep -c "^npm install -g openclaw@" "$1/npm.log" 2>/dev/null || true); [ "${n:-0}" = 0 ]; } && [ "$3" -ge 4 ] && [ "$3" -lt 60 ]' _ "$SB" "$RC" "$ELAPSED"

echo "--- C15: npm list -g clawdhub never answers ---"
SB=$(new_sandbox "$OC_PIN"); mk_tools "$SB"
run "$W/tree-with" "$SB" update.sh PATH="$TPATH:$SB/prefix/bin"
check "C15a: with an npm that answers: \"already the latest\" (the ordinary path is untouched)" \
    bash -c 'printf "%s" "$1" | grep -q "clawdhub 1.0.0 is already the latest"' _ "$OUT"
SB=$(new_sandbox "$OC_PIN"); mk_tools "$SB"
run "$W/tree-with" "$SB" update.sh PATH="$TPATH:$SB/prefix/bin" STUB_NPM_LIST_HANG=1
check "C15b: skipped with a [WARN] naming 5s; no npm view, no npm install of clawdhub; exit 0; waited one probe limit (${ELAPSED}s, window 4..40)" \
    bash -c 'printf "%s" "$1" | grep -q "clawdhub check skipped (npm did not answer within 5s)" && ! grep -q "^npm view" "$2/npm.log" && ! grep -q "^npm install -g clawdhub" "$2/npm.log" && [ "$3" = 0 ] && [ "$4" -ge 4 ] && [ "$4" -lt 40 ]' _ "$OUT" "$SB" "$RC" "$ELAPSED"

echo "--- C17: a one-line package.json with a second \"version\" key further in ---"
SB=$(new_sandbox "$OC_PIN")
printf '{"name":"openclaw","version":"%s","openclaw":{"plugin":{"version":"1.0.0"}}}\n' "$OC_PIN" > "$SB/prefix/lib/node_modules/openclaw/package.json"
run "$W/tree-with" "$SB" update.sh STUB_NODE_HANG="-p"
check "C17a: the first \"version\" is the package's: no reinstall, node -p not called" \
    bash -c 'n=$(grep -c "^npm install -g openclaw@" "$1/npm.log" 2>/dev/null); [ "${n:-0}" = 0 ] && ! grep -q "^node -p" "$1/node.log" 2>/dev/null' _ "$SB"

echo "--- C18: the limit that a message names ---"
lim() { env -i PATH="$TPATH" "$@" "$BASH_BIN" -c 'source "$1/scripts/lib.sh"; source "$1/platforms/openclaw/limits.sh"; printf "%s %s" "$(oc_limit_seconds probe)" "$(oc_limit_seconds work)"' _ "$W/tree-with" 2>/dev/null; }
L=$(lim OA_NODE_PROBE_TIMEOUT=0005 OA_NODE_WORK_TIMEOUT=0015)
OUT="probe/work = $L"; check "C18a: leading zeros: 5 15" test "$L" = "5 15"
L=$(lim OA_NODE_PROBE_TIMEOUT=4 OA_NODE_WORK_TIMEOUT=abc)
OUT="probe/work = $L"; check "C18b: a value below 5 or not a number is the default: 30 300 (what lib.sh applies)" test "$L" = "30 300"
L=$(lim OA_NODE_PROBE_TIMEOUT=12 OA_NODE_WORK_TIMEOUT=1000000)
OUT="probe/work = $L"; check "C18c: 12, and 7 digits is the default: 12 300" test "$L" = "12 300"
L=$(lim)
OUT="probe/work = $L"; check "C18d: unset: 30 300" test "$L" = "30 300"

echo "--- C20: Ctrl-C while a probe waits ---"
SB=$(new_sandbox "$OC_PIN")
RUN_INT_AFTER=2 run "$W/tree-with" "$SB" update.sh STUB_NODE_CHILD_HANG="--version"
L3=$(leftover)
check "C20a: update.sh (the Node.js gate waits in oc_node_version): ended by SIGINT, exit 130, in ${ELAPSED}s (window 1..5: before the 5 s limit), no child left" \
    bash -c '[ "$1" = 130 ] && [ "$2" -ge 1 ] && [ "$2" -lt 5 ] && [ -z "$3" ]' _ "$RC" "$ELAPSED" "$L3"
check "C20b: it did not go on after the signal: no gate message, no npm install" \
    bash -c '! printf "%s" "$1" | grep -q "is required (found:" && ! grep -q "^npm install" "$2/npm.log" 2>/dev/null' _ "$OUT" "$SB"
SB=$(new_sandbox "$OC_PIN"); mk_tools "$SB"
RUN_INT_AFTER=2 run "$W/tree-with" "$SB" verify.sh PATH="$TPATH:$SB/prefix/bin" STUB_OC_HANG=1
check "C20c: verify.sh (openclaw --version waits in oc_capture): ended by SIGINT, exit 130, in ${ELAPSED}s (window 1..5)" \
    bash -c '[ "$1" = 130 ] && [ "$2" -ge 1 ] && [ "$2" -lt 5 ]' _ "$RC" "$ELAPSED"
for p in $(leftover); do [ "$p" = "?" ] || kill "$p" 2>/dev/null || true; done

echo "--- C21: SIGINT reaches only the shell of the call ---"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" update.sh STUB_NODE_KILL_GP="--version"
check "C21a: update.sh: ended as SIGINT (130) at once (${ELAPSED}s, window 0..5), no gate message, no npm install" \
    bash -c '[ "$1" = 130 ] && [ "$2" -lt 5 ] && ! printf "%s" "$3" | grep -q "is required (found:" && ! grep -q "^npm install" "$4/npm.log" 2>/dev/null' _ "$RC" "$ELAPSED" "$OUT" "$SB"
SB=$(new_sandbox "$OC_PIN"); mk_tools "$SB"
run "$W/tree-with" "$SB" verify.sh PATH="$TPATH:$SB/prefix/bin" STUB_OC_KILL_GP=1
check "C21b: verify.sh: ended as SIGINT (130) at once (${ELAPSED}s), it did not go on to report \"--version failed\" and the rest" \
    bash -c '[ "$1" = 130 ] && [ "$2" -lt 5 ] && ! printf "%s" "$3" | grep -q "found but --version failed" && ! printf "%s" "$3" | grep -q "matches the pinned version"' _ "$RC" "$ELAPSED" "$OUT"
for p in $(leftover); do [ "$p" = "?" ] || kill "$p" 2>/dev/null || true; done

echo "--- C10: the normal path ---"
SB=$(new_sandbox "$OC_PIN")
run "$W/tree-with" "$SB" update.sh
OUT_WITH="$OUT"; RC_WITH=$RC
SB2=$(new_sandbox "$OC_PIN")
run "$W/tree-without" "$SB2" update.sh
norm() { printf '%s' "$1" | sed -e "s#$2#<SANDBOX>#g"; }
check "C10a: with and without the limit function the output (sandbox path aside) and the exit status are the same" \
    bash -c '[ "$1" = "$2" ] && [ "$3" = "$4" ]' _ "$RC_WITH" "$RC" "$(norm "$OUT_WITH" "$SB")" "$(norm "$OUT" "$SB2")"

echo ""
echo "passed: $PASS   failed: $FAIL   (total $TOTAL)"
[ "$FAIL" -eq 0 ]
