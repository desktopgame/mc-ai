# Minecraft 1.7.10 Local AI Companion — init.md

> この文書は将来構想を含む全体設計です。現行v0.1.0の実装範囲は [README](README.md)、既知の問題は [known_issue.md](known_issue.md) を参照してください。

## このファイルの役割

このファイルは、このリポジトリを実装するAIエージェント向けの初期仕様である。

最初の実装担当は Astra を想定する。  
特に Phase 0 の Minecraft 1.7.10 / Forge 開発環境構築は、古いツールチェーンのため推測で進めず、実際にビルド・起動できる組み合わせを確認して固定すること。

作業ログや試行錯誤の記録はこのファイルへ追記しない。  
`init.md` は「何を作るか」「どこまで作るか」「何をしてはいけないか」を保つ。

---

# 1. 目的

Minecraft Java Edition 1.7.10 上で動作する、ローカルLLMベースの Companion / Agent を作る。

重要なのは「LLMをMinecraft MODへ埋め込む」ことではない。

Minecraft 1.7.10 側は薄いゲームI/Oアダプタとして扱い、以下を外部の Agent Daemon へ分離する。

- LLM provider
- 会話人格
- 長期記憶
- 状態キャッシュ
- 状態差分
- prompt構築
- structured output
- tactical decision
- action validation
- provider credentials

Forge MOD 側は主に以下だけを担当する。

- Minecraft内の状態を観測する
- 必要なイベントを外部へ通知する
- Daemonから受け取った検証済みactionをゲーム内で実行する
- Companion entityの最低限のライフサイクルを持つ

最終的には「会話するAI」と「ゲーム内の具体的な判断」を分離し、家庭用GPU上のローカルLLMでも低レイテンシで遊べる構成を目指す。

---

# 2. 最重要設計原則

## 2.1 Minecraft MODをLLMアプリにしない

Forge側に以下を持ち込まない。

- OpenAI SDK
- provider固有SDK
- API key
- 長期memory
- 巨大prompt
- provider switching
- JEV固有処理
- frontier model固有処理

Forge側は protocol adapter / executor として保つ。

---

## 2.2 Social Brain と Tactical Decision を分ける

会話・人格・関係性を扱うコンテキストと、ゲーム中の具体的な状況判断を混ぜない。

概念上は次の3層に分ける。

```text
Minecraft 1.7.10
      │
      │ events / state / action result
      ▼
Agent Daemon
      │
      ├─ Social Brain
      │    - 会話
      │    - 人格
      │    - relationship
      │    - long-term memory
      │    - local LLMを基本とする
      │
      ├─ Tactical Decision
      │    - goal
      │    - current game state
      │    - available actions
      │    - one-shot structured output
      │    - JEV / frontier model / local small model 等を差し替え可能にする
      │
      └─ Executor / Validator
           - action schema検証
           - 不正action拒否
           - Minecraft側へ送信
```

Social Brain の会話履歴を Tactical Decision provider へ送ってはならない。

Tactical Decisionへ渡す情報は、具体的なゲーム目的と、その判断に必要なゲーム状態だけにする。

例:

```json
{
  "goal": {
    "type": "collect_resource",
    "resource": "iron",
    "amount": 4
  },
  "agent": {
    "health": 14,
    "food": 17,
    "position": [120, 32, -50]
  },
  "inventory": {
    "iron_pickaxe": 1,
    "torch": 7,
    "bread": 3
  },
  "observations": {
    "iron_ore": [
      [126, 29, -47],
      [132, 25, -61]
    ],
    "hostiles": [
      {
        "type": "skeleton",
        "distance": 8
      }
    ]
  },
  "availableActions": [
    "mine",
    "move",
    "wait",
    "retreat"
  ]
}
```

Social Brainとの会話内容、人格prompt、relationship stateはここへ含めない。

---

## 2.3 毎ターン世界全体をLLMへ再送しない

既存のAI Companion系MODで見られた、

```text
turn 1: full world snapshot
turn 2: full world snapshot
turn 3: full world snapshot
```

という方式を避ける。

Daemon側に現在状態をキャッシュし、Minecraft側からは主に差分・イベントを送る。

例:

```text
player_chat
health_changed
inventory_changed
hostile_entered_range
hostile_left_range
task_started
task_completed
task_failed
dimension_changed
moved_significantly
entered_new_area
companion_died
```

必要なら定期的にfull snapshotで再同期してよいが、通常ターンで毎回full snapshotを送らない。

