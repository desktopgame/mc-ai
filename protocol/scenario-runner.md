# Headless protocol scenario runner — 設計・導入手順

状態: **fixtureとC1を実装済み**（2026-09-27）。設計調査基点 `79f0998`、実装作業基点 `c07eae2`。
実装は `agent/tests/scenario_support/`、C1は `agent/tests/scenarios/collect-drop-c1.json`、入口は `agent/tests/test_scenarios.py`。
DSLはhttp / receipt / advance / tick / restartの5命令。C2～C5、Java contract replay、専用CLIは未実装。

```powershell
python -m unittest discover -s agent/tests -p test_scenarios.py -v
```

C1は19 stepを新しいfixtureで3回繰り返す。開始所持品3→5でも成果2、重複/矛盾receipt、終端イベントの一意性、固定presentation、ACK再送とoutbox解放を検証する。providerは未設定なので会話履歴登録はこのC1では検証しない。
補助テストは時計配線・終了処理・5命令・期待値不一致の検出・型/参照・発行時receipt bindingを確認する。
2026-09-27確認: scenario用6テスト成功、通常discoverは167件成功。全体テストの初回には既存 `test_wrong_route_and_content_type` がWinError 10053で失敗し、再実行で成功した（既知のKI-10）。C1は両実行で成功。本番のHTTPエラー処理は今回変更していない。
結果はGit管理外の `.tools/scenario-results/` に保存する。期待値を故意に誤らせる自己テストのfailed記録も含む。
本番DaemonやMinecraftは起動・変更せず、テスト専用の空きportでHTTP serverを起動する。以下は実装範囲を超える後続計画も含む。

## 1. 目的と検証範囲

**偽Forgeクライアントから実際のDaemon HTTP handlerへ、snapshot・delta・goal・action receiptを送り、観測からSkill終端までを通すPython製runnerを最小構成とする。** 偽worldは入力データと予定した操作結果だけを持つ。空間シミュレーターは作らない。

これは「Forgeの通信相手を代替した、Daemonのprotocol/Skill lifecycle E2E」である。実際の `ActionBridge / PingBridge / CompanionEntity` が動くMinecraft E2Eではない。Pythonだけの成功をもってJavaクライアントの正常動作とは判定しない。

第二段階で実通信の記録を既存Javaのparser・純粋状態クラスへ渡すcontract replayを追加し、言語間の不一致を検出する。最初からMinecraftクラスの大量stubやJava側bridgeの再実装はしない。

| 検証できる | 検証しない |
| --- | --- |
| HTTP経路・status・JSON schema・binding | Minecraft physics、移動速度、ジャンプ、水泳 |
| snapshot/delta同期・連番・原子的適用 | pathfinding、collision、stand position、到達可能性 |
| candidate選択・Skillの段階遷移・retry | Forgeが正しいworldから観測を作ったか |
| receipt精算・重複・取消・旧世代の隔離 | 実収納・実破壊・drop生成・tool/LoSの実判定 |
| 仮想時刻による期限・lease・観測freshness | 実ゲームtick停止、OS通信障害の完全再現 |
| terminal outbox・固定presentation・ACK | HUD表示・チャット実表示・実LLMの自然さ |

[既知問題](../known_issue.md) KI-11～14の段差・回り込み・高所・川渡りは別の検証対象。runnerに `blocked/path_not_found` を注入してその後のSkill動作は確認できるが、当該navigation不具合が直ったとは言えない。

## 2. 現行実装を利用できる箇所

