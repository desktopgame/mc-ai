# Skill Layer MVP 実装仕様 — collect_drop

状態: **実装対象の仕様。コード未実装。** 2026-09-26、調査基点 `d032115`（MOD 0.0.11）。
利用者の「Skill Layer: 実装仕様化依頼」27項目を前提とする。[元のアイデア](../mod-idea/skill.md)は背景資料、本書をMVPの実装契約とする。
今回作成するのは仕様のみ。実装・起動・配置は行わない。既存の実装済み機能を説明する文書ではない。

## 1. 範囲と確定した判断

最初のSkillは `collect_drop(item, count)`。指定registry名の落下物を、実行開始後に**このSkillのactionによって新たに取得した累積個数**がcountに達するまで集める。
開始前の所持品、他の操作で得た品物は加算しない。取得済みの品物の消費・受け渡し・死亡時ドロップで取得実績を減らさない。
countは整数1～64、bool・小数・数値文字列は拒否する。全actionの取得上限を残数に制限し、依頼数を超えて拾わない。
Companionごとにactive Skillは1つ、実行中Forge actionも1つ。新しい操作指示・手動操作は旧Skillを取り消す。会話だけでは取り消さない。

今回の受け入れ入口は `!agent do collect_drop <registryName> <count>` とtyped protocol。
自然文から引数付きSkillを抽出する機能は後続とし、今回は既存のSocial intentを維持する。これによりSkill実行の検証にLLMを必須としない。
Socialへの将来接続は「検証済みtyped goalを、既存のIntentOrder ticket照合後に同じ受付へ渡す」で固定する。自由文をSkillへ渡さない。

含めないもの: 採掘、道具選択・製作、Planner、階層Skill実行、探索移動、Skill並列実行、checkpoint/resume、複雑な制約、全metadata/NBT、クラウドprovider。

### 利用者の概念例から具体化・調整した点

| 点 | 決定と理由 |
| --- | --- |
| progressの「純増」 | snapshot差ではなくactionの実取得量を累積する。既存コードに手動depositと死亡時ドロップがあり、通信順も独立しているため |
| result.status | Skillは `completed/failed/cancelled`、既存actionは `succeeded/failed/cancelled` を維持。混同しない |
| failure reason | `no_item_in_range`、`path_not_found`、`expired`等を再利用。表示上は「観測した候補に対象なし」等へ変換する |
| protocol | 新しい制御はv2を追加する。v1は厳密なキー検証と1revision=1actionなので、同じ応答へ任意フィールドを足さない |
| restart時の結果 | プロセス消失後の終端結果・正確な数量の復元は保証しない。安全停止と自動再実行しないことを保証する |
| 候補範囲 | 既存の半径16ブロック・最大16件を使う。範囲内の全落下物を網羅したことや到達可能性は保証しない |
| retry | 同一対象をSkillレベルで繰り返さない。Forge内の経路試行3回を使い、失敗対象を除外して次へ進む |

実装前に利用者の判断が必要な未決事項はない。本書の数値・命名を初期値として実装できる。

## 2. 現コードの根拠と変更箇所

| 現コンポーネント | 調査結果 / 変更 |
| --- | --- |
| `agent/src/goals.py` | goalは固定文字列、1revisionに1action。旧actionの遅延結果は受付だけして新goalを書き換えない。共通の所有権・世代管理を抽出し、Skill管理と共有する |
| `agent/src/decision.py` | follow/stop/look/pickup/depositの単発判断。collect_dropには呼ばない。既存判断・検証を維持 |
| `agent/src/skills.py`（新設） | SkillManager・CollectDrop実行状態・選択・進捗・期限・終端結果。Minecraft/HTTP/LLM非依存のロジックを分け、clockを注入 |
| `agent/src/execution_registry.py`（新設） | session/revision受付、Companion単位の実行権、boot ID、action結果台帳。GoalManagerとSkillManagerで二重に権限を持たない |
| `agent/src/skill_protocol.py`（新設） | typed goal/action/resultの厳密な検証、理由・語彙allowlist、独立した入力型 |
| `agent/src/daemon.py` | v2のopen/control/result/statusルート、Skill用の定期tickとshutdown。モデル設定なしでもcollect_dropを使える |
| `agent/src/state_cache.py` | 現在のitemsはID→type/distance、最大16件、stale15秒。構造は再利用し、UUID参照を受け付ける既存identity検証を利用 |
| `ActionBridge.java` | 現状はaction終了後に同revisionの次actionをclaimできない。v2の制御・結果台帳・期限・cancel receiptを追加。既存CONTROL/RESULTSレーンを再利用 |
| `GoalState.java` | 現在は1revisionでclaim1回だけ。既存クラスの契約は維持し、新 `SkillExecutionState.java` でSkillとaction状態を分離する |
| `ActionProtocol.java` | v1用の厳密検証を維持。新 `SkillProtocol.java` でv2を検証し、モデル判断用reasonCodeをSkill actionへ流用しない |
| `CompanionEntity.java` | pickupは毎tick nearestItemを選び、storeした数を結果に残さない。対象固定・数量制限の新pickup経路と不変のActionOutcomeを追加 |
| `ObservationBridge.java` | 現在の `item-<entityId>` は再利用され得る。落下物キーを `item-<Entity UUID>` へ変更。値のtype/distanceと既存deltaを維持 |
| `ObservationDiff.java` | キーをopaque参照として扱うことを確認し、ID再利用・消失/出現の回帰テストを追加 |
| `CompanionCommands.java` / `PingBridge.java` | 手動操作・即時停止は共通arbiterを通す。自然文抽出は今回増やさず、既存の会話順序を維持 |
| `DaemonClient.java` | v2応答・エラーの厳密な読み取り、読み取り専用status取得。通信上限は既存を維持 |

