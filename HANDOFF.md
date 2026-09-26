# エージェント引き継ぎ — 2026-09-26

## 最初に読むもの

「Skill終端結果を既存Socialで発話する共通機構」の設計は [protocol/skill-terminal-social.md](protocol/skill-terminal-social.md)。**Phase 1〜5を実装・自動テスト済み（MOD 0.0.27、実ゲーム未確認）**。Phase 6（delivery ACKでの履歴登録 / Forgeからのpresent・ACK送出）は未実装。調査基点は `develop` / `c5bdd36`。
終端snapshot、取消後の通知回収、会話キュー共有、表現選択、固定fallback、dedupe、実表示後の履歴登録を定義している。Phase 1〜4の実装範囲は下の「Skill終端 → Social発話」節を参照。

`collect_block` は **Daemon・Forgeとも実装済み（MOD 0.0.22）**。仕様と既存の検証記録は [protocol/collect-block.md](protocol/collect-block.md)。
MVPは `minecraft:log`、採掘数ではなく新規回収数で成功判定し、既存のmine_target/pickup_targetを同じSkill内で順序づける。

Skill Layer MVP（`collect_drop`）は [protocol/skill-layer.md](protocol/skill-layer.md) の仕様に沿って **実装済み**（MOD 0.0.12）。
mine primitive（`mine_target`）は [protocol/mine-primitive.md](protocol/mine-primitive.md) の仕様に沿って **実装済み**（MOD 0.0.17）。
Skill Layer硬化（review-7373e37 のP1〜5）を **実装・自動テスト済み**（MOD 0.0.18）。
block観測のcandidate品質改善（表面露出フィルタ）を **実装・実機確認済み**（MOD 0.0.19）。
Daemon の `agent/src/skill_protocol.py` / `execution_registry.py` / `skills.py`、Forge の `SkillProtocol.java` / `SkillExecutionState.java` と既存クラスへの追加。
入口は `!agent do collect_drop <アイテム> <個数>`、`!agent do mine <ブロック>`、`!agent do collect_block minecraft:log <個数>`、v2 typed protocol。Planner・自然文からのSkill引数抽出は範囲外。
自動テストはPython 143件・Java 82件。基本の収集・mine・collect_block（実ゲーム）を確認済み。Skill終端Social通知は自動テスト範囲（実ゲーム未確認）。硬化（P1〜5）は自動テスト範囲で、実機の危険条件は未検証。

このファイル → [init.md](init.md)（設計仕様）→ [README.md](README.md) → 必要に応じて [Agent README](agent/README.md) と [行動ライフサイクル](protocol/action-lifecycle.md)。
`init.md` に作業ログを追加しない。READMEのバージョン別の節は当時の検証記録として読む。

Phase 6（Game Actions）に着手済みで、`pickup` / `deposit` の2操作とSkill Layer、mine primitiveが完了している。残りは `attack / place / craft / smelt`。
計画順は「mine primitive → `collect_block`/`collect(log,N)` → attack/place/craft → Skillを数種 → JEV typed selection → 必要ならクラウド選択 → 高レベルPlanner」。`collect_block`/`collect(log,N)` は次段階で、この増分では実装しない。
仕様は「一度に全部作らない」「1.7.10のpathfinding / recipe / inventory APIを確認しながら追加する」を明記しているため、1操作ずつ追加する。

## 現在地（2026-09-26 確認）

| 項目 | 確認結果 |
| --- | --- |
| Git HEAD | `403e605` 時点からSkill Layer / mine primitiveを実装（本ドキュメント更新前は未コミット） |
| MODバージョン | `0.0.27`（`forge-mod/build.gradle` と `CompanionMod` の両方で管理）。Prism配置済み |
| Prismの有効MOD | `mc-ai-companion-0.0.27.jar`（SHA-256 `2B1D4FECBF619C390670E8555AE25073A93DF395147044BB9833EAF3FAE0996B`）。0.0.26以前は `.disabled` |
| Daemon | PID `43416` が `127.0.0.1:8767` で待受。`protocol 1+2, social=True`。**P1-1/P2-4のDaemon修正は再起動後に反映**（`--shutdown-token` 付き） |
| LM Studio | PID `21708` が `127.0.0.1:1234` で待受。`unsloth/gemma-4-26b-a4b-it` |
| Minecraft | 終了状態 |
| 自動テスト | Python **143件**・Java **82件**・Forgeビルド成功 |

プロセス・HEAD・作業ツリーは変化するため、次回は必ず再確認する。PIDファイルやこの表だけを根拠に停止しない。
**反映済み・確認済み。** ガラス越しの原木で `blocked` 経路が実機動作（原木は破壊されない）。0.0.17 で失敗文言を `失敗[blocked] minecraft:log 0/1`（理由を先頭の短い形）に変更し、実機で表示を確認済み。block観測は typeごと最近傍4・合計最大32、経時破壊は0.0.14で実機確認済み。

## Skill Layer硬化 — review-7373e37 の P1〜5（0.0.18）

`collect_block`/Plannerへ進む前のライフサイクル・安全性の増分。P2-6（表示/Social統合）とP2-7（restart script）は今回対象外。

