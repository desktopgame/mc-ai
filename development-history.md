# v0.1.0以前の開発記録（アーカイブ）

2026-09-26、ed1ca65時点のREADME/HANDOFFを保存。以下の「現在」「未実装」、PID、配置済みjar、テスト件数は当時の記述であり、現行状態を保証しない。現在の入口は [README](README.md)、残課題は [既知の問題](known_issue.md)。履歴資料内の古い段階の主張は修正せず保存している。

## 旧README

# Minecraft 1.7.10 Local AI Companion

Minecraft側を薄いゲームI/Oアダプタとし、AI処理を外部Agent Daemonへ分離するプロジェクト。仕様は[init.md](init.md)を参照。

## Skill Layer — collect_drop（0.0.12 / protocol 2）

最初のSkillとして `collect_drop(item, count)` を実装した。指定したregistry名の落下物を、このSkillのactionで新たに取得した累積数がcountに達するまで集める。
LLMを必須とせず、`!agent do collect_drop <アイテム> <個数>` と typed protocol で検証できる。詳細仕様は [Skill Layer MVP](protocol/skill-layer.md) を参照。

| 入力 | 動作 |
| --- | --- |
| `!agent do collect_drop minecraft:log 10` | v2 `/v2/execution/open` → `/v2/goal` → 対象固定のpickupを繰り返す |

- 対象はForgeが実UUIDで固定し、途中で最も近い落下物へ切り替えない。1 actionで収納するのは最大 `maxCount` 個。
- 進捗はSkillのactionで取得した累積（`acquired`）。開始前の所持品・他操作の取得・消費・受渡しでは増減しない。
- 依頼可能itemは `minecraft:log / cobblestone / iron_ingot / planks / stick`。countは1〜64。
- 失敗理由は `no_item_in_range / path_not_found / retry_exhausted / inventory_full / unsafe_state / stale_state / expired` 等。
- 新しい手動操作・指示は旧Skillを取り消す。会話だけでは取り消さない。
- 会話・Tactical・Planner・採掘は範囲外。自然文からの引数抽出も後続。

生成jarは `forge-mod/build/libs/mc-ai-companion-0.0.12.jar`。Daemonも同じ版へ更新する。
2026-09-26: Python **76件**・Java **40件**とビルドに成功。実ゲームで `minecraft:stick` の基本収集（対象固定）を確認済み。部分収納・取消・経路失敗は未検証。

## mine primitive — 0.0.17 / protocol 2

`mine_target`（1 action = 1 block破壊）と、mine候補の上限付きblock観測を追加した。詳細は [mine primitive](protocol/mine-primitive.md) を参照。

| 入力 | 動作 |
| --- | --- |
| `!agent do mine minecraft:log` | v2 `/v2/goal`（`type:mine`）→ 対象ブロックへ移動し、プレイヤーと同じ経時破壊で1ブロック破壊 |

- 観測はCompanion周辺16ブロック（水平±16・垂直±8）の**allowlist blockのみ**、block typeごとに最近傍4件・合計最大32候補。実座標はDaemonへ渡さず、`block-<x>_<y>_<z>` のopaque参照とregistry名・距離だけを送る。
- 道具選択はForgeが決定的に行う。素手で掘れるブロックは素手、必須ツールが無ければ `tool_unavailable`。自動クラフトはしない。
- 破壊は block hardness と tool speed に応じた時間がかかり、`destroyBlockInWorldPartially` の破壊アニメーションと `swingItem` を伴う。
- **遮蔽/到達可能性のauthorityは採掘距離まで移動した後の `MineTargetTask`**。claim前は target identity/type/range の再検証のみで、現在位置からの直線遮蔽判定はしない（回り込める壁越しのtargetを誤除外しないため）。移動後に採掘開始前とworld変更直前（同一tick）で block種・lease・遮蔽を再検証する。air・非固体（草/花）・leavesは通過可、glass/stone/dirt/wood/ore等のsolidは遮蔽。遮蔽時は `blocked` で失敗し、邪魔なブロックを勝手に複数破壊しない。
- **`mine` goal の count は1固定**（2以上は `unsupported_count` で拒否）。反復は将来の `collect_block`/`collect(block,N)` の責務。
- mineの進捗は破壊数（`mined`）で、`collect_drop` の取得progressとは混ぜない。
- `collect_drop` の意味・成功条件は変更していない。`collect_block` / `collect(log,N)` は未実装。

生成jarは `forge-mod/build/libs/mc-ai-companion-0.0.31.jar`。Daemonも同じ版へ更新する。
2026-09-26: Python **83件**・Java **48件**とビルドに成功。実ゲームで `!agent do mine` の経時破壊と、ガラス越しの `blocked`（原木は破壊されない）を確認済み。回り込める壁越しの採掘・leaves越し・count境界は未検証。

## collect_block — 0.0.22 / protocol 2

`collect_block(block, count)` を、Daemonが `mine_target` と `pickup_target` を順序づけるSkillとして追加した。詳細は [collect_block MVP](protocol/collect-block.md) を参照。

| 入力 | 動作 |
| --- | --- |
| `!agent do collect_block minecraft:log 5` | v2 `/v2/goal`（`type:collect_block`）→ 近くの原木dropを拾い、無ければ原木を1本ずつ掘って落ちた原木を回収し、累積5個で完了 |

- **成功条件は `acquired`**（このSkillのpickupで実際に収納した数）。`mined`（破壊数）は副作用カウンタで成功条件ではない。開始前の所持品は数えない。
- 対象はMVPでは `minecraft:log` のみ（block→item対応表）。countは1〜64。
- mine成功では完了せず、新しい観測を待って落ちた原木を回収する（mine receiptから6秒、pollでは延長しない）。回収が成功するまで次のmineへ戻らない。
- 取消・終端では確定済みの `acquired`/`mined` を保持する。失敗理由に `drop_unavailable` を追加。
- Forgeは `/v2/execution/open` の `capabilities` に `collect_block_v1` が無ければ新goalを送らず安全停止する（mine/collect_dropへ代替送信しない）。
- `collect_drop` / `mine` の意味・入口・結果は変更していない。

生成jarは `forge-mod/build/libs/mc-ai-companion-0.0.31.jar`。
2026-09-26: Python **155件**・Java **83件**とビルドに成功。実ゲームで `!agent do collect_block minecraft:log 5` の原木収集と、Skill終端Social通知（terminal発話の一度表示、present→表示→displayed ACK→履歴登録）を確認済み。skill/action binding検証とterminal result厳密parseを追加。terminal台帳は有界（closedはexact 100件＋digest 1024件で再生成防止）・fingerprintでclosed衝突検出・deep copy・CONTROL laneは実競合時のみSkill制御を優先（active Skillではterminal pollを停止しない）。world/session切替でterminal cursorをリセット。Phase 6で **実表示→displayed ACK→history登録** の順に登録（forget retirement付き）。review-f33f7f5の配送欠落5件を修正（presentなしACK・ACK満杯・生成中12秒・forget中生成・cleanup取りこぼし）。

## Skill終端 → Social発話 — Phase 1〜6 / protocol 2

Skillの `completed / failed / cancelled` を、確定済み terminal result を authority として一度だけ表示する。詳細は [skill-terminal-social](protocol/skill-terminal-social.md)。

- Daemonが `SkillManager._finalize` で immutable な terminal event を一度だけ outbox に登録（identity `(daemonEpoch, session, skillInstanceId, terminalId)`、単調増加 `eventSequence`）。read-only の `/v2/terminal-events`（最大8件）、`/v2/social/skill-terminal`、`/v2/social/terminal-delivery`。
- 固定rendererは Python/Java で同一文字列（`protocol/fixtures/skill-terminal-fallback.json`）。例: `原木回収: 完了。原木を5/5個回収、採掘3ブロック。` / `原木回収: 失敗[drop_unavailable]。回収2/5個、採掘3ブロック。確認した範囲で回収対象を見つけられませんでした。`
- reasonは辞書で意味を固定（`mined` は「Nブロック破壊」、`acquired` は累積取得。未知reasonは推測せず enum 表示）。`complete=false` は「確定分は…。未確定の操作があります。」を付ける。
- Forgeは `skill_terminal_social_v1` がある時だけ新経路を使い、`TerminalDeliveryState`（純粋・first-wins）で identity ごとに一度だけ表示。**旧Daemonでは従来の終端表示を維持**する。
- **Phase 4（0.0.26）**: `ConversationQueue` を `USER_CHAT`/`SKILL_TERMINAL` のtyped entryへ拡張（chat 4件・2048字維持、terminal待機枠8件、単一FIFO）。`PingBridge` が両種を同一順序で処理し、terminalは会話contextを初期化して表示（typed commandだけでも通知可）。待機terminalは12秒でFIFO例外として先行chat中でも表示。forget/退出で未表示terminalは旧会話として表示または抑制。`TerminalPollCursor` をworld/session切替でリセットし、新sessionはeventSequence=1から取得。
- **Phase 5（0.0.27）**: 既存providerで候補選択。`render_candidates` が friendly/calm/concise の**事実完全な3候補**（≤512、Python/Java同一）を生成し、`LocalSocialProvider.select_terminal` が候補IDのenumに限定した厳密schema＋8秒deadlineで1つ選ぶ。`/v2/social/skill-terminal` は台帳で二重呼び出しを防ぎ、provider未設定/busy/例外/予算超過は固定fallback（mode=fallback、同一say）。transport情報はLLMへ送らない。terminal presentationは通常chatと**同じconversation履歴を読むが書かない**（履歴登録はPhase 6のdisplayed ACK後）。presentation生成競合は**first-wins**（generating中のduplicateはfallbackを確定し、遅いprovider結果は上書きしない）。
- **Phase 6（0.0.31）**: delivery ACKで履歴登録を接続。displayed ACK時だけ、通常chatと同じ `(conversationSession, player)` 履歴へ `[内部イベント skill_terminal]` ペアを一度だけ追加（suppressed/重複ACKは追加なし、variant不一致は409）。present未実行/生成中でも snapshot からfallbackを確定してACKを適用。forgetは削除がbusyでもsessionをretiredにして履歴再作成を拒否。ForgeはSOCIAL workerで present と候補検証のみを行い、**game threadで表示可否を判定 → reply → displayed ACK**（reset/forget/owner・world変更後は suppressed ACK）。12秒は in-flight も含む絶対期限。ACK/forgetは bounded pending から通常生成より先に1件ずつ送信（pending ACKは**有界32件**、32件超の異常backlogでは表示を維持しACK/history/outbox closeはbest-effortでdrop・警告、rejectで即消える問題は解消）。
- 未確認: Daemon epoch変更時の旧作業表示は未実装。Skill終端Social通知の基本動作は実ゲーム確認済みだが、reset/forget/退出/world変更の競合・12秒fallback・provider失敗は自動テストのみ。

