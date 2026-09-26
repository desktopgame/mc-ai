# 全体レビュー — 7373e37

対象: 最後に確認した `d032115` 以降の変更を中心に、現在のDaemon・Forge・protocol・テスト・運用スクリプトを確認。
実装コードの修正、ゲームやDaemonの再起動、jar配置は行っていない。このレビュー文書のみ追加。

## 結論

collect_dropとmineの通常動作、型付き通信、取得/破壊の進捗分離は実装されている。
一方、取消と結果の順序逆転、Forgeの実変更前チェック、v1/v2の権限統合に修正が必要。
次のcollect_blockやPlannerへ進む前に、下記1～4を優先して直すことを推奨する。
P1は通常の操作や遅延でも到達するライフサイクル/安全性の問題、P2は条件付きの不整合・表示・運用上の問題。

## 1. [P1] 取消後の成功receiptで旧Skillが終端しなくなる

位置: `agent/src/skills.py:494`～504、`tick():249`～255。

取消済みSkillはactiveからotherへ移されphase=cancellingになる。しかし成功receiptの分岐には取消判定がなく、数量未達ならselectingに戻す。
other内ではcancellingだけをtickするため、そのSkillは総期限を過ぎても終端しない。数量に達した場合もcancelledではなくcompletedになり、仕様と異なる。

**実行して再現済み:** count5のactionを発行→revision2でgoal:null→旧actionのsucceeded/count2→時計を200秒へ進めtick。
結果は `status=running, phase=selecting, progress={requested:5, acquired:2, complete:true}` のまま。

修正: receiptの数量を一度だけ精算した後、取消待ちであった場合は新actionを出さずcancelledへ閉じる。完了と取消の先着規則を一箇所にまとめる。
回帰テスト: 取消→部分成功、取消→残数達成、成功→取消、置換→旧成功、200秒後の終端、otherの有界性。

## 2. [P1] 安全条件・action期限が世界変更の後で評価される

位置: `forge-mod/src/main/java/local/mcai/CompanionEntity.java:412`～423、同443～488、`ActionBridge.java:301`～328・616～624。

PickupTargetTask/MineTargetTaskが収納・破壊する直前に見るのは主にcontrol leaseと対象条件。
HP<=6、所有者との距離、claimedDeadlineはActionBridgeのServerTick ENDで確認される。
claim時にもHP/所有者との距離の確認がないため、低体力で近くの落下物へSkillを開始すると、次のentity更新で拾い、その後ENDでunsafe_state/count0になる経路がある。
実行中に安全条件が変わった場合も同様で、世界は変わったのにDaemonには取得/破壊ゼロを報告し得る。

修正: action実行権・個体/owner・HP/距離・action期限・leaseを世界変更直前に共通ガードで確認する。
すでに確定した正のoutcomeは、その後の安全停止や取消によってcount0へ上書きしない。
回帰テスト: HP6で近距離pickup、採掘完了tickのHP低下/owner離脱、期限直後の到着、pause後の再開、確定outcomeと停止の順序。
コードのtick順から確認した指摘であり、このレビューでは実ゲームで危険条件を発生させていない。

## 3. [P1] 旧SkillのHTTP応答が新Skillの失敗として処理される

位置: `ActionBridge.java:195`～203、283～296、363～377、426～427。

startSkillはrevisionとSkill状態を置換するが、旧skillInFlight/skillDone/skillResponseを新旧区別できる形にしていない。
応答を単一の共有フィールドへ格納し、consumeSkillでは現在のrevisionで検証する。
旧poll中に新しいcollect_drop/mineを入力すると、旧応答が新指示の処理に取り込まれ、revision不一致→failSkillで新指示まで取り消される。
旧リクエストの通信失敗(null)も同じ扱い。v1にはsession/revision付きCompletionがあるが、v2には同じfenceがない。

修正: v2もrequest発行時のsession/revision/epoch/ローカルgenerationと開始時刻をCompletionへ保持。
旧generationの応答・失敗は現在の指示に作用させず破棄する。処理中フラグも該当requestの完了だけで解除する。
開始時刻から古すぎる応答で5秒leaseを延長しない。
回帰テスト: poll遅延中のSkill置換、旧null応答、open中の置換、退出・再入場、同一sessionでの連続操作。
これはコード経路の確認。現在のJavaテストはActionBridgeの非同期入替えを直接検証していない。

## 4. [P2] v2 legacy goalとSkillが別のrevision/取消管理になっている

位置: `agent/src/skills.py:191`前後・268`_legacy`、`agent/src/daemon.py:146`～152、`execution_registry.py`。

v2の文字列goalはSkill側のrevision確認・旧Skill取消を通らずGoalManagerへ渡る。
goal:nullはSkillだけを取り消す。v1ルートもExecutionRegistryの所有権を照合していない。
ExecutionRegistry自体はあるが、GoalManagerには接続されておらず、実際の発行権が統一されていない。