| 指摘 | 閉じたコード変更 | 追加した回帰テスト |
| --- | --- | --- |
| **P1-1** 取消後の成功receiptで旧Skillが終端しない | `skills.py` に `_close_if_settled()` を追加し、成功receiptを一度だけ精算した後の先着規則（取消が先なら `cancelled`、requested到達なら `completed`）を一箇所へ集約。成功分岐で `phase=="cancelling"` を新action発行・`selecting` 復帰より優先 | `test_skills.py`: `test_cancel_then_partial_success_settles_cancelled` / `test_cancel_then_success_at_requested_stays_cancelled` / `test_success_then_cancel_settles_cancelled` / `test_replace_then_old_success_settles_old_cancelled_only` / `test_cancelled_skill_stays_terminal_after_clock_advances` / `test_cancelled_skills_do_not_accumulate_in_other` |
| **P1-2** 安全条件・期限が世界変更の後で評価される | 純粋クラス `SkillMutationGuard.java`（権限/owner/HP>6/距離≤32/lease/action期限/target同一性）を追加し、`CompanionEntity.mutationGuard` として `pickup` 収納直前と `breakBlock` の `setBlockToAir` 直前に配置。`CompanionEntity` に `setActionDeadline` を追加。`SkillTickPolicy.java` でtick判定を集約し、確定済みpositive outcomeを後のunsafe/expiredで0へ上書きしない（`ActionBridge.skillTick` が使用） | `SkillHardeningTest.java`: `mutationGuardBlocksUnsafeHealthAndOwnerLeash` / `mutationGuardChecksLeaseDeadlineTargetAndAuthority` / `aResolvedPositiveOutcomeIsFinalEvenWhenUnsafeOrExpired` / `aResolvedNegativeOutcomeUsesTheEntityReason` / `anUnresolvedActionReportsTheFirstSafetyFailure` |
| **P1-3** 旧SkillのHTTP応答が新Skillの失敗として処理される | 純粋クラス `SkillRequestFence.java`（generation・session・revision・epoch・kind・開始時刻付き `Request`/`Completion`）を追加。`ActionBridge` の共有 `skillResponse/skillDone/...` を generation別の `openCall/goalCall/cancelCall`＋volatile `*Done` に置換し、新Skill/置換/session変更で `advance()`。古いgenerationの完了は破棄し、その完了で現在のin-flightを解除しない。古すぎる応答はleaseを延長しない | `SkillHardeningTest.java`: `advancingTheGenerationDiscardsOldRequests` / `openAckNeedsTheSameSessionAndAEpoch` / `cancelAckNeedsMatchingIdentityAndIdle` |
| **P2-4** v2 legacy goalとSkillのrevision/取消管理が別 | `skill_protocol.py validate_goal` は v2 の文字列goalを `legacy_goal_unsupported`（400）で明示拒否、`goal:null` は取消として許可。`SkillManager` の `goals` 依存・`_legacy`/`_legacy_view` を削除し `daemon.py` を更新。v1 `/v1/goal` のlegacyは不変 | `test_skills.py`: `test_v2_rejects_legacy_string_goals_but_allows_null_cancel` |
| **P2-5** cancel handshakeの失敗を成功扱いする | `startSkillGoal(goal, cancel)` と cancel専用 `Request` を追加。ACKは `SkillRequestFence.validCancelAck`（session/revision/epoch一致＋`status=="idle"`）のみ成功。timeout/null/409/staleは未完了として約1秒間隔で最大3回再試行し、未確認なら `failSkill("disconnected")` で安全停止（無限待機しない）。ローカル停止は `finishCancelHandshake` で通信を待たない。ACKを再送で成立させるため `skills.py` の `goal:null` を同一revisionでも冪等取消として受理 | `test_skills.py`: `test_null_cancel_is_idempotent_at_same_revision` ＋ `SkillHardeningTest.cancelAckNeedsMatchingIdentityAndIdle` |

維持を確認した不変条件: `collect_drop` 成功条件、`mine` count=1、1 action = 1 world mutation、acquired/destroyed分離、receipt idempotency、terminal result immutability、control lease、result queue reservation、opaque/projection境界、Daemon tickからHTTP/LLMを呼ばないこと。

## Skill transport硬化 — review-7cb43e9 の P1/P2（0.0.20）

`collect_block` 前の増分。前回P1〜5・BlockExposure/BlockCandidatesは維持。

| 指摘 | 閉じたコード変更 | 追加した回帰テスト |
| --- | --- | --- |
| **P1** 期限切れの初回action応答をclaimできる | `SkillRequestFence.fresh(request, now, lease)` を追加し、`ActionBridge.consumeSkill` の入口で `now - request.started > CONTROL_LEASE` の応答を**捨てる**（受信時刻ではなく送信時刻基準なので、pause中にworkerが早く返していてもgame threadのconsume時ageで判定）。stale応答はlease更新・新規claim・world mutationにつながる遷移に使わず、`failSkill` の理由にもしない。次のfresh pollでDaemon状態を取り直す。ローカルの安全停止（`SkillMutationGuard`／tickの安全net）は従来どおり先に適用 | `SkillHardeningTest.aReadyResponseOlderThanTheLeaseIsStale` |
| **P2** 世代付きCompletionの単一volatile欄で最新完了を失う | 共有 `openDone/goalDone/cancelDone` を廃止し、`SkillRequestFence.Request` が `volatile Completion completion` を保持。workerは自requestの `completion` にのみ書き、game threadは `goalCall.completion` 等**現在request自身**だけを読む。旧requestの完了は新requestのcompletionを上書きできない。`clearSkillCalls` は現在request参照のみnull化 | `SkillHardeningTest.perRequestCompletionIsNotErasedByALaterRequest` / `aLateWorkerForAnOldRequestCannotEraseTheNewerCompletion` |
| **P2 timeout** completionが失われても永久待機しない | `Request.expired(now, REQUEST_TIMEOUT_NANOS=6s)` を追加。open/goalはcompletion未到着でも期限超過でrequestを破棄して再送可能状態へ。cancelは既存の最大3回再試行と同じゲートに統合（期限超過は未確認試行として数える） | `SkillHardeningTest.anUnansweredRequestExpiresSoItIsNeverAwaitedForever` |

stale responseのreject箇所: `ActionBridge.consumeSkill` 入口の `SkillRequestFence.fresh(...)`。completionのdelivery: request-scoped `Request.completion`。lost completion: `Request.expired(...)` による有界timeout（open/goalは再送、cancelは3回上限）。

## collect_block — Daemon層＋Forge接続（0.0.21）

指示書 [protocol/collect-block.md](protocol/collect-block.md) をDaemon・Forgeとも実装（**実ゲーム確認済み**）。

