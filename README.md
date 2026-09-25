# Minecraft 1.7.10 Local AI Companion

Minecraft側を薄いゲームI/Oアダプタとし、AI処理を外部Agent Daemonへ分離するプロジェクト。仕様は[init.md](init.md)を参照。

## 開発環境の準備状況

2026-09-26時点。空MODのビルド、開発クライアント起動、Prismでのjarロードと初期化ログを確認済み。Prismの音声処理には下記の既知の問題がある。

| 項目 | 状態 |
| --- | --- |
| OS | Windows / PowerShell |
| 開発用JDK | Eclipse Temurin 8u504-b01、Windows x64、プロジェクト内に展開済み |
| JDK動作確認 | `java -version` / `javac -version` 成功（1.8.0_504） |
| Gradle | 5.6.4、公式wrapperを同梱、配布ZIPのSHA-256を固定 |
| ForgeGradle | anatawa12版 1.2-1.1.1（旧ForgeGradleの互換性修正版） |
| Forge / mappings | 1.7.10-10.13.4.1614-1.7.10 / MCP stable_12 |
| 空MOD / 生成jar | `setupDecompWorkspace build` 成功、`clean build` も成功 |
| dev client | `runClient` で起動、MOD初期化・全MODロード完了・音声初期化ログを確認。OpenALFixなし |
| Prismでの生成jar | コピーしたjarのハッシュ一致、MOD初期化・全6MODロード完了ログを確認。タイトル画面は利用者確認済み |
| Prism Launcher | 導入済み。既存の `1.7.10-mod-basic` を確認 |
| 既存インスタンス | Minecraft 1.7.10 / Forge 10.13.4.1614 / Temurin JRE 8u504。OpenALFix導入後、タイトル画面・ワールド入場を利用者が確認済み |

## JDK 8

システムのJava設定を変更せず、開発用JDKを `.tools/jdk8u504-b01` に配置する。`.tools/` はGit管理対象外。

- 配布元: [Eclipse Adoptium / Temurin 8u504-b01](https://github.com/adoptium/temurin8-binaries/releases/tag/jdk8u504-b01)
- ファイル: `OpenJDK8U-jdk_x64_windows_hotspot_8u504b01.zip`
- SHA-256: `ea43d46ede95b51e44a12c66711706cddc762e0a766c54bccea18954e902b2aa`
- 取得したZIPのSHA-256が公式APIの値と一致することを確認済み。

新規cloneではリポジトリ直下のPowerShellで次を実行する。スクリプトが同じZIPを取得し、ハッシュを検証して `.tools/` に展開する。

```powershell
.\scripts\setup-jdk.ps1
```

通常は後述の `forge.ps1` が開発用JDKを選択する。手動で選択する場合は、現在のPowerShellセッションで次を実行する。

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk8u504-b01').Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
javac -version
```

## ビルドと開発クライアント

初回はインターネット接続が必要。リポジトリ直下から実行する。

```powershell
.\scripts\forge.ps1 setupDecompWorkspace build
```

2回目以降のビルド（先に開発クライアントを終了する）:

```powershell
.\scripts\forge.ps1 build
```

開発クライアント:

```powershell
.\scripts\forge.ps1 runClient
```

`forge.ps1` はJDK 8とプロジェクト内のGradleキャッシュを選択して、`forge-mod/gradlew.bat -p forge-mod --no-daemon --console plain` に引数を渡す。終了時には元の環境変数へ戻す。

成果物は `forge-mod/build/libs/mc-ai-companion-0.0.1.jar`。開発クライアントのゲームディレクトリは `forge-mod/run`。
ログ中の `MC AI Companion initialized (Phase 0)` がこのMODの初期化メッセージ。

旧ForgeGradleの配布先・Gradle互換性の問題を避けるため、[anatawa12のForgeGradle 1.2修正版](https://github.com/anatawa12/ForgeGradle-1.2)を利用する。バージョンは固定し、動的な `+` 指定は使わない。

## Prism Launcherへのインストール

1. 対象のゲームを終了する。
2. Prism Launcherで `1.7.10-mod-basic` のフォルダーを開く。
3. `minecraft/mods/` に生成jarをコピーする。OpenALFixも残しておく。
4. インスタンスを起動し、タイトル画面とMods一覧の `MC AI Companion` を確認する。
5. `minecraft/logs/fml-client-latest.log` で上記の初期化メッセージを確認する。開発起動時はコンソールにも出力される。

Prismの起動確認にはMinecraft Java Editionを利用できるアカウントが必要。ログイン操作は利用者が行う。

## このPCで必要だった起動時の対処

利用者によると、既存の `1.7.10-mod-basic` はOpenALFix導入前には毎回クラッシュし、導入後はタイトル画面とワールドへの入場が可能になった。

- 配布元・説明: [icychkn/OpenALFix](https://github.com/icychkn/OpenALFix)
- 導入済みファイル: インスタンス配下の `minecraft/mods/openalfix-1.0.0.jar`
- 導入済みファイルのSHA-256: `d89bf417f91b70288947698ef8867dd176f6ddc46802df4e69fa14508f7507ba`（ローカルファイルの識別用。配布元との一致は未確認）

作者の説明では、ロード後にリソースを再読み込みし、サウンドシステムの初期化に伴うOpenAL contextのエラーを回避するMOD。導入前のクラッシュ原因をログで確定したわけではない。

このPCでPrismの検証環境を再作成する際はOpenALFixも含める。開発用クライアントは独立した環境であり、今回の起動ではOpenALFixなしで音声初期化まで成功した。

## 検証結果と既知の問題

- 初回セットアップとビルド: `BUILD SUCCESSFUL in 3m 36s`。
- 開発クライアント: 00:03:29に `MC AI Companion initialized (Phase 0)` と4MODロード完了を確認。
- Prism: 00:04:32に同じ初期化ログ、00:04:33に6MODロード完了を確認。既存のExcludeMobsとOpenALFixもロードされている。
- クリーン再ビルド: `BUILD SUCCESSFUL in 8s`。
- Prism再起動: 00:06:58に初期化ログ、00:06:59に6MODロード完了を確認。
- 初回素材取得中に同一アイコンの一時的な取得エラーが発生したが、完了後、インデックス内の全686エントリーについてファイルのSHA-1一致を確認した。
- 開発クライアント起動中はjarがロックされ、Windowsで `reobf` が失敗する。ゲームを終了してから再ビルドする。
- 開発起動時に旧Forgeの更新確認処理でJSON解析エラーが出たが、MODロードと音声初期化は完了した。
- PrismではOpenALFix導入済みでも音声処理の不安定さが残る。00:05:14に `UnsatisfiedLinkError: org.lwjgl.openal.AL10.nalGetSourcei(II)I` でクラッシュした。開発クライアントを終了してPrismのみ再起動した後もOpenAL contextのエラーを記録した。原因は未確定で、同時起動が原因とは断定しない。

このため、MODのビルド・ロード確認と、Prism環境の長時間安定動作確認は区別する。生成jar追加後のワールド内動作・長時間安定性は未確認。

実装はロード確認用の最小MODのみ。Daemon・LLM・Companion本体は未実装。
