# mc-ai: Typed Slot Filling + Skill Layer Design

## 背景

Minecraft 1.7.10 上の NPC を LLM で動かす際、世界状態・インベントリ・周辺ブロック・行動履歴・会話履歴などを毎回すべてモデルへ渡すと、コンテキストが急速に肥大化する。

また、RP 用の会話履歴には外部の frontier model へ渡したくない情報が含まれる可能性がある。

そこで、LLM に Minecraft の低レベル操作を直接生成させるのではなく、

1. RP 会話から「行動に必要な意味」だけを抽出する
2. 行動を型付きの穴埋め形式に落とす
3. 具体的な実行は再利用可能な Skill に任せる

という構造を検討する。

---

## 基本アイデア

LLM に自由な行動列を生成させるのではなく、あらかじめ用意した Action / Skill の引数を埋めさせる。

例:

```text
collect(A, B)
craft(A, B)
kill(A, B)
mine(A, B)
move_to(A)
```

具体的には:

```json
{
  "skill": "collect",
  "item": "minecraft:log",
  "count": 16
}
```

このとき `item` には任意の文字列を許さず、Minecraft 内で意味のある値だけを schema で選択可能にする。

```json
{
  "skill": {
    "enum": ["collect", "craft", "kill", "mine"]
  },
  "item": {
    "enum": [
      "minecraft:log",
      "minecraft:cobblestone",
      "minecraft:iron_ingot"
    ]
  },
  "count": {
    "type": "integer",
    "minimum": 1,
    "maximum": 64
  }
}
```

可能なら Minecraft 全 registry を渡すのではなく、その時点で意味のある候補だけを schema に含める。

例:

```text
collectable:
- minecraft:log
- minecraft:cobblestone

craftable:
- minecraft:planks
- minecraft:stick

killable_nearby:
- minecraft:sheep
- minecraft:zombie
```

モデルは「Minecraftについて自由に文章を書く」のではなく、

- Skill を選ぶ
- 対象を選ぶ
- 数量などの引数を決める

という constrained decision のみを行う。

---

## Skill Layer

`collect(log, 16)` のような指示を、そのまま Minecraft の primitive operation に対応させない。

Skill 内部で複数の操作へ分解する。

例:

```text
collect(minecraft:log, 16)

  find suitable tree
      ↓
  navigate to tree
      ↓
  choose suitable tool
      ↓
  equip axe
      ↓
  mine log
      ↓
  collect dropped item
      ↓
  check inventory count
      ↓
  repeat if necessary
```

上位モデルからは、この内部処理を隠す。

つまり:

```text
Natural language
      ↓
Goal / Intent
      ↓
Typed Skill Call
      ↓
Skill
      ↓
Primitive Actions
      ↓
Minecraft
```

とする。

---

## なぜ Skill にするか

Minecraft の低レベル操作まで毎回 LLM に判断させると、

```text
木を探す
座標を確認する
近づく
3番スロットの斧を選ぶ
向きを変える
掘る
ドロップへ移動する
拾う
次の木を探す
...
```

のように大量の状態と行動履歴が必要になる。

一方、

```text
collect(log, 16)
```

を Skill にしておけば、上位モデルに必要なのは、

```text
current_goal
current_subgoal
available_skills
relevant_resources
recent_skill_result
```

程度になる。

また、Skill は deterministic code として徐々に強化できる。

たとえば `collect` が最初は単純な木の採取しかできなくても、後から、

- 適切な道具を自動選択
- 道具がなければクラフト
- 耐久値を考慮
- 到達不能な対象を除外
- 複数の採取地点を巡回
- 危険時に一時中断

などを Skill 内部だけで改善できる。

上位 Planner の schema を変更する必要はない。

---

## Planner と Skill の分離

長期的には最低でも以下の層に分けたい。

```text
Planner
  「次に何を達成するべきか」
        ↓
Tactical / Skill Selection
  「どの Skill を何に対して使うか」
        ↓
Skill Executor
  「Minecraft 上でどう実現するか」
        ↓
Primitive / Reflex
```

例:

ユーザー:

```text
夜までに簡単な拠点を作って
```

Planner:

```json
{
  "next_subgoal": "secure_building_materials"
}
```

Tactical:

```json
{
  "skill": "collect",
  "item": "minecraft:log",
  "count": 16
}
```

Skill executor:

```text
find tree
move
equip axe
mine
pickup
repeat
```

Planner は細かい座標や hotbar slot を知る必要がない。

---

## RP Context との分離

RP 用モデルの会話履歴は、Planner にそのまま送らない。

例:

ユーザー:

```text
今日は疲れたから木を少し集めてきて。
昨日のことは誰にも言わないでね。
```

RP context には全文を保持する。

しかし行動側には、たとえば以下だけ渡す。

```json
{
  "skill": "collect",
  "item": "minecraft:log",
  "count": 8
}
```

これにより frontier model が必要であっても、外部へ送られるのは Minecraft の行動情報だけにできる。

構造としては:

```text
Private RP Context
      ↓
Local Intent / Constraint Extraction
      ↓
Typed Goal / Skill / Constraints
      ↓
Frontier Planner
      ↓
Skill Executor
```

を想定する。

---

## RP 情報が行動に影響する場合

RP と行動を完全に切り離せないケースもある。

例:

```text
あの村人には近づかないで
```

これは会話内容だが、navigation constraint としては必要。

その場合も会話本文そのものを渡すのではなく、行動に必要な部分だけを構造化する。

```json
{
  "skill": "collect",
  "item": "minecraft:log",
  "count": 16,
  "constraints": [
    {
      "type": "avoid_entity",
      "entity_id": "npc:bob"
    }
  ]
}
```

つまり、

