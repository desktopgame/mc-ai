# collect_block MVP — 実装指示書

状態: **Daemon層のみ実装済み（段階①②）。Forge接続は未実装。** 調査基点 `c5d491c`、MOD 0.0.20。
既存の [Skill Layer](skill-layer.md) と [mine primitive](mine-primitive.md) を利用する。
本書は次の実装担当が読む指示書。今回の文書作成ではコード変更・起動・jar配置は行わない。

### 実装状況（2026-09-26 更新）

- **実装済み（Daemon）**: §4 の `/v2/goal` collect_block解析・`COLLECT_BLOCK_TARGETS`・capability `collect_block_v1`、
  §5 の progress/result（`acquired`/`mined`/`complete`）、§6 の action descriptor 台帳（payloadFieldで精算、混合action）、
  §7 の `select_source / wait_drop / recover_drop` stage machine、§8 の `drop_unavailable`。`collect_drop`/`mine` の互換維持。
  §6 のreceipt検証は descriptor基準で共通化（sequence/payload種類/target ID/count上限、running/succeeded/failed/cancelledの
  reason・count固定、terminal Skillでも同一検証、late runningはACKのみ）。§7 の wait_drop/recover_drop は stage固有の
  絶対deadline（mine receipt＋6秒 / 最初のrecovery失敗＋6秒）で、poll・候補入れ替えで延長しない。
  回帰テストは `agent/tests/test_collect_block.py`（26件）＋既存 `test_skills.py`。Python 117件。
- **未実装（次増分 / MOD 0.0.21予定）**: Forge側（§10 の Forge行、§11 UI、capability確認、新しい入口
  `!agent do collect_block minecraft:log <n>`）、§6 のForge descriptor照合、§13 のForge/統合・実ゲームテスト。
  Daemonは capability を広告済みのため、Forgeは capability を確認してから新goalを送る必要がある（未対応Daemonへ
  mine/collect_dropとして代替送信しない）。

## 1. 目的・MVPの範囲

`collect_block(block, count)` を、Daemonがmine_targetとpickup_targetを順序づける新Skillとして追加する。
**成功条件は対象品をこのSkillのpickup actionで新たにcount個収納したこと。count個のブロック破壊ではない。**
開始前に所持していた品物は数えない。破壊数は別の副作用カウンタとして残し、取得数へ足さない。

最初は次の1組だけを対応させる。

| 指定block | 回収するitem | 数量 |
| --- | --- | --- |
| `minecraft:log` | `minecraft:log` | 1～64の整数 |

原木だけに絞る理由は、既存のmine・collect_drop双方に対応しており、まず異なるactionを連結する部分を検証するため。
stone→cobblestoneや鉱石→素材、gravelのランダムdropなどは後続。block IDとitem IDが常に同じという実装にはしない。
`minecraft:log2` も今回は対象外。樹種の指定はせず、logのmetadataを区別しない既存の集計方針を維持する。
実ItemStackのmetadata/NBT・合成条件は保持する。全NBTをDaemonへ送らない。

入口:

```text
!agent do collect_block minecraft:log 5
```

今回含めないもの: 自然文から引数付きgoalの抽出、Planner、子Skillの再帰実行、道具クラフト、耐久値消費の新実装、探索移動、足場設置、障害物の除去、永続resume。
既存のcollect_dropとmineの意味・入口・結果は変更しない。mineのcount=1制限も維持する。

### 落下物を拾う方針

近くに同種の落下物があれば、採掘前に拾う。開始前から地面にあった品物も、Skill開始後にこのSkillが収納した分は加算してよい。
「自分が壊したブロック由来だけ」とはしない。dropの所有権や由来を追跡する機構は追加しない。
成功メッセージは「原木をN個集めました」で、N個の木を切ったとは表現しない。

この方針は不要な追加破壊を避け、EntityItemのmergeや他者の同種dropにも対応するためのMVP上の選択。
採掘後にドロップ回収が確認できない場合は、別の木を次々と壊して埋め合わせない。

