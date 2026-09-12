## Why

The current world bootstrap proves that Flutter can receive one authoritative world snapshot, but it does not define which chunks belong to the browser's current world view. Defining that control plane before block/entity payloads prevents the first chunk codec from becoming a vanilla-only compatibility commitment.

## What Changes

- Add an additive `worldWindowProtocol: 1` capability beside `worldProtocol: 1`.
- Send an initial bounded `world_window` containing the authenticated session epoch, namespaced dimension, center chunk, radius and monotonic revision.
- Add Flutter `WorldState`, `WorldWindow` and `ChunkKey` models so chunk identity is independent from rendering and future payload codecs.
- Derive a 3x3 initial window around the admitted player's authoritative bootstrap position without sending block, entity, resource or lighting data.
- Preserve `play:false`; this change adds no browser gameplay input.
- Keep future chunk payloads registry-driven so Totem and other modded content can use namespaced identifiers without replacing the control-plane contract.

## Impact

- Affected specs: `world-stream-boundary`
- Affected server code: authenticated exchange and world bootstrap control plane
- Affected Flutter code: connection state plus `world/` protocol models
- Validation: JUnit, dedicated-server GameTest, Flutter tests/build, Chromium flow, 3-JVM and runtime validation
