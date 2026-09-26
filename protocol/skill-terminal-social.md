# Skill終端結果 → Social発話 — v0.1.0

状態: **基本経路とreview-f33f7f5対応を実装済み**。実装基点 `ed1ca65`（旧MOD 0.0.31）、当初設計基点 `c5bdd36`。
基本の終端発話には過去の実機確認がある。最新の異常系すべての確認済みを意味しない。

以下は実装指示書として作成した**目標契約と受入条件**を維持する。全節を現在の保証と読み替えない。
現行の導入手順は [README](../README.md)、確認結果は [リリース記録](../RELEASE_NOTES.md)、差分は [既知の問題](../knwon_issue.md) に集約する。

## v0.1.0時点の実装と差分

- snapshot/outbox、既存providerによる3候補選択、共有会話queue、固定fallback、実表示ACKと履歴登録を実装。
- presentなし/生成中fallback ACK、有界pending ACK、in-flightを含む12秒表示期限、forget時の固定表示、独立cleanupへ修正。
- Forgeのbindingキーは完全identityへ統一されていない（KI-01）。
- ACK上限32件と最大2回の送信失敗、履歴lock競合により登録欠落があり得る（KI-02）。
- epoch変更時の旧作業表示は専用経路未実装（KI-03）。
- 台帳はexact/digestの有限LRUで、§6.4の連続sequence＋gap方式は未実装。session辞書の長期有界性、overflow表示、HTTP総時間上限にも差分がある（KI-04）。
- 新APIのresponse bindingやvariantIdと本文の完全照合は受入条件として残る。現行PingClientは主にsay長とmode、PingBridgeは候補本文集合との一致を検査する。
- 今後の受入テストは§13/14を使う。記載されているだけで検証済みとは扱わない。

既存契約: [Skill Layer](skill-layer.md)、[collect_block](collect-block.md)、[行動ライフサイクル](action-lifecycle.md)、[Agent README](../agent/README.md)。

## 1. 目的と推奨案

**Daemonで確定したSkill終端結果を保存し、Forgeが既存の会話キューへ通知を投入する。既存Social providerは表現を選び、コード側の共通rendererが確定した事実を埋めて表示する。**

`completed / failed / cancelled` を対象にし、`collect_drop / mine / collect_block` で同じ通知機構を使う。途中のPrimitive receipt、retry、別候補への移行は通知しない。

自由な文章を生成してから数値だけ検査しても、「全部集めた」「世界に存在しない」のような事実の改変は防げない。MVPはLLM出力を、検証済み表現候補のID選択に限定する。候補はpersonaと会話文脈を見て選べるが、status・reason・全確定カウンタ・未確定注記を省けない。自然さの自由度は限定されるが、**事実不変をpromptへのお願いだけにしない**。

これは第二の人格・第二のLLMではない。同じSocialBrain、SocialProvider、設定、persona、会話履歴、予算管理を使い、入力種別と出力schemaだけを分ける。自由文による全面的な言い換えは後続の別設計とする。

## 2. 現行コードと衝突する点

### 2.1 通常会話

- Forge `PingBridge.onChat` → `ConversationQueue` → `IoExecutors.Lane.SOCIAL` → `PingClient.socialTurn` → `/v1/turn` → `SocialBrain.chat` → `LocalSocialProvider`。
- `ConversationQueue` は文字列のFIFO。待機4件・合計2,048文字、epochで古い応答を排除。`PingBridge` は同時に1件だけ処理する。
- `IoExecutors` は SOCIAL / OBSERVATION / CONTROL / RESULTS 各1 worker・待機1件。通常会話と通知用に別のLLM workerを追加しない。
- Daemonの会話キーは `(conversation session, player)`。execution sessionとは別物。`SocialBrain` はprovider呼び出しを含め単一のnonblocking lockで直列化し、競合時は `busy`。履歴は最大32 session、ContextBudgetでuser/assistantの往復単位に削る。
- 通常会話の出力は `reply + intent`。そのまま終端通知に流用すると意図しないactionへ接続できるため、通知にはintentを生成させない。
- 現行personaには「ゲーム状態は与えられていない」「操作完了を偽らない」という指示がある。終端通知で完了を説明する際には入力契約を区別する必要がある。
- providerはloopback限定、既存APIキー、モデル、context予算を利用。既存通常会話のprovider timeoutは最大45秒、ForgeのSocial読み取りは50秒。通知の待機上限にはそのまま使わない。

