# エージェント引き継ぎ — 2026-09-26

## 最初に読むもの

このファイル → [init.md](init.md)（設計仕様）→ [README.md](README.md) → 必要に応じて [Agent README](agent/README.md) と [行動ライフサイクル](protocol/action-lifecycle.md)。
`init.md` に作業ログを追加しない。READMEのPhase別の検証記録は当時の記録として読む。

利用者は使用量の都合で別のエージェントへ引き継ぐ予定。新しい実装範囲はまだ決めていない。
まず下記の反映待ちを把握し、次の機能は利用者と決める。

## 現在地（2026-09-26 12:04～12:06 JSTに確認）

| 項目 | 確認結果 |
| --- | --- |
| Git HEAD | `5bf96a3` — Update: ドキュメント更新 |
| コミット状況 | 会話指示 `906f613`、Executor `02a2d05`、予算管理 `11f5d3d`、資料更新 `5bf96a3` までコミット済み。今回の資料見直し前は作業ツリーがクリーン |
| Minecraft | ゲームプロセスは確認されず、終了状態。Prism Launcher自体は起動中 |
| Daemon | PID `12240` が `agent/src/daemon.py --port 8767` として稼働。`127.0.0.1:8767` の待受も確認 |
| LM Studio | 起動中。PID `21708` が `127.0.0.1:1234` で待受。モデルの現在のロード状態は今回再確認していない |
| Prismの有効MOD | `mc-ai-companion-0.0.7.jar`。0.0.8は未配置 |
| 最新ビルド成果物 | `forge-mod/build/libs/mc-ai-companion-0.0.8.jar` |
| ローカル予算設定 | 更新済み。下記参照 |

この確認ではプロセスの起動・停止、jar配置、コミットは行っていない。今回の変更は資料のみ。
プロセス・HEAD・作業ツリーは変化するため、次回は再確認する。PIDファイルやこの表だけを根拠に停止しない。

**反映待ち:** 常駐Daemonは予算管理変更前からのプロセスなので、最新コード・設定の利用には再起動が必要。
Executor変更をゲームで使うには0.0.8をPrismへ配置する必要がある。予算管理だけならMODの差し替えは不要。

## 実装済みの機能

- Phase 0～5: Forge環境、HTTP ping、Companionの生成・保存・手動操作、ローカル会話、独立したTactical判断、snapshotと差分観測・キャッシュ・再同期。
- 行動管理基盤（0.0.6）: 判断→両側検証→実行→結果通知。`!agent do follow / look`、即時停止、取消・置換、古い判断と遅延結果の拒否。
- 会話指示（0.0.7）: `!agent chat ついてきて / こっちを見て / 止まって` から限定intentを生成しGoal Managerへ渡す。会話からの操作は実装済み。
- 追従修正: 近距離でもfollowを維持し、所有者が離れたら移動を再開。経路失敗は約1秒間隔で3回連続まで再試行し、途中は `path_retrying`。
- 否定への応答: 明確な「止まらないで」「そのまま続けて」等にはモデルを使わず「わかった。今の動作は変えないよ。」とintent:noneを返す。
- 通信Executor（0.0.8）: 会話・観測・行動制御・結果通知の4系統、各1worker・待機1件。30秒idleで解放。既存の世代照合と受付上限を維持。即時停止は通信待ちと独立。
- コンテキスト予算（Daemon）: Social/Decisionで入力・出力・コンテキスト上限を独立設定。Social履歴は古い往復から削り、履歴と全入力の両予算を満たす。必須入力だけで超過ならモデルを呼ばず422。非同期goalはfailedと理由を記録。

短い停止表現は発言全体をローカル照合してLLMを待たず停止する。その他の自然文は会話FIFOで処理する。
通常のMinecraftチャットは自動取得しない。入口は `!agent chat`。
Socialは返答と `none / follow_owner / stop / look_at_owner` のみ生成し、Tacticalへは目的の固定値だけを渡す。
否定・引用・条件・複数操作・未対応操作はnoneにする方針だが、任意の自然文の完全な意味判定は保証しない。

## 守る設計境界

