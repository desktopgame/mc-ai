# Protocol v1 / v2 — v0.1.0

## 現在のAPI索引

MOD v0.1.0はwire protocol v1/v2を併用する。以下のPhases 1～5の説明は導入時の履歴も含む。現在の実行経路はこの表と各仕様を参照。

| protocol | endpoint | 責務 |
| --- | --- | --- |
| v1 | `/v1/turn` | ping、会話、forget、限定intent。actions配列は空 |
| v1 | `/v1/snapshot` / `/v1/events` / `/v1/state` | 上限付き観測同期・状態参照 |
| v1 | `/v1/decision` | 判断単体の検証。自身では実行しない |
| v1 | `/v1/goal` / `/v1/action-result` | follow/stop/look/pickup/depositの非同期実行管理 |
| v2 | `/v2/execution/open` / `/v2/goal` / `/v2/action-result` / `/v2/skill-status` | collect_drop/mine/collect_blockとreceipt精算 |
| v2 | `/v2/terminal-events` / `/v2/social/skill-terminal` / `/v2/social/terminal-delivery` | 確定終端の取得・表現・表示ACK |

[行動ライフサイクル](action-lifecycle.md)、[Skill Layer](skill-layer.md)、[mine](mine-primitive.md)、[collect_block](collect-block.md)、[終端Social通知](skill-terminal-social.md)。
v2にlegacy文字列goalを送らない（拒否）。観測にはblocksも含み、item参照は実UUID由来。モデルへのprojectionとは別の層である。
実装と設計の差分・保存上限は [既知の問題](../known_issue.md) を参照。

## コンテキスト予算超過