### 2.2 Skill終端

- `skills.py:SkillManager._finalize` がstatus/reason/result.progressを確定し、activeから外して `other` に保存する。既存保存枠は最大100・TTL 600秒。後着receiptでterminal progressを変更しない。
- `/v2/goal` と `/v2/skill-status` でviewを取得。`/v2/skill-status` はactionを返さず、既存Skill IDの確認に使える。
- `ActionBridge.consumeSkill` → `finishSkill` が、検証済みterminalを固定文で直接表示し、ローカル実行状態を解放する。
- 現行 `collect_block` 表示は acquired/requested/mined と失敗時のcomplete=falseを扱う。その他Skillの取消表示はverbose依存の箇所がある。
- `closeSkill` / cancel handshakeでは現在Skillへの参照を捨てる。旧SkillがDaemonで `cancelling` を経て確定した結果を、通常の新goal pollだけで必ず取得できるわけではない。
- `failSkill` のローカル通信失敗とSkill自身のterminalは別。ローカル停止理由を架空のterminal resultに変換しない。

したがって `finishSkill` の固定文をLLM呼び出しに置き換えるだけでは不十分。**旧Skillの精算後通知を回収する経路と、実行状態から独立した配送台帳が必要**。

READMEとcollect-block仕様には0.0.22の実装・検証記録がある一方、調査時のHANDOFF冒頭には「collect_block未実装」が残っていた。現状判断はコードを優先する。本書作成時にはテストや実ゲームを再実行していない。

## 3. 責務と処理順

```text
Primitive receipt
  → Skill policy（retry / alternate target / settlement）
  → _finalize：immutable result確定、gameplay終了
  → TerminalEventStore：最小のsnapshotを登録（LLM・HTTPなし）
  → Forge：通知取得、identity検証、ローカル配送台帳へ保存
  → ConversationQueue：通常会話と共有FIFO
  → SocialBrain.present_terminal：同じpersona / history / provider
  → 検証済みvariant ID → 共通renderer → Forgeで一度だけ表示
  → delivery ACK：表示済みを記録、会話履歴へ追記
```

Daemon単独でproviderを呼ばない。そうするとForgeの会話順序・退出・表示成否を把握できず、既存Social lockとも競合するため。Forgeから生receiptや自己申告の数値をSocialへ送り返す方式にもせず、**表示依頼はterminal identityを参照し、Daemon自身のsnapshotを読む**。

Forgeはworld上の操作と表示のauthority、Skillは終端結果のauthority、Socialは表現選択のみ。Skill lock内ではdeep copyと短いメモリ登録だけを行い、provider・履歴lock・通信を待たない。Social失敗でSkill再実行・action再claim・rollback・terminal修正を行わない。

## 4. Terminal eventのschema

イベントは `_finalize` が初めて確定した時点で一度だけ作る。上書き不可。wire名は既存v2のcamelCaseに合わせる。

```json
{
  "version": 2,
  "category": "skill_terminal",
  "terminalId": "opaque-uuid",
  "eventSequence": 17,
  "daemonEpoch": "opaque-epoch",
  "session": "execution-session",
  "goalRevision": 42,
  "skillInstanceId": "opaque-skill-id",
  "type": "collect_block",
  "status": "failed",
  "reason": "drop_unavailable",
  "target": {"block": "minecraft:log"},
  "progress": {"requested": 5, "acquired": 2, "mined": 3, "complete": true}
}
```

- identityは `(daemonEpoch, session, skillInstanceId, terminalId)`。goalRevisionは照合用に保持するが、終端通知を現active revisionと比較して破棄しない。
- `eventSequence` はepoch/session内の単調増加整数。終端確定順に一度だけ採番し、配送済み範囲の管理に用いる。goalRevisionやactionSequenceとは別。
- `type / target` は当該Skillの定義、`status / reason / progress` は**result内の確定値**からcopyする。外側の進行中progressや現在の所持品で上書きしない。
- progressは現行typed unionを維持する。collect_drop = requested/acquired/complete、mine = requested/mined/complete、collect_block = requested/acquired/mined/complete。存在しない値を0補完しない。
- カウンタはboolを除く非負整数、requestedの上限・completed時の成立条件は現行SkillProtocolを再利用。`complete` は厳密なbool。`complete=false` は精算未確定がある意味であり、Skillがrunningという意味ではない。
- eventにactionId、targetRef、経路、座標、inventory全量、Primitive履歴、Tactical projectionを含めない。categoryは将来拡張用だがMVPはskill_terminalのみ受理。
- 不正な既知schemaや同一identityで内容が異なるpayloadは拒否。未知Skill/未知reasonは自然文の解釈をせず、検証できる汎用情報だけ固定表示する。未対応progressは既知の値として推測しない。

