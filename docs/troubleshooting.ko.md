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

이 프로젝트는 검증되고 테스트된 OpenClaw + Node.js 버전 조합을 고정(pin)합니다(`platforms/openclaw/config.env` 참고). `openclaw update`(및 `openclaw --update`)는 최신 npm 릴리스를 설치하는데, 이는 고정된 버전보다 더 최신 Node.js를 필요로 하여 실행이 안 될 수 있습니다 — 그래서 `$PREFIX/bin/openclaw`에 설치된 가드가 두 명령을 모두 차단합니다. 게이트웨이 자체의 자동 업데이트 기능도 비활성화되어 있지만(`OPENCLAW_NO_AUTO_UPDATE=1`), `update available … Run: openclaw update` 같은 메시지는 계속 출력될 수 있습니다 — 바로 그 명령이 차단 대상입니다.

### 해결 방법

대신 `oa --update`를 사용하세요 — Node.js와 OpenClaw을 함께 검증된 고정 버전으로 업데이트합니다:

```bash
oa --update && source ~/.bashrc
```

읽기 전용인 `openclaw update status`는 차단되지 않고 계속 동작합니다.

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