```text
Private RP state
    ↓ projection
Action-relevant constraints
```

という境界を設ける。

---

## Context Projection

Minecraft の世界状態そのものを一つの巨大な context と考えない。

用途ごとの projection を生成する。

例:

### Planner Context

```text
goal: survive_the_night
time_remaining: 6 min
important_inventory:
- log x3
- cobblestone x8
known_locations:
- forest nearby
- flat ground nearby
```

### Combat Context

```text
self_hp: 12
weapon: stone_sword
enemy:
- zombie distance=4
- zombie distance=7
```

### Navigation Context

```text
current_position
target_position
nearby_obstacles
hazards
```

### Craft Context

```text
inventory
available_recipes
missing_materials
```

モデルごとに必要な projection しか見せない。

---

## 差分コンテキストとの組み合わせ

Skill 化しても、world state は時間とともに変化する。

毎回 full snapshot を渡すのではなく、

```text
Initial compact snapshot
        ↓
delta
delta
delta
        ↓
periodic / error-triggered resync
```

とする。

例:

```text
current_subgoal: collect log x16
inventory.log: 7

Delta:
+ minecraft:log x1
axe durability: 83 -> 82
target tree depleted
```

Skill 実行中の細かい delta は Skill executor が処理し、Planner には意味のある結果だけを返す。

```json
{
  "skill": "collect",
  "status": "completed",
  "item": "minecraft:log",
  "collected": 16
}
```

または:

```json
{
  "skill": "collect",
  "status": "failed",
  "reason": "no_reachable_target"
}
```

これにより Planner の context をさらに小さくできる。

---

## Schema は Action ごとに分ける

可能なら単一の巨大 schema にしない。

例:

### collect

```text
item: currently_collectable_item
count: positive integer
```

### craft

```text
recipe: currently_known_or_available_recipe
count: positive integer
```

### kill

```text
entity: valid_target_entity
count: positive integer
```

### move_to

```text
target: known_location | perceived_position | entity
```

### place

```text
block: available_placeable_block
location: valid_location
```

これにより、

```text
kill(oak_log)
craft(zombie)
```

のような意味不明な組み合わせを schema レベルで禁止できる。

---

## Dynamic Schema

Schema の enum 値は固定ではなく、ゲーム状態から動的に生成したい。

例:

```text
Nearby:
- oak log
- sheep
- zombie

Inventory:
- oak log x3
- cobblestone x4

Craftable:
- oak planks
- sticks
```

から、

```text
collect.target:
- oak_log
- cobblestone

kill.target:
- sheep
- zombie

craft.target:
- oak_planks
- stick
```

を生成する。

モデルに不要な選択肢そのものを見せない。

これは token 削減だけでなく、hallucination や invalid action の抑制にも使える。

---

## Skill の粒度

Skill は最初から巨大にしすぎない。

初期候補:

```text
collect(item, count)
craft(item, count)
mine(block, count)
kill(entity, count)
move_to(target)
equip(item)
place(block, target)
eat(item)
```

将来的には複数 Skill をまとめた高レベル Skill も作れる。

```text
get_wood(count)
get_food(count)
craft_basic_tools()
build_basic_shelter()
return_home()
```

つまり Skill 自体も階層化できる。

```text
build_basic_shelter
    ↓
collect wood
craft planks
choose location
place blocks
```

上位モデルには、十分信頼できる高レベル Skill だけを公開することもできる。

---

## Frontier Model の用途

Frontier model に Minecraft の全状態を渡す必要はない。

想定する役割は、

```text
Goal:
"夜までに生存できる状態にする"

Available skills:
- collect
- craft
- build_basic_shelter
- get_food
- move_to

State:
- night in 6 min
- food sufficient
- building materials insufficient
- forest nearby
```

から、

```json
{
  "skill": "collect",
  "item": "minecraft:log",
  "count": 16
}
```

を選ばせる程度に絞る。

RP 会話・全 inventory・全 block scan・primitive action history などは見せない。

---

## Reflex は LLM に渡さない

即時判断が必要かつルール化できるものは MOD 側で処理する。

例:

```text
creeper extremely close
→ retreat

standing in fire
→ escape

HP critical and safe food available
→ eat

current target disappeared
→ invalidate target
```

こうした処理まで Planner に問い合わせると latency と context が増える。

LLM は「曖昧で意味的な判断」に集中させる。

---

## 最初の検証ケース

最初は以下の命令だけで十分に設計を検証できる。

```text
「原木を10個集めて」
```

期待する流れ:

```text
User request
    ↓
Intent extraction

collect(log, 10)

    ↓
Skill

find log
navigate
select tool
mine
pickup
repeat

    ↓
Skill result

collected log x10

    ↓
RP response
```

このケースだけでも、

- schema constrained output
- dynamic candidate generation
- tool selection
- navigation
- mining
- pickup
- inventory change
- repeated execution
- completion detection
- RP/action context separation

を一通り確認できる。

---

## この設計で確認したい点

Astra には特に以下を検討してほしい。

1. 現在の mc-ai の構造に、この Skill Layer をどこへ挿入するのが自然か
2. 現在の action / decision schema をどこまで再利用できるか
3. Dynamic Schema を Forge 1.7.10 側から生成する現実的な方法
4. `collect(log, 10)` を最初の Skill とした場合の具体的な責務分割
5. Skill executor と Planner の state をどこで保持するべきか
6. Skill が途中失敗した場合の failure schema
7. RP context → typed intent / constraints への変換部分を独立レイヤーにするべきか
8. 現状の差分 context システムと競合せず統合できるか
9. 将来的に Skill を階層化する場合、今の段階で避けるべき設計上の固定化があるか
10. 「LLM に渡さず deterministic code に置くべき責務」の境界