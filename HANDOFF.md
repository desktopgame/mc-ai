# エージェント引き継ぎ — 2026-09-26

## 最初に読むもの

Skill Layer MVP（`collect_drop`）は [protocol/skill-layer.md](protocol/skill-layer.md) の仕様に沿って **実装済み**（MOD 0.0.12）。
Daemon の `agent/src/skill_protocol.py` / `execution_registry.py` / `skills.py`、Forge の `SkillProtocol.java` / `SkillExecutionState.java` と既存クラスへの追加。
入口は `!agent do collect_drop <アイテム> <個数>` と v2 typed protocol。Planner・採掘・自然文からの引数抽出は範囲外。
自動テストはPython 72件・Java 40件。基本の収集を実ゲームで確認済み（部分収納・取消・経路失敗は未検証）。

このファイル → [init.md](init.md)（設計仕様）→ [README.md](README.md) → 必要に応じて [Agent README](agent/README.md) と [行動ライフサイクル](protocol/action-lifecycle.md)。
`init.md` に作業ログを追加しない。READMEのバージョン別の節は当時の検証記録として読む。

Phase 6（Game Actions）に着手済みで、`pickup` と `deposit` の2操作が完了している。残りは `attack / mine / place / craft / smelt`。
仕様は「一度に全部作らない」「1.7.10のpathfinding / recipe / inventory APIを確認しながら追加する」を明記しているため、1操作ずつ追加する。

## 現在地（2026-09-26 14:00頃に確認）

| 項目 | 確認結果 |
| --- | --- |
| Git HEAD | `403e605` 時点からSkill Layerを実装（本ドキュメント更新前は未コミット） |
| MODバージョン | `0.0.12`（`forge-mod/build.gradle` と `CompanionMod` の両方で管理） |
| Prismの有効MOD | `mc-ai-companion-0.0.12.jar`（SHA-256 `310CE06B6E7361E8B9C8DEA90158C617D97CC0E5A313DB92917AE10682AB8713`）。0.0.11は `.disabled` |
| Daemon | PID `42412` が `127.0.0.1:8767` で待受。`protocol 1+2, social=True`（0.0.12相当） |
| LM Studio | PID `21708` が `127.0.0.1:1234` で待受。`unsloth/gemma-4-26b-a4b-it` |
| Minecraft | 終了状態 |
| 自動テスト | Python **71件**・Java **40件**・Forgeビルド成功 |

プロセス・HEAD・作業ツリーは変化するため、次回は必ず再確認する。PIDファイルやこの表だけを根拠に停止しない。
**反映待ちがある。** レビュー指摘の修正と `/local/shutdown` がソースにあるため、ゲーム終了後に `.\scripts\restart-daemon.ps1` と `deploy-mod.ps1 -Version 0.0.12` を行う。
現在稼働中のPID `42412` は `/local/shutdown` 実装前のため、**今回の入れ替えだけ**管理者シェル（またはタスクマネージャ）で停止する必要がある。以後は通常権限で良い。

## 実装済みの機能

- Phase 0～5: Forge環境、HTTP ping、Companionの生成・保存・手動操作、ローカル会話、独立したTactical判断、snapshotと差分観測・キャッシュ・再同期。
- 行動管理基盤（0.0.6）: 判断→両側検証→実行→結果通知。即時停止、取消・置換、古い判断と遅延結果の拒否。
- 会話指示（0.0.7）: 自然文から限定intentを生成しGoal Managerへ渡す。近距離followの維持と経路再試行。
- 通信Executor（0.0.8）: 会話・観測・行動制御・結果通知の4系統、各1worker・待機1件、30秒idleで解放。
- コンテキスト予算（Daemon）: Social/Decisionで入力・出力・コンテキスト上限を独立設定。履歴は古い往復から削る。
- 状態アイコンとデバッグ表示（0.0.9）: ライフサイクルの機械的なチャット通知を既定で止め、画面右上に考え中/行動中/待機中を表示。
  通知は `mcaicompanion.cfg` の `debug { B:verboseChatMessages=false }` をtrueにしたときだけ流す。Socialの返答と実行前の拒否理由は常に表示する。