生成jarは `forge-mod/build/libs/mc-ai-companion-0.0.31.jar`。

## コンテキスト予算 — Daemon

SocialとDecisionそれぞれにコンテキスト長・出力上限・入力予算を設定できる。Socialには独立した履歴予算も持たせ、古い往復から削除する。
systemと今回の発話だけで上限を超える場合は、モデルへ送信せず理由を返す。現在の計数はUTF-8ベースの保守的な推定。
設定項目・既定値・制約は [Agent README](agent/README.md#コンテキスト予算) を参照。MODの変更は不要。

## 所有者への受け渡し — 0.0.11（Phase 6の2番目の操作）

拾ったものを所有者へ渡せるようになった。Phase 6の残り（attack / mine / place / craft / smelt）は未実装。

| 入力 | 動作 |
| --- | --- |
| `!agent chat 持ってるもの渡して` | Socialが `deposit_items` を生成し、判断と検証を経て所有者へ渡す |
| `!agent do deposit` | AI判断経由で受け渡しを依頼 |
| `!agent deposit` | LLMを使わない手動操作。何も持っていなければその場で断る |

**渡すのは所持品すべて**で、pickupと同様に品物は選べない（「砂だけ渡して」等は受け付けず、まとめて渡せると返答する）。
判断モデルへ渡すのは所持点数だけで、アイテム名は渡さない。観測スキーマは変更していない。

所有者へ2ブロック以内まで近づいてから渡す。所有者の持ち物がいっぱいで渡し切れない場合は `owner_inventory_full` で失敗し、
残りはCompanionが持ったままになる。空になったときだけ成功とする。実行直前に所持品が空なら `inventory_empty` で停止する。

生成jarは `forge-mod/build/libs/mc-ai-companion-0.0.11.jar`。Daemonも同じ版へ更新する。
通信仕様は [行動ライフサイクル](protocol/action-lifecycle.md#phase-6の2番目の操作--deposit0011) を参照。
2026-09-26: Python62件・Java36件のテストとビルドに成功。実ゲームでの受け渡し・所有者満杯時の挙動は未検証。

## アイテムの拾得 — 0.0.10（Phase 6の最初の操作）

Companionが近くに落ちているアイテムを拾えるようになった。Phase 6の残り（attack / mine / place / craft / smelt / deposit）は未実装。

| 入力 | 動作 |
| --- | --- |
| `!agent chat そこに落ちてるの拾って` | Socialが `pickup_item` を生成し、Tactical判断とゲーム側の検証を経て拾う |
| `!agent do pickup` | AI判断経由で拾得を依頼 |
| `!agent pickup` | LLMを使わない手動操作。近くに何も無ければその場で断る |
| `!agent status` | 体力・座標・task・結果に加えて所持品を表示 |

**どのアイテムを拾うかはモデルではなくForgeが決め、Companionから16ブロック以内の最も近い落下物だけを対象とする。**
そのため種類を指定した依頼（「ダイヤだけ拾って」等）は受け付けず、近くのものなら拾えると返答する。品物を選ぶ判断は後続の増分に残す。
判断モデルへ渡すのは落下物の個数と最短距離だけで、アイテム名やエンティティIDは渡さない。

インベントリは9スロットでワールドへ保存し、死亡時は中身を地面へ落とす。所有者への受け渡し（deposit）は未実装。
9スロットに入り切らない場合は入った分だけ拾い、全く入らなければ `inventory_full` で失敗する。
判断時にアイテムがあっても実行直前に消えていれば `no_item_in_range` で停止する。追従と同じ32ブロックの制限を受ける。

観測にはCompanionのインベントリと、16ブロック以内の落下物（最大16件・2ブロック刻みの距離）を追加した。
所有者とCompanionのインベントリ差分は `entity` で区別する。通信仕様は [行動ライフサイクル](protocol/action-lifecycle.md#phase-6の最初の操作--pickup0010) を参照。

生成jarは `forge-mod/build/libs/mc-ai-companion-0.0.10.jar`。Daemonも同じ版へ更新する（観測・判断・会話すべてが変わるため再起動が必要）。
2026-09-26: Python58件・Java35件のテストとビルドに成功。実モデルで拾得の判断（アイテム有無の両方）と、会話からの `pickup_item`・否定・種類指定の拒否をHTTP経由で確認した。
実ゲームの初回確認で、Daemonが200を返しても `PingClient` が `pickup_item` を未知のintentとして拒否し会話が失敗する不具合を発見し修正した（MOD側のintent許容リストの更新漏れ）。
Daemonが返し得るintentすべてがMOD側を通過することを回帰テストに追加した。実ゲームでの拾得動作・インベントリ保存・死亡時の落下は未検証。

## 状態アイコンとデバッグ表示 — 0.0.9

行動ライフサイクルの機械的なチャット通知を既定で止め、画面右上に状態アイコンを表示する。

- 🟡「考え中」= Socialの応答待ちまたはTactical判断待ち、🟢「行動中」= 実行中、⚪「待機中」= それ以外。GUI表示中は隠す。
- 「受け付けました。」「新しい指示を受け付けました。」「追従を始めます。」等は `mcaicompanion.cfg` の `debug { B:verboseChatMessages=false }` をtrueにしたときだけ表示する。
- Socialの実際の返答、使い方エラー、実行前に依頼を拒否した理由（Companion未同期・混雑等）は常に表示する。

2026-09-26: 実ゲームでチャットが静かになることと、アイコンの切り替わりを利用者が確認した。

## 通信Executor — 0.0.8

リクエストごとのスレッド生成をやめ、会話・観測・行動制御・結果通知に独立した単一スレッドの `ThreadPoolExecutor` を割り当てた。
遅い会話が制御や観測の順番待ちを発生させない。即時停止は引き続きゲームスレッド上で行い、通信を待たない。
各経路は最大1スレッド、Executor内のキューは1件。既存のinFlight制限と会話・結果の待ち行列上限は維持する。
キュー満杯時にゲームスレッドでHTTPを実行する方式は使わず、拒否を明示して既存の失敗処理へ戻す。

Executorはプロセス単位で所有し、ワールド再入場ごとに増やさない。30秒の無通信でworkerを解放し、JVM終了時にshutdownNowする。
終了時の割り込みだけでHTTPが即中断できるとは限らないため、従来の通信タイムアウトと世代照合も維持する。
ワールドを移動しても同じ経路の旧通信が完了するまでは待つが、別経路は待たない。
生成jarは `forge-mod/build/libs/mc-ai-companion-0.0.8.jar`。Java30件のテストとビルド成功。Prismへの配置と実ゲームの再確認は未実施。

## 会話からの指示 — 0.0.7

`!agent chat` の発言から、Socialモデルが返答と限定したintentを生成し、既存の目的管理へ接続する。

| 入力例 | 動作 |
| --- | --- |
| `!agent chat ついてきて` | 追従の目的を受理し、別のTactical判断とゲーム側の検証を経て実行 |
| `!agent chat こっちを見て` | 所有者への注視を依頼 |
| `!agent chat 止まって` | モデル・会話の待機列を待たず即時停止 |
| `!agent chat こんにちは` | 会話のみ。現在の目的を変更しない |

入口は引き続き `!agent chat`。通常のMinecraftチャットをすべて自動取得するものではない。
即時停止は「止まって」「止まってください」「キャンセル」「やめて」「やっぱやめて」「待って」等の短い発言全体に一致する場合。
それ以外の表現はSocialモデルを待つ。確実な即時停止手段として `!agent stop` も残す。
否定・引用・仮定・複数の操作・未対応の依頼は操作しない方針。モデルへの指示に加え、代表的な否定・引用・条件表現はコードでも拒否する。
任意の自然文を完全に判定する保証はなく、曖昧な場合は短く直接依頼する。採掘や指定場所への移動などは未対応。
「止まらないで」「そのまま続けて」等の明確な継続表現には、Daemonが「今の動作は変えないよ」と返す。新しい操作は依頼せず、実行中のtaskが何かも断定しない。

会話の受付順と最後に受理した指示の順序を管理し、手動停止・新しい指示・再接続・会話消去後に古いintentを実行しない。
追加の雑談だけでは行動判断を取り消さない。自然文の依頼は分類された時点で受理するため、分類待ちの発言は会話FIFOに残る。
会話本文や履歴をTacticalへ渡さず、`follow_owner / stop / look_at_owner` の目的だけを渡す。
追従にはSocialとTacticalの2段階の推論時間が必要。自然文の停止も定型の即時停止表現以外はSocial判定後に停止する。

MODは `forge-mod/build/libs/mc-ai-companion-0.0.7.jar`。Daemonも同じ版へ更新する。設定・モデル・APIキーはそのまま使える。
近くで追従を依頼した場合もfollow状態を維持し、2ブロック以内では足を止め、離れると移動を再開する。
経路探索の一時失敗は約1秒間隔で再試行し、3回連続で失敗した場合にAI追従を終了する。
通信仕様は [行動ライフサイクル](protocol/action-lifecycle.md#会話からの指示--007) を参照。
2026-09-26: Python41件・Java26件のテストとビルドに成功。実ゲームで会話からの追従・注視・停止を確認し、近距離で追従を開始できない問題も修正。否定時の不自然な返答は固定の継続了承へ修正し、HTTP経由で確認した。

## 割り込みと行動管理の基盤 — 0.0.6

Phase 6の新操作を増やす前に、既存のfollow/stop/lookをAI判断から実行する経路を追加した。
会話・目的・実行の状態と通信を分離し、取消後の古い判断、重複action、旧actionの遅延結果を拒否する。
以下は0.0.6時点の仕様で、後続のPhase 1～5の節も各段階の記録。会話からの指示は上の0.0.7節を参照。

| 入力 | 動作 |
| --- | --- |
| `!agent do follow` | 現在の行動を止め、新しい目的でAIに判断を依頼。検証後に追従または待機 |
| `!agent do look` | 同様に注視を依頼。約3秒で完了 |
| `!agent stop` / `!agent do stop` | LLMを待たず、その場で停止。待機中の判断も取消 |
| `!agent follow` / `!agent look` | 従来の手動操作。古いAI指示は取消 |
| `!agent chat メッセージ` | 行動を継続しながら会話。処理中は最大4件・計2048文字を順番待ち |
| `!agent forget` | 会話の待機列と古い返答を取消し、新しい会話セッションへ切替。行動は継続 |

`chat ついてきて` の自然文からはまだ操作しない。自然文の分類、指示の予約、pickup等は未実装。
0.0.6では近すぎる場合に待機を選んでいたが、0.0.7で近距離でも追従を維持するよう修正した。32ブロックの上限は維持する。
指示変更は旧行動を取り消してから判断する。新判断が失敗しても旧行動は復活しない。
AI操作中の接続失敗・危険な状態・経路失敗では停止する。再接続で自動再開せず、再度指示する。
一時停止からの復帰時も、古い応答の期限や再同期によって指示が取り消される場合がある。

起動は従来と同じ。Daemonもこの版へ更新し、Prismには `forge-mod/build/libs/mc-ai-companion-0.0.6.jar` を配置する。
モデル設定はSocialとDecisionの両方を指定する。

```powershell
python agent/src/daemon.py --port 8767 --config agent/config.local.json --decision-config agent/decision.local.json
```

通信と制限の詳細は [行動ライフサイクル](protocol/action-lifecycle.md) を参照。
自動テストと実ゲームの検証状況は [HANDOFF.md](HANDOFF.md) に記録する。
2026-09-26: Python34件・Java19件のテストとビルドに成功。実ゲームでAI追従、行動中の会話、停止、判断中の取消、注視の完了、連続会話への順番どおりの返答を確認した。

## Phase 5 — 状態差分と観測

MOD `0.0.5` は、ワールド入場時に `POST /v1/snapshot` で現在状態を送り、その後は `POST /v1/events` へ変更だけを送る。
観測はサーバー側で毎秒行い、HTTP通信は専用スレッドで同時1件まで。無変更時は約5～6秒ごとに空のイベント配列を送るだけで、全状態やLLMプロンプトは送らない。

同期するもの:

- 所有者と読み込み済みCompanionの座標（最後に同期した座標から2ブロック以上移動）、体力。
- 所有者の所持品・防具の個数差分。Companionのインベントリは未実装。アイテム名の独自変更・NBT・本の内容等は送らない。
- Companionのtaskと結果、タスク完了・失敗。
- Companionから16ブロック以内の近い敵最大16体。種類と2ブロック刻みの距離、範囲への出入りと更新。

Companionの出現・死亡や未読込化・ディメンション変更時は状態構造が変わるためsnapshotで再同期する。定期的なfull snapshotは使わない。
各メッセージにはセッションIDと連番を付け、ACK済み状態を差分の基準にする。連番不一致・接続失敗・Daemon再起動時は5秒後に新しいセッションで再同期する。
Daemonは差分を一括検証して適用し、最大32セッション・各100イベントをメモリに保持する。15秒以上更新がない状態はstaleとし、判断には利用しない。

現在状態を確認するには別のPowerShellから:

```powershell
$observed = Invoke-RestMethod -Uri http://127.0.0.1:8767/v1/state -Method Post -ContentType application/json -Body '{"version":1}'
$observed | ConvertTo-Json -Depth 8
```

観測済み状態で判断を試す場合（ゲーム操作は実行しない）:

```powershell
$request = @{version=1;session=$observed.session;goal=@{type='follow_owner'};availableActions=@('follow','stop','look')} | ConvertTo-Json -Depth 4
Invoke-RestMethod -Uri http://127.0.0.1:8767/v1/decision -Method Post -ContentType application/json -Body $request | ConvertTo-Json -Depth 4
```

判断モデルへは目的に必要な座標・Companion体力と操作一覧だけを渡す。観測処理からLLMを自動呼び出しせず、Socialの会話プロンプトにも全状態を追加しない。
シングルプレイヤー用。敵・所持品の観測は1秒ごとのサンプリングのため、サンプル間だけに発生した短い変化は記録しない。敵16体の上限を越えた場合の出入りは観測対象集合の変化を意味する。
Daemon切断中も手動操作は利用できるが、キャッシュの更新は再接続まで止まる。

Pythonテスト26件・Javaテスト11件とビルドに成功。2026-09-26、実ゲームで初回同期、移動・所持品変更・追従と停止の差分受信を確認した。
Daemonを再起動してキャッシュを失わせた後、新しいセッションへの自動再同期と差分送信の再開を確認。復旧したキャッシュから実モデルのfollow判断も取得できた（executed=false）。Phase 5完了。
敵の出入り・体力変化・ディメンション変更は自動テストまたは実装確認の範囲で、今回の実ゲーム操作では未検証。

## Phase 4 — 独立したTactical Decision

`DecisionProvider` を追加した。決定的なmockと、JSON schema対応のローカルOpenAI互換API実装を切り替えられる。
Phase 4時点の変更はDaemonのみ。Social Brainの会話から判断を呼ぶ処理はまだ接続しない。

```powershell
# モックだけで判断を検証する（LLM不要）
python agent/src/daemon.py --port 8767 --decision-config agent/decision.example.json
# このPCの実モデル設定。会話と判断を別々の設定で有効化する。
python agent/src/daemon.py --port 8767 --config agent/config.local.json --decision-config agent/decision.local.json
```

別のPowerShellから:

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8767/v1/decision -Method Post -ContentType application/json -InFile protocol/examples/decision-request.json | ConvertTo-Json -Depth 5
```

目的は `follow_owner / stop / look_at_owner`、判断結果は `follow / stop / look`。`reasonCode` は固定コードだけを返す。
リクエストから許可した数値状態と目的を再構成し、プレイヤーを `owner` に匿名化する。会話履歴・persona・private memory・relationship・プレイヤー名は送信しない。
判断結果は独立したバリデーターで再検証する。未知の操作、対象不足、未許可操作、低体力時の移動、範囲外の追従などは拒否する。

**このAPIは判断の検証用で、ゲーム操作を実行しない。** 応答の `executed` は常に `false`。Forge側も従来どおりネットワークからの空でないactionsを拒否する。
ゲームとの自動連携時にはForge側のパラメーター検証・実行直前の状態確認・結果通知を追加する。

2026-09-26: Pythonテスト19件が成功。Social Brainへ `PRIVATE_TEST_MARKER_12345` を入れた後も、判断ペイロードや実際に組み立てたAPIリクエストに混入しないことを検証した。
実モデル `unsloth/gemma-4-26b-a4b-it` で追従・停止・向き変更・低体力時の停止の4ケースを確認し、遅延は約1.5～2.0秒。JEVやクラウドproviderは未実装。

## Phase 3 — ローカルSocial Brain

`!agent chat メッセージ` でローカルLLMと会話できる。`!agent forget` で現在の会話履歴を消す。
会話から操作指示は生成・実行しない。追従・停止等はPhase 2の手動コマンドを使う。

このPCの設定:

- LM Studio: `http://127.0.0.1:1234/v1`
- モデル: `unsloth/gemma-4-26b-a4b-it`
- Daemon: `http://127.0.0.1:8767`
- ローカル設定: `agent/config.local.json`（Git管理外）
- APIキー: `.tools/social-api-key.txt`（Git管理外。Daemonのみが読む）

```powershell
# APIキーの初回保存・変更。非表示で入力し、ローカルファイルへ保存する。
.\scripts\set-social-api-key.ps1
# LM Studioで指定モデルをロードし、APIサーバーを開始してから実行する。
python agent/src/daemon.py --port 8767 --config agent/config.local.json
```

環境変数 `MCAI_SOCIAL_API_KEY` でも指定できる。ファイルより環境変数を優先する。詳細と新規clone時の設定は [Agent README](agent/README.md) を参照。

会話履歴はDaemonのメモリ内だけに保持し、設定した履歴予算と入力全体の予算に収まるよう古い往復から削除する。セッションはワールドへの入場単位とプレイヤーで分離する。再入場やDaemon再起動で以前の会話を引き継がず、最大32セッションを超えると古いものから除去する。
Socialモデルへ送るのはpersonaと短い会話履歴だけ。ゲーム状態・プレイヤー識別子は送らない。Phase 4のTactical providerへ会話データを転送する経路は持たない。

通常会話は `reasoning_effort: none`、生成上限256トークン、API待ち時間30秒。MODの会話待ち時間は50秒。失敗時に自動再試行せず、履歴とゲーム内の動作を変更しない。
Daemonログにはモデル名・応答時間・入出力サイズ・利用可能なトークン数を記録し、会話本文・キーは出力しない。prefillと生成時間の個別計測は未対応。

Pythonテスト11件、Javaテスト8件とビルドが成功。2026-09-26、認証付きの指定モデルで実ゲームから日本語の返答と、直前に伝えた好きな色「青」を覚えていることを利用者が確認した。3回のゲーム側RTTは1000 / 1276 / 1275 ms。Phase 3完了。履歴消去・セッション分離・失敗時の履歴保持は自動テストで確認済み（実ゲームでの消去・再入場検証は未実施）。

## Phase 2 — Companionと基本操作

Phase 2（`0.0.3`）で、仮のSteve表示と名前付きのCompanionを追加した。これらの操作はLLMを使わない。
以下は通常のチャットから入力する手動デバッグコマンドで、Daemonを経由しない。`!agent ping` は従来どおりDaemonへ接続する。

| コマンド | 動作 |
| --- | --- |
| `!agent spawn` | 2～4ブロック先の地面に出現。所有者ごとに1体 |
| `!agent follow` | 入力した所有者を標準の経路探索で追従 |
| `!agent stop` | 経路と追従を解除して待機 |
| `!agent look` | 移動を停止し、約3秒間所有者へ顔を向ける |
| `!agent say こんにちは` | `[Companion] こんにちは` と所有者のチャットへ表示 |
| `!agent status` | 体力・座標・task・直近の実行結果を表示 |
| `!agent help` | コマンド一覧 |

平らで広い場所で `spawn` → `follow` → 数ブロック歩く → `stop` の順で確認する。
追従は2ブロック以内で足を止め、32ブロックを超えると移動を保留する。`path_not_found` は経路なし、`owner_out_of_range` は範囲外。
独自pathfinderやテレポートは使わず、地形・落下・溶岩等への高度な回避は未対応。`stop` は物理的な押し出しや落下まで固定するものではない。

名前・体力・位置・所有者をワールドへ保存し、再読込時の動作はidleに戻す。自然消滅しないがダメージで死亡する。死亡後は再度spawnできる。
所有者と個体の対応をワールドの `data/mcai_companions.dat` にも保存し、未読込チャンク内にいる場合は重複spawnを拒否する。その場合は最後にいた場所へ戻る。
ディメンション間の追従、戦闘、アイテム所持、見た目の設定、マルチプレイヤーは対象外。

ビルドとJavaテスト8件に成功。2026-09-26、Prismの実ゲームで出現・名前表示・追従・停止・向き変更・発話・体力と座標の表示を利用者が確認した。保存して同じワールドへ入り直した後も個体が残り、再度spawnしても増殖しないことを確認済み。Phase 2完了。

確認ログ: 00:20:53にPhase 2初期化、00:21:10にspawn、00:21:18にfollow、00:21:34にstop、00:22:09にlook、00:22:37にsay、00:22:46にstatus。死亡後の再spawn・未読込チャンクでの重複防止・ディメンション変更は実機では未検証。

## Phase 1 — 固定ping/pong

Phase 1（`0.0.2`）で追加した固定通信はPhase 2でも利用できる。LLM・memoryは使わない。

1. `python agent/src/daemon.py --port 8767` でDaemonを起動する。このPCでは既定の8766が利用できなかったため8767を使用する。
2. `.\scripts\forge.ps1 build` でビルドする。
3. Minecraftを終了してから `forge-mod/build/libs/mc-ai-companion-0.0.5.jar` をPrismの `minecraft/mods/` へ入れる。古いjarは削除するか `.jar.disabled` へ改名し、複数バージョンをロードしない。
4. `minecraft/config/mcaicompanion.cfg` を以下に設定する。既定値は `http://127.0.0.1:8766`。今回のPrism検証環境は8767へ設定済み。

```text
network {
    S:daemonUrl=http://127.0.0.1:8767
}
```

5. ワールドに入り、通常のチャットで `!agent ping` を入力する。応答は `[Companion] pong`。

開発クライアントを使う場合の設定先は `forge-mod/run/config/mcaicompanion.cfg`。初回起動で設定ファイルが生成される。設定変更後はゲームを再起動する。

通信は別スレッド、チャット表示はサーバーtickで実施する。連打時は1件だけを送信し、通信中の案内を返す。Daemon停止・タイムアウト・不正応答時は短いエラーを表示し、自動再試行しない。Phase 1では空でないactionsを拒否する。

```powershell
python -m unittest discover -s agent/tests -v
.\scripts\forge.ps1 test
```

Daemonの実HTTPテスト4件とJava側テスト4件（正常応答、不正応答・action拒否、HTTP失敗・接続失敗、タイムアウト）が成功。2026-09-26 00:15:05に実ゲームからのHTTP 200と `Daemon ping succeeded, RTT=2 ms` を確認し、利用者も `[Companion] pong` の表示を確認した。Phase 1完了。

詳しくは [Agent Daemon](agent/README.md) と [Protocol v1](protocol/README.md) を参照。

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

成果物は `forge-mod/build/libs/mc-ai-companion-<version>.jar`（現在は `0.0.31`。バージョンは `forge-mod/build.gradle` で管理する）。
開発クライアントのゲームディレクトリは `forge-mod/run`。
現行MODの初期化メッセージは `MC AI Companion initialized (Action lifecycle)`。以下のPhase 5のログ例は当時の記録。

旧ForgeGradleの配布先・Gradle互換性の問題を避けるため、[anatawa12のForgeGradle 1.2修正版](https://github.com/anatawa12/ForgeGradle-1.2)を利用する。バージョンは固定し、動的な `+` 指定は使わない。

## Prism Launcherへのインストール

ビルド後は次のスクリプトで配置する。ゲームが起動中なら中断し、指定バージョン以外の `mc-ai-companion-*.jar` を `.disabled` にして、
同一MODの複数バージョンが有効にならないようにする。配置したjarのSHA-256も表示する。

```powershell
.\scripts\deploy-mod.ps1 -Version 0.0.31
```

Daemonの入れ替えも専用スクリプトを使う。コマンドラインで対象を特定して古いDaemonを停止し、停止できなければ起動せず中断する。
起動後はポートの待受と該当プロセスが1つだけであることを確認する（PythonのHTTPServerは同一ポートへ二重bindできてしまうため）。

```powershell
.\scripts\restart-daemon.ps1
```

手動で行う場合の手順:

1. 対象のゲームを終了する。
2. Prism Launcherで `1.7.10-mod-basic` のフォルダーを開く。
3. `minecraft/mods/` に生成jarをコピーする。OpenALFixも残しておく。古いjarは `.jar.disabled` へ改名する。
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

上記の時刻付きログはPhase 0（0.0.1）の検証記録。Phase 1の現状と操作手順は冒頭を参照。


## 旧HANDOFF

﻿# エージェント引き継ぎ — 2026-09-26

## 最初に読むもの

「Skill終端結果を既存Socialで発話する共通機構」の設計は [protocol/skill-terminal-social.md](protocol/skill-terminal-social.md)。**Phase 1〜6を実装・自動テスト済み（MOD 0.0.28、実ゲーム未確認）**。調査基点は `develop` / `c5bdd36`。
終端snapshot、取消後の通知回収、会話キュー共有、表現選択、固定fallback、dedupe、実表示後の履歴登録を定義している。Phase 1〜4の実装範囲は下の「Skill終端 → Social発話」節を参照。

`collect_block` は **Daemon・Forgeとも実装済み（MOD 0.0.22）**。仕様と既存の検証記録は [protocol/collect-block.md](protocol/collect-block.md)。
MVPは `minecraft:log`、採掘数ではなく新規回収数で成功判定し、既存のmine_target/pickup_targetを同じSkill内で順序づける。

Skill Layer MVP（`collect_drop`）は [protocol/skill-layer.md](protocol/skill-layer.md) の仕様に沿って **実装済み**（MOD 0.0.12）。
mine primitive（`mine_target`）は [protocol/mine-primitive.md](protocol/mine-primitive.md) の仕様に沿って **実装済み**（MOD 0.0.17）。
Skill Layer硬化（review-7373e37 のP1〜5）を **実装・自動テスト済み**（MOD 0.0.18）。
block観測のcandidate品質改善（表面露出フィルタ）を **実装・実機確認済み**（MOD 0.0.19）。
Daemon の `agent/src/skill_protocol.py` / `execution_registry.py` / `skills.py`、Forge の `SkillProtocol.java` / `SkillExecutionState.java` と既存クラスへの追加。
入口は `!agent do collect_drop <アイテム> <個数>`、`!agent do mine <ブロック>`、`!agent do collect_block minecraft:log <個数>`、v2 typed protocol。Planner・自然文からのSkill引数抽出は範囲外。
自動テストはPython 155件・Java 83件。基本の収集・mine・collect_block（実ゲーム）を確認済み。Skill終端Social通知の基本動作（terminal発話の一度表示、present→表示→displayed ACK→履歴登録）を実ゲームで確認済み。硬化（P1〜5）は自動テスト範囲で、実機の危険条件は未検証。

このファイル → [init.md](init.md)（設計仕様）→ [README.md](README.md) → 必要に応じて [Agent README](agent/README.md) と [行動ライフサイクル](protocol/action-lifecycle.md)。
`init.md` に作業ログを追加しない。READMEのバージョン別の節は当時の検証記録として読む。

Phase 6（Game Actions）に着手済みで、`pickup` / `deposit` の2操作とSkill Layer、mine primitiveが完了している。残りは `attack / place / craft / smelt`。
計画順は「mine primitive → `collect_block`/`collect(log,N)` → attack/place/craft → Skillを数種 → JEV typed selection → 必要ならクラウド選択 → 高レベルPlanner」。`collect_block`/`collect(log,N)` は次段階で、この増分では実装しない。
仕様は「一度に全部作らない」「1.7.10のpathfinding / recipe / inventory APIを確認しながら追加する」を明記しているため、1操作ずつ追加する。

## 現在地（2026-09-26 確認）

| 項目 | 確認結果 |
| --- | --- |
| Git HEAD | `403e605` 時点からSkill Layer / mine primitiveを実装（本ドキュメント更新前は未コミット） |
| MODバージョン | `0.0.31`（`forge-mod/build.gradle` と `CompanionMod` の両方で管理）。ビルド済み・Prism配置はゲーム終了後（作業時に起動中で未配置） |
| Prismの有効MOD | `mc-ai-companion-0.0.29.jar`（SHA-256 `9880BCCCDA15D199152917F3A28C1563EC1AD9EC3A7B70F32BEB944F15779C78`）。0.0.28以前は `.disabled`。0.0.30は未配置 |
| Daemon | PID `43416` が `127.0.0.1:8767` で待受。`protocol 1+2, social=True`。**P1-1/P2-4のDaemon修正は再起動後に反映**（`--shutdown-token` 付き） |
| LM Studio | PID `21708` が `127.0.0.1:1234` で待受。`unsloth/gemma-4-26b-a4b-it` |
| Minecraft | 終了状態 |
| 自動テスト | Python **155件**・Java **83件**・Forgeビルド成功 |

プロセス・HEAD・作業ツリーは変化するため、次回は必ず再確認する。PIDファイルやこの表だけを根拠に停止しない。
**反映済み・確認済み。** ガラス越しの原木で `blocked` 経路が実機動作（原木は破壊されない）。0.0.17 で失敗文言を `失敗[blocked] minecraft:log 0/1`（理由を先頭の短い形）に変更し、実機で表示を確認済み。block観測は typeごと最近傍4・合計最大32、経時破壊は0.0.14で実機確認済み。

## Skill Layer硬化 — review-7373e37 の P1〜5（0.0.18）

`collect_block`/Plannerへ進む前のライフサイクル・安全性の増分。P2-6（表示/Social統合）とP2-7（restart script）は今回対象外。

| 指摘 | 閉じたコード変更 | 追加した回帰テスト |
| --- | --- | --- |
| **P1-1** 取消後の成功receiptで旧Skillが終端しない | `skills.py` に `_close_if_settled()` を追加し、成功receiptを一度だけ精算した後の先着規則（取消が先なら `cancelled`、requested到達なら `completed`）を一箇所へ集約。成功分岐で `phase=="cancelling"` を新action発行・`selecting` 復帰より優先 | `test_skills.py`: `test_cancel_then_partial_success_settles_cancelled` / `test_cancel_then_success_at_requested_stays_cancelled` / `test_success_then_cancel_settles_cancelled` / `test_replace_then_old_success_settles_old_cancelled_only` / `test_cancelled_skill_stays_terminal_after_clock_advances` / `test_cancelled_skills_do_not_accumulate_in_other` |
| **P1-2** 安全条件・期限が世界変更の後で評価される | 純粋クラス `SkillMutationGuard.java`（権限/owner/HP>6/距離≤32/lease/action期限/target同一性）を追加し、`CompanionEntity.mutationGuard` として `pickup` 収納直前と `breakBlock` の `setBlockToAir` 直前に配置。`CompanionEntity` に `setActionDeadline` を追加。`SkillTickPolicy.java` でtick判定を集約し、確定済みpositive outcomeを後のunsafe/expiredで0へ上書きしない（`ActionBridge.skillTick` が使用） | `SkillHardeningTest.java`: `mutationGuardBlocksUnsafeHealthAndOwnerLeash` / `mutationGuardChecksLeaseDeadlineTargetAndAuthority` / `aResolvedPositiveOutcomeIsFinalEvenWhenUnsafeOrExpired` / `aResolvedNegativeOutcomeUsesTheEntityReason` / `anUnresolvedActionReportsTheFirstSafetyFailure` |
| **P1-3** 旧SkillのHTTP応答が新Skillの失敗として処理される | 純粋クラス `SkillRequestFence.java`（generation・session・revision・epoch・kind・開始時刻付き `Request`/`Completion`）を追加。`ActionBridge` の共有 `skillResponse/skillDone/...` を generation別の `openCall/goalCall/cancelCall`＋volatile `*Done` に置換し、新Skill/置換/session変更で `advance()`。古いgenerationの完了は破棄し、その完了で現在のin-flightを解除しない。古すぎる応答はleaseを延長しない | `SkillHardeningTest.java`: `advancingTheGenerationDiscardsOldRequests` / `openAckNeedsTheSameSessionAndAEpoch` / `cancelAckNeedsMatchingIdentityAndIdle` |
| **P2-4** v2 legacy goalとSkillのrevision/取消管理が別 | `skill_protocol.py validate_goal` は v2 の文字列goalを `legacy_goal_unsupported`（400）で明示拒否、`goal:null` は取消として許可。`SkillManager` の `goals` 依存・`_legacy`/`_legacy_view` を削除し `daemon.py` を更新。v1 `/v1/goal` のlegacyは不変 | `test_skills.py`: `test_v2_rejects_legacy_string_goals_but_allows_null_cancel` |
| **P2-5** cancel handshakeの失敗を成功扱いする | `startSkillGoal(goal, cancel)` と cancel専用 `Request` を追加。ACKは `SkillRequestFence.validCancelAck`（session/revision/epoch一致＋`status=="idle"`）のみ成功。timeout/null/409/staleは未完了として約1秒間隔で最大3回再試行し、未確認なら `failSkill("disconnected")` で安全停止（無限待機しない）。ローカル停止は `finishCancelHandshake` で通信を待たない。ACKを再送で成立させるため `skills.py` の `goal:null` を同一revisionでも冪等取消として受理 | `test_skills.py`: `test_null_cancel_is_idempotent_at_same_revision` ＋ `SkillHardeningTest.cancelAckNeedsMatchingIdentityAndIdle` |

維持を確認した不変条件: `collect_drop` 成功条件、`mine` count=1、1 action = 1 world mutation、acquired/destroyed分離、receipt idempotency、terminal result immutability、control lease、result queue reservation、opaque/projection境界、Daemon tickからHTTP/LLMを呼ばないこと。

## Skill transport硬化 — review-7cb43e9 の P1/P2（0.0.20）

`collect_block` 前の増分。前回P1〜5・BlockExposure/BlockCandidatesは維持。

| 指摘 | 閉じたコード変更 | 追加した回帰テスト |
| --- | --- | --- |
| **P1** 期限切れの初回action応答をclaimできる | `SkillRequestFence.fresh(request, now, lease)` を追加し、`ActionBridge.consumeSkill` の入口で `now - request.started > CONTROL_LEASE` の応答を**捨てる**（受信時刻ではなく送信時刻基準なので、pause中にworkerが早く返していてもgame threadのconsume時ageで判定）。stale応答はlease更新・新規claim・world mutationにつながる遷移に使わず、`failSkill` の理由にもしない。次のfresh pollでDaemon状態を取り直す。ローカルの安全停止（`SkillMutationGuard`／tickの安全net）は従来どおり先に適用 | `SkillHardeningTest.aReadyResponseOlderThanTheLeaseIsStale` |
| **P2** 世代付きCompletionの単一volatile欄で最新完了を失う | 共有 `openDone/goalDone/cancelDone` を廃止し、`SkillRequestFence.Request` が `volatile Completion completion` を保持。workerは自requestの `completion` にのみ書き、game threadは `goalCall.completion` 等**現在request自身**だけを読む。旧requestの完了は新requestのcompletionを上書きできない。`clearSkillCalls` は現在request参照のみnull化 | `SkillHardeningTest.perRequestCompletionIsNotErasedByALaterRequest` / `aLateWorkerForAnOldRequestCannotEraseTheNewerCompletion` |
| **P2 timeout** completionが失われても永久待機しない | `Request.expired(now, REQUEST_TIMEOUT_NANOS=6s)` を追加。open/goalはcompletion未到着でも期限超過でrequestを破棄して再送可能状態へ。cancelは既存の最大3回再試行と同じゲートに統合（期限超過は未確認試行として数える） | `SkillHardeningTest.anUnansweredRequestExpiresSoItIsNeverAwaitedForever` |

stale responseのreject箇所: `ActionBridge.consumeSkill` 入口の `SkillRequestFence.fresh(...)`。completionのdelivery: request-scoped `Request.completion`。lost completion: `Request.expired(...)` による有界timeout（open/goalは再送、cancelは3回上限）。

## collect_block — Daemon層＋Forge接続（0.0.21）

指示書 [protocol/collect-block.md](protocol/collect-block.md) をDaemon・Forgeとも実装（**実ゲーム確認済み**）。

- `skill_protocol.py`: collect_block goal解析（blockは `COLLECT_BLOCK_TARGETS` のみ、count1〜64、constraints空のみ、未知キー拒否）、capability `collect_block_v1`、終端理由 `drop_unavailable`。
- `skills.py`: **発行action descriptor台帳**へ組み替え。`SkillManager.result` の `target_field` 前提を外し、`_apply_result` は descriptor の payloadField（item→acquired / block→mined）で一度だけ精算。mine_target と pickup_target を同一Skillで交互発行できる。`collect_drop`/`mine` の意味・view・progressは不変。
- `CollectBlock`: stage `select_source / wait_drop / recover_drop`。成功条件は `acquired`（このSkillが収納した数）。開始前所持は数えない。`mined` は副作用カウンタ。mine成功では完了せず新観測を待ち（6秒・延長なし、失敗時 `drop_unavailable`）、回収が成功するまで次mineへ戻らない。blockedは別block/dropへ、尽きたら `blocked`。tool_unavailable/inventory_full/unsafeは即終端。取消は確定済み両カウンタを保持。
- テスト: `agent/tests/test_collect_block.py` 16件（混合action列・旧receipt再送ACK・過大count/種類違い拒否・drop_unavailable・取消精算・blocked・retry_exhausted 等）。既存 `test_skills.py` は互換のまま、capability期待値のみ更新。
- 追補（review指摘の2点）:
  - **descriptor基準のreceipt検証を共通化**（`_validate_receipt`）: actionId→descriptorを引き、`actionSequence`/`payloadField`/`targetCanonicalId`/`count<=maxCount` を毎回照合。`running`=accepted+0、`succeeded`=completed+1..maxCount、`failed`/`cancelled`=0 を固定。duplicate terminal=ACK、late running=ACKのみ（progress不変）、矛盾terminal=409、未知actionId=unknown_action。terminal Skillでも同じ検証。
  - **wait_drop/recover_dropをstage固有の絶対deadlineへ分離**: `wait_deadline = mine receipt + DROP_WINDOW`、`recover_deadline = 最初のrecovery失敗 + DROP_WINDOW`。poll/候補入れ替えで延長しない。wait_dropはnewer観測なしで期限到達→`stale_state`、newer観測ありでitemなし→`drop_unavailable`。recover_dropは回収成功まで次mineへ戻らない。generic `_fence_ready()` はselect_sourceのみで使用（simple Skillのfence挙動は不変）。
  - 追加テスト: descriptor不一致/種類違い/ID違い/succeeded count0/reason不正/late running ACK/terminalでのdescriptor検証、wait deadline固定/poll非延長/境界、recover deadline非延長・次mine禁止、120秒上限。
- テスト件数: Python **118件**（collect_block 27件）・Java **70件**。
- **Forge接続（0.0.21）**:
  - `SkillProtocol.java`: `collect_block` の type/target/progress union（`requested/acquired/mined/complete`、acquired・mined≦requested）を厳密解析。`COLLECT_BLOCK_ITEMS`（block→item対応表、現状 log→log）と `capabilityFor`/`collectItemFor`/`capabilities`。
  - `ActionBridge.java`: 入口 `!agent do collect_block <ブロック> <個数>`。open応答のcapabilitiesを確認し、`collect_block_v1` 未広告なら**mine/collect_dropへ代替送信せず**メッセージして安全停止。current action種類は descriptor ごとに `claimedField` で判定（mine/pickup混在）。`finishSkill` は §11 表示（完了「…をN個集めました（採掘Mブロック）」／失敗「失敗[reason] 回収A/R、採掘M」／取消「取り消しました。回収A/R、採掘M」／complete=falseなら「未確定の操作があります。」）。
  - `CompanionEntity.java`: `pickupItem(targetRef,maxCount,itemName)` に拡張し、UUID一致でもregistry名不一致なら `target_lost`（実収納直前の同一性確認）。mine/pickupは既存を再利用。
  - `SkillExecutionState.java`: 同一Skill内の mine→pickup→mine で sequence前進・旧actionId再実行禁止を確認（追加テスト）。変更なし。
  - 追加テスト: `SkillProtocolTest` の collect_block view解析・progress union厳密性・対応表/capability・capability解析・混合sequence。Daemon側 `COLLECT_BLOCK_TARGETS` とJava `COLLECT_BLOCK_ITEMS` の一致は両言語のfixtureで固定（Python側 `test_collect_block_mapping_is_the_fixed_mvp_pair`）。
- 実ゲーム: `!agent do collect_block minecraft:log 5` で原木を採掘→回収して完了することを確認済み（Daemon再起動＋ゲーム0.0.21読み込み後）。既存の collect_drop / mine / follow 等に退行なし。
- **Forge hardening追補（0.0.22）**:
  - `SkillProtocol.validateBinding(skill, action)` を追加し、claim前に `action.skillInstanceId == skill.skillInstanceId` と Skill種別ごとの action.type/target 対応（collect_drop=pickup+item一致、mine=mine+block一致、collect_block=mine(block一致) or pickup(mapping item一致)）を検証。`validateGoalBinding(skill, type, target, requested)` で開始済みgoalとも照合。`ActionBridge.consumeSkill` は claim・lease・world mutation の前に両者を呼び、不一致は `failSkill("action_failed")`（未claimなので偽receiptを送らない）。
  - `SkillProtocol.Skill` の terminal result を厳密parse: `result.skillInstanceId == outer`、status∈{completed,failed,cancelled}、`result.progress` を outer と同じschemaでparseし全フィールド一致を要求。completedは `complete==true` かつ成功カウンタ==requested（collect_blockはacquired、mineはmined）。failed/cancelledは部分成果可。`phase==terminal ⇔ result!=null` も固定。
  - `CompanionEntity.collectTarget` 冒頭で実収納前にregistry名を再確認し、不一致は `pickupStored=0`/`target_lost`（world mutationなし）。
  - 追加Javaテスト4件: binding不一致/許可（instance・type・target・未対応action）、goal binding、terminal result/progress不一致・completed不変条件・phase/result整合、混合sequence claim。Java 74件。
- 未消化: `ActionBridge` の配送→consume→claim を通す統合テスト（Minecraft依存のため未）、および §13 の一部異常系（収納満杯・回収途中停止・経路失敗・pause/退出）の実機確認。

## Skill終端 → Social発話 — Phase 1〜6（0.0.31）

仕様 [protocol/skill-terminal-social.md](protocol/skill-terminal-social.md) の段階1〜3を実装。**事実のauthorityはSkillの確定terminal result**、Socialは表現のみ、Forgeはworld/表示のauthority。LLM候補選択・conversation履歴登録はPhase 4〜6で未実装。

- `agent/src/terminal_presentation.py`（新）: 静的 `DESCRIPTORS`（task/metric/metric label/unit/表示順）、`TARGET_LABELS`、`REASON_MEANINGS`（reason→許される短い意味。未知reasonは推測せず enum 表示）、固定 `render_fallback`（≤512 UTF-16、数値・未確定注記を切らない）。`mined` は「Nブロック破壊」、`acquired` は累積取得で「今も持っている」とは言わない。
- `agent/src/terminal_events.py`（新）: `TerminalEventStore`。`_finalize` で一度だけ登録する outbox（最大100・TTL600）、identity `(daemonEpoch, session, skillInstanceId, terminalId)`、epoch/session内で単調増加の `eventSequence`、read-only snapshot（最大8・`afterSequence`/hasMore/overflow）、presentation台帳（not_started/generating/ready/delivered/suppressed）、present/deliverの重複排除・binding照合・同一Skill別terminalId衝突。
- `skill_protocol.py`: capability `skill_terminal_social_v1`、`validate_terminal_event`（typed progress union、bool/負数/未知キー拒否、completed不変条件）、`/v2/terminal-events`・`/v2/social/skill-terminal`・`/v2/social/terminal-delivery` の厳密validator。
- `skills.py`: `_finalize` が result の deep copy を authority として event を一度だけ記録（provider/HTTPを呼ばない、再finalize防止）。
- `daemon.py`: 3 endpoint。epoch不一致は409、未知terminal=404、破棄済み=410。read取得はaction/leaseを変更しない。
- `forge-mod/.../TerminalDeliveryState.java`（新、純粋）: identity台帳、received/queued/inFlight/displayed/suppressed、timeoutとHTTP完了が同一tickでもfirst-winsで一度だけ表示。
- `forge-mod/.../TerminalPresentation.java`（新、純粋）: Pythonと同一文字列の固定renderer。`protocol/fixtures/skill-terminal-fallback.json` で両言語一致を検証。
- `forge-mod/.../SkillProtocol.java`: `TerminalEvent` 厳密parse（typed progress、completed不変条件）と `TERMINAL_CAPABILITY`。
- `forge-mod/.../ActionBridge.java`: openの `skill_terminal_social_v1` がある時だけ新経路。`finishSkill` は実行状態を解放して表示をoutbox dispatcherへ委譲。`terminalTick` が `/v2/terminal-events` をCONTROL laneで取得し、`TerminalDeliveryState` でidentity重複排除して固定fallbackを一度だけ表示。**旧Daemon（capabilityなし）は従来の終端表示を維持**（二重表示しない）。
- 追加テスト: `test_terminal.py`（Python 19件: fixture一致・projection/不変・nested mutation不変・bool/負数/未知キー拒否・duplicate poll/present/ACK・ACK後outbox閉鎖・closed identityのpayload conflict・exact closed ledger追い出し後もidentity再生成なし・presentation台帳有界化とsay解放・duplicate recordのsequence非消費・binding/同一Skill衝突・旧revision・cancel後着・capability・HTTP3endpoint）、`TerminalSocialTest.java`（Java 5件: fixture一致・配送first-wins/重複/期限・terminal event parse/拒否・terminal pollのSkill制御優先）。
- 配送基盤の追加修正（最終レビュー）: `closed`（exact, 100件, sayなし）とは別に **compact closed digest 台帳（1024件, identityのblake2b 8byte→payload fingerprint）** を持ち、exact recordがLRU追い出しされても identity を再生成しない（同一payloadは no-op、異なるpayloadは `terminal_identity_conflict`）。保証範囲は digest 台帳内（後述）。terminal poll は `SkillRequestFence.terminalPollAllowed(open, goal, cancel, skillControlImminent)` に変更し、**active Skill だけでは停止しない**。control request in-flight/queued、またはこのtickで control を enqueue する場合のみ見送る（starvation回避）。
- 配送基盤の追加修正（feature/skill-social 再レビュー）: presentation台帳と closed identity 台帳を各100件に有界化（`MAX_PRESENTATIONS`/`MAX_CLOSED`）。closed は state/binding/ACK/payload fingerprint のみ保持し say を解放。`record` は identity を sequence 採番より先に確認し、同一 payload は既存返却（sequence非消費）、内容違いは `terminal_identity_conflict`。closed後も fingerprint で同じ性質を維持。`record` は nested を含め deep copy で store が snapshot を所有。CONTROL lane は `SkillRequestFence.terminalPollAllowed` により、active Skill / skill request in-flight 中は terminal poll を見送り Skill 制御を優先。
- 配送基盤の修正（feature/skill-social）: `TerminalEventStore.deliver` は displayed/suppressed ACK で outbox entry を閉じ（`_close`）、`afterSequence=0` の再pollにも出さない。ACK済み identity の `record` は再生成しない。`record` は identity を sequence 採番より先に確認し、同一 immutable payload の再送は既存 event を返して sequence を消費しない（内容違いは `terminal_identity_conflict`）。
- **Phase 4（0.0.25）**: `ConversationQueue` を `USER_CHAT`/`SKILL_TERMINAL` のtyped entryへ拡張（chat 4件・2048字は維持、terminal待機枠8件、単一FIFO）。`PingBridge` が両種を同一順序で処理し、terminalは `enqueueTerminal` で会話contextを初期化（typed commandだけでも通知可能）。terminalはSocial workerを使わず固定fallbackを表示。待機terminalが12秒でFIFO例外として先行chat中でも表示。forget時は未表示terminalを旧会話として先に表示→reset。退出/owner変更で `reset(null)`（末消化terminalは抑制）。At-most-onceは `TerminalDeliveryState`＋`PingBridge.seenTerminals` で維持。追加Javaテスト: `conversationQueueMixesChatAndTerminalInOneOrder` / `conversationQueueKeepsSeparateCapacitiesAndTerminalDedupe`。
- **Phase 4追補（0.0.26）**: terminal poll cursorを `TerminalPollCursor`（純粋）へ分離し、world/session切替の `synchronize()` で `terminalPoll.reset()`（afterSequence=0, nextPoll=0）。新 `(daemonEpoch, session)` は eventSequence=1 から取得でき、旧cursorを持ち越さない。`observe` はbinding内で単調。テスト: `terminalCursorResetsPerBindingAndNeverGoesBackward`。
- **Phase 5（0.0.27）**: 既存providerで候補選択。`terminal_presentation.render_candidates` が friendly/calm/concise の**事実完全な3候補**（≤512、Python/Java同一）を生成。`LocalSocialProvider.select_terminal` は通常chatと同じprovider設定・persona・予算で、候補IDのenumに限定した厳密schema＋8秒deadline。`SocialBrain.present_terminal` は固定rendererではなく**候補IDを選ばせ**、sayを候補から復元（自由文・intent・追加キー・未知IDは `SocialError`）。transport情報（session/epoch/UUID）はLLMへ送らない。`/v2/social/skill-terminal` は presentation台帳で generating/ready を管理し、**同じrequestでproviderを二重に呼ばない**。provider未設定/busy/例外/予算超過は固定fallback（mode=fallback）で同一say。fallback fixtureは candidates も含みPython/Java一致を検証。追加テスト: candidates fixture一致（Py/Java）、present_terminalのprovider選択/失敗/ busy、endpointの provider1回・二重present・fallback。
- **Phase 5追補**: terminal presentationは通常chatと**同じconversation identity `(conversationSession, player)`** の履歴を `budget.prepare` に渡して参照する（読むだけ。terminal自体は履歴へ追加しない。別conversationの履歴は混ざらない）。presentation生成競合は first-wins: `present_begin` の duplicate（generating中）はその場で fallback を ready 確定し、遅れて来たprovider結果は `present_finish` が上書きしない。ACK後も closed と整合（ACK済みvariantと矛盾するresponseを返さない、closedを再openしない）。追加テスト: 履歴参照/非更新/別会話分離、generating duplicateのfirst-winsとACK後整合。
- **Phase 6（0.0.28）**: delivery ACKで履歴登録を接続。`/v2/social/terminal-delivery` の **displayed ACK時だけ**、`SocialBrain.register_terminal` が通常chatと同じ `(conversationSession, player)` 履歴へ `[内部イベント skill_terminal] <確定事実>` / assistant(実表示say) の1ペアを一度だけ追加（suppressed/重複ACKは追加なし、variant不一致・ACK後のlate providerは409/据置）。forgetは削除がbusyでも先にsessionをretired記録し、通知生成・ACK・遅着chat commitによる履歴再作成を拒否。Forgeは `PingBridge` のSOCIAL workerで `/v2/social/skill-terminal` を呼び、返却sayが**自分の候補集合に一致するときだけ採用**（不一致は固定fallback）。12秒未表示はfallback表示＋ACK。
- **Phase 6追補（0.0.29）**: 表示とACKの順序を修正。SOCIAL workerは**presentation選択のみ**を行い、完了情報 `TerminalDone(epoch, identity, request, say, variantId)` をgame threadへ渡す。game threadで `TerminalAckPolicy.shouldDisplay(epoch, conversationEpoch, ownerPresent)` を判定し、**表示可能なら `reply(say)` → その後 displayed ACK**、reset/forget/owner・world変更後なら **suppressed ACK**（表示なし・history登録なし）。`reset()` が先にsuppressedを送っていても後段はepoch不一致でsuppressedに留まり、reset後displayed ACKを送ることはない。追加テスト: `TerminalAckPolicy` 判定、既存の displayed=1ペア/suppressed=0/duplicate増加なしを維持。
- **review-f33f7f5 対応（0.0.30）**: 5件の配送欠落を修正。
  - (1) present未実行/生成中のfallback ACK: `TerminalEventStore.deliver` は presentation entry が無くても snapshot から固定fallbackを確定してACKを適用（No presentation）。生成中entryはfallbackをfirst-winsで確定。生成済みsocialにForgeがfallbackを表示した場合も `variantId=fallback` を受理（他のvariantは409）。自由文はACKから受け付けない。
  - (2) ACK executor満杯: `PingBridge` は game thread の有界 `pendingAcks`（**現在32件**）から、通常Social生成より先に1件ずつ送信。`io.execute` に未受理のACKはこの範囲で保持し次tick再試行する（executor rejectで即消える問題は解消）。ただし **pending queue自体は32件上限**で、32件を超える異常backlogでは新しいACKを**dropして警告ログ**を出す。その場合、表示済みterminalの **history登録・outbox close は欠落し得る（best-effort）**。表示済みチャットは巻き戻さず、Skillを再実行せず、terminalを再表示しない。保証範囲: 通常の有界backlog内=表示→ACK再送→history/outbox close、32件超=表示維持・ACK/history/outbox closeはbest-effort。
  - (3) 生成中も12秒期限: Forgeの配送台帳 `TerminalDeliveryState` を実際の経路へ接続。enqueue時刻からの絶対12秒でfallback表示（first-wins）。表示済みは遅着応答で再表示しない。
  - (4) forget中の生成terminal: `displayAllPendingTerminals` が in-flight を含む未表示terminalを固定表示してから会話を切替（world/owner変更のsuppressedとは分離）。
  - (5) forget cleanupの取りこぼし: cleanupを種別非依存の `pendingCleanup` として保持し、Social生成より先に送信。次entryがterminalでも旧sessionの `!agent forget` を失わない。
  - 追加テスト: presentなしdisplayed/suppressed、生成中fallback first-wins、ready socialへのfallback表示許可と他variant409、fallback ACKでの履歴1ペア。Python 155件。
  - `PingBridge.reset()` は旧binding破棄後に `seenTerminals.clear()` し、session/world/daemon bindingをまたいでterminal dedupe stateを持ち越さない（terminal identityは `(daemonEpoch, session, skillInstanceId, terminalId)` のため）。
- 未実装/未確認: Daemon epoch変更時の「旧作業」明示表示は未実装。基本動作は実ゲーム確認済みだが、review-f33f7f5の5件（ACK満杯・生成中12秒・forget中生成・cleanup取りこぼし・presentなしACK）は自動テストのみ。



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
  control lease: action実行中もForgeが約1秒ごとに `/v2/goal` をpollしてleaseを更新し、最後の検証済み同epoch/session/revision応答から5秒を超えると `CompanionEntity` が収納直前（world変更前）に停止する。Daemonも最後のcontrol pollから5秒を超えたら新actionを発行しない（`status`取得やaction結果ではleaseを更新しない）。
  lease/receipt追加反映: leaseは受理が確定したpollのみ更新（`stale_goal`/`conflicting_goal`/`stale_state`等の拒否では更新しない）。action receiptの `goalRevision` を該当Skillのrevisionと照合し、不一致は409。terminal後に届いた既知actionのreceiptはrecorded/ACKのみで `settled` に記録し、terminal resultとprogressは変えない（未知IDは409）。
- **mine primitive（0.0.13→0.0.19 / protocol 2）**: `mine_target`（1 action = 1 block破壊）と mine候補のblock観測。
  観測はCompanion周辺16ブロック（水平±16・垂直±8）のallowlist blockのみ。typeごとに最近傍4件・合計最大32候補（一律N件だと近いdirtがlog/oreを締め出すため）。Daemonはopaqueな `block-<x>_<y>_<z>`、registry名、距離だけを扱う。
  **表面露出フィルタ（0.0.19）**: 6近傍のいずれかが「air / 非固体material / leaves」のblockだけを候補にする（`BlockExposure`）。glassはsolidなので露出扱いにしない。埋まったdirtや壁内部のoreが最近傍候補を占有して `blocked` になる経路を防ぐ。これは観測候補の品質のみで、実行可能性のauthorityは `MineObstruction`／`MineTargetTask` のまま。上限（typeごと4・合計32）は `BlockCandidates` で維持。
  道具選択はForgeが決定的（`Material.isToolNotRequired()` なら素手、必須ツールが無ければ `tool_unavailable`）。破壊はblock hardnessとtool speedに応じた経時処理（`destroyBlockInWorldPartially`/`swingItem`）。
  遮蔽/到達可能性のauthorityは採掘距離へ移動した後の `MineTargetTask`（`MineObstruction`＝`Material.isSolid()`＋leaves例外、MaterialLookupで純粋化）で、採掘開始前とworld変更直前の同一tickで block種・lease・遮蔽を再検証する。claim前は target identity/type/range のみ再検証し、現在位置からの直線遮蔽判定はしない（回り込める壁越しのtargetを誤除外しないため）。遮蔽時は `blocked`。観測候補が全て `blocked` なら**検索窓を待たず即座に**最終理由 `blocked`（`no_block_in_range` と区別）。邪魔なブロックは破壊しない（1 action = 1 block）。`mine` goal の count は1固定（2以上は `unsupported_count`）。
  進捗は `mined`、receiptは `destroyed:{block,count}` で `collect_drop` のprogressとは混ぜない。入口は `!agent do mine <ブロック>`。詳細は [protocol/mine-primitive.md](protocol/mine-primitive.md)。

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
| Skill（MOD） | `SkillProtocol.java`、`SkillExecutionState.java`、`ActionBridge.java` の `requestSkill`/`requestMine`/`skillTick`、`CompanionEntity.pickupItem`/`mineBlock` |
| block観測（Daemon/MOD） | `state_cache.py` の `TASKS / RESULTS / block_candidate`、`ObservationBridge.java`/`ObservationDiff.java`、`SkillProtocol.BLOCKS` |

Skillを増やすときは allowlist（Daemon `SUPPORTED_ITEMS`/`SUPPORTED_BLOCKS` と MOD `SkillProtocol.ITEMS`/`BLOCKS`）と fixture を同時に更新する。

`IntentTest.everySupportedIntentIsAccepted` がDaemonの全intentをMOD側に通す回帰テストなので、intentを増やしたらここにも追加する。

## 検証状況

直近の自動検証はPython **83件**・Java **48件**・Forgeビルド成功（0.0.17時点）。

実ゲームで確認済み（0.0.9～0.0.14分）:

- デバッグ通知OFFでチャットが静かになること、状態アイコンの切り替わり。
- 会話「そこに落ちてるの拾って」→ 判断 → 拾得完了 → `!agent status` の所持品に反映（`minecraft:sand x1`）。
- 会話「持ってるもの渡して」→ 近づいて受け渡し → 所持品が空になること。
- 「近くに拾えるアイテムがありません」「種類は指定できない」の拒否経路。追従・拾得に回帰がないこと。
- **Skill Layer（0.0.12）**: `!agent do collect_drop minecraft:stick 2` で対象固定の収集が完了すること。
- **mine primitive（0.0.14）**: `!agent do mine <ブロック>` で、プレイヤーと同じく時間をかけた経時破壊が行われること。
- **block観測の表面露出フィルタ（0.0.19）**: `!agent do mine minecraft:dirt` で、目の前に露出した土を正常に選んで採掘できること（埋まった土を最近傍候補として選び `blocked` になる経路が解消）。

実モデルで確認済み: pickup/depositの判断（対象あり・なしの両方）、会話からの `pickup_item` / `deposit_items`、否定・種類指定の拒否。

未確認・残る制限:

- mine primitive（0.0.17）の遮蔽authority・`blocked` 即終端・失敗表示（理由先頭の短い文言）は実機確認済み。回り込めるsolid wall越しの採掘・leaves越しは未検証。
- mine primitive（0.0.14）の経時破壊は実機確認済み。素手/道具の選択・`tool_unavailable`・連続採掘・block観測の網羅性は未検証。
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

ビルド・配置・Daemon操作は専用スクリプトに統一した（`.claude/settings.json` と `opencode.json` で許可済み）。

```powershell
.\scripts\forge.ps1 build                    # ビルド＋Javaテスト
.\scripts\run-python-tests.ps1               # Pythonテスト（引数はそのまま渡せる: -v）
.\scripts\deploy-mod.ps1 -Version 0.0.17     # Prismへ配置（ゲーム起動中なら中断）
.\scripts\restart-daemon.ps1                 # Daemon入れ替え（graceful shutdown→起動、二重起動を拒否）
.\scripts\stop-daemon.ps1                    # Daemon停止のみ（loopback /local/shutdown、昇格不要）
.\scripts\daemon-status.ps1                  # 待受PID・プロセス数・protocol行・token有無・ログ末尾（読取専用）
.\scripts\daemon-state.ps1                   # 観測キャッシュの要約（items/blocks数とサンプル、読取専用）
.\scripts\show-daemon-logs.ps1               # Daemonログのtail（読取専用）
.\scripts\read-mc-source.ps1 -SourceEntry net/minecraft/world/World.java -Pattern setBlockToAir   # 逆コンパイル済みMCソースの閲覧（読取専用）
```

`deploy-mod.ps1` は指定バージョン以外の `mc-ai-companion-*.jar` を `.disabled` にし、配置後のSHA-256を表示する。
`restart-daemon.ps1` はまず loopback `/local/shutdown` で終了させ（管理者起動のDaemonでも昇格不要）、生存時のみプロセス停止へフォールバックし、起動後は該当プロセスが1つだけであることを検証する。
初回cloneは `.\scripts\setup-jdk.ps1` → `.\scripts\forge.ps1 setupDecompWorkspace build`。開発起動は `.\scripts\forge.ps1 runClient`。

`opencode.json` では上記スクリプト・`python -m unittest discover -s agent/tests`・読み取り専用の `Get-*` を allow にし、`Measure-Command` や任意URLへの `Invoke-RestMethod`、`Add-Type` など任意実行になり得るものは allow していない（必要なときだけ承認する）。

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
- `agent/src/daemon.py` / `state_cache.py`: HTTPと観測キャッシュ（owner/companionのインベントリ、hostiles、items、blocks）。
- `agent/src/skill_protocol.py` / `skills.py` / `execution_registry.py`: v2のSkill語彙・検証、`collect_drop`/`mine` の状態遷移、実行権と結果台帳。
- `forge-mod/src/main/java/local/mcai/` の `PingBridge`・`ConversationQueue`・`PingClient`: 会話FIFO、返信の世代、intentの許容リスト。
- 同 `ActionBridge`・`GoalState`・`ActionProtocol`: 実行権限、世代管理、制御と結果通知、操作ごとの安全条件。
- 同 `IoExecutors`: 4系統の通信Executor。`CompanionHud`: 状態アイコン（クライアント専用、ClientProxy経由で登録）。
- 同 `CompanionEntity`・`CompanionCommands`: 個体・経路探索・操作・9スロットのインベントリ（follow / pickup / deposit / pickup_target / mine の各タスク）。
- 同 `ObservationBridge`・`ObservationDiff`: 観測・差分・ACK・再同期。`blocks` は周辺16ブロックのallowlist block候補（typeごと最近傍4・合計最大32）。
- 同 `SkillProtocol`・`SkillExecutionState`: v2のSkill view/action検証、claimとaction台帳。
- `protocol/` と各tests: 通信仕様・fixture・回帰テスト。

観測は毎秒、ACK済み位置から2ブロック以上の累積移動で座標送信。無変更でも約5～6秒ごとに空イベント。
落下物は `item-<UUID>` で最大16件、採掘候補blockは `block-<x>_<y>_<z>` でtypeごと最大4・合計最大32件、距離は2ブロック刻み。インベントリ差分は `entity`（owner/companion）で区別する。
キャッシュは32セッション・各100イベント。会話は32セッションで予算内の直近往復を保持。両方メモリのみ。

## 次の機能候補（未着手）

Phase 6の残りは `attack / place / craft / smelt`。`pickup` / `deposit` / `mine` は完了しているので再実装しない。
計画順は「mine primitive → `collect_block`/`collect(log,N)` → attack/place/craft → Skillを2〜4種 → JEV typed skill selection → 必要ならクラウド選択 → 高レベルPlanner」。

- 次段階は `collect_block` / `collect(log,N)`: `mine_target` と `pickup_target` を順序づけるSkill。`collect_drop` の意味・成功条件は変更しない（§15）。mineの破壊数とpickupの取得数を同じprogressへ二重加算しない。
- `attack`: 敵の観測（`hostiles`）は既にあるため判断入力は揃っている。対象選択の可否、武器・ダメージ、危険時の撤退など安全条件の設計が増える。
- 実ゲーム未検証項目（mine、インベントリ保持、満杯時の挙動、経路失敗）を潰してから次へ進む選択肢もある。
- goto・長期記憶、JEV/クラウドprovider、GUI設定、マルチプレイヤーは未実装。高度な割り込み分類、予約実行、進捗を用いた会話も後続。

## ログ・成果物の参照

- Daemonのログ: `.tools/daemon.stdout.log` / `.tools/daemon.stderr.log`、PID記録 `.tools/daemon.pid`（現在性は保証しない）。
  `intent-*` `lifecycle-*` `phase*-*` は過去セッションの記録。
- ゲームログ: Prism内 `minecraft/logs/fml-client-latest.log`（MODの初期化・例外）と `latest.log`（チャット、CP932）。
- 配置済み0.0.17のSHA-256: `9847AE37AE8A91ED7357A2CDAB90FC18C45453A8E21E39F18ACC3EE04A89E681`。
- Claude向けの権限設定は `.claude/settings.json`（読み取り専用コマンド、上記3スクリプト、WebFetchの許可ドメイン）。

古い手順・実装経緯はGit履歴から参照できる。過去のPIDや「未コミット」「起動したまま」を現在の状態として扱わない。

