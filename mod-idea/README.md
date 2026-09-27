# アイデア資料

このディレクトリは設計背景・未確定案です。実装済み機能の一覧は [README](../README.md)、残課題は [既知の問題](../known_issue.md) を参照してください。

[skill.md](skill.md) を具体化した [Skill Layer](../protocol/skill-layer.md)、[collect_block](../protocol/collect-block.md)、[終端Social通知](../protocol/skill-terminal-social.md) はv0.1.0に実装されています。元のアイデア全体を実装済みとする意味ではありません。

## 未確定の小さい改善案

- **mine対象の到達判定を広げる**: 現在の採掘対象探索はプレイヤー目線の高さに近い1点のレイキャストに依存しており、対象ブロックと目線の高さがずれると`blocked`で失敗する（2026-09-27、collect_block(minecraft:log)の自然文Skill起動確認時に観測）。もう少し粗く・広い範囲（複数レイ or AABB探索等）で対象を拾えるようにできると、木の高さや足場の段差での失敗が減りそう。対象の探索・到達判定（mine primitive側）の話で、Social/Skill選択とは別レイヤー。
