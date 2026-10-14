# release/ - signed release manifest tools

Every public release of this repository carries two files next to the scripts:

| File | What it is |
|---|---|
| `RELEASE.manifest` | the sha256 of every file of the release (except `android/`), one line per file |
| `RELEASE.manifest.sig` | a detached OpenPGP signature of `RELEASE.manifest` |

The public key ring is `release/oa-release.gpg` (binary). **Compare the key fingerprints in the README with
`gpg --show-keys --with-subkey-fingerprint release/oa-release.gpg` before you trust a copy of it.**

Status: signed releases carry the two files (the first signed release has not been published when this is written).
Checking them is planned for the scripts that download and run code (`oa --update`, `oa --install`, the installer's
last step, `bootstrap.sh`) in a later release; until then those scripts do not read these files.

## Check a release by hand

The check has to start from something that is **not** in the archive you are checking: the key fingerprints. Take
them from the project README on github.com and from the release notes of the GitHub release (two places), and
compare. Then, in a shell:

```sh
mkdir tree && curl -sL https://github.com/AidanPark/openclaw-android/archive/refs/heads/main.tar.gz | tar -xz -C tree --strip-components=1
# 1. the key ring of the archive must be the key you know: exactly one "pub" line, and its fingerprint (and those
#    of the "sub" lines) equal to the published values. (This creates ~/.gnupg if you have none.)
gpg --show-keys --with-subkey-fingerprint ./tree/release/oa-release.gpg
# 2. the signature of the manifest, with the system's gpgv and that key ring (not with a script of the archive)
gpgv --keyring ./tree/release/oa-release.gpg ./tree/RELEASE.manifest.sig ./tree/RELEASE.manifest
# 3. the manifest names the hash of the verifier: compare it before you run the verifier
grep '  release/release-verify.sh$' tree/RELEASE.manifest
sha256sum tree/release/release-verify.sh
# 4. then the whole tree against the manifest
bash tree/release/release-verify.sh --tree tree
```

Step 4 alone proves nothing: the archive's own script would check the archive's own files (a changed script can end
with exit 0). Steps 1 to 3 are what make step 4 mean something. (A `git clone` is not a release tree: it has a `.git`
directory and the check reports it. The archive is what the scripts download.)

`release-verify.sh` needs `gpgv` (GnuPG) and `sha256sum` (or `shasum`). It ends with exit 0 only when the signature is
good and the tree is exactly the signed one: no missing file, no extra file, no link, no changed byte. A failure
prints one line `FAIL <class>: <detail>`; the classes are `sig-bad`, `sig-nokey`, `sig-clock`, `sig-revoked`, `grammar`,
`name`, `perm`, `link`, `set`, `hash`, `version`. Exit 2 means that it could not check (no `gpgv`, no key ring, a
tool is missing).

## Files

| File | Use |
|---|---|
| `release-manifest.sh` | writes the manifest of an unpacked tree (the maintainer's deploy step runs it) |
| `release-verify.sh` | checks a tree against its manifest and signature (the deploy step and the CI run it) |
| `oa-release.gpg` | the public key ring: the master key and two signing subkeys (the key in daily use and a standby) |

## What the signature does not cover

It covers the scripts and configuration of this repository. It does not cover what those scripts install from other
places (npm packages, the Node.js download, Termux packages), and the very first `curl | bash` can only be checked
by comparing the key fingerprint printed by `bootstrap.sh` with the one in the README.
