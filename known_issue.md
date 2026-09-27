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

状態: 局所改善を実装（2回目）／実機確認待ち。優先度P2。
2026-09-27、collect_block(minecraft:log)の実機確認中に観測。対象ブロックの破壊自体は成功するが、生成したdropアイテムがCompanionの目線より高い位置にあり、
本来ジャンプすれば届く距離でもCompanionがその場で停止し、拾得（`CompanionEntity.PickupTargetTask`）が進行しなくなる。
根本原因は`PickupTargetTask`/`PickupTask`が`getNavigator().tryMoveToEntityLiving(item, 1.0D)`でitem entityへ直接pathingしていたこと。
KI-12/KI-13・FR-02（実行可能な候補を優先する部分）と合わせて1つの改善として、新設の`PickupApproach`がitem周辺の標準位置リング（半径1→2、companionの現在Y基準）から
「立てる」候補（`MineObstruction.standable`で足場・頭上を判定。`MineApproach`とも共有）を探し、見つかればitem entityではなくその地面座標へ`tryMoveToXYZ`する（`CompanionEntity.moveTowardItem`）。
見つからない場合は従来の`tryMoveToEntityLiving`にフォールバックする。`PickupApproachTest`で純粋ロジックを検証済み。

1回目の修正では未解消と実機で再確認された（"ドロップアイテムを見つめて固まることがある"）。KI-13で見つかった構造的に同じ不足がここにもあった。
`PickupApproach`は候補のstandableしか見ておらず、一度「実行不能」と分かった候補セルを除外する仕組みがなかったため、`PickupTargetTask`が同じ候補を
再計算のたびに選び直し、実際にはpickup可能距離に入れない位置で足止めされ得た（KI-13の`mineFailedCells`と同根）。
対処: `PickupTargetTask`専用に`pickupFailedCells`（新しいpickup対象開始時にクリア、上限32件）と、直近の再計算から実位置がほぼ動いていないかの
`stationary`判定を追加。stationaryなら現在の水平セルを確定的に除外し、`PickupApproach.bestStandPosition`の新しい除外set引数へ渡して二度と同じセルを
返さないようにした（`MineApproach`と同型の仕組み）。診断ログ（`pickup approach ...`）も追加。汎用の`!agent pickup`（`PickupTask`、targetなし・毎tick対象が
変わり得る）は対象外のまま単純な`moveTowardItem`を使う。`PickupApproachTest#anExcludedCellIsNeverReturnedEvenIfItWouldOtherwiseWin`で除外の効果を確認済み。
Minecraft地形上の実際の到達可否はJava unit testの対象外（scenario runnerも対象外、[scenario-runner.md](protocol/scenario-runner.md)参照）のため実機確認が必要。

## KI-12 到達可能な対象でも遮蔽物を回り込めず停止する

状態: 局所改善を実装／実機確認待ち。優先度P2。
2026-09-27、collect_block(minecraft:log)の実機確認中に観測。ブロックなど明らかに回り込んで到達できる遮蔽物があっても、Companionが手前で停止し目的の場所まで移動しない。
根本原因は`MineTargetTask`が常に生の対象座標（`mineX,mineY,mineZ`）へ`tryMoveToXYZ`していたこと。到達判定`MineObstruction.accessible`自体は直線LoSのMVP実装のまま変更していない。
KI-13・FR-02（実行可能な候補を優先する部分）と合わせて1つの改善として、新設の`MineApproach`が対象周辺の標準位置リング（半径1→2→3→4、companionの現在Y基準、8方位）から
「立てる」かつ`MineObstruction.accessible`でLoSが通る候補を探し、見つかった最小半径の中でcompanionに最も近いものへ`tryMoveToXYZ`する。
見つからない場合は従来どおり生の対象座標へフォールバックする。`MineApproachTest`で純粋ロジックを検証済み。
実機再確認でKI-13側に追加の不具合（下記）が見つかり、それに対する2回目の修正でKI-12の制御フローも合わせて直した。
Minecraft地形上の実際の到達可否はJava unit testの対象外（scenario runnerも対象外）のため実機確認が必要。

## KI-13 見えるが、壊せるとは限らない

状態: 局所改善を実装（4回目）／実機確認待ち。
ブロックを3つぐらい縦に積み、その上に原木を置いてから破壊を指示する。すると近くまで接近するが、破壊が行われない。
「mine_target が採掘可能な stand position を探索しない」という、KI-12と同根の問題として確定した。

