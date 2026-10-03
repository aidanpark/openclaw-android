# OpenClaw on Android

[English](README.md) | [한국어](README.ko.md) | [日本語](README.ja.md)

<img src="docs/images/openclaw_android.jpg" alt="OpenClaw on Android">

![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-brightgreen)
![Termux](https://img.shields.io/badge/Termux-Required-orange)
![No proot](https://img.shields.io/badge/proot--distro-Not%20Required-blue)
![License MIT](https://img.shields.io/github/license/AidanPark/openclaw-android)
![GitHub Stars](https://img.shields.io/github/stars/AidanPark/openclaw-android)

Android 也配拥有一个 Shell。

## 无需安装 Linux

在 Android 上运行 OpenClaw 的常规方法是通过 proot-distro 安装一个完整的 Linux 发行版，需要额外占用 700MB-1GB 的存储空间。OpenClaw on Android 只安装 glibc 动态链接器（ld.so），无需完整 Linux 发行版即可运行 OpenClaw。

**常规方法**：在 Termux 中通过 proot-distro 安装完整的 Linux 发行版。

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

**本项目**：无需 proot-distro，只安装 glibc 动态链接器。

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

| | 常规方法 (proot-distro) | 本项目 |
|---|---|---|
| 存储开销 | 1-2GB（Linux + 软件包） | ~200MB |
| 安装时间 | 20-30 分钟 | 3-10 分钟 |
| 性能 | 较慢（proot 中间层） | 原生速度 |
| 安装步骤 | 安装发行版、配置 Linux、安装 Node.js、修复路径…… | 一条命令搞定 |

## <img src="docs/images/claw-icon.svg" width="28" alt="Claw icon"> Claw App

还提供了独立的 Android 应用。它将终端模拟器和基于 WebView 的界面打包成一个 APK，无需 Termux。

- 一键安装：在应用内完成 bootstrap、Node.js 和 OpenClaw 的安装
- 内置仪表盘：控制网关、查看运行状态、管理工具
- 独立于 Termux 运行 — 安装此应用不会影响已有的 Termux + `oa` 环境

前往 [Releases](https://github.com/AidanPark/openclaw-android/releases) 页面下载 APK。

> **中国用户**：如果无法直接从 GitHub 下载，可使用镜像链接：
> [ghfast.top 镜像下载](https://ghfast.top/https://github.com/AidanPark/openclaw-android/releases/latest/download/app-release.apk)

## 系统要求

- Android 7.0 或更高版本（推荐 Android 10+）
- 约 1GB 可用存储空间
- Wi-Fi 或移动数据连接

## 安装内容

安装程序会自动处理 Termux 与标准 Linux 之间的差异。你无需手动操作——一条安装命令会完成以下所有工作：

1. **glibc 环境** — 安装 glibc 动态链接器（通过 pacman 的 glibc-runner），使标准 Linux 二进制文件无需修改即可运行
2. **Node.js (glibc)** — 下载官方 Node.js linux-arm64 版本，并通过 ld.so 加载脚本进行包装（不使用 patchelf，因为它会在 Android 上导致段错误）
3. **路径转换** — 自动将标准 Linux 路径（`/tmp`、`/bin/sh`、`/usr/bin/env`）转换为 Termux 路径
4. **临时目录配置** — 为 Android 配置可访问的临时文件夹
5. **服务管理绕过** — 在没有 systemd 的环境下配置正常运行
6. **OpenCode 集成** — 如果选择安装，会使用 proot + ld.so 拼接方式安装 OpenCode（用于 Bun 独立二进制文件）

## 从全新手机开始的详细步骤

1. [准备你的手机](#步骤一准备你的手机)
2. [安装 Termux](#步骤二安装-termux)
3. [Termux 初始设置](#步骤三termux-初始设置)
4. [安装 OpenClaw](#步骤四安装-openclaw) — 一条命令
5. [启动 OpenClaw 初始配置](#步骤五启动-openclaw-初始配置)
6. [启动 OpenClaw（网关）](#步骤六启动-openclaw网关)

### 步骤一：准备你的手机

配置开发者选项、保持唤醒、充电限制和电池优化。详细步骤请参阅 [保持进程存活指南](docs/disable-phantom-process-killer.md)。

### 步骤二：安装 Termux

> **重要提示**：Play Store 版本的 Termux 已停止维护，无法正常使用。必须从 F-Droid 安装。

1. 用手机浏览器打开 [f-droid.org](https://f-droid.org)
2. 搜索 `Termux`，然后点击 **Download APK** 下载并安装
   - 提示时请允许"安装来自未知来源的应用"

### 步骤三：Termux 初始设置

打开 Termux 应用，粘贴以下命令安装 curl（下一步需要用到）。

```bash
pkg upgrade -y && pkg install -y curl
```

> 首次运行时可能会要求你选择镜像源。随便选一个就行，选地理位置较近的会更快。
>
> 升级过程中如果出现配置文件相关的提问（`(Y/I/N/O/D/Z) [default=N] ?`），直接按 **Enter** 保留默认值即可。
>
> 不要跳过升级：在新安装的 Termux 上只安装 curl 可能导致 curl 无法运行（`CANNOT LINK EXECUTABLE "curl"`）。如果已经出现这种情况，请参阅[故障排除](docs/troubleshooting.md#curl-is-broken-after-step-3-cannot-link-executable)。

### 步骤四：安装 OpenClaw

> **提示：使用 SSH 输入更方便**
> 从这一步开始，你可以用电脑键盘输入命令，而不必在手机屏幕上打字。详见 [Termux SSH 设置指南](docs/termux-ssh-guide.md)。

在 Termux 中粘贴以下命令。

```bash
curl -sL myopenclawhub.com/install | bash && source ~/.bashrc
```

一条命令自动完成所有安装。根据网络速度和设备性能，大约需要 3-10 分钟。建议使用 Wi-Fi。

> **中国网络优化**：脚本会自动检测网络环境。如果 GitHub 访问较慢，会自动切换到镜像站点下载；如果 npm 安装速度较慢，会自动切换到 npmmirror.com 镜像。无需手动配置。

安装完成后，会显示 OpenClaw 版本以及运行 `openclaw onboard` 的提示。

### 步骤五：启动 OpenClaw 初始配置

按照安装输出中的提示，运行：

```bash
openclaw onboard
```

按照屏幕上的指引完成初始设置。

![openclaw onboard](docs/images/openclaw-onboard.png)

### 步骤六：启动 OpenClaw（网关）

初始设置完成后，启动网关：

> **重要提示**：请直接在手机上的 Termux 应用中运行 `openclaw gateway`，不要通过 SSH。如果通过 SSH 运行，当 SSH 会话断开时网关也会停止。

网关运行时会占用当前终端，因此需要新开一个标签页。点击底部菜单栏的 **汉堡菜单图标（☰）**，或从屏幕左侧边缘向右滑动（在底部菜单栏上方区域）打开侧边菜单，然后点击 **NEW SESSION**。

<img src="docs/images/termux_menu.png" width="300" alt="Termux 侧边菜单">

在新标签页中运行：

```bash
openclaw gateway
```

<img src="docs/images/termux_tab_1.png" width="300" alt="openclaw gateway 运行中">

> 要停止网关，按 `Ctrl+C`。不要使用 `Ctrl+Z` — 它只会挂起进程而不会终止它。

## 保持进程存活

Android 可能会在屏幕关闭时杀死后台进程或对其进行限制。详细的推荐设置请参阅 [保持进程存活指南](docs/disable-phantom-process-killer.md)（开发者选项、保持唤醒、充电限制、电池优化和 Phantom Process Killer）。

## 从电脑访问仪表盘

请参阅 [Termux SSH 设置指南](docs/termux-ssh-guide.md) 了解 SSH 访问和仪表盘隧道设置。

## 管理多台设备

如果你在同一网络中的多台设备上运行 OpenClaw，可以使用 <a href="https://myopenclawhub.com" target="_blank">Dashboard Connect</a> 工具从电脑统一管理。

- 为每台设备保存连接设置（IP、令牌、端口），并设置昵称
- 自动生成 SSH 隧道命令和仪表盘 URL
- **数据留在本地** — 连接设置（IP、令牌、端口）仅保存在浏览器的 localStorage 中，永远不会发送到任何服务器。

## CLI 参考

安装完成后，可以使用 `oa` 命令管理你的安装：

| 选项 | 说明 |
|--------|-------------|
| `oa --update` | 更新 OpenClaw 和 Android 补丁 |
| `oa --install` | 安装可选工具（tmux、code-server、AI CLI 等） |
| `oa --uninstall` | 卸载 OpenClaw on Android |
| `oa --backup` | 创建 OpenClaw 数据的完整备份 |
| `oa --restore` | 从备份恢复 |
| `oa --status` | 显示安装状态和所有已安装组件 |
| `oa --version` | 显示版本 |
| `oa --help` | 显示可用选项 |

## 更新

```bash
oa --update && source ~/.bashrc
```

一条命令更新所有已安装组件：

- **OpenClaw** — 保持在锁定（pinned）的已验证版本（当前为 2026.7.35 — 不一定是最新版本）
- **code-server** — 浏览器 IDE
- **OpenCode** — AI 编程助手
- **AI CLI 工具** — Claude Code、Gemini CLI、Codex CLI (Termux)
- **Android 补丁** — 本项目的兼容性补丁

已是最新的组件会被跳过。未安装的组件不会被触及——只更新设备上已有的内容。可以多次安全运行。

如果更新时网关正在运行，请在更新后重新启动它（停止后重新运行 `openclaw gateway`，或重启应用），以使用更新后的运行时。

**注意**：`openclaw update`（以及 `openclaw --update`）被有意阻止——本项目安装了一个守卫，将 OpenClaw 锁定在已验证的版本上。网关可能仍会打印类似 `update available … Run: openclaw update` 的提示，但该命令会被阻止。请改用 `oa --update`。只读的 `openclaw update status` 仍然可用。

> 如果 `oa` 命令不可用（旧版安装），请使用 curl 运行：
> ```bash
> curl -sL myopenclawhub.com/update | bash && source ~/.bashrc
> ```

## 备份与恢复

OpenClaw 内置的备份命令（`openclaw backup create`）在 Android 上经常失败，因为它依赖硬链接，而 Android 的应用私有存储会阻止硬链接操作。`oa --backup` 命令通过直接使用 `tar` 来解决这个问题，同时完全兼容 OpenClaw 的备份规范。

创建备份：
```bash
oa --backup
```
备份存储在 `~/.openclaw-android/backup/` 目录下，文件名带有时间戳（例如 `2026-03-14T00-00-00.000Z-openclaw-backup.tar.gz`）。你也可以指定自定义路径：`oa --backup ~/my-backups/`。每次备份包含你的配置、状态、工作区和代理。

从备份恢复：
```bash
oa --restore
```
此命令会列出默认备份目录中所有可用的备份。只需选择你要恢复的备份编号即可。工具会自动从备份清单中检测平台并将数据恢复到 `~/.openclaw/`。注意这会覆盖现有数据，因此需要确认。

## 故障排除

请参阅 [故障排除指南](docs/troubleshooting.md) 获取详细解决方案。

## 性能

`openclaw status` 等 CLI 命令可能比在 PC 上运行时感觉更慢。这是因为每条命令需要读取大量文件，而手机存储比 PC 慢，加上 Android 安全机制带来的额外开销。

不过，**一旦网关启动后，就没有区别了**。进程驻留在内存中，无需重新读取文件，而 AI 响应由外部服务器处理——速度与 PC 上相同。

## Android 本地 LLM

OpenClaw 通过 [node-llama-cpp](https://github.com/withcatai/node-llama-cpp) 支持本地 LLM 推理。预构建的原生二进制文件（`@node-llama-cpp/linux-arm64`）已包含在安装中，并能在 glibc 环境下成功加载——**本地 LLM 在手机上技术上是可行的**。

但存在一些实际限制：

| 限制因素 | 详情 |
|------------|---------|
| 内存 | GGUF 模型至少需要 2-4GB 可用内存（7B 模型，Q4 量化）。手机内存与 Android 和其他应用共享 |
| 存储 | 模型文件大小从 4GB 到 70GB+ 不等。手机存储空间很快就会用完 |
| 速度 | ARM 上的纯 CPU 推理非常慢。Android 不支持 llama.cpp 的 GPU 加速 |
| 使用场景 | OpenClaw 主要调用云端 LLM API（OpenAI、Gemini 等），响应速度与 PC 上相同。本地推理是辅助功能 |

如需体验，可以在手机上运行 TinyLlama 1.1B（Q4，约 670MB）等小型模型。生产环境建议使用云端 LLM 服务。

> **为什么使用 `--ignore-scripts`？** 安装程序使用 `npm install -g openclaw@2026.7.35 --ignore-scripts`（锁定的版本），因为 node-llama-cpp 的 postinstall 脚本会尝试通过 cmake 从源码编译 llama.cpp——在手机上需要 30 分钟以上且会因工具链不兼容而失败。预构建的二进制文件无需此编译步骤即可工作，因此可以安全跳过 postinstall。

<details>
<summary>面向开发者的技术文档</summary>

## 已安装组件

安装程序会跨多个包管理器设置基础设施、平台包和可选工具。核心基础设施和平台依赖会自动安装；可选工具在安装过程中逐个提示。

### 核心基础设施

| 组件 | 作用 | 安装方式 |
|-----------|------|----------------|
| git | 版本控制，npm git 依赖 | `pkg install` |

### Agent 平台运行时依赖

这些由平台的 `config.env` 标志控制。对于 OpenClaw，全部安装：

| 组件 | 作用 | 安装方式 |
|-----------|------|----------------|
| [pacman](https://wiki.archlinux.org/title/Pacman) | glibc 包的包管理器 | `pkg install` |
| [glibc-runner](https://github.com/termux-pacman/glibc-packages) | glibc 动态链接器 — 使标准 Linux 二进制文件能在 Android 上运行 | `pacman -Sy` |
| [Node.js](https://nodejs.org/) 22.23.3 (linux-arm64) | OpenClaw 的 JavaScript 运行时，锁定版本并通过 sha256 校验 | 从 nodejs.org 直接下载 |
| python | 原生 C/C++ 扩展的构建脚本 (node-gyp) | `pkg install` |
| make | 原生模块的 Makefile 执行 | `pkg install` |
| cmake | 基于 CMake 的原生模块构建 | `pkg install` |
| clang | 原生模块的 C/C++ 编译器 | `pkg install` |
| binutils | 原生构建的二进制工具 (llvm-ar) | `pkg install` |

### OpenClaw 平台

| 组件 | 作用 | 安装方式 |
|-----------|------|----------------|
| [OpenClaw](https://github.com/openclaw/openclaw) | AI Agent 平台（核心），锁定为已验证版本（2026.7.35） | `npm install -g` |
| [clawdhub](https://github.com/AidanPark/clawdhub) | OpenClaw 的技能管理器 | `npm install -g` |
| [PyYAML](https://pyyaml.org/) | `.skill` 打包的 YAML 解析器 | `pip install` |

### 可选工具（安装时提示）

每个工具都会通过单独的 Y/n 提示。你可以选择安装哪些。可选工具安装失败时只会提示警告并跳过，不会中止后续安装。

| 组件 | 作用 | 安装方式 |
|-----------|------|----------------|
| [tmux](https://github.com/tmux/tmux) | 终端复用器，用于后台会话 | `pkg install` |
| [ttyd](https://github.com/tsl0922/ttyd) | Web 终端 — 从浏览器访问 Termux | `pkg install` |
| [dufs](https://github.com/sigoden/dufs) | HTTP/WebDAV 文件服务器，用于浏览器文件传输 | `pkg install` |
| [android-tools](https://developer.android.com/tools/adb) | ADB，用于禁用 Phantom Process Killer | `pkg install` |
| [code-server](https://github.com/coder/code-server) | 基于浏览器的 VS Code IDE，锁定为 4.117.0（更新版本需要 Node.js 24） | 从 GitHub 直接下载 |
| [OpenCode](https://opencode.ai/) | AI 编程助手 (TUI)。自动安装 [Bun](https://bun.sh/) 和 [proot](https://proot-me.github.io/) 作为依赖 | `bun install -g` |
| [Chromium](https://www.chromium.org/) | OpenClaw 的浏览器自动化支持（约 400MB） | 自定义安装脚本 |
| [Playwright](https://playwright.dev/) | 浏览器自动化库（需要 Chromium）。自动配置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` | 自定义安装脚本 |
| [Claude Code](https://github.com/anthropics/claude-code) (Anthropic) | AI CLI 工具（可以安装，但在此环境下原生二进制文件可能尚无法运行 — 支持计划中） | `npm install -g` |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) (Google) | AI CLI 工具 | `npm install -g` |
| [Codex CLI](https://github.com/DioNanos/codex-termux)（OpenAI Codex 的 Termux 分支） | AI CLI 工具 | `npm install -g` |

## 项目结构

```
openclaw-android/
├── bootstrap.sh                # curl | bash 一行安装命令（下载器）
├── install.sh                  # 平台感知安装程序（入口点）
├── oa.sh                       # 统一 CLI（安装到 $PREFIX/bin/oa）
├── post-setup.sh               # Claw App 引导后设置（OTA 分发）
├── update.sh                   # 薄包装器（下载并运行 update-core.sh）
├── update-core.sh              # 轻量级更新程序（用于已有安装）
├── uninstall.sh                # 完整卸载（编排器）
├── patches/
│   ├── glibc-compat.js        # Node.js 运行时补丁（os.cpus、networkInterfaces）
│   ├── argon2-stub.js          # argon2 原生模块的 JS 桩（code-server）
│   ├── termux-compat.h         # Bionic 原生构建的 C 头文件（sharp）
│   ├── spawn.h                 # POSIX spawn 桩头文件
│   ├── systemctl               # Termux 的 systemd 桩
│   ├── apply-patches.sh        # 旧版补丁编排器（v1.0.2 兼容）
│   └── patch-paths.sh          # 旧版路径修复器（v1.0.2 兼容）
├── scripts/
│   ├── lib.sh                  # 共享函数库（颜色、平台检测、提示）
│   ├── check-env.sh            # 安装前环境检查
│   ├── install-infra-deps.sh   # 核心基础设施包（L1）
│   ├── install-glibc.sh        # glibc-runner 安装（L2 条件安装）
│   ├── install-nodejs.sh       # Node.js glibc 包装器安装（L2 条件安装）
│   ├── install-build-tools.sh  # 原生模块构建工具（L2 条件安装）
│   ├── backup.sh               # 备份和恢复 OpenClaw 数据（oa --backup/--restore）
│   ├── build-sharp.sh          # 构建 sharp 原生模块（图像处理）
│   ├── install-chromium.sh     # 安装 Chromium 用于浏览器自动化
│   ├── install-playwright.sh   # 安装 Playwright 浏览器自动化库
│   ├── install-code-server.sh  # 安装/更新 code-server（浏览器 IDE）
│   ├── install-opencode.sh     # 安装 OpenCode
│   ├── setup-env.sh            # 配置环境变量
│   └── setup-paths.sh          # 创建目录和符号链接
├── platforms/
│   ├── openclaw/               # OpenClaw 平台插件
│   │   ├── config.env          # 平台元数据和依赖声明
│   │   ├── env.sh              # 平台特定环境变量
│   │   ├── install.sh          # 平台包安装（npm、补丁、clawdhub）
│   │   ├── update.sh           # 平台包更新
│   │   ├── openclaw-shim.sh    # 生成 `openclaw update` 版本锁定守卫
│   │   ├── uninstall.sh        # 平台包卸载
│   │   ├── status.sh           # 平台状态显示
│   │   ├── verify.sh           # 平台验证检查
│   │   └── patches/            # 平台特定补丁
│   │       ├── openclaw-apply-patches.sh
│   │       ├── openclaw-patch-paths.sh
│   │       └── openclaw-build-sharp.sh
├── tests/
│   └── verify-install.sh       # 安装后验证（编排器 + 平台）
└── docs/
    ├── disable-phantom-process-killer.md    # 保持进程存活指南（EN）
    ├── disable-phantom-process-killer.ko.md # 保持进程存活指南（KO）
    ├── termux-ssh-guide.md     # Termux SSH 设置指南（EN）
    ├── termux-ssh-guide.ko.md  # Termux SSH 设置指南（KO）
    ├── troubleshooting.md      # 故障排除指南（EN）
    ├── troubleshooting.ko.md   # 故障排除指南（KO）
    └── images/                 # 截图和图片
```

## 架构

本项目使用**平台插件架构**，将平台无关的基础设施与平台特定代码分离：

```
┌─────────────────────────────────────────────────────────────┐
│  编排器 (install.sh, update-core.sh, uninstall.sh)           │
│  ── 平台无关。读取 config.env 并委托执行。                     │
├─────────────────────────────────────────────────────────────┤
│  共享脚本 (scripts/)                                         │
│  ── L1: install-infra-deps.sh（始终执行）                     │
│  ── L2: install-glibc.sh, install-nodejs.sh,                │
│         install-build-tools.sh（根据 config.env 条件执行）    │
│  ── L3: 可选工具（用户选择）                                   │
├─────────────────────────────────────────────────────────────┤
│  平台插件 (platforms/<name>/)                                │
│  ── config.env: 声明依赖 (PLATFORM_NEEDS_*)                 │
│  ── install.sh / update.sh / uninstall.sh / ...             │
└─────────────────────────────────────────────────────────────┘
```

**依赖层级：**

| 层级 | 范围 | 示例 | 控制方式 |
|-------|-------|----------|---------------|
| L1 | 基础设施（始终安装） | git、`pkg update` | 编排器 |
| L2 | 平台运行时（条件安装） | glibc、Node.js、构建工具 | `config.env` 标志 |
| L3 | 可选工具（用户选择） | tmux、code-server、AI CLI | 用户提示 |

每个平台在 `config.env` 中声明其 L2 依赖：

```bash
# platforms/openclaw/config.env
PLATFORM_NEEDS_GLIBC=true
PLATFORM_NEEDS_NODEJS=true
PLATFORM_NEEDS_BUILD_TOOLS=true
```

编排器读取这些标志并有条件地运行相应的安装脚本。不需要某些依赖的平台只需将对应标志设为 `false`，那些重量级依赖就会被完全跳过。

## 详细安装流程

运行 `bash install.sh` 将按顺序执行以下 8 个步骤。

### [1/8] 环境检查 — `scripts/check-env.sh`

在开始安装前验证当前环境是否满足要求。

- **Termux 检测**：检查 `$PREFIX` 环境变量。如果不在 Termux 中则立即退出
- **架构检查**：运行 `uname -m` 验证 CPU 架构（推荐 aarch64，支持 armv7l，x86_64 视为模拟器）
- **磁盘空间**：确保 `$PREFIX` 分区至少有 1000MB 可用空间。不足时报错
- **已有安装**：如果 `openclaw` 命令已存在，显示当前版本并提示这是重新安装/升级
- **Node.js 预检**：如果 Node.js 已安装，显示版本，低于 22 时发出警告
- **Phantom Process Killer**（Android 12+）：显示有关 Phantom Process Killer 的提示信息，附带 [禁用指南](docs/disable-phantom-process-killer.md) 链接

### [2/8] 平台选择

选择要安装的平台。目前硬编码为 `openclaw`。未来版本将在有多个平台可用时提供选择界面。

通过 `scripts/lib.sh` 中的 `load_platform_config()` 加载平台的 `config.env`，导出所有 `PLATFORM_*` 变量供后续步骤使用。

### [3/8] 可选工具选择（L3）

通过 `/dev/tty` 提供 11 个单独的 Y/n 提示，用于选择可选工具：

- tmux、ttyd、dufs、android-tools
- Chromium、Playwright
- code-server、OpenCode
- Claude Code、Gemini CLI、Codex CLI (Termux)

所有选择在安装开始前一次性完成。这样用户可以一次做完所有决定，然后在安装期间放手不管。

### [4/8] 核心基础设施（L1）— `scripts/install-infra-deps.sh` + `scripts/setup-paths.sh`

无论选择哪个平台都会执行。

**install-infra-deps.sh：**
- 运行 `pkg update -y && pkg upgrade -y` 刷新并升级软件包
- 安装 `git`（npm git 依赖和仓库克隆所需）

**setup-paths.sh：**
- 创建 `$PREFIX/tmp` 和 `$HOME/.openclaw-android/patches` 目录
- 显示标准 Linux 路径（`/bin/sh`、`/usr/bin/env`、`/tmp`）到 Termux 等效路径的映射

### [5/8] 平台运行时依赖（L2）

根据平台 `config.env` 标志有条件地安装运行时依赖：

| 标志 | 脚本 | 安装内容 |
|------|--------|-----------------|
| `PLATFORM_NEEDS_GLIBC=true` | `scripts/install-glibc.sh` | pacman、glibc-runner（提供 `ld-linux-aarch64.so.1`） |
| `PLATFORM_NEEDS_NODEJS=true` | `scripts/install-nodejs.sh` | Node.js 22.23.3 linux-arm64（sha256 校验）、grun 风格的包装脚本 |
| `PLATFORM_NEEDS_BUILD_TOOLS=true` | `scripts/install-build-tools.sh` | python、make、cmake、clang、binutils |

每个脚本都是自包含的，具有预检查和幂等行为（如果已安装则跳过）。

### [6/8] 平台包安装（L2）— `platforms/<platform>/install.sh`

委托给平台自身的安装脚本。对于 OpenClaw，此步骤：

1. 设置 `CPATH` 以获取 glib-2.0 头文件（原生模块构建所需）
2. 通过 pip 安装 PyYAML（`.skill` 打包所需）
3. 将 `glibc-compat.js` 复制到 `~/.openclaw-android/patches/`（Node.js 包装脚本读取随 Node.js 一起安装在 `~/.openclaw-android/lib/` 的副本——应用不会覆盖该位置）
4. 安装 `systemctl` 桩到 `$PREFIX/bin/`
5. 运行 `npm install -g openclaw@2026.7.35 --ignore-scripts`（`config.env` 中锁定的版本）
6. 运行 OpenClaw 自身的 postinstall 脚本（`postinstall-bundled-plugins.mjs`），该脚本被 `--ignore-scripts` 跳过
7. 通过 `openclaw-apply-patches.sh` 应用平台特定补丁
8. 安装 `openclaw update` 守卫（`openclaw-shim.sh`），使锁定版本得以保持
9. 安装 `clawdhub`（技能管理器）以及 `undici` 依赖（如需要）

**[6.5] 环境变量 + CLI + 标记文件：**

平台安装后，编排器会：
- 运行 `setup-env.sh` 写入 `.bashrc` 环境变量块
- 执行平台的 `env.sh` 获取平台特定变量
- 写入平台标记文件（`~/.openclaw-android/.platform`）
- 安装 `oa` CLI 和 `oaupdate` 包装器到 `$PREFIX/bin/`
- 将 `lib.sh`、`setup-env.sh` 和平台目录复制到 `~/.openclaw-android/` 供更新程序和卸载程序使用

### [7/8] 安装可选工具（L3）

安装步骤 3 中选择的工具：

- **Termux 包**：tmux、ttyd、dufs、android-tools — 通过 `pkg install` 安装
- **code-server**：基于浏览器的 VS Code IDE，带有 Termux 特定的解决方案（替换捆绑的 node、修补 argon2、处理硬链接失败）
- **OpenCode**：AI 编程助手，使用 proot + ld.so 拼接方式运行 Bun 独立二进制文件
- **Chromium**：OpenClaw 的浏览器自动化支持（约 400MB）
- **Playwright**：浏览器自动化库（通过 npm 安装 `playwright-core`）。自动设置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 和 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` 环境变量。如果未安装 Chromium 则自动安装
- **AI CLI 工具**：Claude Code、Gemini CLI、Codex CLI (Termux) — 通过 `npm install -g` 安装；安装失败时只会提示警告并跳过，不会中止整个安装（Claude Code 可能已安装但在此环境下尚无法运行 — 支持计划中）

### [8/8] 验证 — `tests/verify-install.sh`

运行两级验证：

**编排器检查（FAIL 级别）：**

| 检查项 | 通过条件 |
|------------|---------------|
| Node.js 版本 | `node -v` 与锁定版本一致（`platforms/openclaw/config.env`） |
| npm | `npm` 命令存在 |
| TMPDIR | 环境变量已设置 |
| OA_GLIBC | 设为 `1` |
| glibc-compat.js | 文件存在于 `~/.openclaw-android/lib/`（Node.js 包装脚本读取的副本；不存在时使用 `patches/`） |
| .glibc-arch | 标记文件存在 |
| glibc 动态链接器 | `ld-linux-aarch64.so.1` 存在 |
| glibc node 包装器 | 包装脚本位于 `~/.openclaw-android/bin/node` |
| 目录 | `~/.openclaw-android`、`$PREFIX/tmp` 存在 |
| .bashrc | 包含环境变量块 |

**编排器检查（WARN 级别，非关键）：**

| 检查项 | 通过条件 |
|------------|---------------|
| code-server | `code-server --version` 成功 |
| opencode | `opencode` 命令可用 |

**平台验证** — 委托给 `platforms/<platform>/verify.sh`：

| 检查项 | 通过条件 |
|------------|---------------|
| openclaw | `openclaw --version` 成功 |
| openclaw 包版本 | 与 `config.env` 中的锁定版本一致 |
| openclaw update 守卫 | 已安装到 `$PREFIX/bin/openclaw` |
| CONTAINER | 设为 `1` |
| clawdhub | 命令可用 |
| ~/.openclaw | 目录存在 |

所有 FAIL 级别项通过 → PASSED。任何 FAIL → 显示重新安装说明。WARN 项不会导致失败。

## 轻量级更新流程 — `oa --update`

运行 `oa --update`（或 `oaupdate`，用于向后兼容）会从 GitHub 下载最新的发行版 tarball，然后执行以下 5 个步骤。

### [1/5] 预检查

验证更新所需的最低条件。

- 检查 `$PREFIX` 存在（Termux 环境）
- 检查 `curl` 可用
- 从 `~/.openclaw-android/.platform` 标记文件检测平台
- 检测架构：glibc（`.glibc-arch` 标记）或 Bionic（旧版）
- 如需要，迁移旧目录名（`.openclaw-lite` → `.openclaw-android` — 旧版兼容）
- **Phantom Process Killer**（Android 12+）：显示提示信息，附带 [禁用指南](docs/disable-phantom-process-killer.md) 链接

### [2/5] 下载最新版本

从 GitHub 下载完整仓库 tarball 并解压到临时目录。验证所有必需文件存在：

- `scripts/lib.sh`
- `scripts/setup-env.sh`
- `platforms/<platform>/config.env`
- `platforms/<platform>/update.sh`

### [3/5] 更新核心基础设施

更新更新程序、卸载程序和 CLI 使用的共享文件：

- 将最新的平台目录复制到 `~/.openclaw-android/platforms/`
- 更新 `~/.openclaw-android/scripts/` 中的 `lib.sh` 和 `setup-env.sh`
- 更新补丁文件（`glibc-compat.js`、`argon2-stub.js`、`spawn.h`、`systemctl`）
- 更新 `$PREFIX/bin/` 中的 `oa` CLI 和 `oaupdate` 包装器
- 更新 `~/.openclaw-android/` 中的 `uninstall.sh`
- 如果检测到 Bionic 架构，执行自动 glibc 迁移
- 将 Node.js 收敛到锁定版本（sha256 校验下载，原子替换——失败时恢复之前的安装）
- 运行 `setup-env.sh` 刷新 `.bashrc` 环境变量块
- **Node 门控**：如果此时 Node.js 仍未达到锁定版本，更新到此为止——不会触及 OpenClaw（[4/5]）。请重新运行 `oa --update`

### [4/5] 更新平台

委托给 `platforms/<platform>/update.sh`。对于 OpenClaw，此步骤：

- 安装 `binutils`（用于原生模块构建）
- 再次检查 Node.js 锁定版本作为安全网（纵深防御——[3/5] 已经做过门控）
- 将 `openclaw` 收敛到锁定版本——只要已安装版本不同（无论更新还是更旧）就会安装锁定版本（更新的版本也会被回退到锁定版本）
- 重新应用平台特定补丁
- 刷新 `openclaw update` 守卫（`openclaw-shim.sh`），保持 `openclaw update`/`--update` 持续被阻止
- 更新/安装 `clawdhub`（技能管理器）
- 如需要，为 clawdhub 安装 `undici`（Node.js v24+）
- 如需要，将技能从 `~/skills/` 迁移到 `~/.openclaw/workspace/skills/`
- 如缺失则安装 PyYAML

### [5/5] 更新可选工具

更新已安装的工具：

- **code-server**：以更新模式运行 `install-code-server.sh`——保持在固定版本 4.117.0（之前安装的更新版本会被改回）。未安装则跳过
- **OpenCode**：已安装则更新；未安装则提供安装选项。需要 glibc 架构
- **Chromium**：已安装则更新。未安装则跳过
- **AI CLI 工具**（Claude Code、Gemini CLI、Codex CLI (Termux)）：比较已安装版本与最新 npm 版本，需要时更新。未安装的工具不会提供安装选项

</details>

## 许可证

MIT