### 4.1 表示descriptor

Skill定義に近い場所へ、静的な `PresentationDescriptor` 登録表を設ける。Socialコードへ `if skill == collect_block ...` を増殖させない。

| Skill | 成果metric | その他表示metric | requestedの単位 |
| --- | --- | --- | --- |
| collect_drop | acquired：回収・個 | なし | 個 |
| mine | mined：採掘・ブロック | なし | ブロック |
| collect_block | acquired：回収・個 | mined：採掘・ブロック | 個 |

descriptorはtask label、target表示名、metricのラベル・単位・表示順、requestに対応する成果metricを定義する。値や成功条件は定義しない。表示名はregistry allowlistのローカライズ、未知なら検証済みregistry名。任意のitem名/NBTをprompt命令として扱わない。

`mined=3` は**3ブロック破壊**。木を3本伐採した意味ではない。「3本切った」は今回の入力から生成しない。acquiredは累積取得実績であり「今も持っている」とは言わない。

## 5. 表現生成とreason semantics

共通rendererがevent + descriptor + reason辞書から最大3候補（親しみのある表現／落ち着いた表現／簡潔な表現）を作る。各候補は同じ事実を全部含め、512 UTF-16単位以内。収まらない場合は短い固定fallbackだけを使い、数値や未確定注記を切らない。

同じSocialProviderのHTTP実装へ、内部用のschema付き生成メソッドを追加する。通常chatのinterface互換を維持し、通知専用の独立HTTPクライアントは作らない。出力は例えば厳密に `{"variantId":"friendly"}` のみ。enumはその呼び出しで提示した候補ID、additionalProperties=false、文字列・既知IDを検査する。自由文、reply、intent、action、追加キーを受け取ったらfallback。

LLM入力は共通persona、予算内の既存履歴、最小のterminal event、reasonの短い説明、生成済み候補だけ。transportのsession/epoch/UUIDはLLMに不要なので除外する。目的は会話に合う候補の選択であり、retry・今後の作業・原因推測を要求しない。

personaの文体部分を共通利用し、通常chatの「世界を観測していない」規則は通常chat用の入力契約へ分離する。通知には「このtyped terminalだけが確定事実。現在の世界状態ではなく終了済み作業の記録」を上位の入力契約として加える。ユーザー設定personaも事実制約を変更できない。候補にない独自の口癖などはMVPでは表現できない。

| reason | 許される意味／禁止する推測 |
| --- | --- |
| completed | このSkillの条件を満たして終了。現在の所持数や別の依頼の完了に拡張しない |
| no_block_in_range / no_item_in_range | 今回確認した範囲で対象を確認できなかった。世界全体に存在しないとは言わない |
| drop_unavailable | 回収待ちの範囲・期間で回収対象を確認できなかった。dropしなかった、消えた、盗まれたとは断定しない |
| blocked | 操作が遮られて完了できなかった。遮蔽物の種類や回避不能を推測しない |
| path_not_found | 今回は到達経路を確保できなかった。永久に到達不能とは言わない |
| inventory_full | 回収先の収納が足りず完了できなかった。プレイヤーの持ち物へ帰属させない |
| tool_unavailable | 必要な採掘条件を満たす道具を利用できなかった。道具が一切ないとは言わない |
| unsafe_state | 安全条件を満たさず終了。敵・溶岩・負傷など具体的原因を作らない |
| owner_unavailable / companion_unavailable | 必要なowner / Companionを利用できなかった。死亡・退出など原因を断定しない |
| stale_state | 新しい状態を確認できず終了。世界で何が起きたか推測しない |
| expired | 実行期限内に完了できなかった。単なるSocial timeoutとは別 |
| disconnected | 実行側の接続が失われ終了。provider障害とは別 |
| stopped | 停止による取消。成果は確定値だけ表示 |
| replaced | 別の指示への切替で当該作業を取消。新しい指示が成功したとは言わない |
| retry_exhausted | 試行上限に達し完了できなかった。試行回数は入力にない限り言わない |
| action_failed | 操作を完了できなかった。根本原因は不明 |
| 未知reason | 「終了理由: enum」の固定表示。意味をLLMに推測させない |

