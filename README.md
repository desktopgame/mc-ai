# Minecraft 1.7.10 Local AI Companion — v0.1.0

Minecraft Forge側でゲーム操作を実行し、PythonのAgent Daemonで会話・判断・Skillの進行を管理するローカルCompanionです。
**v0.1.0は既知の問題を残した機能の区切りです。** 対象はローカルのシングルプレイヤー環境。未対応機能・不具合・検証不足は [known_issue.md](known_issue.md) に集約しています。

## できること

| 機能 | 入口・範囲 |
| --- | --- |
| Companion | 出現、追従、停止、注視、発話、状態確認、所持品保存 |
| ローカル会話 | `!agent chat こんにちは`、直近会話の記憶、`!agent forget` |
| 会話からの簡単な指示 | 追従・停止・注視・近くの落下物の拾得・所持品全体の受け渡し |
| 型付きSkill | collect_drop、mine、collect_block。引数付きSkillの自然文選択は未対応 |
| 終端結果の発話 | 成功・失敗・取消と確定した部分成果を表示。既存Socialモデルは3候補から表現を選択 |
| 割り込み | 即時停止、指示の置換、古い判断の破棄。通常会話は操作と別に処理 |
| 観測 | 上限付きsnapshot/delta同期。全世界を探索する機能ではない |

## 操作例

| 入力 | 動作 |
| --- | --- |
| `!agent spawn` | 所有者のCompanionを出現させる |
| `!agent follow` / `!agent stop` / `!agent look` | LLMを使わない手動操作 |
| `!agent say こんにちは` / `!agent status` | 発話／状態・所持品確認 |
| `!agent chat ついてきて` | 会話から限定intentを受け付ける |
| `!agent do follow` / `!agent do look` / `!agent do stop` | 判断経由の操作 |
| `!agent pickup` / `!agent deposit` | 手動で近くの落下物を拾う／所持品全体を渡す |
| `!agent do pickup` / `!agent do deposit` | 判断経由で拾得／受け渡し |
| `!agent do collect_drop minecraft:stick 5` | 指定した落下物を新たに累積5個回収 |
| `!agent do mine minecraft:log` | 対象を1ブロック採掘 |
| `!agent do collect_block minecraft:log 5` | 落下物回収と採掘を組み合わせて原木を累積5個回収 |
| `!agent forget` | 会話をリセット。Skillの取消とは別操作 |

collect_dropの対象は `minecraft:log / cobblestone / iron_ingot / planks / stick`、個数1～64。
collect_blockは原木（`minecraft:log`）のみ、個数1～64。mineの個数は1固定、対象allowlistは [mine仕様](protocol/mine-primitive.md) を参照。
`acquired` はこのSkillが新たに回収した累積実績、`mined` は破壊したブロック数です。現在の所持数や木の本数ではありません。
Companionの所持品は9スロット。depositは全品対象で、種類を選択できません。

## 環境構築と起動

確認環境はWindows / PrismLauncher / Minecraft 1.7.10 / Forge 10.13.4.1614、JDK 8、Python 3.14.0、LM Studioです。Python側は標準ライブラリのみを使用します。
Gradle wrapperと固定JDK用スクリプトを同梱しています。初回の依存取得にはネットワークが必要です。

```powershell
.\scripts\setup-jdk.ps1
.\scripts\forge.ps1 build
```

出力は `forge-mod/build/libs/mc-ai-companion-0.1.0.jar`。Minecraftを保存して終了した後、必要な場合に配置します。

```powershell
.\scripts\deploy-mod.ps1 -Version 0.1.0
```

配置先の既定はPrismの `1.7.10-mod-basic`。この検証環境ではOpenALFix導入後に起動できた記録がありますが、音声系の安定性は別途 [既知の問題](known_issue.md#ki-06-音声系クラッシュの過去報告) を参照してください。

`agent/config.example.json` と `agent/decision.local.example.json` を参考にGit管理外の `config.local.json` / `decision.local.json` を作成し、モデル・URL・実際のcontext長を合わせます。既存ファイルを上書きしないでください。
検証時のモデルは `unsloth/gemma-4-26b-a4b-it`、LM Studioは `http://127.0.0.1:1234/v1`。これらは環境例で、起動中の状態を示すものではありません。

```powershell
.\scripts\set-social-api-key.ps1
python agent/src/daemon.py --port 8767 --config agent/config.local.json --decision-config agent/decision.local.json
```

LM StudioのモデルとAPIサーバーを準備してからDaemonを起動します。キー設定、persona、予算、mock起動は [Agent README](agent/README.md) を参照。
MODの `mcaicompanion.cfg` 内 `daemonUrl` とDaemonの待受先を合わせてください。Daemonの既定ポートは8766、この環境例では8767を指定します。
既存Daemonの確認には `scripts/daemon-status.ps1`、ログには `scripts/show-daemon-logs.ps1` を使います。再起動は動作中Skillがない時に行います。

## 設計の境界

- Forgeが操作直前に対象・権限・安全条件を確認し、実際のworld変更を行う。
- Primitiveはtyped receipt、Skillはretry・別候補・段階遷移・精算・終端を担当する。
- Socialは確定したSkill終端を表現する。途中のPrimitive失敗を最終失敗として発話しない。
- Socialの会話履歴・personaをTacticalへ送らない。Skill終端の最小projectionはSocialへ渡す。
- LLM失敗時は固定表示。発話の失敗を理由にSkillを再実行しない。配送・履歴の保証には有界キューと保存期間の制限がある。
- protocol v1（会話・観測・legacy操作）とv2（Skill・終端通知）を併用する。MODのバージョン番号とは別。

## 検証とリリース

```powershell
python -m unittest discover -s agent/tests
.\scripts\forge.ps1 test --offline --rerun-tasks
```

v0.1.0準備時の結果は [リリース記録](RELEASE_NOTES.md) に記載。基本操作・会話・Skill・終端発話には以前の実ゲーム確認がありますが、最新修正の異常系すべてを実ゲームで確認したものではありません。
タグは利用者が作成します。リポジトリの版、ビルド済みjar、Prism配置済みjar、起動中Daemonは別々に確認してください。

## ドキュメント

- [引き継ぎ](HANDOFF.md): 現在地と再開手順
- [既知の問題](known_issue.md): 不具合・設計制限・実機未検証を区別
- [通信仕様の索引](protocol/README.md): v1/v2の入口
- [全体構想](init.md): 将来計画を含む設計。実装済み一覧ではない
- [過去の開発記録](development-history.md): v0.1.0以前のREADME/HANDOFFを保存
- [アイデア](mod-idea/README.md)、[前回レビュー](reviews/review-f33f7f5.md): 背景・指摘時点の記録
