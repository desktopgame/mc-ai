# known_issue
v0.1.0時点での既知の問題、将来の改善候補をここに記載する。

1. **Forge terminal dedupeの完全identity化は未完**
   
   Daemon側の正式identityは

   ```text
   (daemonEpoch, session, skillInstanceId, terminalId)
   ```

   ですが、Forgeの `seenTerminals` / `TerminalDeliveryState` など一部はまだ `terminalId` 単体をkeyにしています。

   reset時に `seenTerminals.clear()` するので通常運用上は問題になりにくく、terminalIdも実質一意ですが、厳密には新bindingで同じterminalIdを再利用した場合に `TerminalDeliveryState` 側のclosed stateが残る可能性があります。

   これは「既知の設計上の制限」として残してよいです。

2. **pending terminal ACKは最大32件**
   
   これはすでに文書化済みですね。

   32件超の異常backlogではACKをdropするため、

   ```text
   表示は済んでいる
   しかしhistory登録 / outbox closeが欠落
   ```

   し得ます。

   Skill再実行や二重表示はしないので、MVPとしては妥当です。

3. **Daemon epoch変更時の旧terminal再表示**
   
   protocolには「同じworld/ownerなら旧作業と明示したfallback」という案がありますが、現状未実装です。

4. **Social terminalの異常系は実ゲーム未検証のものがある**
   
   基本の

   ```text
   Skill完了
   → terminal表示
   → displayed ACK
   → history登録
   ```

   は実機確認済み。

   一方、

   ```text
   12秒fallback
   forget中生成
   owner/world退出競合
   presentなしfallback
   ACK backlog
   ```

   あたりは主に自動テストです。

5. **一部Gameplay異常系も実機未検証**
   
   たとえば、

   - `inventory_full`
   - `owner_inventory_full`
   - pickup/deposit中の `path_not_found`
   - 一部のmine tool / wall / leaves条件
   - 長時間安定動作

   あたり。
