# tests/openclaw-smoke — stage 1 of the new-version check

Stage 1 installs one OpenClaw version in a linux/arm64 container under a system-call filter that imitates the
Android app sandbox (`trap_android.py`), then starts the gateway, restarts it, runs doctor and a backup, and
makes one agent turn through a mock model. It is the second look at a candidate for the OpenClaw pin, after
`tests/verify-openclaw-fit.sh` (stage 0, no install). **A PASS is not a go-ahead for a pin bump:** the trap
imitates three system calls and one denied file. SELinux, API-level differences and Termux's own layer are for
the QA devices. The whole procedure is in `.agent/note/openclaw-pin-bump.md` (kept in the private repository).

```sh
bash tests/verify-openclaw-smoke.sh latest --baseline 2026.10.1     # about 6 minutes per version
```

| File | What it is |
|---|---|
| `../verify-openclaw-smoke.sh` | entry point on the host: stage 0, then Docker, then the comparison with the baseline |
| `stage1.sh <version> <install\|run>` | runs **inside** a container of the `node:<Node.js pin>-bookworm` image (Node.js, Python 3 and curl are in it): the `install` phase has the network and runs no package code, the `run` phase has no network (`--network none`) and runs the package; the two share one Docker volume |
| `trap_android.py` | the filter: `openat2` → SIGSYS, `linkat` → EACCES, `ioctl FICLONE` → EACCES (aarch64 numbers only) |
| `mock-model/server.mjs` | the mock model server for the agent turn (a single file with no dependencies; `mock-model/README.md` explains the options and the self test) |

## Reading the result

Every judged row is `[ok]` or `[NOT OK]`; the last line is `RESULT:`; the exit status is 0 (all ok), 1 (a row is
not ok) or 2 (could not judge: no Docker, install failed, a step did not run). A PASS needs stage 0 and stage 1.

`[SKIPPED]` is a step that could not be judged (exit 2). `[NOT RUN]` is printed when the system-call filter could not
be installed in the container (a runtime that refuses a second seccomp filter): nothing after it was started and the
reason is the runtime's own (`seccomp failed errno=...`).

One flaky direction exists: a SIGTERM stop that takes longer than 15 seconds is `[NOT OK]` even for a healthy
gateway (baseline 2026.10.1: 1 of 16 stops ended with `rc=137`). If exactly one stop row fails and its detail shows a
`draining active work before stop` line, run the same candidate again before reading it as a regression. There is no
automatic retry on purpose.

The controls (C1..C7) are the part that makes a PASS mean something. Each failing control has a **partner** that
removes only that cause and has to succeed; that pair is the proof. The reason line the gateway prints is checked
as far as it can tell the causes apart: C1 and C4 must not show the PID-namespace refusal, C3 must show it, C6 must
not be a timeout nor the namespace. (The link trap and the `openat2` trap both end in the same "bootstrap failed"
line: only the partners tell them apart.)

| Control | Fails because | Partner |
|---|---|---|
| C1 raw tree (no patches), full trap | link/reflink refused at bootstrap | C2: same, link trap off → starts |
| C3 raw tree (no F4), link trap off, namespace file denied | `different PID namespace` | the patched tree starts with the same file denied (rows `[ENOENT]` `[EACCES]`) |
| C4 patched tree, wrapper hook off | `openat2` trap | C5: same, openat2 trap off → starts |
| C6 raw tree, backup create | link/reflink refused (and not the namespace) | C7: same, link trap off → succeeds |

## What a run touches