- `agent/src/daemon.py:Handler` はv1観測、v2 Skill、terminal APIを処理する。`main()` はHTTP serverと0.1秒周期のSkill tickerを組み立てている。
- `StateCache / ExecutionRegistry / SkillManager / TerminalEventStore` は `clock` 注入に対応する。`GoalManager` も時計を受けるが、MVPではv1 Tactical goalを対象にしない。
- `agent/tests/test_skills.py` に実 `ThreadingHTTPServer(("127.0.0.1", 0), Handler)` を使う小さなv2 lifecycleテストがある。これを構成の出発点とする。ただしそのテストは初期観測を直接cacheへ入れるため、runnerでは観測もHTTP経由にする。
- `test_collect_block.py` は採掘→drop待ち→回収、deadline、混合receiptを直接managerで検証する。既存unit testを削除・置換せず、複数endpoint間の順序をscenarioで補う。
- `test_terminal.py` はsnapshot/outbox/ACKの検証を持つ。runnerの固定presentationモードではproviderを使わず、既存のfallback経路を使える。
- Forgeの `SkillProtocol / SkillExecutionState / SkillRequestFence / TerminalPresentation / TerminalDeliveryState / ObservationDiff` 等にはMinecraft非依存部分がある。Java contract replayの対象にできる。
- 最新Socialには `collect_block_log` intentとcountがあるが、今回のMVPはtyped goalから開始する。会話→Skill選択・実LLMは含めない。

### 現行wireで間違えやすい点

1. 観測は `/v1/snapshot`・`/v1/events`、Skillは `/v2/...`。MOD版とwire versionは別。
2. open前にfresh snapshotが必要。v2 execution openの応答から実 `daemonEpoch` を取得する。
3. v2 goalはtyped objectまたはnull。legacy文字列goalは拒否される。
4. pickup receiptは `acquired: {item,count}`、mine receiptは `destroyed: {block,count}`。単一のcountだけには平坦化しない。
5. actionにはgoalRevisionが入っていない。**発行時の外側viewのrevisionとaction全体を一緒に保存**する。取消・置換後の「現在revision」で旧receiptを作らない。
6. 同じgoal/revisionのPOSTが制御pollになる。`/v2/skill-status` はactionを返さず、制御leaseの更新代わりにもならない。
7. running/accepted receipt後は同じactionがviewに再掲載されない。captureしたdescriptorを保持する。
8. `goal:null` のidle応答は旧Skillの精算完了を意味しない。旧skillInstanceIdでstatus/outboxを追う。
9. `inventory_changed` は成果receiptではない。所持品増加だけでSkillのacquiredを増やしてはいけない。

## 3. 最小構成と責務

```text
scenario JSON
  → Runner（順序、capture、assert、仮想時刻）
  → ScriptedForgePeer（HTTP client、発行済みdescriptorの保存）
  → localhost HTTP
  → 実Handler → 実StateCache / Registry / SkillManager / TerminalEventStore
  → HTTP response → assert / transcript
```

### TestDaemonFixture

scenarioごとに新しい本物の各serviceと、空きportのHTTP serverを同一Pythonプロセス内に作る。既存アプリへ接続しない。`server.states / registry / skills / terminals / goals / brain / decisions / shutdown_token` を本番と同じ依存関係で設定する。MVPは `brain=None, decisions=None`、Skill managerにはterminal_storeを渡す。

serverは別threadで `serve_forever`。scenario実行はHTTP応答を最後まで受信してから次へ進む。仮想時計更新と `skills.tick()` はfixtureの制御口からのみ行う。これは本番schedulerの代役であり、private methodやSkill状態を直接変更する口ではない。

最初はtest用fixtureだけで構成し、本番mainへテスト専用endpoint・clock引数・バックドアを足さない。mainとの組み立て乖離が問題になった時だけ、同じデフォルト動作を保つservice factory抽出を別変更で行う。

### ScriptedForgePeer

HTTPの送受信、観測連番の提案、binding保存、receipt envelopeの組み立てを担当する。actionを受けても**自動成功させない**。全操作結果と観測変化をscenario側で明記する。

採掘成功からdropを自動生成しない。pickup成功からinventoryを自動変更しない。Forgeで起きる事実を二重にモデル化せず、成功receiptと次の観測を独立した入力にする。矛盾する入力も負例として送れるようにする。

同一actionIdの再配送を新実行と数えない。peer側の「実行回数」はscenarioがactionを開始した回数であり、実際のworld mutation回数ではない。ログの表記も `scripted execution` とする。

### Runner / assertion