- **アイテムの拾得（0.0.10）**: 目的 `pickup_item` / 判断 `pickup` / 理由 `goal_pickup・no_item_in_range`。
  9スロットのCompanionインベントリ（ワールド保存・死亡時ドロップ）、16ブロック以内の落下物の観測（最大16件・2ブロック刻み）。
- **所有者への受け渡し（0.0.11）**: 目的 `deposit_items` / 判断 `deposit` / 理由 `goal_deposit・inventory_empty`。
  所有者へ2ブロック以内まで近づいて所持品すべてを渡す。渡し切れなければ `owner_inventory_full` で残りを持ったままにする。
- **Skill Layer（0.0.12 / protocol 2）**: `collect_drop(item, count)`。v2 `/v2/execution/open`・`/v2/goal`・`/v2/action-result`・`/v2/skill-status`。
  DaemonがSkill進捗・候補選択・期限・理由を管理し、ForgeはUUIDで固定した対象だけを数量制限付きで収納する。
  入口は `!agent do collect_drop <アイテム> <個数>`。allowlistは log/cobblestone/iron_ingot/planks/stick、count 1〜64。手動操作・新指示は旧Skillを取り消す。
  レビュー反映: Skillを離れるときは `goal:null` のcancel handshakeを完了してから通常actionへ移る。terminal receiptは収納前にqueue枠を予約し、満杯時は pending として再送する（黙って捨てない）。Forgeは `timeoutMs` で単発actionを打ち切る。Daemonは取消receiptを再選択ではなくcancelledで終端する。

### pickup / deposit の設計判断（重要）

**どのアイテムを拾うか・渡すかはモデルではなくForgeが決める。** pickupは最も近い落下物1件、depositは所持品すべて。
判断結果は `{"action":"pickup"}` / `{"action":"deposit"}` でtargetを持たない。品物を選ぶ判断は後続の増分に残した意図的な制約。
そのため種類を指定した依頼（「ダイヤだけ拾って」「砂だけ渡して」）はSocialがnoneにして、できることを説明する。

判断モデルへ渡すのは**落下物の個数と最短距離、Companionの所持点数だけ**で、アイテム名やエンティティIDは渡さない。
この境界は自動テストで固定している（`test_decision.py` / `test_goals.py` が payload に `minecraft` や `item-` が現れないことを検査）。

## 守る設計境界

- ForgeはゲームI/O・検証済み操作の実行を担当。provider SDK・APIキー・人格・長期記憶を持たせない。
- Tacticalへ会話履歴、persona、private memory、relationship、不要なプレイヤー識別子を送らない。アイテム名も送らない。
- 会話と行動のライフサイクル・世代を分離する。雑談だけでは現在の行動を取り消さない。
- session・goalRevision・actionIdで古い判断を拒否。実行直前にも個体・ディメンション・体力・距離、さらに**落下物の有無と所持点数**を確認する。
- providerの文字列をMinecraft commandやシェルとして実行しない。未知の操作と不完全な引数を両側で拒否する。
- 通常観測は差分。挨拶のたびに世界状態をLLMへ送らない。
- 推測で古いツールチェーンを更新しない。

### 操作を追加するときに必ず両側を直す場所

0.0.10で**MOD側の更新漏れによる実ゲーム不具合**を出したため、次の操作でも以下を最初に確認する。