The containers get `tests/openclaw-smoke`, `platforms/openclaw` read-only, one writable directory of their own per run, and a Docker volume. The rest of
the working tree (signing keys, local notes) is not mounted. The package's code runs only in the `run` phase,
which has **no network**: it cannot reach the internet or services that listen on the host's loopback (an adb
server, a local git server). The `install` phase has the network and runs no package code
(`npm install --ignore-scripts`). The host script never reads a file a container wrote: it stores each
container's standard output and exit status. What the containers wrote (`artifacts-<version>/`) is for reading by
hand and is untrusted. The `install` phase is the exception to "no network": npm has it, and package metadata (a
dependency given as a URL) steers npm's own requests, which can reach services of the host (adb server, local
git server). npm's cache lives inside the install container, so an answer does not reach the `run` phase through
it, but a tarball that answers is installed into the package's tree like any dependency. npm is told not to use
git (`npm_config_git=/bin/false`; a GitHub tarball by commit still installs). Run it on a machine whose loopback
services you would let an unknown package send a GET request to (a GitHub runner is one). CPU, memory and disk are not limited (only the number of processes): a
hostile package can fill the disk of the host through the output directory. The run also writes stage 0's cache (`OA_FIT_CACHE`) and npm's cache, creates and removes
a volume `oa-smoke-w-*` per version (remove a leftover of a killed run with `docker volume rm`) and pulls the
image. Ctrl-C or SIGTERM stops the running container and removes the volume (exit status 130).

## Things that went wrong while building this (do not repeat)

- **Do not deny `/proc/self/ns/pid` with `NODE_OPTIONS=--require`.** OpenClaw starts some worker threads with
  an environment of their own; the preload does not reach them, and a correctly patched gateway then fails with
  `different PID namespace`. `stage1.sh` rewrites the path string in every JavaScript file of `dist/` after the patches are applied.
- **A control that fails proves nothing until its reason is read.** The first draft's "raw tree + namespace
  denied" control failed at the link trap and was taken as a namespace proof.
- **doctor, backup and the agent turn run with the namespace file denied** and right after a SIGKILLed gateway
  (the state a device is in); run in a restored tree they judge the wrong thing. They read the namespace file
  themselves (strace of 2026.10.2: doctor 5 times, backup create 2, agent 2, backup verify 0), but the gateway
  rows are what tells a missing F4 apart.
- **A gateway that answers `/health` once and then dies is not a working gateway.** Every start that has to work is
  watched for 20 seconds after its first answer (the first heartbeat, at 15 seconds, falls into it), and every SIGTERM stop is a judged row that has to end within 15 seconds
  with exit status 0 (a shutdown that crashes with 134 is not a stop).
- Package output reaches the logs: reasons are cleaned (control characters, `::`, `##[`) before they are
  printed, and the workflow prints the output between `::stop-commands::` and its resume token.

## Known limits

- A native library inside the package (`@trycua/cua-driver-linux-arm64-gnu`'s `libcua_driver_sdk.so`) also contains the
  namespace file's path; a string in a binary cannot be rewritten, and whether the gateway loads it on Android is not
  known (open item).
- The `run` phase has no network, so the gateway's remote model catalog refresh ("remote model catalog refresh
  failed" in the log) is never exercised under the trap; the first harness version had the network and applied
  the catalog (it appears to write SQLite state - inference from reading the code). A failure of that path on
  Android would not show here.
- The namespace file is denied by rewriting its path string in the package's `dist/` (every `.js`/`.mjs`/`.cjs`
  file, checked by a row). A read whose path is assembled from pieces, or one outside `dist/`, is not denied;
  the controls and the `strace` counts above are what show that the rewrite reaches the reads that matter.
- The package's code runs under the same user as the harness, with its HOME in the shared volume. This checks a
  candidate from the npm registry for regressions; it does not judge a hostile package (such a package could
  forge a verdict, for example by its own `/health` answer or output). What the isolation protects is the host.
- A JavaScript file outside `dist/` that names the namespace path is not rewritten: the run is `[SKIPPED]` for it
  (exit 2), not a fault of the package.
- If OpenClaw itself starts to cope with a missing namespace file or with refused hard links, C1, C3 and C6 turn
  `[NOT OK]` (the raw tree no longer fails): that is a change to read, not a harness fault. The same holds for C4
  if the file-system helper stops using `openat2` (the wrapper hook is then no longer what the trap acts on).
- The `[EACCES]` variant needs a non-root user (root gets ENOENT from a mode 000 directory): the containers run as
  uid 1000, and a row of the run phase checks that the denied path really gives EACCES.
- The system-call filter itself is checked first (`$T true`): a container runtime that refuses the filter ends the
  run with exit 2 and the runtime's reason (`seccomp failed errno=...`), not as a failed install.

## Not covered

The update path from an older state (an old installation → the candidate's `oa --update`) is a later unit:
the state images it needs are not in the repository yet. Stage 1 here is the new-install path only.
