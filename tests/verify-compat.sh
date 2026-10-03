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

echo "=== Compatibility Constraint Harness ==="
echo ""

# ─────────────────────────────────────────────────
# AXIS 1: LD_PRELOAD lifecycle
#   Constraint A: node.real must load WITHOUT bionic LD_PRELOAD
#   Constraint B: child bionic processes must HAVE LD_PRELOAD
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

# 1b: LD_PRELOAD must be restored in node's environment (for child inheritance)
node_check "1b: LD_PRELOAD restored in node env" \
    "if (!process.env.LD_PRELOAD) process.exit(1)"

# 1c: child /bin/sh inherits LD_PRELOAD
CHILD_LP=$(node -e "
const { execSync } = require('child_process');
process.stdout.write(execSync('echo \$LD_PRELOAD', {encoding:'utf8'}).trim());
" 2>/dev/null)
if [ -n "$CHILD_LP" ] && echo "$CHILD_LP" | grep -q "libtermux-exec"; then
    pass "1c: child sh inherits LD_PRELOAD (libtermux-exec)"
else
    fail "1c: child sh missing LD_PRELOAD (got: ${CHILD_LP:-empty})"
fi

# ─────────────────────────────────────────────────
# AXIS 2: Shebang resolution
#   Constraint A: #!/usr/bin/env must resolve in child processes
#   Constraint B: our own wrappers must NOT use #!/usr/bin/env
# ─────────────────────────────────────────────────
echo "--- Axis 2: Shebang resolution ---"

# 2a: shebang with /usr/bin/env works from node child process
TMPSCRIPT="$(mktemp "${TMPDIR:-/tmp}/compat-test.XXXXXX")"
echo '#!/usr/bin/env sh' > "$TMPSCRIPT"
echo 'echo shebang-ok' >> "$TMPSCRIPT"
chmod +x "$TMPSCRIPT"
SHEBANG_OUT=$(node -e "
const { execSync } = require('child_process');
process.stdout.write(execSync('$TMPSCRIPT', {encoding:'utf8'}).trim());
" 2>/dev/null)
if [ "$SHEBANG_OUT" = "shebang-ok" ]; then
    pass "2a: #!/usr/bin/env sh shebang resolves from node"
else
    fail "2a: #!/usr/bin/env sh shebang failed (got: ${SHEBANG_OUT:-empty})"
fi
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

# ─────────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────────
echo ""
echo "==============================="
echo -e "  Results: ${GREEN}$PASS passed${NC} / ${RED}$FAIL failed${NC} / $TOTAL total"
echo "==============================="
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo -e "${RED}COMPAT CHECK FAILED${NC} — fix failures before committing."
    exit 1
else
    echo -e "${GREEN}ALL CONSTRAINTS SATISFIED${NC}"
fi
