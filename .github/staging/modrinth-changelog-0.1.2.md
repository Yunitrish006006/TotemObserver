## Nexus 地圖細節同步

- 支援 TotemNexus 0.3.23 的 Nexus Observer provider v5。
- 觀察 Nexus 地圖時，伺服器會驗證 session、持圖身分與權限，再由 Nexus 透過原版地圖封包提供地形與細節。
- 正式地圖畫面保持唯讀，同步縮放、平移與遠端游標；不傳截圖或 framebuffer。
- 三程序整合驗證已更新為新 Nexus／Core 組合，並驗證 400% 細節畫面。

Minecraft 26.2 / Fabric。Observer 本身仍相容 Core 0.7.18 以上；搭配 Nexus 0.3.23 時請使用 Core 0.7.21 以上。
