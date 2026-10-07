# Troubleshooting

Common issues and solutions when using OpenClaw on Termux.

## curl is broken after Step 3 (CANNOT LINK EXECUTABLE)

```
CANNOT LINK EXECUTABLE "curl": cannot locate symbol "SSL_set_quic_tls_early_data_enabled" referenced by ".../usr/lib/libcurl.so"
```

### Cause

On a freshly installed Termux, running `pkg install curl` without a full upgrade installs a new libcurl on top of the older OpenSSL that came with the Termux app. curl then can't start, so the install command in Step 4 silently does nothing — and `pkg upgrade` fails too, because pkg uses curl.

### Solution

```bash
apt update && apt full-upgrade -y
```

If you see questions about configuration files (`(Y/I/N/O/D/Z) [default=N] ?`), press **Enter** to keep the default. When it finishes, `curl --version` should work — continue from Step 4.

## Gateway won't start: "gateway already running" or "Port is already in use"

```
Gateway failed to start: gateway already running (pid XXXXX); lock timeout after 5000ms
Port 18789 is already in use.
```

### Cause

A previous gateway process was terminated abnormally, leaving behind a lock file or a zombie process. This typically happens when:

- SSH connection drops, leaving the gateway process orphaned
- `Ctrl+Z` (suspend) was used instead of `Ctrl+C` (terminate), leaving the process alive in the background
- Termux was force-killed by Android

> **Note**: Always use `Ctrl+C` to stop the gateway. `Ctrl+Z` only suspends the process — it does not terminate it.

### Solution

#### Step 1: Find and kill remaining processes

```bash
ps aux | grep -E "node|openclaw" | grep -v grep
```

If processes are listed, note the PID and kill them:

```bash
kill -9 <PID>
```

#### Step 2: Remove lock files

```bash
rm -rf $PREFIX/tmp/openclaw-*
```

#### Step 3: Restart the gateway

```bash
openclaw gateway
```

### If it still doesn't work

If the above steps don't help, fully close and reopen the Termux app, then run `openclaw gateway`. Rebooting the phone will reliably clear all state.

## Gateway disconnected: "gateway not connected"

```
send failed: Error: gateway not connected
disconnected | error
```

### Cause

The gateway process has stopped or the SSH session was disconnected.

### Solution

Check the SSH session where the gateway was running. If the session was disconnected, reconnect via SSH and start the gateway:

```bash
openclaw gateway
```

