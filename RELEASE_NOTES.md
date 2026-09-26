# v0.1.0 リリース準備記録

日付: 2026-09-26。実装基点: `ed1ca65`（0.0.31）。既知の不具合を残して現在の機能を区切る版。
この文書整理で機能は変更せず、MODの3か所のバージョンを0.1.0に揃えた。タグ作成は利用者が行う。

## 含む機能

Companionの手動操作・所持品保存、ローカルSocial会話と限定intent、独立したTactical判断、観測同期、非同期goal/割り込み、collect_drop/mine/collect_block、Skill終端の候補選択発話と固定fallback。

0.0.31までに前回レビューの5件へコード修正が入っている。ただし無制限・永続の通知配送や全異常系の実機確認は保証しない。残課題は [known_issue.md](known_issue.md)。

## 今回の確認

- Python: 155件。初回はHTTPエラーテストでWinError 10053が1件発生。全体を再実行して155件成功。初回失敗は [KI-10](known_issue.md#ki-10-httpエラーテストで接続中断を観測) として記録。
- Java: 83件成功。`build --offline --rerun-tasks` で再実行。
- Forge: build成功。mcmod.info更新後にも再buildし、jar内version=0.1.0を確認。
- 文書:主要ドキュメントのローカルリンクとgit diffの空白エラーを検査。
- jar SHA-256: `fe4e87001a6200e58f17ade0b70f46056903d3b0c054303c9c3163ff9e9e63ba`。これは生成物の識別子であり、Prism配置済みという意味ではない。
Prismへのjar配置、Minecraft操作、Daemon再起動、コミット、タグ作成は今回行わない。
過去の基本動作確認と、v0.1.0の最新jarでの実ゲーム検証は区別する。

## タグを付ける前の確認

1. 差分を確認し、必要なドキュメント・バージョン変更をコミットする。タグはコミットを指し、未コミットの内容は含まない。
2. build結果、既知問題の記録、利用者報告の未特定項目を確認する。
3. 利用者が対象コミットへ `v0.1.0` タグを付ける。タグpushやリリース公開は別途行う。

配布候補jar: `forge-mod/build/libs/mc-ai-companion-0.1.0.jar`。