---

## 2.4 必要な観測だけ取得する

挨拶へ返事をするために、周囲の全ブロック情報をLLMへ送らない。

例:

```text
「こんにちは」
  → persona + recent chat + minimal status

「鉄を取ってきて」
  → inventory + known ores + mining-related state + tactical schema

「敵いる？」
  → nearby hostile observations

「ここに家を作って」
  → terrain/build observations
```

将来的には Tactical Decision 側から observation request を返せる構造も検討する。

例:

```json
{
  "type": "request_observation",
  "observation": "nearby_blocks",
  "radius": 8
}
```

ただしMVPではまだ実装しなくてよい。

---

# 3. 対象環境

## Minecraft

- Minecraft Java Edition 1.7.10
- Forge 1.7.10系
- Java 8を基本とする

### 重要

Minecraft 1.7.10のMOD開発環境は古いため、ForgeGradle / Gradle / JDK / mappings 等の正確な組み合わせをAIの記憶だけで決めないこと。

Phase 0で実際に動く組み合わせを確認し、以下をREADMEへ記録する。

- Forge version
- ForgeGradle version
- Gradle version / wrapper
- JDK distribution/version
- mappings
- build command
- runClient相当の起動方法
- Prism Launcherへjarを入れて確認する方法

一度確認できたバージョンは固定し、理由なく更新しない。

---

# 4. 想定実行環境

概念上は以下。

```text
Minecraft PC
  │
  │ LAN / Nebula
  ▼
Agent Daemon
  │
  ├─ Local Social LLM
  │    LM Studio / llama.cpp等
  │
  └─ Tactical Decision Provider
       JEV / frontier structured output / local model
```

Minecraft MODからLLMへ直接接続しない。

Minecraft MODが知る接続先は原則として Agent Daemon だけ。

例:

```text
http://127.0.0.1:8766
```

またはLAN/Nebula上のDaemon address。

---

# 5. Repository 構成

初期は以下程度にする。

```text
/
├─ init.md
├─ README.md
├─ .gitignore
├─ forge-mod/
│  ├─ build.gradle
│  ├─ gradle/
│  └─ src/
│     └─ main/
│        ├─ java/
│        └─ resources/
├─ agent/
│  ├─ README.md
│  ├─ src/
│  └─ tests/
└─ protocol/
   ├─ README.md
   └─ examples/
```

実際のForgeGradle 1.7.10テンプレート都合で必要なファイルは追加してよい。

不要なmonorepo frameworkは導入しない。

---

# 6. Protocol

MinecraftとDaemon間のprotocolは、特定LLM providerに依存させない。

MVPはHTTP + JSONでよい。

WebSocket、SSE、gRPC等は最初から導入しない。

## 6.1 Versioning

すべてのmessageにprotocol versionを持たせる。

```json
{
  "version": 1
}
```

互換性を壊す変更を暗黙に行わない。

---

## 6.2 Turn request

最初の実装では、player chatをきっかけにしたrequest/response方式でよい。

例:

```http
POST /v1/turn
Content-Type: application/json
```

```json
{
  "version": 1,
  "event": {
    "type": "player_chat",
    "player": "desktopgame",
    "text": "ついてきて"
  },
  "state": {
    "companion": {
      "position": [0, 64, 0],
      "health": 20,
      "task": "idle"
    },
    "player": {
      "position": [3, 64, 2]
    }
  }
}
```

response:

```json
{
  "version": 1,
  "say": "はいはい、行くよ。",
  "actions": [
    {
      "type": "follow",
      "target": "desktopgame"
    }
  ]
}
```

---

# 7. Action Schema

MVPで許可するactionを小さく保つ。

最初は以下のみ。

```text
say
follow
stop
look
goto
```

## 7.1 say

```json
{
  "type": "say",
  "text": "こんにちは"
}
```

## 7.2 follow

```json
{
  "type": "follow",
  "target": "desktopgame"
}
```

## 7.3 stop

```json
{
  "type": "stop"
}
```

## 7.4 look

```json
{
  "type": "look",
  "target": {
    "kind": "player",
    "name": "desktopgame"
  }
}
```

## 7.5 goto

```json
{
  "type": "goto",
  "position": [100, 64, -20]
}
```

### 重要

未知のactionは実行しない。

parameterが欠けているactionも実行しない。

Daemon側とMinecraft側の両方でvalidationする。

---

