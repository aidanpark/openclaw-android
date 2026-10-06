#!/usr/bin/env bash
# openclaw-patch-hardlink.sh - make OpenClaw copy a file when a hard link is denied.
#
# Android (app and Termux alike) denies hard links (ln, fs.link, linkat) with EACCES. OpenClaw's
# fs-safe publishes files with strategy "link-or-copy": it tries a hard link first and copies only
# when the error code is in HARDLINK_FALLBACK_CODES (EPERM, EXDEV, ENOTSUP, EOPNOTSUPP, ENOSYS).
# EACCES is not in that list, so every such publication fails - among them the SQLite snapshot of
# `openclaw doctor --fix`, which migrates the data when OpenClaw is updated (the gateway then does
# not start). Two changes:
#   F1  add EACCES to HARDLINK_FALLBACK_CODES and to NATIVE_COPY_FALLBACK_CODES (the clone/reflink step
#       of the copy is refused with EACCES too); both are inlined in 3 bundles and in
#       node_modules/@openclaw/fs-safe
#   F1b dist/backup-create-*.mjs: strategy "link-required" -> "link-or-copy"
#       (so `openclaw backup create` works)
#
#   F3  B3_SESSION_ARCHIVE_PATCH: the session migration of 9.x (`doctor --fix`, "Legacy session store
#       requires migration") archives each old transcript with moveMigrationArtifact: a hard link
#       (strategy "link-required") and an `nlink == 2` check, then it removes the source. Hard links are
#       refused here (EACCES) and a copy would fail the check, so the move is done with rename(2)
#       instead (same file system: atomic, inode/mtime kept, so the later identity checks still hold;
#       "undo" renames back). Applied by wrapping the function in each bundle that defines it. The
#       wrapper only takes over when the link is refused with EACCES (or when an interrupted rename
#       is resumed); every other case runs OpenClaw's own code unchanged.
#
# THIS PATCH IS TIED TO THE PINNED OPENCLAW VERSION. At every pin bump re-run it on the new
# package and make sure nothing is reported as MISSING/NOMATCH (and test under
# .agent/tools/android-trap/trap_android.py). Files are read and written as bytes (latin1): the
# worker bundles are not clean UTF-8. Idempotent. A file that does not match is reported, never
# hidden: the data migration then fails again with the original EACCES message.
# Usage: openclaw-patch-hardlink.sh [--check] [--only-b3] [<openclaw package dir>]   (default: npm root -g)
# --only-b3: look at the session-archive patch only (for a package taken from the npm tarball, before it is installed)
# --check: change nothing, report what is still to do (exit 3 when something is, or does not match)
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

CHECK=0
ONLY_B3=0
ARG_DIR=""
for arg in "$@"; do
    case "$arg" in
        --check) CHECK=1 ;;
        --only-b3) ONLY_B3=1 ;;
        *) [ -n "$ARG_DIR" ] || ARG_DIR="$arg" ;;
    esac
done
OPENCLAW_DIR="${ARG_DIR:-$(npm root -g 2>/dev/null || true)/openclaw}"
STATE_FILE="${HOME:-/tmp}/.openclaw-android/hardlink-patch.state"

if [ ! -f "$OPENCLAW_DIR/package.json" ]; then
    echo -e "${YELLOW}[WARN]${NC} OpenClaw not found at $OPENCLAW_DIR - hard-link patch skipped"
    exit 0
fi
if ! command -v node >/dev/null 2>&1; then
    echo -e "${YELLOW}[WARN]${NC} node not found - hard-link patch skipped"
    exit 0
fi