期待値はHTTP responseとscenarioの固定値から判定する。managerの `_find`、active/other、private stage、直接cache参照を成功判定に使わない。公開されないstageは次に返るaction/no-action、progress、terminalで観測する。

本番rendererや同じvalidatorで期待結果を丸ごと生成して自己一致を見るテストにしない。固定のstatus/reason/count、identityの対応、明示的な受信順をassertする。

## 4. 時計と順序

fixture全体に1つのManualClockを注入する。整数ミリ秒を保存し、既存serviceへ秒を返す。wall-clockのsleepで6秒・40秒・120秒を待たない。HTTP timeoutとfixture終了待ちには実時間の上限を設ける。

MVPの `advance` は **時計を指定量進めてから `SkillManager.tick()` を1回呼ぶ**。通常HTTP stepの後に勝手にtick・観測更新・pollを追加しない。`tick` は時刻を変えず1回だけ呼ぶ。時間を戻す操作は禁止。

これにより、同時刻の順序はJSONのstep順だけで決まる。HTTP handlerの処理が終わる前にadvanceしない。本番の背景tickerは起動しない。最終終了時には全request完了・server.shutdown/server_close・thread joinを有界で行う。

現行の主な時間条件:

| 条件 | 現行値 | scenario上の注意 |
| --- | --- | --- |
| control lease | 5秒 | 新action発行を維持したいなら同revisionのgoal pollを明記 |
| drop待ち | 6秒 | mine成功receiptを基準に固定。pollで延長しない |
| cancel grace | 5秒 | 現行判定は `>`。5,001msなど境界を明示 |
| cache stale | 15秒 | 空eventsも連番と更新時刻を進める |
| ready / running待ち | 10秒 / 40秒 | Daemon期限。Forge action.timeoutMs（30秒）と区別 |
| Skill全体 | 120秒 | 他の先行期限で違う終端にならないfixtureを作る |

6秒のdrop待ちを試すのに15秒staleや5秒leaseを混ぜない。例えば3秒進めて空events＋goal poll、さらに3秒進める、とstepを明記する。
ただし「mine後の新観測なし」を試すケースでは空eventsも送らない。空eventsを送るとsequenceが進み、`stale_state` ではなく `drop_unavailable` の条件になる。

初期MVPに自動heartbeat schedulerは入れない。繰り返しが増えた時だけ、間隔・送信順・停止条件を明記する有界pumpを導入する。隠れたheartbeatで不具合を消さない。

## 5. Scenario形式

**UTF-8 JSON**を使う。標準ライブラリだけで読み、YAML依存・任意Python式・eval・埋め込みshellを導入しない。scenarioのversionはwire versionと別に `scenarioVersion: 1`。

最初の命令は5種類に絞る。

| op | 入力と動作 |
| --- | --- |
| http | path/body、期待status、期待JSON pointer、save名。実POSTを1回送る |
| receipt | `from`に保存した発行view名、status/reason/count。descriptorからwire envelopeを作り `/v2/action-result` へ送る |
| advance | ms。仮想時計を進め、tickを1回実行 |
| tick | 同じ時刻でtickを1回実行 |
| restart | fixtureを停止・再生成。旧captureは保持、cache/epoch/Skill/outboxは新規。実Daemonには触れない |

http/receiptの `save` は `{status, body}` をdeep copy保存する。`expect.status` は必須。`expect.json` はJSON Pointer→期待値の完全一致（指定した部分のみ）。null、bool、整数を区別し、path不存在はnullと同一視しない。配列全体の期待値も指定できる。

`{"$ref":"open/body/daemonEpoch"}` は、保存名＋JSON Pointerへの型を保つ参照。文字列内展開や式評価は行わない。未定義参照・save名の上書きはrunnerエラー。期待値も同じ参照を使える。

receiptは元viewのsession/epoch/revision/skillInstanceId/actionId/actionSequenceをコピーし、action.typeに従ってacquiredまたはdestroyedを作る。runningとfailed/cancelledのcount=0、succeededのcount>0はscenarioで指定する。receiptからbindingを推測し直さない。

