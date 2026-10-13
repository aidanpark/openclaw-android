#!/bin/bash
# stage1.sh <openclaw-version> <install|run> - stage 1 of the new-version check, run INSIDE containers by
# tests/verify-openclaw-smoke.sh (do not run it on a host: it needs the trap, /repo, /out and /home/node of the
# containers). Two phases share one Docker volume mounted at /home/node:
#
#   install  (container WITH network, runs no package code: npm install --ignore-scripts only)
#            new install of the candidate twice (a tree that gets our patches, a raw tree)
#   run      (container WITHOUT network: package code runs here, and cannot reach the host or the internet)
#            patches applied (problems=0) and the check fails on the raw tree (control)
#            gateway: first start, restart after SIGTERM, restart after SIGKILL, each with the PID namespace file
#              (/proc/self/ns/pid) normal, ENOENT and EACCES in EVERY thread
#            doctor, backup create + verify and one agent turn through the mock model, with the file denied
#            controls C1..C7: each removes one cause (see "Facts" below)
#
# Every judged row is printed as "[ok]" or "[NOT OK]" (or "[SKIPPED]"; "[NOT RUN]" marks a step that was not
# started because the system-call filter could not be installed); the last line is "RESULT: ...".
# Exit: 0 = every row ok   1 = a row is not ok   2 = could not judge (install failed, mock model missing, ...)
#
# Facts this script is built on (found while running it by hand for 2026.10.2):
#   - The namespace file cannot be denied with NODE_OPTIONS=--require: OpenClaw starts some worker threads with an
#     environment of their own, so the preload does not reach them and a correctly patched gateway looks broken.
#     The path string is rewritten in every JavaScript file of dist/ instead (after the patches are applied) and the number of files
#     that still name it is a judged row.
#   - A control that "fails" proves nothing unless the reason is the one it claims (the first draft's control failed
#     at the link trap and was read as a namespace proof). Each control therefore has a partner that removes only
#     that cause and has to succeed. The gateway's own reason line is the same for the link trap and the openat2
#     trap (bootstrap failed): the partner rows are the proof, the reason line only excludes the namespace.
#   - A gateway that answers /health once and then dies is not a working gateway: every start is watched for
#     20 s after its first answer (its heartbeats run every 15 s, so the first one falls into the window) and
#     every SIGTERM stop is judged (it has to
#     end within 15 s with exit status 0: a shutdown that crashes is not a stop).
#   - doctor, backup create and the agent turn read /proc/self/ns/pid themselves (strace, 2026.10.2: doctor 5
#     times, backup create 2, agent 2, backup verify 0): they are run with the file denied, like the gateway.
#   - Package output reaches this log (reasons are taken from it): it is cleaned before it is printed.
# /repo is a read-only mount of tests/openclaw-smoke (the mock model is in it) and platforms/openclaw; /out is a
# writable directory of this run that the host never reads.

