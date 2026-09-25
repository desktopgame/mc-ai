# Agent Daemon — Phase 5

Python標準ライブラリで動く固定ping/pong、観測キャッシュ、ローカルSocial BrainとTactical Decision。確認環境はPython 3.14.0。モデル設定を省略するとpingと観測キャッシュだけを利用できる。

## 状態キャッシュと観測

Phase 5の `/v1/snapshot`・`/v1/events`・`/v1/state` はモデル設定なしでも利用できる。起動引数とAPIキー設定は従来どおり。
モデルを呼ばずに現在状態を保持し、差分のバッチを検証して反映する。古い連番・重複・不整合は409を返して再同期を要求する。
キャッシュとイベント履歴はメモリ内のみで、Daemon再起動時に消える。MODが新しいセッションで自動的に再同期する。

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8767/v1/state -Method Post -ContentType application/json -Body '{"version":1}' | ConvertTo-Json -Depth 8
```

状態には `stale`・最終更新からの秒数・sequenceを付ける。ゲームの一時停止中も更新が止まるため、15秒経過でstaleになる。
キャッシュから判断するには `/v1/decision` へstateの代わりにsessionを指定する。staleまたはCompanionが未読込なら409を返す。
この場合もモデルへ送るのは目的と必要な数値状態だけで、所持品全体・イベント履歴・セッションID・会話は送らない。操作の自動実行はしない。

キャッシュは最大32セッション・各100イベント。新しい観測による古いセッションの追い出しと、読み出し時のコピーでメモリ量・外部からの改変を制限する。
観測APIの入力は32KiB、その他は8KiBまで。仕様とfixtureは [Protocol README](../protocol/README.md) を参照。

## Tactical Decision

`--decision-config` でSocialとは別に有効化する。`decision.example.json` はLLM不要のmock。
実モデル設定を `decision.local.json`（Git管理外）へ作る場合:

```json
{
  "provider": "local",
  "base_url": "http://127.0.0.1:1234/v1",
  "model": "unsloth/gemma-4-26b-a4b-it",
  "api_key_file": "../.tools/social-api-key.txt",
  "timeout_seconds": 15
}
```

APIキーは `MCAI_DECISION_API_KEY` 環境変数を優先し、未設定なら `api_key_file` から読む。ファイルパスは設定ファイルからの相対パス。
今回のローカル検証ではLM Studioの同じキーを利用するが、Socialとは別のキー・モデル・接続設定にもできる。

```powershell
python agent/src/daemon.py --port 8767 --config agent/config.local.json --decision-config agent/decision.local.json
```

`POST /v1/decision` は各回2メッセージ（固定systemと匿名化されたゲーム目的・状態）だけを送る。
会話・判断履歴は保持せず、自然言語のreasoningも要求しない。`response_format: json_schema`、`reasoning_effort: none`、256トークン、temperature 0を指定する。
生成結果はサーバーのschema保証に依存せずDaemonでも検証する。タイムアウト・不正出力時は503で、操作も自動再試行も行わない。
JSON schemaをサポートするローカルサーバーが必要。[LM Studioの仕様](https://lmstudio.ai/docs/developer/openai-compat/structured-output)。

ログはprovider・model・入出力サイズ・遅延・action・固定reasonCodeだけで、入力JSON本文やキーは出さない。
テストは `python -m unittest discover -s agent/tests -v`。26件で会話と判断の分離、観測の同期、HTTP、出力検証、失敗系を確認する。

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