不正なenvelope、過大count、別item、旧epochなどを試す場合は `http` でそのまま送る。runnerがDaemonのvalidatorを先に呼んで負例を遮断してはいけない。scenario形式の誤りと、protocolが拒否すべき入力を区別する。

### 記述例: collect_dropを1個回収

これはDSLの基本例。実装済みC1は開始所持品と重複配送の検証も含むため、実行するJSONは `agent/tests/scenarios/collect-drop-c1.json` を使う。

```json
{
  "scenarioVersion": 1,
  "name": "collect-drop-one",
  "steps": [
    {
      "op": "http", "path": "/v1/snapshot", "save": "snap",
      "body": {
        "version": 1, "session": "world-a", "sequence": 0,
        "state": {
          "dimension": 0,
          "owner": {"position": [0, 64, 0], "health": 20, "inventory": {}},
          "companion": {"id": "companion-a", "position": [0, 64, 0], "health": 20, "task": "idle", "result": "none", "inventory": {}},
          "hostiles": {}, "blocks": {},
          "items": {"item-a": {"type": "minecraft:log", "distance": 4}}
        }
      },
      "expect": {"status": 200, "json": {"/synced": true, "/sequence": 0}}
    },
    {
      "op": "http", "path": "/v2/execution/open", "save": "open",
      "body": {"version": 2, "session": "world-a"},
      "expect": {"status": 200, "json": {"/version": 2, "/session": "world-a"}}
    },
    {
      "op": "http", "path": "/v2/goal", "save": "g",
      "body": {"version": 2, "session": "world-a", "daemonEpoch": {"$ref": "open/body/daemonEpoch"}, "goalRevision": 1,
        "goal": {"type": "collect_drop", "target": {"item": "minecraft:log"}, "count": 1, "constraints": []}},
      "expect": {"status": 200, "json": {"/status": "running", "/action/type": "pickup_target", "/action/targetRef": "item-a", "/action/maxCount": 1}}
    },
    {
      "op": "receipt", "from": "g", "status": "running", "reason": "accepted", "count": 0,
      "expect": {"status": 200, "json": {"/accepted": true}}
    },
    {
      "op": "http", "path": "/v1/events", "save": "delta",
      "body": {"version": 1, "session": "world-a", "sequence": 1, "events": [
        {"type": "item_left_range", "id": "item-a"},
        {"type": "inventory_changed", "entity": "companion", "added": {"minecraft:log": 1}, "removed": {}}
      ]},
      "expect": {"status": 200, "json": {"/sequence": 1}}
    },
    {
      "op": "receipt", "from": "g", "status": "succeeded", "reason": "completed", "count": 1,
      "expect": {"status": 200, "json": {"/accepted": true}}
    },
    {
      "op": "http", "path": "/v2/skill-status", "save": "end",
      "body": {"version": 2, "session": "world-a", "daemonEpoch": {"$ref": "open/body/daemonEpoch"}, "skillInstanceId": {"$ref": "g/body/skill/skillInstanceId"}},
      "expect": {"status": 200, "json": {"/status": "completed", "/action": null, "/skill/result/progress": {"requested": 1, "acquired": 1, "complete": true}}}
    }
  ]
}
```

`item-a` はschemaを満たすopaque fixture ID。Minecraft entityを生成しない。UUIDやepoch/action IDは応答からcaptureし、固定値として期待しない。
実際の最初のscenarioには、下記C1の重複receipt・terminal取得/ACKまで追加する。

## 6. 最初に自動化する5ケース

### C1: collect_drop成功、重複精算、終端配送

開始時inventoryに原木3個、goalは新規2個。pickupをrunning→succeeded(count=2)、deltaでinventoryを5へ更新し、receiptを同一内容で再送。
期待はrequested=2/acquired=2（5ではない）、completed、action=null。矛盾する再送countは409で成果不変。
terminal-eventsを2回取得して同じterminalId/eventSequenceが1件であることを確認。providerなしのpresent→fallback応答→displayed ACK→同一ACK再送→outbox空を確認する。表示はpeerの記録だけで、ゲームチャットを確認したとは扱わない。

