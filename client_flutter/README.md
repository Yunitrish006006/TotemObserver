# Observer 多人網頁客戶端

目前完成本機帳號連線介面與第一段 Minecraft 玩家接入：建立帳號、登入、確認連線、登出與失聯處理，並由伺服器為每個帳號核發固定玩家 UUID / profile name 與每次登入更新的 session epoch。登入成功後，26.2 專用伺服器會建立真正的 `ServerPlayer` 並加入原版 `PlayerList`，使用原版 playerdata 保存與重載。Flutter 仍不接收區塊、實體、資源或光照，也不送出移動、挖放方塊、背包等遊戲操作，因此 `play:false` 維持不變；帳號登入不會取得 `/observeui`、OP 或額外遊戲權限。

## 啟動

專用伺服器使用此開發中的 Observer 原始碼建置，JVM 參數置於 `-jar` 前：

```text
-Dtotem.observer.bridge.enabled=true
-Dtotem.observer.bridge.registration=true
-Dtotem.observer.bridge.port=25580
-Dtotem.observer.bridge.origin=http://localhost:8080
```

建立帳號預設關閉；服主啟用 registration 後才可從網頁註冊。帳號資料位於伺服器的 `config/totem-observer/accounts-v1.properties`，是含隨機鹽值的密碼雜湊，不是明文密碼。玩家 UUID/profile 由帳號名稱在伺服器端固定導出；成功 admission 後由原版 `PlayerList` 載入及保存該固定身分的 playerdata。

在此目錄執行：

```sh
flutter pub get --enforce-lockfile
flutter run -d web-server --web-hostname 127.0.0.1 --web-port 8080
```

開啟 `http://localhost:8080`，伺服器位址填 `ws://127.0.0.1:25580/observer/bridge`。Origin 必須與伺服器設定完全相符。接口仍只監聽 loopback；公網部署、TLS、世界資料串流與真正遊戲操作是後續階段。

## 會話與資料

- 帳號為 3–24 個小寫英數或底線，`version` 保留；密碼為 12–128 個 UTF-16 code units，最多 64 個帳號。
- `totem-observer-account-v1` 使用 1024-byte frame；既有 `totem-observer-bootstrap-v1` 維持原有 256-byte/ping-only 契約。
- 握手後 10 秒內登入；會話最多 15 分鐘。登入同一帳號會撤銷上一條連線。
- 支援 player identity contract 時，登入成功會回傳伺服器核發的固定玩家 UUID、固定 `obs_...` profile name 與當次 session epoch；瀏覽器不能提交或覆寫這些欄位。
- 專用伺服器會宣告 `playerAdmission:true`；只有真正 `ServerPlayer` admission 完成後才回傳 `playerAttached:true`。Flutter 會拒絕宣告 admission 能力卻沒有成功 attach 的不相容回應。
- admission 使用原版 `PlayerList.canPlayerLogin`、`placeNewPlayer` 與 `remove`，保留 ban、whitelist、IP ban 與容量等原版政策；bridge 仍只使用 loopback transport address。相同帳號 replacement 會先保存舊玩家，再以同一固定 UUID/profile 重載 playerdata；舊 socket 的延遲清理不能移除新玩家。
- 目前 attached 玩家只建立伺服器端生命週期；Observer 的虛擬連線丟棄 world/clientbound gameplay packets，只在伺服器內部回答 vanilla keepalive。區塊/實體/光照串流與玩家輸入尚未實作，因此 authenticated 回應仍固定 `play:false`。
- 登出、斷線、逾時與停服撤銷會話並移除/保存已接入玩家；沒有 bearer token、自動恢復或瀏覽器長期登入資料。網頁送出後清空密碼欄，取消待處理的連線不會讓延遲回應重新登入。
- 網頁每 5 秒發送 heartbeat，12 秒未收到有效回應即離線。伺服器原有連線數、frame 速率與 idle 上限仍生效。
- 一個獨立工作執行緒處理密碼，佇列最多 8 筆，服務整體每分鐘最多 12 次嘗試；Minecraft tick 與 Netty event loop 不執行雜湊/檔案寫入。
- 密碼雜湊固定為 JDK PBKDF2-HMAC-SHA256、600,000 次、16-byte salt、32-byte hash；使用帶版本的原子檔案替換。關閉服務會等待執行中的寫入結束，佇列工作清除密碼後跳過存取，避免重啟時舊寫入覆蓋新資料。工作因子參考 [OWASP PBKDF2 說明](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2)。

## 驗證

```sh
flutter analyze
flutter test
flutter build web --release --no-web-resources-cdn
```

真實瀏覽器測試使用編譯好的網頁與 test-only Java 帳號接口：

```sh
# 在 TotemObserver 根目錄
./gradlew prepareAccountFixture
cd client_flutter/browser_tests
npm ci
npx playwright install chromium
npm test
```

`JAVA_HOME` 必須是 Java 25；可用 `CHROME_BIN` 指定既有 Chromium。瀏覽器測試自動建立隔離帳號檔，不連正式 Minecraft 世界；畫面與結果寫入 `build/account-browser-evidence/`。GitHub `Build` workflow 共用一次 Java/Flutter 建置與上述固定測試，不另建重複工作流程。Server GameTest 另驗證真正 `PlayerList` admission、固定玩家 replacement、vanilla playerdata 保存/重載與 stale release 安全性；GameTest 專用容量調整只存在 test mod，不進 production mixin。

繁中字型隨網頁附上，避免首次載入缺字。[Noto Sans TC 上游](https://github.com/google/fonts/tree/main/ofl/notosanstc) 的 SIL Open Font License 一併放在 `assets/fonts/OFL.txt`。目前介面已能顯示伺服器核發的玩家身分與「已接入 Minecraft」狀態，但仍沒有重建 Minecraft 世界畫面或宣稱可從瀏覽器遊玩。