| 対象 | ファイル |
| --- | --- |
| 会話intent（Daemon） | `agent/src/social.py` の `INTENTS` と `INTENT_INSTRUCTIONS` |
| 会話intent（**MOD側の許容リスト**） | `forge-mod/.../PingClient.java` の `parseSocialReply` |
| 目的・判断・理由（Daemon） | `agent/src/decision.py` の `GOALS / ACTIONS / REASONS / SYSTEM / DECISION_SCHEMA / validate_decision / MockDecisionProvider` |
| 判断入力・結果理由（Daemon） | `agent/src/goals.py` の `_input` と `REASONS` |
| 観測のtask・result（Daemon） | `agent/src/state_cache.py` の `TASKS / RESULTS` |
| 目的・判断・安全条件（MOD） | `ActionProtocol.java`、`ActionBridge.java`（`requestGoal` の許容リスト、実行直前の追加検証、完了時の理由対応） |
| task・result → イベント種別 | `ObservationDiff.java` |
| 手動コマンド | `DebugCommand.java`、`CompanionCommands.java` |
| Skill（Daemon） | `agent/src/skill_protocol.py` の語彙・理由、`skills.py` の状態遷移 |
| Skill（MOD） | `SkillProtocol.java`、`SkillExecutionState.java`、`ActionBridge.java` の `requestSkill`/`skillTick`、`CompanionEntity.pickupItem` |

Skillを増やすときは `collect_drop` の allowlist（Daemon `SUPPORTED_ITEMS` と MOD `SkillProtocol.ITEMS`）と fixture を同時に更新する。

`IntentTest.everySupportedIntentIsAccepted` がDaemonの全intentをMOD側に通す回帰テストなので、intentを増やしたらここにも追加する。

## 検証状況

直近の自動検証はPython **72件**・Java **40件**・Forgeビルド成功（0.0.12時点）。

実ゲームで確認済み（0.0.9～0.0.12分）:

- デバッグ通知OFFでチャットが静かになること、状態アイコンの切り替わり。
- 会話「そこに落ちてるの拾って」→ 判断 → 拾得完了 → `!agent status` の所持品に反映（`minecraft:sand x1`）。
- 会話「持ってるもの渡して」→ 近づいて受け渡し → 所持品が空になること。
- 「近くに拾えるアイテムがありません」「種類は指定できない」の拒否経路。追従・拾得に回帰がないこと。
- **Skill Layer（0.0.12）**: `!agent do collect_drop minecraft:stick 2` で対象固定の収集が完了すること。

実モデルで確認済み: pickup/depositの判断（対象あり・なしの両方）、会話からの `pickup_item` / `deposit_items`、否定・種類指定の拒否。

未確認・残る制限:

- Skill Layer（0.0.12）の基本収集は実機確認済み。部分収納（対象がmaxCountより少ない）・地面残量・取消・置換・経路失敗・満杯は未検証。
- ワールド再入場後のCompanionインベントリ保持（NBT保存は実装済み・実機未検証）。
- 死亡時の所持品ドロップ、`inventory_full`（9スロット満杯での拾得）、`owner_inventory_full`（所有者満杯での受け渡し）。
- 拾得・受け渡し中の経路失敗（`path_not_found`）での中断。
- 否定時の固定文面のゲーム内再確認、予算超過のゲーム内専用表示（未実装。API/ログに理由は出る）。
- 厳密なtokenizerは未導入（UTF-8バイト数で保守的に推定、`utf8_estimate`）。
- 敵・体力・ディメンションの同期、死亡後の再spawn・未読込チャンクの重複防止、長時間安定性。
- 再接続・応答逆転・重複配送・受付上限などの異常系は主に自動テストの範囲。

## このPCの環境と設定

| 項目 | 値 |
| --- | --- |
| リポジトリ | `C:\Users\dansaka\Work\Repository\mc-ai` |
| JDK | Eclipse Temurin 8u504-b01、`.tools/jdk8u504-b01` |
| Gradle | 5.6.4、wrapperと配布ZIPのSHA-256固定 |
| ForgeGradle / Forge | anatawa12版 `1.2-1.1.1` / `1.7.10-10.13.4.1614-1.7.10` |
| mappings / Python | MCP `stable_12` / Python 3.14.0、Daemonは標準ライブラリのみ |
| Prism | `1.7.10-mod-basic` |
| ゲームディレクトリ | `%APPDATA%\PrismLauncher\instances\1.7.10-mod-basic\minecraft`（`.minecraft`ではない） |
| Daemon / LM Studio API | `http://127.0.0.1:8767` / `http://127.0.0.1:1234/v1`、LM Studioは認証有効 |
| 利用者指定モデル | `unsloth/gemma-4-26b-a4b-it` |