### C2: collect_blockの別候補とmine→pickup連携

原木block A/B、距離2/4、目標回収1。Aにmine_targetが発行されたらrunning→failed/blocked(count=0)を注入。新観測と必要なpollを送り、Bへ移ること・この時点でterminalがないことを確認する。
Bのmine成功(count=1)後もacquired=0で未完了・actionなし。**新sequenceのdeltaでB削除とitem出現を明示して初めて**pickupへ進む。pickup成功1でcompleted、mined=1/acquired=1。
blockedの地形・回り込みは再現せず、失敗後のSkill policyだけを検証する。発行回数はmine 2 / pickup 1、成功破壊数は1。

### C3: 取消・置換と遅着receipt

目標3のcollect_dropで1個回収し、次actionがrunningになったところで新revisionのgoal:nullを送る。idle ACKを旧Skillの完了とは扱わない。
grace内に旧actionのsucceeded(count=1)を送ると旧Skillはcancelled/stopped、acquired=2を一度精算。重複再送でも増えない。
同じfamilyの明示的な別JSONとして、取消を新goalへのreplaceに置き換え、旧成功receiptが新Skillへ加算されないことも確認する。
receipt未着版では5,001msを進めてcancelled/complete=falseを確認。終端後の遅着receiptでresultを変更しない。別ケース間の分岐DSLは作らない。

### C4: drop待ち期限と新観測の有無

collect_blockのmine成功後に、(a) 新sequenceでitemなしの観測を送る、(b) 新観測を一切送らない、の2つの独立scenario。
6秒直前ではrunningで次mineを出さず、期限到達で(a) failed/drop_unavailable、(b) failed/stale_state。双方mined=1/acquired=0を保持。
数回の同revision goal pollを挟んでも期限が延長しないことを確認する。空eventsを送る/送らない差を明示し、必要以上に時間を進めて別のtimeoutを起こさない。

### C5: delta拒否・同期し直し・旧epoch隔離

正常snapshotから、sequence欠落deltaと、同一batch内で後半だけinventory_mismatchになるdeltaを送る。HTTP 409、`/v1/state` のsequenceと全対象フィールドが変更されていないことを確認。正しい連番で再送して同期を回復する。
fixtureをrestartし、新sessionのsnapshot→openを行う。保存済み旧epochのgoal/receiptは409/daemon_restarted。新epochのgoalは動き、新終端eventSequenceはその新sessionで1から始まる。
これはDaemonの拒否・回復契約の検証であり、実際のForgeの自動再同期やterminal cursor resetの検証ではない。

最初の導入はC1だけを通し、C2→C3→C4→C5の順に増やす。「5ケース」は5つのcase familyで、境界・負例は独立した小さなJSONへ分ける。

## 7. Forge側を含める次の段階

Python実行でraw request/responseをJSONLへ保存し、Javaのheadless JUnitが次を検査する。

- `SkillProtocol` で実応答をparseし、`validateBinding / validateGoalBinding` でSkillとactionを照合。
- `SkillExecutionState` で同一actionの二重claim禁止、sequence前進、old receiptの扱いを確認。
- `SkillRequestFence` は別途明示した送信時刻・generation入力で検証。Pythonの仮想時刻を実 `System.nanoTime()` と混ぜない。
- `TerminalPresentation` のfallbackとPython共通fixture、terminal eventのparseを確認。
- `ObservationDiff` が生成するdeltaについてはJava側fixtureをprotocol inputへ戻し、同じDaemon scenarioで受理を確認する。

成功ケースのresponseだけでなく、binding改変・旧generationなどの負例も固定fixtureにする。parserへ合うようにrunner側が応答を整形してはならない。
Java既存のビルド環境は必要だがMinecraftクライアントは起動しない。最小Python runnerの実行にはJDKやForgeの依存取得を要求しない。

この段階でも `PingBridge` のACK pump、`ActionBridge` のcallback順序を実行したことにはならない。そこまで必要になったら両bridgeから配送状態の純粋ロジックを抽出し、同じscenario意図をそちらのテストへ接続する。既知の欠落を偽Forgeに同じように実装して「互換」としない。

