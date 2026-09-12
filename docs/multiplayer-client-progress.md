# 多人網頁客戶端開發進度

最終目標：Flutter 網頁作為只有多人模式、操作能力對齊 Java 客戶端的遊戲介面；Observer 安裝在伺服器內作為雙向中轉站。Minecraft 執行世界與模組邏輯，網頁負責呈現與輸入。正常天空光/方塊光、日夜、維度亮度、動態光照更新與客戶端亮度設定均在範圍內。

Totem 全系列是最終相容範圍，包括 Core、Observer、Alchemy、VanillaTweaks、Nexus、Remnant、Automata、Excavation、Enchanting、Villagers、Locksmith，及之後納入套件的模組。逐項驗收自訂內容、正式介面、操作、權限、多人同步、存檔與資源呈現；未驗證的項目不能視為支援。

## 開發順序

| 步驟 | 交付 | 狀態 |
| --- | --- | --- |
| 1 | Observer 內嵌雙向連線、版本握手、限額與生命週期；固定自動測試 | 已實作，驗證結果見下 |
| 2 | 自訂帳號、可撤銷連線會話；Flutter 最小連線介面 | 已實作，驗證結果見下；遊戲角色留在步驟 3 |
| 3 | 固定 26.2 玩家接入與遊戲資料契約；確認瀏覽器核心相容性 | 尚未實作 |
| 4 | 區塊/實體/資源/光照同步及網頁 3D 顯示 | 尚未實作 |
| 5 | 移動、校正、挖放方塊與兩人互動 | 尚未實作 |
| 6 | 背包、合成、容器、戰鬥、死亡/重生與維度生命週期 | 尚未實作 |
| 7 | 逐模組完整 Totem 相容；解決正式 Screen 渲染契約 | 尚未實作 |
| 8 | 亮度對照、裝置/效能、完整部署與版本相容性驗收 | 尚未實作 |

步驟 1 不決定後續採原生 Minecraft 封包轉接或專用遊戲協定。`bootstrap-v1` 是短期傳輸驗證，不承諾為未來 play 協定的相容基礎。原生客戶端 26.2 支援與正式 Screen 問題仍依 [可行性調查](flutter-client-feasibility.md) 處理。

## 步驟 1 使用方式

以下是保留的 bootstrap-v1 傳輸測試。現在另有 account-v1 登入流程，啟動與使用方式見 [Flutter 客戶端說明](../client_flutter/README.md)。

bootstrap 接口預設關閉，只能監聽 `127.0.0.1`；bootstrap-v1 仍只允許 ping，不包含帳號、玩家、世界資料或操作權限。origin 比對不是帳號驗證。

在專用 Minecraft 伺服器 JVM 啟動參數加入（置於 `-jar` 前）：

```text
-Dtotem.observer.bridge.enabled=true
-Dtotem.observer.bridge.port=25580
-Dtotem.observer.bridge.origin=http://localhost:8080
```

Origin 必須為明確的本機 HTTP origin（localhost 或 127.0.0.1 加連接埠）；預設如上。伺服器啟動後開啟接口，停止時釋放接口與連線。設定錯誤或連接埠占用只停用 bootstrap，不中止 Minecraft。單人整合伺服器不會啟動接口。

在 origin 相符的本機網頁中，可使用標準瀏覽器 WebSocket API：

```js
const socket = new WebSocket(
  'ws://127.0.0.1:25580/observer/bridge',
  'totem-observer-bootstrap-v1',
);
socket.onmessage = ({data}) => {
  const message = JSON.parse(data);
  if (message.type === 'hello') {
    socket.send(JSON.stringify({type: 'ping', seq: 0}));
  }
};
// 使用完畢：socket.close(1000, 'done');
```

握手回應 `{"type":"hello","protocol":1,"capabilities":["ping"],"play":false}`。
接收 `{"type":"ping","seq":0}` 後回應 `{"type":"pong","seq":0}`。
本階段只接受上述固定欄位順序、無空白的訊息格式；seq 為 0–999999999 的整數，單次連線嚴格遞增，重連重新開始。其他訊息關閉連線，不轉送到 Minecraft。

