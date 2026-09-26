# 差分レビュー — 7cb43e9

対象: 7373e37以降のレビュー反映とブロック露出フィルタ（MOD 0.0.19）。
コード・仕様・追加テストを確認。ゲーム・Daemon・配置済みjarは操作せず、実装も修正していない。

## 指摘

### 1. [P1] 期限切れの初回action応答をclaimできる

位置: `forge-mod/src/main/java/local/mcai/ActionBridge.java:403`～410、447～455。

request.startedから5秒以内という判定は既存activeのlease更新だけに適用される。
activeがない最初のactionではそのままclaimし、現在時刻から新しいleaseとaction期限を与える。
例えばcontrol要求後にゲームを一時停止し、バックグラウンドHTTPが応答を保持したまま10秒以上経過すると、Daemonはready期限でSkillを終端できる。
しかし再開tickのForgeはその古いactionを新規claimする。近距離pickupなら次のpollで終端を知る前に収納する可能性がある。
世代は同じなのでgenerationチェックだけでは防げない。

修正: 応答の経過時間をconsumeSkillの入口で判定し、古い応答からはlease更新も新規claimも行わない。
Skill停止または新しいpoll結果を待つ処理へ進める。受信時刻だけでは、一時停止中に溜まった応答を防げない。
テスト: activeなしでrequest.started+5秒超のreadyを渡す、pause後の再開、Daemonのready期限後の応答、世界変更が0回であること。
根拠はコード経路。今回、実ゲームで一時停止の再現試験は行っていない。

### 2. [P2] 世代付きCompletionを単一volatile欄で渡すため、最新の完了通知を失う

位置: `ActionBridge.java:294`～298、348～358。goalDone/cancelDoneも同構造。

generationを付けたこと自体は適切だが、応答の保管先は依然として全generation共有のopenDone等である。
openCall等をclearして新世代requestを出せるため、旧requestと新requestが同じCONTROLレーンに実行中/待機中として残り得る。

成立するインターリーブ:

1. 旧open Aの応答がopenDoneへ入り、新open Bがキューから実行される。
2. ゲームスレッドが `opened = openDone` でAを読む。
3. workerがBの完了をopenDoneへ書く。
4. ゲームスレッドが `openDone = null` を実行し、Bを消す。
5. Aは旧generationとして捨てられる。openCallはBのままなので、以降openを再送せず待ち続ける。

volatileは可視性を保証するが、この読み取りと消去を一つの操作にはしない。
また、キュー満杯による新requestの即時失敗通知を、実行中の旧request完了が上書きする経路もある。

修正: requestごとに専用の完了slotを持たせ、現在のrequestだけを読むか、有界のcompletion queueを消費する。
単純に共有slotのgetAndSetへ置き換えるだけでは、旧workerによる新通知の上書きまで解消しない。
未完了requestに明示的な期限を持たせ、完了通知を失った場合も無限待ちにしない。
テスト: worker/game-threadをlatchで止めて上記の順序を固定、新Skillの成功/null通知が消えないことを確認。
今回の指摘はコード上のインターリーブ分析であり、実ゲームでの発生頻度は測っていない。

## 前回指摘の確認

- 取消後の部分成功で旧Skillがselectingへ戻る問題は `_close_if_settled` と追加テストで修正されている。
- 世界変更前のHP・owner距離・lease・action期限ガードと、tickで正のoutcomeを優先する処理が追加された。
- 旧generationの応答を新Skillの失敗にしない構造へ改善された。ただし上記2の受け渡し競合は残る。
- v2文字列goalを明示拒否する対応で、前回再現したv2 legacy/Skillの同時受付経路は閉じられた。
  v1/v2全体の共通arbiterを実装したわけではないため、将来APIを広げる際には所有権の統合を再確認する。
- cancel ACKの内容確認と最大3回の再試行が追加され、前回の無条件成功扱いは修正された。
- 数量未確定・取消時の表示と、再起動スクリプトの停止範囲は明示的な対象外として残っている。新規の退行とは数えない。

## ブロック探索チェック

追加は「探索移動」ではなく観測候補の表面露出フィルタ。
6隣接のどれかがair/非固体/leavesなら候補とし、埋没ブロックを除外する。
未読込の隣接をrockとして扱うため、未読込境界を露出と誤認しない。実行側のMineObstructionとは分離されている。
この増分について、定義された露出ルールに反する重大な不具合は見つからなかった。

仕様上の限界:

- 別の閉じた空洞に接するブロックも露出扱い。Companionから見える/到達可能という意味ではない。
- 非固体には液体も含み得る。安全な立ち位置を保証する判定ではない。
- 種類ごと4件・全体32件は維持。11種類が揃った場合、全体の距離順打切りで遠い種類が候補0件になることはある。
  今回新たに発生した問題ではなく、全種類の最低1件を保証する実装でもない。

## 検証

- Python: unittest discover、91件成功。
- Java: forge.ps1 test --rerun-tasks、61件成功、failures/errors=0。
- BlockExposure/BlockCandidatesおよびSkillの純粋ロジックのテストは追加されている。
- ActionBridge本体のrequest配送・completion消費・初回claimまでつないだ試験がまだ不足している。
- 文書の起動状態は今回再確認していない。HANDOFFの古いHEAD記載や「反映済み」と「Daemon再起動後に反映」の混在は引き続き整理余地がある。

今回追加した成果物はこのレビュー文書のみ。