set -u
unset CDPATH
V=${1:-}
PHASE=${2:-}
case "$V" in ''|-*|*[!0-9A-Za-z._-]*) echo "stage1.sh: not a version: '$V'" >&2; exit 2 ;; esac
case "$PHASE" in install|run) ;; *) echo "stage1.sh: phase has to be install or run" >&2; exit 2 ;; esac
export W=/home/node/w
mkdir -p "$W" || { echo "stage1.sh: cannot create $W" >&2; exit 2; }
[ -w /out ] || { echo "stage1.sh: /out is not writable" >&2; exit 2; }
# npm's cache lives in the container, not in the volume: an answer of a service that npm was sent to (a URL
# dependency in the package metadata) must not reach the run phase, which runs the package's code
export npm_config_cache=/tmp/oa-npmcache npm_config_update_notifier=false
# -I: the harness's own interpreter ignores PYTHON* variables and the user site directory (package code runs
# under the same user and HOME)
T="python3 -I /repo/tests/openclaw-smoke/trap_android.py"
HL=/repo/platforms/openclaw/patches/openclaw-patch-hardlink.sh
PAT=$W/pat; RAW=$W/raw
DP=$PAT/lib/node_modules/openclaw; DR=$RAW/lib/node_modules/openclaw
strip() { sed 's/\x1b\[[0-9;]*m//g'; }
# package data reaches the log: one line, no control characters, nothing a workflow runner would read as a command
# (ASCII only: a cut in the middle of a multi-byte character must not reach a host that reads this log)
clean() { printf '%s' "$1" | LC_ALL=C tr '\n\r\t' '   ' | LC_ALL=C tr -c ' -~' '?' | sed -e 's/##\[/# #[/g' -e ':a' -e 's/::/: :/' -e 'ta'; }
say() { printf '%s\n' "$*"; }
BAD=0; ROWS=0; SKIPPED=0
row() { # <label> <1|0 ok> [detail]
    ROWS=$((ROWS+1))
    if [ "$2" = 1 ]; then say "[ok]     $1${3:+ - $(clean "$3")}"; else BAD=$((BAD+1)); say "[NOT OK] $1${3:+ - $(clean "$3")}"; fi
}
skip() { SKIPPED=$((SKIPPED+1)); say "[SKIPPED] $1${2:+ - $2}"; }
finish() {
    echo "######## $PHASE phase done"
    if [ "$BAD" != 0 ]; then say "RESULT: NOT OK ($PHASE phase: $BAD of $ROWS rows not ok, $SKIPPED skipped)"; exit 1; fi
    if [ "$SKIPPED" != 0 ]; then say "RESULT: could not judge completely ($PHASE phase: $ROWS rows ok, $SKIPPED skipped)"; exit 2; fi
    say "RESULT: ok ($PHASE phase: $ROWS rows, 0 not ok)"; exit 0
}

echo "######## stage 1, $PHASE phase, OpenClaw $V (node $(node --version))"
want_node=$(sed -n 's/^PLATFORM_NODE_VERSION="\(.*\)"/\1/p' /repo/platforms/openclaw/config.env | head -1)
row "Node.js in the container is the pin ($want_node)" "$([ "v$want_node" = "$(node --version)" ] && echo 1 || echo 0)" "$(node --version)"

# The filter itself has to be accepted by the kernel (a container runtime's seccomp profile can refuse a second
# filter): without this row a refusal would look like a failed npm install. The reason is printed as it is.
terr=$($T true 2>&1 >/dev/null); trc=$?
if [ "$trc" != 0 ]; then
    say "[NOT RUN] the system-call filter could not be installed - $(clean "$terr")"
    say "RESULT: could not judge (the trap is not in place: $PHASE phase stopped before it ran anything)"
    exit 2
fi
row "the system-call filter (trap) is accepted by the kernel" 1 "rc=0"

# =================================================================== install phase (network, no package code)
if [ "$PHASE" = install ]; then
    install_into() { # <prefix>
        local s rc
        mkdir -p "$1/tmp" "$1/bin"
        s=$(date +%s)
        npm_config_git=/bin/false npm_config_prefix=$1 HOME=$W/h-install TMPDIR=$1/tmp $T npm install -g "openclaw@$V" --no-fund --no-audit --ignore-scripts >/dev/null 2>&1
        rc=$?
        row "install $(basename "$1") tree" "$([ $rc = 0 ] && echo 1 || echo 0)" "rc=$rc, $(( $(date +%s)-s )) s"
    }
    install_into $PAT; install_into $RAW
    if [ ! -f "$DP/openclaw.mjs" ] || [ ! -f "$DR/openclaw.mjs" ]; then
        say "RESULT: could not judge (the install failed; nothing after it was run)"; exit 2
    fi
    vp=$(sed -n 's/^  "version": "\(.*\)",/\1/p' "$DP/package.json" | head -1)
    vr=$(sed -n 's/^  "version": "\(.*\)",/\1/p' "$DR/package.json" | head -1)
    row "installed versions equal the requested one" "$([ "$vp" = "$V" ] && [ "$vr" = "$V" ] && echo 1 || echo 0)" "patched tree $vp, raw tree $vr"
    mkdir -p "$W/nsdeny" && chmod 000 "$W/nsdeny"   # as a non-root owner: stat of $W/nsdeny/pid is EACCES
    finish
fi

# =================================================================== run phase (no network)
if [ ! -f "$DP/openclaw.mjs" ] || [ ! -f "$DR/openclaw.mjs" ]; then
    say "RESULT: could not judge (the installed trees are not there)"; exit 2
