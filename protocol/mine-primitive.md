# mine primitive — MOD 0.0.15 / protocol 2

状態: **実装済み**。`collect_drop(0.0.12)` を変更せず、mine primitive を追加した増分。
自動テストはPython 82件・Java 45件。基本の経時破壊は実ゲームで確認済み（遮蔽・count境界は未検証）。

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
- 破壊はプレイヤーと同じく **block hardness と tool speed に応じた時間**がかかる。採掘中は
  `World.destroyBlockInWorldPartially` で破壊アニメーションを出し、`EntityLivingBase.swingItem` で腕を振る。
  到達範囲を外れたら途中経過をリセットして近づき直す。硬さ0以下は即時、負（bedrock等）は `tool_unavailable`。
- `mine` の進捗は `mined`（破壊数）。receiptは `destroyed:{block,count}`。
  これは将来の `collect(log,N)` の取得progressへ直接加算しない。
- **`mine` goal の count は必ず1**。`mine_target` は1 action = 1 block。複数個の反復は将来の
  `collect_block` / `collect(block,N)` Skillの責務。count 2以上は `unsupported_count` で拒否する。
- **遮蔽 / 到達可能性を採掘開始前とworld mutation直前の両方で再検証**する。観測候補として
  「見えている/近い」ことと実行可能であることを同一視しない。
  - air・collisionを持たない非固体（草/花）・leaves は通過可能（leavesはMVPではsoft obstruction扱い）。
  - glass / stone / dirt / wood / ore など `Material.isSolid()` のブロックは遮蔽。
  - 判定は `MineObstruction` が `Material.isSolid()` と leaves 例外で行い、独自の巨大allowlistは作らない。
  - 遮蔽時は `blocked` で失敗し、**邪魔なブロックを勝手に複数破壊しない**（1 action = 1 block維持）。
    将来は上位Skill/Plannerが obstruction を `mine_target` で先に処理する。
  - `blocked` は失敗候補として除外して次の候補へ進み、連続失敗カウントには数えない。

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
