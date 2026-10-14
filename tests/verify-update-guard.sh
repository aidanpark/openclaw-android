#!/usr/bin/env bash
# verify-update-guard.sh — Cache-skew guard harness (OA_UPDATE_CORE_PROTOCOL)
#
# Right after a release the raw update-core.sh can still be an older copy while the
# tarball it downloads is already new. The guard stops that pair before anything is
# changed: update-core.sh and install.sh export OA_UPDATE_CORE_PROTOCOL=<N>, and the two
# scripts that change a version (scripts/install-nodejs.sh for Node.js,
# platforms/openclaw/update.sh for OpenClaw) refuse a marker below the N they were
# written for.
#
# This harness reads N from the exporters and checks the consumers against it, so the
# same file passes on a tree at protocol 2 and on one at protocol 3, and it fails when
# only one side of the pair was raised:
#   - the exporters agree on N, and no unexpected file uses the marker
#   - marker N-1 + a Node.js pin change      -> install-nodejs.sh refuses, nothing changed
#   - marker N-1 + an OpenClaw pin change    -> update.sh refuses, installed copy untouched
#   - marker N (and N+1)                     -> both go on past the guard
#   - marker unset / not a number            -> both refuse
#   - the developer escape hatches           -> both go on
#
# It runs the real scripts on the host (no device, no network): a temporary HOME and
# PREFIX, and stub curl / node / npm in front of PATH. Nothing outside the temporary
# directory is written.
#
# Usage:
#   bash tests/verify-update-guard.sh
#   OA_TEST_ROOT=/path/to/tree bash tests/verify-update-guard.sh   (a copy to test)
#   OA_TEST_BASH=/opt/homebrew/bin/bash bash tests/verify-update-guard.sh

set -uo pipefail

ROOT="${OA_TEST_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BASH_BIN="${OA_TEST_BASH:-bash}"

PASS=0; FAIL=0; TOTAL=0
RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); TOTAL=$((TOTAL+1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); TOTAL=$((TOTAL+1)); }

# check <description> <condition...>: the condition is a command; a failure prints OUT
check() {
    local desc="$1"; shift
    if "$@"; then pass "$desc"; else fail "$desc"; [ -n "${OUT:-}" ] && printf '       | %s\n' "${OUT//$'\n'/$'\n       | '}"; fi
}

echo "=== Update Guard Harness (OA_UPDATE_CORE_PROTOCOL) ==="
echo "    tree: $ROOT"
echo ""

for f in update-core.sh install.sh scripts/install-nodejs.sh platforms/openclaw/update.sh platforms/openclaw/config.env scripts/lib.sh; do
    if [ ! -f "$ROOT/$f" ]; then
        echo -e "${RED}[FAIL]${NC} $f not found under $ROOT"
        exit 1
    fi
done

W=$(mktemp -d "${TMPDIR:-/tmp}/oa-update-guard.XXXXXX") || exit 1
trap 'rm -rf "$W"' EXIT
STUBS="$W/stubs"
mkdir -p "$STUBS"

# ── Stubs ─────────────────────────────────────────────────────────────────
# curl: never reaches the network; logs the call so a test can tell "went on to the download".
cat > "$STUBS/curl" << 'EOF'
#!/bin/sh
echo "curl $*" >> "${CURL_LOG:-/dev/null}"
exit 22
EOF
# node: only what update.sh asks for — --version and -p "require('<package.json>').version".
cat > "$STUBS/node" << 'EOF'
#!/bin/sh
case "$1" in
    --version) echo "v${STUB_NODE_VERSION:-0.0.0}" ;;
    -p)
        f=$(printf '%s' "$2" | sed -n "s/.*require('\(.*\)').*/\1/p")
        [ -f "$f" ] && sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p' "$f" | head -1
        ;;
esac
exit 0
EOF
# npm: `root -g` is a directory in the sandbox; `install` is logged and fails (a run that gets
# this far has passed the guard).
cat > "$STUBS/npm" << 'EOF'
#!/bin/sh
case "$1" in
    root) echo "${STUB_NPM_ROOT:-/nonexistent}" ;;
    install) echo "npm $*" >> "${NPM_LOG:-/dev/null}"; exit 1 ;;
esac
exit 0
EOF
chmod +x "$STUBS/curl" "$STUBS/node" "$STUBS/npm"

# ── What the tree under test says ─────────────────────────────────────────
export_value() { sed -n 's/^export OA_UPDATE_CORE_PROTOCOL=\([0-9][0-9]*\)[[:space:]]*$/\1/p' "$ROOT/$1" | head -1; }
N_CORE=$(export_value update-core.sh)
N_INST=$(export_value install.sh)

read -r NODE_PIN OC_PIN < <(
    # shellcheck source=/dev/null
    ( source "$ROOT/platforms/openclaw/config.env" && echo "$PLATFORM_NODE_VERSION $PLATFORM_NPM_PACKAGE_VERSION" )
)
OC_OLD="2020.1.1"        # an installed OpenClaw that differs from (and is older than) the pin
NODE_OLD="20.0.0"        # an installed Node.js that differs from the pin