fi
MOCK=/repo/tests/openclaw-smoke/mock-model/server.mjs
[ -f "$MOCK" ] || MOCK=""
export PREFIX=$PAT HOME=$W/h-patch TMPDIR=$PAT/tmp npm_config_prefix=$PAT
mkdir -p "$HOME/.openclaw-android/platforms/openclaw/patches" "$HOME/.openclaw-android/scripts" "$HOME/.openclaw-android/bin"
# the package's own bin directory goes last: a package that declares a bin named curl or python3 must not stand in
# for the tools this script uses
export PATH=$PATH:$PAT/bin
out=$($T bash /repo/platforms/openclaw/patches/openclaw-apply-patches.sh 2>&1 | strip)
sum=$(printf '%s\n' "$out" | grep -E '^Hard-link fallback' | tail -1)
row "patches applied (problems=0, no NOMATCH/NOTARGET/WRITEFAIL)" "$(if printf '%s' "$sum" | grep -q 'problems=0' && ! printf '%s\n' "$out" | grep -qE 'NOMATCH|NOTARGET|WRITEFAIL'; then echo 1; else echo 0; fi)" "$sum"
chk=$(bash $HL --check "$DP" 2>&1 | strip | grep -E 'Hard-link fallback' | tail -1)
row "--check on the patched tree: todo=0 problems=0" "$(printf '%s' "$chk" | grep -q 'todo=0 .*problems=0' && echo 1 || echo 0)" "$chk"
chk=$(bash $HL --check "$DR" 2>&1 | strip | grep -E 'Hard-link fallback' | tail -1)
row "--check on the raw tree: todo>0 (control: the check can fail)" "$(printf '%s' "$chk" | grep -qE 'todo=[1-9][0-9]*' && echo 1 || echo 0)" "$chk"
export OPENCLAW_NO_AUTO_UPDATE=1