1回目の修正（`MineApproach`のLoSのみの候補探索を追加）では未解消と実機で再確認された。回り込む動きはするが途中の状況で停止し採掘しない、との報告を受け、
2回目の修正で(a)立てない候補の除外（`MineObstruction.standable`）と(b)採掘可能圏内に入った瞬間の即`blocked`終端をやめ毎秒候補を再計算する方式に変更した。

2回目の修正も実機で未解消と確認された。回り込んだ末に停止し、利用者が手動でCompanionを押して位置をわずかにずらすと採掘できた、という報告から、
`MineApproach`の事前判定が対象セルの理想化した中心座標でLoSを判定しているのに対し、実際の衝突・経路探索がその中心と一致しない位置に着地させることがあり、
遮蔽物の縁に近い候補ではその差だけでLoSの可否が反転し得る、という3つ目の不足が判明した。3回目の修正で`mineFailedCells`（採掘可能圏内で立っているセルを
確定的に除外リストへ登録し、`MineApproach`が二度と同じセルを返さないようにする仕組み）を追加した。

3回目の修正も実機で未解消と確認された。利用者からログを直接確認してほしいと依頼があり、診断用ログ（`mine approach ...`、毎秒の位置・候補・除外セル数）を
追加した状態で再現してもらい、`fml-client-latest.log`をこちらで直接読んで解析した。ログから、Companionが30秒近く同一座標
（`pos=(39.59,64.00,-418.26)`）に留まり続け、`MineApproach`が同じ候補`(39,64,-419)`（対象`(37,67,-421)`に対する対角線方向・半径2）を返し続けている一方、
`withinReach`が終始`false`のままであることが分かった。この候補の理想中心での到達距離自乗は`20.25`（ハード上限ぴったり）だが、
実際の着地位置での到達距離自乗は`21.64`で上限を超えていた。`MineTargetTask`のセル除外登録は`withinReach`が`true`になった時だけ動く設計だったため、
`withinReach`が一度も`true`にならないこの経路では除外機構自体が発動せず、`MineApproach`が同じ「机上では届くはずだが実際には届かない」候補を無限に返し続けていた。

4つ目の不足として、`MineApproach`が候補選定時に**到達距離（mining reach）を一切見ていなかった**ことが判明した（standable・LoSは見ていたが、届くかどうかは
`MineTargetTask`側の別チェック任せだった）。対処:
- `MineObstruction.MAX_REACH_SQUARED`（20.25、`MineTargetTask`の到達判定と共有）を追加し、`MineApproach`の候補フィルタに组み込んだ。実際の着地位置のブレを
  吸収するため、ハード上限より少し狭い`REACH_MARGIN_SQUARED`（18.0、実距離で約0.26ブロックの余裕）で候補をふるいにかける。
  これにより上記の対角線候補（20.25）は除外され、同じ半径の軸沿い候補（16.25）が選ばれるようになった。
- `mineFailedCells`への登録条件を`withinReach`だけでなく、**直近の再計算から実位置がほぼ動いていない（`stationary`）場合も含める**よう拡張した
  （`withinReach`が真になったことがなくても、机上の候補どおりに動いたのに前進していない＝行き詰まりと判定できるようにするため）。
  `blocked`終端の条件も同様に`withinReach || stationary`に拡張した。

`MineApproachTest`に、実際に観測された座標そのものを使った回帰テスト（`aBoundaryLineDiagonalCandidateIsSkippedForASaferAxisAlignedOne`）と、
到達距離だけで候補が棄却されるケース（`candidatesFarOutOfReachDespiteClearSightAreNeverReturned`）を追加。
診断ログはCompanionEntity側に残しており、次に何か起きた場合もこちらでログを直接読んで解析できる。実機での再確認が必要。

## KI-14 川を渡れない

状態：実機再現あり／未実装
「ついてきて」のあとプレイヤーが川の向こう岸へ行くと、ついてこれずに止まる。

## 今回の区切りで未対応の機能

採掘時の道具耐久値消費・詳細metadata対応、attack / place / craft / smelt、JEV・Planner、collect_block(minecraft:log)以外の自然文からの引数付きSkill選択（collect_dropや他ブロックのcollect_block、mine等）、長期記憶、全世界探索、マルチプレイヤー、cloud routing。これらは既存機能の不具合とは区別する。

## 修正反映済みのレビュー

[review-f33f7f5](reviews/review-f33f7f5.md) の5件は `7459b4d` / `978b046` でコード変更あり。present前ACK、executor拒否保持、生成中期限、forget中結果、cleanup独立化。残る保証範囲・実機未検証はKI-02～05を参照。