- `skill_protocol.py`: collect_block goal解析（blockは `COLLECT_BLOCK_TARGETS` のみ、count1〜64、constraints空のみ、未知キー拒否）、capability `collect_block_v1`、終端理由 `drop_unavailable`。
- `skills.py`: **発行action descriptor台帳**へ組み替え。`SkillManager.result` の `target_field` 前提を外し、`_apply_result` は descriptor の payloadField（item→acquired / block→mined）で一度だけ精算。mine_target と pickup_target を同一Skillで交互発行できる。`collect_drop`/`mine` の意味・view・progressは不変。
- `CollectBlock`: stage `select_source / wait_drop / recover_drop`。成功条件は `acquired`（このSkillが収納した数）。開始前所持は数えない。`mined` は副作用カウンタ。mine成功では完了せず新観測を待ち（6秒・延長なし、失敗時 `drop_unavailable`）、回収が成功するまで次mineへ戻らない。blockedは別block/dropへ、尽きたら `blocked`。tool_unavailable/inventory_full/unsafeは即終端。取消は確定済み両カウンタを保持。
- テスト: `agent/tests/test_collect_block.py` 16件（混合action列・旧receipt再送ACK・過大count/種類違い拒否・drop_unavailable・取消精算・blocked・retry_exhausted 等）。既存 `test_skills.py` は互換のまま、capability期待値のみ更新。
- 追補（review指摘の2点）:
  - **descriptor基準のreceipt検証を共通化**（`_validate_receipt`）: actionId→descriptorを引き、`actionSequence`/`payloadField`/`targetCanonicalId`/`count<=maxCount` を毎回照合。`running`=accepted+0、`succeeded`=completed+1..maxCount、`failed`/`cancelled`=0 を固定。duplicate terminal=ACK、late running=ACKのみ（progress不変）、矛盾terminal=409、未知actionId=unknown_action。terminal Skillでも同じ検証。
  - **wait_drop/recover_dropをstage固有の絶対deadlineへ分離**: `wait_deadline = mine receipt + DROP_WINDOW`、`recover_deadline = 最初のrecovery失敗 + DROP_WINDOW`。poll/候補入れ替えで延長しない。wait_dropはnewer観測なしで期限到達→`stale_state`、newer観測ありでitemなし→`drop_unavailable`。recover_dropは回収成功まで次mineへ戻らない。generic `_fence_ready()` はselect_sourceのみで使用（simple Skillのfence挙動は不変）。
  - 追加テスト: descriptor不一致/種類違い/ID違い/succeeded count0/reason不正/late running ACK/terminalでのdescriptor検証、wait deadline固定/poll非延長/境界、recover deadline非延長・次mine禁止、120秒上限。
- テスト件数: Python **118件**（collect_block 27件）・Java **70件**。
- **Forge接続（0.0.21）**:
  - `SkillProtocol.java`: `collect_block` の type/target/progress union（`requested/acquired/mined/complete`、acquired・mined≦requested）を厳密解析。`COLLECT_BLOCK_ITEMS`（block→item対応表、現状 log→log）と `capabilityFor`/`collectItemFor`/`capabilities`。
  - `ActionBridge.java`: 入口 `!agent do collect_block <ブロック> <個数>`。open応答のcapabilitiesを確認し、`collect_block_v1` 未広告なら**mine/collect_dropへ代替送信せず**メッセージして安全停止。current action種類は descriptor ごとに `claimedField` で判定（mine/pickup混在）。`finishSkill` は §11 表示（完了「…をN個集めました（採掘Mブロック）」／失敗「失敗[reason] 回収A/R、採掘M」／取消「取り消しました。回収A/R、採掘M」／complete=falseなら「未確定の操作があります。」）。
  - `CompanionEntity.java`: `pickupItem(targetRef,maxCount,itemName)` に拡張し、UUID一致でもregistry名不一致なら `target_lost`（実収納直前の同一性確認）。mine/pickupは既存を再利用。
  - `SkillExecutionState.java`: 同一Skill内の mine→pickup→mine で sequence前進・旧actionId再実行禁止を確認（追加テスト）。変更なし。
  - 追加テスト: `SkillProtocolTest` の collect_block view解析・progress union厳密性・対応表/capability・capability解析・混合sequence。Daemon側 `COLLECT_BLOCK_TARGETS` とJava `COLLECT_BLOCK_ITEMS` の一致は両言語のfixtureで固定（Python側 `test_collect_block_mapping_is_the_fixed_mvp_pair`）。
- 実ゲーム: `!agent do collect_block minecraft:log 5` で原木を採掘→回収して完了することを確認済み（Daemon再起動＋ゲーム0.0.21読み込み後）。既存の collect_drop / mine / follow 等に退行なし。
- **Forge hardening追補（0.0.22）**:
  - `SkillProtocol.validateBinding(skill, action)` を追加し、claim前に `action.skillInstanceId == skill.skillInstanceId` と Skill種別ごとの action.type/target 対応（collect_drop=pickup+item一致、mine=mine+block一致、collect_block=mine(block一致) or pickup(mapping item一致)）を検証。`validateGoalBinding(skill, type, target, requested)` で開始済みgoalとも照合。`ActionBridge.consumeSkill` は claim・lease・world mutation の前に両者を呼び、不一致は `failSkill("action_failed")`（未claimなので偽receiptを送らない）。
  - `SkillProtocol.Skill` の terminal result を厳密parse: `result.skillInstanceId == outer`、status∈{completed,failed,cancelled}、`result.progress` を outer と同じschemaでparseし全フィールド一致を要求。completedは `complete==true` かつ成功カウンタ==requested（collect_blockはacquired、mineはmined）。failed/cancelledは部分成果可。`phase==terminal ⇔ result!=null` も固定。
  - `CompanionEntity.collectTarget` 冒頭で実収納前にregistry名を再確認し、不一致は `pickupStored=0`/`target_lost`（world mutationなし）。
  - 追加Javaテスト4件: binding不一致/許可（instance・type・target・未対応action）、goal binding、terminal result/progress不一致・completed不変条件・phase/result整合、混合sequence claim。Java 74件。
- 未消化: `ActionBridge` の配送→consume→claim を通す統合テスト（Minecraft依存のため未）、および §13 の一部異常系（収納満杯・回収途中停止・経路失敗・pause/退出）の実機確認。

## Skill終端 → Social発話 — Phase 1〜5（0.0.27）

仕様 [protocol/skill-terminal-social.md](protocol/skill-terminal-social.md) の段階1〜3を実装。**事実のauthorityはSkillの確定terminal result**、Socialは表現のみ、Forgeはworld/表示のauthority。LLM候補選択・conversation履歴登録はPhase 4〜6で未実装。