# 8. Companion Entity

MVPでは見た目やアニメーションの作り込みを優先しない。

最初は次の条件を満たせればよい。

- ワールドにspawnできる
- 名前が表示できる
- playerを向ける
- playerを追従できる
- stopできる
- chat messageを表示できる
- position / health / task stateを取得できる

Minecraft 1.7.10の既存mob AIを使える部分は使う。

最初から独自pathfinderを書かない。

Baritone相当の高度な経路探索はMVP外。

---

# 9. Agent Daemon

DaemonはMinecraftとは独立して実行できること。

Minecraftを起動しなくてもunit test可能にする。

## 9.1 Responsibilities

Daemon側で扱うもの:

- protocol parsing
- current-state cache
- event history
- Social Brain
- Tactical Decision Provider
- structured output
- action validation
- logging
- provider config
- API keys
- retries / timeout
- fallback

## 9.2 Provider abstraction

最低限以下のinterface相当を持たせる。

```text
SocialProvider
DecisionProvider
```

`DecisionProvider` は将来、

```text
JEV
OpenAI-compatible frontier model
local small LLM
deterministic rules
```

へ差し替えられること。

JEVをハードコードしない。

---

# 10. Social Brain

Social Brainは会話人格を担当する。

MVPでは高度な長期memoryは不要。

保持してよいもの:

- persona
- recent chat
- companion name
- owner name
- current high-level goal
- small relationship state
- important events

Social Brainは具体的なMinecraft操作を直接生成しなくてよい。

理想は次のようなintentを返すこと。

```json
{
  "reply": "鉄なら少し取ってくるよ。",
  "intent": {
    "type": "collect_resource",
    "resource": "iron",
    "amount": 4
  }
}
```

その後の具体的な行動判断は Tactical Decisionへ渡す。

---

## 10.1 会話・目的・行動の独立と割り込み

会話は行動完了を待たずに受け付けられる構造にする。会話の処理状態とactionの実行状態を、同じ一本の状態機械にしない。
ただし「受け付けられる」はLLMを無制限に並列実行する意味ではない。

```text
Social turn → reply
           → optional intent → Goal Manager → Tactical Decision
                                  │                 │
                                  └── cancel        ▼
                                              Action Executor
                                                    │
                                                action result
```

- Social Brain: 会話と任意のintentを生成する。Executorを直接操作しない。
- Goal Manager: 現在の目的と世代を管理し、新しいintentに対する継続・取消・置換を決める。最初は小さい状態管理でよく、汎用スケジューラーは作らない。
- Tactical Decision: 特定の目的の世代に対する一回の判断を返す。会話履歴を持たない。
- Action Executor: 検証済みactionを実行し、進行状態と結果を管理する。会話待ちで停止しない。

### 最初の運用規則

自然文の高度な意図分類より、明示的な操作から次の規則を成立させる。

| 入力 | 判断待ち・実行中の扱い |
| --- | --- |
| 通常の会話 | 現在の目的・行動を継続する。会話を受けただけで行動判断を無効にしない |
| 受理した新しい行動指示 | 最新の指示を優先し、以前の判断を無効化する。現在の行動を取り消して、新しい目的へ置換する |
| 明示的な停止・取消 | LLMや通常の待ち行列を待たずに目的を取り消し、Forgeの次の処理可能なゲームtickで停止する |
| 未対応・不正・曖昧な指示 | 勝手に目的を変更しない。未対応と知らせるか確認する |

最初の確実な停止手段は `!agent stop`。手動のfollow/stop/lookも同じ割り込み規則に参加させ、手動停止後に古いAI判断で追従を再開させない。
「戻って」は移動先の判断を要し、「危ない」は状況報告でもあるため、単語が含まれるだけで即時停止扱いにしない。「止まらないで」等の否定表現も同様。
自然文の分類方法・追加の停止表現・指示の予約実行は後から検討する。最初は明示的な指示と通常会話の経路を分けてよい。

新しい目的の受理と、LLMによる実行可能性の判断を区別する。
受理して置換した後に判断が失敗した場合は、停止状態のまま失敗を知らせる。取り消した古い目的を自動復活させない。
目的の変更がない通常の推論失敗・timeoutでは、現在actionを勝手に変更しない（第16節）。

### 古い結果を実行しないための識別

少なくとも以下を区別する。具体的なJSON形式は実装時にprotocolへ定義する。

