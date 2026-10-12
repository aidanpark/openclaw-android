#!/usr/bin/env bash
# openclaw-patch-hardlink.sh - make OpenClaw copy a file when a hard link is denied.
#
# Android (app and Termux alike) denies hard links (ln, fs.link, linkat) and reflink clones (ioctl FICLONE) with
# EACCES. OpenClaw's fs-safe publishes files with strategy "link-or-copy": it tries a hard link first and copies
# only when the error code is in a list of "not supported" codes (EPERM, EXDEV, ENOTSUP, EOPNOTSUPP, ENOSYS).
# EACCES is not in those lists, so every such publication fails - among them the SQLite snapshot of
# `openclaw doctor --fix`, which migrates the data when OpenClaw is updated (the gateway then does not start).
# Five changes (the names are ours: a "class" below is what has to be found at least once per package):
#   F1  (class "classifier")  add EACCES to HARDLINK_FALLBACK_CODES and NATIVE_COPY_FALLBACK_CODES (fs-safe's
#       publish-file.js and the bundles that inline it; the sets exist in fs-safe 0.21 and 0.24)
#   F1c (class "classifier")  the native copy (createNativeCopyFile, assertNativeCopyCompleted) falls back to a
#       plain copy only on ENOTSUP, and the "not supported" list of the completion check has no EACCES: both get
#       it (fs-safe's copy-file-input.js and the bundles that inline it). Found in fs-safe 0.21.1 (OpenClaw
#       2026.9.x: copy-file-input.js and package-update-activation-recovery.mjs) and in 0.24.2 (2026.10.x); it
#       is needed in 2026.10 (without it `doctor --fix` fails: the copy of the SQLite snapshot gets EACCES from
#       the reflink step). Not edited: the minified bundles dist/worker/*.mjs of 2026.9.x inline the same
#       fallback in a form (`Zt===\`ENOTSUP\`&&Kt.clone!==\`always\``) this pattern does not match, and no
#       NOMATCH is raised there; 2026.10 has no worker bundles, and the 2026.9.x migration was run through
#       under the trap without it.
#   F1b (class "backup")      dist/backup-create-*.mjs: strategy "link-required" -> "link-or-copy"
#       (so `openclaw backup create` works)
#   F3  (class "archive")     B3_SESSION_ARCHIVE_PATCH: the session migration of 9.x (`doctor --fix`, "Legacy
#       session store requires migration") archives each old transcript with moveMigrationArtifact: a hard link
#       (strategy "link-required") and an `nlink == 2` check, then it removes the source. Hard links are refused
#       here (EACCES) and a copy would fail the check, so the move is done with rename(2) instead (same file
#       system: atomic, inode/mtime kept, so the later identity checks still hold; "undo" renames back). Applied
#       by wrapping the function in each bundle that defines it. The wrapper only takes over when the link is
#       refused with EACCES (or when an interrupted rename is resumed); every other case runs OpenClaw's own
#       code unchanged.
#
#   F4  (OpenClaw 2026.10, not a hard-link matter - it is here because it is the same kind of Android difference
#       and the same way of finding the files): the gateway lock tells "my own process" from "a process in another
#       PID namespace" by stat("/proc/self/ns/pid"). Android's app sandbox answers ENOENT, the namespace of the
#       process is then "null", and a heartbeat that is less than 90 s old is judged "unknown": the gateway refuses
#       to start (`failed to acquire gateway state ownership ... different PID namespace`), even against its own
#       lease. The inode is replaced by the constant "unavailable" when the stat fails, in every bundle and
#       worker that reads it (all of them must agree); a readable namespace is used as before.
#       F4 is an OPTIONAL class: OpenClaw 2026.9.x has no such code, so finding no target is not a NOTARGET;
#       a bundle that has the gateway lock function in a form the patch does not know is a NOMATCH.
#
# THE TARGETS ARE FOUND BY CONTENT, NOT BY FILE NAME. OpenClaw moves code between bundles from release to release
# (2026.10 removed dist/worker/ altogether), so a list of names goes stale. The script looks for the marker
# strings above in dist/ (and in node_modules/@openclaw/fs-safe/dist/ when it is there) and patches every file
# that has one. A class that finds no target at all is reported as NOTARGET and counts as a problem: the patch
# does not fit that package and a person has to look (this is what the bump procedure's step 1 catches). So is
# fs-safe: when the package depends on @openclaw/fs-safe, at least one file of it has to be found (a package whose
# node_modules was cut off must not pass because dist/ alone had targets).
# A marker that is present in a form the patch does not know is NOMATCH (also a problem); for F3 the marker is a
# string of the function body ("Cannot discard a recovery publication ..."), so a bundle whose definition
# changed shape is caught even when another bundle was patched. Nothing is hidden: the data migration then fails
# again with the original EACCES message.
#
# THIS PATCH IS TIED TO THE PINNED OPENCLAW VERSION. At every pin bump re-run it on the new package (see
# .agent/skills/ bump procedure) and test under .agent/tools/android-trap/trap_android.py. Files are read and
# written as bytes (latin1): the worker bundles are not clean UTF-8. Idempotent.
#
# "Complete or cut off" is NOT judged here (this script never says MISSING): oa_openclaw_incomplete in
# scripts/lib.sh compares the package with OpenClaw's own dist/postinstall-content-inventory.json.
#
# Usage: openclaw-patch-hardlink.sh [--check] [--only-b3] [<openclaw package dir>]   (default: npm root -g)
# --only-b3: look at the session-archive patch (class "archive") only, in dist/ (for a package taken from the
#            npm tarball before it is installed; fs-safe is not part of the tarball)
# --check: change nothing, report what is still to do (exit 3 when something is, or does not fit)
#
# TIME LIMIT: the node process that does the work runs under
# `timeout --foreground -k 5 ${OA_HARDLINK_NODE_TIMEOUT:-120}` (--foreground and -k only where this timeout knows
# them: without -k a node that ignores TERM is not stopped; no timeout command at all: no limit). A value that is
# not a whole number of 2 to 999999 seconds (leading zeros are ignored, at most 6 digits) is replaced by 120.
# --foreground keeps Ctrl-C working but does not stop children of node (it has none).
# A node started through the glibc loader was seen to stay alive and idle for 15 minutes after its patches had
# been written (QA, Script v1.2.5). When it is stopped by the limit, its SUMMARY line is used if it had printed
# one, otherwise it runs once more; stopped twice, the result is "incomplete" (state file "error exit=<code>":
# 124 with GNU timeout, 137 when it had to be killed, 143 with a BusyBox timeout; --check exits non-zero like any
# other failure). A stop that comes sooner than the limit (the system killed the process) is an ordinary
# failure, as before. The notes about a stop that was made up for carry no [WARN]: a caller that looks for WARN
# (post-setup.sh) then reports only what is left incomplete.
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
HARDLINK_LIST=$(mktemp "${TMPDIR:-/tmp}/oa-hardlink-list.XXXXXX") || HARDLINK_LIST="/tmp/oa-hardlink-list.$$"
trap 'rm -f "$HARDLINK_TMP" "$HARDLINK_LIST"' EXIT

