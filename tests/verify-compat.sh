#!/usr/bin/env bash
# verify-compat.sh — Compatibility constraint harness
#
# Tests all known mutual-exclusion constraints simultaneously.
# A fix for one axis must not break another. Run after every
# glibc-compat.js or wrapper change.
#
# Usage:
#   bash tests/verify-compat.sh            (on device)
#   ssh device 'bash ~/verify-compat.sh'   (remote)

set -uo pipefail

PASS=0; FAIL=0; TOTAL=0
RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); TOTAL=$((TOTAL+1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); TOTAL=$((TOTAL+1)); }

check() {
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi
}

node_check() {
    local desc="$1" code="$2"
    if node -e "$code" 2>/dev/null; then pass "$desc"; else fail "$desc"; fi
}

# A known gap is a limitation we have measured and deliberately not fixed yet.
# It is reported but never counted as a failure. If it starts working, say so
# loudly ([GAP-CLOSED]) so the check is promoted to a regular one instead of
# silently turning green.
KNOWN=0; CLOSED=0
YELLOW='\033[1;33m'
known_gap() {
    local desc="$1" got="$2" want="$3"
    if [ "$got" = "$want" ]; then
        echo -e "${YELLOW}[GAP-CLOSED]${NC} $desc — works now; promote this to a regular check"
        CLOSED=$((CLOSED+1))
    else
        echo -e "${YELLOW}[KNOWN-GAP]${NC} $desc (got: ${got:-empty})"
        KNOWN=$((KNOWN+1))
    fi
}

echo "=== Compatibility Constraint Harness ==="
echo ""

# ─────────────────────────────────────────────────
# AXIS 1: LD_PRELOAD lifecycle
#   Constraint A: node.real must load WITHOUT bionic LD_PRELOAD
#   Constraint B: node must NOT carry LD_PRELOAD back into its own environment
#                 or its children — libtermux-exec.so re-injects it on execve and
#                 glibc children then die with "Could not find a PHDR".
#                 (glibc-compat.js deletes LD_PRELOAD on purpose since v1.0.22;
#                 shebang resolution is done in JS instead — see axis 2.)
# ─────────────────────────────────────────────────
echo "--- Axis 1: LD_PRELOAD lifecycle ---"

# 1a: node.real itself must not have libtermux-exec loaded in its own process
#     (if it did, glibc/bionic mismatch would crash — the fact that we're
#     running means it didn't, but verify the wrapper structure)
WRAPPER=$(node -e "process.stdout.write(process.env._OA_WRAPPER_PATH || '')" 2>/dev/null)
if [ -n "$WRAPPER" ] && [ -f "$WRAPPER" ] && grep -q "unset LD_PRELOAD" "$WRAPPER"; then
    pass "1a: node wrapper unsets LD_PRELOAD before exec"
else
    fail "1a: node wrapper missing or missing 'unset LD_PRELOAD' (path: ${WRAPPER:-not set})"
fi

# 1b: LD_PRELOAD must NOT be in node's environment (compat deletes it, and the
#     saved copy _OA_ORIG_LD_PRELOAD too). "|" = both empty; a crash prints nothing.
NODE_LP=$(node -e "process.stdout.write((process.env.LD_PRELOAD||'')+'|'+(process.env._OA_ORIG_LD_PRELOAD||''))" 2>/dev/null)
if [ "$NODE_LP" = "|" ]; then
    pass "1b: no LD_PRELOAD / _OA_ORIG_LD_PRELOAD in node env"
else
    fail "1b: node env carries LD_PRELOAD (got: ${NODE_LP:-node failed})"
fi