- `agent/src/terminal_presentation.py`（新）: 静的 `DESCRIPTORS`（task/metric/metric label/unit/表示順）、`TARGET_LABELS`、`REASON_MEANINGS`（reason→許される短い意味。未知reasonは推測せず enum 表示）、固定 `render_fallback`（≤512 UTF-16、数値・未確定注記を切らない）。`mined` は「Nブロック破壊」、`acquired` は累積取得で「今も持っている」とは言わない。
- `agent/src/terminal_events.py`（新）: `TerminalEventStore`。`_finalize` で一度だけ登録する outbox（最大100・TTL600）、identity `(daemonEpoch, session, skillInstanceId, terminalId)`、epoch/session内で単調増加の `eventSequence`、read-only snapshot（最大8・`afterSequence`/hasMore/overflow）、presentation台帳（not_started/generating/ready/delivered/suppressed）、present/deliverの重複排除・binding照合・同一Skill別terminalId衝突。
- `skill_protocol.py`: capability `skill_terminal_social_v1`、`validate_terminal_event`（typed progress union、bool/負数/未知キー拒否、completed不変条件）、`/v2/terminal-events`・`/v2/social/skill-terminal`・`/v2/social/terminal-delivery` の厳密validator。
- `skills.py`: `_finalize` が result の deep copy を authority として event を一度だけ記録（provider/HTTPを呼ばない、再finalize防止）。
- `daemon.py`: 3 endpoint。epoch不一致は409、未知terminal=404、破棄済み=410。read取得はaction/leaseを変更しない。
- `forge-mod/.../TerminalDeliveryState.java`（新、純粋）: identity台帳、received/queued/inFlight/displayed/suppressed、timeoutとHTTP完了が同一tickでもfirst-winsで一度だけ表示。
- `forge-mod/.../TerminalPresentation.java`（新、純粋）: Pythonと同一文字列の固定renderer。`protocol/fixtures/skill-terminal-fallback.json` で両言語一致を検証。
- `forge-mod/.../SkillProtocol.java`: `TerminalEvent` 厳密parse（typed progress、completed不変条件）と `TERMINAL_CAPABILITY`。
- `forge-mod/.../ActionBridge.java`: openの `skill_terminal_social_v1` がある時だけ新経路。`finishSkill` は実行状態を解放して表示をoutbox dispatcherへ委譲。`terminalTick` が `/v2/terminal-events` をCONTROL laneで取得し、`TerminalDeliveryState` でidentity重複排除して固定fallbackを一度だけ表示。**旧Daemon（capabilityなし）は従来の終端表示を維持**（二重表示しない）。
- 追加テスト: `test_terminal.py`（Python 19件: fixture一致・projection/不変・nested mutation不変・bool/負数/未知キー拒否・duplicate poll/present/ACK・ACK後outbox閉鎖・closed identityのpayload conflict・exact closed ledger追い出し後もidentity再生成なし・presentation台帳有界化とsay解放・duplicate recordのsequence非消費・binding/同一Skill衝突・旧revision・cancel後着・capability・HTTP3endpoint）、`TerminalSocialTest.java`（Java 5件: fixture一致・配送first-wins/重複/期限・terminal event parse/拒否・terminal pollのSkill制御優先）。
- 配送基盤の追加修正（最終レビュー）: `closed`（exact, 100件, sayなし）とは別に **compact closed digest 台帳（1024件, identityのblake2b 8byte→payload fingerprint）** を持ち、exact recordがLRU追い出しされても identity を再生成しない（同一payloadは no-op、異なるpayloadは `terminal_identity_conflict`）。保証範囲は digest 台帳内（後述）。terminal poll は `SkillRequestFence.terminalPollAllowed(open, goal, cancel, skillControlImminent)` に変更し、**active Skill だけでは停止しない**。control request in-flight/queued、またはこのtickで control を enqueue する場合のみ見送る（starvation回避）。
- 配送基盤の追加修正（feature/skill-social 再レビュー）: presentation台帳と closed identity 台帳を各100件に有界化（`MAX_PRESENTATIONS`/`MAX_CLOSED`）。closed は state/binding/ACK/payload fingerprint のみ保持し say を解放。`record` は identity を sequence 採番より先に確認し、同一 payload は既存返却（sequence非消費）、内容違いは `terminal_identity_conflict`。closed後も fingerprint で同じ性質を維持。`record` は nested を含め deep copy で store が snapshot を所有。CONTROL lane は `SkillRequestFence.terminalPollAllowed` により、active Skill / skill request in-flight 中は terminal poll を見送り Skill 制御を優先。
- 配送基盤の修正（feature/skill-social）: `TerminalEventStore.deliver` は displayed/suppressed ACK で outbox entry を閉じ（`_close`）、`afterSequence=0` の再pollにも出さない。ACK済み identity の `record` は再生成しない。`record` は identity を sequence 採番より先に確認し、同一 immutable payload の再送は既存 event を返して sequence を消費しない（内容違いは `terminal_identity_conflict`）。
- **Phase 4（0.0.25）**: `ConversationQueue` を `USER_CHAT`/`SKILL_TERMINAL` のtyped entryへ拡張（chat 4件・2048字は維持、terminal待機枠8件、単一FIFO）。`PingBridge` が両種を同一順序で処理し、terminalは `enqueueTerminal` で会話contextを初期化（typed commandだけでも通知可能）。terminalはSocial workerを使わず固定fallbackを表示。待機terminalが12秒でFIFO例外として先行chat中でも表示。forget時は未表示terminalを旧会話として先に表示→reset。退出/owner変更で `reset(null)`（末消化terminalは抑制）。At-most-onceは `TerminalDeliveryState`＋`PingBridge.seenTerminals` で維持。追加Javaテスト: `conversationQueueMixesChatAndTerminalInOneOrder` / `conversationQueueKeepsSeparateCapacitiesAndTerminalDedupe`。
- **Phase 4追補（0.0.26）**: terminal poll cursorを `TerminalPollCursor`（純粋）へ分離し、world/session切替の `synchronize()` で `terminalPoll.reset()`（afterSequence=0, nextPoll=0）。新 `(daemonEpoch, session)` は eventSequence=1 から取得でき、旧cursorを持ち越さない。`observe` はbinding内で単調。テスト: `terminalCursorResetsPerBindingAndNeverGoesBackward`。
- **Phase 5（0.0.27）**: 既存providerで候補選択。`terminal_presentation.render_candidates` が friendly/calm/concise の**事実完全な3候補**（≤512、Python/Java同一）を生成。`LocalSocialProvider.select_terminal` は通常chatと同じprovider設定・persona・予算で、候補IDのenumに限定した厳密schema＋8秒deadline。`SocialBrain.present_terminal` は固定rendererではなく**候補IDを選ばせ**、sayを候補から復元（自由文・intent・追加キー・未知IDは `SocialError`）。transport情報（session/epoch/UUID）はLLMへ送らない。`/v2/social/skill-terminal` は presentation台帳で generating/ready を管理し、**同じrequestでproviderを二重に呼ばない**。provider未設定/busy/例外/予算超過は固定fallback（mode=fallback）で同一say。fallback fixtureは candidates も含みPython/Java一致を検証。追加テスト: candidates fixture一致（Py/Java）、present_terminalのprovider選択/失敗/ busy、endpointの provider1回・二重present・fallback。
- 未実装（Phase 6）: delivery ACKによる会話履歴登録（`/v2/social/terminal-delivery` の displayed で既存履歴へ1ペア追加）、Forgeからの present/ACK 送出（現状Forge表示は固定fallbackのまま。present接続はSOCIAL laneでの実装が必要）、Daemon epoch変更時の旧作業表示。



