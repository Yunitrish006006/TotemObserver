# Observer world-bootstrap v1 contract

Date: 2026-09-13

## Decision

World bootstrap v1 is the first Route B contract that describes a future chunk subscription without transporting any chunk, section, block-state, biome, lighting, or entity payload.

The admitted vanilla `ServerPlayer` remains authoritative. The browser may request a bootstrap or resync, but it does not choose the dimension, vertical bounds, subscription center, radius, subscription identity, or revision.

`play` remains `false`.

## Capability

Authenticated `hello` adds:

```json
{"worldBootstrapProtocol":1}
```

Protocol `1` is advertised only when real player admission is available. Identity-only account fixtures advertise `0`.

## Request

After `authenticated` with `playerAttached:true`, the client may send:

```json
{"type":"world_bootstrap","seq":0}
```

`seq` shares the account-v1 monotonic request sequence with `ping` and `world_state`. Only one bootstrap request may be in flight per connection.

Sending another bootstrap request after a completed response is the v1 resync mechanism.

## Response

```json
{
  "type":"world_bootstrap",
  "protocol":1,
  "seq":0,
  "sessionEpoch":123,
  "subscriptionId":1,
  "revision":1,
  "dimension":"minecraft:overworld",
  "minY":-64,
  "height":384,
  "centerChunkX":0,
  "centerChunkZ":0,
  "radius":2
}
```

The response fields are server-authoritative:

- `sessionEpoch` binds the bootstrap to the authenticated play session.
- `subscriptionId` identifies the current dimension subscription within that session.
- `revision` versions resyncs within the subscription.
- `dimension`, `minY`, and `height` define the dimension envelope that future section/chunk payloads must match.
- `centerChunkX` and `centerChunkZ` are the player's server-side chunk center at capture time.
- `radius` is the Observer-owned subscription radius. v1 fixes it to `2`; this is not the vanilla client render-distance setting.

## Versioning and resync rules

1. First bootstrap in a dimension starts a new subscription with `revision:1`.
2. A bootstrap resync while the player remains in the same dimension keeps the same `subscriptionId` and increments `revision`.
3. If the authoritative dimension changes, the server allocates a new, higher `subscriptionId` and resets `revision` to `1`.
4. Re-authentication creates a new `sessionEpoch`; subscription identifiers from an older session are therefore unusable even if their numeric values match.
5. Future chunk/section messages must carry enough identity to be rejected when they belong to an older session, subscription, or revision.

The Flutter client already enforces the initial `revision:1`, monotonically increasing resync revisions, immutable dimension bounds/radius within one subscription, and increasing subscription identifiers when the subscription changes.

## Authority and threading

`ObserverPlayerAdmissionService.bootstrap(...)` captures the dimension identifier, vertical bounds, and center chunk only on the Minecraft server thread. Netty never reads `ServerPlayer`/`ServerLevel` fields directly.

The authenticated exchange revalidates account session, play session, and player admission after the asynchronous server-thread capture and before sending the response. A replacement or revoked admission cannot complete an old bootstrap.

## Acceptance for this slice

- Dedicated GameTest captures valid dimension bounds and chunk center directly from an admitted vanilla player.
- Real WebSocket GameTest requests bootstrap twice and verifies `subscriptionId:1` with revisions `1` then `2` before the existing world-state/heartbeat path.
- Flutter automatically requests bootstrap after admission and strictly validates session/request/schema/version fields.
- Flutter exposes `resyncWorld()` and accepts a same-subscription revision increase while rejecting an invalid initial revision.
- Existing `world_state` remains independent and `play:false` is unchanged.

## Not included

This contract does not transport registry entries, chunks, sections, block states, biomes, light arrays, heightmaps, entities, time/weather, movement, collision, or interaction. The next slice can now define the first bounded chunk/section payload keyed to this bootstrap identity.