8766はこのPCで利用できなかったため、Prismの `config/mcaicompanion.cfg` とDaemonは8767を使う。コード既定値は8766。
OpenALFix導入後も音声処理のクラッシュ記録があり、完全解消とは断定しない。`openalfix-1.0.0.jar` と既存ExcludeMobsを残す。

ローカル設定は `agent/config.local.json` と `agent/decision.local.json`。キーは `.tools/social-api-key.txt`。
すべてGit管理外。内容を丸ごと表示してキーを漏らさない。環境変数 `MCAI_SOCIAL_API_KEY` / `MCAI_DECISION_API_KEY` がファイルより優先。

予算設定:

| 設定 | Social | Decision |
| --- | --- | --- |
| context_window_tokens | 65,536 | 65,536 |
| max_output_tokens | 256 | 256 |
| prompt_budget_tokens | 8,192 | 4,096 |
| history_budget_tokens | 4,096 | 省略＝0 |
| safety_margin_tokens | 512 | 512 |

65,536は前回LM Studioの実ロード長を確認した値。理論最大262,144をそのまま設定しない。`reasoning_effort: none` を使用。

## 再開・反映手順

ビルド・配置・Daemon再起動は専用スクリプトに統一した（`.claude/settings.json` で許可済み）。

```powershell
.\scripts\forge.ps1 build                    # ビルド＋Javaテスト
python -m unittest discover -s agent/tests   # Pythonテスト
.\scripts\deploy-mod.ps1 -Version 0.0.12     # Prismへ配置（ゲーム起動中なら中断）
.\scripts\restart-daemon.ps1                 # Daemon入れ替え（二重起動を拒否）
```

`deploy-mod.ps1` は指定バージョン以外の `mc-ai-companion-*.jar` を `.disabled` にし、配置後のSHA-256を表示する。
`restart-daemon.ps1` はコマンドラインで対象を特定して停止し、**停止できなければ起動せず中断**、起動後は該当プロセスが1つだけであることを検証する。
初回cloneは `.\scripts\setup-jdk.ps1` → `.\scripts\forge.ps1 setupDecompWorkspace build`。開発起動は `.\scripts\forge.ps1 runClient`。

ゲーム一時停止中は観測も止まり、15秒以上でstaleになる。状態確認中はワールドを一時停止しない。
Daemon再起動で会話履歴・キャッシュ・goalは消える。ワールド保存済みのCompanionと所持品は残り、観測は再同期する。

### 環境で踏んだ罠（次回も起きうる）

- **Daemonの二重起動**: PythonのHTTPServerは `allow_reuse_address` を設定するため、Windowsでは同一ポートへ二重bindが成功してしまう。
  どちらが応答するか不定で、ログファイルも共有して「動いていない」ように見える。必ず `restart-daemon.ps1` 経由で入れ替え、待受プロセスが1つか確認する。
- **管理者権限の罠**: 管理者シェルでDaemonを起動すると、通常権限の `Stop-Process`/`taskkill` では停止できず（Access denied）、毎回昇格が必要になる。
  `restart-daemon.ps1` はまず loopback HTTP `POST /local/shutdown`（`--shutdown-token` の共有トークン付き）で終了させるため、**管理者起動のDaemonでも通常権限から入れ替えられる**。
  ただし `/local/shutdown` 実装前のDaemonは一度だけ管理者シェルかタスクマネージャで停止する。以後は通常のPowerShellから `restart-daemon.ps1` を実行すれば昇格不要。
- **ツール実行時のハング**: `Start-Process` でDaemonを起動すると、呼び出し元シェルの子プロセスとして残り、opencode等のbashツールが子孫の終了を待って応答しなくなる。
  `restart-daemon.ps1` は `Invoke-CimMethod Win32_Process Create` で起動して親を WmiPrvSE にし、呼び出し元から切り離す。ログは `cmd /c ... > stdout 2> stderr` で取得する。
  ポート確認は `Get-NetTCPConnection`（約0.6秒）ではなく `TcpClient` の接続プローブ（数ms）を使う。