## 実装済みの機能

- Phase 0～5: Forge環境、HTTP ping、Companionの生成・保存・手動操作、ローカル会話、独立したTactical判断、snapshotと差分観測・キャッシュ・再同期。
- 行動管理基盤（0.0.6）: 判断→両側検証→実行→結果通知。即時停止、取消・置換、古い判断と遅延結果の拒否。
- 会話指示（0.0.7）: 自然文から限定intentを生成しGoal Managerへ渡す。近距離followの維持と経路再試行。
- 通信Executor（0.0.8）: 会話・観測・行動制御・結果通知の4系統、各1worker・待機1件、30秒idleで解放。
- コンテキスト予算（Daemon）: Social/Decisionで入力・出力・コンテキスト上限を独立設定。履歴は古い往復から削る。
- 状態アイコンとデバッグ表示（0.0.9）: ライフサイクルの機械的なチャット通知を既定で止め、画面右上に考え中/行動中/待機中を表示。
  通知は `mcaicompanion.cfg` の `debug { B:verboseChatMessages=false }` をtrueにしたときだけ流す。Socialの返答と実行前の拒否理由は常に表示する。
- **アイテムの拾得（0.0.10）**: 目的 `pickup_item` / 判断 `pickup` / 理由 `goal_pickup・no_item_in_range`。
  9スロットのCompanionインベントリ（ワールド保存・死亡時ドロップ）、16ブロック以内の落下物の観測（最大16件・2ブロック刻み）。
- **所有者への受け渡し（0.0.11）**: 目的 `deposit_items` / 判断 `deposit` / 理由 `goal_deposit・inventory_empty`。
  所有者へ2ブロック以内まで近づいて所持品すべてを渡す。渡し切れなければ `owner_inventory_full` で残りを持ったままにする。
- **Skill Layer（0.0.12 / protocol 2）**: `collect_drop(item, count)`。v2 `/v2/execution/open`・`/v2/goal`・`/v2/action-result`・`/v2/skill-status`。
  DaemonがSkill進捗・候補選択・期限・理由を管理し、ForgeはUUIDで固定した対象だけを数量制限付きで収納する。
  入口は `!agent do collect_drop <アイテム> <個数>`。allowlistは log/cobblestone/iron_ingot/planks/stick、count 1〜64。手動操作・新指示は旧Skillを取り消す。
  レビュー反映: Skillを離れるときは `goal:null` のcancel handshakeを完了してから通常actionへ移る。terminal receiptは収納前にqueue枠を予約し、満杯時は pending として再送する（黙って捨てない）。Forgeは `timeoutMs` で単発actionを打ち切る。Daemonは取消receiptを再選択ではなくcancelledで終端する。
  control lease: action実行中もForgeが約1秒ごとに `/v2/goal` をpollしてleaseを更新し、最後の検証済み同epoch/session/revision応答から5秒を超えると `CompanionEntity` が収納直前（world変更前）に停止する。Daemonも最後のcontrol pollから5秒を超えたら新actionを発行しない（`status`取得やaction結果ではleaseを更新しない）。
  lease/receipt追加反映: leaseは受理が確定したpollのみ更新（`stale_goal`/`conflicting_goal`/`stale_state`等の拒否では更新しない）。action receiptの `goalRevision` を該当Skillのrevisionと照合し、不一致は409。terminal後に届いた既知actionのreceiptはrecorded/ACKのみで `settled` に記録し、terminal resultとprogressは変えない（未知IDは409）。
- **mine primitive（0.0.13→0.0.19 / protocol 2）**: `mine_target`（1 action = 1 block破壊）と mine候補のblock観測。
  観測はCompanion周辺16ブロック（水平±16・垂直±8）のallowlist blockのみ。typeごとに最近傍4件・合計最大32候補（一律N件だと近いdirtがlog/oreを締め出すため）。Daemonはopaqueな `block-<x>_<y>_<z>`、registry名、距離だけを扱う。
  **表面露出フィルタ（0.0.19）**: 6近傍のいずれかが「air / 非固体material / leaves」のblockだけを候補にする（`BlockExposure`）。glassはsolidなので露出扱いにしない。埋まったdirtや壁内部のoreが最近傍候補を占有して `blocked` になる経路を防ぐ。これは観測候補の品質のみで、実行可能性のauthorityは `MineObstruction`／`MineTargetTask` のまま。上限（typeごと4・合計32）は `BlockCandidates` で維持。
  道具選択はForgeが決定的（`Material.isToolNotRequired()` なら素手、必須ツールが無ければ `tool_unavailable`）。破壊はblock hardnessとtool speedに応じた経時処理（`destroyBlockInWorldPartially`/`swingItem`）。
  遮蔽/到達可能性のauthorityは採掘距離へ移動した後の `MineTargetTask`（`MineObstruction`＝`Material.isSolid()`＋leaves例外、MaterialLookupで純粋化）で、採掘開始前とworld変更直前の同一tickで block種・lease・遮蔽を再検証する。claim前は target identity/type/range のみ再検証し、現在位置からの直線遮蔽判定はしない（回り込める壁越しのtargetを誤除外しないため）。遮蔽時は `blocked`。観測候補が全て `blocked` なら**検索窓を待たず即座に**最終理由 `blocked`（`no_block_in_range` と区別）。邪魔なブロックは破壊しない（1 action = 1 block）。`mine` goal の count は1固定（2以上は `unsupported_count`）。
  進捗は `mined`、receiptは `destroyed:{block,count}` で `collect_drop` のprogressとは混ぜない。入口は `!agent do mine <ブロック>`。詳細は [protocol/mine-primitive.md](protocol/mine-primitive.md)。