If you get a "gateway already running" error, see the [Gateway won't start](#gateway-wont-start-gateway-already-running-or-port-is-already-in-use) section above.

## Dashboard asks for a token or shows "unauthorized"

```
unauthorized
```

### Cause

The dashboard at `http://127.0.0.1:18789/` signs in with the gateway token. OpenClaw does not show it by default: `openclaw config get gateway.auth.token` hides secrets (it prints `__OPENCLAW_REDACTED__`), and `openclaw dashboard --no-open` prints a link without the token.

### Solution

Print the token from the config file (OpenClaw writes it as plain JSON):

```bash
node -p "require(process.env.HOME + '/.openclaw/openclaw.json').gateway.auth.token"
```

Then either paste the token into the dashboard's auth field, or open this address (replace `<token>`):

```
http://127.0.0.1:18789/#token=<token>
```

Keep the token private — anyone who has it can control your OpenClaw.

If the command fails, the file was probably edited by hand (comments are not valid JSON): open `~/.openclaw/openclaw.json` in an editor and copy the value of `gateway.auth.token`. If there is no `gateway.auth.token`, OpenClaw's dashboard documentation suggests running `openclaw doctor --generate-gateway-token`.

## SSH connection failed: "Connection refused"

```
ssh: connect to host 192.168.45.139 port 8022: Connection refused
```

### Cause

The Termux SSH server (`sshd`) is not running. Closing the Termux app or rebooting the phone stops sshd.

### Solution

Open the Termux app on the phone and run `sshd`. Either type directly on the phone or send via adb:

```bash
adb shell input text 'sshd'
```
```bash
adb shell input keyevent 66
```

The IP address may have changed, so verify:

```bash
adb shell input text 'ifconfig'
```
```bash
adb shell input keyevent 66
```

> To start sshd automatically, add `sshd 2>/dev/null` to the end of your `~/.bashrc` file so the SSH server starts whenever Termux opens.

## `openclaw --version` fails

### Cause

Environment variables are not loaded.

### Solution

```bash
source ~/.bashrc
```

Or fully close and reopen the Termux app.

## "Cannot find module glibc-compat.js" error

```
Error: Cannot find module '/data/data/com.termux/files/home/.openclaw-lite/patches/glibc-compat.js'
```

> **Note**: This issue only affects pre-1.0.0 (Bionic) installations. In v1.0.0+ (glibc), `glibc-compat.js` is loaded by the node wrapper script, not `NODE_OPTIONS`.

### Cause

The `NODE_OPTIONS` environment variable in `~/.bashrc` still references the old installation path (`.openclaw-lite`). This happens when updating from an older version where the project was named "OpenClaw Lite".

### Solution

Run the updater to refresh the environment variable block:

```bash
oa --update && source ~/.bashrc
```

Or manually fix it:

```bash
sed -i 's/\.openclaw-lite/\.openclaw-android/g' ~/.bashrc && source ~/.bashrc
```

## "systemctl --user unavailable: spawn systemctl ENOENT" during update

_Applies to installs before v1.1.0 — `openclaw update` is now blocked; use `oa --update`._

```
Gateway service check failed: Error: systemctl --user unavailable: spawn systemctl ENOENT
```

### Cause

After running `openclaw update`, OpenClaw tries to restart the gateway service using `systemctl`. Since Termux doesn't have systemd, the `systemctl` binary doesn't exist and the command fails with `ENOENT`.

### Impact

**This error is harmless.** The update itself has already completed successfully — only the automatic service restart failed. Your OpenClaw installation is up to date.

### Solution

Simply start the gateway manually:

```bash
openclaw gateway
```

If the gateway was already running before the update, you may need to stop the old process first. See the [Gateway won't start](#gateway-wont-start-gateway-already-running-or-port-is-already-in-use) section above.

## `openclaw update` says it is blocked

```
[BLOCKED] OpenClaw is pinned to the version verified by OpenClaw on Android.
          Run 'oa --update' to update safely. ('openclaw update status' is allowed.)
```

### Cause

This project pins OpenClaw and Node.js to a verified, tested pair (see `platforms/openclaw/config.env`). `openclaw update` (and `openclaw --update`) would install the latest npm release, which can require a newer Node.js than the pinned one and then fail to start — so a guard installed at `$PREFIX/bin/openclaw` blocks both. The gateway's own auto-updater is also disabled (the Node.js wrapper sets `OPENCLAW_NO_AUTO_UPDATE=1` by default), but it may still print something like `update available … Run: openclaw update` — that's the command being blocked.

### Solution

Use `oa --update` instead — it updates Node.js and OpenClaw together to the verified, pinned versions:

```bash
oa --update && source ~/.bashrc
```

`openclaw update status` (read-only) still works and is not blocked.

## The dashboard's Update button, or an `openclaw update` command OpenClaw suggests, shows [BLOCKED]

```
[BLOCKED] OpenClaw is pinned to the version verified by OpenClaw on Android.
          Run 'oa --update' to update safely. ('openclaw update status' is allowed.)
```

### Cause

OpenClaw 2026.9.8 can start its own update from the dashboard's Update button, from the agent's gateway tool, and from `openclaw gateway call update.run`. These paths do not go through the `openclaw` command guard, so the Node.js wrapper blocks OpenClaw's own update as well. Some OpenClaw messages also suggest `openclaw update --yes` or `openclaw update repair`; these commands are blocked too. This is intended: the pinned Node.js and OpenClaw versions are verified as a pair.

### Solution

Do not use the Update button or those commands. Update with:

```bash
oa --update && source ~/.bashrc
```

`openclaw update status` (read-only) is allowed.

## "Bad system call" (SIGSYS) when OpenClaw runs

```
Bad system call
```

### Cause

OpenClaw 2026.9.x's file-safety module (`@openclaw/fs-safe`) has a native helper that uses the `openat2` system call. Android's app seccomp policy kills the process with SIGSYS (Bad system call) on that call. The Node.js wrapper therefore sets `FS_SAFE_TEST_NO_OPENAT2=1` by default, which keeps the native helper but stops it from using `openat2`. Only the exact value `1` is recognized.

### Solution

Normally nothing needs to be done. If you still see this error, check that the value is exactly `1`:

```bash
echo "$FS_SAFE_TEST_NO_OPENAT2"
```

If it prints something else, set it to `1`. As a last resort you can turn off the native helper itself:

```bash
export FS_SAFE_NATIVE_MODE=off
```

The cost: with the native helper off, OpenClaw may refuse some of its data migrations.

## `oa --update` stops with "The OpenClaw gateway is running"

```
[FAIL] The OpenClaw gateway is running.
       This update replaces OpenClaw and Node.js, which a running gateway cannot follow.
```

### Cause

This update changes the pinned Node.js or OpenClaw version. A running gateway cannot follow the replacement, so `oa --update` stops without changing anything.

### Solution

In the Claw app, open Settings → Install & Update and tap Stop gateway. The app can stop only a gateway that was started in its own terminal; a gateway detached with `nohup`, `tmux` or `setsid` cannot be stopped from the app, so stop it with `kill <PID>` in a terminal. Alternatively, press Ctrl+C in the terminal tab where the gateway runs. As a last resort, use Android Settings > Apps > Claw > Force stop. Swiping the app away from the recent apps list does not stop the gateway, because the app keeps a foreground service.

The app's own Update button on that screen also stops a gateway it started before it updates. If the app stopped the gateway for the update, start it again from the Dashboard afterwards.

Stop the gateway (press Ctrl+C in the terminal where it runs), then run the update again:

```bash
oa --update && source ~/.bashrc
```

If you cannot find where the gateway is running, see [Gateway won't start](#gateway-wont-start-gateway-already-running-or-port-is-already-in-use).

The check looks for a process whose command line matches `openclaw.*gateway`, so a command such as `tail -f …gateway.log` can also trigger it. If you are sure no gateway is running, run `OA_SKIP_GATEWAY_CHECK=1 oa --update`. The app cannot pass environment variables to its own update, so in the Claw app use the Open in terminal option on the Install & Update screen and run `OA_SKIP_GATEWAY_CHECK=1 oa --update` there.

## `oa --update` stops with "Another update, setup or tools run is in progress"

```
[FAIL] Another update, setup or tools run is in progress. Try again when it has finished.
```

A new install prints `Another setup, update or tools run is in progress. Try again when it has finished.`, and a tool install from the Claw app prints `Another tools run is in progress. Try again when it has finished.` The exit code is 2 in all three cases, and the installation was not changed.

### Cause

A new install, `oa --update` and a tool install from the Claw app run one at a time. They share one lock, the folder `~/.openclaw-android/.tools.lock`, and a run that finds it held by another run stops right away.

### Solution

Wait until the other run has finished (in a terminal or in the Claw app), then run the command again.

If no other run exists (for example, a run was killed), you usually need to do nothing: the lock holds the process ID of its owner in the file `pid`, and a new run takes the lock over automatically when that process is gone. A lock without a `pid` file (its owner stopped while creating it) is taken over after about one minute. If the message still appears, wait about a minute and run the command again.

As a last resort, remove the lock folder. Do this only when you are sure that no other setup, update or tool install is running, in a terminal or in the Claw app:

```bash
rm -rf ~/.openclaw-android/.tools.lock
```

## "state database schema migration required", or the gateway does not start after an update

```
state database schema migration required
```

### Cause

OpenClaw 2026.9.8 may need to migrate the data of an older OpenClaw version. When `oa --update` finds that a migration is needed, it runs `openclaw doctor --fix` once automatically, but only if that run created a backup in `~/.openclaw-android/backup/pre-update/`. If the migration fails, is skipped (`OA_SKIP_AUTO_DOCTOR=1`), or the backup was skipped, the data stays as it was and the gateway may refuse to start.

### Solution

Stop the gateway first, then run the migration by hand and start the gateway again:

```bash
openclaw doctor --fix
openclaw gateway
```

If something went wrong, `oa --restore` lists the `pre-update/` backup (your data as it was before the update). It restores data only, not the programs.

If `oa --update` ran the migration itself and it failed, the full output of `openclaw doctor --fix` is saved in `~/.openclaw-android/doctor-fix.log`.

If `oa --update` finishes with the following line, the data check was skipped, not failed: the gateway is using the OpenClaw state, so OpenClaw's own check could not look at it. The update itself finished. Stop the gateway, then run `openclaw doctor`. In the Claw app, you can stop a gateway started in the app's terminal with Settings → Install & Update → Stop gateway.

```
[WARN] The gateway is using the OpenClaw state, so the data check was skipped. Stop the gateway, then run: openclaw doctor
```

## `Hard-link patch: not complete`, `linkat … Permission denied`, or `FICLONE: Permission denied`

```
Hard-link patch: not complete (…)
```

### Cause

Android blocks hardlinks and reflink (`FICLONE`) copies in the app data area, in the app and in Termux alike; they fail with `EACCES`. OpenClaw 2026.9.8 tries a hardlink first and falls back to copying only on a "not supported" error, which `EACCES` is not, and it moves the original chat history to its archive folder with a hardlink only. OpenClaw on Android therefore patches OpenClaw (`platforms/openclaw/patches/openclaw-patch-hardlink.sh`) every time it installs or updates it. `oa --status` shows `Hard-link patch: applied (…)` when the patch is in place and `not complete (…)` when it is not, for example after OpenClaw was reinstalled without the patch.

### Solution

Run the update again, or reinstall the pinned OpenClaw version, which applies the patch again:

```bash
oa --update && source ~/.bashrc
# or
npm install -g openclaw@2026.9.8
```

Then check `oa --status`. If the line still says `not complete`, or an automatic data migration failed, read `~/.openclaw-android/doctor-fix.log` (the full output of `openclaw doctor --fix`) and report it together with the output of `oa --status`.

## `oa --update` stops with "Your OpenClaw has saved chat history, and the patch … does not fit"

```
[FAIL] Your OpenClaw has saved chat history, and the patch for moving chat history does not fit OpenClaw 2026.9.8.
```

### Cause

You are updating from OpenClaw 2026.7.35 and have saved chat history, which the new OpenClaw must move into its database. That move needs the hard-link patch above. Before it changes anything, `oa --update` checks that the patch fits the OpenClaw version it is about to install. If it does not fit, the update would leave OpenClaw unable to start, so it stops.

### Impact

Nothing was changed. Node.js and OpenClaw stay as they were (Node.js 22 and OpenClaw 7.35), and your data is untouched.

### Solution

Keep the current state and wait for the next release of OpenClaw on Android, then run `oa --update` again. The message can also appear when the update scripts come from an old cached copy; running `oa --update` again after a few minutes fetches a fresh copy. If it keeps stopping, report it with the full message.

## The update was interrupted during a data migration

### Cause

The update, or the data migration inside it, ended before it finished (for example, the app was force-stopped or the device ran out of power in the middle). Your data is still there, but the migration is incomplete. Running the same `oa --update` again does not start the automatic migration again, because that run creates no new backup and the automatic migration runs only when it made the backup itself.

### Solution

Stop the gateway first, then finish the migration by hand and start the gateway again:

```bash
openclaw doctor --fix
openclaw gateway
```

If something went wrong, `oa --restore` lists the `pre-update/` backup (your data as it was before the update).

## `EACCES: permission denied, realpath '/data/data/<other package>/...'`, or "... point into another app's folder"

```
EACCES: permission denied, realpath '/data/data/<other package>/files/home/.openclaw/...'
```

```
[WARN] N path(s) point into another app's folder and could not be repaired automatically
```

### Cause

After data is moved between the Claw app and Termux, between a debug and a release app, or restored from a backup made on another device, OpenClaw's data can still contain the home path of the other app (`/data/data/<other package>/files/home/...`). Android answers an access to another app's folder with a permission error (EACCES), and the data migration of OpenClaw 2026.9 stops there.

`oa --update` (just before the configuration check) and every `oa --restore` (a restore on the same environment included) now repair these paths: the agent database registrations and stale lease rows in OpenClaw's state database, and the agent `workspace` and `agentDir` values in `openclaw.json` (also for several agents). When something was repaired, the output says `Repaired N path(s) …` (during a restore, `Updated N path(s) …`). If a value cannot be repaired automatically, the warning above is shown.

### Solution

If an earlier update stopped because of this problem, run the update again, then stop the gateway and run the migration by hand:

```bash
oa --update && source ~/.bashrc
openclaw doctor --fix
```

If the warning `Could not fix the folder paths in your OpenClaw config … Nothing was changed.` appears, edit the path values in `~/.openclaw/openclaw.json` that name the other app's folder (the agent `workspace` and `agentDir`) so that they point to a location under your current home (`$HOME`), then run `openclaw doctor --fix` again.

The warning `point into another app's folder and could not be repaired automatically` refers to entries in OpenClaw's state database that do not have the expected shape. If `openclaw doctor --fix` still stops with `EACCES … realpath` afterwards, please [open an issue](https://github.com/AidanPark/openclaw-android/issues) with the output of `oa --status` and `~/.openclaw-android/doctor-fix.log`; you can return to your data from before the update with `oa --restore` (the `pre-update/` backup).

Before changing anything, the repair saves copies of the files it edits (the latest 3 of each are kept):

- `~/.openclaw/state/openclaw.sqlite.oa-before-repair-<timestamp>`
- `~/.openclaw/openclaw.json.oa-before-repair-<timestamp>`

## `The updater downloaded an older copy of itself (cache)`

```
[FAIL] The updater downloaded an older copy of itself (cache). Nothing was changed.
       Run 'oa --update' again in a few minutes.
```

### Cause

For a few minutes after a new release, a cache can still serve an old copy of the update script. The updater checks that its own script is the latest; when it finds an old copy, it stops before it changes Node.js or OpenClaw.

### Impact

Nothing was changed. Node.js, OpenClaw, and your data stay as they were.

### Solution

Run `oa --update` again after a few minutes, which fetches a fresh copy:

```bash
oa --update && source ~/.bashrc
```

The environment variables `OA_ALLOW_UNMARKED_NODE_CHANGE=1` and `OA_ALLOW_UNMARKED_OPENCLAW_CHANGE=1` turn this check off for developers. Do not use them in normal use.

## "Not enough free storage"

```
[FAIL] Not enough free storage to ...: 2000 MB needed, ... MB available.
       Nothing was changed.
```

### Cause

A new install, and an update that changes the pinned versions, both need 2000 MB of free space. If there is less, the script stops before changing anything and shows the space needed and the space left.

### Solution

Free some space (for example, clear other apps' caches, delete unused files, or remove `~/.npm/_cacache`) and run the command again. Nothing was changed, so it is safe to retry.

When the storage is completely full, `oa --update` can stop earlier with `[FAIL] Could not create the run lock in … (is the storage full?)`. The cause and the solution are the same.

## OpenClaw's desktop automation tool does not work on Android 10 or lower

### Cause

OpenClaw's desktop automation tool (`@trycua/cua-driver`) may not work on Android 10 or lower (API 29 or lower), because some of the system calls it needs are blocked there.

### Solution

This is a known limitation of this tool on those Android versions.

## sharp build fails during `openclaw update`

_Applies to installs before v1.1.0 — `openclaw update` is now blocked; use `oa --update`._

```
npm error gyp ERR! not ok
Update Result: ERROR
Reason: global update
```

### Cause

The OpenClaw version this project pins does not depend on `sharp` — image handling goes through `photon` instead, so this build should not run at all through the normal install/update flow. If you still see it, you likely ran `npm install`/`npm rebuild sharp` by hand, or you're on an older, unpinned OpenClaw release that still declares `sharp` as a dependency.

### Impact

**This error is non-critical.** OpenClaw itself works normally without `sharp` — at most, whatever manual step triggered the rebuild failed.

### Solution

`oa --update` no longer installs `libvips` or rebuilds `sharp` — it only keeps Node.js and OpenClaw at the pinned, verified versions, and the pinned OpenClaw doesn't need `sharp`:

```bash
oa --update && source ~/.bashrc
```

## `clawdhub` fails with "Cannot find package 'undici'"

```
Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'undici' imported from /data/data/com.termux/files/usr/lib/node_modules/clawdhub/dist/http.js
```

### Cause

Node.js v24+ on Termux doesn't bundle the `undici` package, which `clawdhub` depends on for HTTP requests.

### Solution

Run the updater to automatically install `clawdhub` and its `undici` dependency:

```bash
oa --update && source ~/.bashrc
```

Or fix it manually:

```bash
cd $(npm root -g)/clawdhub && npm install undici
```

## "not supported on android" error

```
Gateway status failed: Error: Gateway service install not supported on android
```

> **Note**: This issue only affects pre-1.0.0 (Bionic) installations. In v1.0.0+ (glibc), Node.js natively reports `process.platform` as `'linux'`, so this error does not occur.

### Cause

**Pre-1.0.0 (Bionic)**: The `process.platform` override in `glibc-compat.js` is not being applied because `NODE_OPTIONS` is not set.

### Solution

Check which Node.js is being used:

```bash
node -e "console.log(process.platform)"
```

If it prints `android`, the glibc node wrapper is not being used. Load the environment:

```bash
source ~/.bashrc
```

If it still prints `android`, update to the latest version (v1.0.0+ uses glibc and resolves this permanently):

```bash
oa --update && source ~/.bashrc
```

## `openclaw update` fails with node-llama-cpp build error

_Applies to installs before v1.1.0 — `openclaw update` is now blocked; use `oa --update`._

```
[node-llama-cpp] Cloning ggml-org/llama.cpp (local bundle)
npm error 48%
Update Result: ERROR
```

### Cause

When OpenClaw updates via npm, `node-llama-cpp`'s postinstall script attempts to clone and compile `llama.cpp` from source. This fails on Termux because the build toolchain (`cmake`, `clang`) is linked against Bionic, while Node.js runs under glibc — the two are incompatible for native compilation.

### Impact

**This error is harmless.** The prebuilt `node-llama-cpp` binaries (`@node-llama-cpp/linux-arm64`) are already installed and work correctly under the glibc environment. The failed source build does not overwrite them.

Node-llama-cpp is used for optional local embeddings. If the prebuilt binaries don't load, OpenClaw automatically falls back to remote embedding providers (OpenAI, Gemini, etc.).

### Solution

No action needed. The error can be safely ignored. To verify that the prebuilt binaries are working:

```bash
node -e "require('$(npm root -g)/openclaw/node_modules/@node-llama-cpp/linux-arm64/bins/linux-arm64/llama-addon.node'); console.log('OK')"
```

## OpenCode install shows EACCES permission errors

```
EACCES: Permission denied while installing opencode-ai
Failed to install 118 packages
```

### Cause

Bun attempts to create hardlinks and symlinks when installing packages. Android's filesystem restricts these operations, causing `EACCES` errors for dependency packages.

### Impact

**These errors are harmless.** The main binary (`opencode`) is installed correctly despite the dependency link failures. The ld.so concatenation and proot wrapper handle execution.

### Solution

No action needed. Verify that OpenCode works:

```bash
opencode --version
```