- `sessionId`: ワールド入場・接続の世代。再入場や再接続で以前の処理を引き継がない。
- `turnId`: 会話の受付順と返信の対応。会話履歴消去後に古い返信や履歴を復活させない。
- `goalRevision`: 行動指示の受理・置換・取消で進む世代。雑談では進めない。
- `actionId`: 個々の実行を識別し、結果通知や重複配送を対応付ける。

観測差分のsequenceとgoalRevisionは別物である。通常の座標更新だけで毎回判断を失効させない。
一方で、世代が一致していても実行直前の体力・距離・対象・ディメンション等は再検証する。

判断開始時のsessionとgoalRevisionを結果に対応付け、Daemonの採用時とForgeの実行直前の両方で現在値と照合する。
停止の受付時点でForge側も古い実行権限を無効化する。Daemonへの取消通知の到着やLLM呼び出しの終了を待たない。
進行中のHTTP/LLM処理を物理的に中断できなくてもよいが、その遅延応答は実行にも現在の目的の書換えにも使わない。
無効になった依頼に対する「実行します」等の返答も、現在の依頼への返答として表示しない。

### 受付と待ち行列

Socialの推論は最初は同時1件までとし、行動実行・結果待ちとは独立させる。
追加の会話を保持する場合は件数と総文字数に上限を設け、受付順を保つ。満杯なら短く知らせ、黙って捨てない。
初期実装で会話をまとめ直す仕組みは不要。受理した会話には順番に返答してよい。
行動指示は無制限に積まず、未実行の旧指示を最新の指示で置換する。停止はこの待ち行列を迂回する。
共有GPU/providerの都合で推論が直列でも、ゲームtick・停止処理・行動の結果通知をその待ちに巻き込まない。

### 行動の状態と結果

実行受付と行動完了を区別する。最初は実行中・成功・失敗・取消を識別できればよい。
追従は開始時に完了とせず、停止・置換等まで続く行動として扱う。
取消は既に完了した操作の巻き戻しではない。将来の採掘・設置等では、安全に中断できる境界を各actionに定義する。
actionIdごとの実行は重複させず、遅れて届いた旧actionの結果は記録しても、新しい目的やactionの状態へ上書きしない。

「まだ？」等に答える際は、現在taskと確認できた結果など、必要最小限の進行状態だけをSocialへ渡せる接点を設ける。
進捗率や到着時間を根拠なく生成しない。この接点を理由にSocialの会話履歴をTacticalへ渡さない。

### 自動実行を接続する際の受け入れ条件

- 判断中に停止し、その後旧判断が到着してもactionを開始しない。
- 行動指示AをBへ置換し、応答順が逆転してもBだけを採用する。
- 雑談を挟んでも現在の目的・行動判断は維持される。
- 行動実行中・結果待ちでも会話を受け付けられる。
- 重複action配送を再実行せず、旧actionの結果で新actionを終了させない。
- ワールド再入場・再接続・会話履歴消去後に古い結果を復活させない。
- 会話の待ち行列に上限があり、満杯でも停止を妨げない。

遅延・応答順の逆転を制御できるmockで検証する。実モデルの偶然の応答速度に依存するテストにしない。

---

# 11. Tactical Decision

Tactical Decisionはone-shotを基本とする。

長期会話履歴を持たない。

入力:

- current goal
- sanitized current state
- relevant observations
- available actions

出力:

- structured decision

例:

```json
{
  "decision": {
    "action": "goto",
    "position": [126, 29, -47]
  },
  "reasonCode": "nearest_safe_target"
}
```

`reasonCode` はデバッグ用であり、自然言語reasoningを要求しない。

Chain of Thoughtを要求しない。

---

# 12. Privacy / Context Boundary

これは機能要件である。

Tactical Decision providerへ以下を送ってはならない。

- Social Brainの会話履歴
- private memory
- relationship conversation
- persona全文
- playerとの私的な雑談
- providerに不要なplayer identifiers

必要ならplayer名も tactical requestでは匿名化する。

例:

```text
desktopgame
```

ではなく、

```text
owner
```

でよい。

frontier modelをDecisionProviderとして使う場合も、この境界を維持する。

---

# 13. State Cache / Event Model

Daemonは現在状態を保持する。

Minecraftから来たeventを適用して更新する。

例:

```json
{
  "type": "inventory_changed",
  "entity": "companion",
  "added": {
    "minecraft:iron_ore": 2
  },
  "removed": {}
}
```

