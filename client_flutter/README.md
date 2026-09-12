# Observer 多人網頁客戶端

目前完成本機帳號連線介面：建立帳號、登入、確認連線、登出與失聯處理，並由伺服器為每個帳號保留固定玩家 UUID / profile name 與每次登入更新的 session epoch。尚未建立真正 Minecraft 玩家、接入世界或開放遊戲操作；帳號登入不會取得 `/observeui`、OP 或任何遊戲權限。

## 啟動

專用伺服器使用此開發中的 Observer 原始碼建置，JVM 參數置於 `-jar` 前：

```text
-Dtotem.observer.bridge.enabled=true
-Dtotem.observer.bridge.registration=true
-Dtotem.observer.bridge.port=25580
-Dtotem.observer.bridge.origin=http://localhost:8080
```

建立帳號預設關閉；服主啟用 registration 後才可從網頁註冊。帳號資料位於伺服器的 `config/totem-observer/accounts-v1.properties`，是含隨機鹽值的密碼雜湊，不是明文密碼。玩家 UUID/profile 由帳號名稱在伺服器端固定導出，但正式 Minecraft 玩家存檔仍未接入。

在此目錄執行：

```sh
flutter pub get --enforce-lockfile
flutter run -d web-server --web-hostname 127.0.0.1 --web-port 8080
```

開啟 `http://localhost:8080`，伺服器位址填 `ws://127.0.0.1:25580/observer/bridge`。Origin 必須與伺服器設定完全相符。接口仍只監聽 loopback；公網部署、TLS、帳號管理及真正遊戲角色 admission 是後續階段。

## 會話與資料

- 帳號為 3–24 個小寫英數或底線，`version` 保留；密碼為 12–128 個 UTF-16 code units，最多 64 個帳號。
- `totem-observer-account-v1` 使用 1024-byte frame；既有 `totem-observer-bootstrap-v1` 維持原有 256-byte/ping-only 契約。
- 握手後 10 秒內登入；會話最多 15 分鐘。登入同一帳號會撤銷上一條連線。
- 支援 player identity contract 時，登入成功會回傳伺服器核發的固定玩家 UUID、固定 `obs_...` profile name 與當次 session epoch；瀏覽器不能提交或覆寫這些欄位。`play` 仍為 `false`。
- 登出、斷線、逾時與停服撤銷會話；沒有 bearer token、自動恢復或瀏覽器長期登入資料。網頁送出後清空密碼欄，取消待處理的連線不會讓延遲回應重新登入。
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

`JAVA_HOME` 必須是 Java 25；可用 `CHROME_BIN` 指定既有 Chromium。測試自動建立隔離帳號檔，不連正式世界；畫面與結果寫入 `build/account-browser-evidence/`。GitHub `Build` workflow 共用一次 Java/Flutter 建置與上述固定測試，不另建重複工作流程。

繁中字型隨網頁附上，避免首次載入缺字。[Noto Sans TC 上游](https://github.com/google/fonts/tree/main/ofl/notosanstc) 的 SIL Open Font License 一併放在 `assets/fonts/OFL.txt`。這是登入與玩家身分前置介面，沒有新增 Minecraft Screen、建立遊戲玩家或聲稱重建正式遊戲畫面。
