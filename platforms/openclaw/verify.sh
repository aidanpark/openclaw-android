#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$BASE_DIR/scripts/lib.sh"

PASS=0
FAIL=0
WARN=0

check_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASS=$((PASS + 1))
}

check_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    FAIL=$((FAIL + 1))
}

check_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    WARN=$((WARN + 1))
}

echo "=== OpenClaw Platform Verification ==="
echo ""

if command -v openclaw &>/dev/null; then
    CLAW_VER=$(openclaw --version 2>/dev/null || true)
    if [ -n "$CLAW_VER" ]; then
        check_pass "openclaw $CLAW_VER"
    else
        check_fail "openclaw found but --version failed"
    fi
else
    check_fail "openclaw command not found"
fi

# `openclaw --version` alone is not enough: an unsupported release can still
# print its version and then fail to start the gateway. Compare against the pin.
PIN_VER=""
if load_platform_config openclaw "$BASE_DIR" >/dev/null; then
    PIN_VER="${PLATFORM_NPM_PACKAGE_VERSION:-}"
fi
INSTALLED_VER=""
OPENCLAW_PKG="$(npm root -g 2>/dev/null)/openclaw/package.json"
if [ -f "$OPENCLAW_PKG" ]; then
    INSTALLED_VER=$(node -p "require('$OPENCLAW_PKG').version" 2>/dev/null || echo "")
fi
if [ -z "$PIN_VER" ]; then
    check_warn "OpenClaw version pin not found in config.env — skipping pin check"
elif [ "$INSTALLED_VER" = "$PIN_VER" ]; then
    check_pass "openclaw package $INSTALLED_VER matches the pinned version"
else
    check_fail "openclaw package ${INSTALLED_VER:-not found} does not match the pinned version $PIN_VER (run: oa --update)"
fi

if grep -q "version-pin guard" "$PREFIX/bin/openclaw" 2>/dev/null; then
    check_pass "openclaw update guard installed"
else
    check_warn "openclaw update guard missing — 'openclaw update' can leave the pinned version (run: oa --update)"
fi

if [ "${CONTAINER:-}" = "1" ]; then
    check_pass "CONTAINER=1"
else
    check_warn "CONTAINER is not set to 1"
fi

if command -v clawdhub &>/dev/null; then
    check_pass "clawdhub command available"
else
    check_warn "clawdhub not found"
fi

if [ -d "$HOME/.openclaw" ]; then
    check_pass "Directory $HOME/.openclaw exists"
else
    check_fail "Directory $HOME/.openclaw missing"
fi

echo ""
echo "==============================="
echo -e "  Results: ${GREEN}$PASS passed${NC}, ${RED}$FAIL failed${NC}, ${YELLOW}$WARN warnings${NC}"
echo "==============================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
