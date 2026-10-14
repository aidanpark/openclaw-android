#!/usr/bin/env bash
# verify-release-signing.sh - harness for release/release-manifest.sh and release/release-verify.sh
#
# It builds a small release tree, makes a test key family (master + daily + standby subkeys, the structure of the
# real one; the keys live in a temporary GNUPGHOME and are thrown away), and then runs the generator and the
# verifier over good and damaged cases. A case passes when the verifier ends with the exit status and the class
# that the case expects (a verifier that only says "fail" would pass a wrong reason).
#
#   good        manifest -> sign -> verify
#   signature   bad signature, key not in the keyring, revoked key, the standby key, a keyring that also holds an
#               expired subkey that was not used, a key created in the future, two signatures in either order
#   tree        extra file, missing file, changed file, symbolic link, bad name (newline, space), a .git
#               directory (a worktree is not a release tree), files under android/ (out of scope)
#   manifest    unsorted, duplicated path, CRLF, missing or unknown header line, wrong hash line
#   version     an older release than the installed one
#   tools       no gpgv, no sha256sum (the shasum fallback)
#   generator   refuses a tree with a link or a bad name and prints nothing
#
# With OA_RS_DOCKER=1 (and a working docker) the whole harness runs again inside a Debian 12 container (gpgv 2.2,
# GNU find/sort/sha256sum) and a Debian 13 container (gpgv 2.4): the BSD tools of a Mac and the GNU tools behave
# differently, and the verdict of some signature cases depends on the gpgv version (those cases only assert what
# is the same on all versions and print the rest as [INFO]).
#
# Usage:
#   bash tests/verify-release-signing.sh
#   OA_RS_DOCKER=1 bash tests/verify-release-signing.sh
#
# Needs: gpg and gpgv (GnuPG 2.2 or newer), the tools of release/. Nothing outside the temporary directory is written.

set -uo pipefail
export LC_ALL=C
unset CDPATH

ROOT="${OA_TEST_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
GEN="$ROOT/release/release-manifest.sh"
VER="$ROOT/release/release-verify.sh"
[ -f "$GEN" ] && [ -f "$VER" ] || { echo "release/release-manifest.sh and release-verify.sh are not in $ROOT"; exit 2; }
command -v gpg >/dev/null 2>&1 && command -v gpgv >/dev/null 2>&1 || { echo "gpg and gpgv are needed"; exit 2; }

