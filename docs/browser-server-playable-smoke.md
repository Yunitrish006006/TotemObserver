# Real browser / Minecraft movement smoke

`client_flutter/browser_tests/playable.mjs` launches the built Flutter web client in
Chromium and a dedicated Minecraft server with the separate `browserFixture`
source set. The Build workflow runs this after the existing account and DOM input
fixtures, using the same pinned TotemCore artifact and release web build.

The browser registers through the production account bridge and controls the
admitted vanilla ServerPlayer through actual pointer lock, keyboard and mouse
events. WebSocket listeners only observe messages; they do not intercept or
replace them. The test checks walking into a two-block-high wall, jumping and
landing, strafing around the wall, crossing a chunk boundary, acknowledged mouse
look, matching server correction, and logout removal from PlayerList.

The fixture initializes a bounded stone floor and wall in a fresh flat world,
sets the vanilla world respawn position and disables randomized respawn radius.
This is test setup, not a gameplay operation. No test movement teleports a player.
Only the test mod may load terrain for setup; production render requests remain
loaded-chunk-only. The source set is absent from the production JAR and has no
GameTest entrypoint, so this smoke does not change the dedicated GameTest count.

The fixture samples ServerPlayer state on the server thread every two ticks and
atomically replaces a bounded JSON evidence file. Its lifetime is at most 3,600
ticks. Node also enforces startup and operation deadlines and stops its dedicated
process group on failure. The browser capture retains only bounded movement and
bootstrap observations, never account frames or passwords.

Every run requires fresh `build/browser-playable/server` and `results` directories;
archive previous evidence before rerunning. Credentials remain in the private
test account store and are excluded from uploaded artifacts. Uploaded evidence is
limited to screenshots, the result summary and server startup log. Terrain
screenshots follow receipt of the complete below-player section and require
visual review; snapshot arrival is not by itself proof of visible geometry.

Local invocation after the normal pinned Flutter dependency/format/build steps:

```sh
cd client_flutter/browser_tests
npm run test:playable
```

Use Java 25 and optionally `TOTEM_CORE_JAR` for the pinned Core JAR. A failure is
not a playable result. The earlier `movement.mjs` remains a mock protocol fixture
for deterministic input lifecycle coverage and must not be cited as Minecraft
physics evidence. This smoke does not validate block/entity interactions,
inventory/container behavior, faithful vanilla visuals, or enable `play:true`.
