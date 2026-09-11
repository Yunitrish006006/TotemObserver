# TotemObserver

Watch another player's supported Minecraft screens and cursor while observing their gameplay. TotemObserver provides a read-only view for multiplayer assistance, demonstrations and administration.

## Features

- Use `/observeui <player>` to start; press Escape or use `/observeui stop` to finish.
- Start from any game mode. Observation temporarily uses spectator mode and restores your previous mode and location when it ends.
- Administrators can observe while Observer is enabled. A world rule can also allow mutually accepted TotemCore friends to observe each other.
- Supported vanilla interfaces use their native Minecraft screens. Compatible Totem modules provide their own views; unsupported interfaces remain unavailable.
- The observer cannot click, type or interact on behalf of the watched player. Private drafts and secrets are excluded from the shared view.

## Installation

For Minecraft **26.2**, Fabric Loader **0.19.3+** and Java **25+**. Install **Fabric API**, **TotemCore 0.7.18 or newer within 0.7.x**, and **TotemObserver** on the server and participating clients.

TotemVanillaTweaks is optional. If installed together, use **TotemVanillaTweaks 0.1.28 or newer**; older releases contain the previous embedded Observer implementation. Other Totem modules are optional and must support compatible Observer views on both clients.

## World rules

`/gamerule totem:observer_enabled true` enables Observer. Set it to `false` to stop all observation sessions.

`/gamerule totem:observer_allow_friends true` enables observation between mutual TotemCore friends. This is disabled by default. Removing a friendship or disabling access ends the session.

## 繁體中文

TotemObserver 可讓你在觀察其他玩家時，看見對方受支援的遊戲介面與游標，適合多人教學、協助操作與管理用途。觀察畫面為唯讀，不能代替對方點擊或輸入。

使用 `/observeui <玩家>` 開始，按 Escape 或輸入 `/observeui stop` 結束。不必事先切換旁觀模式；系統會暫時切換，結束時還原原模式與位置。

管理員可在功能開啟時觀察玩家。世界規則 `totem:observer_allow_friends` 可額外開放雙向確認的 TotemCore 好友觀察，預設關閉。`totem:observer_enabled` 可開啟或關閉整個功能。

伺服器與參與的客戶端都需要 Fabric API、TotemCore 0.7.18 以上且低於 0.8.0，以及本模組。支援 Minecraft 26.2、Java 25。若同時安裝 TotemVanillaTweaks，請使用 0.1.28 以上版本。

Source and issue tracking are available on [GitHub](https://github.com/Yunitrish006006/TotemObserver). Licensed under Apache-2.0.