- ForgeはゲームI/O・検証済み操作の実行を担当。provider SDK・APIキー・人格・長期記憶を持たせない。
- Tacticalへ会話履歴、persona、private memory、relationship、不要なプレイヤー識別子を送らない。
- 会話と行動のライフサイクル・世代を分離する。雑談だけでは現在の行動を取り消さない。
- session・goalRevision・actionIdで古い判断を拒否。実行直前にも個体・ディメンション・体力・距離等を確認する。
- providerの文字列をMinecraft commandやシェルとして実行しない。未知の操作と不完全な引数を両側で拒否する。
- 通常観測は差分。挨拶のたびに世界状態をLLMへ送らない。
- 推測で古いツールチェーンを更新しない。

## 検証状況

直近の自動検証はPython **50件成功**、Java **30件成功**、Forgeビルド成功。
Pythonは予算管理時、JavaとForgeはExecutor変更時の結果。今回の資料見直しでは再実行していない。
Pythonの既存HTTPエラーテストが一度Windowsの接続切断で失敗したが、再実行では全件成功した。

実ゲームで確認済み:

- Companion出現・追従・停止・注視・発話・状態表示、保存・再入場後の保持。
- 会話と直前の会話内容の記憶、観測差分とDaemon再起動後の再同期。
- AI判断からの追従、行動中の会話、判断中の停止で旧判断が実行されないこと。
- 注視の完了、連続した会話2件の順序、会話→追従・注視・停止。
- 近距離の追従修正後に移動すること、否定時にも追従が続くこと。

実モデルで確認済み:

- 会話の限定intentと否定・引用・条件・未対応操作などのケース。
- 追従・停止・注視・低体力時のTactical判断。
- 予算管理追加後もSocialのfollow_ownerとDecisionのfollowを生成。ゲームへactionは送らない独立検証。

未確認・残る制限:

- 0.0.8のPrism導入と実ゲーム確認。
- 否定時の最終固定文面は自動テストとHTTPで確認済み、ゲームでの再確認は未実施。
- 予算超過のゲーム内専用表示は未実装。API/ログに理由を出し、MODは一般的な失敗表示。
- 厳密なtokenizerは未導入。UTF-8バイト数と枠の余裕で保守的に推定し、`utf8_estimate` と記録。将来 `TokenCounter` を注入可能。
- 敵・体力・ディメンションの同期、死亡後の再spawn・未読込チャンクの重複防止、長時間安定性は実ゲームで網羅していない。
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
OpenALFix導入前は利用者環境で毎回クラッシュしていた。`openalfix-1.0.0.jar` と既存ExcludeMobsを残す。
OpenALFix導入後も音声処理のクラッシュ記録があり、完全解消とは断定しない。

ローカル設定は `agent/config.local.json` と `agent/decision.local.json`。キーは `.tools/social-api-key.txt`。
すべてGit管理外。内容を丸ごと表示してキーを漏らさない。両providerは同じキーファイルを参照するが設定は独立。
環境変数 `MCAI_SOCIAL_API_KEY` / `MCAI_DECISION_API_KEY` がファイルより優先。キー入力は `.\scripts\set-social-api-key.ps1`。

予算設定（今回キーを出さず確認済み）:

| 設定 | Social | Decision |
| --- | --- | --- |
| context_window_tokens | 65,536 | 65,536 |
| max_output_tokens | 256 | 256 |
| prompt_budget_tokens | 8,192 | 4,096 |
| history_budget_tokens | 4,096 | 省略＝0 |
| safety_margin_tokens | 512 | 512 |

65,536は前回LM Studioの実ロード長を確認した値。理論最大262,144をそのまま設定しない。ロード長の自動検出はない。
`reasoning_effort: none` を使用。Gemmaの思考生成で出力256を使い切った経緯がある。

## 再開・反映手順

1. Git、関連プロセス、8767/1234の待受、Prismの有効jarを再確認する。
2. 古いDaemonが動いていればコマンドと対象を照合して終了し、最新コードで起動する。LM Studioの指定モデル・APIも確認する。
3. Executorをゲーム確認する場合はMinecraftが終了していることを確認し、Prismの0.0.7を `.jar.disabled` にして0.0.8を配置する。同一MODの複数バージョンを有効にしない。
4. Prismを起動し、`!agent ping`、会話、追従・停止を確認する。

