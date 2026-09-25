# Agent Daemon — Phase 1

Python標準ライブラリだけで動く固定ping/pongサーバー。確認環境はPython 3.14.0。LLM、provider、memory、Companion Entityは使わない。

リポジトリ直下から:

```powershell
python agent/src/daemon.py
```

既定の接続先は `http://127.0.0.1:8766`。終了はCtrl+C。
別のポートは `--port 8767`。LANへ公開するときだけ `--host` で明示的に待受アドレスを指定する。認証・インターネット公開・マルチプレイヤーは未対応。

このPCでは8766が利用できなかったため、実機検証では `python agent/src/daemon.py --port 8767` を使用する。以下の確認URLも8767へ読み替える。MOD側の `daemonUrl` とポートを一致させる。

別のPowerShellから疎通確認:

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8766/v1/turn -Method Post -ContentType application/json -InFile protocol/examples/ping-request.json
```

テスト:

```powershell
python -m unittest discover -s agent/tests -v
```

ログにはHTTPステータスと応答サイズを記録する。会話本文・プレイヤー名・リクエストヘッダーは記録しない。