statusとreasonは両方保持する。理由だけから成功／失敗／取消を組み替えない。failedでも部分成果は表示する。`complete=false` は**全statusの全候補・fallback**に「確定分」「未確定の操作があります」を付ける。requested-acquiredを「失った数」として計算しない。

## 6. 配送APIと終端保存

既存のexecution APIを変質させず、capability `skill_terminal_social_v1` と次のPOSTを追加する。既存のJSON/body制限・loopback・例外応答規約を再利用し、未知キーも検証する。

### 6.1 `/v2/terminal-events`

request: `version, daemonEpoch, session`。response: 同じbindingと `events: [TerminalEvent]`（最大8件、確定順）、`hasMore: bool, overflow: bool`。

TerminalEventStoreはSkillの `other` と独立したsnapshot outbox。旧revision・取消中から後で確定したSkillも含む。terminal生成だけでなく未ACKのイベントを再取得できる。readでactionを発行・claim・lease更新しない。既存Skill TTLを通知待ちで延長しない。新経路では `finishSkill` が実行状態を解放しても最終表示はoutbox受信まで待つ。view経由で別identityの通知を作らない。旧Skill viewの厳密parserは変更不要。

Forgeはexecution接続がある間、active Skillがなくても1秒ごとを目安に取得する。CONTROL laneの既存制御要求を優先し、同時pollは1件。通信はgame thread外、callback適用はgame thread。受け取った8件を保存してから次を取る。反復取得はidentityで重複排除する。先頭8件の未ACKで後続取得が詰まらないよう、requestには任意の `afterSequence` を許可する。これは当該Forgeがsnapshotを保存済みの範囲だけを飛ばす取得cursorで、配送ACKではない。再接続時の再取得では0から始められる。

### 6.2 `/v2/social/skill-terminal`

request: `version, daemonEpoch, session, skillInstanceId, terminalId, conversationSession, player, deliveryId`。deliveryIdはForgeが当該イベントに一度だけ採番し、再取得で変えない。progress・reason・personaはrequestに含めない。

response: 同じidentity/bindingと `say, variantId, mode`。modeは `social / fallback`、variantIdは候補IDまたは `fallback`。actions/intentフィールド自体を持たせない。Daemonはsnapshotを参照し、Forgeも保存済みeventから同じ候補を再現してsayの一致を検証する。Python/Java共通fixtureで候補・fallbackの一致を担保する。

Daemonのpresentation台帳はidentityごとに `not_started / generating / ready / delivered / suppressed`。同じrequestは生成済み回答を返すかgeneratingを返し、二重にproviderを呼ばない。別player/session/deliveryIdへの再bindingは409。同じSkillのterminalId違いも衝突扱い。

### 6.3 `/v2/social/terminal-delivery`

request: 上記bindingと `outcome: displayed | suppressed`、displayed時だけ `variantId`。自由文sayをクライアントから履歴へ登録しない。Daemonは候補または固定fallbackを再構築する。

同一ACKはidempotent。違うoutcome/variantへの変更は409。displayedでoutboxを配送済みにし、履歴へ一度だけ登録。suppressedは会話履歴に追加せずoutboxを閉じる。provider結果が後から到着してもdelivered/suppressedを上書きしない。

### 6.4 保存上限と保証の範囲