- **PowerShellツールが使えないセッションがある**: `"hello"` すら「アクセスが拒否されました」になることがあった。
  その場合はBashから `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/xxx.ps1 > out 2>&1` で代用できる。
  出力をファイルへリダイレクトしないと、隠しプロセスがパイプを保持して呼び出しが戻らない。
- **ビルド成果物の上書き**: バージョンを上げる前にビルドすると、前バージョンのjarが新しいコードで上書きされる。
  Prismへ配置済みのjarは別ファイルなので影響しないが、`build/libs` のjarとバージョン番号の対応は信用しすぎない。
- **ゲームログの文字コード**: `latest.log` はCP932。`iconv -f CP932 -t UTF-8` を通さないと日本語が読めない。

## 主要コードと状態保持

- `agent/src/context_budget.py`: 予算検証・推定・履歴の削除。
- `agent/src/social.py` / `decision.py`: 独立したprovider、会話intentとTacticalの入力・出力検証。
- `agent/src/goals.py`: 有界の目的管理・推論worker・世代・実行結果。判断入力の組み立て（落下物と所持点数の要約もここ）。
- `agent/src/daemon.py` / `state_cache.py`: HTTPと観測キャッシュ（owner/companionのインベントリ、hostiles、items）。
- `forge-mod/src/main/java/local/mcai/` の `PingBridge`・`ConversationQueue`・`PingClient`: 会話FIFO、返信の世代、intentの許容リスト。
- 同 `ActionBridge`・`GoalState`・`ActionProtocol`: 実行権限、世代管理、制御と結果通知、操作ごとの安全条件。
- 同 `IoExecutors`: 4系統の通信Executor。`CompanionHud`: 状態アイコン（クライアント専用、ClientProxy経由で登録）。
- 同 `CompanionEntity`・`CompanionCommands`: 個体・経路探索・操作・9スロットのインベントリ（follow / pickup / depositの3タスク）。
- 同 `ObservationBridge`・`ObservationDiff`: 観測・差分・ACK・再同期。
- `protocol/` と各tests: 通信仕様・fixture・回帰テスト。

観測は毎秒、ACK済み位置から2ブロック以上の累積移動で座標送信。無変更でも約5～6秒ごとに空イベント。
落下物は `item-<entityId>` で最大16件、距離は2ブロック刻み。インベントリ差分は `entity`（owner/companion）で区別する。
キャッシュは32セッション・各100イベント。会話は32セッションで予算内の直近往復を保持。両方メモリのみ。

## 次の機能候補（未着手）

Phase 6の残りは `attack / mine / place / craft / smelt`。`pickup` と `deposit` は完了しているので再実装しない。

- `attack`: 敵の観測（`hostiles`）は既にあるため判断入力は揃っている。対象選択の可否、武器・ダメージ、危険時の撤退など安全条件の設計が増える。
- 実ゲーム未検証項目（インベントリ保持、満杯時の挙動、経路失敗）を潰してから次へ進む選択肢もある。
- goto・長期記憶、JEV/クラウドprovider、GUI設定、マルチプレイヤーは未実装。高度な割り込み分類、予約実行、進捗を用いた会話も後続。

## ログ・成果物の参照

- Daemonのログ: `.tools/daemon.stdout.log` / `.tools/daemon.stderr.log`、PID記録 `.tools/daemon.pid`（現在性は保証しない）。
  `intent-*` `lifecycle-*` `phase*-*` は過去セッションの記録。
- ゲームログ: Prism内 `minecraft/logs/fml-client-latest.log`（MODの初期化・例外）と `latest.log`（チャット、CP932）。
- 配置済み0.0.12のSHA-256: `310CE06B6E7361E8B9C8DEA90158C617D97CC0E5A313DB92917AE10682AB8713`。
- Claude向けの権限設定は `.claude/settings.json`（読み取り専用コマンド、上記3スクリプト、WebFetchの許可ドメイン）。

古い手順・実装経緯はGit履歴から参照できる。過去のPIDや「未コミット」「起動したまま」を現在の状態として扱わない。
