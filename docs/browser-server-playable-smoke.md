# Real browser / Minecraft movement and block-use smoke

`client_flutter/browser_tests/playable.mjs` launches the built Flutter web client in
Chromium and a dedicated Minecraft server with the separate `browserFixture`
source set. The Build workflow runs this after the existing account and DOM input
fixtures, using the same pinned TotemCore artifact and release web build.

The browser registers through the production account bridge and controls the
admitted vanilla ServerPlayer through actual pointer lock, keyboard and mouse
events. WebSocket listeners only observe messages; they do not intercept or
replace them. The test checks walking into a two-block-high wall, jumping and
landing, strafing around the wall, crossing a chunk boundary, acknowledged mouse
look, matching server correction, and logout removal from PlayerList. Before
walking, it aims at a fixture lever using real mouse deltas and authoritative
corrections, presses E, checks the exact intent-only request and bound applied
response, verifies the real server lever is powered, and waits for a newer
bootstrap revision. No interaction protocol frame is injected.

The fixture initializes a bounded stone floor and wall in a fresh flat world,
sets the vanilla world respawn position and disables randomized respawn radius.
It places a wall-mounted lever away from the walking path and explicitly prepares
empty main/offhand stacks once for this test player. That preparation is isolated
fixture setup, not a production inventory behavior; real players' onboarding
items must never be deleted to make use succeed.
This is test setup, not a gameplay operation. No test movement teleports a player.
Only the test mod may load terrain for setup; production render requests remain
loaded-chunk-only. The source set is absent from the production JAR and has no
GameTest entrypoint, so this smoke does not change the dedicated GameTest count.

The fixture samples ServerPlayer state on the server thread every two ticks and
atomically replaces a bounded JSON evidence file. Its lifetime is at most 3,600
ticks. Node also enforces startup and operation deadlines and stops its dedicated
process group on failure. The browser capture retains only bounded movement and
bootstrap observations and at most nine use requests/replies, never account
frames or passwords. The server evidence also records lever power and empty-hand
state. `after-block-use.png` records the browser after the refresh acknowledgment;
it does not prove faithful rendering of the lever's partial geometry.

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
physics evidence. This smoke covers the narrow empty-hand lever-use interaction.
It does not validate mining, placement, entity interactions, inventory/container
behavior, faithful vanilla visuals, or enable `play:true`.
