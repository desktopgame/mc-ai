# mine primitive — MOD 0.0.13 / protocol 2

状態: **実装済み**。`collect_drop(0.0.12)` を変更せず、mine primitive を追加した増分。
自動テストはPython 81件・Java 43件。実ゲーム検証は未実施。

## 目的と固定した判断

- `mine_target` は **1 action = 1 block破壊**。反復・数量は将来のSkill側の責務。
- block観測は周辺世界のvoxel mapではなく、**mine対象候補を選ぶための上限付き観測**。
  Companion周辺16ブロック（水平±16・垂直±8）で、**block typeごとに最近傍4件・合計最大32候補**。
  距離順の一律N件にすると近くの `dirt` が `log` やoreを締め出してしまうため、typeごとの公平な上限にする。
  既存 `items` と同じく registry名と2ブロック刻みの距離だけを持つ。
- Daemonが扱うのは **opaqueな `targetRef`（`block-<x>_<y>_<z>`）、block registry名、distance** のみ。
  実座標はForgeがtargetRefから解決し、Daemonへ別途露出しない。
- 全block列挙・3D voxel map・chunk knowledge・全metadata/NBTは扱わない。
- 道具選択はForge側で決定的に行う。自動クラフトは行わない。
- 素手で採掘可能（`Material.isToolNotRequired()`）なら素手で破壊。必須ツールが無ければ
  `tool_unavailable` で失敗。ツールがあれば最も採掘速度の高いものを選ぶ。
- `mine` の進捗は `mined`（破壊数）。receiptは `destroyed:{block,count}`。
  これは将来の `collect(log,N)` の取得progressへ直接加算しない。

## 観測（v1 snapshot/delta に追加）

`state.blocks` を追加。値は `{"type": <block registry名>, "distance": <2ブロック刻み>}`。
イベントは `block_entered_range / block_updated / block_left_range`。

allowlist（Daemon `SUPPORTED_BLOCKS` と MOD `SkillProtocol.BLOCKS` で一致必須）:
`minecraft:log / log2 / cobblestone / stone / coal_ore / iron_ore / gold_ore / diamond_ore / dirt / sand / gravel`

## Skill protocol

### mine goal（`/v2/goal`）

```json
{"version":2,"session":"obs-session","daemonEpoch":"boot-id","goalRevision":7,
 "goal":{"type":"mine","target":{"block":"minecraft:log"},"count":1,"constraints":[]}}
```

### mine_target action（v2 view.action）

```json
{"type":"mine_target","actionId":"action-1","actionSequence":1,"skillInstanceId":"skill-id",
 "companionId":"companion-id","dimension":0,"targetRef":"block-10_64_-3",
 "block":"minecraft:log","timeoutMs":30000,"observationSequence":21}
```

### mine result（`/v2/action-result`）

```json
{"version":2,"session":"obs-session","daemonEpoch":"boot-id","goalRevision":7,
 "skillInstanceId":"skill-id","actionId":"action-1","actionSequence":1,
 "status":"succeeded","reason":"completed","destroyed":{"block":"minecraft:log","count":1}}
```

進捗は `progress:{requested, mined, complete}`。terminal理由は `no_block_in_range` と
`tool_unavailable`（後者は即terminal）を追加。既存の期限・lease・cancel handshake・receipt台帳を
そのまま使う。

## 手動入口

```
!agent do mine <ブロック>
```

1ブロックを対象とする（count指定はSkill側の責務）。`!agent do collect_drop <アイテム> <個数>` は
既存のまま。

## 変更したファイル

- Daemon: `skill_protocol.py`（mine goal/result union）、`skills.py`（`Skill` 基底＋ `CollectDrop` / `Mine`）、`state_cache.py`（blocks）。
- Forge: `SkillProtocol.java`（mine action/view）、`ActionBridge.java`（mineのclaim/receipt/lease）、`CompanionEntity.java`（`MineTargetTask`・道具選択・破壊）、`ObservationBridge.java`/`ObservationDiff.java`（blocks）。

## 未実装（この増分の範囲外）

`collect_block` / `collect(log,N)`、探索移動、自動クラフト、複数block metadata、耐久値消費。
