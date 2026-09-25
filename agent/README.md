# Agent Daemon — Phase 3

Python標準ライブラリで動く固定ping/pongとローカルSocial Brain。確認環境はPython 3.14.0。設定を省略するとping専用になる。

## ローカルLLMとAPIキー

新規cloneでは `config.example.json` を `config.local.json` へコピーし、`model` をLM StudioでロードしたモデルのAPI識別子へ変更する。
現在の実機検証モデルは `unsloth/gemma-4-26b-a4b-it`。接続先は `http://127.0.0.1:1234/v1`。
Phase 3では接続先をループバックHTTPに限定し、リダイレクト・環境変数のHTTPプロキシを使わない。

APIキーの設定方法は次のいずれか。

- 環境変数: Daemonを起動するプロセスに `MCAI_SOCIAL_API_KEY` を設定する。名前はconfigの `api_key_env` で変更できる。
- ローカルファイル: リポジトリ直下で `.\scripts\set-social-api-key.ps1` を実行して非表示入力する。`.tools/social-api-key.txt` に平文で保存される。configに `"api_key_file": "../.tools/social-api-key.txt"` を追加する。パスはconfigファイルの場所から解決する。

両方ある場合は環境変数を優先する。キーもローカル設定もGit管理外。キーをMODへ渡さず、ログにも出さない。変更後はDaemonを再起動する。

```powershell
python agent/src/daemon.py --port 8767 --config agent/config.local.json
```

`persona` で会話人格を設定できる（1～2000文字）。未指定なら短く親しみやすい日本語で話すCompanion。
`reasoning_effort` は既定で `none`。対応していないローカルサーバーでは `null` にするとフィールドを送らない。
指定Gemmaモデルは既定の思考生成では256トークンで返答が完了しなかったため、`none` で実測確認した。
[LM Studioの対応について](https://lmstudio.ai/changelog/lmstudio/lmstudio-v0.4.8)。

履歴はメモリ内のみ。セッション別に直近6往復・約4000文字まで、最大32セッション。
不正・空・長すぎる・生成途中の出力は拒否する。`reasoning_content` やtool callをゲームへ送らず、`actions` は常に空。
タイムアウト・認証失敗・モデル未ロード等はHTTP 503として返し、ゲーム操作や会話履歴を更新しない。

## ping専用起動

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

ログにはHTTPステータス、応答サイズ、LLMの応答時間・入出力サイズ・トークン数を記録する。会話本文・プレイヤー名・リクエストヘッダーは記録しない。これはDaemon側のログ方針であり、LM Studio側のログ設定とは独立している。