PASS=0; FAIL=0
pass() { printf '[PASS] %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '[FAIL] %s\n' "$1"; FAIL=$((FAIL+1)); }

# a short path: the gpg-agent socket path has a limit of about 100 characters on macOS
W=$(mktemp -d /tmp/oa-rs.XXXXXX) || exit 2
export GNUPGHOME="$W/gh"
cleanup() {
    local h
    for h in "$W"/gh* "$W/other"; do [ -d "$h" ] && gpgconf --homedir "$h" --kill all >/dev/null 2>&1; done
    chmod -R u+rwx "$W" 2>/dev/null
    rm -rf "${W:?}"
}
trap cleanup EXIT
mkdir -m 700 "$GNUPGHOME"

G() { gpg --batch --pinentry-mode loopback --passphrase x "$@" >/dev/null 2>&1; }

# ---------------------------------------------------------------- the test key family
G --quick-generate-key "Release Test Key" ed25519 cert never
FPR=$(gpg --list-keys --with-colons | awk -F: '/^fpr:/{print $10; exit}')
G --quick-add-key "$FPR" ed25519 sign never
DAILY=$(gpg --list-keys --with-colons --with-subkey-fingerprint "$FPR" | awk -F: '/^sub:/{s=1} s&&/^fpr:/{print $10; exit}')
G --quick-add-key "$FPR" ed25519 sign never
STBY=$(gpg --list-keys --with-colons --with-subkey-fingerprint "$FPR" | awk -F: -v d="$DAILY" '/^sub:/{s=1} s&&/^fpr:/{if ($10!=d) {print $10; exit}}')
[ -n "$FPR" ] && [ -n "$DAILY" ] && [ -n "$STBY" ] && [ "$DAILY" != "$STBY" ] || { echo "could not create the test keys"; exit 2; }
gpg --export "$FPR" > "$W/ring-both.gpg" 2>/dev/null

# an unrelated key (the signature of a key that is not in the keyring)
OTHERHOME="$W/other"; mkdir -m 700 "$OTHERHOME"
GNUPGHOME="$OTHERHOME" gpg --batch --pinentry-mode loopback --passphrase x --quick-generate-key "Other Key" ed25519 sign never >/dev/null 2>&1
GNUPGHOME="$OTHERHOME" gpg --batch --export > "$W/ring-other.gpg" 2>/dev/null
OTHERFPR=$(GNUPGHOME="$OTHERHOME" gpg --list-keys --with-colons | awk -F: '/^fpr:/{print $10; exit}')

sign() { # <key fingerprint> <home> <manifest> <out sig>   (the subkey, "!" = exactly that one)
    GNUPGHOME="$2" gpg --batch --pinentry-mode loopback --passphrase x --local-user "$1"'!' --detach-sign --output "$4" "$3" >/dev/null 2>&1
}

# ---------------------------------------------------------------- a release tree
mk_tree() { # <dir>
    local d=$1
    rm -rf "$d"; mkdir -p "$d/scripts" "$d/platforms/openclaw" "$d/patches" "$d/release" "$d/android/app" "$d/docs"
    printf '#!/usr/bin/env bash\nOA_VERSION="1.3.3"\n' > "$d/scripts/lib.sh"
    printf '#!/usr/bin/env bash\necho oa\n' > "$d/oa.sh"
    printf '#!/usr/bin/env bash\necho update-core\n' > "$d/update-core.sh"
    printf 'PLATFORM_NODE_VERSION="24.21.0"\n' > "$d/platforms/openclaw/config.env"
    printf 'module.exports = {};\n' > "$d/patches/glibc-compat.js"
    printf 'doc\n' > "$d/docs/readme.md"
    printf 'kt\n' > "$d/android/app/Main.kt"
    cp "$W/ring-both.gpg" "$d/release/oa-release.gpg"
    chmod 755 "$d/oa.sh"
}
T="$W/tree"; mk_tree "$T"

# run the verifier and report "exit class"
run() { # <args...>  -> sets RC and CLS
    local out
    out=$(bash "$VER" --quiet "$@" 2>&1); RC=$?
    CLS=$(printf '%s' "$out" | sed -nE 's/^(FAIL|ERROR) ([a-z-]+):.*/\2/p' | head -1)
    [ "$RC" = 0 ] && CLS=ok
    LAST=$out
}
expect() { # <label> <exit> <class>
    if [ "$RC" = "$2" ] && [ "$CLS" = "$3" ]; then pass "$1 (exit $RC, $CLS)"
    else fail "$1: expected exit $2 / $3, got exit $RC / '$CLS': $(printf '%s' "$LAST" | head -1 | cut -c1-160)"; fi
}

# ---------------------------------------------------------------- good
echo "== good"
bash "$GEN" "$T" --issued 2026-10-13T00:00:00Z > "$W/m.good" 2>"$W/gen.err"; grc=$?
[ "$grc" = 0 ] && pass "generator writes a manifest for the tree" || fail "generator: exit $grc: $(cat "$W/gen.err")"
sed -n '1,3p' "$W/m.good" | grep -q '^# openclaw-android release manifest v1$' && pass "the manifest has the v1 header" || fail "header"
! grep -q 'android/' "$W/m.good" && pass "android/ is out of the manifest" || fail "android/ is in the manifest"
grep -q '  release/oa-release.gpg$' "$W/m.good" && pass "the keyring file is in the manifest" || fail "keyring file missing from the manifest"
cp "$W/m.good" "$T/RELEASE.manifest"
run --tree "$T" --no-sig;                                   expect "unsigned: tree matches the manifest" 0 ok
sign "$DAILY" "$GNUPGHOME" "$T/RELEASE.manifest" "$T/RELEASE.manifest.sig"
run --tree "$T";                                            expect "signed with the daily key: verified" 0 ok
run --tree "$T" --min-oa-version 1.3.3;                     expect "same version as installed" 0 ok
run --tree "$T" --min-oa-version 1.3.2;                     expect "newer than installed" 0 ok

# ---------------------------------------------------------------- signature
echo "== signature"
run --tree "$T" --min-oa-version 1.3.4;                     expect "older than installed: refused" 1 version
cp "$T/RELEASE.manifest" "$W/m.tampered"; sed -i.bak '4s/^./0/' "$W/m.tampered" 2>/dev/null; rm -f "$W/m.tampered.bak"
if cmp -s "$W/m.tampered" "$T/RELEASE.manifest"; then sed -i.bak '4s/^./1/' "$W/m.tampered"; rm -f "$W/m.tampered.bak"; fi
run --tree "$T" --manifest "$W/m.tampered";                 expect "manifest changed after signing: BADSIG" 1 sig-bad
sign "$OTHERFPR" "$OTHERHOME" "$T/RELEASE.manifest" "$W/s.other"
run --tree "$T" --sig "$W/s.other";                         expect "signed by a key that is not in the keyring" 1 sig-nokey
sign "$STBY" "$GNUPGHOME" "$T/RELEASE.manifest" "$W/s.stby"
run --tree "$T" --sig "$W/s.stby";                          expect "signed with the standby key (it is in the keyring)" 0 ok
rm -f "$W/s.none"; run --tree "$T" --sig "$W/s.none";       expect "no signature file" 2 sig-tool
run --tree "$T" --keyring "$W/missing.gpg";                 expect "no keyring file" 2 sig-tool
: > "$W/empty.gpg"; run --tree "$T" --keyring "$W/empty.gpg"; expect "empty keyring" 2 sig-tool
head -c 40 "$W/ring-both.gpg" > "$W/cut.gpg"; run --tree "$T" --keyring "$W/cut.gpg"; expect "truncated keyring: the key is not found" 1 sig-nokey

# revoked daily subkey (the keyring that carries the revocation)
printf 'key 1\nrevkey\ny\n0\n\ny\nsave\n' > "$W/rev.cmd"
gpgconf --homedir "$GNUPGHOME" --kill all >/dev/null 2>&1   # sockets cannot be copied
cp -R "$GNUPGHOME" "$W/gh-rev" && chmod 700 "$W/gh-rev"
# which of the two subkeys is "key 1"? pick by fingerprint so the test does not depend on the listing order
idx=$(GNUPGHOME="$W/gh-rev" gpg --list-keys --with-colons --with-subkey-fingerprint "$FPR" | awk -F: -v d="$DAILY" '/^sub:/{n++} /^fpr:/&&n&&$10==d{print n; exit}')
printf 'key %s\nrevkey\ny\n0\n\ny\nsave\n' "${idx:-1}" > "$W/rev.cmd"
GNUPGHOME="$W/gh-rev" gpg --batch --pinentry-mode loopback --passphrase x --command-fd 0 --edit-key "$FPR" < "$W/rev.cmd" >/dev/null 2>&1
GNUPGHOME="$W/gh-rev" gpg --export "$FPR" > "$W/ring-revoked.gpg" 2>/dev/null
gpgconf --homedir "$W/gh-rev" --kill all >/dev/null 2>&1
run --tree "$T" --keyring "$W/ring-revoked.gpg";            expect "daily key revoked in the keyring: refused" 1 sig-revoked
run --tree "$T" --sig "$W/s.stby" --keyring "$W/ring-revoked.gpg"; expect "standby key still passes when the daily key is revoked" 0 ok

# an expired subkey that was NOT used: a key family made with a clock in the past (one subkey that expired the next
# day, one without an expiry); the manifest is signed now by the one without an expiry
EH="$W/gh-exp"; mkdir -m 700 "$EH"
FT="20250101T000000"
GNUPGHOME="$EH" gpg --batch --pinentry-mode loopback --passphrase x --faked-system-time "$FT" --quick-generate-key "Expiry Test Key" ed25519 cert never >/dev/null 2>&1
EFPR=$(GNUPGHOME="$EH" gpg --list-keys --with-colons | awk -F: '/^fpr:/{print $10; exit}')
GNUPGHOME="$EH" gpg --batch --pinentry-mode loopback --passphrase x --faked-system-time "$FT" --quick-add-key "$EFPR" ed25519 sign 1d >/dev/null 2>&1
GNUPGHOME="$EH" gpg --batch --pinentry-mode loopback --passphrase x --faked-system-time "$FT" --quick-add-key "$EFPR" ed25519 sign never >/dev/null 2>&1
# the subkey without an expiry: the one whose expiry field is empty
EGOOD=$(GNUPGHOME="$EH" gpg --list-keys --with-colons --with-subkey-fingerprint "$EFPR" | awk -F: '/^sub:/{ex=$7; s=1} s&&/^fpr:/{ if (ex=="") {print $10; exit} }')
EEXP=$(GNUPGHOME="$EH" gpg --list-keys --with-colons --with-subkey-fingerprint "$EFPR" | awk -F: '/^sub:/{ex=$7; s=1} s&&/^fpr:/{ if (ex!="") {print $10; exit} }')
if [ -n "$EGOOD" ] && [ -n "$EEXP" ]; then
    GNUPGHOME="$EH" gpg --batch --export "$EFPR" > "$W/ring-expired.gpg" 2>/dev/null
    GNUPGHOME="$EH" gpg --batch --pinentry-mode loopback --passphrase x --local-user "$EGOOD"'!' --detach-sign --output "$W/s.expired-ring" "$T/RELEASE.manifest" >/dev/null 2>&1
    run --tree "$T" --sig "$W/s.expired-ring" --keyring "$W/ring-expired.gpg"
    expect "keyring with an unused expired subkey: the valid subkey verifies (KEYEXPIRED alone is not a failure)" 0 ok
    # and the signature of the expired subkey itself is refused (made at the time when it was valid is not possible
    # here: sign now with the expired one is refused by gpg, so this case checks only that no file is made)
else
    fail "could not make the key family with an expired subkey (faked clock refused?)"
fi

# a key created in the future (the key is made with a clock two years ahead; the verifier's clock is real)
FH="$W/gh-future"; mkdir -m 700 "$FH"
GNUPGHOME="$FH" gpg --batch --pinentry-mode loopback --passphrase x --faked-system-time "$(( $(date +%s) + 63072000 ))" --quick-generate-key "Future Key" ed25519 sign never >/dev/null 2>&1
FFPR=$(GNUPGHOME="$FH" gpg --list-keys --with-colons | awk -F: '/^fpr:/{print $10; exit}')
if [ -n "$FFPR" ]; then
    GNUPGHOME="$FH" gpg --batch --export > "$W/ring-future.gpg" 2>/dev/null
    GNUPGHOME="$FH" gpg --batch --pinentry-mode loopback --passphrase x --faked-system-time "$(( $(date +%s) + 63072000 ))" --detach-sign --output "$W/s.future" "$T/RELEASE.manifest" >/dev/null 2>&1
    run --tree "$T" --sig "$W/s.future" --keyring "$W/ring-future.gpg"
    # gpgv 2.2 prints no NO_PUBKEY line for this case and ends in the "other failure" class; 2.4 and newer say clock
    if [ "$RC" = 1 ] && { [ "$CLS" = sig-clock ] || [ "$CLS" = sig-revoked ]; }; then pass "key created in the future: refused (exit 1, $CLS; the class depends on the gpgv version)"
    else fail "key created in the future: expected exit 1 / sig-clock or sig-revoked, got exit $RC / '$CLS': $(printf '%s' "$LAST" | head -1 | cut -c1-140)"; fi
else
    fail "could not create the future key"
fi
gpgconf --homedir "$FH" --kill all >/dev/null 2>&1

# two signatures in one file: the known key first passes on every version; the unknown key first is only
# information (gpgv 2.5 stops at the first key that it does not know)
cat "$T/RELEASE.manifest.sig" "$W/s.other" > "$W/s.dual-known-first"
cat "$W/s.other" "$T/RELEASE.manifest.sig" > "$W/s.dual-unknown-first"
run --tree "$T" --sig "$W/s.dual-known-first";              expect "two signatures, the known key first" 0 ok
run --tree "$T" --sig "$W/s.dual-unknown-first"
echo "[INFO] gpgv version: $(gpgv --version | head -1 | awk '{print $NF}')"
echo "[INFO] two signatures, the unknown key first: exit $RC / ${CLS:-?} (gpgv $(gpgv --version | head -1 | awk '{print $NF}'): 2.5 stops at the first unknown key, 2.2 and 2.4 pass)"

# a damaged second signature (the public-key algorithm byte of a copy of the good signature is changed to an unknown
# value): gpgv prints an ERRSIG with a reason other than "no public key", and the verifier has to refuse
if command -v python3 >/dev/null 2>&1; then
    python3 - "$T/RELEASE.manifest.sig" "$W/s.damaged" <<'PY'
import sys
b = bytearray(open(sys.argv[1], 'rb').read())
i = b.find(bytes([0x04, 0x00, 0x16]))      # version 4, signature type 0, algorithm 22 (EdDSA)
if i < 0:
    sys.exit(1)
b[i + 2] = 0x63                             # an algorithm that does not exist
open(sys.argv[2], 'wb').write(b)
PY
    if [ -f "$W/s.damaged" ]; then
        cat "$T/RELEASE.manifest.sig" "$W/s.damaged" > "$W/s.dual-damaged"
        run --tree "$T" --sig "$W/s.dual-damaged"
        if [ "$RC" = 1 ]; then pass "a good signature plus a damaged one (ERRSIG other than no-key): refused (exit 1, $CLS)"
        else fail "good + damaged signature: expected exit 1, got exit $RC / $CLS"; fi
    else
        echo "[INFO] could not damage a signature copy: the case is not run"
    fi
else
    echo "[INFO] no python3: the damaged-signature case is not run"
fi

# a signature made with a subkey that has expired since (valid when it signed): the clock class
if [ -n "${EEXP:-}" ]; then
    GNUPGHOME="$EH" gpg --batch --pinentry-mode loopback --passphrase x --faked-system-time "20250101T010000" --local-user "$EEXP"'!' --detach-sign --output "$W/s.expired-used" "$T/RELEASE.manifest" >/dev/null 2>&1
    if [ -f "$W/s.expired-used" ]; then
        run --tree "$T" --sig "$W/s.expired-used" --keyring "$W/ring-expired.gpg"
        expect "signed with a subkey that has expired since: refused" 1 sig-clock
    else
        echo "[INFO] could not sign with the expired subkey: the case is not run"
    fi
fi
# a signature time one hour ahead of this clock is accepted (a device clock a little behind must not block updates)
GNUPGHOME="$GNUPGHOME" gpg --batch --pinentry-mode loopback --passphrase x --faked-system-time "$(( $(date +%s) + 3600 ))" --local-user "$DAILY"'!' --detach-sign --output "$W/s.ahead" "$T/RELEASE.manifest" >/dev/null 2>&1
if [ -f "$W/s.ahead" ]; then run --tree "$T" --sig "$W/s.ahead"; expect "a signature made one hour ahead of the clock" 0 ok; fi
# a relative keyring path (gpgv runs with a temporary home)
# (run sets RC and CLS: it must not run in a subshell, or expect would judge the value of the case before)
pushd "$W" >/dev/null || exit 2
run --tree "$T" --keyring "ring-both.gpg"
popd >/dev/null || exit 2
expect "a relative --keyring path" 0 ok

# ---------------------------------------------------------------- tree
echo "== tree"
mk_tree "$W/t2"; cp "$W/m.good" "$W/t2/RELEASE.manifest"
printf 'x\n' > "$W/t2/extra.sh";                                  run --tree "$W/t2" --no-sig; expect "an extra file in the tree" 1 set
rm -f "$W/t2/extra.sh"; rm -f "$W/t2/oa.sh";                      run --tree "$W/t2" --no-sig; expect "a file of the manifest is missing" 1 set
mk_tree "$W/t2"; cp "$W/m.good" "$W/t2/RELEASE.manifest"
printf '#!/usr/bin/env bash\necho changed\n' > "$W/t2/oa.sh";     run --tree "$W/t2" --no-sig; expect "a file was changed" 1 hash
mk_tree "$W/t2"; cp "$W/m.good" "$W/t2/RELEASE.manifest"
ln -s oa.sh "$W/t2/link.sh";                                      run --tree "$W/t2" --no-sig; expect "a symbolic link in the tree" 1 link
rm -f "$W/t2/link.sh"; mkdir -p "$W/t2/android/x"; ln -s ../../oa.sh "$W/t2/android/x/l"; printf 'k\n' > "$W/t2/android/new.kt"
run --tree "$W/t2" --no-sig;                                      expect "files and a link under android/ are out of scope" 0 ok
mk_tree "$W/t2"; cp "$W/m.good" "$W/t2/RELEASE.manifest"
printf 'x\n' > "$W/t2/patches/glibc-libs
oa.sh";                                                           run --tree "$W/t2" --no-sig; expect "a file name with a newline" 1 name
rm -f "$W/t2/patches/glibc-libs"$'\n'"oa.sh"
printf 'x\n' > "$W/t2/patches/a b";                               run --tree "$W/t2" --no-sig; expect "a file name with a space" 1 name
rm -f "$W/t2/patches/a b"; mkdir -p "$W/t2/.git"; printf 'gitdir: x\n' > "$W/t2/.git/HEAD"
run --tree "$W/t2" --no-sig;                                      expect "a .git directory is not a release tree" 1 set
rm -rf "$W/t2/.git"
mkdir "$W/t2/has space dir";                                      run --tree "$W/t2" --no-sig; expect "an empty directory with a space in its name" 1 name
rmdir "$W/t2/has space dir"
printf 'x\n' > "$W/t2/-";                                          run --tree "$W/t2" --no-sig; expect "a file named - that is not in the manifest" 1 set
printf 'x\n' > "$W/t2/-x"
# a file named "-" that IS in the manifest: it has to be hashed as a file (not read from standard input)
mk_tree "$W/t6"; printf 'EVIL\n' > "$W/t6/-"; printf 'x\n' > "$W/t6/-x"
bash "$GEN" "$W/t6" --issued 2026-10-13T00:00:00Z > "$W/t6/RELEASE.manifest" 2>/dev/null; grc=$?
if [ "$grc" = 0 ]; then
    run --tree "$W/t6" --no-sig </dev/null; expect "files named - and -x are hashed as files" 0 ok
    printf 'CHANGED\n' > "$W/t6/-"
    run --tree "$W/t6" --no-sig </dev/null; expect "a changed file named -" 1 hash
else
    fail "generator refused a tree with files named - and -x (exit $grc)"
fi
# "android" that is a file or a link is not the out-of-scope directory
mk_tree "$W/t7"; rm -rf "$W/t7/android"; printf 'x\n' > "$W/t7/android"
bash "$GEN" "$W/t7" --issued 2026-10-13T00:00:00Z > "$W/t7/RELEASE.manifest" 2>/dev/null
grep -q '  android$' "$W/t7/RELEASE.manifest" && pass "a file named android is in the manifest (not pruned)" || fail "a file named android was pruned"
rm -f "$W/t7/android"; ln -s scripts "$W/t7/android"
run --tree "$W/t7" --no-sig;                                      expect "android as a symbolic link" 1 link
# a directory that cannot be listed hides its files
mk_tree "$W/t8"; mkdir "$W/t8/hooks"; printf 'x\n' > "$W/t8/hooks/post.sh"
bash "$GEN" "$W/t8" --issued 2026-10-13T00:00:00Z > "$W/t8/RELEASE.manifest" 2>/dev/null
chmod 111 "$W/t8/hooks"
run --tree "$W/t8" --no-sig;                                      expect "a directory that cannot be read" 1 perm
chmod 755 "$W/t8/hooks"
# upper- and lower-case names in one directory: the order of the manifest is the byte order (LC_ALL=C), also when
# the caller's locale says otherwise
mk_tree "$W/t9"; printf 'a\n' > "$W/t9/CHANGELOG.md"; printf 'b\n' > "$W/t9/bootstrap.sh"; printf 'c\n' > "$W/t9/Zeta.md"; printf 'd\n' > "$W/t9/alpha.md"
bash "$GEN" "$W/t9" --issued 2026-10-13T00:00:00Z > "$W/t9/RELEASE.manifest" 2>/dev/null
for loc in en_US.UTF-8 C.UTF-8 ko_KR.UTF-8; do
    if locale -a 2>/dev/null | grep -qx "$loc"; then
        LC_ALL=$loc run --tree "$W/t9" --no-sig; expect "mixed-case names verify under $loc" 0 ok
    fi
done

# ---------------------------------------------------------------- manifest
echo "== manifest"
mk_tree "$W/t3"
mfile="$W/t3/RELEASE.manifest"
try_manifest() { # <label> <exit> <class> <file content producer via stdin>
    cat > "$mfile"; run --tree "$W/t3" --no-sig; expect "$1" "$2" "$3"
}
try_manifest "paths not in increasing order" 1 grammar < <(awk 'NR==4{a=$0; next} NR==5{print; print a; next} {print}' "$W/m.good")
try_manifest "a path listed twice" 1 grammar < <({ sed -n '1,4p' "$W/m.good"; sed -n '4p' "$W/m.good"; sed -n '5,$p' "$W/m.good"; })
try_manifest "CRLF line ends" 1 grammar < <(sed 's/$/\r/' "$W/m.good")
try_manifest "header line missing" 1 grammar < <(sed '1d' "$W/m.good")
try_manifest "an unknown header line" 1 grammar < <({ sed -n '1,3p' "$W/m.good"; echo 'keyring_update=abc'; sed -n '4,$p' "$W/m.good"; })
try_manifest "issued has the wrong form" 1 grammar < <({ sed -n '1,2p' "$W/m.good"; echo 'issued=yesterday'; sed -n '4,$p' "$W/m.good"; })
try_manifest "a body line of the wrong form" 1 grammar < <({ cat "$W/m.good"; echo "0000  patches/x"; })
try_manifest "no newline at the end" 1 grammar < <(printf '%s' "$(cat "$W/m.good")")
try_manifest "a path with .." 1 name < <({ sed -n '1,3p' "$W/m.good"; printf '%064d  ../etc/passwd\n' 0; })
try_manifest "an android/ path in the manifest" 1 name < <({ sed -n '1,3p' "$W/m.good"; printf '%064d  android/app/Main.kt\n' 0; })
try_manifest "a manifest without files" 1 grammar < <({ sed -n '1,3p' "$W/m.good"; })
try_manifest "a v2 header (a format this verifier does not know)" 1 grammar < <(sed '1s/v1/v2/' "$W/m.good")
try_manifest "oa_version with a leading zero" 1 grammar < <(sed '2s/=1\.3\.3/=01.3.3/' "$W/m.good")

# ---------------------------------------------------------------- generator
echo "== generator"
mk_tree "$W/t4"; ln -s oa.sh "$W/t4/l.sh"
out=$(bash "$GEN" "$W/t4" 2>/dev/null); rc=$?
[ "$rc" = 1 ] && [ -z "$out" ] && pass "generator refuses a tree with a link and prints nothing (exit 1)" || fail "generator with a link: exit $rc, output: ${out:0:80}"
rm -f "$W/t4/l.sh"; printf 'x\n' > "$W/t4/has space.sh"
out=$(bash "$GEN" "$W/t4" 2>/dev/null); rc=$?
[ "$rc" = 1 ] && [ -z "$out" ] && pass "generator refuses a bad name and prints nothing (exit 1)" || fail "generator with a bad name: exit $rc, output: ${out:0:80}"
# a worktree or a clone has a .git of its own: not a release tree
rm -f "$W/t4/has space.sh"; mkdir -p "$W/t4/.git"; printf 'x\n' > "$W/t4/.git/HEAD"
out=$(bash "$GEN" "$W/t4" 2>/dev/null); rc=$?
[ "$rc" = 1 ] && [ -z "$out" ] && pass "generator refuses a tree with a .git entry and prints nothing (exit 1)" || fail "generator with .git: exit $rc, output: ${out:0:80}"
rm -rf "$W/t4/.git"
# the detail of a failure is one line without control characters (a file name with an escape sequence)
mk_tree "$W/t10"; cp "$W/m.good" "$W/t10/RELEASE.manifest"
esc=$(printf 'bad\033[2Kname')
printf 'x\n' > "$W/t10/patches/$esc"
msg=$(bash "$VER" --tree "$W/t10" --no-sig 2>&1 || true)
if printf '%s' "$msg" | grep -q "$(printf '\033')"; then fail "a control character of a file name reached the output"
else pass "a control character of a file name does not reach the output"; fi
# a manifest line with a control character is shown in the failure line: it must not reach the output either
mk_tree "$W/t11"
{ sed -n '1,3p' "$W/m.good"; printf '%s  %s\n' "$(printf '%064d' 0)" "bad$(printf '\033[2K')name"; } > "$W/t11/RELEASE.manifest"
msg=$(bash "$VER" --tree "$W/t11" --no-sig 2>&1 || true)
if [ -z "$msg" ]; then fail "no failure line for a manifest line with a control character"
elif printf '%s' "$msg" | grep -q "$(printf '\033')"; then fail "a control character of a manifest line reached the output"
else pass "a control character of a manifest line does not reach the output"; fi
rm -rf "$W/t5"; mkdir -p "$W/t5/scripts"; printf 'x\n' > "$W/t5/a.sh"
out=$(bash "$GEN" "$W/t5" 2>/dev/null); rc=$?
[ "$rc" = 1 ] && pass "generator needs OA_VERSION (or --version): exit 1" || fail "generator without a version: exit $rc"
out=$(bash "$GEN" "$W/t5" --version 1.2.3 --issued 2026-01-01T00:00:00Z 2>/dev/null); rc=$?
[ "$rc" = 0 ] && printf '%s\n' "$out" | sed -n 2p | grep -q '^oa_version=1.2.3$' && pass "--version and --issued are used" || fail "--version: exit $rc"
a=$(bash "$GEN" "$T" --issued 2026-10-13T00:00:00Z 2>/dev/null); b=$(bash "$GEN" "$T" --issued 2026-10-13T00:00:00Z 2>/dev/null)
[ -n "$a" ] && [ "$a" = "$b" ] && pass "the manifest is reproducible for the same tree and time" || fail "the manifest differs between two runs"

# ---------------------------------------------------------------- tools
echo "== tools"
# a PATH that holds the tools the verifier uses, with or without gpgv and sha256sum
mkpath() { # <dir> <tools...>
    local d=$1; shift; rm -rf "$d"; mkdir -p "$d"
    local t p
    for t in "$@"; do p=$(command -v "$t" 2>/dev/null) && ln -s "$p" "$d/$t"; done
}
BASETOOLS="bash find sort diff sed awk grep head tail od tr wc cut mktemp rm cat comm perl dirname date printf xargs uname tee env"
mkpath "$W/path-nogpgv" $BASETOOLS sha256sum shasum
PATH_SAVE=$PATH
PATH="$W/path-nogpgv" run --tree "$T";                            expect "no gpgv on the device: could not verify" 2 sig-tool
mkpath "$W/path-noshasum256" $BASETOOLS shasum gpgv
if command -v shasum >/dev/null 2>&1; then
    PATH="$W/path-noshasum256" run --tree "$T";                    expect "no sha256sum: the shasum fallback verifies" 0 ok
else
    echo "[INFO] no shasum on this host: the fallback case is not run"
fi
mkpath "$W/path-nofind" bash sort diff comm sed awk grep head tail od tr wc cut mktemp perl dirname date printf xargs uname tee env sha256sum shasum gpgv
PATH="$W/path-nofind" run --tree "$T";                            expect "no find: could not verify (a missing tool is not a pass)" 2 tool
mkpath "$W/path-nohash" $BASETOOLS gpgv
PATH="$W/path-nohash" run --tree "$T";                            expect "neither sha256sum nor shasum: could not verify" 2 tool
PATH=$PATH_SAVE

# ---------------------------------------------------------------- other gpgv versions and GNU tools
# The whole harness runs again inside Debian 12 (gpgv 2.2) and Debian 13 (gpgv 2.4): GNU find, sort and sha256sum
# behave differently from the BSD tools of a Mac, and the verdict of some signature cases depends on the gpgv version.
if [ "${OA_RS_DOCKER:-0}" = 1 ] && [ -z "${OA_RS_INNER:-}" ] && command -v docker >/dev/null 2>&1; then
    echo "== the same harness in containers (docker)"
    for img in debian:12 debian:13; do
        res=$(docker run --rm -e OA_RS_INNER=1 -e OA_TEST_ROOT=/r -v "$ROOT":/r:ro "$img" bash -c '
            (apt-get update -qq && apt-get install -y -qq gnupg gpgv python3 locales >/dev/null) >/dev/null 2>&1 || { echo NOGPG; exit 0; }
            sed -i "s/^# *\(en_US.UTF-8\)/\1/" /etc/locale.gen 2>/dev/null; locale-gen >/dev/null 2>&1
            useradd -m t >/dev/null 2>&1
            su t -c "bash /r/tests/verify-release-signing.sh" 2>&1' 2>&1)
        if printf '%s\n' "$res" | grep -q NOGPG; then echo "[INFO] $img: gnupg could not be installed (no network?): not run"; continue; fi
        np=$(printf '%s\n' "$res" | grep -c '^\[PASS\]'); nf=$(printf '%s\n' "$res" | grep -c '^\[FAIL\]')
        echo "[INFO] $img: $(printf '%s\n' "$res" | sed -n 's/^\[INFO\] gpgv version: //p' | head -1) $np passed, $nf failed"
        printf '%s\n' "$res" | grep -E '^\[INFO\]' | sed 's/^/        /'
        if [ "$nf" = 0 ] && [ "$np" -ge 50 ]; then pass "$img: the whole harness passes ($np cases)"
        else fail "$img: the harness failed ($np passed, $nf failed)"; printf '%s\n' "$res" | grep '^\[FAIL\]' | head -8; fi
    done
elif [ -z "${OA_RS_INNER:-}" ]; then
    echo "[INFO] containers: not run (set OA_RS_DOCKER=1 with a working docker)"
fi

echo
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" = 0 ]