HARDLINK_TMP=$(mktemp "${TMPDIR:-/tmp}/oa-hardlink-patch.XXXXXX") || HARDLINK_TMP="/tmp/oa-hardlink-patch.$$"
trap 'rm -f "$HARDLINK_TMP"' EXIT
cat > "$HARDLINK_TMP" << 'HARDLINK_JS'
const fs = require('fs');
const path = require('path');
const root = process.argv[2];
const FILES = [
  'node_modules/@openclaw/fs-safe/dist/publish-file.js',
  'dist/package-update-activation-recovery.mjs',
  'dist/worker/worker.mjs',
  'dist/worker/sqlite-store.worker.mjs',
];
// Two classifier sets, each `NAME = [/* @__PURE__ */] new Set([ <quote>CODE<quote>, ... ])` (quotes may be
// ", ' or `, on one line or several; the minified bundles use back quotes on one line):
//   HARDLINK_FALLBACK_CODES    - the hard link is refused  -> copy
//   NATIVE_COPY_FALLBACK_CODES - the clone/reflink step of that copy is refused (ioctl FICLONE: EACCES on
//                                Android, found on the device after the first fix) -> plain copy
const SETS = ['HARDLINK_FALLBACK_CODES', 'NATIVE_COPY_FALLBACK_CODES'];
let patched = 0, already = 0, problems = 0;
const CHECK = process.argv.includes('--check');
const ONLY_B3 = process.argv.includes('--only-b3');
const read = (f) => fs.readFileSync(f, 'latin1');
// Atomic: a full disk must not leave a cut-off bundle (a broken OpenClaw); write a temp file next to it, then
// rename it over the original. A failed write is a problem (counted, reported), the original stays as it was.
const write = (f, s) => {
  if (CHECK) return true;
  const tmp = f + '.oa-tmp';
  try {
    fs.writeFileSync(tmp, s, 'latin1');
    fs.chmodSync(tmp, fs.statSync(f).mode & 0o7777);
    fs.renameSync(tmp, f);
    return true;
  } catch (e) {
    try { fs.unlinkSync(tmp); } catch (e2) { /* nothing to clean */ }
    console.log('WRITEFAIL ' + path.relative(root, f) + ' (' + (e && e.code ? e.code : String(e)) + ')');
    problems++;
    return false;
  }
};
// Add EACCES to one set; returns 'patched' | 'already' | 'nomatch'
function addEacces(text, name) {
  const re = new RegExp('(' + name + '\\s*=\\s*(?:\\/\\*[^*]*\\*\\/\\s*)?new Set\\(\\[\\s*)((["\'`])[^\\]]*)\\]\\)');
  const m = re.exec(text);
  if (!m) return { status: 'nomatch', text };
  if (/EACCES/.test(m[2])) return { status: 'already', text };
  const q = m[3];
  const out = text.slice(0, m.index) + m[1] + q + 'EACCES' + q + ',' + m[2] + '])' + text.slice(m.index + m[0].length);
  return { status: 'patched', text: out };
}
for (const rel of ONLY_B3 ? [] : FILES) {
  const file = path.join(root, rel);
  if (!fs.existsSync(file)) { console.log('MISSING ' + rel); problems++; continue; }
  let s = read(file);
  let changed = false;
  for (const name of SETS) {
    const r = addEacces(s, name);
    if (r.status === 'nomatch') { console.log('NOMATCH ' + rel + ' (' + name + ')'); problems++; continue; }
    if (r.status === 'already') { already++; continue; }
    s = r.text; changed = true; patched++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + rel + ' (' + name + ')');
  }
  if (changed) write(file, s);
}
const dist = path.join(root, 'dist');
const backups = fs.existsSync(dist) && !ONLY_B3 ? fs.readdirSync(dist).filter((n) => /^backup-create-.*\.mjs$/.test(n)) : [];
if (backups.length === 0 && !ONLY_B3) { console.log('MISSING dist/backup-create-*.mjs'); problems++; }
for (const name of backups) {
  const file = path.join(dist, name);
  const s = read(file);
  const n = s.split('strategy: "link-required"').length - 1;
  if (n === 0) { already++; continue; }
  if (!write(file, s.split('strategy: "link-required"').join('strategy: "link-or-copy"'))) continue;
  patched++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + 'dist/' + name + ' (' + n + ' link-required -> link-or-copy)');
}

