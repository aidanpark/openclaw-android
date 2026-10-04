# OpenClaw on Android

[English](README.md) | [中文](README.zh.md) | [日本語](README.ja.md)

<img src="docs/images/openclaw_android.jpg" alt="OpenClaw on Android">

![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-brightgreen)
![Termux](https://img.shields.io/badge/Termux-Required-orange)
![No proot](https://img.shields.io/badge/proot--distro-Not%20Required-blue)
![License MIT](https://img.shields.io/github/license/AidanPark/openclaw-android)
![GitHub Stars](https://img.shields.io/github/stars/AidanPark/openclaw-android)

나야, [OpenClaw](https://github.com/openclaw). 근데 이제 Android-Termux 를 곁들인...

## 리눅스 설치 없이

일반적으로 Android에서 OpenClaw를 실행하려면 proot-distro로 Linux를 설치해야 하고, 700MB~1GB의 저장공간이 필요합니다. OpenClaw on Android는 glibc 동적 링커(ld.so)만 설치하여, 전체 Linux 배포판 없이 OpenClaw를 실행할 수 있게 합니다.

**기존 방식**: Termux에서 proot-distro를 통해 전체 Linux 배포판을 설치합니다.

```
┌───────────────────────────────────────────────────┐
│ Linux Kernel                                      │
│ ┌───────────────────────────────────────────────┐ │
│ │ Android · Bionic libc · Termux                │ │
│ │ ┌───────────────────────────────────────────┐ │ │
│ │ │ proot-distro · Debian/Ubuntu              │ │ │
│ │ │ ┌───────────────────────────────────────┐ │ │ │
│ │ │ │ GNU glibc                             │ │ │ │
│ │ │ │ Node.js → OpenClaw                    │ │ │ │
│ │ │ └───────────────────────────────────────┘ │ │ │
│ │ └───────────────────────────────────────────┘ │ │
│ └───────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────┘
```

**이 프로젝트**: proot-distro 없이, glibc 동적 링커만 설치합니다.

```
┌───────────────────────────────────────────────────┐
│ Linux Kernel                                      │
│ ┌───────────────────────────────────────────────┐ │
│ │ Android · Bionic libc · Termux                │ │
│ │ ┌───────────────────────────────────────────┐ │ │
│ │ │ glibc ld.so (linker only)                 │ │ │
│ │ │ ld.so → Node.js → OpenClaw                │ │ │
│ │ └───────────────────────────────────────────┘ │ │
│ └───────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────┘
```

| | 기존 방식 (proot-distro) | 이 프로젝트 |
|---|---|---|
| 저장공간 오버헤드 | 1-2GB (Linux + 패키지) | ~200MB |
| 설치 시간 | 20-30분 | 3-10분 |
| 성능 | 느림 (proot 레이어) | 네이티브 속도 |
| 설정 과정 | 디스트로 설치, Linux 설정, Node.js 설치, 경로 수정... | 명령어 하나 실행 |

## <img src="docs/images/claw-icon.svg" width="28" alt="Claw icon"> Claw 앱

독립 실행형 Android 앱도 제공됩니다. 터미널 에뮬레이터와 WebView 기반 UI를 하나의 APK에 담았으며, Termux 없이 사용할 수 있습니다.

- 원탭 설치: 앱 안에서 부트스트랩, Node.js, OpenClaw 자동 설치
- 내장 대시보드: 게이트웨이 제어, 런타임 정보, 도구 관리
- Termux와 독립 실행 — 앱 설치가 기존 Termux + `oa` 환경에 영향을 주지 않음

[Releases](https://github.com/AidanPark/openclaw-android/releases) 페이지에서 APK를 다운로드하세요.

## 요구사항

- Android 7.0 이상 (Android 10 이상 권장)
- 약 1GB 이상의 여유 저장공간
- Wi-Fi 또는 모바일 데이터 연결

## 동작 원리

설치 스크립트는 Termux와 일반 Linux 환경의 차이를 자동으로 해결합니다. 사용자가 직접 할 일은 없으며, 설치 명령어 하나로 아래 내용이 모두 처리됩니다:

1. **glibc 환경** — glibc 동적 링커(pacman의 glibc-runner)를 설치하여 표준 Linux 바이너리가 수정 없이 실행되도록 설정. 설치 전에 Termux 서명 키로 패키지 목록을 검증합니다
2. **Node.js (glibc)** — 공식 Node.js linux-arm64 바이너리를 다운로드하고 ld.so 로더 스크립트로 래핑 (patchelf는 Android에서 segfault를 유발하므로 미사용)
3. **경로 변환** — 일반 Linux 경로(`/tmp`, `/bin/sh`, `/usr/bin/env`)를 Termux 경로로 자동 변환
4. **임시 폴더 설정** — Android에서 접근 가능한 임시 폴더로 자동 설정
5. **서비스 관리자 우회** — systemd 없이도 정상 동작하도록 설정
6. **OpenCode 통합** — 선택 시, proot + ld.so 결합 방식으로 Bun 독립 실행 바이너리인 OpenCode 설치

## 처음부터 설치하기 (초기화된 폰 기준)

1. [폰 준비](#1단계-폰-준비)
2. [Termux 설치](#2단계-termux-설치)
3. [Termux 초기 설정](#3단계-termux-초기-설정)
4. [OpenClaw 설치](#4단계-openclaw-설치) — 명령어 하나
5. [OpenClaw 설정 시작](#5단계-openclaw-설정-시작)
6. [OpenClaw(게이트웨이) 실행](#6단계-openclaw게이트웨이-실행)

### 1단계: 폰 준비

개발자 옵션, 화면 켜짐 유지, 충전 제한, 배터리 최적화 설정을 진행합니다. [프로세스 라이브 상태 유지 가이드](docs/disable-phantom-process-killer.ko.md)를 참고하세요.

### 2단계: Termux 설치

> **중요**: Google Play Store의 Termux는 업데이트가 중단되어 정상 동작하지 않습니다. 반드시 F-Droid에서 설치하세요.

1. 폰 브라우저에서 [F-Droid 공식 사이트](https://f-droid.org)에 접속
2. `Termux` 검색 후 **Download APK**를 눌러 다운로드 및 설치
   - "출처를 알 수 없는 앱" 설치 허용 팝업이 뜨면 **허용**

### 3단계: Termux 초기 설정

Termux 앱을 열고 아래 명령어를 붙여넣으세요. 다음 단계에 필요한 curl을 설치합니다.

```bash
pkg upgrade -y && pkg install -y curl
```

> 처음 실행하면 저장소 미러를 선택하라는 메시지가 나올 수 있습니다. 아무거나 선택해도 되지만, 지역적으로 가까운 미러를 고르면 더 빠릅니다.
>
> 업그레이드 중 설정 파일에 대한 질문(`(Y/I/N/O/D/Z) [default=N] ?`)이 나오면 **Enter** 를 눌러 기본값으로 진행하세요.
>
> 업그레이드를 건너뛰지 마세요. 새로 설치한 Termux 에서 curl 만 설치하면 curl 이 깨질 수 있습니다(`CANNOT LINK EXECUTABLE "curl"`). 이미 그렇게 됐다면 [문제 해결](docs/troubleshooting.ko.md)을 보세요.

### 4단계: OpenClaw 설치

> **팁: SSH로 편하게 입력하기**
> 이 단계부터는 폰 화면 대신 컴퓨터 키보드로 명령어를 입력할 수 있습니다. [Termux SSH 접속 가이드](docs/termux-ssh-guide.ko.md)를 참고하세요.

Termux에 아래 명령어를 붙여넣으세요.

```bash
curl -sL myopenclawhub.com/install | bash && source ~/.bashrc
```

명령어 하나로 모든 설치가 자동으로 진행됩니다. 3~10분 정도 소요되며 (네트워크 속도와 기기 성능에 따라 다름), Wi-Fi 환경을 권장합니다.

설치가 완료되면 OpenClaw 버전이 출력되고, `openclaw onboard`로 설정을 시작하라는 안내가 나타납니다.

### 5단계: OpenClaw 설정 시작

설치 완료 메시지의 안내에 따라 아래 명령어를 실행합니다.

```bash
openclaw onboard
```

화면의 안내에 따라 초기 설정을 진행합니다.

![openclaw onboard](docs/images/openclaw-onboard.png)

### 6단계: OpenClaw(게이트웨이) 실행

설정이 끝나면 게이트웨이를 실행합니다:

> **중요**: `openclaw gateway`는 SSH가 아닌, 폰의 Termux 앱에서 직접 실행하세요. SSH로 실행하면 SSH 연결이 끊어질 때 게이트웨이도 함께 종료됩니다.

게이트웨이는 실행 중 터미널을 점유하므로, 별도 탭에서 실행하세요. 하단 메뉴바의 **햄버거 아이콘(☰)**을 탭하거나, 화면 왼쪽 가장자리에서 오른쪽으로 스와이프하면 (하단 메뉴바 위 영역) 사이드 메뉴가 나타납니다. **NEW SESSION**을 눌러 새 탭을 추가하세요.

<img src="docs/images/termux_menu.png" width="300" alt="Termux 사이드 메뉴">

새 탭에서 실행합니다:

```bash
openclaw gateway
```

<img src="docs/images/termux_tab_1.png" width="300" alt="openclaw gateway 실행 화면">

> 게이트웨이를 중지하려면 `Ctrl+C`를 누르세요. `Ctrl+Z`는 프로세스를 종료하지 않고 일시 중지만 시키므로, 반드시 `Ctrl+C`를 사용하세요.

## 프로세스 라이브 상태 유지

Android는 백그라운드 프로세스를 종료하거나 화면이 꺼지면 스로틀링할 수 있습니다. [프로세스 라이브 상태 유지 가이드](docs/disable-phantom-process-killer.ko.md)에서 모든 권장 설정(개발자 옵션, 화면 켜짐 유지, 충전 제한, 배터리 최적화, Phantom Process Killer)을 확인하세요.

## PC에서 대시보드 접속

SSH 접속 및 대시보드 터널 설정은 [Termux SSH 접속 가이드](docs/termux-ssh-guide.ko.md)를 참고하세요.

대시보드 주소는 `http://127.0.0.1:18789/` 입니다. 토큰을 요구하거나 "unauthorized"가 나오면 토큰을 출력해서 함께 여세요:
```bash
node -p "require(process.env.HOME + '/.openclaw/openclaw.json').gateway.auth.token"
```
출력된 토큰을 대시보드의 인증 입력란에 붙여 넣거나 `http://127.0.0.1:18789/#token=<토큰>` 주소로 여세요. 토큰은 공개하지 마세요. 자세한 내용: [대시보드가 토큰을 요구함](docs/troubleshooting.ko.md#대시보드가-토큰을-요구하거나-unauthorized가-나옴).

## 여러 디바이스 관리

같은 네트워크에서 여러 기기에 OpenClaw를 운영한다면, <a href="https://myopenclawhub.com" target="_blank">Dashboard Connect</a> 도구로 PC에서 편리하게 관리할 수 있습니다.

- 각 기기의 연결 정보(IP, 토큰, 포트)를 닉네임과 함께 저장
- SSH 터널 명령어와 대시보드 URL을 자동 생성
- **데이터는 로컬에만 저장** — 연결 정보(IP, 토큰, 포트)는 브라우저의 localStorage에만 저장되며 어떤 서버로도 전송되지 않습니다.

## CLI 명령어

설치 후 `oa` 명령어로 설치를 관리할 수 있습니다:

| 옵션 | 설명 |
|------|------|
| `oa --update` | OpenClaw 및 Android 패치 업데이트 |
| `oa --install` | 선택적 도구 설치 (tmux, code-server, AI CLI 등) |
| `oa --uninstall` | OpenClaw on Android 제거 |
| `oa --backup` | OpenClaw 데이터 전체 백업 생성 |
| `oa --restore` | 백업에서 복구 |
| `oa --status` | 설치 상태 및 모든 설치된 컴포넌트 정보 표시 |
| `oa --version` | 버전 표시 |
| `oa --help` | 사용 가능한 옵션 표시 |

## 업데이트

```bash
oa --update && source ~/.bashrc
```

이 명령어 하나로 설치된 모든 컴포넌트를 한번에 업데이트합니다:

- **OpenClaw** — 고정(pinned)된 검증 버전으로 유지되는 코어 패키지 (현재 2026.7.35 — 항상 최신 릴리스는 아님)
- **code-server** — 브라우저 IDE
- **OpenCode** — AI 코딩 어시스턴트
- **AI CLI 도구** — Claude Code, Gemini CLI, Codex CLI (Termux)
- **Android 패치** — 이 프로젝트의 호환성 패치

이미 최신인 컴포넌트는 스킵됩니다. 설치하지 않은 컴포넌트는 건드리지 않고 — 기기에 이미 설치된 것만 업데이트합니다. 여러 번 실행해도 안전합니다.

업데이트 중에 게이트웨이가 실행 중이었다면, 업데이트 뒤 다시 시작하세요(멈춘 뒤 `openclaw gateway` 를 다시 실행하거나 앱을 다시 시작) — 그래야 업데이트된 런타임을 사용합니다.

**참고**: `openclaw update`(및 `openclaw --update`)는 의도적으로 차단되어 있습니다 — 이 프로젝트가 검증한 버전에 OpenClaw을 고정하는 가드가 설치되어 있기 때문입니다. 게이트웨이의 "update available" 알림은 꺼져 있습니다(`update.checkOnStart=false`). 직접 다시 켠 경우 `update available … Run: openclaw update` 같은 메시지가 출력될 수 있지만 해당 명령어는 차단되므로 대신 `oa --update`를 사용하세요. `openclaw update status`(읽기 전용)는 계속 동작합니다.

> `oa` 명령어가 없는 경우 (이전 설치 사용자), curl로 실행:
> ```bash
> curl -sL myopenclawhub.com/update | bash && source ~/.bashrc
> ```

## 백업 및 복구

OpenClaw의 내장 백업 명령어(`openclaw backup create`)는 Android의 앱 전용 저장소에서 하드링크(`fs.link()`) 생성이 제한되어 실패하고, 대화 기록도 담지 않습니다. `oa --backup` 명령어는 두 문제를 모두 해결합니다. OpenClaw 데이터 폴더(`~/.openclaw`) 전체를 `tar`로 묶고, SQLite 데이터베이스는 일관된 스냅샷으로 담으며, `openclaw backup verify`가 인정하는 아카이브를 생성합니다.

백업 생성:
```bash
oa --backup
```
백업은 `~/.openclaw-android/backup/` 폴더에 타임스탬프가 포함된 파일명(예: `2026-03-14T00-00-00.000Z-openclaw-backup.tar.gz`)으로 저장됩니다. `oa --backup ~/my-backups/`와 같이 사용자 지정 경로를 지정할 수도 있습니다. 백업에는 설정, 상태, 대화 기록, 워크스페이스, 에이전트 데이터가 포함됩니다(로그, 임시 파일, 플러그인 의존성 폴더는 제외).

> **백업 파일은 비공개로 보관하세요.** 백업에는 API 키와 로그인 자격 증명이 들어 있습니다. 파일은 본인만 읽을 수 있게 만들어지지만(`/sdcard` 같은 공유 저장소에서는 적용되지 않을 수 있음), 공개된 곳에 공유하거나 올리지 마세요.
>
> **Claw 앱:** `oa --backup`이 `backup.sh not found`라고 나오면(이 기능이 들어오기 전에 설치한 앱) `oa --update`를 한 번 실행하세요.

백업에서 복구:
```bash
oa --restore
```
이 명령어를 실행하면 기본 백업 폴더에 있는 사용 가능한 백업 목록이 표시됩니다. 복구하려는 백업의 번호를 선택하면 됩니다. 도구가 백업 매니페스트에서 플랫폼을 자동으로 감지하여 이 기기의 `~/.openclaw/` 경로로 복구합니다(다른 기기에서 만든 백업도 복구할 수 있습니다). 먼저 OpenClaw 게이트웨이를 중지하세요. 게이트웨이가 실행 중이면 복구를 거부합니다. 덮어쓰기 전에 현재 데이터를 `~/.openclaw-android/backup/pre-restore/`에 안전 백업으로 저장합니다. 백업 이후에 만든 파일은 그대로 남습니다. 실행 전 확인 절차가 진행됩니다.

## 문제 해결

자세한 트러블슈팅 가이드는 [문제 해결 문서](docs/troubleshooting.ko.md)를 참고하세요.

## 성능

`openclaw status` 같은 명령어는 PC보다 느리게 느껴질 수 있습니다. 이는 명령어를 실행할 때마다 많은 파일을 읽어야 하는데, 폰의 저장장치가 PC보다 느리고 Android의 보안 처리가 추가되기 때문입니다.

단, **게이트웨이가 실행된 이후에는 차이가 없습니다**. 프로세스가 메모리에 상주하므로 파일을 다시 읽지 않고, AI 응답은 외부 서버에서 처리되므로 PC와 동일한 속도입니다.

## 로컬 LLM 실행

OpenClaw은 [node-llama-cpp](https://github.com/withcatai/node-llama-cpp)를 통해 로컬 LLM 추론을 지원합니다. 프리빌트 네이티브 바이너리(`@node-llama-cpp/linux-arm64`)가 설치에 포함되어 있으며, glibc 환경에서 정상적으로 로딩됩니다 — **폰에서 로컬 LLM 구동이 기술적으로 가능합니다**.

다만 현실적인 제약이 있습니다:

| 제약 | 상세 |
|------|------|
| RAM | GGUF 모델은 최소 2-4GB 여유 메모리 필요 (7B 모델, Q4 양자화 기준). 폰 RAM은 Android와 다른 앱이 공유 |
| 저장공간 | 모델 파일 크기 4GB~70GB+. 폰 저장공간이 빠르게 소진됨 |
| 속도 | ARM CPU에서 추론은 매우 느림. Android에서는 llama.cpp GPU 오프로딩을 지원하지 않음 |
| 용도 | OpenClaw는 주로 클라우드 LLM API(OpenAI, Gemini 등)로 라우팅하며, PC와 동일한 속도로 응답. 로컬 추론은 보조 기능 |

실험 목적이라면 TinyLlama 1.1B (Q4, ~670MB) 같은 소형 모델은 폰에서 실행할 수 있습니다. 실제 사용에는 클라우드 LLM 제공자를 권장합니다.

> **왜 `--ignore-scripts`인가?** 설치 스크립트는 `npm install -g openclaw@2026.7.35 --ignore-scripts`(고정된 버전)를 사용합니다. node-llama-cpp의 postinstall 스크립트가 cmake로 llama.cpp 소스를 빌드하려고 시도하는데, 폰에서 30분 이상 소요되며 툴체인 호환성 문제로 실패합니다. 프리빌트 바이너리는 이 빌드 과정 없이 작동하므로, postinstall을 안전하게 건너뜁니다.

<details>
<summary>개발자용 기술 문서</summary>

## 설치 컴포넌트

설치 스크립트는 여러 패키지 매니저를 통해 인프라, 플랫폼 패키지, 선택적 도구를 설치합니다. 핵심 인프라와 플랫폼 의존성은 자동으로 설치되고, 선택적 도구는 설치 중 개별적으로 선택할 수 있습니다.

### 핵심 인프라 (항상 설치)

| 컴포넌트 | 역할 | 설치 방식 |
|----------|------|-----------|
| git | 버전 관리, npm git 의존성 | `pkg install` |

### 에이전트 플랫폼 런타임 의존성

플랫폼의 `config.env` 플래그로 제어됩니다. OpenClaw의 경우 모두 설치됩니다:

| 컴포넌트 | 역할 | 설치 방식 |
|----------|------|-----------|
| [pacman](https://wiki.archlinux.org/title/Pacman) | glibc 패키지 관리자 | `pkg install` |
| [glibc-runner](https://github.com/termux-pacman/glibc-packages) | glibc 동적 링커 — 표준 Linux 바이너리를 Android에서 실행 | `pacman -Sy` |
| [Node.js](https://nodejs.org/) 22.23.3 (linux-arm64) | OpenClaw용 JavaScript 런타임, sha256로 검증되는 고정 버전 | nodejs.org에서 직접 다운로드 |
| python | 네이티브 C/C++ 애드온 빌드 스크립트 (node-gyp) | `pkg install` |
| make | 네이티브 모듈 Makefile 실행 | `pkg install` |
| cmake | CMake 기반 네이티브 모듈 빌드 | `pkg install` |
| clang | 네이티브 모듈용 C/C++ 컴파일러 | `pkg install` |
| binutils | 네이티브 빌드용 바이너리 유틸리티 (llvm-ar) | `pkg install` |

### OpenClaw 플랫폼

| 컴포넌트 | 역할 | 설치 방식 |
|----------|------|-----------|
| [OpenClaw](https://github.com/openclaw/openclaw) | AI 에이전트 플랫폼 (핵심), 검증된 버전으로 고정 (2026.7.35) | `npm install -g` |
| [clawdhub](https://github.com/AidanPark/clawdhub) | OpenClaw 스킬 매니저 | `npm install -g` |
| [PyYAML](https://pyyaml.org/) | `.skill` 패키징용 YAML 파서 | `pip install` |

### 선택적 도구 (설치 중 선택)

각 도구는 개별 Y/n 프롬프트로 제공됩니다. 원하는 도구만 선택하여 설치할 수 있습니다. 선택적 도구 설치가 실패해도 경고만 표시하고 건너뛸 뿐, 나머지 설치 과정을 중단하지 않습니다.

| 컴포넌트 | 역할 | 설치 방식 |
|----------|------|-----------|
| [tmux](https://github.com/tmux/tmux) | 백그라운드 세션용 터미널 멀티플렉서 | `pkg install` |
| [ttyd](https://github.com/tsl0922/ttyd) | 웹 터미널 — 브라우저에서 Termux 접속 | `pkg install` |
| [dufs](https://github.com/sigoden/dufs) | HTTP/WebDAV 파일 서버 | `pkg install` |
| [android-tools](https://developer.android.com/tools/adb) | Phantom Process Killer 비활성화용 ADB | `pkg install` |
| [code-server](https://github.com/coder/code-server) | 브라우저 기반 VS Code IDE, 4.117.0으로 고정 (그 이후 버전은 Node.js 24 필요) | GitHub에서 직접 다운로드 |
| [OpenCode](https://opencode.ai/) | AI 코딩 어시스턴트 (TUI). [Bun](https://bun.sh/)과 [proot](https://proot-me.github.io/)을 의존성으로 자동 설치 | `bun install -g` |
| [Chromium](https://www.chromium.org/) | OpenClaw 브라우저 자동화 (~400MB) | 전용 설치 스크립트 |
| [Playwright](https://playwright.dev/) | 브라우저 자동화 라이브러리 (Chromium 필요). `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 자동 설정 | 전용 설치 스크립트 |
| [Claude Code](https://github.com/anthropics/claude-code) (Anthropic) | AI CLI 도구 (설치는 되지만 이 환경에서 네이티브 바이너리가 아직 실행되지 않을 수 있음 — 지원 예정) | `npm install -g` |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) (Google) | AI CLI 도구 | `npm install -g` |
| [Codex CLI](https://github.com/DioNanos/codex-termux) (OpenAI Codex의 Termux 포크) | AI CLI 도구 | `npm install -g` |

## 프로젝트 구조

```
openclaw-android/
├── bootstrap.sh                # curl | bash 원라이너 설치 (다운로더)
├── install.sh                  # 플랫폼 인식 설치 스크립트 (진입점)
├── oa.sh                       # 통합 CLI (설치 시 $PREFIX/bin/oa로 설치)
├── post-setup.sh               # Claw 앱 부트스트랩 후 설치 (OTA 배포)
├── update.sh                   # Thin wrapper (update-core.sh 다운로드 후 실행)
├── update-core.sh              # 기존 설치 환경 경량 업데이터
├── uninstall.sh                # 깔끔한 제거 (오케스트레이터)
├── patches/
│   ├── glibc-compat.js        # Node.js 런타임 패치 (os.cpus, networkInterfaces)
│   ├── argon2-stub.js          # argon2 네이티브 모듈용 JS 스텅 (code-server)
│   ├── termux-compat.h         # Bionic 네이티브 빌드용 C 헤더 (sharp)
│   ├── spawn.h                 # POSIX spawn 스텅 헤더
│   ├── systemctl               # Termux용 systemd 스텅
│   ├── apply-patches.sh        # 레거시 패치 오케스트레이터 (v1.0.2 호환)
│   └── patch-paths.sh          # 레거시 경로 수정 (v1.0.2 호환)
├── scripts/
│   ├── lib.sh                  # 공유 함수 라이브러리 (색상, 플랫폼 감지, 프롬프트)
│   ├── check-env.sh            # 사전 환경 점검
│   ├── install-infra-deps.sh   # 핵심 인프라 패키지 (L1)
│   ├── install-glibc.sh        # glibc-runner 설치 (L2 조건부)
│   ├── install-nodejs.sh       # Node.js glibc 래퍼 설치 (L2 조건부)
│   ├── install-build-tools.sh  # 네이티브 모듈용 빌드 도구 (L2 조건부)
│   ├── backup.sh               # OpenClaw 데이터 백업/복구 (oa --backup/--restore)
│   ├── build-sharp.sh          # sharp 네이티브 모듈 빌드 (이미지 처리)
│   ├── install-chromium.sh     # 브라우저 자동화용 Chromium 설치
│   ├── install-playwright.sh   # Playwright 브라우저 자동화 라이브러리 설치
│   ├── install-code-server.sh  # code-server 설치/업데이트 (브라우저 IDE)
│   ├── install-opencode.sh     # OpenCode 설치
│   ├── setup-env.sh            # 환경변수 설정
│   └── setup-paths.sh          # 디렉토리 및 심볼릭 링크 생성
├── platforms/
│   ├── openclaw/               # OpenClaw 플랫폼 플러그인
│   │   ├── config.env          # 플랫폼 메타데이터 및 의존성 선언
│   │   ├── env.sh              # 플랫폼별 환경변수
│   │   ├── install.sh          # 플랫폼 패키지 설치 (npm, 패치, clawdhub)
│   │   ├── update.sh           # 플랫폼 패키지 업데이트
│   │   ├── openclaw-shim.sh    # `openclaw update` 버전 고정 가드 생성
│   │   ├── uninstall.sh        # 플랫폼 패키지 제거
│   │   ├── status.sh           # 플랫폼 상태 표시
│   │   ├── verify.sh           # 플랫폼 검증 체크
│   │   └── patches/            # 플랫폼 전용 패치
│   │       ├── openclaw-apply-patches.sh
│   │       ├── openclaw-patch-paths.sh
│   │       └── openclaw-build-sharp.sh
├── tests/
│   └── verify-install.sh       # 설치 후 검증 (오케스트레이터 + 플랫폼)
└── docs/
    ├── disable-phantom-process-killer.md    # 프로세스 라이브 상태 유지 가이드 (영문)
    ├── disable-phantom-process-killer.ko.md # 프로세스 라이브 상태 유지 가이드 (한국어)
    ├── termux-ssh-guide.md     # Termux SSH 접속 가이드 (영문)
    ├── termux-ssh-guide.ko.md  # Termux SSH 접속 가이드 (한국어)
    ├── troubleshooting.md      # 트러블슈팅 가이드 (영문)
    ├── troubleshooting.ko.md   # 트러블슈팅 가이드 (한국어)
    └── images/                 # 스크린샷 및 이미지
```

## 아키텍처

이 프로젝트는 **플랫폼 플러그인 아키텍처**를 사용하여 플랫폼 비종속 인프라와 플랫폼별 코드를 분리합니다:

```
┌─────────────────────────────────────────────────────────────┐
│  오케스트레이터 (install.sh, update-core.sh, uninstall.sh) │
│  ── 플랫폼 비종속. config.env를 읽고 위임.                │
├─────────────────────────────────────────────────────────────┤
│  공유 스크립트 (scripts/)                                  │
│  ── L1: install-infra-deps.sh (항상 실행)                │
│  ── L2: install-glibc.sh, install-nodejs.sh,              │
│         install-build-tools.sh (config.env 조건부)       │
│  ── L3: 선택적 도구 (사용자 선택)                        │
├─────────────────────────────────────────────────────────────┤
│  플랫폼 플러그인 (platforms/<name>/)                       │
│  ── config.env: 의존성 선언 (PLATFORM_NEEDS_*)           │
│  ── install.sh / update.sh / uninstall.sh / ...           │
└─────────────────────────────────────────────────────────────┘
```

**의존성 계층:**

| 계층 | 범위 | 예시 | 제어 주체 |
|------|------|------|-----------|
| L1 | 인프라 (항상 설치) | git, `pkg update` | 오케스트레이터 |
| L2 | 플랫폼 런타임 (조건부) | glibc, Node.js, 빌드 도구 | `config.env` 플래그 |
| L3 | 선택적 도구 (사용자 선택) | tmux, code-server, AI CLI | 사용자 프롬프트 |

각 플랫폼은 `config.env`에서 L2 의존성을 선언합니다:

```bash
# platforms/openclaw/config.env
PLATFORM_NEEDS_GLIBC=true
PLATFORM_NEEDS_NODEJS=true
PLATFORM_NEEDS_BUILD_TOOLS=true
```

오케스트레이터는 이 플래그를 읽고 해당하는 설치 스크립트를 조건부로 실행합니다. 특정 의존성이 필요 없는 플랫폼은 해당 플래그를 `false`로 설정하면 무거운 의존성이 전부 스킵됩니다.

## 설치 흐름 상세

`bash install.sh`를 실행하면 아래 8단계가 순서대로 실행됩니다.

### [1/8] 환경 체크 — `scripts/check-env.sh`

설치를 시작하기 전에 현재 환경이 적합한지 검증합니다.

- **Termux 감지**: `$PREFIX` 환경변수 존재 여부로 Termux 환경인지 확인. 없으면 즉시 종료
- **아키텍처 확인**: `uname -m`으로 CPU 아키텍처 확인 (aarch64 권장, armv7l 지원, x86_64은 에뮬레이터로 판단)
- **디스크 여유 공간**: `$PREFIX` 파티션에 최소 1000MB 이상 여유 공간이 있는지 확인. 부족하면 오류
- **기존 설치 감지**: `openclaw` 명령어가 이미 존재하면 현재 버전을 표시하고 재설치/업데이트임을 안내
- **Node.js 사전 확인**: 이미 설치된 Node.js가 있으면 버전을 표시하고, 22 미만이면 업그레이드 예고
- **Phantom Process Killer** (Android 12+): Phantom Process Killer에 대한 안내 메시지와 [비활성화 가이드](docs/disable-phantom-process-killer.ko.md) 링크를 표시

### [2/8] 플랫폼 선택

설치할 플랫폼을 선택합니다. 현재는 `openclaw`으로 하드코딩되어 있습니다. 향후 여러 플랫폼이 제공되면 선택 UI가 추가될 예정입니다.

`scripts/lib.sh`의 `load_platform_config()`를 통해 플랫폼의 `config.env`를 로드하여, 이후 단계에서 사용할 모든 `PLATFORM_*` 변수를 내보냅니다.

### [3/8] 선택적 도구 선택 (L3)

11개의 개별 Y/n 프롬프트(`/dev/tty` 사용)로 선택적 도구를 선택합니다:

- tmux, ttyd, dufs, android-tools
- Chromium, Playwright
- code-server, OpenCode
- Claude Code, Gemini CLI, Codex CLI (Termux)

모든 선택은 설치 시작 전에 한 번에 수집됩니다. 사용자가 모든 결정을 마치면 설치 중 자리를 비울 수 있습니다.

### [4/8] 핵심 인프라 (L1) — `scripts/install-infra-deps.sh` + `scripts/setup-paths.sh`

플랫폼 선택과 무관하게 항상 실행됩니다.

**install-infra-deps.sh:**
- `pkg update -y && pkg upgrade -y`로 패키지 저장소 갱신 및 업그레이드
- `git` 설치 (npm git 의존성 및 저장소 클론에 필요)

**setup-paths.sh:**
- `$PREFIX/tmp`와 `$HOME/.openclaw-android/patches` 디렉토리 생성
- 표준 Linux 경로(`/bin/sh`, `/usr/bin/env`, `/tmp`)의 Termux 매핑 표시

### [5/8] 플랫폼 런타임 의존성 (L2)

플랫폼의 `config.env` 플래그에 따라 런타임 의존성을 조건부로 설치합니다:

| 플래그 | 스크립트 | 설치 내용 |
|--------|----------|----------|
| `PLATFORM_NEEDS_GLIBC=true` | `scripts/install-glibc.sh` | pacman, glibc-runner (`ld-linux-aarch64.so.1` 제공) |
| `PLATFORM_NEEDS_NODEJS=true` | `scripts/install-nodejs.sh` | Node.js 22.23.3 linux-arm64 (sha256 검증), grun 스타일 래퍼 스크립트 |
| `PLATFORM_NEEDS_BUILD_TOOLS=true` | `scripts/install-build-tools.sh` | python, make, cmake, clang, binutils |

각 스크립트는 사전 체크와 멱등성(이미 설치된 경우 스킵)을 갖춘 독립 실행형입니다.

### [6/8] 플랫폼 패키지 설치 (L2) — `platforms/<platform>/install.sh`

플랫폼 고유의 설치 스크립트에 위임합니다. OpenClaw의 경우:

1. `CPATH`를 glib-2.0 헤더용으로 설정 (네이티브 모듈 빌드에 필요)
2. pip으로 PyYAML 설치 (`.skill` 패키징용)
3. `glibc-compat.js`를 `~/.openclaw-android/patches/`에 복사 (Node.js 래퍼는 Node.js 설치 때 함께 들어가는 `~/.openclaw-android/lib/` 사본을 읽습니다 — 앱이 덮어쓰지 않는 위치)
4. `systemctl` 스텅을 `$PREFIX/bin/`에 설치
5. `npm install -g openclaw@2026.7.35 --ignore-scripts` 실행 (`config.env`에 고정된 버전)
6. `--ignore-scripts`로 건너뛴 OpenClaw 자체 postinstall 스크립트(`postinstall-bundled-plugins.mjs`) 실행
7. `openclaw-apply-patches.sh`로 플랫폼별 패치 적용
8. `openclaw update` 가드(`openclaw-shim.sh`) 설치 — 고정 버전이 유지되도록 함
9. `clawdhub` (스킬 매니저) 및 필요 시 `undici` 의존성 설치
10. 게이트웨이의 "update available" 알림 끄기(`openclaw config set update.checkOnStart false`) — 이미 직접 설정한 값이 있으면 그대로 둠

**[6.5] 환경변수 + CLI + 마커:**

플랫폼 설치 후 오케스트레이터가:
- `setup-env.sh`를 실행하여 `.bashrc` 환경변수 블록 작성
- 플랫폼의 `env.sh`를 평가하여 플랫폼별 변수 설정
- 플랫폼 마커 파일(`~/.openclaw-android/.platform`) 기록
- `oa` CLI와 `oaupdate` 래퍼를 `$PREFIX/bin/`에 설치
- `lib.sh`, `setup-env.sh`, 플랫폼 디렉토리를 `~/.openclaw-android/`에 복사 (업데이터와 언인스톨러가 사용)

### [7/8] 선택적 도구 설치 (L3)

3단계에서 선택한 도구를 설치합니다:

- **Termux 패키지**: tmux, ttyd, dufs, android-tools — `pkg install`로 설치
- **code-server**: 브라우저 기반 VS Code IDE. Termux 전용 워커라운드 포함 (번들 node 교체, argon2 패치, 하드 링크 실패 처리)
- **OpenCode**: AI 코딩 어시스턴트. proot + ld.so 결합 방식으로 Bun 독립 실행 바이너리 지원
- **Chromium**: OpenClaw 브라우저 자동화 지원 (~400MB)
- **Playwright**: 브라우저 자동화 라이브러리 (`playwright-core` npm 설치). `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 및 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` 환경변수 자동 설정. Chromium 미설치 시 자동 설치
- **AI CLI 도구**: Claude Code, Gemini CLI, Codex CLI (Termux) — `npm install -g`로 설치. 설치 실패 시 경고만 표시하고 건너뛰며 설치 전체를 중단하지 않음 (Claude Code는 설치는 되지만 이 환경에서 아직 실행되지 않을 수 있음 — 지원 예정)

### [8/8] 검증 — `tests/verify-install.sh`

2단계 검증을 실행합니다:

**오케스트레이터 검증 (FAIL 레벨):**

| 검증 항목 | PASS 조건 |
|-----------|----------|
| Node.js 버전 | `node -v`가 고정 버전과 일치 (`platforms/openclaw/config.env`) |
| npm | `npm` 명령어 존재 |
| TMPDIR | 환경변수 설정됨 |
| OA_GLIBC | `1`로 설정됨 |
| glibc-compat.js | `~/.openclaw-android/lib/`에 파일 존재 (Node.js 래퍼가 읽는 사본 · 없으면 `patches/`) |
| .glibc-arch | 마커 파일 존재 |
| glibc 동적 링커 | `ld-linux-aarch64.so.1` 존재 |
| glibc node 래퍼 | `~/.openclaw-android/bin/node`에 래퍼 스크립트 존재 |
| 디렉토리 | `~/.openclaw-android`, `$PREFIX/tmp` 존재 |
| .bashrc | 환경변수 블록 포함 |

**오케스트레이터 검증 (WARN 레벨, 비필수):**

| 검증 항목 | PASS 조건 |
|-----------|----------|
| code-server | `code-server --version` 성공 |
| opencode | `opencode` 명령어 존재 |

**플랫폼 검증** — `platforms/<platform>/verify.sh`에 위임:

| 검증 항목 | PASS 조건 |
|-----------|----------|
| openclaw | `openclaw --version` 성공 |
| openclaw 패키지 버전 | `config.env`의 고정 버전과 일치 |
| openclaw update 가드 | `$PREFIX/bin/openclaw`에 설치됨 |
| CONTAINER | `1`로 설정됨 |
| clawdhub | 명령어 존재 |
| ~/.openclaw | 디렉토리 존재 |

모든 FAIL 레벨 항목 통과 시 PASSED. FAIL 발생 시 재설치 안내를 표시합니다. WARN 항목은 실패로 처리되지 않습니다.

## 경량 업데이터 흐름 — `oa --update`

`oa --update` (또는 하위 호환을 위한 `oaupdate`)를 실행하면 GitHub에서 최신 릴리스 tarball을 다운로드하고 아래 5단계를 순서대로 실행합니다.

### [1/5] 사전 점검

업데이트를 위한 최소 조건을 확인합니다.

- `$PREFIX` 존재 확인 (Termux 환경)
- `curl` 사용 가능 여부 확인
- `~/.openclaw-android/.platform` 마커 파일에서 플랫폼 감지
- 아키텍처 감지: glibc (`.glibc-arch` 마커) 또는 Bionic (레거시)
- 구버전 디렉토리 마이그레이션 (`.openclaw-lite` → `.openclaw-android` — 레거시 호환)
- **Phantom Process Killer** (Android 12+): [비활성화 가이드](docs/disable-phantom-process-killer.ko.md) 링크와 함께 안내 메시지를 표시

### [2/5] 최신 릴리스 다운로드

GitHub에서 전체 저장소 tarball을 다운로드하고 임시 디렉토리에 추출합니다. 필수 파일의 존재를 확인합니다:

- `scripts/lib.sh`
- `scripts/setup-env.sh`
- `platforms/<platform>/config.env`
- `platforms/<platform>/update.sh`

### [3/5] 핵심 인프라 업데이트

업데이터, 언인스톨러, CLI가 사용하는 공유 파일을 갱신합니다:

- 최신 플랫폼 디렉토리를 `~/.openclaw-android/platforms/`에 복사
- `~/.openclaw-android/scripts/`의 `lib.sh`와 `setup-env.sh` 갱신
- 패치 파일 갱신 (`glibc-compat.js`, `argon2-stub.js`, `spawn.h`, `systemctl`)
- `$PREFIX/bin/`의 `oa` CLI와 `oaupdate` 래퍼 갱신
- `~/.openclaw-android/`의 `uninstall.sh` 갱신
- Bionic 아키텍처가 감지되면 자동 glibc 마이그레이션 수행
- Node.js를 고정 버전으로 수렴 (sha256 검증 다운로드, 원자적 교체 — 실패 시 이전 설치 복원)
- `setup-env.sh`를 실행하여 `.bashrc` 환경변수 블록 갱신
- **Node 게이트**: 이후에도 Node.js가 고정 버전이 아니면 여기서 업데이트를 중단합니다 — OpenClaw([4/5])는 건드리지 않습니다. `oa --update`를 다시 실행하세요

### [4/5] 플랫폼 업데이트

`platforms/<platform>/update.sh`에 위임합니다. OpenClaw의 경우:

- `binutils` 설치 (네이티브 모듈 빌드용)
- 안전장치로 Node.js 고정 버전을 재확인 (방어적 이중 확인 — [3/5]에서 이미 게이트를 거침)
- `openclaw`을 고정 버전으로 수렴 — 설치된 버전이 다르면 방향에 상관없이 고정 버전을 설치 (더 최신 버전이 설치되어 있어도 고정 버전으로 되돌림)
- 플랫폼별 패치 재적용
- `openclaw update` 가드(`openclaw-shim.sh`) 갱신 — `openclaw update`/`--update`가 계속 차단되도록 유지
- `clawdhub` (스킬 매니저) 업데이트/설치
- 필요 시 clawdhub용 `undici` 설치 (Node.js v24+)
- 게이트웨이의 "update available" 알림 끄기(`update.checkOnStart=false`) — 직접 설정한 값이 없을 때만
- 필요 시 `~/skills/`에서 `~/.openclaw/workspace/skills/`로 스킬 마이그레이션
- PyYAML 누락 시 설치

### [5/5] 선택적 도구 업데이트

이미 설치된 도구만 업데이트합니다:

- **code-server**: `install-code-server.sh`를 update 모드로 실행 — 고정 버전 4.117.0 으로 유지(예전에 더 새 버전이 설치됐으면 되돌림). 미설치 시 스킵
- **OpenCode**: 설치된 경우 업데이트, 미설치 시 설치 여부 문의. glibc 아키텍처 필요
- **Chromium**: 설치된 경우 업데이트. 미설치 시 스킵
- **AI CLI 도구** (Claude Code, Gemini CLI, Codex CLI (Termux)): 설치된 버전과 최신 npm 버전을 비교하여 필요 시 업데이트. 미설치 도구는 설치를 제안하지 않음

</details>

## 라이선스

MIT