```powershell
python agent/src/daemon.py --port 8767 --config agent/config.local.json --decision-config agent/decision.local.json
```

ビルド・テスト:

```powershell
python -m unittest discover -s agent/tests -v
.\scripts\forge.ps1 build
```

初回cloneは `.\scripts\setup-jdk.ps1` → `.\scripts\forge.ps1 setupDecompWorkspace build`。
`.tools`、ローカル設定、APIキーはcloneに含まれない。[Agent README](agent/README.md) を参照。
開発起動は `.\scripts\forge.ps1 runClient`。起動中のjarロックを避けるため再ビルド前にゲームを終了する。

ゲーム一時停止中は観測も止まり、15秒以上でstaleになる。状態確認中はワールドを一時停止しない。
Daemon再起動で会話履歴・キャッシュ・goalは消える。ワールド保存済みのCompanionは残り、観測は再同期する。

## 主要コードと状態保持

- `agent/src/context_budget.py`: 予算検証・推定・履歴の削除。
- `agent/src/social.py` / `decision.py`: 独立したprovider、会話とTacticalの入力・出力検証。
- `agent/src/goals.py`: 有界の目的管理・推論worker・世代・実行結果。
- `agent/src/daemon.py` / `state_cache.py`: HTTPと観測キャッシュ。
- `forge-mod/src/main/java/local/mcai/` の `PingBridge`・`ConversationQueue`: 会話FIFOと返信の世代。
- 同 `ActionBridge`・`GoalState`・`ActionProtocol`: 実行権限、世代管理、制御と結果通知。
- 同 `IoExecutors`: 4系統の通信Executor。
- 同 `CompanionEntity`・`CompanionCommands`: 個体・経路探索・操作。
- 同 `ObservationBridge`・`ObservationDiff`: 観測・差分・ACK・再同期。
- `protocol/` と各tests: 通信仕様・fixture・回帰テスト。

観測は毎秒、ACK済み位置から2ブロック以上の累積移動で座標送信。無変更でも約5～6秒ごとに空イベント。
連番不一致・通信失敗・Daemon再起動では5秒後に新sessionでsnapshot。個体・ディメンション変更でもsnapshot。
キャッシュは32セッション・各100イベント。会話は32セッションで予算内の直近往復を保持。両方メモリのみ。
会話は `!agent forget`・再入場・Daemon再起動で消える。Companionと所有者対応はワールド保存、再入場時taskはidle。
結果通知は有界キュー・最大3回の試行で、再起動をまたぐ配送保証はない。

## 次の機能候補（未着手）

follow/stop/lookのAI接続は完了しているため、再実装しない。
新しいGame ActionsはCompanionのインベントリとpickup等を小さく追加するのが候補だが、利用者の選択はまだない。
goto・attack・mine・place・craft・smelt・deposit、長期記憶、JEV/クラウドprovider、GUI設定、マルチプレイヤーは未実装。
高度な割り込み分類、予約実行、進捗を用いた会話も後続。

## ログ・成果物の参照

- 既存Daemonのログ: `.tools/intent-final.stderr.log` / `.tools/intent-final.stdout.log`。PID記録 `.tools/intent-daemon.pid` は現在性を保証しない。
- 過去の追従修正検証: `.tools/intent-follow-fix.stderr.log`。Phase 5再同期: `.tools/phase5-reconnect.stderr.log`。
- ゲームログ: Prism内 `minecraft/logs/fml-client-latest.log`。
- 配置済み0.0.7のSHA-256: `415784220406FF0D0CC82018B88770D0053DDC938404BD3E1335E1493AD89907`。
- 未配置0.0.8のSHA-256: `17EB48F270C83390623D5EED924FEEBADDA1EDC214B322ACF3EA588D8A515865`。
- 修正前jarはPrism内の `mc-ai-companion-0.0.7-before-follow-fix.jar.disabled` として保持。

古い手順・実装経緯はGit履歴から参照できる。過去のPIDや「未コミット」「起動したまま」を現在の状態として扱わない。
