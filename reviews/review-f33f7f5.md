# Skill終端Social通知 レビュー

2026-09-26、`develop` / `f33f7f5`（MOD 0.0.29）。実装指示書と `c5bdd36` 以降の変更を確認。修正は行っていない。

## 確認結果

Skill終端snapshotをauthorityとし、同じSocial providerで候補IDを選ぶ基本経路は実装されている。添付画像では部分失敗の理由・回収数・採掘ブロック数と、完了通知の表示が確認できる。ただし画像だけから重複配送や履歴登録、世界上の実際の取得数との一致までは判定できない。

Python `unittest discover -s agent/tests`: **150件成功**。
Java `scripts/forge.ps1 test --offline --rerun-tasks`: **83件成功**。
実ゲーム操作、Daemon再起動、jar配置は行っていない。

以下5件は既存テストでは検出できていない。

## 1. [P2] present前・生成中のfallback ACKを正常処理できない

場所: `agent/src/terminal_events.py:214–244`、`agent/src/daemon.py` のterminal-delivery処理。

Forgeはqueue満杯、待機12秒超過、executor拒否などで、`/v2/social/skill-terminal` を呼ばずに固定結果を表示し、displayed/fallbackをACKする。しかし `deliver` はsnapshotが存在していてもpresentation entryがなければ `unknown_terminal` にする。表示済みなのにoutboxが閉じず、履歴にも登録されない。

また生成中のentryにはsayがないため、その時点のfallback ACKはfirst=Trueでoutboxを閉じるが、say=Noneを返す。Daemon側の履歴登録条件を満たさず、その後のACK再送でも履歴を補完できない。

**実際のStoreで再現済み**:

```text
record(event) → deliver(displayed, fallback)
  → TerminalError: unknown_terminal
record(event) → present_begin(...) → deliver(displayed, fallback)
  → first=True, say=None, variantId=None, mode=None
  → outboxから削除済み、履歴への登録材料がない
```

修正方針: snapshotを根拠にpresent未実行でもbindingを確定し、fallbackをrendererで再構築してACKを適用する。生成中も同様にfallbackを確定させ、遅着providerより優先する。生成済みのsocial variantが通信障害等で届かずForgeがfallbackを表示したケースも扱う（現在はvariant不一致でack_conflict）。いずれもユーザー向け自由文をACKから受け入れる必要はない。

追加テスト: presentなしのdisplayed/suppressed、生成中fallback、ready後fallback、ACK再送、実表示内容の履歴一回登録。

## 2. [P2] ACK用executorが満杯になるとACKを無言で捨てる

場所: `forge-mod/src/main/java/local/mcai/PingBridge.java:100–113`。

`ackAsync` は `io.execute` のboolean戻り値を確認しない。SOCIAL laneは実行1件＋待機1件なので、長いchat中に2件以上のterminalが期限切れになると、最初のACKだけ待機でき、後続ACKはexecutorに拒否される。Runnable自体が実行されないため、内部の2回再試行や失敗ログも動かない。ローカルではrequestを削除済みで、後から再送する経路がない。

この問題は項目1を直しても残る。結果は表示されるが、履歴登録・outbox解放が欠落する。通常chatの投入とも直接競合するため、ACKを共有キューの外からまとめて投入しない。

修正方針: game thread側に有界なpending ACKを保持し、次のSocial生成より先に1件ずつ送る。executor拒否時はpendingを保持する。通信再試行回数と、executorにまだ受理されていない状態を区別する。

追加テスト: workerを止めた状態で複数terminalを期限切れにし、全ACKが最終的に送られること、通常chatを失わないこと。

## 3. [P2] 生成に入った通知は12秒の表示期限から外れる

場所: `forge-mod/src/main/java/local/mcai/PingBridge.java:125–138`、`211–218`。

期限確認は `queue.snapshot()` のみ。`queue.poll()` で取り出して `processTerminal` に入った通知は対象外になる。

例えばterminalが先行chatの後ろで11秒待ち、その後provider生成に8秒かかると、受信から表示まで約19秒になる。socket read timeoutは受信全体の絶対期限でもないため、通知HTTPの設定だけではこの契約を満たせない。

`TerminalDeliveryState` には期限・first-winsをテストした実装があるが、実際のActionBridgeは `accept` しか呼ばない。`fallbackExpired / finishFallback / finishSocial` は本番経路に接続されていない。

修正方針: enqueue時刻からの絶対期限をin-flightにも保持し、表示を同じ配送状態機械で確定させる。12秒でfallback表示した後の応答は再表示しない。ただし実際のHTTPが終わる前にworkerのinFlightを解除しない。

追加テスト: 11秒待機→生成開始→12秒時点fallback→19秒時点遅着応答。既存の純粋クラス単体テストだけでなく、実際のdispatcherと同じ状態遷移を検証する。

## 4. [P2] forget中に生成しているterminalの結果が消える

場所: `forge-mod/src/main/java/local/mcai/PingBridge.java:116–122`、`170–173`、`reset`。

同じ世界でterminal生成中に `!agent forget` を送ると、`displayPendingTerminals` はqueueに残るものだけをfallback表示する。生成中terminalはすでにpoll済みなので対象外。その後resetがterminalRequestsをsuppressedとして削除し、遅着回答もepoch不一致で表示されない。

仕様の「forgetは受信済み・未表示terminalを固定表示してから会話を破棄する」と異なり、作業結果が一度も見えない。world/owner変更で表示を抑制する処理と、同じworldで会話だけ忘れる処理を分ける必要がある。

修正方針: forget時はin-flightを含む未表示の配送台帳を走査してfallbackを一度表示し、その後会話を切り替える。HTTP応答は遅着として破棄する。項目3と同じ台帳に寄せると二重表示を避けやすい。

追加テスト: terminal providerを停止させ、生成中にforget。固定結果一回・古い自然文ゼロ・新しい会話へ旧履歴が入らないこと。

## 5. [P2] forget後の次entryがterminalだとDaemonへの履歴削除を失う

場所: `forge-mod/src/main/java/local/mcai/PingBridge.java:212–218`。

`cleanup = forgetSession` を取り、`forgetSession = null` にした後、次entryがSKILL_TERMINALなら `processTerminal` してreturnする。cleanupを使う処理は下のUSER_CHAT側にしかない。

forget直後、次tickまでにterminalがenqueueされた場合、Daemonへ `!agent forget` が一度も送信されない。画面はリセット済みでも旧履歴が残り、retiredの登録も行われない。新sessionへの表示漏れとは別だが、履歴破棄の契約に反する。

修正方針: cleanupも通常chat/terminalの種別から独立したpending処理にし、送信を受理するまで消費しない。ACKとforgetの順序を明示して共有workerへ流す。

追加テスト: forget保留＋次entryがterminalという状態で、旧sessionのforgetが一回送られ、新sessionの通知がその後に処理されること。

## 補足

画像の「原木回収だよ。完了。…」という表現は、現行の候補が共通の固定本文に短い導入文を付けた構成であるため。設計上の限定された言い換えの範囲には入っており、不具合とは分けて扱う。自然さを改善する場合も、まず上記の配送・履歴の欠落を直し、その後に事実を維持した候補本文の改善を行うのがよい。