## 2. 不変条件

- Companionごとにactive Skill1つ、current action1つ。採掘と拾得を並行実行しない。
- goalRevisionは依頼の置換・取消時だけ進める。採掘→拾得でrevisionを増やさない。
- 同じSkill instanceの中でactionIdとactionSequenceだけを進める。
- mine_targetは1 actionで1ブロックのみ、pickup_targetは残数以下のみ収納する。
- 取得数・破壊数はreceipt台帳から算出する。snapshot差分やlastResult文字列だけでは加算しない。
- 未確定actionがある間は次actionを出さない。結果が失われたら終了し、自動再実行しない。
- 取消受理後は新規actionを発行しない。既に確定した世界変更の結果は取消でcount0に上書きしない。
- 終端結果は不変。旧結果は新Skillへ加算しない。
- 0.0.20のrequestごとのCompletion、generation照合、初回claim前のfreshness確認を維持する。

## 3. 責務分担

Daemon: 対象block→取得itemの固定対応表、進行段階、候補選定、台帳、残数、失敗数、期限、部分成果、終了判定。
Forge: 現在のworld・対象・経路・道具・収納のauthority、採掘/拾得、実変更直前ガード、即時停止、確定receipt。

新Skillは既存primitiveを交互に発行するコードとし、CollectDropやMineを内部HTTPで呼んだり、子goalとして登録したりしない。
Daemon tickからHTTP/LLMを呼ばない。モデル未起動・provider設定なしでも実行できる。

## 4. requestとcapability

既存 `/v2/goal` にgoal unionの分岐を追加する。v2の文字列legacy goalは引き続き拒否する。

```json
{
  "version": 2,
  "session": "world",
  "daemonEpoch": "boot-id",
  "goalRevision": 7,
  "goal": {
    "type": "collect_block",
    "target": {"block": "minecraft:log"},
    "count": 5,
    "constraints": []
  }
}
```

- goalはtype/target/countが必須。constraints省略は空へ正規化する。それ以外のキーは拒否。
- targetはblockのみ。未知block/非対応blockは400 unsupported_block。countは厳密な整数1～64、bool等を拒否。
- constraintsは空だけ受理し、非空ならunsupported_constraint。黙って捨てない。
- 対象が現在見えていなくてもgoalは有効。実行段階の探索失敗と区別する。
- 対応表はDaemonに `COLLECT_BLOCK_TARGETS`、Forgeにも対応する定義を置き、fixtureで一致を検証する。
- `/v2/execution/open` のcapabilitiesに `collect_block_v1` を追加する。
- Forgeはこのcapabilityを確認してから新goalを送る。未対応Daemonへmine/collect_dropとして代替送信しない。
- 新しいMODバージョンは着手時のHEADから決め、build.gradle・CompanionMod・資料をそろえる。

## 5. wire viewと進捗

既存Skill objectのキーは維持し、collect_block専用progressを追加する。

```json
{
  "skillInstanceId": "skill-7",
  "type": "collect_block",
  "target": {"block": "minecraft:log"},
  "phase": "waiting_action",
  "progress": {"requested": 5, "acquired": 2, "mined": 3, "complete": false},
  "result": null
}
```

phaseは既存のselecting/waiting_action/cancelling/terminalを維持する。内部のstageは別に保持し、今回wireへ新しいphaseは増やさない。
requested/acquired/minedは0～64（requestedは1以上）。acquired<=requested、mined<=requestedを検証する。
completeは「発行した全actionの副作用が確定しているか」。採掘か拾得のどちらかが未確定ならfalse。

終端result:

```json
{
  "skillInstanceId": "skill-7",
  "status": "failed",
  "reason": "drop_unavailable",
  "progress": {"requested": 5, "acquired": 2, "mined": 3, "complete": true}
}
```