- snapshot/presentation台帳は合計最大100件・600秒を初期上限とし、表示済みの本文は早期解放可能。dedupe tombstoneはそのepoch/sessionが生きている間、最新の配送順序と有界な未処理ID集合で保持する。単なるLRU削除で古いeventを再生成させない。
- Daemon・Forgeとも配送済みsequenceの連続範囲と未完了gapを管理する。順序を飛ばしたfallbackがあるため、単純な最大sequence以下の全破棄にはしない。失効したgapは明示的に閉じる。破棄済み範囲への再依頼は410にし、terminalIdを再発行しない。上限超過・TTL失効はoverflowをstickyに記録し、Forgeに「一部の終了結果を取得できませんでした」と一度通知する。Skillの開始や終了は止めない。
- **正常にsnapshotを受け取った接続中のsessionでは、provider障害でも一度だけ結果を表示する。** Daemon/ゲームのプロセスクラッシュをまたぐexactly-onceや無制限の切断期間中の配送は保証しない。ACK前のクラッシュには分散配送上の限界があることを明記する。
- endpointがunknown terminal/再起動を返しても、Forgeが検証済みsnapshotを持つ場合はそれでfallback可能。snapshot未取得なら結果を捏造せず同期エラーを通知する。

## 7. 通常会話とのqueue・表示順

`ConversationQueue` を `USER_CHAT / SKILL_TERMINAL` のtyped entryへ拡張する。terminalを偽の `!agent chat <JSON>` にしない。通常chatの待機4件・文字数制限は維持し、terminal待機枠を別に8件確保。両種を一つのenqueue順序で処理する。

- すでに生成中のchatは先に終了する。入った順に後続chat・terminalを処理する。通知を理由に通常chatを取り消さない。
- terminal用LLMは最大8秒（既存provider timeoutとの小さい方）、通知をForgeで最初に受けてから表示まで最大12秒。予算超過、queue満杯、executor拒否、busy、schema不正、通信失敗なら固定fallback。
- 待機中のterminalが12秒に達した場合はFIFOの例外として即fallback表示し、LLM候補から外す。先行する長いchatの終了を待たせない。この例外の表示順をテストする。
- 表示済みterminalの遅着応答は破棄。workerがまだHTTP中ならinFlightを早期解放して別provider呼び出しを重ねない。ソケット上限は通知専用に短くし、Python側も8秒の絶対deadlineを持つ（response読み取りを含む）。
- `!agent stop` / 即時停止は従来どおりqueueを通らず実行する。「停止を受け付けた」という即時応答は終端結果とは別で、未精算の成果数を述べない。
- `finishSkill` は検証済みterminal受信後すぐ実行状態を解放。表示用snapshotは独立したoutbox取得で回収する。通常時の固定terminal表示を削除し、通知dispatcherだけが最終結果を表示する。verbose=falseでも最終結果は表示する。

## 8. generation・fencing・取消

Forgeに `TerminalDeliveryState` を置き、受信・queued・inFlight・displayed・suppressedを管理する。状態遷移と実表示の判定はgame threadに集約し、同じtickでtimeoutとHTTP完了が来てもfirst-winsで一回だけ表示する。

| 境界 | 方針 |
| --- | --- |
| terminal pollの再配送 | identity一致なら既存entryを使い、再enqueue・再生成・再表示しない |
| 新Skill/手動操作でaction generation変更 | 旧terminalは有効な過去の事実。新Skillとして扱わず「前の原木回収: …」のようにtask labelを付けて表示。action fenceと独立 |
| 通常会話が追加される | terminalは無効化しない。terminalにIntentOrder ticketは不要で、acceptIntentを呼ばない |
| 旧Skillがcancelling | result確定前は発話なし。outboxへ確定後に登録し、旧Skill参照がForgeから消えていても回収 |
| 同じ世界で `!agent forget` | 受信済み・未表示terminalは旧会話の固定fallbackで先に表示し、旧会話を破棄。未受信terminalは事実だけを新会話へ届ける。旧provider回答を新会話へ移さない |
| world/owner/接続の変更 | 旧contextの表示をsuppressedにする。新world/playerに持ち越さない。可能なACKはbest effort。旧callbackが新contextの台帳を触らない |
| Daemon epoch変更 | 旧snapshotを持ち同じworld/ownerなら旧作業と明示したfallbackだけ許可。新epochへ再生成を依頼しない |

bindingはForge側のworld/server identity、owner、execution session、conversation session/epochを保持する。Skillをtyped commandだけで開始したケースでも、terminal enqueue時にPingBridgeの会話contextを初期化できるようにする。現行の「onChatがownerを設定する」前提を残すと通知できない。

provider応答の適用時はdeliveryId・terminal identity・会話epoch・owner/world・未表示状態を全部照合する。現在Skillのtype/targetから文章を組み直さない。response内の一致していないbindingは固定fallbackにする。

