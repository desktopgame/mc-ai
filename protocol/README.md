# Protocol v1 — Phase 1

HTTP + UTF-8 JSON。`POST /v1/turn`。全リクエスト・JSON応答に整数の `version: 1` を含める。

- [リクエスト例](examples/ping-request.json): `player_chat`、空でない `player`（64文字以内）、`text: "!agent ping"` を受け付ける。
- [レスポンス例](examples/ping-response.json): `say: "pong"`、`actions: []` を返す。
- この段階では世界状態を必要としないため送らない。任意の `state` を受け取る場合はobjectに限り、保存・利用しない。
- 不正な入力は400、未定義の経路は404、Content-Length不足は411、8192バイトを超える本文は413、JSON以外は415を返す。エラー形式は `{"version":1,"error":"invalid_request"}` など。
- Forge側は応答を8192バイト、`say` を512文字以内に制限する。未知のversion、不正な型、空でないactionsは応答全体を拒否する。操作を実行する機能はまだない。
- 接続タイムアウト2秒、読み取りタイムアウト3秒。自動再試行なし。ゲームのtickを通信待ちでブロックしない。同時リクエストは1件に制限する。
- 切断済みプレイヤーや以前のワールドセッションに対する応答は表示しない。

将来のaction実装時には、Daemon側とForge側の両方にallowlistとパラメーター検証を追加する。この段階で未実装のactionを許可しない。