```json
{
  "type": "health_changed",
  "entity": "companion",
  "before": 20,
  "after": 14
}
```

```json
{
  "type": "hostile_entered_range",
  "entityType": "minecraft:skeleton",
  "distance": 8
}
```

MVPではevent種類を増やしすぎない。

---

# 14. Full Snapshot

差分だけでは同期が壊れる可能性があるため、full snapshotは残す。

ただし毎LLM turnには付けない。

用途:

- world join
- companion spawn
- reconnect
- manual debug
- periodic resync
- state mismatch recovery

例:

```http
POST /v1/snapshot
```

---

# 15. Logging

ログは最低限以下を追跡できること。

- Minecraft → Daemon event
- Daemon → Minecraft action
- SocialProvider request duration
- DecisionProvider request duration
- provider name
- input size
- output size
- validation failure
- action result
- timeout
- retry

API keyやsecretはログへ出さない。

Social Brainの会話本文をdebug logへ常時dumpしない。

Tactical Decisionはsanitized payloadのみdebug dump可能にする。

---

# 16. Error Handling

## Daemon unavailable

Minecraftをクラッシュさせない。

Companionは停止状態に入り、必要ならplayerへ短いエラーを表示する。

## LLM timeout

現在actionを勝手に変更しない。

タイムアウトしたturnを無限retryしない。

## Invalid structured output

actionを実行しない。

validation errorとして記録する。

## Unknown action

無視してログに残す。

## Executor failure

結果をDaemonへ返す。

例:

```json
{
  "type": "action_result",
  "actionId": "abc123",
  "status": "failed",
  "reason": "path_not_found"
}
```

---

# 17. Security

MVPでも以下は守る。

- API keyをForge MOD configへ保存しない
- API keyをMinecraft clientへ送らない
- Daemonのlisten addressを明示設定する
- defaultはlocalhost bindを推奨
- LAN/Nebula公開時は明示設定する
- arbitrary shell executionを実装しない
- LLMの文字列をMinecraft commandとしてそのまま実行しない
- action allowlistを使う

---

# 18. Phase 0 — 開発環境構築

最初のAstra担当範囲。

## Goal

Minecraft 1.7.10 Forge MODを再現可能にビルド・起動できる状態を作る。

## Tasks

1. Forge 1.7.10向けの実用的な開発環境を確認
2. JDKを固定
3. Gradle wrapperを固定
4. ForgeGradle / mappingsを固定
5. 空のMODをビルド
6. dev clientを起動
7. MODがロードされたことをログで確認
8. jarを生成
9. Prism Launcherの1.7.10インスタンスへjarを入れて起動確認
10. READMEへexact commandを記録

## Acceptance Criteria

以下がすべて成立すること。

```text
./gradlew build
```

またはその環境で正しい同等commandが成功する。

生成されたjarをPrism Launcherへ入れるとMinecraft 1.7.10が起動する。

ログにMODの初期化メッセージが出る。

新規cloneからREADMEの手順だけで再現できる。

## Important

環境構築中に、AI Companion本体の実装へ先走らない。

まず「空の1.7.10 Forge MODが再現可能に動く」ことを確定する。

---

# 19. Phase 1 — Minecraft ↔ Daemon Ping

LLMをまだ使わない。

## Goal

Forge MODと外部Daemonが通信できることを確認する。

### Minecraft

playerがchatへ

```text
!agent ping
```

と入力したらDaemonへHTTP requestを送る。

### Daemon

固定responseを返す。

```json
{
  "say": "pong",
  "actions": []
}
```

### Minecraft

chatへ

```text
[Companion] pong
```

を表示する。

## Acceptance Criteria

- LLMなし
- providerなし
- memoryなし
- Companion Entityなし

でも通信が確認できる。

---

# 20. Phase 2 — Companion Entity + Minimal Actions

追加する。

```text
spawn
say
look
follow
stop
```

この段階でもLLMは不要。

手動debug commandからactionを送れるようにする。

例:

```text
!agent follow
!agent stop
```

---

# 21. Phase 3 — Local Social Brain

ここで初めてローカルLLMを接続する。

Goal:

```text
player chat
   ↓
Social Brain
   ↓
reply + optional intent
   ↓
Minecraft
```

最初はaction生成を限定する。

LLMが自由形式Minecraft commandを生成する設計にしない。

---

# 22. Phase 4 — Tactical Decision Provider

DecisionProvider interfaceを追加する。