statusはcompleted/failed/cancelled。completedにはacquired=requestedかつcomplete=trueが必要。
minedは副作用の記録。取得目標の達成条件には使わない。UIの「2/5」はacquired/requested。
complete:falseなら確認済みの値として表示する。採掘3・回収2の失敗と、何も壊さなかった失敗を区別できる表示にする。

## 6. action/receiptと台帳の変更（最重要）

action JSONは既存mine_target/pickup_targetをそのまま使う。skillInstanceIdは同一、actionId/sequenceは各発行で新規。

| action | expected payload | 上限 | 成功時の加算 |
| --- | --- | --- | --- |
| mine_target | destroyed:{block,count} | 1 | minedのみ |
| pickup_target | acquired:{item,count} | requested-acquired | acquiredのみ |

現コード `SkillManager.result` はpayload_kindとskill.target_fieldを比較し、`_apply_result` は全成功をprogress_countに足している。
このままではblockを目標とするSkillがitem receiptを受け取れず、採掘を取得として数えてしまう。**Skill種類でなく発行済みactionを基準に検証・精算する構造へ変更すること。**

各発行時、台帳に不変のdescriptorを保存する:
`actionId, actionSequence, actionType, targetRef, payloadField, targetCanonicalId, maxCount, observationSequence`。
Skillのcurrentはdescriptorを参照し、settledにも照合情報を残す。単なるissued_ids集合だけで遅延receiptを受理しない。

受付順:

1. epoch/session/revision/skillInstanceIdを検証。
2. 発行台帳からactionIdを検索し、sequence、payload種類、対象ID、count上限、status/reasonの組を確認。
3. 同一terminal再送はACKのみ。矛盾した数量・種類・理由は409 conflicting_result。
4. runningはaccepted/count0、succeededはcompleted/count1以上、failed/cancelledはcount0。既存正常receiptはこの条件を満たす。
5. current actionのterminalを一度だけ精算。mineならmined、pickupならacquiredへ加算。
6. 既に取消待ちならその時点でcancelledへ閉じる。旧Skillやterminalへ新actionを出さない。
7. 通常時だけ次stageへ進める。

terminal後の既知receiptは検証してACK/記録だけ行い、終端progressを変更しない。
終端結果より遅いrunningは同descriptorを検証してACKし、状態を戻さない。
個数上限超過、destroyed/acquiredの取り違え、同じ文字列minecraft:logでもpayload種類違いは必ず拒否する。

## 7. 内部stageと動作

内部stageは `select_source / wait_drop / recover_drop`。phaseは配送状態を表す既存4値。
他にmine/pickupそれぞれの除外集合、mined済みblock参照、回収待ち開始時刻、失敗数、取得・破壊累積を保持する。

### select_source

1. acquired=requestedならcompleted。
2. fresh cacheのitemsに該当itemがあれば、距離順/同距離は参照ID順でpickup_targetを発行する。
3. 該当dropがなければblocksの該当blockを選びmine_targetを発行する。
4. 候補なしは既存6秒の検索窓を使い、最後までなければno_block_in_range。fresh cacheがなければstale_state。

pickup成功後は、取得数に達していなければ新観測を待ってselect_sourceへ戻る。
最初からあったdropのpickupがtarget_lost/path_not_found等なら、そのdropを除外して別drop、次にblockを検討できる。

### mine成功 → wait_drop

destroyed.count=1を確認したらminedだけ増やす。そのblock参照はSkill中の採掘済み集合へ入れ、再度mineしない。
blockを破壊できたことと収納できたことは別。**ここでは成功にも次mineにも進まない。**
発行時より新しいcache sequenceが来るのを待ち、該当item候補を探す。期限はmine terminal receipt受理から6秒、再pollで延長しない。
このsequenceは世界変更後であることの厳密な証明ではない。UUID・種類のForge再確認とreceipt台帳で正しさを保つ。
6秒以内に見つかればrecover_dropへ。見つからなければfailed/drop_unavailable（fresh更新自体がない場合stale_state）。
既存の最大16件のdrop観測を利用し、全世界の不在を主張しない。上限でdropが観測外になる場合も安全に失敗する。