## 9. Fallback

固定rendererはLLM不要で、Forgeにも同じ規則を実装する。Daemonに到達できない場合も取得済みsnapshotから表示できるようにする。例:

```text
原木回収: 完了。原木を5/5個回収、採掘3ブロック。
原木回収: 失敗[drop_unavailable]。回収2/5個、採掘3ブロック。確認した範囲で回収対象を見つけられませんでした。
原木回収: 取消[stopped]。回収2/5個、採掘1ブロック。
原木回収: 取消[replaced]。確定分は回収2/5個、採掘1ブロック。未確定の操作があります。
```

自然な候補では「原木、5個集めたよ。依頼の5個に届いた。採掘は3ブロックだった。」など、意味を保った表現にできる。失敗／取消は文体を変えても必ず明示する。

Social providerの未設定、停止、HTTP例外、timeout、出力不正、未知variant、予算不足、busy、queue/executor拒否の全てをfallback経路に集約する。失敗時にSocial生成を自動再試行せず、Skillも再実行しない。ACK再送は生成再試行ではない。

### Forge配送ACKの保証範囲（実装済み）

Forgeのpending delivery ACKは**有界（現在32件）**。SOCIAL executorに未受理のACKはこの範囲で保持し、通常のSocial生成より先に1件ずつ再試行する（executor rejectで即座に消える問題は解消）。ただしpending queue自体が32件上限であり、32件を超える異常backlogでは新しいACKをdropして警告ログを出す。その結果、**表示済みterminalのhistory登録・outbox closeは欠落し得る（best-effort）**。表示済みチャットは巻き戻さず、Skillを再実行せず、terminalを再表示しない。保証範囲は「通常の有界backlog内=表示→ACK再送→history/outbox close」「32件超の異常backlog=表示は維持、ACK/history/outbox closeはbest-effort」。

ログはidentity、status/reason、mode、latency、fallback causeだけを基本とし、会話全文・persona・APIキーを記録しない。provider障害の説明を通常の結果発話へ毎回混ぜない。

## 10. 会話履歴と予算

**実表示されたterminal発話だけを、delivery ACKで既存会話履歴へ一度記録する。** 生成段階で履歴を更新しない。固定fallbackも同じ扱い。timeout後に捨てた生成文は残さない。

現行ContextBudgetはuser/assistantのペアを要求するため、履歴内部には次のペアを追加する。

```text
user: [内部イベント skill_terminal] <最小の確定事実。ユーザー発言ではない>
assistant: <実表示した候補またはfallback>
```

systemで内部イベントの区別を説明し、通常chatのintent対象は**最後の実ユーザー入力だけ**とする。terminal履歴からfollow/stop等の新intentを再生しない。UUIDなどtransport情報は履歴に不要。新しい独立履歴や第二のmemoryを作らない。

ACKはSocial laneで次のchat生成より先に処理し、SocialBrainの同じlockでappend/trimする。既存の最大32 session、history/prompt/output budget、往復単位trimを適用。新規system/schema/candidatesもtoken予算に含め、必須事実が収まらなければLLMを呼ばずfallback。

ACKは短い通信期限（接続2秒・全体3秒）で最大2回、同じbindingで再送。届かなくても表示やSkillを巻き戻さず、履歴未登録としてログを残して次のchatを通す。後から古いACKを新しいchatの後ろに挿入しない。通常の通信では次の会話から成果を参照できるが、ACK障害・履歴trim・forget・再起動後の記憶保持は保証しない。

Social lockをprovider呼び出しが保持している間に、ACKをgame threadで待たない。forgetと遅着ACKの順序は会話epochで検証し、forget後に古い履歴を復活させない。通常chatの既存履歴commit方式の全面変更は今回行わない。

forgetの受理時に旧conversationSessionをretiredとして記録し、履歴削除がbusyでも通知生成・ACKによる再作成を拒否する。通常chatの遅着commitにもretiredチェックを追加する。履歴削除の再試行だけでは復活防止にならない。retired記録はsession有効期間中保持する有界台帳に置き、追い出す場合はそのsessionの以後の要求も失効扱いにする。

Socialに追加するゲーム情報はこのterminal projectionだけ。Tactical providerへ会話履歴・persona・通知文を流さず、private/public projectionの境界も拡大しない。