### pickup / deposit の設計判断（重要）

**どのアイテムを拾うか・渡すかはモデルではなくForgeが決める。** pickupは最も近い落下物1件、depositは所持品すべて。
判断結果は `{"action":"pickup"}` / `{"action":"deposit"}` でtargetを持たない。品物を選ぶ判断は後続の増分に残した意図的な制約。
そのため種類を指定した依頼（「ダイヤだけ拾って」「砂だけ渡して」）はSocialがnoneにして、できることを説明する。

判断モデルへ渡すのは**落下物の個数と最短距離、Companionの所持点数だけ**で、アイテム名やエンティティIDは渡さない。
この境界は自動テストで固定している（`test_decision.py` / `test_goals.py` が payload に `minecraft` や `item-` が現れないことを検査）。

## 守る設計境界

- ForgeはゲームI/O・検証済み操作の実行を担当。provider SDK・APIキー・人格・長期記憶を持たせない。
- Tacticalへ会話履歴、persona、private memory、relationship、不要なプレイヤー識別子を送らない。アイテム名も送らない。
- 会話と行動のライフサイクル・世代を分離する。雑談だけでは現在の行動を取り消さない。
- session・goalRevision・actionIdで古い判断を拒否。実行直前にも個体・ディメンション・体力・距離、さらに**落下物の有無と所持点数**を確認する。
- providerの文字列をMinecraft commandやシェルとして実行しない。未知の操作と不完全な引数を両側で拒否する。
- 通常観測は差分。挨拶のたびに世界状態をLLMへ送らない。
- 推測で古いツールチェーンを更新しない。

### 操作を追加するときに必ず両側を直す場所

0.0.10で**MOD側の更新漏れによる実ゲーム不具合**を出したため、次の操作でも以下を最初に確認する。

| 対象 | ファイル |
| --- | --- |
| 会話intent（Daemon） | `agent/src/social.py` の `INTENTS` と `INTENT_INSTRUCTIONS` |
| 会話intent（**MOD側の許容リスト**） | `forge-mod/.../PingClient.java` の `parseSocialReply` |
| 目的・判断・理由（Daemon） | `agent/src/decision.py` の `GOALS / ACTIONS / REASONS / SYSTEM / DECISION_SCHEMA / validate_decision / MockDecisionProvider` |
| 判断入力・結果理由（Daemon） | `agent/src/goals.py` の `_input` と `REASONS` |
| 観測のtask・result（Daemon） | `agent/src/state_cache.py` の `TASKS / RESULTS` |
| 目的・判断・安全条件（MOD） | `ActionProtocol.java`、`ActionBridge.java`（`requestGoal` の許容リスト、実行直前の追加検証、完了時の理由対応） |
| task・result → イベント種別 | `ObservationDiff.java` |
| 手動コマンド | `DebugCommand.java`、`CompanionCommands.java` |
| Skill（Daemon） | `agent/src/skill_protocol.py` の語彙・理由、`skills.py` の状態遷移 |
| Skill（MOD） | `SkillProtocol.java`、`SkillExecutionState.java`、`ActionBridge.java` の `requestSkill`/`requestMine`/`skillTick`、`CompanionEntity.pickupItem`/`mineBlock` |
| block観測（Daemon/MOD） | `state_cache.py` の `TASKS / RESULTS / block_candidate`、`ObservationBridge.java`/`ObservationDiff.java`、`SkillProtocol.BLOCKS` |

Skillを増やすときは allowlist（Daemon `SUPPORTED_ITEMS`/`SUPPORTED_BLOCKS` と MOD `SkillProtocol.ITEMS`/`BLOCKS`）と fixture を同時に更新する。

`IntentTest.everySupportedIntentIsAccepted` がDaemonの全intentをMOD側に通す回帰テストなので、intentを増やしたらここにも追加する。

## 検証状況

直近の自動検証はPython **83件**・Java **48件**・Forgeビルド成功（0.0.17時点）。

実ゲームで確認済み（0.0.9～0.0.14分）:

- デバッグ通知OFFでチャットが静かになること、状態アイコンの切り替わり。
- 会話「そこに落ちてるの拾って」→ 判断 → 拾得完了 → `!agent status` の所持品に反映（`minecraft:sand x1`）。
- 会話「持ってるもの渡して」→ 近づいて受け渡し → 所持品が空になること。
- 「近くに拾えるアイテムがありません」「種類は指定できない」の拒否経路。追従・拾得に回帰がないこと。
- **Skill Layer（0.0.12）**: `!agent do collect_drop minecraft:stick 2` で対象固定の収集が完了すること。
- **mine primitive（0.0.14）**: `!agent do mine <ブロック>` で、プレイヤーと同じく時間をかけた経時破壊が行われること。
- **block観測の表面露出フィルタ（0.0.19）**: `!agent do mine minecraft:dirt` で、目の前に露出した土を正常に選んで採掘できること（埋まった土を最近傍候補として選び `blocked` になる経路が解消）。

実モデルで確認済み: pickup/depositの判断（対象あり・なしの両方）、会話からの `pickup_item` / `deposit_items`、否定・種類指定の拒否。

未確認・残る制限:

- mine primitive（0.0.17）の遮蔽authority・`blocked` 即終端・失敗表示（理由先頭の短い文言）は実機確認済み。回り込めるsolid wall越しの採掘・leaves越しは未検証。
- mine primitive（0.0.14）の経時破壊は実機確認済み。素手/道具の選択・`tool_unavailable`・連続採掘・block観測の網羅性は未検証。
- Skill Layer（0.0.12）の基本収集は実機確認済み。部分収納（対象がmaxCountより少ない）・地面残量・取消・置換・経路失敗・満杯は未検証。
- ワールド再入場後のCompanionインベントリ保持（NBT保存は実装済み・実機未検証）。
- 死亡時の所持品ドロップ、`inventory_full`（9スロット満杯での拾得）、`owner_inventory_full`（所有者満杯での受け渡し）。
- 拾得・受け渡し中の経路失敗（`path_not_found`）での中断。
- 否定時の固定文面のゲーム内再確認、予算超過のゲーム内専用表示（未実装。API/ログに理由は出る）。
- 厳密なtokenizerは未導入（UTF-8バイト数で保守的に推定、`utf8_estimate`）。
- 敵・体力・ディメンションの同期、死亡後の再spawn・未読込チャンクの重複防止、長時間安定性。
- 再接続・応答逆転・重複配送・受付上限などの異常系は主に自動テストの範囲。

## このPCの環境と設定

| 項目 | 値 |
| --- | --- |
| リポジトリ | `C:\Users\dansaka\Work\Repository\mc-ai` |
| JDK | Eclipse Temurin 8u504-b01、`.tools/jdk8u504-b01` |
| Gradle | 5.6.4、wrapperと配布ZIPのSHA-256固定 |
| ForgeGradle / Forge | anatawa12版 `1.2-1.1.1` / `1.7.10-10.13.4.1614-1.7.10` |
| mappings / Python | MCP `stable_12` / Python 3.14.0、Daemonは標準ライブラリのみ |
| Prism | `1.7.10-mod-basic` |
| ゲームディレクトリ | `%APPDATA%\PrismLauncher\instances\1.7.10-mod-basic\minecraft`（`.minecraft`ではない） |
| Daemon / LM Studio API | `http://127.0.0.1:8767` / `http://127.0.0.1:1234/v1`、LM Studioは認証有効 |
| 利用者指定モデル | `unsloth/gemma-4-26b-a4b-it` |

8766はこのPCで利用できなかったため、Prismの `config/mcaicompanion.cfg` とDaemonは8767を使う。コード既定値は8766。
OpenALFix導入後も音声処理のクラッシュ記録があり、完全解消とは断定しない。`openalfix-1.0.0.jar` と既存ExcludeMobsを残す。

ローカル設定は `agent/config.local.json` と `agent/decision.local.json`。キーは `.tools/social-api-key.txt`。
すべてGit管理外。内容を丸ごと表示してキーを漏らさない。環境変数 `MCAI_SOCIAL_API_KEY` / `MCAI_DECISION_API_KEY` がファイルより優先。

予算設定:

| 設定 | Social | Decision |
| --- | --- | --- |
| context_window_tokens | 65,536 | 65,536 |
| max_output_tokens | 256 | 256 |
| prompt_budget_tokens | 8,192 | 4,096 |
| history_budget_tokens | 4,096 | 省略＝0 |
| safety_margin_tokens | 512 | 512 |

65,536は前回LM Studioの実ロード長を確認した値。理論最大262,144をそのまま設定しない。`reasoning_effort: none` を使用。

## 再開・反映手順

ビルド・配置・Daemon操作は専用スクリプトに統一した（`.claude/settings.json` と `opencode.json` で許可済み）。

```powershell
.\scripts\forge.ps1 build                    # ビルド＋Javaテスト
.\scripts\run-python-tests.ps1               # Pythonテスト（引数はそのまま渡せる: -v）
.\scripts\deploy-mod.ps1 -Version 0.0.17     # Prismへ配置（ゲーム起動中なら中断）
.\scripts\restart-daemon.ps1                 # Daemon入れ替え（graceful shutdown→起動、二重起動を拒否）
.\scripts\stop-daemon.ps1                    # Daemon停止のみ（loopback /local/shutdown、昇格不要）
.\scripts\daemon-status.ps1                  # 待受PID・プロセス数・protocol行・token有無・ログ末尾（読取専用）
.\scripts\daemon-state.ps1                   # 観測キャッシュの要約（items/blocks数とサンプル、読取専用）
.\scripts\show-daemon-logs.ps1               # Daemonログのtail（読取専用）
.\scripts\read-mc-source.ps1 -SourceEntry net/minecraft/world/World.java -Pattern setBlockToAir   # 逆コンパイル済みMCソースの閲覧（読取専用）
```

`deploy-mod.ps1` は指定バージョン以外の `mc-ai-companion-*.jar` を `.disabled` にし、配置後のSHA-256を表示する。
`restart-daemon.ps1` はまず loopback `/local/shutdown` で終了させ（管理者起動のDaemonでも昇格不要）、生存時のみプロセス停止へフォールバックし、起動後は該当プロセスが1つだけであることを検証する。
初回cloneは `.\scripts\setup-jdk.ps1` → `.\scripts\forge.ps1 setupDecompWorkspace build`。開発起動は `.\scripts\forge.ps1 runClient`。

`opencode.json` では上記スクリプト・`python -m unittest discover -s agent/tests`・読み取り専用の `Get-*` を allow にし、`Measure-Command` や任意URLへの `Invoke-RestMethod`、`Add-Type` など任意実行になり得るものは allow していない（必要なときだけ承認する）。

ゲーム一時停止中は観測も止まり、15秒以上でstaleになる。状態確認中はワールドを一時停止しない。
Daemon再起動で会話履歴・キャッシュ・goalは消える。ワールド保存済みのCompanionと所持品は残り、観測は再同期する。

### 環境で踏んだ罠（次回も起きうる）

- **Daemonの二重起動**: PythonのHTTPServerは `allow_reuse_address` を設定するため、Windowsでは同一ポートへ二重bindが成功してしまう。
  どちらが応答するか不定で、ログファイルも共有して「動いていない」ように見える。必ず `restart-daemon.ps1` 経由で入れ替え、待受プロセスが1つか確認する。