# Find the files by content (grep is fast on the few hundred MB of dist/; node then reads only these).
B3_BODY_MARK='Cannot discard a recovery publication created by an earlier run'
SCAN_ROOTS=()
[ -d "$OPENCLAW_DIR/dist" ] && SCAN_ROOTS+=("$OPENCLAW_DIR/dist")
if [ "$ONLY_B3" != 1 ] && [ -d "$OPENCLAW_DIR/node_modules/@openclaw/fs-safe/dist" ]; then
    SCAN_ROOTS+=("$OPENCLAW_DIR/node_modules/@openclaw/fs-safe/dist")
fi
: > "$HARDLINK_LIST"
if [ "${#SCAN_ROOTS[@]}" -gt 0 ]; then
    grep -rlF --include='*.js' --include='*.mjs' --include='*.cjs' \
        -e 'HARDLINK_FALLBACK_CODES' -e 'NATIVE_COPY_FALLBACK_CODES' \
        -e 'function moveMigrationArtifact(' -e '__oaMoveMigrationArtifactOrig' \
        -e 'function createNativeCopyFile' -e 'function assertNativeCopyCompleted' \
        -e "$B3_BODY_MARK" -e '/proc/self/ns/pid' \
        "${SCAN_ROOTS[@]}" > "$HARDLINK_LIST" 2>/dev/null || true