最初はmock providerでよい。

次にone-shot structured output対応providerを追加する。

候補:

```text
JEV
OpenAI-compatible API
local model
```

Social contextが混入していないことをtestする。

---

# 23. Phase 5 — State Diff / Observation

Minecraft側からeventを送る。

最初の候補:

```text
position_changed_significantly
health_changed
inventory_changed
hostile_entered_range
hostile_left_range
task_completed
task_failed
```

full snapshotはjoin/reconnect時のみ。

---

# 24. Phase 6 — Game Actions

MVP後に順次検討する。

```text
pickup
attack
mine
place
craft
smelt
deposit
```

一度に全部作らない。

Minecraft 1.7.10のpathfinding / recipe / inventory APIを確認しながら追加する。

---

# 25. Non-goals for MVP

以下は最初から作らない。

- 完全自律Minecraft攻略
- 自動建築
- 高度なクラフトプランナー
- GregTech攻略
- 全MOD対応
- vision model
- voice input
- TTS
- multiplayer対応
- remote internet exposure
- companion skin customization
- romance system
- animation system
- Baritone完全移植
- long-term vector database
- RAG
- embeddings
- multi-agent
- autonomous infinite loop
- GUI設定画面

後から必要になったものだけ追加する。

---

# 26. Testing

## Forge側

可能な範囲でlogicをMinecraft依存コードから分離する。

特に:

```text
protocol parsing
action validation
state diff
```

はunit test可能にする。

## Daemon側

Minecraftなしでtestできること。

fixtureとしてprotocol JSONを使う。

最低限:

- valid event
- invalid event
- valid action
- unknown action
- missing parameter
- timeout
- malformed model output
- social context leakage test

を用意する。

---

# 27. Context Leakage Test

重要な回帰テスト。

Social Brainへ次の会話を入れる。

```text
PRIVATE_TEST_MARKER_12345
```

その後Tactical Decision requestを生成する。

Tactical payloadに

```text
PRIVATE_TEST_MARKER_12345
```

が含まれていないことを自動testする。

persona全文やrecent chatがDecisionProvider payloadへ混入していないことも確認する。

---

# 28. Performance Metrics

ローカルLLM用途なので、品質だけでなくlatencyを測る。

最低限記録する。

```text
social prompt chars/tokens
social prefill time
social generation time

decision prompt chars/tokens
decision latency

Minecraft → daemon RTT
daemon → Minecraft RTT
```

「賢いが遅い」は失敗になり得る。

短い通常会話では巨大なゲーム状態を送らない。

---

# 29. Coding Constraints

- まず動く小さい実装を作る
- 不要なframeworkを入れない
- interfaceを増やしすぎない
- provider依存をprotocolへ漏らさない
- Minecraft version固有処理をAgent Daemonへ漏らさない
- magic reflectionに頼らない
- silent fallbackを増やさない
- errorは原因が追える形で残す
- debug outputとuser-visible messageを分ける
- generated filesを不用意にcommitしない
- secretをcommitしない

---

# 30. README に最終的に必要な内容

最低限:

```text
What this is
Architecture
Prerequisites
Minecraft 1.7.10 environment setup
Build
Run Forge dev client
Build jar
Install into Prism Launcher
Run Agent Daemon
Protocol overview
Configuration
Troubleshooting
Known limitations
```

1.7.10固有の古い環境構築は特に丁寧に書く。

将来のAI実装担当が再調査しなくて済む状態にする。

---

# 31. 最初の作業指示

Astraはまず Phase 0 だけを実施する。

完了条件:

1. Minecraft 1.7.10 Forgeの空MODがbuildできる
2. dev clientが起動する
3. MOD initログが確認できる
4. jarが生成される
5. Prism Launcherで生成jarをロードできる
6. 正確なversionとcommandがREADMEへ記録される

この段階ではDaemonもLLMも実装しない。

Phase 0完了後に、Phase 1としてMinecraft ↔ Daemonの固定`ping/pong`を作る。

---

# 32. 最も重要なルール

このプロジェクトでは、

> MinecraftへAIを埋め込む

のではなく、

> Minecraftを外部Agent Runtimeへ接続する

と考える。

そして、

> Social context と Tactical context を混ぜない。

> 毎ターン同じ巨大contextを再送しない。

> ゲーム内の具体的な判断はone-shot structured decisionとして切り出せる設計にする。

この3点を実装中に崩さないこと。
