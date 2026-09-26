# Action lifecycle — MOD 0.0.6 / protocol 1

## 0.0.8の通信実装

HTTP APIとJSONは変更なし。ForgeはSocial・Observation・Control・Resultsの4系統の単一スレッドExecutorをプロセス単位で共有する。
各系統は最大1worker＋Executor内キュー1件、inFlight制限と既存のアプリケーション側キュー上限も維持する。
同一系統の完了通知とworkerの復帰が重なる場合のためにキュー1件を許すが、無制限にタスクを蓄積しない。
満杯・終了済みExecutorは即時拒否し、Socialは利用者へ失敗を通知、Observationは再同期、Controlは停止、Resultsは既存の有界再試行へ接続する。
CallerRunsPolicyは使わず、ゲームスレッドで通信しない。即時停止はExecutorを経由しない。
idle workerは30秒で終了し、次回の必要時に再生成する。JVM shutdown hookが全系統をshutdownNowする。
ワールド退出時に新たなプールを作り直さず、旧HTTPの終了待ちを同じ系統に限定する。旧結果は従来のsession/世代チェックで無効化する。

## 会話からの指示 — 0.0.7

`POST /v1/turn` の会話リクエストへ `acceptIntent: true` を指定すると、応答に `intent` を追加する。

```json
{"version":1,"session":"conversation-session","acceptIntent":true,"event":{"type":"player_chat","player":"owner","text":"!agent chat ついてきて"}}
```

```json
{"version":1,"say":"追従の依頼を受け付けたよ。","intent":"follow_owner","actions":[]}
```

intentは `none / follow_owner / stop / look_at_owner` の固定文字列のみ。自由形式の操作・対象指定・コマンドは許可しない。
従来クライアントのリクエストではintentを返さず、会話だけを行う。新クライアントは欠けた・不正なintentを拒否して何も実行しない。
SocialはJSON schemaでreply/intentを生成し、Daemonで再検証する。世界状態や識別子はSocialへ渡さない。
履歴は会話だけを保持し、intentの根拠は最後の発言だけとする。過去の指示を再実行しないようモデルへ指示する。
代表的な否定・引用・条件表現等は、モデルがintentを出してもDaemonがnoneと固定説明へ置き換える。
「止まらないで」「停止しないで」「そのまま続けて」（各「ください」付きも可）は全文一致で継続の了承を返し、モデルを呼ばずintentをnoneにする。会話FIFOの順序は維持する。
任意の自然文に対する完全な意味判定ではない。未対応の依頼や複数の操作はnoneにする方針。

Forgeは会話受付時の順序ticketを保持する。雑談は行動の順序境界を進めず、受理した行動intentと手動操作だけが進める。
既に新しい指示を受理していれば、古いticketのintentとその返答を破棄する。
新しい会話セッション、観測セッション、forget、失敗・切断時にも旧intentを実行しない。
受理後は既存のgoalRevision/actionIdの検証に従う。Socialのreplyを実行結果として扱わない。

`!agent chat` の本文全体が短い停止表現に一致する場合は、ローカルで停止して世代を更新する。
この処理はSocialのFIFOを迂回し、モデルを呼ばず、待機列が満杯でも機能する。部分一致や引用・否定は対象外。
対象表現は `ImmediateStop.java` に固定し、通常の会話・解釈・人格をForgeへ移さない。
停止以外の自然文はSocialのFIFOで分類し、最新の受理したintentを優先する。

0.0.7の追従修正: 近距離のfollow_ownerはstopへ変換せず、follow/owner_nearで追従状態を維持する。
Forgeは2ブロック以内で足を止め、離れれば同じactionで移動を再開する。経路失敗は約1秒ごとの試行で3回連続まで待ち、途中の状態は観測result `path_retrying`、上限で `path_not_found` とする。

以下は0.0.6から維持する実行API。

既存の `/v1/turn` は会話専用（actionsは空）、`/v1/decision` は実行しない判断検証用として維持する。
実行用に以下の2経路を追加した。両方8KiBまで、HTTP/JSONと既存のエラー形式を使用する。

## 目的の受理と取得: `POST /v1/goal`

```json
{"version":1,"session":"observation-session","goalRevision":1,"goal":"follow_owner"}
```

`session` はACK済み観測セッション。`goalRevision` は0～2147483647の整数。
`goal` は `follow_owner / look_at_owner / stop / null`。nullは取消であり、観測が古くても受け付ける。
MODの `!agent do stop` と `!agent stop` はその場で停止・世代更新し、nullを通知する。停止のためにLLMを呼ばない。

