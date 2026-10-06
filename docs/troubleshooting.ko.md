# 트러블슈팅

Termux에서 OpenClaw 사용 중 발생할 수 있는 문제와 해결 방법을 정리합니다.

## 3단계 뒤 curl 이 깨짐 (CANNOT LINK EXECUTABLE)

```
CANNOT LINK EXECUTABLE "curl": cannot locate symbol "SSL_set_quic_tls_early_data_enabled" referenced by ".../usr/lib/libcurl.so"
```

### 원인

새로 설치한 Termux 에서 전체 업그레이드 없이 `pkg install curl` 만 하면, Termux 앱에 들어 있던 옛 OpenSSL 위에 새 libcurl 이 설치됩니다. 그러면 curl 이 실행되지 않아 4단계 설치 명령이 아무 일도 하지 않고 끝나고, pkg 도 curl 을 쓰기 때문에 `pkg upgrade` 까지 실패합니다.

### 해결 방법

```bash
apt update && apt full-upgrade -y
```

설정 파일에 대한 질문(`(Y/I/N/O/D/Z) [default=N] ?`)이 나오면 **Enter** 를 눌러 기본값으로 진행하세요. 끝나면 `curl --version` 이 동작합니다 — 4단계부터 이어서 진행하세요.

## 게이트웨이가 시작되지 않음: "gateway already running" 또는 "Port is already in use"

```
Gateway failed to start: gateway already running (pid XXXXX); lock timeout after 5000ms
Port 18789 is already in use.
```

### 원인

이전 게이트웨이 프로세스가 비정상 종료되면서 잠금 파일이 남아있거나, 프로세스가 좀비 상태로 남아있는 경우 발생합니다. 주로 다음 상황에서 일어납니다:

- SSH 연결이 끊어지면서 게이트웨이 프로세스가 고아(orphan) 상태로 남음
- `Ctrl+Z`(일시정지)로 중단한 경우 프로세스가 종료되지 않고 백그라운드에 남음
- Termux가 Android에 의해 강제 종료된 경우

> **참고**: 게이트웨이를 종료할 때는 반드시 `Ctrl+C`를 사용하세요. `Ctrl+Z`는 프로세스를 일시정지시킬 뿐 종료하지 않습니다.

### 해결 방법

#### 1단계: 남아있는 프로세스 확인 및 종료

```bash
ps aux | grep -E "node|openclaw" | grep -v grep
```

프로세스가 보이면 PID를 확인하고 종료:

```bash
kill -9 <PID>
```

#### 2단계: 잠금 파일 삭제

```bash
rm -rf $PREFIX/tmp/openclaw-*
```

#### 3단계: 게이트웨이 재시작

```bash
openclaw gateway
```

### 그래도 안 되면

위 과정으로도 해결되지 않으면 Termux 앱을 완전히 종료했다가 다시 열고 `openclaw gateway`를 실행하세요. 폰을 재시작하면 확실하게 모든 상태가 초기화됩니다.

## 게이트웨이 연결 끊김: "gateway not connected"

```
send failed: Error: gateway not connected
disconnected | error
```

### 원인

게이트웨이 프로세스가 종료되었거나 SSH 세션이 끊어진 경우 발생합니다.

### 해결 방법

게이트웨이를 실행했던 SSH 세션을 확인하세요. 세션이 끊어졌다면 다시 SSH 접속 후 게이트웨이를 시작합니다:

```bash
openclaw gateway
```

