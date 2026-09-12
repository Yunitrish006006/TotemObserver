# Observer world registry contract v1

This contract is the decoding boundary between the authenticated Observer session and future chunk/section payloads. It does not stream chunks by itself and does not enable gameplay; `play` remains `false`.

## Capability

Authenticated `hello` advertises `worldRegistryProtocol: 1` only when real Minecraft player admission is available. The capability depends on the same server-owned player identity/session used by `worldBootstrapProtocol` and `worldStateProtocol`.

## Request

```json
{"type":"world_registry","seq":4,"offset":0}
```

`seq` participates in the connection-wide strictly increasing request sequence. `offset` is a raw `Block.BLOCK_STATE_REGISTRY` id and must identify an existing state. The server returns at most eight consecutive states beginning at that id.

## Response

```json
{
  "type":"world_registry",
  "protocol":1,
  "seq":4,
  "sessionEpoch":1,
  "fingerprint":"64-lowercase-hex-sha256",
  "offset":0,
  "total":12345,
  "states":["minecraft:air","minecraft:stone"]
}
```

The `states` array is positional: `states[i]` describes raw state id `offset + i`. Canonical state strings use the block identifier followed by properties sorted by property name, for example `minecraft:oak_log[axis=y]`.

`fingerprint` is SHA-256 over every canonical state string in raw-id order, each followed by a zero byte. It identifies the complete server block-state table for the current runtime. A client must not reuse cached numeric mappings across sessions unless the fingerprint matches exactly.

## Intended chunk integration

Future chunk/section snapshots may transmit compact raw block-state ids. The client should group missing ids into small lookup requests and cache successful pages by `(registryFingerprint, rawStateId)`. Registry pages are intentionally demand-driven; login must not transfer the complete block-state table.

## Invariants

- registry ids are server-owned and never accepted from the browser as definitions;
- the registry is immutable for the lifetime of the running server;
- every response is bound to the current `sessionEpoch` and request `seq`;
- page size is capped at eight states;
- malformed offsets, stale sessions, malformed fingerprints or invalid canonical state strings fail closed;
- no chunk, biome, light, block-entity, entity, movement or interaction payload is part of protocol v1.