fi
cat > "$HARDLINK_TMP" << 'HARDLINK_JS'
const fs = require('fs');
const path = require('path');
const root = path.resolve(process.argv[2]);
const CHECK = process.argv.includes('--check');
const ONLY_B3 = process.argv.includes('--only-b3');
const listAt = process.argv.indexOf('--list');
const LIST_FILE = listAt > 0 ? process.argv[listAt + 1] : '';
let patched = 0, already = 0, problems = 0;
// how many files each class located (patched or already patched): a class with none is NOTARGET
const found = { classifier: 0, backup: 0, archive: 0, fssafe: 0 };
const read = (f) => fs.readFileSync(f, 'latin1');
const rel = (f) => path.relative(root, f);
const FSSAFE_DIST = path.join(root, 'node_modules', '@openclaw', 'fs-safe', 'dist') + path.sep;
// the package declares fs-safe: then a file of it has to be found (not required for --only-b3: not unpacked yet)
let needFsSafe = false;
try {
  const pj = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  needFsSafe = !!(pj.dependencies && pj.dependencies['@openclaw/fs-safe']);
} catch (e) { /* an unreadable package.json: not required */ }
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
    console.log('WRITEFAIL ' + rel(f) + ' (' + (e && e.code ? e.code : String(e)) + ')');
    problems++;
    return false;
  }
};

// ---- F1: two classifier sets, each `NAME = [/* @__PURE__ */] new Set([ <quote>CODE<quote>, ... ])` (quotes may
// be ", ' or `, on one line or several; minified bundles use back quotes on one line):
//   HARDLINK_FALLBACK_CODES    - the hard link is refused  -> copy
//   NATIVE_COPY_FALLBACK_CODES - the clone/reflink step of that copy is refused (ioctl FICLONE: EACCES on
//                                Android, found on the device after the first fix) -> plain copy
const SETS = ['HARDLINK_FALLBACK_CODES', 'NATIVE_COPY_FALLBACK_CODES'];
const B3_BODY_MARK = 'Cannot discard a recovery publication created by an earlier run';
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