"gateway already running" 에러가 나오면 위의 [게이트웨이가 시작되지 않음](#게이트웨이가-시작되지-않음-gateway-already-running-또는-port-is-already-in-use) 섹션을 참고하세요.

## 대시보드가 토큰을 요구하거나 "unauthorized"가 나옴

```
unauthorized
```

### 원인

`http://127.0.0.1:18789/` 대시보드는 게이트웨이 토큰으로 로그인합니다. OpenClaw는 이 토큰을 기본으로 보여 주지 않습니다. `openclaw config get gateway.auth.token`은 비밀값을 가려서(`__OPENCLAW_REDACTED__`) 출력하고, `openclaw dashboard --no-open`은 토큰이 없는 링크만 출력합니다.

### 해결 방법

설정 파일에서 토큰을 출력합니다(OpenClaw가 일반 JSON으로 저장합니다):

```bash
node -p "require(process.env.HOME + '/.openclaw/openclaw.json').gateway.auth.token"
```

출력된 토큰을 대시보드의 인증 입력란에 붙여 넣거나, 아래 주소로 여세요(`<토큰>`을 바꿔 넣음):

```
http://127.0.0.1:18789/#token=<토큰>
```

토큰은 공개하지 마세요. 토큰을 가진 사람은 누구나 OpenClaw를 제어할 수 있습니다.

명령이 실패하면 파일을 직접 편집했을 가능성이 큽니다(주석은 유효한 JSON이 아닙니다). `~/.openclaw/openclaw.json`을 편집기로 열어 `gateway.auth.token` 값을 복사하세요. `gateway.auth.token`이 아예 없으면 OpenClaw 대시보드 문서는 `openclaw doctor --generate-gateway-token` 실행을 안내합니다.

## SSH 접속 실패: "Connection refused"

```
ssh: connect to host 192.168.45.139 port 8022: Connection refused
```

### 원인

Termux의 SSH 서버(`sshd`)가 실행되지 않은 상태입니다. Termux 앱을 종료하거나 폰을 재시작하면 sshd가 꺼집니다.

### 해결 방법

폰에서 Termux 앱을 열고 `sshd`를 실행하세요. 폰에서 직접 타이핑하거나 adb로 전송:

```bash
adb shell input text 'sshd'
```
```bash
adb shell input keyevent 66
```

IP가 변경되었을 수 있으니 확인:

```bash
adb shell input text 'ifconfig'
```
```bash
adb shell input keyevent 66
```

> 매번 수동으로 `sshd`를 실행하기 번거로우면 `~/.bashrc` 맨 아래에 `sshd 2>/dev/null`을 추가하면 Termux 시작 시 자동으로 SSH 서버가 켜집니다.

## `openclaw --version` 실패

### 원인

환경변수가 로드되지 않은 상태입니다.

### 해결 방법

```bash
source ~/.bashrc
```

또는 Termux 앱을 완전히 종료했다가 다시 여세요.

## "Cannot find module glibc-compat.js" 에러

```
Error: Cannot find module '/data/data/com.termux/files/home/.openclaw-lite/patches/glibc-compat.js'
```

> **참고**: 이 문제는 v1.0.0 이전(Bionic) 설치에서만 발생합니다. v1.0.0+(glibc)에서는 `glibc-compat.js`가 node 래퍼 스크립트에 의해 로딩되므로 `NODE_OPTIONS`를 사용하지 않습니다.

### 원인

`~/.bashrc`의 `NODE_OPTIONS` 환경변수가 이전 설치 경로(`.openclaw-lite`)를 참조하고 있습니다. 프로젝트명이 "OpenClaw Lite"였던 이전 버전에서 업데이트한 경우 발생합니다.

### 해결 방법

업데이터를 실행하면 환경변수 블록이 갱신됩니다:

```bash
oa --update && source ~/.bashrc
```

또는 수동으로 수정:

```bash
sed -i 's/\.openclaw-lite/\.openclaw-android/g' ~/.bashrc && source ~/.bashrc
```

## 업데이트 중 "systemctl --user unavailable: spawn systemctl ENOENT" 에러

_v1.1.0 이전 설치에 해당합니다 — 이제 `openclaw update` 는 막혀 있으니 `oa --update` 를 쓰세요._

```
Gateway service check failed: Error: systemctl --user unavailable: spawn systemctl ENOENT
```

### 원인

`openclaw update` 실행 후, OpenClaw이 `systemctl`로 게이트웨이 서비스를 재시작하려고 합니다. Termux에는 systemd가 없으므로 `systemctl` 바이너리를 찾을 수 없어 `ENOENT` 에러가 발생합니다.

### 영향

**이 에러는 무해합니다.** 업데이트 자체는 이미 성공적으로 완료되었으며, 자동 서비스 재시작만 실패한 것입니다. OpenClaw은 최신 상태로 업데이트되어 있습니다.

### 해결 방법

수동으로 게이트웨이를 시작하면 됩니다:

```bash
openclaw gateway
```

업데이트 전에 게이트웨이가 실행 중이었다면 기존 프로세스를 먼저 종료해야 할 수 있습니다. 위의 [게이트웨이가 시작되지 않음](#게이트웨이가-시작되지-않음-gateway-already-running-또는-port-is-already-in-use) 섹션을 참고하세요.

## `openclaw update`가 차단되었다고 나옴

```
[BLOCKED] OpenClaw is pinned to the version verified by OpenClaw on Android.
          Run 'oa --update' to update safely. ('openclaw update status' is allowed.)
```

### 원인

이 프로젝트는 검증되고 테스트된 OpenClaw + Node.js 버전 조합을 고정(pin)합니다(`platforms/openclaw/config.env` 참고). `openclaw update`(및 `openclaw --update`)는 최신 npm 릴리스를 설치하는데, 이는 고정된 버전보다 더 최신 Node.js를 필요로 하여 실행이 안 될 수 있습니다 — 그래서 `$PREFIX/bin/openclaw`에 설치된 가드가 두 명령을 모두 차단합니다. 게이트웨이 자체의 자동 업데이트 기능도 비활성화되어 있지만(Node.js 래퍼가 `OPENCLAW_NO_AUTO_UPDATE=1` 을 기본으로 설정), `update available … Run: openclaw update` 같은 메시지는 계속 출력될 수 있습니다 — 바로 그 명령이 차단 대상입니다.

### 해결 방법

대신 `oa --update`를 사용하세요 — Node.js와 OpenClaw을 함께 검증된 고정 버전으로 업데이트합니다:

```bash
oa --update && source ~/.bashrc
```

읽기 전용인 `openclaw update status`는 차단되지 않고 계속 동작합니다.

## 대시보드의 Update 버튼이나 OpenClaw 가 권하는 `openclaw update` 명령이 [BLOCKED] 로 나옴

```
[BLOCKED] OpenClaw is pinned to the version verified by OpenClaw on Android.
          Run 'oa --update' to update safely. ('openclaw update status' is allowed.)
```

### 원인

OpenClaw 2026.9.8 은 대시보드의 Update 버튼, 에이전트의 gateway 도구, `openclaw gateway call update.run` 으로 자체 업데이트를 시작할 수 있습니다. 이 경로는 `openclaw` 명령 가드를 거치지 않으므로 Node.js 래퍼가 OpenClaw 의 자체 업데이트도 차단합니다. OpenClaw 의 일부 메시지는 `openclaw update --yes` 나 `openclaw update repair` 를 권하는데, 이 명령도 차단되어 있습니다. 고정된 Node.js 와 OpenClaw 버전은 한 쌍으로 검증된 조합이므로 의도된 동작입니다.

### 해결 방법

Update 버튼이나 위의 명령을 사용하지 말고 다음으로 업데이트하세요:

```bash
oa --update && source ~/.bashrc
```

읽기 전용인 `openclaw update status` 는 허용됩니다.

## OpenClaw 실행 중 "Bad system call" (SIGSYS)

```
Bad system call
```

### 원인

OpenClaw 2026.9.x 의 파일 안전 모듈(`@openclaw/fs-safe`)은 네이티브 도우미에서 `openat2` 시스템 콜을 사용합니다. Android 의 앱 seccomp 정책이 이 호출을 SIGSYS(Bad system call)로 종료시킵니다. 그래서 Node.js 래퍼는 기본으로 `FS_SAFE_TEST_NO_OPENAT2=1` 을 설정하여, 네이티브 도우미는 유지하되 `openat2` 를 사용하지 않게 합니다. 값이 정확히 `1` 이어야 인식됩니다.

### 해결 방법

보통은 조치가 필요 없습니다. 그래도 이 오류가 나오면 값이 정확히 `1` 인지 확인하세요:

```bash
echo "$FS_SAFE_TEST_NO_OPENAT2"
```

다른 값이 출력되면 `1` 로 설정하세요. 최후의 수단으로 네이티브 도우미 자체를 끌 수 있습니다:

```bash
export FS_SAFE_NATIVE_MODE=off
```

대가: 네이티브 도우미를 끄면 OpenClaw 의 일부 데이터 이전이 거부될 수 있습니다.

## `oa --update` 가 "The OpenClaw gateway is running" 으로 멈춤

```
[FAIL] The OpenClaw gateway is running.
       This update replaces OpenClaw and Node.js, which a running gateway cannot follow.
```

### 원인

이번 업데이트는 고정된 Node.js 나 OpenClaw 버전을 바꿉니다. 실행 중인 게이트웨이는 교체를 따라갈 수 없으므로, `oa --update` 는 아무것도 바꾸지 않고 멈춥니다.

### 해결 방법

Claw 앱에서는 게이트웨이가 실행 중인 터미널 탭에서 Ctrl+C 를 누르거나, Android 설정 > 앱 > Claw > 강제 종료를 사용하세요. 최근 앱 목록에서 앱을 밀어 닫아도 앱이 포그라운드 서비스를 유지하므로 게이트웨이는 멈추지 않습니다.

게이트웨이를 멈추고(실행 중인 터미널에서 Ctrl+C) 업데이트를 다시 실행하세요:

```bash
oa --update && source ~/.bashrc
```

게이트웨이가 어디에서 실행 중인지 찾을 수 없으면 [게이트웨이가 시작되지 않음](#게이트웨이가-시작되지-않음-gateway-already-running-또는-port-is-already-in-use) 섹션을 참고하세요.

이 검사는 명령줄이 `openclaw.*gateway` 와 맞는 프로세스를 찾으므로 `tail -f …gateway.log` 같은 명령에도 걸릴 수 있습니다. 게이트웨이가 실행 중이 아닌 것이 확실하면 `OA_SKIP_GATEWAY_CHECK=1 oa --update` 로 실행하세요.

## `oa --update` 가 "Another update, setup or tools run is in progress" 로 멈춤

```
[FAIL] Another update, setup or tools run is in progress. Try again when it has finished.
```

새 설치는 `Another setup, update or tools run is in progress. Try again when it has finished.` 를, Claw 앱의 도구 설치는 `Another tools run is in progress. Try again when it has finished.` 를 출력합니다. 세 경우 모두 종료 코드는 2 이며, 설치 상태는 바뀌지 않았습니다.

### 원인

새 설치, `oa --update`, Claw 앱의 도구 설치는 한 번에 하나만 실행됩니다. 이 실행들은 폴더 `~/.openclaw-android/.tools.lock` 이라는 잠금 하나를 공유하며, 다른 실행이 이 잠금을 쥐고 있으면 즉시 멈춥니다.

### 해결 방법

터미널이나 Claw 앱에서 진행 중인 다른 실행이 끝날 때까지 기다린 뒤 명령을 다시 실행하세요.

다른 실행이 없는데도 이 메시지가 나오면(예: 실행이 강제 종료된 경우) 대개 아무것도 할 필요가 없습니다. 잠금은 소유자의 프로세스 ID 를 `pid` 파일에 기록하며, 그 프로세스가 사라졌으면 새 실행이 잠금을 자동으로 넘겨받습니다. `pid` 파일이 없는 잠금(만드는 도중 소유자가 멈춘 경우)은 약 1분 뒤에 넘겨받습니다. 그래도 이 메시지가 계속 나오면 약 1분 기다린 뒤 명령을 다시 실행하세요.

최후의 수단으로 잠금 폴더를 삭제할 수 있습니다. 터미널에서든 Claw 앱에서든 다른 설치·업데이트·도구 설치가 실행 중이지 않다고 확신할 때만 삭제하세요:

```bash
rm -rf ~/.openclaw-android/.tools.lock
```

## "state database schema migration required" 또는 업데이트 뒤 게이트웨이가 시작되지 않음

```
state database schema migration required
```

### 원인

OpenClaw 2026.9.8 은 이전 OpenClaw 버전의 데이터를 이전해야 할 수 있습니다. `oa --update` 는 데이터 이전이 필요하다고 판단하면 `openclaw doctor --fix` 를 한 번 자동으로 실행하지만, 이번 실행에서 `~/.openclaw-android/backup/pre-update/` 에 백업을 만들었을 때만 실행합니다. 이전이 실패했거나, 자동 이전을 껐거나(`OA_SKIP_AUTO_DOCTOR=1`), 백업을 건너뛴 경우에는 데이터가 그대로 남아 게이트웨이가 시작을 거부할 수 있습니다.

### 해결 방법

먼저 게이트웨이를 멈춘 뒤 이전을 직접 실행하고 게이트웨이를 다시 시작하세요:

```bash
openclaw doctor --fix
openclaw gateway
```

문제가 생기면 `oa --restore` 의 목록에서 `pre-update/` 백업(업데이트 전의 데이터)을 선택할 수 있습니다. 복구는 데이터만 되돌리고 프로그램은 되돌리지 않습니다.

`oa --update` 가 이전을 직접 실행했다가 실패한 경우, `openclaw doctor --fix` 의 전체 출력이 `~/.openclaw-android/doctor-fix.log` 에 저장됩니다.

`oa --update` 가 다음 줄과 함께 끝나면 데이터 확인은 실패한 것이 아니라 건너뛴 것입니다. 게이트웨이가 OpenClaw 상태를 사용 중이어서 OpenClaw 자체의 확인이 그 상태를 볼 수 없었습니다. 업데이트 자체는 끝났습니다. 게이트웨이를 멈춘 뒤 `openclaw doctor` 를 실행하세요.

```
[WARN] The gateway is using the OpenClaw state, so the data check was skipped. Stop the gateway, then run: openclaw doctor
```

## `Hard-link patch: not complete`, `linkat … Permission denied` 또는 `FICLONE: Permission denied`

```
Hard-link patch: not complete (…)
```

### 원인

Android 는 앱 데이터 영역에서 하드링크와 reflink(`FICLONE`) 복사를 막습니다(앱과 Termux 모두). 이 호출은 `EACCES` 로 실패합니다. OpenClaw 2026.9.8 은 하드링크를 먼저 시도하고 「지원 안 함」 오류일 때만 복사로 넘어가는데, `EACCES` 는 지원 안 함으로 보지 않습니다. 또 대화 기록의 원본을 보관 폴더로 옮길 때는 하드링크만 사용합니다. 그래서 OpenClaw on Android 는 OpenClaw 를 설치하거나 업데이트할 때마다 OpenClaw 를 패치합니다(`platforms/openclaw/patches/openclaw-patch-hardlink.sh`). `oa --status` 는 패치가 적용되어 있으면 `Hard-link patch: applied (…)`, 그렇지 않으면 `not complete (…)` 를 표시합니다. 패치 없이 OpenClaw 를 다시 설치한 경우가 그 예입니다.

### 해결 방법

업데이트를 다시 실행하거나 고정 버전의 OpenClaw 를 다시 설치하면 패치가 다시 적용됩니다:

```bash
oa --update && source ~/.bashrc
# 또는
npm install -g openclaw@2026.9.8
```

그 뒤 `oa --status` 를 확인하세요. 줄이 계속 `not complete` 이거나 자동 데이터 이전이 실패했다면 `~/.openclaw-android/doctor-fix.log`(`openclaw doctor --fix` 의 전체 출력)를 읽고, `oa --status` 의 출력과 함께 보고하세요.

## `oa --update` 가 "Your OpenClaw has saved chat history, and the patch … does not fit" 으로 멈춤

```
[FAIL] Your OpenClaw has saved chat history, and the patch for moving chat history does not fit OpenClaw 2026.9.8.
```

### 원인

OpenClaw 2026.7.35 에서 업데이트하는 중이고 저장된 대화 기록이 있습니다. 새 OpenClaw 는 이 기록을 자체 데이터베이스로 옮겨야 하며, 이 이전에는 위의 하드링크 패치가 필요합니다. `oa --update` 는 아무것도 바꾸기 전에 이 패치가 설치할 OpenClaw 버전에 맞는지 확인합니다. 맞지 않으면 업데이트 뒤 OpenClaw 가 시작되지 않으므로 멈춥니다.

### 영향

아무것도 바뀌지 않았습니다. Node.js 와 OpenClaw 는 그대로(Node.js 22 와 OpenClaw 7.35)이고 데이터도 그대로입니다.

### 해결 방법

현재 상태를 유지하고 OpenClaw on Android 의 다음 릴리스를 기다린 뒤 `oa --update` 를 다시 실행하세요. 업데이트 스크립트가 오래된 캐시 사본일 때도 이 메시지가 나올 수 있으므로, 몇 분 뒤 다시 실행하면 새 사본을 받습니다. 계속 멈추면 전체 메시지와 함께 보고하세요.

## 데이터 이전 도중 업데이트가 끊김

### 원인

업데이트 또는 그 안의 데이터 이전이 끝나기 전에 종료되었습니다(예: 앱을 강제 종료했거나 전원이 꺼짐). 데이터는 그대로 남아 있지만 이전은 끝나지 않은 상태입니다. 같은 `oa --update` 를 다시 실행해도 자동 이전은 다시 실행되지 않습니다. 그 실행은 새 백업을 만들지 않고, 자동 이전은 같은 실행에서 백업을 만들었을 때만 실행되기 때문입니다.

### 해결 방법

먼저 게이트웨이를 멈춘 뒤 이전을 직접 마무리하고 게이트웨이를 다시 시작하세요:

```bash
openclaw doctor --fix
openclaw gateway
```

문제가 생기면 `oa --restore` 의 목록에서 `pre-update/` 백업(업데이트 전의 데이터)을 선택할 수 있습니다.

## `EACCES: permission denied, realpath '/data/data/<다른 패키지>/...'` 또는 "... point into another app's folder"

```
EACCES: permission denied, realpath '/data/data/<다른 패키지>/files/home/.openclaw/...'
```

```
[WARN] N path(s) point into another app's folder and could not be repaired automatically
```

### 원인

Claw 앱과 Termux 사이, 디버그 앱과 릴리스 앱 사이에서 데이터를 옮기거나 다른 기기에서 만든 백업을 복구하면, OpenClaw 데이터 안에 다른 앱의 홈 경로(`/data/data/<다른 패키지>/files/home/...`)가 남을 수 있습니다. Android 는 다른 앱의 폴더에 접근하면 권한 오류(EACCES)를 내며, OpenClaw 2026.9 의 데이터 이전은 이 경로에서 멈춥니다.

`oa --update`(설정 확인 직전)와 모든 `oa --restore`(같은 환경 복구 포함)는 이제 이런 경로를 고칩니다. 대상은 OpenClaw 상태 데이터베이스의 에이전트 데이터베이스 등록 행과 오래된 lease 행, 그리고 `openclaw.json` 의 에이전트 `workspace`·`agentDir` 값입니다(에이전트를 여러 개 만든 경우 포함). 고쳤으면 `Repaired N path(s) …` (복구 때는 `Updated N path(s) …`) 가 출력됩니다. 자동으로 고칠 수 없는 값이 있으면 위의 경고가 나옵니다.

### 해결 방법

이전 업데이트가 이 문제로 멈췄다면 업데이트를 다시 실행한 뒤, 게이트웨이를 멈추고 이전을 직접 실행하세요.

```bash
oa --update && source ~/.bashrc
openclaw doctor --fix
```

`Could not fix the folder paths in your OpenClaw config … Nothing was changed.` 경고가 나오면 `~/.openclaw/openclaw.json` 에서 다른 앱의 폴더를 가리키는 경로 값(에이전트의 `workspace`·`agentDir`)을 현재 홈(`$HOME`) 아래로 직접 고친 뒤 `openclaw doctor --fix` 를 다시 실행하세요.

`point into another app's folder and could not be repaired automatically` 경고는 OpenClaw 상태 데이터베이스 안의 예상과 다른 모양의 항목을 가리킵니다. 그 뒤에도 `openclaw doctor --fix` 가 `EACCES … realpath` 로 멈추면 `oa --status` 출력과 `~/.openclaw-android/doctor-fix.log` 를 첨부해 [이슈](https://github.com/AidanPark/openclaw-android/issues)를 남겨 주세요. `oa --restore` 로 업데이트 전 데이터(`pre-update/` 백업)로 돌아갈 수 있습니다.

고치기 전에 수정할 파일의 사본이 남습니다(각각 최신 3개 보관).

- `~/.openclaw/state/openclaw.sqlite.oa-before-repair-<날짜시각>`
- `~/.openclaw/openclaw.json.oa-before-repair-<날짜시각>`

## `The updater downloaded an older copy of itself (cache)`

```
[FAIL] The updater downloaded an older copy of itself (cache). Nothing was changed.
       Run 'oa --update' again in a few minutes.
```

### 원인

새 릴리스 직후 몇 분 동안은 캐시가 업데이트 스크립트의 옛 사본을 내려줄 수 있습니다. 업데이터는 자신이 최신인지 확인하고, 옛 사본이면 Node.js 와 OpenClaw 를 바꾸기 전에 멈춥니다.

### 영향

아무것도 바뀌지 않았습니다. Node.js, OpenClaw, 데이터는 그대로입니다.

### 해결 방법

몇 분 뒤 `oa --update` 를 다시 실행하면 새 사본을 받습니다.

```bash
oa --update && source ~/.bashrc
```

환경 변수 `OA_ALLOW_UNMARKED_NODE_CHANGE=1` 과 `OA_ALLOW_UNMARKED_OPENCLAW_CHANGE=1` 은 개발자용으로 이 확인을 끕니다. 일반 사용에서는 쓰지 마세요.

## "Not enough free storage"

```
[FAIL] Not enough free storage to ...: 2000 MB needed, ... MB available.
       Nothing was changed.
```

### 원인

새 설치와, 고정 버전이 바뀌는 업데이트는 모두 2000MB 의 여유 공간이 필요합니다. 공간이 부족하면 아무것도 바꾸기 전에 멈추고 필요한 양과 남은 양을 알려 줍니다.

### 해결 방법

공간을 확보한 뒤(예: 다른 앱의 캐시 삭제, 사용하지 않는 파일 삭제, `~/.npm/_cacache` 제거) 명령을 다시 실행하세요. 아무것도 바뀌지 않았으므로 안전하게 다시 시도할 수 있습니다.

저장 공간이 완전히 찼으면 `oa --update` 가 그보다 먼저 `[FAIL] Could not create the run lock in … (is the storage full?)` 를 출력하고 멈출 수 있습니다. 원인과 해결 방법은 같습니다.

## Android 10 이하에서 OpenClaw 의 데스크톱 자동화 도구가 동작하지 않음

### 원인

OpenClaw 의 데스크톱 자동화 도구(`@trycua/cua-driver`)는 Android 10 이하(API 29 이하)에서 필요한 시스템 콜 일부가 막혀 동작하지 않을 수 있습니다.

### 해결 방법

해당 Android 버전에서 이 도구에 알려진 한계입니다.

## `openclaw update` 중 sharp 빌드 실패

_v1.1.0 이전 설치에 해당합니다 — 이제 `openclaw update` 는 막혀 있으니 `oa --update` 를 쓰세요._

```
npm error gyp ERR! not ok
Update Result: ERROR
Reason: global update
```

### 원인

이 프로젝트가 고정한 OpenClaw 버전은 `sharp`에 의존하지 않습니다 — 이미지 처리는 대신 `photon`을 거치므로, 정상적인 설치/업데이트 흐름에서는 이 빌드 자체가 실행되지 않아야 합니다. 그래도 이 에러가 보인다면, `npm install`/`npm rebuild sharp`를 직접 실행했거나, 여전히 `sharp`에 의존하는 예전의 고정되지 않은 OpenClaw 버전을 사용 중일 가능성이 높습니다.

### 영향

**이 에러는 무해합니다.** OpenClaw 자체는 `sharp` 없이도 정상적으로 작동합니다 — 문제가 있다면 수동으로 실행한 리빌드 과정만 실패한 것입니다.

### 해결 방법

`oa --update`는 더 이상 `libvips`를 설치하거나 `sharp`를 리빌드하지 않습니다 — Node.js와 OpenClaw을 검증된 고정 버전으로만 유지하며, 고정된 OpenClaw은 `sharp`가 필요하지 않습니다:

```bash
oa --update && source ~/.bashrc
```

## `clawdhub` 실행 시 "Cannot find package 'undici'" 에러

```
Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'undici' imported from /data/data/com.termux/files/usr/lib/node_modules/clawdhub/dist/http.js
```

### 원인

Node.js v24+ Termux 환경에서는 `undici` 패키지가 Node.js에 번들되지 않습니다. `clawdhub`가 HTTP 요청에 `undici`를 사용하지만 찾을 수 없어 실패합니다.

### 해결 방법

업데이터를 실행하면 `clawdhub`와 `undici` 의존성이 자동으로 설치됩니다:

```bash
oa --update && source ~/.bashrc
```

또는 수동으로 수정:

```bash
cd $(npm root -g)/clawdhub && npm install undici
```

## "not supported on android" 에러

```
Gateway status failed: Error: Gateway service install not supported on android
```

> **참고**: 이 문제는 v1.0.0 이전(Bionic) 설치에서만 발생합니다. v1.0.0+(glibc)에서는 Node.js가 `process.platform`을 `'linux'`으로 보고하므로 이 에러가 발생하지 않습니다.

### 원인

**v1.0.0 이전(Bionic)**: `glibc-compat.js`의 `process.platform` 오버라이드가 적용되지 않은 상태입니다. `NODE_OPTIONS`가 설정되지 않았기 때문입니다.

### 해결 방법

어떤 Node.js가 사용되고 있는지 확인:

```bash
node -e "console.log(process.platform)"
```

`android`가 출력되면 glibc node 래퍼가 사용되지 않고 있는 것입니다. 환경변수를 로드하세요:

```bash
source ~/.bashrc
```

여전히 `android`가 출력되면, 최신 버전으로 업데이트하세요 (v1.0.0+는 glibc를 사용하여 이 문제를 영구적으로 해결합니다):

```bash
oa --update && source ~/.bashrc
```

## `openclaw update` 시 node-llama-cpp 빌드 에러

_v1.1.0 이전 설치에 해당합니다 — 이제 `openclaw update` 는 막혀 있으니 `oa --update` 를 쓰세요._

```
[node-llama-cpp] Cloning ggml-org/llama.cpp (local bundle)
npm error 48%
Update Result: ERROR
```

### 원인

OpenClaw이 npm으로 업데이트할 때, `node-llama-cpp`의 postinstall 스크립트가 `llama.cpp` 소스를 clone하고 컴파일을 시도합니다. Termux의 빌드 툴체인(`cmake`, `clang`)이 Bionic으로 링크되어 있고 Node.js는 glibc로 실행되므로 — 두 환경이 네이티브 컴파일에 호환되지 않아 실패합니다.

### 영향

**이 에러는 무해합니다.** 프리빌트 `node-llama-cpp` 바이너리(`@node-llama-cpp/linux-arm64`)가 이미 설치되어 있으며 glibc 환경에서 정상 작동합니다. 실패한 소스 빌드가 프리빌트 바이너리를 덮어쓰지 않습니다.

node-llama-cpp는 선택적 로컬 임베딩에 사용됩니다. 프리빌트 바이너리가 로딩되지 않으면 OpenClaw이 원격 임베딩 프로바이더(OpenAI, Gemini 등)로 자동 fallback합니다.

### 해결 방법

조치가 필요 없습니다. 이 에러는 안전하게 무시할 수 있습니다. 프리빌트 바이너리가 정상 작동하는지 확인하려면:

```bash
node -e "require('$(npm root -g)/openclaw/node_modules/@node-llama-cpp/linux-arm64/bins/linux-arm64/llama-addon.node'); console.log('OK')"
```

## OpenCode 설치 시 EACCES 권한 에러

```
EACCES: Permission denied while installing opencode-ai
Failed to install 118 packages
```

### 원인

Bun이 패키지 설치 시 하드링크와 심링크를 생성하려고 시도합니다. Android 파일시스템이 이러한 작업을 제한하여 의존성 패키지에서 `EACCES` 에러가 발생합니다.

### 영향

**이 에러는 무해합니다.** 메인 바이너리(`opencode`)는 의존성 링크 실패에도 불구하고 정상적으로 설치됩니다. ld.so 결합과 proot 래퍼가 실행을 처리합니다.

### 해결 방법

조치가 필요 없습니다. OpenCode가 정상 작동하는지 확인:

```bash
opencode --version
```