パス省略したJavaファイルは `forge-mod/src/main/java/local/mcai/` 配下。

## 3. 責務・権限・スケジューリング

DaemonはSkill instance、goalとの関連、target selector、requested/acquired、phase、失敗回数、除外対象、current action、取消状態、終端結果を保持する。
Forgeは実世界のauthority。個体、所有者、dimension、対象UUID・種類・距離・拾得待ち時間・到達可能性・収納可能量を検証し、移動とinventory変更をサーバーゲームスレッドで行う。
Forgeはaction単位の取得量と実行済みIDを保持するが、Skillの総進捗・残数・次対象は判断しない。

SkillManagerはモデルworkerに載せない。HTTP受付・action結果・cache更新の後、および単一の100ms周期tickで短いstepを進める。
tickがHTTP/LLMを呼ぶことは禁止。必要なcacheのコピーを取得後にmanagerロック内で遷移し、cacheとmanagerのロックを相互に取り合わない。
既存DecisionProviderが遅くてもSkill停止・結果受付を妨げない。

同一Companionへの旧sessionと新sessionの競合はExecutionRegistryで解決する。Forgeから検証済みの新session openを受けたら旧所有権を失効させる。
両managerが別々に操作を発行しない。新MODは既存の単発goalもv2共通制御へ通し、内部で既存GoalManagerへ委譲する。
v1クライアントは従来経路を利用可能だが、v2所有中の同一Companionへのv1制御は409 `execution_conflict`。未知sessionの取消で他sessionを解除しない。

## 4. 語彙と依頼の検証

MVPの依頼可能itemは静的allowlist:
`minecraft:log`, `minecraft:cobblestone`, `minecraft:iron_ingot`, `minecraft:planks`, `minecraft:stick`。
まず数量・反復を検証するための範囲で、registry全列挙や自動検出は不要。拡張はDaemonの語彙定義とForgeの検証定義を同時更新し、fixtureで一致確認する。
Forgeは実際のregistryにも存在することを受付時に確認する。未知名、allowlist外は `unsupported_item` で拒否。
観測に対象がなくても依頼自体は有効。実行開始後の探索で失敗し得る。allowlistと候補集合を混同しない。

`target: {"item": "minecraft:log"}` はそのregistry名の全metadataを含む。樹種指定等はまだ受け付けない。
実ItemStackのmetadata/NBTは既存storeの同一性検査で保持し、異なるstackを無理に合成しない。
将来targetへmetadata等を追加する際はschema/capabilityを明示的に更新する。現在の未知キーは拒否する。

constraintsは省略または空配列のみ受理。空でない配列は `unsupported_constraint` で**開始前に拒否**する。
未知キー・型違いを無視しない。unsupportedな新依頼で既存の動作を置換しないよう、Forgeで構文と語彙を検証してから新revisionを採番する。
Daemon側での最終拒否時は新Skillを作らず、既に停止した旧動作を自動再開しない。

## 5. 識別子と通信契約

識別子は既存identity形式 `[A-Za-z0-9_-]{1,80}`。整数revisionは0～2,147,483,647。boolを整数扱いしない。