// ---- F1c: fs-safe 0.24 (OpenClaw 2026.10). Its native copy has no code set: createNativeCopyFile falls back to a
// plain copy only on ENOTSUP, and assertNativeCopyCompleted lists the "not supported" codes in a literal. EACCES is
// added to both (the same code in fs-safe's copy-file-input.js and in the bundles that inline it; the bundlers
// format the list differently, so whitespace is not part of the pattern). The list is edited only in a file that
// has the ENOTSUP-only fallback (fs-safe 0.21 and 0.24 alike; the code sets of F1 are a separate thing: they
// decide the hard link and the clone step, this decides what the plain-copy fallback itself accepts).
const NF_OLD = /code === "ENOTSUP"( && input\.clone !== "always")/;
const NF_DONE = /code === "ENOTSUP" \|\| code === "EACCES"\) && input\.clone !== "always"/;
const NL_OLD = /(\[\s*"ENOTSUP",\s*"EOPNOTSUPP",\s*"ENOSYS",\s*"EXDEV",\s*"EINVAL")(\s*\]\.includes\(copied\.errorCode\))/;
const NL_DONE = /"EINVAL",\s*"EACCES"\s*\]\.includes\(copied\.errorCode\)/;
const NATIVE_COPY_DEF = /function createNativeCopyFile\w*\(/;

// ---- F4: the pid namespace inode of the gateway lock (OpenClaw 2026.10; see the header)
const NS_OLD = /(?<!return )\b([\w$]+)\.statSync\((["'`])\/proc\/self\/ns\/pid\2, \{ bigint: (?:true|!0) \}\)\.ino\.toString\(\)/;
const NS_DONE = /\/proc\/self\/ns\/pid[^;]{0,60}\.ino\.toString\(\); \} catch \{ return "unavailable"; \}/;
const NS_DEF = /function readGatewayLockProcessNamespace\(/;
const NATIVE_LIST_DEF = /function assertNativeCopyCompleted\w*\(/;

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

// ---- the files to look at: the ones the caller's grep found a marker in (dist/ and fs-safe), plus the backup bundle
const candidates = new Set();
if (LIST_FILE) {
  for (const line of fs.readFileSync(LIST_FILE, 'utf8').split('\n')) if (line) candidates.add(path.resolve(line));
}
const backupFiles = new Set();
const distDir = path.join(root, 'dist');
if (!ONLY_B3 && fs.existsSync(distDir)) {
  for (const n of fs.readdirSync(distDir)) if (/^backup-create-.*\.mjs$/.test(n)) backupFiles.add(path.join(distDir, n));
}
for (const f of backupFiles) candidates.add(f);

for (const file of [...candidates].sort()) {
  let s;
  try { s = read(file); } catch (e) { continue; }
  const original = s;
  const r = rel(file);
  const patchedBefore = patched, alreadyBefore = already;
  const inFsSafe = file.startsWith(FSSAFE_DIST);
  const classifierBefore = found.classifier;
  if (!ONLY_B3) {
    // F1
    for (const name of SETS) {
      if (!new RegExp('\\b' + name + '\\s*=').test(s)) continue;   // a mention without the definition is no target
      const res = addEacces(s, name);
      if (res.status === 'nomatch') { console.log('NOMATCH ' + r + ' (' + name + ')'); problems++; continue; }
      found.classifier++;
      if (res.status === 'already') { already++; continue; }
      s = res.text; patched++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + r + ' (' + name + ')');
    }
    // F1c
    const nfOld = NF_OLD.test(s), nfDone = NF_DONE.test(s);
    if (nfOld) {
      s = s.replace(new RegExp(NF_OLD.source, 'g'), '(code === "ENOTSUP" || code === "EACCES")$1');
      found.classifier++; patched++;
      console.log((CHECK ? 'TODO ' : 'PATCHED ') + r + ' (native copy: EACCES falls back to a plain copy)');
    } else if (nfDone) {
      found.classifier++; already++;
    }
    if (nfOld || nfDone) {
      if (NL_OLD.test(s)) {
        s = s.replace(new RegExp(NL_OLD.source, 'g'), '$1, "EACCES"$2');
        patched++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + r + ' (native copy: EACCES is a "not supported" code)');
      } else if (NL_DONE.test(s)) {
        already++;
      } else if (NATIVE_LIST_DEF.test(s)) {
        console.log('NOMATCH ' + r + ' (native copy: list of "not supported" codes)'); problems++;
      }
    } else if (NATIVE_COPY_DEF.test(s) && !/\bNATIVE_COPY_FALLBACK_CODES\s*=/.test(s)) {
      // defines the native copy, but neither in the 0.21 shape (a code set, handled by F1) nor in the 0.24 shape
      console.log('NOMATCH ' + r + ' (native copy fallback in a form the patch does not know)'); problems++;
    }
    if (inFsSafe && found.classifier > classifierBefore) found.fssafe++;
    // F4
    if (s.includes('/proc/self/ns/pid')) {
      if (NS_DONE.test(s) && !NS_OLD.test(s)) {
        already++;
      } else if (NS_OLD.test(s)) {
        s = s.replace(new RegExp(NS_OLD.source, 'g'), (m, o, q) => '(() => { try { return ' + o + '.statSync(' + q + '/proc/self/ns/pid' + q + ', { bigint: true }).ino.toString(); } catch { return "unavailable"; } })()');
        patched++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + r + ' (gateway lock: pid namespace unavailable on Android)');
      } else if (NS_DEF.test(s)) {
        console.log('NOMATCH ' + r + ' (gateway lock pid namespace in a form the patch does not know)'); problems++;
      }
    }
    // F1b
    if (backupFiles.has(file)) {
      const n = s.split('strategy: "link-required"').length - 1;
      found.backup++;
      if (n === 0) { already++; }
      else {
        s = s.split('strategy: "link-required"').join('strategy: "link-or-copy"');
        patched++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + r + ' (' + n + ' link-required -> link-or-copy)');
      }
    }
  }
  // F3
  if (s.includes('function moveMigrationArtifact(') || s.includes(B3_MARK)) {
    const res = patchB3(s);
    if (res.status === 'nomatch') { console.log('NOMATCH ' + r + ' (B3 moveMigrationArtifact)'); problems++; }
    else {
      found.archive++;
      if (res.status === 'already') already++;
      else { s = res.text; patched++; console.log((CHECK ? 'TODO ' : 'PATCHED ') + r + ' (B3 moveMigrationArtifact)'); }
    }
  } else if (s.includes(B3_BODY_MARK)) {
    // the body of the function is here, its definition is not in the known form: this bundle is not patched
    console.log('NOMATCH ' + r + ' (B3 moveMigrationArtifact: definition in a form the patch does not know)'); problems++;
  }
  if (s !== original && !write(file, s)) {
    // not written (already counted as a problem): nothing of this file was patched
    patched = patchedBefore; already = alreadyBefore;
  }
}

// ---- a class that located nothing: this package does not fit the patch (loud, never silent)
if (!ONLY_B3) {
  if (!found.classifier) { console.log('NOTARGET classifier (HARDLINK_FALLBACK_CODES / native copy fallback)'); problems++; }
  if (!found.backup) { console.log('NOTARGET backup (dist/backup-create-*.mjs)'); problems++; }
  if (needFsSafe && !found.fssafe) { console.log('NOTARGET fs-safe (node_modules/@openclaw/fs-safe/dist: the package depends on it)'); problems++; }
}
if (!found.archive) { console.log('NOTARGET archive (moveMigrationArtifact)'); problems++; }
console.log('SUMMARY ' + (CHECK ? 'todo=' : 'patched=') + patched + ' already=' + already + ' problems=' + problems);
process.exit(problems || (CHECK && patched) ? 3 : 0);
HARDLINK_JS
rc=0
JS_FLAGS=""
[ "$CHECK" = 1 ] && JS_FLAGS="--check"
[ "$ONLY_B3" = 1 ] && JS_FLAGS="$JS_FLAGS --only-b3"
# A node started through the glibc loader was seen to stay alive and idle after it had done its work (QA, Script
# v1.2.5, the first run of an update on a device): never wait for it without a limit. Every file is written by a
# rename and a second run finds the patches of the first, so a run that is stopped here can simply be done again.
HARDLINK_NODE_LIMIT="${OA_HARDLINK_NODE_TIMEOUT:-120}"
case "$HARDLINK_NODE_LIMIT" in ''|*[!0-9]*) HARDLINK_NODE_LIMIT=120 ;; esac
# no leading zeros ("00300" is 300); empty (only zeros), more than 6 digits or less than 2: 120
HARDLINK_NODE_LIMIT=${HARDLINK_NODE_LIMIT#"${HARDLINK_NODE_LIMIT%%[!0]*}"}
if [ -z "$HARDLINK_NODE_LIMIT" ] || [ "${#HARDLINK_NODE_LIMIT}" -gt 6 ] || [ "$HARDLINK_NODE_LIMIT" -lt 2 ]; then
    HARDLINK_NODE_LIMIT=120
fi
HARDLINK_TIMEOUT=()
if command -v timeout >/dev/null 2>&1; then
    # --foreground keeps Ctrl-C working (plain timeout moves the command to its own process group); -k is not
    # known to every timeout (older BusyBox): use what this one accepts
    if timeout --foreground -k 1 10 true >/dev/null 2>&1; then
        HARDLINK_TIMEOUT=(timeout --foreground -k 5 "$HARDLINK_NODE_LIMIT")
    elif timeout -k 1 10 true >/dev/null 2>&1; then
        HARDLINK_TIMEOUT=(timeout -k 5 "$HARDLINK_NODE_LIMIT")
    else
        HARDLINK_TIMEOUT=(timeout "$HARDLINK_NODE_LIMIT")
    fi
fi
HARDLINK_TIMED_OUT=0
HARDLINK_TRY=1
while :; do
    rc=0
    hl_t0=$SECONDS
    # shellcheck disable=SC2086
    out=$(${HARDLINK_TIMEOUT[@]+"${HARDLINK_TIMEOUT[@]}"} node "$HARDLINK_TMP" "$OPENCLAW_DIR" --list "$HARDLINK_LIST" $JS_FLAGS) || rc=$?
    # stopped by the limit: rc 124 (stopped), 137 (had to be killed) or 143 (a BusyBox timeout hands the TERM to
    # the command itself) after the whole time; the same codes after a shorter time came from somewhere else
    # (the system killed it) and are an ordinary failure
    if [ "${#HARDLINK_TIMEOUT[@]}" -gt 0 ] && { [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ] || [ "$rc" -eq 143 ]; } \
        && [ $((SECONDS - hl_t0 + 1)) -ge "$HARDLINK_NODE_LIMIT" ]; then
        :
    else
        break
    fi
    if printf '%s\n' "$out" | grep -q '^SUMMARY '; then
        # it reported its result and then did not exit: the result is taken from its output
        echo "  Note: the Node.js process of the hard-link patch had reported its result but did not exit within ${HARDLINK_NODE_LIMIT}s; the result is used."
        s=$(printf '%s\n' "$out" | grep '^SUMMARY ' | tail -1)
        rc=0
        case "$s" in *problems=0*) ;; *) rc=3 ;; esac
        [ "$CHECK" != 1 ] || case "$s" in *todo=0\ *) ;; *) rc=3 ;; esac
        break
    fi
    if [ "$HARDLINK_TRY" -ge 2 ]; then
        HARDLINK_TIMED_OUT=1
        break
    fi
    echo "  Note: the Node.js process of the hard-link patch did not finish within ${HARDLINK_NODE_LIMIT}s and was stopped; trying once more."
    HARDLINK_TRY=2