## 11. 実装箇所

| ファイル | 変更 |
| --- | --- |
| `agent/src/skills.py` | `_finalize`から immutable snapshot発行。provider非依存、既存の終端・精算不変性維持 |
| `agent/src/skill_protocol.py` | capability、終端event/新APIの厳密検証。既存result schemaは維持 |
| 新 `agent/src/terminal_events.py` | 有界outbox、identity、配送sequence、dedupe、binding、ACK状態 |
| 新 `agent/src/terminal_presentation.py` | descriptor、reason辞書、projection、候補/fallbackの純粋renderer |
| `agent/src/social.py` | 同じproviderで候補選択、schema/deadline指定、通知用Brain入口、実表示履歴登録、persona入力契約分離 |
| `agent/src/daemon.py` | 3 endpoint、既存エラー/容量規約。skills lockを解放してからSocialへ入る |
| `forge-mod/.../ActionBridge.java` | 固定終端表示からdispatcherへ移行、実行状態は直ちに解放 |
| `forge-mod/.../SkillProtocol.java` / `DaemonClient.java` | 新capability/event取得・検証。旧版では現行固定表示を維持 |
| 新 `forge-mod/.../TerminalDeliveryState.java` | 有界台帳、独立fence、期限・一回表示・ACK状態。Minecraft非依存 |
| 新 `forge-mod/.../TerminalPresentation.java` | Java側固定rendererと候補の一致検査。Pythonと共通fixture |
| `forge-mod/.../ConversationQueue.java` | typed entry・共通順序・種別ごとの容量 |
| `forge-mod/.../PingBridge.java` / `PingClient.java` | 通知enqueue、同一SOCIAL worker、typed command時context初期化、表示/ACKとhistory順序 |
| bridgeを組み立てる既存箇所 | terminal dispatcherを接続。新Threadや第二のSocial executorを追加しない |
| Python/Java tests、README、HANDOFF | 異常系・互換性・確認手順、導入済み範囲の更新 |

`forge-mod/...` は `forge-mod/src/main/java/local/mcai/` の略。新規ファイル名は上記を推奨するが、既存の純粋状態クラスに無理なく統合できる場合は統合可。

## 12. 段階的な実装順

1. snapshot schema、descriptor、reason辞書、候補/fallbackの共通fixtureを作る。既存result不変条件を流用。
2. Daemonのoutbox・取得・ACK・dedupeを実装。LLMなしで正常終了とcancel/replaceの後着終端を検証。
3. Forgeの独立配送台帳を実装。capabilityがある時だけ新経路を使い、固定fallbackで一回表示を成立させる。旧版Daemonでは旧経路を維持し二重表示しない。
4. typed ConversationQueue、通常会話との順序、owner初期化、forget/退出/timeout fenceを実装。
5. 既存providerへ候補選択を追加。LLMを無効にしたままでも同じSkill動作・成果表示が維持されることを確認。
6. delivery ACKで履歴登録を接続し、予算・busy・遅着・forget競合を検証。
7. 自動テストを完了してからbuild、ゲームを終了した状態でjar更新。実ゲーム確認後にREADME/HANDOFFへ実際の検証結果を書く。

## 13. 必須自動テスト

### Python

- 3 Skill × completed/failed/cancelledのprojection。存在しないmetricを追加しない。生成後に元Skillや遅着receiptを変更してもsnapshot不変。
- collect_blockでPrimitive blocked後に別候補へ進む間はeventなし、最終確定時だけ1件。cancel grace中もeventなし、確定後に1件。
- result.progressをauthorityにする。invalid status、bool count、negative、未知キー、外側progress不一致、identity内容衝突を拒否。
- acquired=2/requested=5/mined=3、complete=falseの全候補に数値・単位・未確定注記。採掘数を木の本数、未回収数を紛失数にしない。
- reason辞書の全enumと未知reason固定表示。no_block_in_range/drop_unavailableに全世界・drop不存在の断定がない。
- duplicate poll/request/ACKでprovider呼び出し・履歴追記は各1回。generating中重複、別binding、遅着回答対fallback ACK、TTL/overflow/epochも検証。
- provider timeout/HTTP failure/busy/未設定/余分なキー/自由文/未知variant/予算不足で固定fallback。これらでSkillManagerのgoal/result/action stateが変化しない。
- personaと履歴を再利用し、inputにPrimitive log・targetRef・Tactical/private情報がない。通知出力にintent/actionなし。
- 実表示ACK前は履歴変更なし。displayedのみ1ペア、suppressed/遅着/forget済みは追加なし。trimはペア単位、通常chat intentは直近実ユーザーだけを対象。
- 新endpointのbody上限・型・unknown identity・別session・旧epoch・別playerを拒否。read-onlyイベント取得でaction/leaseが変わらない。