限制：16 個 TCP 連線（含未握手）、5 秒初始握手期限、15 秒未讀到資料關閉、256 bytes/frame、每秒 32 frames（含 WebSocket 控制 frame）、禁止分片與二進位業務訊息、有限寫入緩衝。沒有 HTTP 管理頁面、代理任意地址、token 或 payload 記錄。

## 固定驗證與結果

`ObserverBridgeServerTest` 使用真實本機 TCP/WebSocket：握手、hello/pong、錯誤 origin/協定、重複序號、非法遊戲操作、超大/二進位/分片訊息、關閉及重新綁定、占用連接埠、非法數字、連線數、握手與 idle 逾時。控制 frame 限額使用 Netty EmbeddedChannel 邊界測試。

`ObserverBridgeGameTest` 在專用 Minecraft runtime 中以背景網路工作完成真實 hello/pong，不在 tick 上等待網路，也不需要客戶端 Screen。這驗證 runtime 類別相容與交換；不代表公網、TLS、Flutter 渲染或帳號功能通過。

既有 `Build` workflow 的 `test` 與 `runGameTest` 自動執行新測試，不另建立重複 workflow。原有 Observer Screen 與 owner API 未更動；完整客戶端/3-JVM 回歸仍由既有 owning workflows 負責。本次未推送或發佈，不能宣稱遠端 CI 已驗證本機改動。

2026-09-11 本機結果：Java 25、Core 0.7.18；24 項單元測試通過，其中 11 項為 bridge 測試；專用 Minecraft 26.2 runtime 的 7 項 GameTests 全數通過，包含新增 bridge hello/pong。獨立審查與修正後複查通過；bind 失敗清理與非法 JSON 序號已修正並有回歸測試。

環境限制：標準 `runGameTest` 曾在執行測試前因共用 Fabric API 快取的 `named`/`official` namespace 不符而失敗。隔離重測在 `build/bridge-verified-api/` 使用 35 個替換 JAR：29 個由完整 API 包抽出並比對原快取路徑預期 SHA-1，6 個由官方 Maven 下載並比對官方 SHA-1。使用原 GameTest 的 JVM 參數與相同測試類別，透過暫存 init-script 建立 `runVerifiedBridgeGameTest` 執行；沒有修改共用快取。此結果是有明確相依輸入的本機替代證據，不宣稱原快取或遠端 CI 已修復。

## 步驟 2 驗證

2026-09-12：31 項 Java 單元測試通過（含 7 項帳號測試與 11 項 bootstrap 測試）；專用 Minecraft 26.2 runtime 的 8 項 GameTests 全數通過，包含帳號註冊與 authenticated pong。runtime 沿用上節具 SHA-1 證據的隔離相依替換，未修改共用快取。網路 GameTest 使用 20 秒實際時間上限，避免加速 tick 在密碼雜湊完成前誤判逾時。

Flutter analyze、5 項 Flutter UI/controller 測試與 release web 建置通過。真實 Chromium 對 release 網頁完成註冊、登入、pong、登出、錯誤密碼與停服撤銷共 6 項檢查。首次視覺檢查發現繁中字型載入延遲，已改用隨網頁提供的 Noto Sans TC；桌面與手機介面已檢查繁中字形。手機版加入 360 像素頁面無水平外溢的固定斷言；Flutter 內部處理捲動，截圖採實際視窗尺寸。證據位於 `build/account-browser-evidence/` 與 `build/account-validation-evidence.json`。

獨立審查要求補上帳號寫入的關閉等待與瀏覽器 heartbeat 回應期限，已修正並通過複查；測試 fixture 與 CI 串接另經只讀審查。沒有變更既有 Observer 正式 Screen、遊戲操作權限或生產世界；尚未提交、推送或發佈。
