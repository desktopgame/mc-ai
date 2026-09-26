# Protocol v1

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
- Forge側は応答を8192バイト、`say` を512文字以内に制限する。未知のversion、不正な型、空でないactionsは応答全体を拒否する。操作を実行する機能はまだない。
- 接続タイムアウト2秒、読み取りタイムアウト3秒。自動再試行なし。ゲームのtickを通信待ちでブロックしない。同時リクエストは1件に制限する。
- 切断済みプレイヤーや以前のワールドセッションに対する応答は表示しない。

将来のaction実装時には、Daemon側とForge側の両方にallowlistとパラメーター検証を追加する。この段階で未実装のactionを許可しない。

## Phase 2の手動操作との関係

`spawn / follow / stop / look / say / status` はForge内のデバッグ用コマンド。HTTPのaction schemaを拡張するものではない。
ネットワーク経由の `actions` は引き続き空配列だけを許可する。手動コマンドは入力をallowlistで検証し、所有者のCompanionにだけ適用する。
状態取得は現在 `!agent status` で確認し、Daemonへの状態同期やaction result通知は後続フェーズで追加する。

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
- `goal.type`: `follow_owner / stop / look_at_owner` のいずれか。自由文は受け渡さない。
- `state.companion.health`: 有限の0～20。
- `state.companion.position` と `state.owner.position`: 有限の3要素座標（XZは±30000000、Yは±2048以内）。
- `availableActions`: `follow / stop / look` の空でない部分集合。未知値・重複を拒否する。

入力から上記フィールドだけを新しいJSONに再構成する。余分なpersona・会話・名前等は捨て、実名はモデルへ渡さない。
出力は `version / decision / reasonCode / executed`。
`decision` は `{"action":"stop"}` または `{"action":"follow","target":"owner"}` / `{"action":"look","target":"owner"}`。
`reasonCode` は `goal_follow / goal_stop / goal_look / owner_near / low_health / owner_out_of_range / unavailable_action` のみ。

体力6以下はstopのみ、followは所有者まで32ブロック以内。0.0.7からは2ブロック以内でもfollow/owner_nearで追従状態を維持し、移動だけを保留する。目的と一致しないfollow/look、未許可の操作、余分なパラメーターは拒否する。
入力不正は400、判断provider未設定・busy・タイムアウト・不正出力は503でdecisionを返さない。

`executed: false` は判断の提案だけであることを示す。Phase 5では、明示的なstateの代わりに `session` を指定すればキャッシュの状態を使える。stateとsessionの同時指定は400、古い状態・Companion不在・不明なセッションは409。自動操作は行わない。

## Phase 5 — 観測同期

- `POST /v1/snapshot`: [初回同期例](examples/snapshot-request.json)。`version / session / sequence / state`。
- `POST /v1/events`: [イベント例](examples/events-request.json)。`version / session / sequence / events`。
- ACK: `{"version":1,"sequence":0,"synced":true}`。正しいACKまでクライアントの基準状態を進めない。
- `POST /v1/state`: `{"version":1}` で最後に更新されたセッション、`session` を付けると特定セッションを参照する。

stateはdimension・owner・companion・hostilesのみ。ownerはposition・health・inventory、companionはid・position・health・task・result（未読込ならnull）。hostilesは一時IDからtype・distanceへの辞書。
自由文・会話・所有者名・余分なフィールドは拒否する。inventoryはレジストリ名から個数への辞書で、NBTは含まない。hostilesは最大16、inventoryは最大128種類。

イベント:

- `position_changed_significantly`: entity（owner/companion）とposition。
- `health_changed`: entityとhealth。
- `inventory_changed`: 所有者のadded/removed個数。差分適用前の個数と矛盾する場合は409。
- `task_changed / task_completed / task_failed`: taskとresult。
- `hostile_entered_range / hostile_updated`: idとobservation（type/distance）。
- `hostile_left_range`: id。

sequenceは非負整数。snapshotでセッションを初期化し、eventsは現在値+1だけを受理する。重複・欠落・順序逆転は409を返す。再同期には新しいsessionでsnapshotを送る。
バッチは最大64イベント・32KiB。全イベントをコピーへ適用して検証後に一括反映し、不正なバッチで一部だけ更新しない。
空のeventsは連番と最終更新時刻だけを進める。15秒を超えて更新がない場合stale=true。切断・死亡・チャンク未読込は敵の見かけの消失と同様、観測の範囲に従う。