| 識別子 | 発行元 / 意味 |
| --- | --- |
| session | 既存ObservationBridge。観測・world入場の世代 |
| goalRevision | Forgeの共通操作受付。新指示・取消ごとに単調増加。同じ依頼のpoll/retryでは増やさない |
| skillInstanceId | Daemonが受理したcollect_dropごとにUUID発行。goalと別のID |
| actionId | Daemonが各単発actionごとにUUID発行。配送再試行は同ID、別実行は別ID |
| actionSequence | Skill内の単調な1始まり連番。Forgeが古いreadyを再実行しないために使用 |
| daemonEpoch | Daemon起動ごとのUUID。再起動後の古いcontrolを新依頼として解釈しない |
| targetRef | session内の `item-<Entity UUID>`。ローカルな候補参照、外部モデルに実UUIDを送る必要はない |

### APIの追加

すべてPOST JSON、v2応答のversionは2。8KiB制限、同じキー集合・型を両側で厳密検証する。
既存 `/v1/snapshot`, `/v1/events`, `/v1/state` はversion1のまま。観測と制御のversionを混同しない。

| endpoint | 用途 |
| --- | --- |
| `/v2/execution/open` | snapshot ACK後に `version,session` を送る。応答 `version,session,daemonEpoch,capabilities`。capabilitiesは `collect_drop_v1` を含む固定配列 |
| `/v2/goal` | create/置換/cancel/poll。下記requestとview |
| `/v2/action-result` | actionのrunning/terminal receipt。下記schema |
| `/v2/skill-status` | `version,session,daemonEpoch,skillInstanceId` に対して過去を含むSkill viewを返す。開始・再実行は一切しない |

openは現在のcacheにCompanionが存在するsessionだけ受理。同じsession・epochへの再送は同一結果。v2をopenしたsessionは以後v1実行制御不可。
新MODはv2未対応サーバーへcollect_dropをv1 pickupとして代替送信しない。明示的に未対応表示する。
epoch不一致は409 `daemon_restarted`、Skill未知は409 `unknown_skill`。新epoch取得後も旧Skill requestを再送しない。
再起動を検出したForgeはローカルactionを停止、旧操作を失敗表示し、観測を新sessionで同期して、次の利用者指示を待つ。

### Skill request

```json
{
  "version": 2,
  "session": "obs-session",
  "daemonEpoch": "boot-id",
  "goalRevision": 7,
  "goal": {
    "type": "collect_drop",
    "target": {"item": "minecraft:log"},
    "count": 5,
    "constraints": []
  }
}
```

constraintsは受理後必ず空配列へ正規化。goalはこのobject、既存の単発goal文字列、または取消を表すnullのunion。
collect_dropのgoal objectのJSON Schemaは以下。HTTP envelopeは第5節の必須5キーのみを許可し、全フィールド必須とする。

```json
{
  "type": "object", "additionalProperties": false,
  "required": ["type", "target", "count"],
  "properties": {
    "type": {"const": "collect_drop"},
    "target": {
      "type": "object", "additionalProperties": false, "required": ["item"],
      "properties": {"item": {"type": "string", "enum": [
        "minecraft:log", "minecraft:cobblestone", "minecraft:iron_ingot",
        "minecraft:planks", "minecraft:stick"
      ]}}
    },
    "count": {"type": "integer", "minimum": 1, "maximum": 64},
    "constraints": {"type": "array", "maxItems": 0}
  }
}
```

これは実行受付のschemaであり、現在のモデルサーバーに新しいschema機能を要求するものではない。
既存文字列は `follow_owner/stop/look_at_owner/pickup_item/deposit_items` のみ。stopはForge即時停止後、goal:nullとして送る。
同じsession/revisionで内容が同じなら同じSkillを返し、新instanceを作らない。内容が異なれば409 `conflicting_goal`。
小さいrevisionは409 `stale_goal`。大きいrevisionは旧実行の発行権を失効させ、新しい依頼を受理する。

### v2 Skill view

```json
{
  "version": 2, "session": "obs-session", "daemonEpoch": "boot-id",
  "goalRevision": 7, "status": "running", "error": null,
  "skill": {
    "skillInstanceId": "skill-id", "type": "collect_drop",
    "target": {"item": "minecraft:log"}, "phase": "waiting_action",
    "progress": {"requested": 5, "acquired": 2, "complete": false},
    "result": null
  },
  "action": {
    "actionId": "action-3", "actionSequence": 3,
    "skillInstanceId": "skill-id", "companionId": "companion-id", "dimension": 0,
    "type": "pickup_target", "targetRef": "item-entity-uuid",
    "item": "minecraft:log", "maxCount": 3, "timeoutMs": 30000,
    "observationSequence": 21
  }
}
```