**実行して再現済み:** 同sessionでv2 collect_drop revision1→v2 follow_owner revision2。
`skill_active=True` のままlegacyはready。その後v2 goal:null revision3を送ってもlegacyはreadyのまま。

現MODは通常操作をv1へ戻すため、これだけでゲーム内で2操作が同時実行されると断定はしない。
ただし公開しているv2 unionの置換・取消契約は成立していない。今後のSkill接続で事故になりやすい。
修正: 共通arbiterでrevision・所有権・取消を管理するか、未対応のv2 legacy経路を明示的に拒否して契約を絞る。
回帰テスト: Skill↔legacy、v2 cancel、v1/v2混在、同Companionの旧/新session。

## 5. [P2] cancel handshakeの失敗を成功扱いする

位置: `ActionBridge.java:347`～353。

skillCancelSent後はskillDoneだけを見てskillResponseを捨て、Skillモードを終了する。
null（タイムアウト・非200・通信失敗）でも、epoch/session/revision不一致でも同じ扱いなので、Daemonが取消を受理していなくても通常actionへ移る。
「cancel handshakeを完了してから通常actionへ移る」という現在の引き継ぎ記述と一致しない。

修正: 対応する取消requestへの正しいidle ACKを検証する。失敗時は取消を有界再試行するか、明示的な接続失敗として実行を止める。
ローカルの即時停止は通信を待たせない。旧取消応答で新しい指示の状態を消さないことも項目3と共通化する。
回帰テスト: cancelの500/409/timeout/null、旧pollの後着、取消再送、cancel ACK前の通常操作。

## 6. [P2] 数量未確定・取消時の部分成果がユーザーへ伝わらない

位置: `ActionBridge.java:382`、452～459、468～491。

SkillProtocolはprogress.completeを読むが、finishSkillへ渡すのはachievedだけ。
未確定の0/5と確定した0/5が同じ表示になる。cancelledはdebugReplyだけで、標準設定では部分成果どころか最終通知も表示されない。
ローカル通信失敗もdebugReplyだけの経路があり、ユーザーには突然idleになったように見える。

修正: terminal結果とprogress.completeを表示へ渡し、未確定を「確認済みN個・未確定あり」と明示。
取消でも部分成果を通常通知として一度表示する。自発的な機械的進捗通知と、依頼の最終結果を分離する。
回帰テスト: complete=falseの失敗、2/5の取消、verbose=falseの通信失敗、再pollによる通知重複なし。

## 7. [P2] 再起動スクリプトが別checkout/別portのDaemonも停止する

位置: `scripts/restart-daemon.ps1:17`～19・45～48。

Find-Daemonsは相対的な `agent/src/daemon.py` の部分一致だけでプロセスを列挙し、再起動時に全件をStop-Processする。
選択したPortのHTTP shutdownが成功した後もこのループを実行するため、別portで検証中のDaemonや別checkoutの同名スクリプトも巻き込む。

修正: このrepoの絶対スクリプトパスと指定port、または検証済みlistener PIDに限定して停止する。
パスを判別できない相対起動プロセスを一律停止しない。終了後の件数確認も同じ範囲にする。
回帰テスト: 2port/2checkoutで一方だけ再起動。今回はプロセス停止の実験は行っていない。

## 検証とレビュー範囲の限界

- Python: `python -m unittest discover -s agent/tests -q`、83件成功。
- Java: `.\scripts\forge.ps1 test --rerun-tasks`、48件成功（XMLでもfailures/errors=0）。
- 項目1・4は既存fixtureを使った独立したin-memory再現。稼働中Daemonやモデルを呼んでいない。
- Java側の指摘はコード上の呼出順・状態管理の確認。実ゲームの再現試験は未実施。
- 世界状態projectionと会話の分離、予算管理、通常のreceipt重複排除、mine/取得progress分離は維持されている。
- MineObstruction/型検証の単体テストはあるが、ActionBridgeとEntity更新を跨ぐtick順・通信順の試験が不足している。
- 全アイテム/ブロック/他MOD互換性の網羅や、長時間プレイの性能計測は対象外。

## 資料について

HANDOFF冒頭はPython81/Java43・mine未検証、後半は83/48・実機確認済みとなっている。
HEAD欄も403e605/未コミットの記録が残り、今回確認したHEAD7373e37とは異なる。
現在の状態と過去の作業時点の記録を分け直した方がよい。今回プロセスの稼働状態は再確認していないため、起動状態の更新はしていない。

修正順の推奨: Forgeのmutation前ガードとoutcome保全 → Daemonの取消精算 → v2通信のgeneration管理 → 発行権の統合 → 表示/運用スクリプト。
