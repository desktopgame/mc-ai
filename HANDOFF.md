# 再開用メモ — 2026-09-26

## 現在地

### 行動管理基盤の実装 — 2026-09-26

MOD `0.0.6` に、既存のfollow/stop/lookを使ったAI判断→検証→実行→結果通知を追加した。
下のPhase 0～5と中断時点の節は過去の記録。現在の操作と通信仕様はREADME冒頭と `protocol/action-lifecycle.md` を優先する。

- `!agent do follow / look` で目的を置換し、キャッシュを使ったAI判断後に実行する。
- `!agent stop` / `!agent do stop` はLLMを待たずに停止して旧判断を無効化する。手動follow/lookも旧AI目的を取り消す。
- session・goalRevision・actionIdを使い、両側で古い判断を拒否する。実行直前の個体・ディメンション・体力・距離等も確認する。
- Socialは独立した通信とFIFOを持ち、処理中1件＋待機4件・計2048文字。forget/再入場で旧返信を破棄する。
- 自然文のintent分類、予約実行、進捗を使った会話生成、新しいGame Actionsはまだ実装しない。
- 結果配送はメモリ上の有界キュー・最大3回の試行。再起動をまたぐ配送保証はない。

自動検証: Python **34件**、Java **19件**（計53件）が成功。Forgeビルド成功。
遅延推論中の取消、最新待機指示への置換、重複配送、旧結果の拒否、世代・個体変更、会話キュー上限を検証した。
実ゲームでAI判断による追従・行動中の会話・手動停止を利用者が確認した。
判断の実測は約1.8～3.1秒、会話は約2.1秒。継続的な性能保証ではない。
経路が見つからず停止したケースもログで確認。経路探索自体は既存のMinecraft標準処理を使用している。
判断中の取消後に勝手に動かないこと、約3秒の注視、連続した2発言への順番どおりの返答も利用者が確認した。
ログでも取消後の旧判断が実行されないことと、lookのrunning→succeededの結果通知を確認。今回の基盤実装と基本的な実ゲーム検証は完了。
新基盤の再接続・応答順逆転・重複配送・会話キュー上限・forgetによる旧返信拒否は自動テストまたはコード確認の範囲で、すべての異常系を実ゲームで再現したわけではない。

Prismへ0.0.6を導入し、0.0.5は `.jar.disabled` として保持した。配置jarと成果物のSHA-256一致確認済み。
Daemonは8767で起動。起動時PID36988は `.tools/lifecycle-daemon.pid` に記録した（停止前に実プロセスを照合すること）。
ログは `.tools/lifecycle-daemon.stderr.log` / `.tools/lifecycle-daemon.stdout.log`。ping/pong応答を確認。
実ゲーム確認用にPrismの `1.7.10-mod-basic` を起動した。
作業終了時点でMinecraftとDaemonは起動したまま。今回はコミットしていない。

追加した主要コード:

- `agent/src/goals.py`: 有界の目的管理、推論worker、世代・結果対応。
- `forge-mod/.../ActionBridge.java`: ゲームスレッドの実行権限、制御と結果の独立通信。
- `forge-mod/.../GoalState.java` / `ActionProtocol.java`: Minecraft非依存の世代管理と検証。
- `forge-mod/.../ConversationQueue.java`: 会話の有界FIFOと返信の世代。
- `agent/tests/test_goals.py` / `forge-mod/.../LifecycleTest.java`: 割り込み・遅延等の回帰テスト。

### 再開時の方針追記

利用者は次の作業として、既存のfollow/stop/lookをAI判断から実行する接続を選択した。
その実装に先立ち、考え中・行動中の発話を扱える基盤を整える方針となった。
仕様は `init.md` 第10.1節を参照。会話と行動の独立、会話と目的の世代分離、古い判断の拒否、即時停止、上限付き受付、action結果の対応付けを定義した。
高度な自然文の割り込み分類や指示の予約は後回し。まず明示的な指示で基盤を成立させる。
この追記時点では仕様変更のみで、下記Phase 5の実装にこの基盤はまだ入っていない。

### 中断時点の記録

Minecraft 1.7.10のローカルAI Companion開発。Phase 0～5の今回の実装・検証を完了し、ここで作業を中断する。
次の担当者はまずこのファイル、[init.md](init.md)、[README.md](README.md)を読む。
`init.md` は仕様であり、作業ログを書き足さない。

**会話・観測・判断結果の取得までは実装済みだが、LLMの判断によるゲーム操作の自動実行は未実装。**
Phase 4の判断APIは常に `executed: false` を返す。会話で「ついてきて」と言っても自動追従はしない。
追従などは手動の `!agent follow` 等で実行する。

記録時点のHEADは `c29db8b`（`Update: 差分転送`）。このメモを作る直前の作業ツリーはクリーンだった。
このメモ追加後の変更については、次回 `git status` で確認する。今回は新たなコミットは作成していない。

## 守る設計境界