top statusは `idle/thinking/running/completed/failed/cancelled`。thinkingは既存の単発LLM判断だけ。
取消精算中はtop runningとphase:cancellingを返すが、Forgeはこのphaseで必ず現在actionを停止し、新規claimを禁止する。
actionは配送可能な現在の1件、またはnull。running通知受理後はnullとし、Forgeは保持したactionを継続する。
終端viewはaction:null。Skillなし（idle、既存単発goal）ならskill:null。
`/v2/skill-status` は同じviewのactionを常にnullにし、元のgoalRevisionを返す。status取得は実行権・leaseを更新しない。
既存単発goalは旧action objectを `type: legacy_action, payload: <既存ActionProtocol object>` で包むタグ付きunion。旧actionのsucceededをtop completedへ写像する。
各unionの許可キーを別々に検証し、Skill actionを既存のdecision/schemaへ押し込まない。

### action result

```json
{
  "version": 2, "session": "obs-session", "daemonEpoch": "boot-id", "goalRevision": 7,
  "skillInstanceId": "skill-id", "actionId": "action-3", "actionSequence": 3,
  "status": "succeeded", "reason": "completed",
  "acquired": {"item": "minecraft:log", "count": 3}
}
```

statusは `running/succeeded/failed/cancelled`。runningはreason:accepted、count:0。
全Skill action receiptはitemを必須とし、発行内容と一致、countは0～maxCount。terminalでのみ取得量を確定する。
応答は `{"version":2,"accepted":true}`。同一terminalの再送は同じACK、数量は二重加算しない。
同IDで異なるterminal/数量は409 `conflicting_result`。terminal後の遅いrunningはACKして無視。
未発行ID・別Skill・別session・sequence不一致は409。古いSkillの既知IDはその台帳だけへ反映でき、新Skillへ加算しない。
legacy actionのresultは別unionとしてskillInstanceId/actionSequence/acquiredを持たず、旧status/reasonを維持する。

v2 request検証失敗は400の固定error (`invalid_request/unsupported_item/unsupported_constraint`)。capacity超過は503 `busy`。
stale観測による開始拒否は409 `stale_state`。形式拒否にはSkill ID・terminal resultを作らない。
受理後の失敗はHTTP 200のview内terminalで返す。この区別によりinvalid_requestを実行失敗として混ぜない。

## 6. 取得の原子性と進捗

既存store(ItemStack)は、同種・同NBTの既存slotに詰め、空slotへコピーして格納する実装を再利用する。
新pickup_targetは固定したUUIDだけを追跡し、途中でnearestItemへ切り替えない。
到着時はゲームスレッドで以下を一つの不可分な処理として行う。

1. session/revision/actionの実行権、取消フラグ、期限、安全条件を確認。
2. 生存する同UUIDのEntityItem、registry名一致、拾得待ち時間0、距離1.5以内を確認。
3. live stackのコピーから `min(maxCount, liveCount)` 個だけを切り出してstoreへ渡す。
4. 実際に格納できたstoredだけlive stackを減らす。0になった場合のみentityを消す。残りはdatawatcher経由で更新する。
5. actionIdに紐づく不変のoutcomeにstoredを保存して操作を終了する。stop()でこのoutcomeを消さない。

stored>0ならsucceeded/completed（部分収納でもよい）、stored=0ならfailed/inventory_full。
1actionで収納操作は最大1回。次tickや応答再送で同じ収納を繰り返さない。
Skill累積は `acquired = Σ 一意のactionIdの確定terminal.count`。通常時のprogress.complete=trueは「発行済みactionの取得量が全て確定」の意味。
current actionを発行した時点からready/running中は取得量未確定なのでcomplete=false。完了receiptでtrueへ戻す。
成功条件はacquired=requestedかつ全発行action確定。inventory_changedやtask_completed観測では加算しない。
snapshotとresultの順序が逆でも同じ値になる。途中で取り出された品物・死亡時ドロップも取得実績から減算しない。

結果通知が消失した場合、実取得量をcache差から推測しない。確認済みacquiredとcomplete:falseを返し、「確認済み3個、未確定あり」と表示する。
これは通信断・プロセスクラッシュ時に実世界変更と通知を原子的に永続化できないための明示的な制限。exactly-once配送や完全な監査ログを保証しない。