# ── Sandbox ───────────────────────────────────────────────────────────────
new_sandbox() {   # prints a new sandbox directory: Node.js installed, OpenClaw installed
    # (called in $(...): a counter would not survive the subshell, so every case gets its own mktemp -d)
    local sb
    sb=$(mktemp -d "$W/sb.XXXXXX") || return 1
    mkdir -p "$sb/home/.openclaw-android/bin" "$sb/prefix/glibc/lib" "$sb/prefix/bin" \
             "$sb/prefix/tmp" "$sb/prefix/lib/node_modules/openclaw"
    printf '#!/bin/sh\nexit 0\n' > "$sb/prefix/glibc/lib/ld-linux-aarch64.so.1"
    chmod +x "$sb/prefix/glibc/lib/ld-linux-aarch64.so.1"
    # the installed Node.js wrapper that install-nodejs.sh reads the version from
    printf '#!/bin/sh\n[ "$1" = "--version" ] && echo "v%s"\nexit 0\n' "$NODE_OLD" > "$sb/home/.openclaw-android/bin/node"
    chmod +x "$sb/home/.openclaw-android/bin/node"
    # (one key per line, as npm writes it: update.sh and update-core.sh read the version with sed, not node)
    printf '{\n  "name": "openclaw",\n  "version": "%s"\n}\n' "$OC_OLD" > "$sb/prefix/lib/node_modules/openclaw/package.json"
    echo "$sb"
}

# A listing with a checksum per file: equal before and after means nothing was changed.
snapshot() {
    ( cd "$1" && find home prefix \( -type f -o -type d -o -type l \) | LC_ALL=C sort | while IFS= read -r p; do
        if [ -f "$p" ] && [ ! -L "$p" ]; then printf '%s %s\n' "$p" "$(cksum < "$p")"; else printf '%s\n' "$p"; fi
    done )
}

# run_script <sandbox> <script> <marker|-> [VAR=value ...]  ->  sets OUT and RC
#   marker "-" leaves OA_UPDATE_CORE_PROTOCOL unset
run_script() {
    local sb="$1" script="$2" marker="$3"; shift 3
    local -a e=(HOME="$sb/home" PREFIX="$sb/prefix" PATH="$STUBS:/usr/bin:/bin" TERM=dumb
                OA_RESULT_NOTES="$sb/notes" CURL_LOG="$sb/curl.log" NPM_LOG="$sb/npm.log"
                STUB_NODE_VERSION="$NODE_PIN" STUB_NPM_ROOT="$sb/prefix/lib/node_modules"
                OA_MIN_FREE_UPDATE_MB=1)
    [ "$marker" = "-" ] || e+=(OA_UPDATE_CORE_PROTOCOL="$marker")
    [ "$#" -eq 0 ] || e+=("$@")
    : > "$sb/notes"
    case "$script" in
        node) OUT=$(env -i "${e[@]}" "$BASH_BIN" "$ROOT/scripts/install-nodejs.sh" "$NODE_PIN" 2>&1); RC=$? ;;
        oc)   OUT=$(env -i "${e[@]}" "$BASH_BIN" "$ROOT/platforms/openclaw/update.sh" 2>&1); RC=$? ;;
    esac
}

# ── Assertions ────────────────────────────────────────────────────────────
refused_cleanly() {   # <sandbox> <snapshot before>: exit 1, the cache note, no side effect
    local sb="$1" before="$2"
    [ "$RC" -eq 1 ] \
        && grep -qx 'reason=cache_old_updater' "$sb/notes" \
        && grep -qx 'changed=false' "$sb/notes" \
        && [[ "$OUT" == *"older copy of itself"* ]] \
        && [ ! -e "$sb/curl.log" ] && [ ! -e "$sb/npm.log" ] \
        && [ "$(snapshot "$sb")" = "$before" ]
}
went_past_node_guard() {   # <sandbox>: reached the download (the stub curl was called)
    local sb="$1"
    ! grep -qx 'reason=cache_old_updater' "$sb/notes" \
        && [[ "$OUT" != *"older copy of itself"* ]] \
        && [[ "$OUT" == *"Downloading Node.js v$NODE_PIN"* ]] \
        && [ -s "$sb/curl.log" ]
}
went_past_oc_guard() {     # <sandbox>: reached `npm install -g openclaw@<pin>`
    local sb="$1"
    ! grep -qx 'reason=cache_old_updater' "$sb/notes" \
        && [[ "$OUT" != *"older copy of itself"* ]] \
        && grep -q "install -g openclaw@$OC_PIN" "$sb/npm.log" 2>/dev/null
}

# ═════════════════════════════════════════════════════════════════════════
# G0: the exporters, and who uses the marker
# ═════════════════════════════════════════════════════════════════════════
echo "--- G0: exporters ---"
OUT="update-core.sh exports '${N_CORE:-?}', install.sh exports '${N_INST:-?}'"
check "G0a: update-core.sh and install.sh export the same numeric marker ($OUT)" \
    bash -c '[ -n "$1" ] && [ "$1" = "$2" ]' _ "$N_CORE" "$N_INST"