// ---- F3: moveMigrationArtifact by rename when the hard link is refused (see the header)
const B3_MARK = '__oaMoveMigrationArtifactOrig';
const B3_DEF = /async function moveMigrationArtifact\(/;
function b3Helper(requireSync, syncDir) {
  return `/* B3_SESSION_ARCHIVE_PATCH v2 (OpenClaw on Android): hard links are refused, archive by rename */
async function __oaMoveByRename(sourcePath, targetPath, expected, onPublished, publishSourceRemoval) {
	const sourceThere = !!fs.lstatSync(sourcePath, { bigint: true, throwIfNoEntry: false });
	const targetThere = !!fs.lstatSync(targetPath, { bigint: true, throwIfNoEntry: false });
	if (sourceThere && targetThere) throw new Error("artifact archive path already exists");
	let movedHere = false;
	if (sourceThere) {
		if (!sameMigrationArtifact(readMigrationArtifactIdentity(sourcePath), expected)) throw new Error("artifact changed before publication");
		fs.renameSync(sourcePath, targetPath);
		movedHere = true;
	}
	${requireSync}(await ${syncDir}(path.dirname(targetPath)), "Recovery artifact publication");
	const verifyMoved = () => {
		if (fs.lstatSync(sourcePath, { bigint: true, throwIfNoEntry: false }) || !sameMigrationArtifact(readMigrationArtifactIdentity(targetPath), expected)) throw new Error("artifact changed during publication");
	};
	let published = false;
	const removeSource = () => {
		verifyMoved();
		if (onPublished) {
			onPublished();
			published = true;
			verifyMoved();
		}
		published = true;
	};
	let retainedSource = false;
	const retainSource = () => {
		// as in OpenClaw's own code: a publication made by an earlier run cannot be discarded
		if (!movedHere) throw new Error("Cannot discard a recovery publication created by an earlier run.");
		verifyMoved();
		fs.renameSync(targetPath, sourcePath);
		retainedSource = true;
	};
	try {
		if (publishSourceRemoval) publishSourceRemoval(removeSource, retainSource);
		else removeSource();
	} catch (error) {
		// failed before the move was final: put the file back under its own name (OpenClaw's own code
		// keeps both names then), so that a rerun sees the same state as after a refused hard link
		if (movedHere && !published && !retainedSource) {
			try {
				if (!fs.lstatSync(sourcePath, { bigint: true, throwIfNoEntry: false }) && sameMigrationArtifact(readMigrationArtifactIdentity(targetPath), expected)) fs.renameSync(targetPath, sourcePath);
			} catch (restoreError) { /* keep the original error */ }
		}
		throw error;
	} finally {
		if (retainedSource) ${requireSync}(await ${syncDir}(path.dirname(targetPath)), "Recovery artifact deferral");
	}
	${requireSync}(await ${syncDir}(path.dirname(sourcePath)), "Recovery artifact source");
	if (!sameMigrationArtifact(readMigrationArtifactIdentity(targetPath), expected)) throw new Error("artifact changed during publication");
}
async function moveMigrationArtifact(sourcePath, targetPath, expected, onPublished, publishSourceRemoval) {
	const missing = (p) => !fs.lstatSync(p, { bigint: true, throwIfNoEntry: false });
	// an interrupted rename-based move: the source is gone, the archive copy is there
	if (missing(sourcePath) && !missing(targetPath)) return __oaMoveByRename(sourcePath, targetPath, expected, onPublished, publishSourceRemoval);
	try {
		return await ${B3_MARK}(sourcePath, targetPath, expected, onPublished, publishSourceRemoval);
	} catch (error) {
		// only a refused hard link (the codes of the B2 fallback list), before anything was published
		if (error && ["EACCES", "EPERM", "ENOTSUP", "EOPNOTSUPP", "ENOSYS"].includes(error.code) && !missing(sourcePath) && missing(targetPath)) return __oaMoveByRename(sourcePath, targetPath, expected, onPublished, publishSourceRemoval);
		throw error;
	}
}
`;
}
const B3_TAG = '/* B3_SESSION_ARCHIVE_PATCH';
const B3_TAG_CURRENT = B3_TAG + ' v2 ';
const B3_HELPER_NAMES = /([\w$]+)\(await ([\w$]+)\(path\.dirname\(/;
// A bundle with a second, unwrapped definition would pass on the marker alone: count them (the wrapper is the one)
const b3Defs = (t) => t.split(B3_DEF).length - 1;
function patchB3(text) {
  if (b3Defs(text) > 1) return { status: 'nomatch', text };
  if (text.includes(B3_MARK)) {
    if (text.includes(B3_TAG_CURRENT)) return { status: 'already', text };
    // patched by an earlier version of this helper: replace it (it sits right before the renamed original)
    const a = text.indexOf(B3_TAG);
    const oidx = text.indexOf('async function ' + B3_MARK + '(');
    if (a < 0 || oidx < a) return { status: 'nomatch', text };
    const m = B3_HELPER_NAMES.exec(text.slice(oidx, oidx + 8000));
    if (!m) return { status: 'nomatch', text };
    return { status: 'patched', text: text.slice(0, a) + b3Helper(m[1], m[2]) + text.slice(oidx) };
  }
  const idx = text.search(B3_DEF);
  if (idx < 0) return { status: 'nomatch', text };
  // the names this bundle uses for the directory-sync helpers (minified bundles rename them)
  const m = B3_HELPER_NAMES.exec(text.slice(idx, idx + 8000));
  if (!m) return { status: 'nomatch', text };
  const rest = text.slice(idx).replace(B3_DEF, 'async function ' + B3_MARK + '(');
  return { status: 'patched', text: text.slice(0, idx) + b3Helper(m[1], m[2]) + rest };
}
const b3Files = [];
if (fs.existsSync(path.join(root, 'dist'))) {
  for (const n of fs.readdirSync(path.join(root, 'dist'))) if (/^session-sqlite-migration-manifest-.*\.mjs$/.test(n)) b3Files.push('dist/' + n);
}
b3Files.push('dist/package-update-activation-recovery.mjs', 'dist/worker/worker.mjs', 'dist/worker/sqlite-store.worker.mjs');
let b3Seen = 0;
for (const rel of b3Files) {
  const file = path.join(root, rel);
  if (!fs.existsSync(file)) { console.log('MISSING ' + rel + ' (B3)'); problems++; continue; }
  const r = patchB3(read(file));
  if (r.status === 'already') { already++; b3Seen++; continue; }
  if (r.status === 'nomatch') { console.log('NOMATCH ' + rel + ' (B3 moveMigrationArtifact)'); problems++; continue; }
  if (!write(file, r.text)) continue; patched++; b3Seen++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + rel + ' (B3 moveMigrationArtifact)');
}
console.log('SUMMARY ' + (CHECK ? 'todo=' : 'patched=') + patched + ' already=' + already + ' problems=' + problems);
process.exit(problems || (CHECK && patched) ? 3 : 0);
HARDLINK_JS
rc=0
JS_FLAGS=""
[ "$CHECK" = 1 ] && JS_FLAGS="--check"
[ "$ONLY_B3" = 1 ] && JS_FLAGS="$JS_FLAGS --only-b3"
# shellcheck disable=SC2086
out=$(node "$HARDLINK_TMP" "$OPENCLAW_DIR" $JS_FLAGS) || rc=$?

summary=$(printf '%s\n' "$out" | { grep '^SUMMARY ' || true; } | tail -1 | sed 's/^SUMMARY //')
# (|| true: with nothing to report grep finds no line, and that must not end the script)
{ printf '%s\n' "$out" | grep -v '^SUMMARY ' || true; } | while IFS= read -r line; do
    case "$line" in
        PATCHED*) echo -e "  ${GREEN}[PATCHED]${NC} ${line#PATCHED }" ;;
        TODO*) echo -e "  ${YELLOW}[TODO]${NC} ${line#TODO }" ;;
        MISSING*|NOMATCH*|WRITEFAIL*) echo -e "  ${YELLOW}[WARN]${NC} ${line%% *}: ${line#* } - hard-link fallback NOT applied here" ;;
        *) [ -z "$line" ] || echo "  $line" ;;
    esac
done
ocver=$(sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$OPENCLAW_DIR/package.json" | head -1)
if [ "$CHECK" != 1 ] && [ "$ONLY_B3" != 1 ]; then
    mkdir -p "$(dirname "$STATE_FILE")" 2>/dev/null || true
    echo "openclaw=${ocver:-unknown} ${summary:-error} exit=$rc date=$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$STATE_FILE" 2>/dev/null || true
fi
if [ "$rc" -eq 0 ]; then
    echo "Hard-link fallback: ${summary}"
elif [ "$CHECK" = 1 ] && printf '%s' "$summary" | grep -q 'problems=0'; then
    echo "Hard-link fallback not applied yet: ${summary}"
else
    echo -e "${YELLOW}[WARN]${NC} Hard-link fallback incomplete (${summary:-node failed}): this OpenClaw version does not match the patch, or a file could not be written."
    echo "       Hard links are denied on Android, so the data migration of 'openclaw doctor --fix' can fail with EACCES."
fi
# (a normal run never fails the caller; --check reports through its exit code)
[ "$CHECK" = 1 ] && exit "$rc"
exit 0