### recover_drop

該当itemのpickup_targetを1件発行する。元の採掘由来のUUIDに限定しない。
正の取得を確認したら、残数があれば新観測を待ってselect_sourceへ戻る。正の部分収納でもこの条件を満たす。
到達後inventory_fullなら即failed。target_lost等なら別drop候補を探せるが、**回収が一度も成功しないまま次mineには戻らない。**
再探索の6秒窓は最初のpickup失敗で開始し、候補の入れ替わりで延長しない。単発実行中はaction期限を使い、失敗後に窓切れなら終了する。
回収候補なしはdrop_unavailable、到達失敗で尽きた場合はpath_not_found、連続失敗上限はretry_exhausted。

### 採掘上限と収納不足

成功したmine数はrequested以下。上限まで壊してもacquiredが足りなければ、落下物回収だけを許可し、追加mineしない。
MVPでは完全な収納余地予測APIは追加しない。満杯でmineに進んだ場合、最大1ブロックを壊した後pickupがinventory_fullで終わる可能性がある。
これは部分成果/副作用として明示し、その後に追加破壊しない。将来の収納preflightは、stackのmetadata/NBTを保持した実収納可能量に基づいて追加する。
「満杯なのに採掘を繰り返す」実装は不可。

## 8. 失敗・再試行・期限

既存reasonを再利用し、新しいSkill終端理由として `drop_unavailable` だけを追加する。action側にこの理由を追加する必要はない。

| 状況 | 処理 |
| --- | --- |
| mineのblocked | block除外、別block/dropへ。候補が尽きたらblocked |
| mineのtarget_lost/path_not_found | block除外、別候補へ。有界再試行 |
| tool_unavailable | failed即終端、別blockへ無限再試行しない |
| inventory_full | failed即終端、追加mineしない |
| unsafe_state/owner_unavailable/companion_unavailable/disconnected | failed即終端、次actionを発行しない |
| receipt未着・action/Skill期限 | failed/expired、未確定ならcomplete:false。代替action禁止 |
| 取消 | cancelled/stoppedまたはreplaced。確定済み両カウンタを保持 |
| 採掘後のdrop不在 | failed/drop_unavailable、破壊数を保持 |
| cache session喪失 | failed/disconnected。キャッシュ例外でtick全体を止めない |

timeoutは既存のSkill120秒、action30秒、ready10秒、結果待ち40秒、lease5秒、検索6秒、取消精算5秒を維持。
総期限は段階切替・成功・再試行で延長しない。大きなcountでも期限内達成を保証せず、途中までの実績で失敗し得る。

発行action最大192件、連続失敗3回。成功時は連続失敗数をリセットするが、全発行数と総期限はリセットしない。
blockedは従来どおり連続失敗へ数えず除外するが、全発行数には数える。block/dropの除外集合を別々に持つ。
未確定actionを期限切れで閉じた後、同じ操作を別IDで再発行しない。
同じblock参照に新たな原木が置かれても、このSkillでは再採掘しない。完全なblock incarnation追跡は今回追加しない。

## 9. 取消・安全・通信

既存の即時停止、SkillMutationGuard、SkillTickPolicy、SkillRequestFence、cancel ACK検証を必ず再利用する。
mine成功直後に取消されたら破壊数だけ残してcancelled、pickup未発行。pickup成功直後の取消なら取得数も精算する。
取消が先に確定していれば、遅れた成功receiptで目標数に達してもcancelled。完了が先ならcompletedを維持する。
失敗/取消で品物や破壊したblockをrollbackしない。

重要なForge接続上の確認:

- 現在のcloseSkill/failSkill等、skillTick以外の終了経路でも、Entityに確定済みの正のoutcomeがあればそれをreceiptへ残す。
- 既存のpickup対象追跡はUUID中心。collect_blockではexpected itemを保持し、実収納直前にもregistry名を照合する。UUIDが同じでも中身が変わればtarget_lost。
- 新actionのskillInstanceIdがviewのSkill IDと一致すること、action種類/対象が受理したcollect_blockの対応表に一致することをForgeで検証する。
- 未対応capability、不正応答、キュー不足は安全停止または有界待機。通常mineへ黙ってフォールバックしない。
- 旧requestのCompletionは現在requestを変更しない。5秒を超えた応答から初回claimしない。
- 再起動・再入場・epoch変更でresumeしない。inventoryは既存NBT保存、Skillはメモリのみ。

## 10. 実装箇所

| 箇所 | 必要な変更 |
| --- | --- |
| `agent/src/skill_protocol.py` | collect_blockのgoal解析、対応表、capability、progress/result検証、drop_unavailable |
| `agent/src/skills.py` | CollectBlockとstage遷移。Skill単位のtarget_field/receipt_key依存をaction descriptorへ移す。mine/collect_dropの互換を保つ |
| `agent/src/daemon.py` | 原則既存v2ルートを利用。新しいLLM呼び出しを追加しない |
| `forge-mod/.../SkillProtocol.java` | Skill typeとprogress unionを明示。現在の「targetがblockなら進捗はmined」前提を除去 |
| `forge-mod/.../ActionBridge.java` | collect_block受付、capability確認、Skill種類とcurrent action種類を分離。終端表示にacquired/mined/completeを渡す |
| `forge-mod/.../CompanionEntity.java` | 既存mine/pickupを再利用。実収納前item確認・正のoutcome保全の全経路を確認 |
| `forge-mod/.../SkillExecutionState.java` | 同一Skillのmine→pickup→mineでsequenceと重複排除が維持されることを確認 |
| `ObservationBridge/ObservationDiff/StateCache` | 既存items/blocks/cacheを再利用。新しい全体snapshot/voxel map/LLM用schemaは不要 |
| tests/fixtures | 同じtarget文字列で異なるpayload種類を含む混合action列を追加 |
| README/HANDOFF/protocol | 新入口、対応対象、部分失敗と非対応事項、実際の配置・稼働状況を更新 |

Javaの省略パスは `forge-mod/src/main/java/local/mcai/`。

## 11. UI

- 完了: 「原木を5個集めました（採掘3ブロック）。」
- 失敗: 「失敗[drop_unavailable] 回収2/5、採掘3。」
- 取消: 「取り消しました。回収2/5、採掘3。」
- 未確定: 「確認済み: 回収2/5、採掘3。未確定の操作があります。」

最終結果はverbose=falseでも一度表示する。内部の段階通知はdebug表示のままでよい。
今回の新Skillについては数量未確定を黙って確定数として表示しない。既存Skillも共通表示へ移すなら互換テストを追加する。
Skillがblockをtargetに持つことを理由に「採掘しました」だけを返す現在の分岐へ入れない。

## 12. 具体例

開始前inventoryにlog3、地面にlog2、原木ブロックが複数。依頼5。

1. pickup Aで2取得: acquired2、mined0。
2. mine Bで1破壊: acquired2、mined1。ここでは完了しない。
3. 新観測を待ち、pickup B-dropで1取得: acquired3、mined1。
4. mine C→pickup C-drop、mine D→pickup D-dropでacquired5、mined3となりcompleted。

同じreceiptの再送でカウンタを増やさない。開始前の3個は一切加算しない。
mine C後に他者がdropを取った場合、別の同種dropを拾ってもよいが、回収できなければ部分失敗で終了する。

## 13. 必須テスト

### Daemon（fake clock/cache、モデル不要）