## 8. 成果物・失敗時の診断

提案ファイル構成:

```text
agent/tests/scenario_support/fixture.py     # 実Handler、service配線、時計、停止
agent/tests/scenario_support/peer.py        # HTTPとreceipt binding
agent/tests/scenario_support/runner.py      # JSON命令、capture、期待値比較
agent/tests/scenarios/*.json               # C1～C5
agent/tests/test_scenarios.py               # unittestの通常discoverへ接続
scripts/run-scenarios.ps1                  # Python入口への薄いラッパー（任意）
```

テスト用コードをproduction endpointからimportしない。既存test_skillsのprivate helperへ依存せず、必要なfixtureをsupportへ小さく分離する。

出力はscenario名、step番号、仮想時刻、endpoint、期待HTTP/実HTTP、JSON pointer差分、保存済みbinding、直前数件の通信。transcriptには実際に送ったbody、応答body、virtual time、実通信durationを保存する。生成IDはrawのまま保存し、比較時にだけcapture名へ正規化する。

出力先はGit管理外の `.tools/scenario-results/<run-id>/`。`summary.json` と `transcript.jsonl`、失敗時の最小snapshotを残す。ユーザーのlocal config/APIキー/既存会話を読み込まない。CLI exit codeは成功0、assert不一致1、runner形式/起動不良2を推奨。
step数上限1,000、HTTP応答1MiB以内、各HTTP実時間timeout2秒、scenario実時間上限30秒を初期値とする。上限値はtest側設定で明示し、無限pollしない。

## 9. 導入順と完了条件

1. **fixtureだけ作る。** fresh cache、同一clock配線、空きport、本番Handler、背景tickerなし、終了保証をテスト。`main` の本番サービス構成との差もチェック項目にする。
2. **C1を実装。** snapshotからACKまで直接managerを呼ばずHTTPで通す。receipt binding保存を最初に固める。
3. **C2～C4を追加。** deltaとreceiptの独立性、cancel/replace、仮想時間の境界を検証する。故意に誤った期待値を置くrunner自己テストも用意する。
4. **C5と再現ログを追加。** 409後の原子性、restart後の旧epoch拒否、failure reportを確認する。
5. **通常のPythonテストへ組み込む。** 実LLM・ゲーム・既存Daemonなしで全caseを実行できること。動的IDと実時間以外の結果が繰り返し一致すること。
6. **Java contract replayを追加。** 実Daemonが生成したwireを既存Javaコードへ通す。両段階の成功をCIで別名表示する。
7. READMEへ実装済みの起動方法・対象case・未対象の境界を追記する。本書の予定コマンドを実装前に利用可能とは書かない。

将来の実行例（入口名も提案）:

```powershell
python -m unittest discover -s agent/tests -p test_scenarios.py
.\scripts\run-scenarios.ps1 -Scenario collect-block-alternate
.\scripts\forge.ps1 test --offline --tests local.mcai.ScenarioContractTest
```

現時点では先頭のunittest入口のみ実装済み。PowerShellラッパーとJava ScenarioContractTestは存在しない。既存unit testの成功数をscenario runnerの検証実績に数えない。

## 10. 今回追加しないもの

Minecraft entity/worldの模倣、地形グリッド、navigation/jump/water/collision、仮想的な経路探索、drop確率やレシピ、実LLM、自由文Skill選択、GUI、常駐シミュレーター、既存Daemonへの注入モード、productionの時間操作API。

transport故障は最初はreceipt省略・同一body再送・旧binding送信・明示的な順序変更で表す。packet proxyや実thread race schedulerは後続。ACK lossを試す場合も「サーバーへ送って応答を捨てる」と「送信しない」を区別して設計し、両者を同じ失敗として自動retryしない。

この最小構成により、現状の問題を **Daemonのprotocol/Skill、Forge側の配送・純粋ロジック、Minecraft固有の挙動** のどこで検証するか切り分けられる。