# HTTP status of /health: curl prints 000 itself when it cannot connect (no second 000 is appended)
health() {
    local c
    c=$(curl -q -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:18789/health 2>/dev/null)
    printf '%s' "${c:-000}"
}
gw() { # <label> <dir> <home> <hook 1|0> [<extra env words, e.g. OA_TRAP_LINKAT=none> [<seconds to keep watching>]]
    local label=$1 D=$2 H=$3 hook=$4 extra=${5:-} hold=${6:-0}
    local envv=(env "HOME=$H" OPENCLAW_NO_AUTO_UPDATE=1)
    [ "$hook" = 1 ] && envv+=(FS_SAFE_TEST_NO_OPENAT2=1)
    # shellcheck disable=SC2206  # the words are plain NAME=value pairs written in this script
    [ -z "$extra" ] || envv+=($extra)
    mkdir -p "$H"
    GW_LOG=/out/gw-$label.log
    "${envv[@]}" $T node "$D/openclaw.mjs" gateway --allow-unconfigured --port 18789 --bind loopback > "$GW_LOG" 2>&1 &
    local pid=$! code=000 i
    GW_SECS=0; GW_WHY=""
    for i in $(seq 1 30); do
        sleep 2
        kill -0 $pid 2>/dev/null || break
        code=$(health)
        [ "$code" = 200 ] && break
    done
    GW_SECS=$((i*2))
    if kill -0 $pid 2>/dev/null; then
        GW_PID=$pid; GW_OK=1; GW_RC=""
        [ "$code" = 200 ] || { GW_OK=0; GW_WHY="alive but /health=$code after $GW_SECS s"; kill -9 $pid 2>/dev/null; wait $pid 2>/dev/null; }
        # a gateway that answers once and then dies (its heartbeats run every 15 s) is not a working gateway
        local t=0
        while [ "$GW_OK" = 1 ] && [ "$t" -lt "$hold" ]; do
            sleep 5; t=$((t+5))
            if ! kill -0 $pid 2>/dev/null; then
                wait $pid; GW_RC=$?; GW_OK=0; GW_WHY="exited (rc=$GW_RC) $t s after the first /health=200"
            else
                code=$(health)
                [ "$code" = 200 ] || { GW_OK=0; GW_WHY="/health=$code $t s after the first 200"; kill -9 $pid 2>/dev/null; wait $pid 2>/dev/null; }
            fi
        done
    else
        wait $pid; GW_RC=$?; GW_OK=0
        GW_WHY=$(strip < "$GW_LOG" | grep -a -E 'different PID namespace|Gateway failed to start|gateway-run-bootstrap .*failed' | tail -1 | cut -c1-210)
        [ -n "$GW_WHY" ] || GW_WHY="(no reason line in the log)"
    fi
}
# stop the running gateway with SIGTERM (up to 15 s) and judge that it did stop
stop_term() { # <row label prefix>
    local t0 note rc ok=0; t0=$(date +%s)
    kill -TERM $GW_PID 2>/dev/null
    for _ in $(seq 1 15); do kill -0 $GW_PID 2>/dev/null || break; sleep 1; done
    if kill -0 $GW_PID 2>/dev/null; then kill -9 $GW_PID 2>/dev/null; note="did not stop within 15 s, killed"; else note="stopped"; ok=1; fi
    wait $GW_PID 2>/dev/null; rc=$?
    # a clean shutdown ends with 0 (2026.10.1 and 2026.10.2 both do); a crash on the way out (134, 159, ...) is not a stop
    [ "$rc" = 0 ] || { ok=0; note="$note, exit status is not 0"; }
    # a stop that failed shows the gateway's own drain line (what it was still waiting for), if there is one
    local drain=""
    if [ "$ok" = 0 ]; then
        drain=$(strip < "$GW_LOG" | grep -a 'draining active work before stop' | tail -1 | cut -c1-200)
        [ -z "$drain" ] || drain=", drain line: $drain"
    fi
    row "$1 stops on SIGTERM within 15 s with exit status 0" "$ok" "$note, rc=$rc, $(( $(date +%s)-t0 )) s$drain"
}
stop_kill9() { kill -9 $GW_PID 2>/dev/null; wait $GW_PID 2>/dev/null; }
expect_alive() { # <row label>
    row "$1" "$GW_OK" "$( [ "$GW_OK" = 1 ] && echo "/health=200 after $GW_SECS s" || echo "rc=${GW_RC:-?}: $GW_WHY" )"
}
# the gateway has to stop before /health, with a log line matching <regexp> and not matching <not regexp>
# (both are tested against the whole gateway log, the reason shown is its last matching line)
expect_refused() { # <row label> <regexp> [<regexp that must NOT match>]
    local not=${3:-}
    if [ "$GW_OK" = 1 ]; then row "$1" 0 "unexpected: the gateway started (expected a refusal matching /$2/)"; stop_term "$1"
    elif ! strip < "$GW_LOG" | grep -aqE "$2"; then row "$1" 0 "unexpected reason (wanted /$2/): rc=${GW_RC:-?}: $GW_WHY"
    elif [ -n "$not" ] && strip < "$GW_LOG" | grep -aqE "$not"; then row "$1" 0 "unexpected reason (the log matches /$not/): rc=${GW_RC:-?}: $GW_WHY"
    else row "$1" 1 "rc=${GW_RC:-?}: $GW_WHY"; fi
}

# "denied in every thread": the path of the namespace file is rewritten in every JavaScript file of dist/. F4 is applied BEFORE this.
cp -a "$DP/dist" $W/dist-patched
cp -a "$DR/dist" $W/dist-raw
deny() { # <tree dir> <ENOENT|EACCES>
    local rep='/proc/self/ns/pidX'; [ "$2" = EACCES ] && rep="$W/nsdeny/pid"
    grep -rlZF "/proc/self/ns/pid" "$1/dist" --include='*.js' --include='*.mjs' --include='*.cjs' | xargs -0 sed -i "s#/proc/self/ns/pid#$rep#g"
}
restore() { # <tree dir> <saved dist> [<ENOENT|EACCES>]
    rm -rf "${1:?}/dist"; cp -a "$2" "$1/dist"; [ -z "${3:-}" ] || deny "$1" "$3"
}
# JavaScript files of the whole package that still name the real path (a quote right after it)
nreal() { grep -rlE '/proc/self/ns/pid["`'"'"']' "$1" --include='*.mjs' --include='*.js' --include='*.cjs' 2>/dev/null | wc -l | tr -d ' '; }

