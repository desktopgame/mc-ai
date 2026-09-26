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

生成jarは `forge-mod/build/libs/mc-ai-companion-0.0.22.jar`。Daemonも同じ版へ更新する。
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

生成jarは `forge-mod/build/libs/mc-ai-companion-0.0.22.jar`。
2026-09-26: Python **118件**・Java **74件**とビルドに成功。実ゲームで `!agent do collect_block minecraft:log 5` の原木収集を確認済み。skill/action binding検証とterminal result厳密parseを追加。

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

成果物は `forge-mod/build/libs/mc-ai-companion-<version>.jar`（現在は `0.0.22`。バージョンは `forge-mod/build.gradle` で管理する）。
開発クライアントのゲームディレクトリは `forge-mod/run`。
現行MODの初期化メッセージは `MC AI Companion initialized (Action lifecycle)`。以下のPhase 5のログ例は当時の記録。

旧ForgeGradleの配布先・Gradle互換性の問題を避けるため、[anatawa12のForgeGradle 1.2修正版](https://github.com/anatawa12/ForgeGradle-1.2)を利用する。バージョンは固定し、動的な `+` 指定は使わない。

## Prism Launcherへのインストール

ビルド後は次のスクリプトで配置する。ゲームが起動中なら中断し、指定バージョン以外の `mc-ai-companion-*.jar` を `.disabled` にして、
同一MODの複数バージョンが有効にならないようにする。配置したjarのSHA-256も表示する。

```powershell
.\scripts\deploy-mod.ps1 -Version 0.0.22
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
