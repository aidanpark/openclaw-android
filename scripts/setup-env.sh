#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

BASHRC="$HOME/.bashrc"
PLATFORM=$(detect_platform) || true

INFRA_VARS="export TMPDIR=\"\$PREFIX/tmp\"
export TMP=\"\$TMPDIR\"
export TEMP=\"\$TMPDIR\"
export OA_GLIBC=1"

# npm registry re-injection — reads cache file written by resolve_npm_registry.
# Literal \$HOME, \${NPM_CONFIG_REGISTRY:-}, and \$(cat ...) are preserved for
# runtime expansion in each new shell. -z guard lets users override manually.
# if/fi (not a trailing `&&` chain) so the block returns 0 when the guard is
# false — sourcing .bashrc under `set -e` must not stop (same as post-setup.sh).
NPM_REGISTRY_INJECT="# npm registry (auto-detected by OpenClaw Android, safe to override manually)
if [ -z \"\${NPM_CONFIG_REGISTRY:-}\" ] && [ -s \"\$HOME/.openclaw-android/.npm-registry\" ]; then
    export NPM_CONFIG_REGISTRY=\"\$(cat \"\$HOME/.openclaw-android/.npm-registry\")\"
fi"

PATH_LINE="export PATH=\"\$HOME/.local/bin:\$PATH\""
if [ -n "$PLATFORM" ]; then
    load_platform_config "$PLATFORM" "$(dirname "$(dirname "$0")")" 2>/dev/null || true
    if [ "${PLATFORM_NEEDS_NODEJS:-}" = true ]; then
        PATH_LINE="export PATH=\"\$HOME/.openclaw-android/bin:\$HOME/.openclaw-android/node/bin:\$HOME/.local/bin:\$PATH\""
    fi
fi

PLATFORM_VARS=""
PLATFORM_ENV_SCRIPT="$(dirname "$(dirname "$0")")/platforms/$PLATFORM/env.sh"
if [ -n "$PLATFORM" ] && [ -f "$PLATFORM_ENV_SCRIPT" ]; then
    PLATFORM_VARS=$(bash "$PLATFORM_ENV_SCRIPT")
fi

ENV_BLOCK="${BASHRC_MARKER_START}
# platform: ${PLATFORM:-none}
${PATH_LINE}
${INFRA_VARS}"

if [ -n "$PLATFORM_VARS" ]; then
    ENV_BLOCK="${ENV_BLOCK}
${PLATFORM_VARS}"
fi

ENV_BLOCK="${ENV_BLOCK}
${NPM_REGISTRY_INJECT}
${BASHRC_MARKER_END}"

touch "$BASHRC"
if grep -qF "$BASHRC_MARKER_START" "$BASHRC"; then
    sed -i "/${BASHRC_MARKER_START//\//\\/}/,/${BASHRC_MARKER_END//\//\\/}/d" "$BASHRC"
fi
echo "" >> "$BASHRC"
echo "$ENV_BLOCK" >> "$BASHRC"

if [ ! -e "$PREFIX/bin/ar" ] && [ -x "$PREFIX/bin/llvm-ar" ]; then
    ln -s "$PREFIX/bin/llvm-ar" "$PREFIX/bin/ar"
fi