- ForgeはゲームI/Oと検証済み操作の実行を担当し、provider SDK・キー・人格・長期記憶を持たせない。
- SocialとTacticalは分離する。判断へ渡すのは限定した目的・必要なゲーム状態・許可操作だけ。
- Tacticalには会話履歴、persona、private memory、relationship、不要なプレイヤー識別子を送らない。
- 通常の観測は差分。挨拶のたびに世界状態をLLMへ送らない。
- providerの文字列をMinecraft commandやシェルとして実行しない。
- 未知の操作・欠けたパラメーターを拒否する。将来の自動実行ではDaemonとForgeの両方で検証する。
- 推測で古いツールチェーンを更新しない。動作確認済みバージョンを維持する。

## 実装済み

| Phase | 内容 | 確認状況 |
| --- | --- | --- |
| 0 | Forge環境固定、空MOD、開発クライアント、Prismへのjar導入 | ビルド・初期化ログ・Prism起動確認済み |
| 1 | HTTPの固定ping/pong | `!agent ping` → `[Companion] pong` を実ゲームで確認 |
| 2 | 名前付きCompanion、spawn/say/look/follow/stop/status、保存・重複防止 | 基本操作と再入場後の保持・重複防止を利用者が確認 |
| 3 | ローカルSocial Brain、短い会話履歴、キー設定 | 日本語会話と「好きな色は青」の記憶を実ゲームで確認 |
| 4 | 独立したDecisionProvider、mockとローカル構造化出力、漏洩テスト | 実モデルで追従・停止・look・低体力時停止を確認。自動実行なし |
| 5 | 初回snapshot、連番付き差分、Daemonキャッシュ、再同期 | 実ゲームの移動・所持品・追従/停止の差分とDaemon再起動後の復旧を確認 |

最新MODは `forge-mod/build/libs/mc-ai-companion-0.0.5.jar`。
Phase 5時点の自動テストは **Python 26件、Java 11件、計37件**。ビルド成功。
Phase 3の実測会話RTTは約1.0～1.3秒、Phase 4の判断は約1.5～2.0秒。継続的な性能保証ではない。

## このPCの環境

| 項目 | 値 |
| --- | --- |
| リポジトリ | `C:\Users\dansaka\Work\Repository\mc-ai` |
| JDK | Eclipse Temurin 8u504-b01、`.tools/jdk8u504-b01` |
| Gradle | 5.6.4、公式wrapperと配布ZIPのSHA-256を固定 |
| ForgeGradle | anatawa12版 `1.2-1.1.1` |
| Forge | `1.7.10-10.13.4.1614-1.7.10` |
| mappings | MCP `stable_12` |
| Python | 3.14.0、Daemonは標準ライブラリのみ |
| Prismインスタンス | `1.7.10-mod-basic` |
| ゲームディレクトリ | `%APPDATA%\PrismLauncher\instances\1.7.10-mod-basic\minecraft`（`.minecraft`ではない） |
| Daemon | `http://127.0.0.1:8767` |
| LM Studio API | `http://127.0.0.1:1234/v1`、認証有効 |
| 利用者指定モデル | `unsloth/gemma-4-26b-a4b-it` |

8766はこのPCで利用できず、起動時にWinError 10013、HTTP接続時に別サービスと思われる応答があった。
そのためDaemonとPrism内の `config/mcaicompanion.cfg` を8767に合わせている。コード既定値は8766。

OpenALFix導入前は利用者のPrism環境で毎回クラッシュしたとの報告がある。既存の `openalfix-1.0.0.jar` を残すこと。
OpenALFix導入後も作業中に一度音声処理のクラッシュを確認しており、完全解消とは断定しない。
既存のExcludeMobsもそのまま残している。これらのMODを勝手に削除・変更しない。

## 再開・起動手順

1. まず8767番で既存Daemonが動いていないか確認する。二重起動しない。
2. LM Studioで上記モデルとAPIサーバーを起動する。モデル変更は利用者と相談する。
3. リポジトリ直下のPowerShellでDaemonを起動する。

```powershell
python agent/src/daemon.py --port 8767 --config agent/config.local.json --decision-config agent/decision.local.json
```

4. Prismの `1.7.10-mod-basic` を起動し、ワールドに入る。
5. `!agent ping`、必要なら `!agent chat こんにちは` で確認する。

観測は自動で始まる。ゲームの一時停止中は観測も止まり、15秒以上でstaleになる。
状態確認やキャッシュからの判断検証中は、ワールドを一時停止しない。

```powershell
$observed = Invoke-RestMethod -Uri http://127.0.0.1:8767/v1/state -Method Post -ContentType application/json -Body '{"version":1}'
$observed | ConvertTo-Json -Depth 8
```

ビルド・テスト:

```powershell
python -m unittest discover -s agent/tests -v
.\scripts\forge.ps1 build
```

初回cloneでは `.\scripts\setup-jdk.ps1` → `.\scripts\forge.ps1 setupDecompWorkspace build`。
`.tools`、ローカル設定、APIキーはcloneに含まれない。[Agent README](agent/README.md)を参照して準備する。

開発起動は `.\scripts\forge.ps1 runClient`。起動中はjarがロックされるため、再ビルド前にゲームを終了する。
Prismのjar更新時も保存してゲームを終了し、旧jarを `.jar.disabled` にして新jarを配置する。同じMODの複数バージョンを有効にしない。