eacc=$(node -e 'try{require("fs").statSync(process.argv[1])}catch(e){process.stdout.write(String(e.code))}' "$W/nsdeny/pid" 2>/dev/null)
row "the [EACCES] variant really gives EACCES for this user (it does for a non-root user only; root gets ENOENT)" "$([ "$eacc" = EACCES ] && echo 1 || echo 0)" "stat of the denied path: ${eacc:-no error}, uid $(id -u)"

say "== patched tree: gateway start / restart after SIGTERM / restart after SIGKILL, namespace file normal, ENOENT, EACCES"
for NS in none ENOENT EACCES; do
    if [ "$NS" = none ]; then restore $DP $W/dist-patched; else restore $DP $W/dist-patched $NS; fi
    left=$(nreal $DP); ld=$(nreal $DP/dist); lo=$((left-ld))
    if [ "$NS" = none ]; then say "   namespace file: none (JavaScript files naming the real path: $left)"
    else
        row "[$NS] the namespace path is rewritten in every JavaScript file of dist/" "$([ "$ld" = 0 ] && echo 1 || echo 0)" "$ld file(s) of dist/ still name it"
        # outside dist/ nothing is rewritten: a literal there is a limit of this harness, not a fault of the package
        [ "$lo" = 0 ] || skip "[$NS] JavaScript files outside dist/ name the namespace path ($lo): not rewritten, the gateway rows do not cover them"
    fi
    H=$W/hg-$NS; rm -rf "${H:?}"
    gw $NS-first $DP "$H" 1 "" 20;           expect_alive "[$NS] first start, watched for 20 s";                    [ "$GW_OK" = 1 ] && stop_term "[$NS] first gateway"
    gw $NS-after-term $DP "$H" 1 "" 20;      expect_alive "[$NS] restart right after SIGTERM, watched 20 s";        [ "$GW_OK" = 1 ] && stop_kill9
    gw $NS-after-kill9 $DP "$H" 1 "" 20;     expect_alive "[$NS] restart after SIGKILL (stale lease), watched 20 s"; [ "$GW_OK" = 1 ] && stop_term "[$NS] restarted gateway"
done