# 1c: a child of node gets no LD_PRELOAD either ("[]" = child ran and saw none)
CHILD_LP=$(node -e '
const { execFileSync } = require("child_process");
process.stdout.write("[" + execFileSync("sh", ["-c", "printf %s \"$LD_PRELOAD\""], {encoding:"utf8"}) + "]");
' 2>/dev/null)
if [ "$CHILD_LP" = "[]" ]; then
    pass "1c: child of node inherits no LD_PRELOAD"
else
    fail "1c: child of node has LD_PRELOAD or failed (got: ${CHILD_LP:-empty})"
fi

# 1d: a glibc child (node itself, via the wrapper) starts without a PHDR error —
#     the failure mode that restoring LD_PRELOAD caused
CHILD_NODE=$(node -e '
const { execFileSync } = require("child_process");
process.stdout.write(execFileSync(process.execPath, ["-p", "6*7"], {encoding:"utf8"}).trim());
' 2>/dev/null)
if [ "$CHILD_NODE" = "42" ]; then
    pass "1d: glibc child (node via wrapper) starts normally"
else
    fail "1d: glibc child failed to start (got: ${CHILD_NODE:-empty})"
fi

# ─────────────────────────────────────────────────
# AXIS 2: Shebang resolution
#   Constraint A: #!/usr/bin/env must resolve in child processes. Android has no
#                 /usr/bin/env and libtermux-exec is no longer injected, so
#                 glibc-compat.js resolves the shebang in JS for the child_process
#                 calls it wraps: spawn, spawnSync, execFile, execFileSync, and
#                 exec (which goes through execFile).
#   Constraint B: our own wrappers must NOT use #!/usr/bin/env
#   Known gaps (measured, not fixed — see M1): execSync is not wrapped (Node's
#   execSync calls its module-local spawnSync, so patching the export is
#   bypassed), and compound shell commands (| > < & ; $ ( ) ` { }) are skipped.
# ─────────────────────────────────────────────────
echo "--- Axis 2: Shebang resolution ---"

TMPSCRIPT="$(mktemp "${TMPDIR:-/tmp}/compat-test.XXXXXX")"
echo '#!/usr/bin/env sh' > "$TMPSCRIPT"
echo 'echo shebang-ok' >> "$TMPSCRIPT"
chmod +x "$TMPSCRIPT"

# run one JS snippet against the script; snippet prints the child's stdout
shebang_try() {
    SCRIPT="$TMPSCRIPT" node -e "$1" 2>/dev/null
}
shebang_check() {
    local desc="$1" js="$2" out
    out=$(shebang_try "$js")
    if [ "$out" = "shebang-ok" ]; then
        pass "$desc"
    else
        fail "$desc (got: ${out:-empty})"
    fi
}

shebang_check "2a-1: spawnSync(script) resolves #!/usr/bin/env" \
    'const cp=require("child_process");process.stdout.write(cp.spawnSync(process.env.SCRIPT,[],{encoding:"utf8"}).stdout.trim())'
shebang_check "2a-2: execFileSync(script) resolves #!/usr/bin/env" \
    'const cp=require("child_process");process.stdout.write(cp.execFileSync(process.env.SCRIPT,[],{encoding:"utf8"}).trim())'
shebang_check "2a-3: spawnSync('sh', ['-c', script]) resolves #!/usr/bin/env" \
    'const cp=require("child_process");process.stdout.write(cp.spawnSync("sh",["-c",process.env.SCRIPT],{encoding:"utf8"}).stdout.trim())'
shebang_check "2a-4: spawnSync(script, {shell:true}) resolves #!/usr/bin/env" \
    'const cp=require("child_process");process.stdout.write(cp.spawnSync(process.env.SCRIPT,[],{shell:true,encoding:"utf8"}).stdout.trim())'
shebang_check "2a-5: exec(script) (async, via execFile) resolves #!/usr/bin/env" \
    'require("child_process").exec(process.env.SCRIPT,{encoding:"utf8"},(e,out)=>process.stdout.write((out||"").trim()))'

shebang_check "2a-6: spawn('sh', ['-c', script]) (async) resolves #!/usr/bin/env" \
    'const cp=require("child_process");let o="";const c=cp.spawn("sh",["-c",process.env.SCRIPT]);c.stdout.on("data",d=>{o+=d});c.on("close",()=>process.stdout.write(o.trim()))'

# Known gaps — the expected result is that they do NOT work yet
known_gap "2c: execSync(script) with #!/usr/bin/env (execSync is not wrapped by glibc-compat.js)" \
    "$(shebang_try 'const cp=require("child_process");process.stdout.write(cp.execSync(process.env.SCRIPT,{encoding:"utf8"}).trim())')" "shebang-ok"
known_gap "2d: spawnSync('sh', ['-c', 'script | cat']) — compound shell commands are not resolved" \
    "$(shebang_try 'const cp=require("child_process");process.stdout.write(cp.spawnSync("sh",["-c",process.env.SCRIPT+" | cat"],{encoding:"utf8"}).stdout.trim())')" "shebang-ok"
rm -f "$TMPSCRIPT"

# 2b: our wrappers do NOT use #!/usr/bin/env
OUR_WRAPPERS_OK=true
WRAPPER_DIR=$(dirname "$WRAPPER" 2>/dev/null)
for f in "$WRAPPER_DIR/node" "$WRAPPER_DIR/npm" "$WRAPPER_DIR/npx"; do
    if [ -f "$f" ] && head -1 "$f" | grep -q "/usr/bin/env"; then
        fail "2b: $f uses #!/usr/bin/env (will break)"
        OUR_WRAPPERS_OK=false
    fi
done
$OUR_WRAPPERS_OK && pass "2b: our wrappers avoid #!/usr/bin/env"

# ─────────────────────────────────────────────────
# AXIS 3: process identity
#   Constraint A: process.platform must be 'linux'
#   Constraint B: process.execPath must point to wrapper, not ld.so
# ─────────────────────────────────────────────────
echo "--- Axis 3: process identity ---"

node_check "3a: process.platform === 'linux'" \
    "if (process.platform !== 'linux') process.exit(1)"

EXEC_PATH=$(node -e "process.stdout.write(process.execPath)" 2>/dev/null)
if [ -x "$EXEC_PATH" ] && ! file "$EXEC_PATH" 2>/dev/null | grep -q ELF; then
    pass "3b: process.execPath → wrapper script (not ld.so)"
else
    fail "3b: process.execPath is not a wrapper script: $EXEC_PATH"
fi

# ─────────────────────────────────────────────────
# AXIS 4: OS API shims
#   Constraint: patched APIs must return valid data
# ─────────────────────────────────────────────────
echo "--- Axis 4: OS API shims ---"

node_check "4a: os.cpus().length > 0" \
    "if (require('os').cpus().length === 0) process.exit(1)"

node_check "4b: os.networkInterfaces() does not throw" \
    "require('os').networkInterfaces()"

# ─────────────────────────────────────────────────
# AXIS 5: DNS resolution
#   Constraint: dns.lookup must work without resolv.conf
# ─────────────────────────────────────────────────
echo "--- Axis 5: DNS resolution ---"

DNS_OK=$(node -e "
const dns = require('dns');
dns.lookup('github.com', (err, addr) => {
    if (err) process.exit(1);
    process.stdout.write(addr);
    process.exit(0);
});
" 2>/dev/null)
if [ -n "$DNS_OK" ]; then
    pass "5a: dns.lookup('github.com') → $DNS_OK"
else
    fail "5a: dns.lookup('github.com') failed"
fi

# ─────────────────────────────────────────────────
# AXIS 6: child_process shell
#   Constraint A: /bin/sh must work (or shim must be active)
#   Constraint B: exec/execSync must default to valid shell
# ─────────────────────────────────────────────────
echo "--- Axis 6: child_process shell ---"

node_check "6a: child_process.execSync works" \
    "require('child_process').execSync('echo ok', {encoding:'utf8'})"

CHILD_PLATFORM=$(node -e "
const { execSync } = require('child_process');
process.stdout.write(execSync('node -e \"process.stdout.write(process.platform)\"', {encoding:'utf8'}));
" 2>/dev/null)
if [ "$CHILD_PLATFORM" = "linux" ]; then
    pass "6b: child node also reports platform=linux"
else
    fail "6b: child node platform=$CHILD_PLATFORM (expected linux)"
fi

# ─────────────────────────────────────────────────
# AXIS 7: npm lifecycle
#   Constraint: npm install with lifecycle scripts must succeed
# ─────────────────────────────────────────────────
echo "--- Axis 7: npm lifecycle ---"

NPM_SCRIPT_SHELL=$(npm config get script-shell 2>/dev/null)
if [ -n "$NPM_SCRIPT_SHELL" ] && [ -x "$NPM_SCRIPT_SHELL" ]; then
    pass "7a: npm script-shell=$NPM_SCRIPT_SHELL (executable)"
else
    fail "7a: npm script-shell not set or not executable ($NPM_SCRIPT_SHELL)"
fi

# ─────────────────────────────────────────────────
# AXIS 8: glibc-compat.js integrity
#   Constraint: all shims must be loaded
# ─────────────────────────────────────────────────
echo "--- Axis 8: glibc-compat.js integrity ---"

COMPAT="$HOME/.openclaw-android/lib/glibc-compat.js"
if [ -s "$COMPAT" ]; then
    pass "8a: glibc-compat.js exists (lib/)"
else
    fail "8a: glibc-compat.js missing in lib/ (the wrapper falls back to patches/, which the app overwrites)"
fi

NODE_OPTS=$(node -e "process.stdout.write(process.env.NODE_OPTIONS||'')" 2>/dev/null)
if echo "$NODE_OPTS" | grep -qF "$COMPAT"; then
    pass "8b: NODE_OPTIONS loads lib/glibc-compat.js"
else
    fail "8b: lib/glibc-compat.js not in NODE_OPTIONS ($NODE_OPTS)"
fi

# 8c-8e: OpenClaw 2026.9.x's native fs-safe helper probes openat2, which Android's app
# sandbox answers with SIGSYS; the wrapper turns that one probe off (everything else
# stays native) and leaves the user's own FS_SAFE_NATIVE_MODE alone.
HOOK=$(env -u FS_SAFE_TEST_NO_OPENAT2 node -e "process.stdout.write(process.env.FS_SAFE_TEST_NO_OPENAT2||'')" 2>/dev/null)
if [ "$HOOK" = "1" ]; then
    pass "8c: wrapper sets FS_SAFE_TEST_NO_OPENAT2=1 by default"
else
    fail "8c: FS_SAFE_TEST_NO_OPENAT2 is '$HOOK' without a user setting (want '1')"
fi
HOOK=$(FS_SAFE_TEST_NO_OPENAT2=0 node -e "process.stdout.write(process.env.FS_SAFE_TEST_NO_OPENAT2||'')" 2>/dev/null)
if [ "$HOOK" = "0" ]; then
    pass "8d: wrapper keeps a FS_SAFE_TEST_NO_OPENAT2 the user set"
else
    fail "8d: user-set FS_SAFE_TEST_NO_OPENAT2=0 became '$HOOK'"
fi
FS_MODE=$(FS_SAFE_NATIVE_MODE=require node -e "process.stdout.write(process.env.FS_SAFE_NATIVE_MODE||'')" 2>/dev/null)
if [ "$FS_MODE" = "require" ]; then
    pass "8e: wrapper leaves the user's FS_SAFE_NATIVE_MODE alone"
else
    fail "8e: user-set FS_SAFE_NATIVE_MODE=require became '$FS_MODE'"
fi

# 8f/8g: OpenClaw's gateway starts a self-update as '<node wrapper> <openclaw>/dist/index.js
# update --yes --json', which bypasses the openclaw command's own guard; the wrapper blocks it.
# (-e 0 keeps OpenClaw itself from running if the guard ever stops working: the wrapper only looks at the path)
UPD_OUT=$(node -e 0 "$PREFIX/lib/node_modules/openclaw/dist/index.js" update --yes --json 2>&1); UPD_RC=$?
if [ "$UPD_RC" -eq 1 ] && echo "$UPD_OUT" | grep -q '^\[BLOCKED\]'; then
    pass "8f: wrapper blocks an OpenClaw self-update started through node"
else
    fail "8f: 'node …/openclaw/dist/index.js update' was not blocked (rc=$UPD_RC)"
fi
UPD_OUT=$(node -e 0 "$PREFIX/lib/node_modules/openclaw/dist/index.js" update status 2>&1 || true)
if echo "$UPD_OUT" | grep -q '^\[BLOCKED\]'; then
    fail "8g: 'update status' (read-only) must not be blocked"
else
    pass "8g: wrapper lets 'update status' through"
fi

# 8h: leading value options reach node unchanged (the wrapper once moved them to NODE_OPTIONS
# and dropped the value: '--require X' became '--require', which node rejected there).
_H_DIR=$(mktemp -d)
echo 'globalThis.__oa_req = "ok";' > "$_H_DIR/r.cjs"
H_OUT=$(node --require "$_H_DIR/r.cjs" -p 'globalThis.__oa_req' 2>&1)
# the shapes OpenClaw 9.8's update hand-off uses: --import <data: URL> and --input-type=module -e
H_OUT2=$(node --import 'data:text/javascript,globalThis.__oa_imp=1' --input-type=module -e 'process.stdout.write(String(globalThis.__oa_imp))' 2>&1)
rm -rf "$_H_DIR"
if [ "$H_OUT" = "ok" ] && [ "$H_OUT2" = "1" ]; then
    pass "8h: wrapper passes leading '--require <file>' and '--import <url>' to node unchanged"
else
    fail "8h: leading --require/--import options did not reach node (got: $H_OUT / $H_OUT2)"
fi

# ─────────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────────
echo ""
echo "==============================="
echo -e "  Results: ${GREEN}$PASS passed${NC} / ${RED}$FAIL failed${NC} / $TOTAL total"
echo -e "  Known gaps: $KNOWN open / $CLOSED closed (not counted as failures)"
echo "==============================="
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo -e "${RED}COMPAT CHECK FAILED${NC} — fix failures before committing."
    exit 1
else
    echo -e "${GREEN}ALL CONSTRAINTS SATISFIED${NC}"
fi