## APIキーとローカル設定

- Social設定: `agent/config.local.json`。
- Decision設定: `agent/decision.local.json`。
- このPCのキー保存先: `.tools/social-api-key.txt`。
- 以上はGit管理外。キーの内容をチャット・ログ・ドキュメントへ書かない。
- 現在は両providerが同じLM Studio用キーを参照しているが、設定は独立している。
- 環境変数はSocialが `MCAI_SOCIAL_API_KEY`、Decisionが `MCAI_DECISION_API_KEY`。ファイルより優先される。
- キー変更は `.\scripts\set-social-api-key.ps1` で非表示入力。保存先は平文のローカルファイル。
- 設定・キー変更後はDaemonを再起動する。

Gemmaは最初のテストで思考生成が256トークンを使い切った。通常会話と判断では `reasoning_effort: none` を指定して実測確認している。

## 主要コードの場所

| ファイル | 役割 |
| --- | --- |
| `agent/src/daemon.py` | HTTPルーティング・起動設定 |
| `agent/src/social.py` | SocialProvider・人格・短い履歴 |
| `agent/src/decision.py` | 匿名化、DecisionProvider、構造化出力・検証 |
| `agent/src/state_cache.py` | snapshot、連番・差分、原子的な更新、stale判定 |
| `forge-mod/src/main/java/local/mcai/PingBridge.java` | ping/chat/forget、非同期通信とゲーム側への応答反映 |
| `forge-mod/src/main/java/local/mcai/CompanionEntity.java` | Entity、標準経路探索を使った追従、task |
| `forge-mod/src/main/java/local/mcai/CompanionCommands.java` | 手動操作 |
| `forge-mod/src/main/java/local/mcai/ObservationBridge.java` | ゲームスレッドでの観測、通信、ACKと再同期 |
| `forge-mod/src/main/java/local/mcai/ObservationDiff.java` | Minecraft非依存の差分生成 |
| `protocol/README.md`・`protocol/examples/` | プロトコルとfixture |

## 同期・記憶の要点

- 観測は毎秒。座標はACK済み位置からの累積移動が2ブロック以上で送る。
- 無変更時は約5～6秒ごとに空イベントを送る。LLMは呼び出さない。
- セッションと連番を使い、ACKが来るまでは差分基準を進めない。
- 失敗・連番不一致・Daemon再起動では5秒後に新しいセッションでsnapshotを送る。
- Companionの有無・個体ID・ディメンション変更でもsnapshotを使う。
- キャッシュは最大32セッション、各100イベント、永続化なし。
- 会話は別のセッション管理。直近6往復・約4000文字まで、最大32セッション、永続化なし。
- `!agent forget` で現在の会話を消す。ワールド再入場・Daemon再起動でも会話を引き継がない。
- Companion本体と所有者対応はワールドに保存される。再入場時のtaskはidleに戻す。

## 未確認・未実装・次の検討事項

実ゲームでは未確認:

- 敵の出入り、体力変化、ディメンション変更の同期（自動テストまたはコード確認の範囲）。
- Companion死亡後の再spawn、未読込チャンクにいる場合の重複防止。
- 会話履歴消去・再入場時のセッション分離（自動テスト済み）。
- 長時間プレイでの音声・経路探索・同期の安定性。

未実装:

- SocialのintentからTacticalへ目的を渡す接続。
- 判断結果のForgeへの配送、Forge側のaction schema検証、実行直前の状態再確認、action result通知。
- Companionのインベントリ、pickup/attack/mine/place/craft/smelt/deposit、goto。
- JEV・クラウドのDecisionProvider、長期記憶、GUI設定、マルチプレイヤー。

次は仕様上Phase 6のGame Actions。ただし一度に全操作を作らない。
自動操作を目指すなら、まず実装済みのfollow/stop/lookで「判断→両側検証→実行→結果通知」を小さく接続し、古い判断の実行や会話情報の混入を防ぐ設計を確認するのが候補。
新しいゲーム操作を先に追加する場合は、pickup等を1つ選び、その前提となるCompanionインベントリと結果通知の範囲を決める。
これらは次回の提案であり、今回まだ着手していない。

## 切り上げ時点のプロセス

メモ作成時点ではMinecraftと検証用Daemonが起動していた。この記録作業では終了させていない。
Daemonの当時のPIDは31796で、`.tools/phase5-daemon.pid` にも記録されている。PIDは再利用されるため、次回この番号だけで停止しない。
終了する場合はMinecraftを保存して終了し、対象がこのリポジトリの `agent/src/daemon.py` であることを確認してDaemonを止める。通常の手動起動ではCtrl+Cで終了できる。
Daemonを止めると会話履歴とキャッシュは消えるが、ワールド保存済みのCompanionは残る。

直近ログは `.tools/phase5-reconnect.stderr.log`、ビルドログは `.tools/phase5-build.log`。
ゲームのMOD初期化・観測ログはPrism内の `minecraft/logs/fml-client-latest.log`。
