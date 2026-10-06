# OpenClaw on Android

[English](README.md) | [한국어](README.ko.md) | [中文](README.zh.md)

<img src="docs/images/openclaw_android.jpg" alt="OpenClaw on Android">

![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-brightgreen)
![Termux](https://img.shields.io/badge/Termux-Required-orange)
![No proot](https://img.shields.io/badge/proot--distro-Not%20Required-blue)
![License MIT](https://img.shields.io/github/license/AidanPark/openclaw-android)
![GitHub Stars](https://img.shields.io/github/stars/AidanPark/openclaw-android)

Androidにもシェルを。

## Linuxのインストール不要

AndroidでOpenClawを実行する一般的な方法では、proot-distroでLinuxをインストールする必要があり、700MB〜1GBのオーバーヘッドが発生します。OpenClaw on Androidは、glibcの動的リンカー（ld.so）だけをインストールすることでこの手間を解消し、完全なLinuxディストリビューションなしでOpenClawを実行できるようにします。

**従来の方式**: Termux上でproot-distroを使い、完全なLinuxディストリビューションをインストールする方式です。

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

**本プロジェクト**: proot-distroを使わず、glibc動的リンカーのみを利用します。

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

| | 従来方式 (proot-distro) | 本プロジェクト |
|---|---|---|
| ストレージ消費 | 1〜2GB（Linux + パッケージ） | 約200MB |
| セットアップ時間 | 20〜30分 | 3〜10分 |
| パフォーマンス | 遅い（prootレイヤー経由） | ネイティブ速度 |
| 手順 | ディストリビューションのインストール、Linuxの設定、Node.jsのインストール、パスの修正… | コマンド1つ |

## <img src="docs/images/claw-icon.svg" width="28" alt="Claw icon"> Claw アプリ

スタンドアロンのAndroidアプリも提供されています。ターミナルエミュレータとWebViewベースのUIを単一のAPKにバンドルしており、Termuxは不要です。

- ワンタップセットアップ: bootstrap、Node.js、OpenClawをアプリ内からまとめてインストール
- ゲートウェイ制御、ランタイム情報、ツール管理を行うダッシュボードを内蔵
- ツールのインストール: 設定 → 追加ツール（Settings → Additional Tools）画面から、インストールスクリプト（`post-setup.sh --tools-only`）でtmux、ttyd、dufs、Android Tools、Playwright、Claude Code、Gemini CLI、Codex CLIをインストールできます。tmux、ttyd、dufs、Android Toolsは署名済みのTermuxパッケージリストで検証します。インストールに失敗したツールや `--version` の確認に失敗したツールは「動作していません」と再インストールボタンで表示します。code-server、OpenCode、SSHサーバー、Chromiumはまだアプリからインストールできません。code-serverはアプリのターミナルで `oa --install` を使ってインストールできます
- Termuxとは独立して動作 — アプリをインストールしても既存のTermux + `oa` 環境には影響しません

APKは[Releases](https://github.com/AidanPark/openclaw-android/releases)ページからダウンロードできます。

## 動作要件

- Android 7.0以上（Android 10以降推奨）
- 約2GB（2000MB）の空きストレージ（新規インストール前と、固定バージョンが変わる更新の前に確認します）
- Wi-Fiまたはモバイルデータ通信

## 何をしてくれるのか

インストーラーがTermuxと標準Linuxの差異を自動的に解決します。手動で行う作業は一切なく、以下の処理がすべて1つのコマンドで完結します。

1. **glibc環境の構築** — pacmanのglibc-runner経由でglibc動的リンカーを導入し、標準的なLinuxバイナリを変更なしに動作させます。導入前にTermuxの署名鍵でパッケージ一覧を検証します
2. **Node.js (glibc版)** — 公式のNode.js linux-arm64をダウンロードし、ld.soローダースクリプトでラップします（Androidでセグフォルトを起こすpatchelfは使いません）
3. **パス変換** — 標準的なLinuxパス（`/tmp`、`/bin/sh`、`/usr/bin/env`）をTermuxのパスに自動変換します
4. **一時ディレクトリの設定** — Androidからアクセス可能な一時フォルダを設定します
5. **サービスマネージャの回避** — systemdなしで通常動作するよう設定します
6. **OpenCode統合** — 選択された場合、Bunスタンドアロンバイナリ向けにproot + ld.soの連結方式でOpenCodeをインストールします

## ステップバイステップのセットアップ（新品の端末から）

1. [スマートフォンの準備](#ステップ-1-スマートフォンの準備)
2. [Termuxのインストール](#ステップ-2-termuxのインストール)
3. [Termuxの初期セットアップ](#ステップ-3-termuxの初期セットアップ)
4. [OpenClawのインストール](#ステップ-4-openclawのインストール) — コマンド1つ
5. [OpenClawのセットアップ開始](#ステップ-5-openclawのセットアップ開始)
6. [OpenClaw（ゲートウェイ）の起動](#ステップ-6-openclawゲートウェイの起動)

### ステップ 1: スマートフォンの準備

開発者オプション、スリープ無効化、充電上限、バッテリー最適化の設定を行います。詳しい手順は[プロセスを生かし続けるためのガイド](docs/disable-phantom-process-killer.md)を参照してください。

### ステップ 2: Termuxのインストール

> **重要**: Play Store版のTermuxはサポートが終了しており動作しません。必ずF-Droidからインストールしてください。

1. スマートフォンのブラウザで[f-droid.org](https://f-droid.org)を開きます
2. `Termux`を検索し、**Download APK**をタップしてダウンロード・インストールします
   - 「提供元不明のアプリのインストール」を許可するよう求められた場合は許可してください

### ステップ 3: Termuxの初期セットアップ

Termuxアプリを開き、次のコマンドを貼り付けてcurlをインストールします（次のステップで必要になります）。

```bash
pkg upgrade -y && pkg install -y curl
```

> 初回起動時にミラーの選択を求められることがあります。どれでも構いませんが、地理的に近いミラーの方が高速です。
>
> アップグレード中に設定ファイルに関する質問（`(Y/I/N/O/D/Z) [default=N] ?`）が表示されることがあります。**Enter**を押してデフォルトのままにしてください。
>
> アップグレードを省略しないでください。まっさらなTermuxでcurlだけをインストールすると、curlが壊れることがあります（`CANNOT LINK EXECUTABLE "curl"`）。すでにその状態になった場合は[トラブルシューティング](docs/troubleshooting.md#curl-is-broken-after-step-3-cannot-link-executable)を参照してください。

### ステップ 4: OpenClawのインストール

> **ヒント: SSHを使うと入力が楽になります**
> このステップ以降は、スマートフォンの画面ではなくPCのキーボードからコマンドを入力できます。詳細は[Termux SSHセットアップガイド](docs/termux-ssh-guide.md)を参照してください。

Termuxで以下のコマンドを貼り付けてください。

```bash
curl -sL myopenclawhub.com/install | bash && source ~/.bashrc
```

すべて1つのコマンドで自動的にインストールされます。所要時間はネットワーク速度や端末によって3〜10分です。Wi-Fi環境を推奨します。

完了するとOpenClawのバージョンが表示され、`openclaw onboard`の実行案内が出ます。

### ステップ 5: OpenClawのセットアップ開始

インストール出力の指示に従って、次を実行します。

```bash
openclaw onboard
```

画面の指示に従って初期セットアップを完了させます。

![openclaw onboard](docs/images/openclaw-onboard.png)

### ステップ 6: OpenClaw（ゲートウェイ）の起動

セットアップが完了したらゲートウェイを起動します。

> **重要**: `openclaw gateway`はSSH経由ではなく、スマートフォン上のTermuxアプリで直接実行してください。SSH経由で実行するとSSH接続が切れた時点でゲートウェイも停止してしまいます。

ゲートウェイは実行中ターミナルを占有するため、専用のタブで起動します。下部メニューバーの**ハンバーガーアイコン (☰)** をタップするか、画面左端（下部メニューバーより上）から右にスワイプしてサイドメニューを開き、**NEW SESSION**をタップします。

<img src="docs/images/termux_menu.png" width="300" alt="Termux side menu">

新しいタブで以下を実行します。

```bash
openclaw gateway
```

<img src="docs/images/termux_tab_1.png" width="300" alt="openclaw gateway running">

> ゲートウェイを停止するには `Ctrl+C` を使ってください。`Ctrl+Z` ではプロセスが終了せずサスペンドされるだけなので使わないでください。

## プロセスを生かし続ける

Androidは画面オフ時にバックグラウンドプロセスを終了したりスロットルしたりすることがあります。推奨される設定（開発者オプション、スリープ無効化、充電上限、バッテリー最適化、Phantom Process Killer）の詳細は[プロセスを生かし続けるためのガイド](docs/disable-phantom-process-killer.md)を参照してください。

## PCからダッシュボードにアクセス

SSHアクセスとダッシュボードトンネルの設定方法は[Termux SSHセットアップガイド](docs/termux-ssh-guide.md)を参照してください。

ダッシュボードのアドレスは `http://127.0.0.1:18789/` です。トークンを求められる（または "unauthorized" と表示される）場合は、トークンを表示して一緒に開いてください:
```bash
node -p "require(process.env.HOME + '/.openclaw/openclaw.json').gateway.auth.token"
```
表示されたトークンをダッシュボードの認証欄に貼り付けるか、`http://127.0.0.1:18789/#token=<トークン>` を開きます。トークンは公開しないでください。詳細: [ダッシュボードがトークンを要求する](docs/troubleshooting.md#dashboard-asks-for-a-token-or-shows-unauthorized)（英語）。

## 複数端末の管理

同じネットワーク上の複数端末でOpenClawを運用する場合は、<a href="https://myopenclawhub.com" target="_blank">Dashboard Connect</a> ツールを使うとPCからまとめて管理できます。

- 各端末ごとに接続設定（IP、トークン、ポート）をニックネーム付きで保存
- SSHトンネルコマンドとダッシュボードURLを自動生成
- **データはローカルに留まります** — 接続設定（IP、トークン、ポート）はブラウザのlocalStorageにのみ保存され、サーバーに送信されることはありません。

## CLIリファレンス

インストール後、`oa`コマンドでインストールを管理できます。

| オプション | 説明 |
|--------|-------------|
| `oa --update` | OpenClawとAndroid向けパッチを更新 |
| `oa --install` | オプションツール（tmux、code-server、AI CLIなど）をインストール |
| `oa --uninstall` | OpenClaw on Androidをアンインストール |
| `oa --backup` | OpenClawデータの完全バックアップを作成 |
| `oa --restore` | バックアップから復元（`--force-no-safety`: 安全バックアップを保存できなくても続行） |
| `oa --status` | インストール状況とインストール済みコンポーネントを表示（ハードリンクパッチの状態を含む） |
| `oa --version` | バージョンを表示 |
| `oa --help` | 利用可能なオプションを表示 |

## アップデート

```bash
oa --update && source ~/.bashrc
```

このコマンド1つで、インストール済みコンポーネントをすべて一括で更新します。

- **OpenClaw** — コアパッケージ。検証済みの固定バージョンに保たれます（現在は2026.9.8 — 常に最新リリースとは限りません）
- **code-server** — ブラウザIDE
- **OpenCode** — AIコーディングアシスタント
- **AI CLIツール** — Claude Code、Gemini CLI、Codex CLI（Termux）
- **Androidパッチ** — 本プロジェクトの互換性パッチ

最新の状態のコンポーネントはスキップされます。インストールされていないコンポーネントには触れないため、すでに端末上にあるものだけが更新対象になります。何度実行しても安全です。

アップデート中にゲートウェイが動作していた場合は、更新後に再起動してください（停止して `openclaw gateway` を再実行するか、アプリを再起動します）。そうすると更新されたランタイムが反映されます。

**固定バージョンが変わるアップデート**（例: Script v1.2.0 でNode.js 24.21.0とOpenClaw 2026.9.8に変わる場合）では、`oa --update` が次の安全策を追加で実行します。

- 空き容量が2000MB必要です。不足している場合は、何も変更する前に停止し、必要量と残量を表示します。
- OpenClawゲートウェイが動作中の場合は、何も変更せずに停止します。ゲートウェイを停止（Ctrl+C）してから `oa --update` をもう一度実行してください。
- 先に `~/.openclaw-android/backup/pre-update/` にデータのバックアップを作成します（保持するのは最新の3件のみ）。
- インストール後にOpenClawの設定とデータの状態を確認します。データ移行が必要で、かつ今回の実行でバックアップを作成した場合に限り、`openclaw doctor --fix` を1回自動実行します（最大2分）。失敗した場合は、バックアップの場所と手動の手順（ゲートウェイを停止して `openclaw doctor --fix` を実行）を表示し、失敗として終了します。

自動移行をオフにするには `OA_SKIP_AUTO_DOCTOR=1`、自動バックアップをスキップするには `OA_SKIP_PRE_UPDATE_BACKUP=1` を設定してください。

`oa --update` は、Node.jsやOpenClawをより低いバージョンに下げることはありません（例: リリース直後に古いファイルが配信された場合）。意図的に下げるには、`OA_ALLOW_OPENCLAW_DOWNGRADE=1` と `OA_ALLOW_NODE_DOWNGRADE=1` を両方設定する必要があります。`oa --restore` はデータだけを戻し、プログラムは戻しません。

**注意**: `openclaw update`（および `openclaw --update`）は意図的にブロックされています。ガードによってOpenClawは本プロジェクトが検証したバージョンに固定されます。ゲートウェイの "update available" 通知はオフになっています（`update.checkOnStart=false`）。自分で再びオンにした場合は `update available … Run: openclaw update` のようなメッセージが表示されることがありますが、そのコマンドはブロックされるため、代わりに `oa --update` を使ってください。`openclaw update status`（読み取り専用）は引き続き利用できます。OpenClaw 2026.9.8 は、ダッシュボードのUpdateボタン、エージェントのgatewayツール、`openclaw gateway call update.run` から独自のアップデートを開始できます。これらの経路は `openclaw` コマンドのガードを通らないため、Node.jsラッパーがOpenClaw自身のアップデートもブロックします（`[BLOCKED]`）。OpenClawのメッセージが `openclaw update --yes` や `openclaw update repair` を勧めることがありますが、これらのコマンドもブロックされているため、`oa --update` を使ってください。

> `oa`コマンドが利用できない場合（古いインストール環境）は、curl経由で実行してください。
> ```bash
> curl -sL myopenclawhub.com/update | bash && source ~/.bashrc
> ```

## バックアップとリストア

OpenClaw標準のバックアップコマンド（`openclaw backup create`）は、Androidのアプリ専用ストレージでブロックされるハードリンクに依存しています。OpenClaw on Androidが適用するハードリンクパッチにより、コピーで代替できます（下記のハードリンクパッチの段落を参照）。`oa --backup`コマンドはOpenClawのデータフォルダ（`~/.openclaw`）全体を`tar`でまとめ、SQLiteデータベースは整合性のあるスナップショットとして保存します。その後、OpenClawが受け付ける場合は`openclaw backup verify`で、受け付けない場合は`tar`の構造チェックでアーカイブを確認します（OpenClaw 2026.9は絶対パスの`plugin-skills`リンクを含むアーカイブを拒否しますが、復元は正常に動作します）。

バックアップを作成するには:
```bash
oa --backup
```
バックアップは `~/.openclaw-android/backup/` にタイムスタンプ付きのファイル名（例: `2026-03-14T00-00-00.000Z-openclaw-backup.tar.gz`）で保存されます。`oa --backup ~/my-backups/` のように保存先を指定することもできます。各バックアップには設定、状態、会話履歴、ワークスペース、エージェントが含まれます（ログ、一時ファイル、プラグインの依存フォルダは除く）。

> **バックアップは非公開で保管してください。** バックアップにはAPIキーとログイン認証情報が含まれます。ファイルは本人だけが読めるように作成されます（`/sdcard` などの共有ストレージでは無効になる場合があります）。公開の場所で共有・アップロードしないでください。
>
> **Clawアプリ:** `oa --backup` が `backup.sh not found` と表示される場合（この機能の追加前にインストールしたアプリ）は、`oa --update` を一度実行してください。

固定バージョンが変わるアップデートの前には、`oa --update` が `~/.openclaw-android/backup/pre-update/` にバックアップを自動作成します（最新の3件を保持）。[アップデート](#アップデート)を参照してください。

バックアップから復元するには:
```bash
oa --restore
```
このコマンドはデフォルトのバックアップディレクトリから利用可能なバックアップを一覧表示します（`pre-update/` のバックアップも表示されます）。復元したいバックアップの番号を選ぶだけで、バックアップマニフェストからプラットフォームを自動検出し、この端末の `~/.openclaw/` に復元します（別の端末で作成したバックアップも復元できます）。先にOpenClawゲートウェイを停止してください。ゲートウェイが動作中は復元を拒否します。上書きする前に、現在のデータを `~/.openclaw-android/backup/pre-restore/` に安全バックアップとして保存します。バックアップ後に作成されたファイルは残ります。確認プロンプトが表示されます。バックアップに記録されたOpenClawのバージョンがインストール済みのものより新しい場合は、警告して確認を求めます（デフォルトはNo）。より古いバージョンのバックアップの場合は、復元後に `openclaw doctor --fix` を実行するよう案内します。`oa --restore` はデータだけを戻し、プログラムは戻しません。安全バックアップを保存できない場合（空き容量不足など）は、何も変更せずに復元を中止します。空き容量を確保して再実行するか、安全バックアップなしで復元するには `oa --restore --force-no-safety` を実行してください。

**別の環境から移したデータ。** Clawアプリ↔Termux、デバッグ版↔リリース版のアプリの間で移した場合や、別の端末のバックアップを復元した場合、OpenClawのデータの中に別のアプリのホームパス（`/data/data/<別のパッケージ>/files/home/...`）が残ることがあります。Androidは他のアプリのフォルダへのアクセスに権限エラー（EACCES）を返し、OpenClaw 2026.9のデータ移行はこのパスで止まります。`oa --update`（設定確認の直前）とすべての `oa --restore`（同じ環境への復元を含む）は、OpenClawの状態データベースにあるエージェントデータベースの登録行と古いlease行、および `openclaw.json` のエージェントの `workspace`・`agentDir` の値にあるこのようなパスを修復します。修復の前に、`~/.openclaw/state/openclaw.sqlite.oa-before-repair-<日時>` と `~/.openclaw/openclaw.json.oa-before-repair-<日時>` というコピーを残します（最新の3つを保持）。自動で修復できない値がある場合は警告が表示されるので、`openclaw.json` の該当するパスを現在のホーム（`$HOME`）の下に手動で直してください。[トラブルシューティングガイド](docs/troubleshooting.md)を参照してください。

## トラブルシューティング

詳しい解決方法は[トラブルシューティングガイド](docs/troubleshooting.md)を参照してください。

## パフォーマンス

`openclaw status` などのCLIコマンドはPC上での動作よりも遅く感じられることがあります。これはコマンド実行のたびに多数のファイルを読み込む必要があり、スマートフォンのストレージはPCより遅く、Androidのセキュリティ処理によるオーバーヘッドも加わるためです。

ただし、**ゲートウェイが起動した後はその差はなくなります**。プロセスはメモリ上に常駐するためファイル再読み込みは不要で、AIの応答処理は外部サーバーで行われるため、PCと同じ速度で動作します。

## Android上でのローカルLLM

OpenClawは [node-llama-cpp](https://github.com/withcatai/node-llama-cpp) によるローカルLLM推論をサポートしています。ビルド済みネイティブバイナリ（`@node-llama-cpp/linux-arm64`）はインストールに同梱されており、glibc環境で正常にロードされます — つまり**スマートフォン上でローカルLLMは技術的には動作します**。

ただし、現実的には以下の制約があります。

| 制約 | 詳細 |
|------|------|
| RAM | GGUFモデルは少なくとも2〜4GBの空きメモリが必要（7Bモデル、Q4量子化）。スマートフォンのRAMはAndroidや他のアプリと共有 |
| ストレージ | モデルファイルは4GBから70GB以上に及ぶ。端末のストレージはすぐ埋まる |
| 速度 | ARM上のCPUのみによる推論は非常に遅い。AndroidではllamaのGPUオフロードがサポートされていない |
| 用途 | OpenClawは主にクラウドLLM API（OpenAI、Geminiなど）にルーティングし、PCと同じ速度で応答します。ローカル推論はあくまで補助的な機能 |

実験用途であればTinyLlama 1.1B（Q4、約670MB）のような小型モデルなら端末上で動作します。実用にはクラウドLLMプロバイダの利用を推奨します。

> **なぜ `--ignore-scripts` ?** インストーラーは `npm install -g openclaw@2026.9.8 --ignore-scripts`（固定バージョン）を使います。これは、node-llama-cppのpostinstallスクリプトがcmake経由でllama.cppをソースからコンパイルしようとし、スマートフォンでは30分以上かかるうえツールチェインの非互換で失敗するためです。ビルド済みバイナリはこのコンパイルなしで動作するため、postinstallは安全にスキップできます。

<details>
<summary>開発者向けの技術ドキュメント</summary>

## インストールされるコンポーネント

インストーラーは複数のパッケージマネージャを横断してインフラ、プラットフォームパッケージ、オプションツールをセットアップします。コアインフラとプラットフォーム依存関係は自動でインストールされ、オプションツールはインストール時に個別にプロンプトされます。

### コアインフラ

| コンポーネント | 役割 | インストール方法 |
|-----------|------|----------------|
| git | バージョン管理、npm gitディペンデンシ | `pkg install` |

### エージェントプラットフォームのランタイム依存

これらはプラットフォームの `config.env` フラグで制御されます。OpenClawではすべてインストールされます。

| コンポーネント | 役割 | インストール方法 |
|-----------|------|----------------|
| [pacman](https://wiki.archlinux.org/title/Pacman) | glibcパッケージのパッケージマネージャ | `pkg install` |
| [glibc-runner](https://github.com/termux-pacman/glibc-packages) | glibc動的リンカー — Android上で標準Linuxバイナリを実行可能に | `pacman -Sy` |
| [Node.js](https://nodejs.org/) 24.21.0 (linux-arm64) | OpenClaw用JavaScriptランタイム。sha256で検証された固定バージョン | nodejs.orgから直接ダウンロード |
| python | ネイティブC/C++アドオンのビルドスクリプト（node-gyp） | `pkg install` |
| make | ネイティブモジュール用Makefile実行 | `pkg install` |
| cmake | CMakeベースのネイティブモジュールビルド | `pkg install` |
| clang | ネイティブモジュール用C/C++コンパイラ | `pkg install` |
| binutils | ネイティブビルド向けバイナリユーティリティ（llvm-ar） | `pkg install` |

### OpenClawプラットフォーム

| コンポーネント | 役割 | インストール方法 |
|-----------|------|----------------|
| [OpenClaw](https://github.com/openclaw/openclaw) | AIエージェントプラットフォーム（コア）。検証済みバージョン（2026.9.8）に固定 | `npm install -g` |
| [clawdhub](https://github.com/AidanPark/clawdhub) | OpenClaw向けスキルマネージャ | `npm install -g` |
| [PyYAML](https://pyyaml.org/) | `.skill` パッケージング用YAMLパーサー | `pip install` |

### オプションツール（インストール時にプロンプト）

各ツールは個別のY/nプロンプトで提示されます。インストール対象は自由に選択できます。オプションツールのインストールに失敗しても、その旨が報告されてスキップされるだけで、残りのセットアップが中断されることはありません。

| コンポーネント | 役割 | インストール方法 |
|-----------|------|----------------|
| [tmux](https://github.com/tmux/tmux) | バックグラウンドセッション用ターミナルマルチプレクサ | `pkg install` |
| [ttyd](https://github.com/tsl0922/ttyd) | Webターミナル — ブラウザからTermuxへアクセス | `pkg install` |
| [dufs](https://github.com/sigoden/dufs) | ブラウザベースのファイル転送向けHTTP/WebDAVファイルサーバ | `pkg install` |
| [android-tools](https://developer.android.com/tools/adb) | Phantom Process Killer無効化用のADB | `pkg install` |
| [code-server](https://github.com/coder/code-server) | ブラウザベースのVS Code IDE。4.117.0に固定（この環境で動作を確認したバージョン） | GitHubから直接ダウンロード |
| [OpenCode](https://opencode.ai/) | AIコーディングアシスタント（TUI）。依存として [Bun](https://bun.sh/) と [proot](https://proot-me.github.io/) を自動インストール | `bun install -g` |
| [Chromium](https://www.chromium.org/) | OpenClaw向けブラウザ自動化（約400MB） | カスタムインストールスクリプト |
| [Playwright](https://playwright.dev/) | ブラウザ自動化ライブラリ（Chromium必須）。`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` を自動設定 | カスタムインストールスクリプト |
| [Claude Code](https://github.com/anthropics/claude-code) (Anthropic) | AI CLIツール — ネイティブバイナリは同梱のglibcローダー経由で起動され、`npm install -g` で更新された後はnpmラッパーがそのランチャーを復元します（Clawアプリで確認済み） | `npm install -g` |
| [Gemini CLI](https://github.com/google-gemini/gemini-cli) (Google) | AI CLIツール | `npm install -g` |
| [Codex CLI](https://github.com/DioNanos/codex-termux) (OpenAI CodexのTermuxフォーク) | AI CLIツール | `npm install -g` |

## プロジェクト構成

```
openclaw-android/
├── bootstrap.sh                # curl | bash ワンライナーインストーラー（ダウンローダー）
├── install.sh                  # プラットフォーム対応インストーラー（エントリポイント）
├── oa.sh                       # 統合CLI（$PREFIX/bin/oa としてインストール）
├── post-setup.sh               # Claw アプリのpost-bootstrapセットアップ（OTA配信）
├── update.sh                   # 軽量ラッパー（update-core.sh をダウンロード・実行）
├── update-core.sh              # 既存インストール向けの軽量アップデーター
├── uninstall.sh                # クリーン削除（オーケストレーター）
├── patches/
│   ├── glibc-compat.js        # Node.jsランタイムパッチ（os.cpus、networkInterfaces）
│   ├── argon2-stub.js          # argon2ネイティブモジュール用JSスタブ（code-server）
│   ├── termux-compat.h         # Bionicネイティブビルド用Cヘッダ（sharp）
│   ├── spawn.h                 # POSIX spawnスタブヘッダ
│   ├── systemctl               # Termux用systemdスタブ
│   ├── apply-patches.sh        # レガシーパッチオーケストレーター（v1.0.2互換）
│   └── patch-paths.sh          # レガシーパス修正（v1.0.2互換）
├── scripts/
│   ├── lib.sh                  # 共有関数ライブラリ（色、プラットフォーム検出、プロンプト）
│   ├── check-env.sh            # 起動前環境チェック
│   ├── install-infra-deps.sh   # コアインフラパッケージ（L1）
│   ├── install-glibc.sh        # glibc-runnerインストール（L2 条件付き）
│   ├── install-nodejs.sh       # Node.js glibcラッパーのインストール（L2 条件付き）
│   ├── install-build-tools.sh  # ネイティブモジュール用ビルドツール（L2 条件付き）
│   ├── backup.sh               # OpenClawデータのバックアップ・復元（oa --backup/--restore）
│   ├── build-sharp.sh          # sharpネイティブモジュール（画像処理）のビルド
│   ├── install-chromium.sh     # ブラウザ自動化用Chromiumのインストール
│   ├── install-playwright.sh   # Playwrightブラウザ自動化ライブラリのインストール
│   ├── install-code-server.sh  # code-server（ブラウザIDE）のインストール・更新
│   ├── install-opencode.sh     # OpenCodeのインストール
│   ├── setup-env.sh            # 環境変数設定
│   └── setup-paths.sh          # ディレクトリ作成とシンボリックリンク
├── platforms/
│   ├── openclaw/               # OpenClawプラットフォームプラグイン
│   │   ├── config.env          # プラットフォームメタデータと依存宣言
│   │   ├── env.sh              # プラットフォーム固有の環境変数
│   │   ├── install.sh          # プラットフォームパッケージのインストール（npm、パッチ、clawdhub）
│   │   ├── update.sh           # プラットフォームパッケージの更新
│   │   ├── openclaw-shim.sh    # `openclaw update` のバージョン固定ガードを書き込む
│   │   ├── uninstall.sh        # プラットフォームパッケージの削除
│   │   ├── status.sh           # プラットフォーム状態表示
│   │   ├── verify.sh           # プラットフォーム検証チェック
│   │   └── patches/            # プラットフォーム固有のパッチ
│   │       ├── openclaw-apply-patches.sh
│   │       ├── openclaw-patch-paths.sh
│   │       ├── openclaw-patch-hardlink.sh  # OpenClaw向けのAndroidハードリンク/reflink代替処理
│   │       └── openclaw-build-sharp.sh
├── tests/
│   └── verify-install.sh       # インストール後検証（オーケストレーター + プラットフォーム）
└── docs/
    ├── disable-phantom-process-killer.md    # プロセスを生かし続けるためのガイド (EN)
    ├── disable-phantom-process-killer.ko.md # プロセスを生かし続けるためのガイド (KO)
    ├── termux-ssh-guide.md     # Termux SSHセットアップガイド (EN)
    ├── termux-ssh-guide.ko.md  # Termux SSHセットアップガイド (KO)
    ├── troubleshooting.md      # トラブルシューティングガイド (EN)
    ├── troubleshooting.ko.md   # トラブルシューティングガイド (KO)
    └── images/                 # スクリーンショットと画像
```

## アーキテクチャ

本プロジェクトは、プラットフォームに依存しないインフラとプラットフォーム固有コードを分離する**プラットフォームプラグイン構成**を採用しています。

```
┌─────────────────────────────────────────────────────────────┐
│  オーケストレーター (install.sh, update-core.sh, uninstall.sh) │
│  ── プラットフォーム非依存。config.env を読みデリゲート          │
├─────────────────────────────────────────────────────────────┤
│  共有スクリプト (scripts/)                                     │
│  ── L1: install-infra-deps.sh（常時）                         │
│  ── L2: install-glibc.sh, install-nodejs.sh,                 │
│         install-build-tools.sh（config.env 条件付き）          │
│  ── L3: オプションツール（ユーザー選択）                         │
├─────────────────────────────────────────────────────────────┤
│  プラットフォームプラグイン (platforms/<name>/)                 │
│  ── config.env: 依存宣言（PLATFORM_NEEDS_*）                  │
│  ── install.sh / update.sh / uninstall.sh / ...              │
└─────────────────────────────────────────────────────────────┘
```

**依存関係のレイヤー:**

| レイヤー | スコープ | 例 | 制御元 |
|-------|-------|----------|---------------|
| L1 | インフラ（常時インストール） | git、`pkg update` | オーケストレーター |
| L2 | プラットフォームランタイム（条件付き） | glibc、Node.js、ビルドツール | `config.env` フラグ |
| L3 | オプションツール（ユーザー選択） | tmux、code-server、AI CLI | ユーザープロンプト |

各プラットフォームは `config.env` でL2依存を宣言します。

```bash
# platforms/openclaw/config.env
PLATFORM_NEEDS_GLIBC=true
PLATFORM_NEEDS_NODEJS=true
PLATFORM_NEEDS_BUILD_TOOLS=true
```

オーケストレーターはこれらのフラグを読み、対応するインストールスクリプトを条件付きで実行します。特定の依存を必要としないプラットフォームでは該当フラグを `false` に設定するだけで、重い依存をまるごとスキップできます。

## 詳細なインストールフロー

`bash install.sh` を実行すると、次の8ステップが順に実行されます。

### [1/8] 環境チェック — `scripts/check-env.sh`

インストール開始前に現在の環境が適切かを検証します。

- **Termux検出**: `$PREFIX` 環境変数を確認。Termux外では即終了
- **アーキテクチャチェック**: `uname -m` でCPUアーキテクチャを確認（aarch64推奨、armv7lサポート、x86_64はエミュレータ扱い）
- **ディスク容量**: `$PREFIX` パーティションに最低2000MBの空きを要求。不足時は必要量と残量を表示してエラー
- **既存インストール**: `openclaw` コマンドが既にあれば現バージョンを表示し、再インストール／アップグレードである旨を案内
- **Node.js事前チェック**: 既にNode.jsが入っていればバージョンを表示し、24未満の場合は警告
- **Phantom Process Killer**（Android 12+）: Phantom Process Killerに関する案内を [無効化ガイド](docs/disable-phantom-process-killer.md) のリンク付きで表示

### [2/8] プラットフォーム選択

インストールするプラットフォームを選択します。現在は `openclaw` にハードコードされています。将来的に複数プラットフォームが利用可能になった際は選択UIを表示します。

`scripts/lib.sh` の `load_platform_config()` を介してプラットフォームの `config.env` を読み込み、すべての `PLATFORM_*` 変数を以降のステップで利用できるよう公開します。

### [3/8] オプションツール選択 (L3)

オプションツール用に11個のY/nプロンプト（`/dev/tty` 経由）を提示します。

- tmux、ttyd、dufs、android-tools
- Chromium、Playwright
- code-server、OpenCode
- Claude Code、Gemini CLI、Codex CLI（Termux）

選択はインストール開始前にまとめて行います。これにより一度の判断で済み、インストール中は端末から離れていても問題ありません。

### [4/8] コアインフラ (L1) — `scripts/install-infra-deps.sh` + `scripts/setup-paths.sh`

プラットフォーム選択にかかわらず常に実行されます。

**install-infra-deps.sh:**
- `pkg update -y && pkg upgrade -y` でパッケージを更新・アップグレード
- `git` をインストール（npm gitディペンデンシとリポジトリクローンに必要）

**setup-paths.sh:**
- `$PREFIX/tmp` と `$HOME/.openclaw-android/patches` ディレクトリを作成
- 標準Linuxパス（`/bin/sh`、`/usr/bin/env`、`/tmp`）からTermuxパスへのマッピングを表示

### [5/8] プラットフォームランタイム依存 (L2)

プラットフォームの `config.env` フラグに基づき、ランタイム依存を条件付きでインストールします。

| フラグ | スクリプト | インストール内容 |
|------|--------|-----------------|
| `PLATFORM_NEEDS_GLIBC=true` | `scripts/install-glibc.sh` | pacman、glibc-runner（`ld-linux-aarch64.so.1` を提供） |
| `PLATFORM_NEEDS_NODEJS=true` | `scripts/install-nodejs.sh` | Node.js 24.21.0 linux-arm64（sha256検証済み）、grun形式のラッパースクリプト |
| `PLATFORM_NEEDS_BUILD_TOOLS=true` | `scripts/install-build-tools.sh` | python、make、cmake、clang、binutils |

各スクリプトは事前チェックと冪等性を備えており（インストール済みならスキップ）、自己完結しています。

**Node.jsラッパーの既定値:** OpenClaw 2026.9.x はNode.js 24.16以上を必要とします。OpenClawのファイル安全モジュール（`@openclaw/fs-safe`）のネイティブヘルパーは `openat2` システムコールを使いますが、Androidのアプリ用seccompポリシーがこの呼び出しをSIGSYS（Bad system call）で終了させます。そのためラッパーは既定で `FS_SAFE_TEST_NO_OPENAT2=1` を設定し、ネイティブヘルパーは維持したまま `openat2` を使わせないようにします（値は正確に `1` である必要があります）。`FS_SAFE_NATIVE_MODE=off` を設定するとネイティブヘルパー自体をオフにできますが、その場合OpenClawの一部のデータ移行が拒否されることがあります。ラッパーは `OPENCLAW_NO_AUTO_UPDATE=1` も既定で設定し、OpenClaw自身のアップデートをブロックします（[アップデート](#アップデート)を参照）。

**ハードリンクパッチ:** Androidは、アプリのデータ領域でハードリンクとreflink（`FICLONE`）によるコピーをブロックします（アプリとTermuxの両方で、エラーは `EACCES`）。OpenClaw 2026.9.8 はまずハードリンクを試し、「サポートされていない」エラーの場合にだけコピーへ切り替えますが、`EACCES` はサポート外とは見なされません。また会話履歴を移行したあと、元のファイルを保管フォルダへ移すときはハードリンクしか使いません。そのままでは、7.35 からアップグレードしたユーザーのデータ移行が失敗します。OpenClaw on AndroidはOpenClawをインストール・アップデートするたびに（アプリのインストール、Termuxのインストール、`oa --update`、そして `npm install -g openclaw@<固定バージョン>` による再インストール）、`platforms/openclaw/patches/openclaw-patch-hardlink.sh` でOpenClawのこの部分を修正します。`EACCES` をコピーへ切り替える対象に含め、保管フォルダへの移動はハードリンクが拒否された場合に同じファイルシステム内のrenameで行います。これにより `openclaw backup create` もハードリンクの代わりにコピーを使えます。`oa --status` には `Hard-link patch: applied (…)` または `not complete (…)` の行が表示されます。会話履歴のある 7.35 のユーザーが固定バージョンの変わるアップデートを行う場合、インストールされるOpenClawにこのパッチが合うかを先に確認し、合わなければ何も変更せずに停止します（[トラブルシューティング](docs/troubleshooting.md)（英語）を参照）。

**既知の制限:** OpenClawのデスクトップ自動化ツール（`@trycua/cua-driver`）は、Android 10以下（API 29以下）では必要なシステムコールの一部がブロックされており、動作しない場合があります。

### [6/8] プラットフォームパッケージのインストール (L2) — `platforms/<platform>/install.sh`

プラットフォーム自身のインストールスクリプトに委譲します。OpenClawの場合は次を実行します。

1. `CPATH` を設定（ネイティブモジュールビルドに必要なglib-2.0ヘッダ用）
2. PyYAMLをpipでインストール（`.skill` パッケージング用）
3. `glibc-compat.js` を `~/.openclaw-android/patches/` にコピー（Node.jsラッパーはNode.jsと一緒にインストールされた `~/.openclaw-android/lib/` 内の自前のコピーを読み込みます — アプリがそちらを上書きすることはありません）
4. `systemctl` スタブを `$PREFIX/bin/` にインストール
5. `npm install -g openclaw@2026.9.8 --ignore-scripts` を実行（`config.env` で固定されたバージョン）
6. OpenClaw自身のpostinstallスクリプト（`postinstall-bundled-plugins.mjs`）を実行（`--ignore-scripts` ではスキップされるため）
7. `openclaw-apply-patches.sh` でプラットフォーム固有のパッチを適用
8. 固定バージョンが維持されるよう、`openclaw update` ガード（`openclaw-shim.sh`）をインストール
9. 必要に応じて `clawdhub`（スキルマネージャ）と `undici` 依存をインストール
10. ゲートウェイの "update available" 通知をオフにする（`openclaw config set update.checkOnStart false`）。すでに自分で値を設定している場合はそのまま

**[6.5] 環境変数 + CLI + マーカー:**

プラットフォームインストール後、オーケストレーターは次を行います。

- `setup-env.sh` を実行して `.bashrc` に環境変数ブロックを書き込み
- プラットフォームの `env.sh` を評価し、プラットフォーム固有変数を反映
- プラットフォームマーカーファイル（`~/.openclaw-android/.platform`）を書き込み
- `oa` CLIと `oaupdate` ラッパーを `$PREFIX/bin/` にインストール
- アップデーターやアンインストーラーが利用するため、`lib.sh`、`setup-env.sh`、プラットフォームディレクトリを `~/.openclaw-android/` にコピー

### [7/8] オプションツールのインストール (L3)

ステップ3で選択されたツールをインストールします。

- **Termuxパッケージ**: tmux、ttyd、dufs、android-tools — `pkg install` でインストール
- **code-server**: ブラウザベースのVS Code IDE。Termux固有の回避策（同梱nodeの差し替え、argon2のパッチ、ハードリンク失敗の処理）を実施
- **OpenCode**: Bunスタンドアロンバイナリ向けにproot + ld.soの連結方式でインストール
- **Chromium**: OpenClaw向けブラウザ自動化サポート（約400MB）
- **Playwright**: ブラウザ自動化ライブラリ（npmで `playwright-core`）。`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` および `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` を自動設定。Chromiumがなければ自動インストール
- **AI CLIツール**: Claude Code、Gemini CLI、Codex CLI（Termux） — `npm install -g` でインストール。失敗した場合はセットアップを中断せず、報告してスキップします（Claude Codeはglibcローダー経由で動作します。Codex CLIはパッケージが `os: android` を宣言している一方、このNode.jsは `linux` を報告するため、Clawアプリでは `--force` でインストールします。Termuxでは警告付きでスキップされる場合があります）

### [8/8] 検証 — `tests/verify-install.sh`

2階層の検証を実行します。

**オーケストレーター検証（FAILレベル）:**

| チェック項目 | PASS条件 |
|------------|---------------|
| Node.jsバージョン | `node -v` が固定バージョン（`platforms/openclaw/config.env`）と一致 |
| npm | `npm` コマンドが存在 |
| TMPDIR | 環境変数が設定済み |
| OA_GLIBC | `1` に設定 |
| glibc-compat.js | `~/.openclaw-android/lib/`（Node.jsラッパーが読み込むコピー。なければ `patches/` にフォールバック）に存在 |
| .glibc-arch | マーカーファイルが存在 |
| glibc動的リンカー | `ld-linux-aarch64.so.1` が存在 |
| glibc nodeラッパー | `~/.openclaw-android/bin/node` のラッパースクリプト |
| ディレクトリ | `~/.openclaw-android`、`$PREFIX/tmp` が存在 |
| .bashrc | 環境変数ブロックを含む |

**オーケストレーター検証（WARNレベル、非クリティカル）:**

| チェック項目 | PASS条件 |
|------------|---------------|
| code-server | `code-server --version` が成功 |
| opencode | `opencode` コマンドが利用可能 |

**プラットフォーム検証** — `platforms/<platform>/verify.sh` に委譲:

| チェック項目 | PASS条件 |
|------------|---------------|
| openclaw | `openclaw --version` が成功 |
| openclaw package version | `config.env` の固定バージョンと一致 |
| openclaw update guard | `$PREFIX/bin/openclaw` にインストール済み |
| CONTAINER | `1` に設定 |
| clawdhub | コマンドが利用可能 |
| ~/.openclaw | ディレクトリが存在 |

すべてのFAILレベルがパスすればPASSED。FAILがあれば再インストール手順を表示します。WARN項目は失敗扱いになりません。

## 軽量アップデーターのフロー — `oa --update`

`oa --update`（または後方互換のため `oaupdate`）を実行すると、最新リリースのtarballをGitHubからダウンロードし、次の5ステップを実行します。

### [1/5] 起動前チェック

更新の前提条件を検証します。

- `$PREFIX` の存在を確認（Termux環境）
- `curl` の存在を確認
- `~/.openclaw-android/.platform` マーカーからプラットフォームを検出
- アーキテクチャを検出: glibc（`.glibc-arch` マーカー）またはBionic（レガシー）
- 必要に応じて旧ディレクトリ名を移行（`.openclaw-lite` → `.openclaw-android` — レガシー互換）
- **Phantom Process Killer**（Android 12+）: [無効化ガイド](docs/disable-phantom-process-killer.md) のリンク付きで案内を表示

### [2/5] 最新リリースのダウンロード

リポジトリ全体のtarballをGitHubからダウンロードし、一時ディレクトリに展開します。必要なファイルがすべて存在することを検証します。

- `scripts/lib.sh`
- `scripts/setup-env.sh`
- `platforms/<platform>/config.env`
- `platforms/<platform>/update.sh`

### [3/5] コアインフラの更新

今回の更新で固定されたNode.jsまたはOpenClawのバージョンが変わる場合は、何かを変更する前に3つの安全策が先に実行されます。空き容量の確認（2000MB）、OpenClawゲートウェイが動作中でないことの確認、`~/.openclaw-android/backup/pre-update/` へのデータバックアップです（[アップデート](#アップデート)を参照）。

アップデーター、アンインストーラー、CLIで共有されるファイルを更新します。

- アップデートスクリプト自身が最新かを確認します。古いキャッシュのコピーが届いた場合（リリース直後の数分間に起こりえます）は、何も変更せずに停止します。数分後に `oa --update` をもう一度実行してください
- 最新のプラットフォームディレクトリを `~/.openclaw-android/platforms/` にコピー
- `~/.openclaw-android/scripts/` の `lib.sh` と `setup-env.sh` を更新
- パッチファイル（`glibc-compat.js`、`argon2-stub.js`、`spawn.h`、`systemctl`）を更新
- `$PREFIX/bin/` の `oa` CLIと `oaupdate` ラッパーを更新
- `~/.openclaw-android/` の `uninstall.sh` を更新
- Bionicアーキテクチャを検出した場合はglibcへの自動マイグレーションを実施
- Node.jsを固定バージョンに収束させる（sha256検証付きダウンロード、アトミックな入れ替え — 何か失敗した場合は以前のインストールが復元されます）。`OA_ALLOW_NODE_DOWNGRADE=1` を設定しない限り、Node.jsをより低いバージョンに下げることはありません
- `setup-env.sh` を実行して `.bashrc` の環境ブロックを更新
- **Nodeゲート**: この時点でNode.jsが固定バージョンになっていなければ、アップデートはここで停止し、OpenClaw（[4/5]）には手を付けません。`oa --update` をもう一度実行してください

### [4/5] プラットフォーム更新

`platforms/<platform>/update.sh` に委譲します。OpenClawの場合は次を実行します。

- 固定バージョンが変わった場合: インストール後にOpenClawの設定とデータの状態を確認し、データ移行が必要で今回の実行でバックアップを作成していれば `openclaw doctor --fix` を1回実行（[アップデート](#アップデート)を参照）
- インストール後、設定の確認の直前に、他のアプリのフォルダを指すパスを修復（[バックアップとリストア](#バックアップとリストア)を参照）
- `binutils` をインストール（ネイティブモジュールのビルド用）
- Node.jsの固定を安全策として再確認（多層防御 — [3/5] でもすでにゲートされています）
- `openclaw` を固定バージョンに収束させる — インストール済みバージョンがより低い場合に固定バージョンをインストールする。`OA_ALLOW_OPENCLAW_DOWNGRADE=1` を設定しない限り、OpenClawをより低いバージョンに下げることはありません
- プラットフォーム固有のパッチを再適用
- `openclaw update` ガード（`openclaw-shim.sh`）を更新し、`openclaw update`/`--update` のブロックを維持（Node.jsラッパーはOpenClaw自身のアップデート経路もブロックします）
- `clawdhub`（スキルマネージャ）を更新／インストール
- 必要に応じてclawdhub用の `undici` をインストール（Node.js v24+）
- ゲートウェイの "update available" 通知をオフにする（`update.checkOnStart=false`）。自分で値を設定していない場合のみ
- 必要に応じてスキルを `~/skills/` から `~/.openclaw/workspace/skills/` へ移行
- 不足していればPyYAMLをインストール

### [5/5] オプションツールの更新

既にインストールされているツールを更新します。

- **code-server**: `install-code-server.sh` を更新モードで実行 — code-serverを固定の4.117.0に保ちます（以前により新しいバージョンをインストールしていた場合は戻されます）。未インストールならスキップ
- **OpenCode**: インストール済みなら更新、未インストールならインストールを案内。glibcアーキテクチャ必須
- **Chromium**: インストール済みなら更新。未インストールならスキップ
- **AI CLIツール**（Claude Code、Gemini CLI、Codex CLI（Termux））: インストール済みのバージョンと最新npmバージョンを比較し、必要に応じて更新。未インストールのツールには更新を提案しません

</details>

## ライセンス

MIT
