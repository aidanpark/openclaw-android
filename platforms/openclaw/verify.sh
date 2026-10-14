#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$BASE_DIR/scripts/lib.sh"
# node and npm calls with a time limit
# shellcheck source=limits.sh
source "$BASE_DIR/platforms/openclaw/limits.sh"

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
    # (with a time limit: a start that does not answer is a warning here, not a stop of the whole check)
    CLAW_VER_RC=0
    CLAW_VER="$(oc_capture probe openclaw --version)" || CLAW_VER_RC=$?
    CLAW_VER="${CLAW_VER%%$'\n'*}"
    if [ "$CLAW_VER_RC" = 75 ] && [ -z "$CLAW_VER" ]; then
        check_warn "openclaw --version did not answer within $(oc_limit_seconds probe)s"
    elif [ -n "$CLAW_VER" ]; then
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
# (the version is read from the file first, then through node with a limit: a node that does not answer is
# "unknown", not "not found")
OPENCLAW_PKG="$(oc_npm_root)/openclaw/package.json"
VER_RC=0
if [ -f "$OPENCLAW_PKG" ]; then
    INSTALLED_VER="$(oc_installed_version "$OPENCLAW_PKG")" || VER_RC=$?
fi
if [ "$VER_RC" = 75 ]; then
    check_warn "the installed openclaw version could not be read (node did not answer in time)"
elif [ -z "$PIN_VER" ]; then
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
