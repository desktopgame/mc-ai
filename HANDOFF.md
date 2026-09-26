# 引き継ぎ — v0.1.0

更新日: 2026-09-26。実装基点 `ed1ca65`（0.0.31）。v0.1.0準備ではドキュメント整理とMODバージョンを変更し、機能修正は行わない。

## 最初に読むもの

[README](README.md) → [既知の問題](knwon_issue.md) → [リリース記録](RELEASE_NOTES.md) → [通信仕様](protocol/README.md)。
`init.md` は将来構想を含む。作業ログを追加しない。過去の段階別の変更・検証は [development-history.md](development-history.md) に退避した。

## 現在の実装

- 手動操作、ローカル会話、限定intent、Tactical判断、非同期goal、割り込み・取消・世代管理。
- 差分観測と上限付きblock候補。Skillはcollect_drop / mine / collect_block。
- collect_blockは原木のみ。回収数で完了し、採掘数は別の累積値。採掘→drop待ち→回収を実装。
- Skill終端snapshot → outbox → Forge共有Social queue → 同じproviderの候補選択 → 実表示 → ACK → 会話履歴。
- terminalの生成・待機中の12秒fallback、forget時の未表示結果処理、有界ACKキュー、cleanup独立処理。
- 攻撃・設置・クラフト・精錬、自然文からの引数付きSkill選択/JEV、Plannerは未実装。

## 前回レビューへの対応

[review-f33f7f5](reviews/review-f33f7f5.md) の5件に対して `7459b4d` / `978b046` で修正が入った。

| 指摘 | 現行コードでの対応 |
| --- | --- |
| presentなし／生成中fallback ACK | snapshotからfallbackを確定して閉じ、履歴登録用sayを返す |
| ACK executor拒否 | 最大32件のpending ACKを保持し再投入。上限超過・通信失敗後の保証は限定 |
| 生成中12秒上限 | PingBridgeがTerminalDeliveryStateの期限を確認 |
| forget中生成の結果欠落 | in-flightを含む未表示結果をfallbackで解決してからreset |
| cleanup取りこぼし | pendingCleanupを通知種別から独立して保持 |

コード反映を確認したことと、全異常系の実ゲーム検証は別。残る問題と検証不足は [knwon_issue.md](knwon_issue.md) を参照。利用者から修正後にも問題が残るとの報告あり。症状未特定のものを解決済みにしない。

## 再開時の確認

1. branch、HEAD、作業ツリー、タグの有無を実際に確認する。本書の基点を最新HEADとは扱わない。
2. `forge-mod/build.gradle`・`CompanionMod.java`・`mcmod.info` のバージョンを照合。v0.1.0の出力名は `mc-ai-companion-0.1.0.jar`。
3. 配置jarと起動中のゲーム・Daemonを確認する。本書に固定PIDや「起動中／終了済み」は保存しない。
4. `agent/config.local.json` / `decision.local.json` とキーはGit管理外。内容をログ・チャットへ出さない。
5. 変更する場合は対象の既知問題を再現し、Javaの純粋状態テストだけでなく実際の配送順序も検証する。
6. ゲーム更新前は保存終了、Daemon更新前は動作中Skillを止める。タグ・コミット・jar配置は別作業。

## 環境と確認範囲

Windows、Prism `1.7.10-mod-basic`、Forge 1.7.10、JDK 8、Python 3.14.0。LLM接続とモデルはREADMEの環境例を参照。
過去に基本の追従・停止・注視・会話記憶・Skill収集/採掘・終端表示を利用者が確認している。最新版のテスト件数と今回のbuild結果は [RELEASE_NOTES](RELEASE_NOTES.md) を正とする。
今回の文書整理ではPrismへの配置、ゲーム操作、Daemon再起動、タグ作成は行っていない。