`/v1/turn` と検証用 `/v1/decision` は、モデルへの入力予算超過時にHTTP 422を返す。
形式は `{"version":1,"error":"mandatory_prompt_exceeds_budget"}`（Socialの必須入力だけで超過）、
または `{"version":1,"error":"prompt_budget_exceeded"}`（providerで超過）。モデル呼び出し・会話履歴更新は行わない。
非同期 `/v1/goal` の判断中の超過はgoalの `status: failed` と同名の `error` で通知し、actionを発行しない。
設定と推定方式は [Agent README](../agent/README.md#コンテキスト予算) を参照。

MOD 0.0.6の実行用APIは [行動ライフサイクル](action-lifecycle.md) を参照。
既存のturn/decisionの意味は変更しない。以下はPhases 1～5のAPIと実装時点の記録。

HTTP + UTF-8 JSON。`POST /v1/turn`。全リクエスト・JSON応答に整数の `version: 1` を含める。

- [リクエスト例](examples/ping-request.json): `player_chat`、空でない `player`（64文字以内）、`text: "!agent ping"` を受け付ける。
- [レスポンス例](examples/ping-response.json): `say: "pong"`、`actions: []` を返す。
- この段階では世界状態を必要としないため送らない。任意の `state` を受け取る場合はobjectに限り、保存・利用しない。
- 不正な入力は400、未定義の経路は404、Content-Length不足は411、8192バイトを超える本文は413、JSON以外は415を返す。エラー形式は `{"version":1,"error":"invalid_request"}` など。
- Forge側は応答を8192バイト、`say` を512文字以内に制限する。未知のversion、不正な型、空でないactionsは応答全体を拒否する。このturn応答自体からPrimitiveを実行しない。実行管理はgoal APIを使う。
- 接続タイムアウト2秒、読み取りタイムアウト3秒。自動再試行なし。ゲームのtickを通信待ちでブロックしない。同時リクエストは1件に制限する。
- 切断済みプレイヤーや以前のワールドセッションに対する応答は表示しない。

将来のaction実装時には、Daemon側とForge側の両方にallowlistとパラメーター検証を追加する。この段階で未実装のactionを許可しない。

## Phase 2の手動操作との関係

`spawn / follow / stop / look / say / status` はForge内のデバッグ用コマンド。HTTPのaction schemaを拡張するものではない。
ネットワーク経由の `actions` は引き続き空配列だけを許可する。手動コマンドは入力をallowlistで検証し、所有者のCompanionにだけ適用する。
手動状態確認は `!agent status`。現行では別経路のsnapshot/eventsとaction-resultも実装済み。

## Phase 3の会話

同じ `POST /v1/turn` に `event.text: "!agent chat メッセージ"` と `session`（1～128文字）を送る。
`session` はMinecraftのワールド入場単位に生成したランダムID。Daemonはsessionとplayerの組で履歴を分離し、その識別子はLLMへ送らない。
`!agent forget` は同じ組の履歴を消去する。会話本文は空白除去後1～512文字。

応答は既存の `version / say / actions` 形式を維持し、actionsは常に空。pingは従来どおりLLMなしで応答する。
会話のMOD側読み取りタイムアウトは50秒、Daemonのモデル待ち時間は設定で1～45秒（既定30秒）。プロバイダー未設定・通信失敗・不正出力・処理中は503を返す。自動再試行なし。

## Phase 4 — `POST /v1/decision`

[入力例](examples/decision-request.json)と[出力例](examples/decision-response.json)。既存のturn形式は変更しない。

必須入力:

- `version`: 整数1。
- `goal.type`: `follow_owner / stop / look_at_owner / pickup_item / deposit_items` のいずれか。自由文は受け渡さない。
- `state.companion.health`: 有限の0～20。
- `state.companion.position` と `state.owner.position`: 有限の3要素座標（XZは±30000000、Yは±2048以内）。
- `state.companion.carrying`: 省略可（既定0）。Companionの所持点数（0～100000の整数）。アイテム名はモデルへ渡さない。
- `state.items`: 省略可。`count`（0～16の整数）と `nearestDistance`（0～16、count=0ならnull）だけ。アイテム名・IDはモデルへ渡さない。
- `availableActions`: `follow / stop / look / pickup / deposit` の空でない部分集合。未知値・重複を拒否する。

入力から上記フィールドだけを新しいJSONに再構成する。余分なpersona・会話・名前等は捨て、実名はモデルへ渡さない。
出力は `version / decision / reasonCode / executed`。
`decision` は `{"action":"stop"}` / `{"action":"pickup"}` / `{"action":"deposit"}` または `{"action":"follow","target":"owner"}` / `{"action":"look","target":"owner"}`。
`reasonCode` は `goal_follow / goal_stop / goal_look / goal_pickup / goal_deposit / owner_near / low_health / owner_out_of_range / no_item_in_range / inventory_empty / unavailable_action` のみ。

体力6以下はstopのみ、followは所有者まで32ブロック以内。0.0.7からは2ブロック以内でもfollow/owner_nearで追従状態を維持し、移動だけを保留する。目的と一致しないfollow/look、未許可の操作、余分なパラメーターは拒否する。
pickupはtargetを持たない。どのアイテムを拾うかはモデルではなくForgeが決め、観測範囲内の最も近い落下物だけを対象とする。
`count` が0のpickupは拒否し、`no_item_in_range` のstopだけを認める。pickupもfollowと同じ32ブロックの制限を受ける。
depositもtargetを持たず、所持品すべてを所有者へ渡す。品物は選べない。
`carrying` が0のdepositは拒否し、`inventory_empty` のstopだけを認める。32ブロックの制限はpickupと同じ。
入力不正は400、判断provider未設定・busy・タイムアウト・不正出力は503でdecisionを返さない。

`executed: false` は判断の提案だけであることを示す。Phase 5では、明示的なstateの代わりに `session` を指定すればキャッシュの状態を使える。stateとsessionの同時指定は400、古い状態・Companion不在・不明なセッションは409。自動操作は行わない。

## Phase 5 — 観測同期

- `POST /v1/snapshot`: [初回同期例](examples/snapshot-request.json)。`version / session / sequence / state`。
- `POST /v1/events`: [イベント例](examples/events-request.json)。`version / session / sequence / events`。
- ACK: `{"version":1,"sequence":0,"synced":true}`。正しいACKまでクライアントの基準状態を進めない。
- `POST /v1/state`: `{"version":1}` で最後に更新されたセッション、`session` を付けると特定セッションを参照する。

stateはdimension・owner・companion・hostiles・items・blocksを含む。ownerはposition・health・inventory、companionはid・position・health・task・result・inventory（未読込ならnull）。hostilesとitemsは一時IDからtype・distanceへの辞書。
自由文・会話・所有者名・余分なフィールドは拒否する。inventoryはレジストリ名から個数への辞書で、NBTは含まない。hostilesとitemsは各最大16、blocksはtypeごと最大4・合計最大32、inventoryは最大128種類。
itemsはCompanionから16ブロック以内の落下物で、IDは `item-<UUID>`、distanceは2ブロック刻み。Companion未読込のときは空。

イベント:

- `position_changed_significantly`: entity（owner/companion）とposition。
- `health_changed`: entityとhealth。
- `inventory_changed`: entity（owner/companion）とadded/removed個数。差分適用前の個数と矛盾する場合は409。
- `task_changed / task_completed / task_failed`: taskとresult。
- `hostile_entered_range / hostile_updated`: idとobservation（type/distance）。
- `hostile_left_range`: id。
- `item_entered_range / item_updated / item_left_range`: 落下物の同形式。

sequenceは非負整数。snapshotでセッションを初期化し、eventsは現在値+1だけを受理する。重複・欠落・順序逆転は409を返す。再同期には新しいsessionでsnapshotを送る。
バッチは最大64イベント・32KiB。全イベントをコピーへ適用して検証後に一括反映し、不正なバッチで一部だけ更新しない。
空のeventsは連番と最終更新時刻だけを進める。15秒を超えて更新がない場合stale=true。切断・死亡・チャンク未読込は敵の見かけの消失と同様、観測の範囲に従う。