## 7. Skill状態とsequence

保持するphaseは `selecting / waiting_action / cancelling / terminal` の4つ。START・CHECK_PROGRESSはstep内の短い処理とし独立状態を増やさない。
waiting_action内ではactionのready/running/terminalを保持する。終了したactionの後もgoalはrunningのまま、ForgeがSkillとactionの状態を別々に持つ。

| phase / 入力 | 遷移 |
| --- | --- |
| 受理 | immutable request、UUID、開始時刻、総期限、初期progress0を作成しselecting |
| selecting、完了数到達 | completedへ |
| selecting、fresh cacheに候補あり | actionを1件だけ発行しwaiting_action |
| waiting_action、正の取得結果 | 台帳で一度だけ加算→selecting、失敗連続数リセット |
| waiting_action、対象消失/到達不能/一時的失敗 | 対象除外、失敗数加算、上限内ならselecting |
| 任意、取消/置換 | 新action発行禁止→cancelling→旧action確定または取消猶予切れでcancelled |
| 任意、timeout/安全条件喪失 | 新発行禁止・現在action失効→failed。未確定数量はcomplete:false |

### 候補の選び方とcache更新待ち

現在sessionのfresh cacheから、item一致かつ除外集合にない候補を、丸めたdistance昇順・targetRef辞書順で選ぶ。
既存キャッシュの位置・距離だけで「到達可能」と断定しない。対象が16ブロック範囲外、別dimension、未知UUIDならForgeが拒否する。
観測上の最大16件の中に対象がないことは世界全体の不在を意味しない。探索移動・チャンク読込はしない。

選択時のcache sequenceをactionに記録する。action後は少なくともそれより新しいsequenceを待ち、最大6秒で打ち切る。
これは結果後の世界更新を厳密に証明するfenceではない。台帳で進捗を管理し、Forgeの再検証で古い候補を拒否するために安全性を保つ。
同じ対象から部分収納した場合も、新actionIdで残数だけ再要求できるが、直前receiptでmaxCount未満を収納したら次のstepで収納可能性をForgeに再確認させる。
新しいcacheが来ても候補なしなら最大6秒の検索窓まで待つ。窓の終了時にno_item_in_range、fresh更新自体が無ければstale_state。
既知対象がなくなっただけで依頼をinvalidにはしない。

## 8. retry・timeout・有界性

時間はwall-clock時刻でなくmonotonic clock/System.nanoTimeを使う。一時停止時間も期限に含む。
Forgeはゲームtickが止まっている間に世界を変更できないため、再開tickで期限確認を**収納前**に行う。

| 制限 | MVP値 / 適用 |
| --- | --- |
| Forge経路試行 | 既存FollowRetryの約20tick間隔・連続失敗3回。成功リセットは維持し、action全体期限で無限化を防ぐ |
| 単発pickup action | 30秒。既存60秒はthinking専用で、実行timeoutが無いため新設 |
| 未claim ready | Daemon発行から10秒で失効。再発行しない、failed/expired |
| 制御lease | 最後に正常な同epoch/session/revisionのpoll応答を受けてから5秒。初回は最大5秒前の応答だけclaim可能 |
| Skill総期限 | 120秒。成功・再探索で延長しない |
| 連続失敗action数 | 3。target_lost、path_not_found、expiredを対象にする。正の取得で0へ戻す |
| 全action発行数 | 192（64×3）。上限でretry_exhausted |
| 検索/新cache待ち | 各6秒、かつ総期限以内 |
| 結果配送 | 既存の最大3送信試行・失敗間隔1秒。操作の再実行ではない |
| 結果待ち期限 | 未claimは10秒、running受理後は40秒以内、かつ総期限以内。running再送では延長しない |
| 取消結果待ち | 最大5秒。未受信ならcancelled/complete:false |

同一対象に新actionで再挑戦しない（正の部分取得後の続きを除く）。失敗対象はSkill終了まで除外する。
制御pollは既存と同じ約1秒間隔。返信を受信した時刻だけでなく要求開始からの経過も5秒以内と確認し、古いHTTP返信でleaseを延長しない。
旧revisionの返信、failed/cancelled/completed、cancelling phase、別epoch、通信エラーではleaseを更新せず停止する。
連続3失敗ならretry_exhausted。上限より前に候補が尽きたときは、到達失敗を含むならpath_not_found、それ以外はno_item_in_range。
terminal結果未受信のactionに代替actionを出してはならない。期限切れ時は終了し、同じSkillを再開しない。