say "== doctor / backup / agent turn on the patched tree, namespace file ENOENT, right after a gateway that was SIGKILLed"
restore $DP $W/dist-patched ENOENT
H=$W/hg-d; rm -rf "${H:?}"
gw d-first $DP "$H" 1 "" 20; expect_alive "[ENOENT] gateway that is SIGKILLed before doctor, backup and the agent turn"; [ "$GW_OK" = 1 ] && stop_kill9
export HOME=$H FS_SAFE_TEST_NO_OPENAT2=1
s=$(date +%s); timeout 180 $T node $DP/openclaw.mjs doctor --non-interactive </dev/null > /out/doctor.log 2>&1; rc=$?
row "doctor --non-interactive rc 0" "$([ $rc = 0 ] && echo 1 || echo 0)" "rc=$rc, $(( $(date +%s)-s )) s"
timeout 60 $T node $DP/openclaw.mjs config validate > /out/config-validate.log 2>&1; rc=$?
say "         (config validate rc=$rc - not judged: this state has no config file)"
rm -rf /out/bk && mkdir -p /out/bk
timeout 180 $T node $DP/openclaw.mjs backup create --output /out/bk > /out/backup-create.log 2>&1; rc=$?
row "backup create rc 0" "$([ $rc = 0 ] && echo 1 || echo 0)" "rc=$rc"
f=$(ls /out/bk/*.tar.gz 2>/dev/null | head -1)
if [ -n "$f" ]; then
    timeout 120 $T node $DP/openclaw.mjs backup verify "$f" > /out/backup-verify.log 2>&1; rc=$?
    row "backup verify rc 0 and inventory verified" "$(if [ $rc = 0 ] && strip < /out/backup-verify.log | grep -q 'Canonical SQLite inventory verified'; then echo 1; else echo 0; fi)" "rc=$rc"
else row "backup verify (an archive was produced)" 0 "no archive"; fi
if [ -n "$MOCK" ]; then
    node "$MOCK" --port 18080 > /out/mock.log 2>&1 &
    MP=$!
    sleep 1
    cat > $W/mock.json5 <<'J'
{
  models: { providers: { mock: { baseUrl: "http://127.0.0.1:18080/v1", apiKey: "mock-not-a-key", api: "openai-completions",
    models: [ { id: "mock-exec", name: "Mock exec", reasoning: false, input: ["text"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 32000, maxTokens: 2048 } ] } } },
  agents: { defaults: { model: { primary: "mock/mock-exec" } } },
}
J
    s=$(date +%s)
    timeout 240 $T node $DP/openclaw.mjs agent exec "run echo hi" --config $W/mock.json5 --model mock/mock-exec --json > /out/agent.json 2> /out/agent.err; rc=$?
    mf=$(grep -c 'MOCK-FINAL: the tool returned: hi' /out/agent.json)
    row "agent turn through the mock model: rc 0 and MOCK-FINAL" "$([ $rc = 0 ] && [ "$mf" -ge 1 ] && echo 1 || echo 0)" "rc=$rc, MOCK-FINAL x$mf, $(( $(date +%s)-s )) s"
    kill $MP 2>/dev/null; wait $MP 2>/dev/null
else
    skip "agent turn through the mock model" "tests/openclaw-smoke/mock-model/server.mjs was not found"
fi
unset FS_SAFE_TEST_NO_OPENAT2

say "== controls (each removes one cause; a partner row has to differ from it in that one variable and succeed)"
BOOT='gateway-run-bootstrap .*failed|Gateway failed to start'
restore $DR $W/dist-raw
rm -rf $W/c1; gw c1-raw-fulltrap $DR $W/c1 1
expect_refused "C1 raw tree, full trap, namespace normal: refused at bootstrap (link/reflink refused)" "$BOOT" "different PID namespace"
rm -rf $W/c2; gw c2-raw-nolinktrap $DR $W/c2 1 "OA_TRAP_LINKAT=none OA_TRAP_FICLONE=none" 20
expect_alive "C2 raw tree, link/reflink trap off, namespace normal: starts (the link trap was C1's only cause)"; [ "$GW_OK" = 1 ] && stop_term "C2 gateway"
restore $DR $W/dist-raw ENOENT
rm -rf $W/c3; gw c3-raw-ns $DR $W/c3 1 "OA_TRAP_LINKAT=none OA_TRAP_FICLONE=none"
expect_refused "C3 raw tree (no F4), link trap off, namespace ENOENT: refused for the PID namespace" "different PID namespace"
restore $DR $W/dist-raw
restore $DP $W/dist-patched
rm -rf $W/c4; gw c4-nohook $DP $W/c4 0
expect_refused "C4 patched tree, wrapper hook off, full trap: refused at bootstrap (openat2 trap)" "$BOOT" "different PID namespace"
rm -rf $W/c5; gw c5-nohook-noopenat2 $DP $W/c5 0 "OA_TRAP_OPENAT2=none" 20
expect_alive "C5 patched tree, wrapper hook off, openat2 trap off: starts (the hook was C4's only cause)"; [ "$GW_OK" = 1 ] && stop_term "C5 gateway"
say "   backup create on the raw tree, state of a clean patched run (namespace file normal: no lease from a denied run):"
rm -rf $W/hb6 $W/hb7; cp -a $W/hg-none $W/hb6; cp -a $W/hg-none $W/hb7
rm -rf /out/bk-raw && mkdir -p /out/bk-raw
HOME=$W/hb6 timeout 180 $T node $DR/openclaw.mjs backup create --output /out/bk-raw > /out/backup-raw.log 2>&1; rc=$?
why=$(strip < /out/backup-raw.log | tail -1 | cut -c1-160)
if [ $rc != 0 ] && [ $rc != 124 ] && ! strip < /out/backup-raw.log | grep -q 'different PID namespace'; then c6=1; else c6=0; fi
row "C6 raw tree, full trap: backup create fails (not a timeout, not the PID namespace)" "$c6" "rc=$rc: $why"
rm -rf /out/bk-raw2 && mkdir -p /out/bk-raw2
HOME=$W/hb7 timeout 180 env OA_TRAP_LINKAT=none OA_TRAP_FICLONE=none $T node $DR/openclaw.mjs backup create --output /out/bk-raw2 > /out/backup-raw2.log 2>&1; rc=$?
row "C7 raw tree, link/reflink trap off: backup create succeeds (so C6 failed for the link trap)" "$([ $rc = 0 ] && echo 1 || echo 0)" "rc=$rc"

finish