done
summary=$(printf '%s\n' "$out" | { grep '^SUMMARY ' || true; } | tail -1 | sed 's/^SUMMARY //')
# (|| true: with nothing to report grep finds no line, and that must not end the script)
{ printf '%s\n' "$out" | grep -v '^SUMMARY ' || true; } | while IFS= read -r line; do
    case "$line" in
        PATCHED*) echo -e "  ${GREEN}[PATCHED]${NC} ${line#PATCHED }" ;;
        TODO*) echo -e "  ${YELLOW}[TODO]${NC} ${line#TODO }" ;;
        NOMATCH*|NOTARGET*|WRITEFAIL*) echo -e "  ${YELLOW}[WARN]${NC} ${line%% *}: ${line#* } - hard-link fallback NOT applied here" ;;
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
    if [ "$HARDLINK_TIMED_OUT" = 1 ]; then
        echo -e "${YELLOW}[WARN]${NC} Hard-link fallback incomplete: the Node.js process did not finish within ${HARDLINK_NODE_LIMIT}s, twice. Running 'oa --update' again usually fixes it."
    else
        echo -e "${YELLOW}[WARN]${NC} Hard-link fallback incomplete (${summary:-node failed}): this OpenClaw version does not match the patch, or a file could not be written."
    fi
    echo "       Hard links are denied on Android, so the data migration of 'openclaw doctor --fix' can fail with EACCES."
fi
# (a normal run never fails the caller; --check reports through its exit code)
[ "$CHECK" = 1 ] && exit "$rc"
exit 0