最大32 active/session entries、terminal Skillは100件・10分、action台帳はactive Skill当たり192件以内。activeはLRU追出しせずcapacity不足をbusyで拒否する。
同一Companionでは取消精算中の旧Skill1件と新active1件まで。短時間の連続置換で上限を超える場合は新受付をbusyにするが停止は常に受理する。
Forge側も同じ上限で台帳と未ACK結果を保持する。空きがなければ新actionをclaimしない。terminal結果を保持する枠を確保してから収納する。
上限超過で黙って結果を捨てて次のactionへ進む実装は禁止。

## 9. 取消・置換・遅延結果

ゲーム内の即時停止が基準。停止/新指示を受理した同じゲームスレッド処理で旧実行権を失効させ、経路と移動を止め、未配送のreadyをclaim禁止にする。
その後にrevisionを進め、旧action receiptと新controlを別レーンで送信する。配送順には依存しない。
収納と取消は同じゲームスレッドで直列化され、取消受理より前に済んだ収納は加算、受理後は追加収納しない。
既に収納完了してoutcomeが確定している場合、後からcancelled/count0で上書きせず、そのsucceeded receiptを旧Skillへ届ける。

Daemonが新revisionを受けたら、旧Skillの新発行を止めてcancellingにする。旧actionの終端receiptがあれば精算してcancelledへ。
旧Skillの完了が既に確定していた場合はcompletedを維持する。旧Skillがcancellingに入った後のreceiptでrequestedに達してもcancelledとする。
未claimのactionも古いreadyが配送中かもしれないので、running未受信を「未実行」と見なさない。5秒以内にcount0取消receiptが届かなければ数量未確定で閉じる。
Forgeは発行内容を受信済みなら、未claimでも破棄時にcancelled/count0のreceiptを残す。
新SkillはForgeで旧actionの停止を済ませた後に開始できる。旧精算は別台帳で行い、新Skill進捗には影響しない。

通信切断中にDaemonだけが取消を知った場合、Forgeへの瞬時伝達は保証できない。次のpollまたは5秒lease失効で止める。
ゲーム内の即時停止とネットワーク越しの取消の保証を混同しない。
終端結果は不変。猶予・期限でcomplete:falseとして閉じた後の既知receiptはACK/記録だけ行い、進捗や新Skillを更新しない。
結果の完全性が必要な将来版では永続台帳等を設計するが、MVPで自動補完・自動再実行しない。

## 10. safety・session喪失・再起動

現状はHP<=6、所有者から32ブロック超、死亡、owner/Companion/worldの不一致、経路失敗による停止とEntityAISwimmingがある。
creeper退避・火からの脱出・自動食事は実装されていない。今回も新しいreflexを作った扱いにしない。
既存の安全条件をSkill actionの実行直前・実行tick・収納直前に適用し、破ったらfailed/unsafe_state等で終了。自動resumeしない。
EntityAISwimmingは既存ローカルAIとして維持し、進めない場合はaction期限で停止する。
将来明示的なreflex割込みを加えるときは同じarbiterを通し、Skillをfailed/unsafe_stateで閉じる（自動resumeは別仕様）。

owner不在はowner_unavailable、個体死亡・消失はcompanion_unavailable、world/session喪失はdisconnected。
Forgeはワールド終了、再入場、観測session変更時にローカル実行を停止。既存の旧session取消通知をベストエフォートで送る。
Daemonはcache stale（15秒）や個体変更でもSkillを終了する。退出通知が来なくてもtickで検出する。

Daemon再起動後は旧メモリのSkill/結果を復元しない。epoch mismatchとleaseでForgeを停止し、旧requestを新規作成しない。
Forge再起動後もtaskはidle、inventoryは既存NBTから復元する。旧Skillをresumeしない。
両プロセスが消えた場合「必ず永続的なterminal結果を取得できる」とはしない。生存中の側でfailed/disconnectedを表示し、記録の無いSkillはunknown_skill。
いずれも新しい利用者指示だけが次の実行を開始できる。

## 11. Skill terminal result・理由

```json
{
  "skillInstanceId": "skill-id", "status": "failed", "reason": "no_item_in_range",
  "progress": {"requested": 5, "acquired": 3, "complete": true}
}
```