新しい世代で目的を置換する。同じ世代・同じ目的は結果取得であり、再推論しない。同じ世代の別目的や古い世代は409。
初回受理には新鮮なキャッシュとCompanionが必要。判断入力は既存の匿名化経路を使い、識別子や会話をモデルへ渡さない。

```json
{
  "version":1,"session":"observation-session","goalRevision":1,
  "status":"ready","error":null,
  "action":{
    "actionId":"unique-action-id","companionId":"observed-companion-id","dimension":0,
    "decision":{"action":"follow","target":"owner"},"reasonCode":"goal_follow"
  }
}
```

statusは `idle / thinking / ready / running / succeeded / failed / cancelled`。actionはready時だけ返し、それ以外はnull。
errorは通常null、失敗時は固定コード。readyは「実行済み」ではない。
actionのdecision/reasonCodeは既存のDecisionProviderスキーマ。stopはtargetを持たず、follow/lookはownerだけを対象とする。

推論は単一worker、未開始の依頼はセッションごとに最新1件、最大32セッション。
推論中は管理ロックを解放するため、取消・置換・結果受付は推論を待たない。
物理的に推論を中断せず、終了時に世代を再照合する。旧推論が終わるまで次の推論は待つ。
新しい状態でも結果を検証し、個体・ディメンション変更や危険なfollowを拒否する。
readyは10秒で失効し、取得時にも最新キャッシュで検証する。

## 実行結果: `POST /v1/action-result`

```json
{"version":1,"session":"observation-session","goalRevision":1,"actionId":"unique-action-id","status":"running","reason":"accepted"}
```

応答: `{"version":1,"accepted":true}`。
statusは `running / succeeded / failed / cancelled`。
reasonは `accepted / completed / replaced / stopped / unsafe_state / path_not_found / owner_unavailable / companion_unavailable / disconnected / expired`。
不正形式は400、未知のactionは409。対応はsession・世代・actionIdの組で検証する。
終端状態への遷移後にrunning等が遅着しても戻さない。置換済みactionの結果も新しい目的へ適用しない。
直近100件の対応を保持し、その範囲で重複・遅着を受け付ける。メモリのみで再起動時は失われる。

followは開始をrunningとして通知し、所有者に近づいて足を止めてもrunningを維持する。
lookは開始をrunning、約3秒の注視終了をsucceededとする。stopは実行してsucceeded。
置換・手動操作・切断で取消し、経路失敗や体力低下等では停止してfailedを通知する。

## Forgeの実行権限と通信

- ゲームスレッドだけが世代更新・actionの適用を行う。HTTP workerはJSONを受け渡すだけ。
- 新指示を受理した時点で旧行動を停止し、未完了の判断を無効化する。未対応入力は変更しない。
- 応答のsession/世代に加えて、所有者の生存、Companion個体・生存・同一ワールド、体力・距離・目的を再確認する。
- 1世代で実行できるactionは1つ。完了後もその世代では再実行しない。
- 受信後5秒以上経過した未実行応答は拒否する。判断待ち全体は60秒まで。
- 制御を約1秒間隔で取得する。観測・会話・実行結果は別workerなので、停止や会話を行動完了待ちにしない。
- 各workerは同時1件。結果の待ち行列は64件まで。60件以上で新AI指示を拒否するが、停止は可能。
- 結果通知は最大3回送信する。失敗をログとチャットへ通知し、ゲーム操作自体は再試行しない。
- 制御通信の失敗時はAI操作を停止して取消。再接続で自動再開しない。手動で始めた行動は維持する。
- 観測の再同期・Companion個体変更・ディメンション変更・プレイヤー再生成ではセッションを変更し、旧権限を失効させる。

## 会話

ゲーム側で推論中1件＋待機最大4件・待機総計2048文字のFIFO。turnIdと会話世代を持つ。
満杯や長すぎる入力は受付せず通知する。受理した会話には順番に返答する。
会話の追加・消去はgoalRevisionを進めない。停止も会話の列を待たない。
forgetは列を消去して会話セッションを即座に変更し、旧返信を表示しない。進行中のHTTPは終了を待ち、旧履歴の削除を試みてから次の会話を送る。
旧履歴削除が通信失敗した場合も、新会話は異なるセッションで分離される。Daemon側の履歴は最大32件に制限される。

自然文からのintent抽出、予約実行、進捗を使った会話生成、永続的な結果配送はまだ実装しない。
現時点では `!agent status` でゲーム内の現在taskを確認する。