- **管理者権限の罠**: 管理者シェルでDaemonを起動すると、通常権限の `Stop-Process`/`taskkill` では停止できず（Access denied）、毎回昇格が必要になる。
  `restart-daemon.ps1` はまず loopback HTTP `POST /local/shutdown`（`--shutdown-token` の共有トークン付き）で終了させるため、**管理者起動のDaemonでも通常権限から入れ替えられる**。
  ただし `/local/shutdown` 実装前のDaemonは一度だけ管理者シェルかタスクマネージャで停止する。以後は通常のPowerShellから `restart-daemon.ps1` を実行すれば昇格不要。
- **ツール実行時のハング**: `Start-Process` でDaemonを起動すると、呼び出し元シェルの子プロセスとして残り、opencode等のbashツールが子孫の終了を待って応答しなくなる。
  `restart-daemon.ps1` は `Invoke-CimMethod Win32_Process Create` で起動して親を WmiPrvSE にし、呼び出し元から切り離す。ログは `cmd /c ... > stdout 2> stderr` で取得する。
  ポート確認は `Get-NetTCPConnection`（約0.6秒）ではなく `TcpClient` の接続プローブ（数ms）を使う。
- **PowerShellツールが使えないセッションがある**: `"hello"` すら「アクセスが拒否されました」になることがあった。
  その場合はBashから `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/xxx.ps1 > out 2>&1` で代用できる。
  出力をファイルへリダイレクトしないと、隠しプロセスがパイプを保持して呼び出しが戻らない。
- **ビルド成果物の上書き**: バージョンを上げる前にビルドすると、前バージョンのjarが新しいコードで上書きされる。
  Prismへ配置済みのjarは別ファイルなので影響しないが、`build/libs` のjarとバージョン番号の対応は信用しすぎない。
- **ゲームログの文字コード**: `latest.log` はCP932。`iconv -f CP932 -t UTF-8` を通さないと日本語が読めない。

## 主要コードと状態保持

- `agent/src/context_budget.py`: 予算検証・推定・履歴の削除。
- `agent/src/social.py` / `decision.py`: 独立したprovider、会話intentとTacticalの入力・出力検証。
- `agent/src/goals.py`: 有界の目的管理・推論worker・世代・実行結果。判断入力の組み立て（落下物と所持点数の要約もここ）。
- `agent/src/daemon.py` / `state_cache.py`: HTTPと観測キャッシュ（owner/companionのインベントリ、hostiles、items、blocks）。
- `agent/src/skill_protocol.py` / `skills.py` / `execution_registry.py`: v2のSkill語彙・検証、`collect_drop`/`mine` の状態遷移、実行権と結果台帳。
- `forge-mod/src/main/java/local/mcai/` の `PingBridge`・`ConversationQueue`・`PingClient`: 会話FIFO、返信の世代、intentの許容リスト。
- 同 `ActionBridge`・`GoalState`・`ActionProtocol`: 実行権限、世代管理、制御と結果通知、操作ごとの安全条件。
- 同 `IoExecutors`: 4系統の通信Executor。`CompanionHud`: 状態アイコン（クライアント専用、ClientProxy経由で登録）。
- 同 `CompanionEntity`・`CompanionCommands`: 個体・経路探索・操作・9スロットのインベントリ（follow / pickup / deposit / pickup_target / mine の各タスク）。
- 同 `ObservationBridge`・`ObservationDiff`: 観測・差分・ACK・再同期。`blocks` は周辺16ブロックのallowlist block候補（typeごと最近傍4・合計最大32）。
- 同 `SkillProtocol`・`SkillExecutionState`: v2のSkill view/action検証、claimとaction台帳。
- `protocol/` と各tests: 通信仕様・fixture・回帰テスト。

観測は毎秒、ACK済み位置から2ブロック以上の累積移動で座標送信。無変更でも約5～6秒ごとに空イベント。
落下物は `item-<UUID>` で最大16件、採掘候補blockは `block-<x>_<y>_<z>` でtypeごと最大4・合計最大32件、距離は2ブロック刻み。インベントリ差分は `entity`（owner/companion）で区別する。
キャッシュは32セッション・各100イベント。会話は32セッションで予算内の直近往復を保持。両方メモリのみ。

## 次の機能候補（未着手）

Phase 6の残りは `attack / place / craft / smelt`。`pickup` / `deposit` / `mine` は完了しているので再実装しない。
計画順は「mine primitive → `collect_block`/`collect(log,N)` → attack/place/craft → Skillを2〜4種 → JEV typed skill selection → 必要ならクラウド選択 → 高レベルPlanner」。

- 次段階は `collect_block` / `collect(log,N)`: `mine_target` と `pickup_target` を順序づけるSkill。`collect_drop` の意味・成功条件は変更しない（§15）。mineの破壊数とpickupの取得数を同じprogressへ二重加算しない。
- `attack`: 敵の観測（`hostiles`）は既にあるため判断入力は揃っている。対象選択の可否、武器・ダメージ、危険時の撤退など安全条件の設計が増える。
- 実ゲーム未検証項目（mine、インベントリ保持、満杯時の挙動、経路失敗）を潰してから次へ進む選択肢もある。
- goto・長期記憶、JEV/クラウドprovider、GUI設定、マルチプレイヤーは未実装。高度な割り込み分類、予約実行、進捗を用いた会話も後続。

## ログ・成果物の参照

- Daemonのログ: `.tools/daemon.stdout.log` / `.tools/daemon.stderr.log`、PID記録 `.tools/daemon.pid`（現在性は保証しない）。
  `intent-*` `lifecycle-*` `phase*-*` は過去セッションの記録。
- ゲームログ: Prism内 `minecraft/logs/fml-client-latest.log`（MODの初期化・例外）と `latest.log`（チャット、CP932）。
- 配置済み0.0.17のSHA-256: `9847AE37AE8A91ED7357A2CDAB90FC18C45453A8E21E39F18ACC3EE04A89E681`。
- Claude向けの権限設定は `.claude/settings.json`（読み取り専用コマンド、上記3スクリプト、WebFetchの許可ドメイン）。

古い手順・実装経緯はGit履歴から参照できる。過去のPIDや「未コミット」「起動したまま」を現在の状態として扱わない。