### Java

- 受信snapshotの厳密検証、binding不一致、同identity異内容、旧capabilityの固定表示互換。
- 純粋な配送状態クラスにfake clock/HTTP completionを与え、duplicate poll・timeoutとresponse同tick・executor拒否・late replyの表示回数が1。
- 先行chat→terminal→後続chatのFIFO、12秒fallback例外、chat容量とterminal容量、queue overflow後の重複受信。
- 新Skill開始後も旧結果は旧label、旧数字のまま一回表示。SocialからacceptIntent/goal/claimを呼ばない。
- typed commandだけで開始しonChat未実行でも通知可能。verbose=falseでも取消結果が見える。
- forgetでは旧pendingがfallback一回、provider遅着を破棄。退出・owner変更では新worldへ漏れない。
- delivery ACKを次chatより先に送信。ACK再送、最終失敗後の継続、forget後の遅着を検証。
- Python/Java共通JSON fixtureで候補/fallback全variantの文字列一致・512 UTF-16制限を確認。

既存Skill/receipt/queue/intent/IoExecutorsの回帰テストも実行する。ActionBridge本体のMinecraft依存を理由に状態遷移のテストを省略せず、配送部分を純粋クラスへ切り出す。

## 14. 実ゲーム確認

1. 先にchatを送らずcollect_blockを開始。成功結果に正しい回収数・採掘ブロック数が一度だけ出る。
2. collect_drop/mineでも同じ機構で単位が正しく、既存の行動は変わらない。
3. 回収部分成功後のdrop_unavailable、必要道具不足、範囲内に候補なし。できた成果とできなかった理由を分けて表示する。
4. blockedから別候補へ迂回した場合、途中で失敗発話せず最終結果だけ表示する。
5. 回収途中にstop、別goalへreplace。取消済み成果を保持し、新Skillの結果と取り違えない。
6. slow provider中も移動・即時stopが動く。先行chatが長い場合も12秒で固定結果が表示され、後から二重発話しない。
7. LM Studio停止・不正出力・小さいprompt budgetでfallback。結果が消えず、Skillが再開しない。
8. 終端待ちにforget、退出・再入場、Daemon再起動。旧会話や旧worldの発話が新contextへ漏れない。
9. 表示後に「何個集めた？」。通常のACK成功時は履歴の成果を参照する。現在所持数と混同しない。
10. verbose offでも成功・失敗・取消が表示される。未確定操作は実機で再現困難ならfake clock/receipt欠落テストで補い、実機確認済みと混同しない。

## 15. 将来Skillの追加手順と対象外

新Skillはまず自分のpolicyでtyped terminal resultを確定する。次にresult validatorと静的descriptorへ成果metric・単位・target labelを登録し、新reasonがあれば意味と禁止推論をreason辞書へ追加する。最後にprojection/rendererのfixtureを追加する。**通知transport、queue、provider、dedupe、historyにSkill専用分岐を追加しない**。

attack/place/craft/smeltは、この手順で `defeated / placed / crafted / smelted` 等の確定metricを登録できる。将来のtyped detailはSkillが明示した要約のみ、schema版を上げて追加する。SocialがPrimitive logから説明を組み立てる構造にはしない。

対象外: 自然文からのSkill選択/JEV、Planner、Primitive選択、retry policy変更、world authority変更、新Primitive、上記将来Skill自体、途中経過の発話、全Primitive履歴投入、cloud routing、プロセスクラッシュをまたぐ永続配送、自由文の意味同一性を判定する第二のLLM。

実装前に利用者の判断を必須とする未決事項はない。MVPでは本書の一案で進める。自然な表現の自由度を広げる場合は、事実不変をどう検証するかと合わせて後続で扱う。
