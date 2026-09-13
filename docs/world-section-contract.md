# Observer world section contract v1

This is the first real world-content payload. It transfers exactly one immutable 16×16×16 block-state snapshot at a time. It does not stream live changes and does not enable gameplay; `play` remains `false`.

## Capability and prerequisites

Authenticated `hello` advertises `worldSectionProtocol: 1` only when real Minecraft player admission is available. A client may request a section only after it has accepted both:

- a `world_bootstrap` for the current `(sessionEpoch, subscriptionId, revision)`; and
- registry page zero establishing the current `registryFingerprint` and `registryTotal`.

## Request

```json
{
  "type":"world_section",
  "seq":7,
  "subscriptionId":1,
  "revision":2,
  "chunkX":12,
  "chunkZ":-4,
  "sectionY":3
}
```

The request uses the connection-wide strictly increasing `seq`. The requested chunk must be inside the bootstrap radius, `sectionY` must intersect the advertised dimension height, and the subscription/revision must exactly match the server's most recently issued bootstrap. The server never loads or generates a chunk solely to satisfy Observer; only an already-loaded chunk may be sampled.

## Successful response parts

One successful request produces exactly four text frames, each carrying 1024 block states:

```json
{
  "type":"world_section",
  "protocol":1,
  "seq":7,
  "sessionEpoch":42,
  "subscriptionId":1,
  "revision":2,
  "registryFingerprint":"64-lowercase-hex-sha256",
  "dimension":"minecraft:overworld",
  "chunkX":12,
  "chunkZ":-4,
  "sectionY":3,
  "part":0,
  "parts":4,
  "stateCount":1024,
  "data":"base64-encoded-unsigned-varints"
}
```

`data` is the concatenation of 1024 canonical unsigned VarInts, then standard padded Base64. Raw state IDs are capped below 1,000,000, therefore every encoded ID occupies at most three bytes and every part's Base64 payload is at most 4096 characters. No WebSocket fragmentation is required.

## Loaded-chunk unavailable response

A request that is otherwise valid can race with Minecraft chunk unloading. If the exact requested chunk is not loaded when the server-thread snapshot executes, the bridge does not force-load or generate it and does not close the authenticated Observer session. Instead it sends one terminal response:

```json
{
  "type":"world_section_unavailable",
  "protocol":1,
  "seq":7,
  "sessionEpoch":42,
  "subscriptionId":1,
  "revision":2,
  "registryFingerprint":"64-lowercase-hex-sha256",
  "dimension":"minecraft:overworld",
  "chunkX":12,
  "chunkZ":-4,
  "sectionY":3,
  "reason":"not_loaded"
}
```

The response is bound to the same request sequence, session epoch, subscription/revision, registry fingerprint, dimension and coordinates as a successful section. `reason` is currently restricted to `not_loaded`. It is valid only when no successful part for that request has been accepted.

The Flutter client may cache this unavailable result only for the exact current subscription/revision. It must not retry that coordinate repeatedly within the same revision. A newer accepted bootstrap revision, a new subscription or disconnect invalidates both successful section snapshots and unavailable markers, so a later world view may try the coordinate again.

Invalid request bounds, stale subscription/revision, concurrent section requests, revoked sessions, mismatched metadata and malformed frames remain protocol errors and fail closed. The nonfatal response is only for an otherwise valid request whose loaded-chunk lookup returns no chunk.

## Block order

The complete section contains 4096 states in this fixed order:

```text
index = (localY * 16 + localZ) * 16 + localX
```

`part 0` contains indices `0..1023`, part 1 contains `1024..2047`, part 2 contains `2048..3071`, and part 3 contains `3072..4095`.

A client commits a section snapshot only after all four distinct parts have passed validation. Duplicate parts, malformed/non-canonical VarInts, out-of-range state IDs, wrong coordinates, stale subscription/revision/session, or a mismatched registry fingerprint fail closed.

## Snapshot semantics

- sampling happens on the Minecraft server thread;
- only the exact requested section is copied;
- the section is immutable once copied into the response;
- an accepted newer bootstrap revision invalidates all previously cached successful and unavailable section results;
- dimension changes require a new subscription, so old section frames cannot be mixed into the new world;
- missing/unloaded chunks produce `world_section_unavailable` rather than being force-loaded/generated or terminating the valid session.

## Non-goals

Protocol v1 contains block states only. Biomes, sky/block light, heightmaps, block entities, entities, chunk deltas, movement, interaction and rendering are deliberately outside this slice.