viewのskill.resultにこのobjectを入れ、phase:terminal、action:nullとする。結果のprogressとviewのprogressは一致させる。
status:completedはreason:completed、cancelledはstopped/replaced/disconnectedのいずれか。
UIは固定reasonから日本語を生成し、LLM返答を成功の証拠として使わない。debug通知がOFFでも最終結果・部分成果・数量未確定は利用者へ1回表示する。
状態HUDはselecting/waiting_actionを行動中、cancellingを停止処理中に写像する。再pollで最終通知を重複表示しない。

| reason | terminal / 意味 |
| --- | --- |
| completed | completed。requestedに到達、未確定actionなし |
| no_item_in_range | failed。検索窓内の観測候補に該当品がない（全世界の不存在ではない） |
| path_not_found | failed。観測した対象へ経路を確保できず他候補もない |
| retry_exhausted | failed。連続失敗または全action数の上限 |
| inventory_full | failed。実対象のstackを1個も収納できない |
| unsafe_state | failed。体力・距離等の安全条件違反、明示的な安全割込み |
| owner_unavailable / companion_unavailable | failed。必要な個体の不在 |
| stale_state | failed。必要なfresh観測を待てない |
| expired | failed。ready、action、Skillの期限 |
| disconnected | failedまたはcancelled。通信/セッションの喪失。明示的退出取消を受理した場合はcancelled |
| stopped / replaced | cancelled。明示停止/新指示 |
| action_failed | failed。不正receipt/不整合/想定外のaction異常。自由文は制御に使わない |

action限定reasonは既存accepted/completed等に `target_lost` と `target_not_ready` を追加する。
対象の消失・種類変化・範囲外はtarget_lost、拾得待ち時間が残る場合はtarget_not_ready。
両方とも失敗対象を除外して再選択し、連続失敗数へ算入する。最終Skill理由には通常no_item_in_range/retry_exhaustedを使う。
未対応requestは第5節の受付エラーであり、受理済みSkillをinvalid_requestで終える用途には使わない。

## 12. 観測・結果の再利用とprivacy

必要な観測は既存のCompanion ID/位置/体力/inventory、owner位置、dimension、itemsのtype/distanceとsequence。
itemsキーをUUID参照へ変えるだけで、MVPに個々のstack数・全NBT・詳細座標・全registryを追加しない。
到達後の実個数・収納量・metadata同一性はForgeが知る。Daemonの候補は到達保証ではない。
UUIDの変化、merge/despawn、他者による取得は既存item_entered/updated/leftイベントに反映する。
変化が無いheartbeatでもcacheのfreshness/sequenceが進む既存動作を利用する。

deltaはcacheの同期専用。Skillロジックには専用の `collect_candidates(cache, selector)` projectionを渡す。
LLMにdelta履歴やcache全体を渡さない。collect_dropはSocial/Tactical/Plannerを呼ばず実行可能。
将来Social抽出はローカル1回のstructured outputでよいが、型・語彙・数量・constraintsを再検証し、過去の会話の指示を再実行しない。
将来外部モデルへ送るprojectionはローカルUUIDではなく一時的opaque aliasと固定型を使い、会話本文・人名・任意detailを含めない。
Dynamic Schema生成はDaemonのprojection/語彙層に置く。ForgeがLLM用schemaを生成する経路は作らない。

## 13. 具体的sequence

正常: 開始前log3個、依頼5個、drop Aが2個・Bが10個。

1. Forgeが引数を検証、旧操作を停止しrevision7を発行。DaemonがSkill Sを作りprogress0/5。
2. Aを選びaction A1/maxCount5を発行。Forgeが固定UUIDへ移動し2個格納、succeeded/count2。
3. DaemonがA1を一度だけ加算。新cacheを待ち、Bをaction A2/maxCount3で指定。
4. Forgeが3個だけ格納しBに7個残す。succeeded/count3。
5. Daemonが5/5、completed/complete:true。既存所持3個は計数しない。

部分失敗: 3個取得後、対象なし。検索窓6秒終了でfailed/no_item_in_range、3/5、complete:true。3個は残る。
取消: 2個取得済み、次actionは移動中。Forgeで停止→cancelled/count0 receipt→Daemonはcancelled/stopped、2/5、complete:true。
収納直後の取消: 次actionが1個格納済みなら確定succeeded/count1を旧台帳で精算し、cancelledのprogressは3/5。
結果消失: 2個確認済み、次actionの収納結果が不明。新actionを出さず期限で終了、2/5、complete:false。成功と断定しない。

## 14. 実装順・テスト・受け入れ基準

