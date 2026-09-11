## Initial standalone release

- Move Observer View into its own module with `/observeui <player>` and `/observeui stop`.
- Server-authoritative spectator sessions, semantic vanilla Screen reconstruction, remote cursor synchronization and read-only input/packet suppression.
- Discover module-owned Screen providers through TotemCore; target and observer must advertise matching family and protocol identities. Supports Nexus protocols 3 and 4.
- No framebuffer, screenshot or video transport. Private drafts and secrets remain excluded.
- Install on the server and participating clients with Fabric API and TotemCore >=0.7.18 <0.8.0. VanillaTweaks is optional; if present, use 0.1.28 or newer.

## 首次獨立版本

- Observer View 拆為獨立模組，使用 `/observeui <玩家>` 開始、`/observeui stop` 停止觀察。
- 伺服器授權旁觀模式、原版畫面語意重建、遠端游標同步與唯讀輸入／封包限制。
- 透過 TotemCore 使用各模組擁有的畫面 provider；雙方必須使用相符協定，包含 Nexus protocol 3 與 4。
- 不傳截圖、影像串流或 framebuffer，不同步私人草稿與機密。
- 伺服器與參與的客戶端皆須安裝 Fabric API 與 TotemCore >=0.7.18 <0.8.0。VanillaTweaks 為選配；並裝時需 0.1.28 以上。

Minecraft 26.2 / Fabric / Java 25. Project review status is independent of version upload status.
