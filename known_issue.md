# 既知の問題・制限 — v0.1.0

2026-09-26。実装基点 `ed1ca65`。v0.1.0は以下を残して区切る。
不具合、設計上の上限、実機検証不足を分ける。過去レビューの指摘をそのまま未修正とは扱わない。

## KI-01 Forge終端通知のidentity管理が統一されていない（解消済み）

状態: 解消済み。優先度P2だった。
Daemonのidentityは `(daemonEpoch, session, skillInstanceId, terminalId)`。
ActionBridgeは完全identityを使用していたが、PingBridgeの `seenTerminals` / `terminalRequests` / 配送台帳はterminalId単体を使用しており、同じterminalIdを別bindingで再利用すると通知を拒否する可能性があった。
`PingBridge.TerminalRequest` に `identity`（`daemonEpoch|execSession|skillInstanceId|terminalId`、`TerminalDeliveryState.identity` / `SkillProtocol.TerminalEvent.identity()` と同一の式・入力）を追加し、`seenTerminals` / `terminalRequests` / `deliveries.accept` / `queue.offerTerminal` を全てこの複合キーに統一した。Daemonへのワイヤープロトコル呼び出し（`deliverTerminal` / `presentTerminal`）は元々terminalId単体を送る仕様のため変更していない。
`TerminalSocialTest#terminalRequestIdentityMatchesActionBridgeBinding` で、同一terminalIdでもdaemonEpoch/execSession/skillInstanceIdが異なれば別identityになることを確認済み（`.\scripts\forge.ps1 test --offline` でBUILD SUCCESSFUL）。
reset時に配送台帳のclosed状態が残る挙動自体はActionBridge側と同じ設計として維持（同一identityの再表示を永続的に防ぐ意図）であり、今回の不統一の原因ではないため変更していない。

## KI-02 ACKと会話履歴の配送保証は有界・best effort

状態: 既知の制限／コード確認。優先度P2。
`PingBridge.pendingAcks` は最大32件。上限を超えると新しいACKをdropし警告を出す。executorに拒否されたACKは保持するが、通信自体は最大2回で終了する。
そのため画面に結果が出ても、会話履歴への登録・Daemon outboxの解放が欠落し得る。表示済みの成果を次の会話で思い出せない場合がある。Skillやworld変更を巻き戻す仕組みではない。
またDaemonは配送完了を記録してから履歴へ登録するため、履歴lockがbusyなどで登録を断念すると同じACKの再送で補完されない。
対処候補は配送状態と履歴登録状態の分離、上限超過の可視化、ACK混雑・通常chatとの順序の統合テスト。回避のため同じSkillを再実行しないこと（別の取得・採掘が発生する）。

## KI-03 Daemon再起動時の旧結果の扱いが不完全

状態: 未実装の復旧動作／仕様との差分。優先度P2。
仕様の「同じworld/ownerなら、取得済み旧snapshotを旧作業と明示してfallback表示する」専用経路は未実装。
Daemon再起動でoutbox・会話履歴は失われる。再起動をまたぐ配送やexactly-onceは保証しない。
旧epochの応答、新epochのcursor、取得済み/未取得snapshotを分けて復旧を確認する必要がある。

## KI-04 長時間運用での台帳保持・期限・通知欠落

状態: コード上の制限・未検証。優先度P2。
Daemon outboxは100件・600秒、closed記録は100件・digest記録は1024件。有限のLRUであり「永久に再生成しない」とは保証できない。通常はSkill側が一度だけeventを作成する。
`sequences / by_session / overflow` のsessionキーは空になっても自動削除されず、session作り直しを続けた場合のメモリ量を別途検証する必要がある。
Forgeはterminal-eventsのoverflowフラグをユーザー向けの欠落通知へ接続していないため、保存枠・TTLを超えた未取得結果を黙って失う可能性がある。
read timeoutは絶対的な総通信時間の上限ではない。Forgeの12秒表示fallbackと、provider/HTTP workerの終了保証は区別する。
対処候補はsession寿命の有界管理、連続sequenceと未完了gapを使うdedupe、overflow表示、長時間・遅い受信のテスト。

## KI-05 最新の配送修正には実ゲーム未検証の経路がある

状態: 検証不足（全項目が不具合と確定した意味ではない）。
基本の終端表示・displayed ACK・履歴登録には過去の実機確認がある。0.0.30～0.0.31の修正後について、次の全経路が実機確認済みとは扱わない。

- 先行chat待機＋生成中を含む12秒fallbackと、後着応答の破棄。
- 生成中forget、連続forget、owner/world退出との競合。
- presentなしfallback、ACK backlog・再送・通常chatとの順序。
- provider停止・Daemon再起動・旧snapshot・cursorの境界。

Pythonの追加テストでACKの修正を検証しているが、Java83件という件数は実際のPingBridge配送経路全体を保証しない。fake worker/clockを使った統合テストと実機確認を追加する。

## KI-06 音声系クラッシュの過去報告

状態: 過去の実機報告／現在の再現有無・原因未確定。
Prism検証環境ではOpenALFix導入前に毎回クラッシュし、導入後にタイトルとワールドへ進めたとの利用者確認がある。一方、導入後にもOpenAL関連のクラッシュ記録が残る。
Companion MODの問題、音声環境の問題、同時起動の影響のいずれかと断定しない。長時間安定性とは別に起動時ログ・音声例外を採取する。過去記録は [開発履歴](development-history.md) を参照。

## KI-07 Gameplay異常系の実機確認が不足