実装順: (1)protocol/台帳/純粋な状態遷移 → (2)Forgeの対象指定・数量制限pickup → (3)制御と観測を接続 → (4)手動typed入口とUI → (5)実ゲーム。
既存pickup/deposit/follow/lookと会話は回帰対象。通常のpickupは今までどおりnearest1対象で、新Skill用動作へ黙って置き換えない。

### Daemonの自動テスト（fake clock・fake cache、モデル不要）

- count境界1/64、0/65/bool/小数/未知キー/unsupported item/constraintを拒否。見えていない既知itemは受理後にno_item_in_range。
- 開始前所持品を加算しない。複数actionの正のcountを合算し、requested超過receiptを拒否。
- 同revision再送で同Skill/同action、異なる内容は409。旧revision・epoch・sessionを拒否。
- result重複、terminal後running、矛盾terminal、未知ID、別item/sequence。正のcountは一度だけ加算。
- 結果とinventory deltaの到着順を逆転して同じ進捗。inventoryの消費・deposit観測で累積を減らさない。
- 取消→旧成功receipt、新goal→旧receipt、未claimの取消、取消と完了の両順序、旧terminal不変。
- lease/ready/action/Skill/検索/結果待ち期限、連続失敗3回、全action上限、通信停止でもtickで終端。
- 対象除外、部分収納後の新action、候補なし・上位16件外の対象、距離同値の安定選択。
- 32/100/192の上限、結果保管枠不足、別sessionから同Companionへの要求とv1/v2競合。
- DecisionモデルをblockしてもSkill停止・結果処理が進む。Skillだけの起動でモデル設定不要。
- restartで旧epoch requestを再実行しない。未知Skillのstatus取得で何も作成しない。

### Javaの自動テスト（Minecraft非依存部分を分離）

- 同revisionでA1完了後A2を1回だけclaim。旧A1、sequence飛越、ID変更再送を拒否。
- Skill phaseとaction終了の独立、旧IntentOrder ticket拒否、手動操作による取消。
- 取得予定量・空き容量・部分格納・上限の計算、ActionOutcome不変、取消直前/直後の保存規則。
- 結果キュー3送信で実操作を再実行しない。ACK喪失の再送で同じreceipt。
- 期限切れ/安全条件違反を収納より先に評価。ゲームpause後のtickで追加取得しない。
- protocolの正負fixture、v1既存動作、CONTROL/RESULTS/SOCIALの独立、HUD・最終表示の重複防止。

### 実ゲームで必須

- 開始前log3、依頼5、drop2+10で新取得5、地面に7残る。別種類がもっと近くても拾わない。
- 同registryの異なるmetadata/NBTは合成ルールを保つ。満杯/部分空き時の結果と地面残量。
- 移動中に対象をプレイヤーが拾う、despawn、対象が動く、経路が塞がる、16ブロック外へ出る。
- 2/5時点で停止、置換、手動deposit。旧Skillの追加取得なし、新Skillへ旧数量が混入しない。
- HP低下、所有者32ブロック超、退出、dimension変更、Companion死亡。
- Daemon停止/再起動、モデル停止中、ネットワーク結果喪失、一時停止後の復帰。自動再開なし、未確定表示。
- 既存follow/look/pickup/deposit、行動中会話、明確な停止、否定時の継続が退行しない。

実装完了の条件は単純な成功だけでなく、取消・重複・部分失敗・再起動ケースの検証結果を記録すること。
自動テストだけでゲームAPIの収納・drop保存・経路・tick順を検証済みにしない。

## 15. 維持する拡張点

- TypedGoal → Skill factoryを分離し、将来Plannerは同じ受付へtyped subgoalを渡す。
- SkillInstanceとActionInstanceを分離。将来parentSkillIdを追加してもgoalRevisionを子ごとに増やさない。
- TargetSelector objectを維持し、item単一文字列へ内部状態を固定しない。metadata等は必要時にversion/capability付きで追加。
- Primitiveの効果receiptをactionId単位で扱う。将来mineの破壊数とpickupの取得数を同じ進捗へ二重加算しない。
- collect_block/collectは探索・採掘・拾得を順序づける別Skillとして追加し、collect_dropの成功条件を変更しない。
- RetryPolicy、固定reason、Projection、cancel hookをSkillごとに差し替え可能にする。初期実装で汎用graph engineは作らない。
- constraintsは受付の検証と実行時保証の両方を伴って追加。未対応のまま受理しない。
- 結果台帳・永続化は将来交換可能にするが、今回のメモリ上の保証を永続保証と説明しない。