- 新goalの型・数量1/64・非対応block・constraints・未知キー、capability。
- drop先行→mine→pickupの交互実行、1度にcurrent action1件、同一goalRevision/Skill ID。
- mine成功だけでは完了しない。requested1のmine→pickup完了を最小ケースにする。
- 初期所持品を無視。drop10から残数3だけ要求。inventory消費・deposit観測で取得実績を減らさない。
- mineとpickup双方のreceipt重複、異なるpayload種類、間違いsequence、過大count、遅延running、矛盾terminalを検証。
- action終了後にSkill stageが変わっていても、旧receiptを発行台帳で検証し正しくACKする。
- 採掘後dropなし/他者取得/merge/最大16件から漏れるケースで、追加破壊を続けない。
- inventory_full・tool_unavailable・unsafe_stateは即終端、正の部分収納は正しく計上。
- blocked候補から別候補へ、全blocked、既採掘位置へ置き直されたblockを再採掘しない。
- 6/30/120秒等の境界、最大192action、連続失敗3回、mine成功数requested上限。
- 取消→mine成功、取消→pickup成功、成功→取消、置換→旧receipt。確定両カウンタを保持し新Skillへ混ぜない。
- cache消失や例外で他sessionのtickを妨げない。epoch変更で旧goalを再実行しない。

### Forge / 統合

- 新Skill viewのacquired/mined/completeを厳密解析し、異なるSkill/action IDと対象不一致を拒否。
- 同一revisionのmine A1→pickup A2→mine A3を一度ずつclaimし、旧A1再送では世界を変えない。
- request-scoped completionとfreshness判定を、補助クラスだけでなく実際の配送→consume→claim経路で検証する。
- 実変更直前のHP/owner/期限/lease/対象種類チェック、正のoutcomeがcancel/failでも保全されること。
- verbose=falseで完了/取消/未確定を表示、再pollで最終通知を重複しない。

### 実ゲーム

- モデルなしで原木3個を採掘・回収し成功。開始前所持品がカウントされない。
- 既存dropだけで目標達成できる場合はblockを壊さない。
- 採掘後にdropをプレイヤーが拾う、収納満杯、回収途中の停止/別指示、経路失敗。
- pause/退出/Daemon再起動で古いactionが再開しない。partial resultが現物と矛盾しない。
- follow/look/pickup/deposit、collect_drop、mine count1、会話と停止に退行がない。

実行コマンド:

```powershell
python -m unittest discover -s agent/tests -v
.\scripts\forge.ps1 test --rerun-tasks
.\scripts\forge.ps1 build
```

テスト件数の増加だけを完了条件にせず、上記の混合receipt・取消・回収待ちのケースを実際に検証する。

## 14. 実装順と引き継ぎの更新

1. protocol fixtureと台帳のaction単位検証を追加し、既存2Skillの回帰テストを通す。
2. CollectBlockをfake cache上で実装。成功・部分失敗・取消・期限を先に検証。
3. Forgeの解析・コマンド・表示を接続。primitiveの変更は必要な検証とoutcome保全に限定。
4. Java/Pythonテストとビルド、実ゲームでの成功と失敗を確認。
5. 資料には実際のHEAD・配置jar・反映待ちを記録する。古い「未コミット」「反映済み」を引き継がない。

起動中のMinecraftへjarを上書きしない。ゲームの保存終了を確認してから配置する。
Daemon再起動スクリプトの既知の停止範囲問題が残っていれば、今回も対象repo/portを照合した方法で操作する。
世界の原状回復、無断の別MOD変更、検証用の無制限な採掘は行わない。

## 15. 後続拡張

block→item対応は独立した定義にし、単純な同名変換に固定しない。
将来のcollect(item,N)は、複数の供給元を選ぶ上位Skillとして追加する。今回のcollect_blockにrecipe/Plannerを混ぜない。
採掘・拾得のaction descriptorと効果の分離は、将来の道具作成・回収・納品にも再利用する。
厳密なdrop由来追跡、収納preflight、広域探索、metadata指定、道具耐久値は別の増分として仕様化する。

この初期スコープで実装前に必要な利用者判断はない。対象拡大や「自分の採掘由来だけ数える」への変更は、本書と異なる仕様変更として扱う。