if ! [[ "$N_CORE" =~ ^[0-9]+$ ]] || [ "$N_CORE" != "$N_INST" ]; then
    echo ""
    echo -e "${RED}PROTOCOL GUARD CHECK FAILED${NC} — the exporters do not agree; the cases below need N."
    exit 1
fi
N="$N_CORE"; LOW=$((N-1)); HIGH=$((N+1))

EXPECTED=$'install.sh\nplatforms/openclaw/update.sh\nscripts/install-nodejs.sh\nupdate-core.sh'
USERS=$( cd "$ROOT" && grep -rl --include='*.sh' 'OA_UPDATE_CORE_PROTOCOL' . \
            --exclude-dir=.git --exclude-dir=.agent --exclude-dir=node_modules --exclude-dir=tests --exclude-dir=android \
        | sed 's|^\./||' | LC_ALL=C sort )
OUT="expected:
$EXPECTED
found:
$USERS"
check "G0b: only the two exporters and the two consumers use the marker (a new user needs its own case here)" \
    test "$USERS" = "$EXPECTED"
echo ""

# ═════════════════════════════════════════════════════════════════════════
# G1/G2: marker N-1 — an old updater with the new tarball
# ═════════════════════════════════════════════════════════════════════════
echo "--- G1: marker $LOW (older updater), version change ---"
SB=$(new_sandbox); BEFORE=$(snapshot "$SB")
run_script "$SB" node "$LOW"
check "G1a: install-nodejs.sh refuses a Node.js change ($NODE_OLD -> $NODE_PIN) and changes nothing" \
    refused_cleanly "$SB" "$BEFORE"

SB=$(new_sandbox); BEFORE=$(snapshot "$SB")
run_script "$SB" oc "$LOW"
check "G1b: update.sh refuses an OpenClaw change ($OC_OLD -> $OC_PIN); the installed copy is untouched" \
    refused_cleanly "$SB" "$BEFORE"
OUT=$(cat "$SB/prefix/lib/node_modules/openclaw/package.json")
check "G1c: the installed OpenClaw is still $OC_OLD" \
    grep -q "\"$OC_OLD\"" "$SB/prefix/lib/node_modules/openclaw/package.json"
echo ""

# ═════════════════════════════════════════════════════════════════════════
# G3: marker N (and above) — the current updater
# ═════════════════════════════════════════════════════════════════════════
echo "--- G3: marker $N and $HIGH (current updater) ---"
for m in "$N" "$HIGH"; do
    SB=$(new_sandbox)
    run_script "$SB" node "$m"
    check "G3: marker $m -> install-nodejs.sh goes on to the download" went_past_node_guard "$SB"
    SB=$(new_sandbox)
    run_script "$SB" oc "$m"
    check "G3: marker $m -> update.sh goes on to the npm install" went_past_oc_guard "$SB"
done
echo ""

# ═════════════════════════════════════════════════════════════════════════
# G4: no marker at all, a value that is not a number
# ═════════════════════════════════════════════════════════════════════════
echo "--- G4: marker unset / not a number ---"
for m in "-" "abc"; do
    label="unset"; [ "$m" = "-" ] || label="'$m'"
    SB=$(new_sandbox); BEFORE=$(snapshot "$SB")
    run_script "$SB" node "$m"
    check "G4: marker $label -> install-nodejs.sh refuses, nothing changed" refused_cleanly "$SB" "$BEFORE"
    SB=$(new_sandbox); BEFORE=$(snapshot "$SB")
    run_script "$SB" oc "$m"
    check "G4: marker $label -> update.sh refuses, nothing changed" refused_cleanly "$SB" "$BEFORE"
done
echo ""

# ═════════════════════════════════════════════════════════════════════════
# G5: developer escape hatches (documented in docs/troubleshooting.md)
# ═════════════════════════════════════════════════════════════════════════
echo "--- G5: escape hatches at marker $LOW ---"
SB=$(new_sandbox)
run_script "$SB" node "$LOW" OA_ALLOW_UNMARKED_NODE_CHANGE=1
check "G5a: OA_ALLOW_UNMARKED_NODE_CHANGE=1 -> install-nodejs.sh goes on" went_past_node_guard "$SB"
SB=$(new_sandbox)
run_script "$SB" oc "$LOW" OA_ALLOW_UNMARKED_OPENCLAW_CHANGE=1
check "G5b: OA_ALLOW_UNMARKED_OPENCLAW_CHANGE=1 -> update.sh goes on" went_past_oc_guard "$SB"
echo ""

# ─────────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────────
echo "==============================="
echo -e "  Protocol under test: $N (refused: $LOW, accepted: $N, $HIGH)"
echo -e "  Results: ${GREEN}$PASS passed${NC} / ${RED}$FAIL failed${NC} / $TOTAL total"
echo "==============================="
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo -e "${RED}PROTOCOL GUARD CHECK FAILED${NC} — the exporters and the consumers disagree, or a guard lets an old updater through."
    exit 1
else
    echo -e "${GREEN}PROTOCOL GUARD CONSISTENT${NC}"
fi