状態: 検証不足。
収納満杯（inventory_full / owner_inventory_full）、道具不足・leaves越しの採掘、pause/退出・長時間動作の組み合わせに未消化がある。
基本のcollect_drop・mine・collect_blockの確認を、これらの確認済みの根拠にしない。再現条件・入力・結果・ログを記録して項目ごとに閉じる。
pickup系path_not_foundと壁への回り込みは、実機再現ありの不具合としてKI-11・KI-12へ切り出した。

## KI-08 終端発話の自然さは限定的

状態: MVPの表現上の制限。
LLMはfriendly/calm/conciseの候補を選ぶだけで、自由文を生成しない。現行候補は導入文以外の大部分が共通のため、「原木回収だよ。完了。…」のような機械的な返答になる。
数値・reason・未確定注記を維持したまま候補本文を改善する余地がある。採掘Nブロックを木N本、取得累積を現在所持数へ言い換えない。

## KI-09 修正後に残る問題の利用者報告（詳細待ち）

状態: 利用者報告あり・症状/再現条件未特定。→誤解でした。
~~2026-09-26に「修正したがまだ問題があった」と報告。上記項目と同じかは未確認。操作、表示、期待結果、使用jar/Daemon版が分かり次第、該当項目へ統合または別issue化する。~~
~~この報告をもって全機能が動かないとは判断せず、逆に自動テスト成功だけで解決済みにもしない。~~

## KI-10 HTTPエラーテストで接続中断を観測

状態: v0.1.0準備時に1回観測／原因未確定。
Python全155件の初回実行で `test_wrong_route_and_content_type` が期待するHTTP 415を受信できず、Windowsの `ConnectionAbortedError (10053)` で失敗した。
これは今回の確認で観測した事実であり、ゲームの症状と同一原因とは判断しない。再実行結果は [リリース記録](RELEASE_NOTES.md) を参照。テスト用HTTP接続・不正Content-Typeの処理・実行環境を切り分ける必要がある。

## KI-11 破壊済みブロックのdropが目線より高い位置にあると回収できず停止する

状態: 実機再現あり／原因未確定。優先度P2。
2026-09-27、collect_block(minecraft:log)の実機確認中に観測。対象ブロックの破壊自体は成功するが、生成したdropアイテムがCompanionの目線より高い位置にあり、
本来ジャンプすれば届く距離でもCompanionがその場で停止し、拾得（`CompanionEntity.PickupTargetTask`）が進行しなくなる。
`PickupTargetTask.updateTask`は`getDistanceSqToEntity(item) <= 2.25D`への到達と`getNavigator().tryMoveToEntityLiving(item, 1.0D)`の経路探索だけに依存しており、
Y方向の到達（ジャンプ）を明示的に補助する処理はない。1.7.10標準のPathNavigateGroundの自動ジャンプで解決できない配置（段差・足場の上のitem等）だと、
`FollowRetry`（連続3回で`path_not_found`）が尽きるまで待つか、見た目上停止し続ける可能性がある。
KI-07で未検証としていたpickup系path_not_foundの一部が実機再現ありの不具合として確定した。再現条件（段差の高さ・item位置）の記録とログ採取が必要。

## KI-12 到達可能な対象でも遮蔽物を回り込めず停止する

状態: 実機再現あり／原因未確定。優先度P2。
2026-09-27、collect_block(minecraft:log)の実機確認中に観測。ブロックなど明らかに回り込んで到達できる遮蔽物があっても、Companionが手前で停止し目的の場所まで移動しない。
mineの到達判定`MineObstruction.accessible`（`MineObstruction.java`）はCompanionの現在の目線位置から対象ブロック中心への直線1本だけを判定するMVP実装で、
遮蔽されていれば移動やジャンプでの回り込みを試さずその場で`blocked`として終了する（実装コメントに「MVP reachability」と明記済み）。
また`MineTargetTask`の経路探索は`FollowRetry`により連続3回の`tryMoveToXYZ`失敗で`path_not_found`として打ち切るため、
1.7.10標準ナビゲータが遠回りルートを見つけられない・見つけるまでに3回を超える場合も同様の症状になり得る。
KI-07で「壁への回り込み」として未検証としていた項目が実機再現ありの不具合として確定した。直線LoS判定の緩和（複数候補位置からの再判定、ジャンプ込みの再接近）または経路探索の拡張が対処候補。

## KI-13 見えるが、壊せるとは限らない

状態：実機再現あり／未実装
ブロックを3つぐらい縦に積み、その上に原木を置いてから破壊を指示する。
すると近くまで接近するが、破壊が行われない。
「mine_target が採掘可能な stand position を探索しない」というより一般的な問題？

## KI-14 川を渡れない

状態：実機再現あり／未実装
「ついてきて」のあとプレイヤーが川の向こう岸へ行くと、ついてこれずに止まる。

## 今回の区切りで未対応の機能

採掘時の道具耐久値消費・詳細metadata対応、attack / place / craft / smelt、JEV・Planner、collect_block(minecraft:log)以外の自然文からの引数付きSkill選択（collect_dropや他ブロックのcollect_block、mine等）、長期記憶、全世界探索、マルチプレイヤー、cloud routing。これらは既存機能の不具合とは区別する。

## 修正反映済みのレビュー

[review-f33f7f5](reviews/review-f33f7f5.md) の5件は `7459b4d` / `978b046` でコード変更あり。present前ACK、executor拒否保持、生成中期限、forget中結果、cleanup独立化。残る保証範囲・実機未検証はKI-02～05を参照。
