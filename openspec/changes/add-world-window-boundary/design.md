## Context

Step 3D has an authenticated `ServerPlayer` and a one-time world bootstrap, but no chunk-stream ownership boundary. The next stable decision should identify chunks without prematurely committing to block palettes, lighting encodings, resource delivery or a renderer. The final client must also support optional Totem modules and future modded registry content.

## Goals / Non-Goals

- Goals: establish a bounded, versioned chunk-identity window tied to the active session and dimension; give Flutter a renderer-independent `WorldState`; preserve namespaced identity for modded dimensions/content.
- Non-Goals: transmit chunk block states, biomes, heightmaps, lighting, block entities, entities, resources, movement or gameplay actions; choose a final chunk payload codec; set `play:true`.

## Decisions

- Decision: `worldWindowProtocol: 1` is additive and only advertised when the server also exposes the admitted-player world bootstrap.
- Decision: the initial window is server-authoritative radius 1 (3x3 identities) centered on the chunk containing the bootstrap pose.
- Decision: `world_window` carries `sessionEpoch`, namespaced `dimension`, `centerChunkX`, `centerChunkZ`, bounded `radius`, and monotonic `revision`.
- Decision: chunk identities are derived as `(dimension, x, z)` keys. Future chunk snapshots must reference the same identity plus the active window revision before entering cache.
- Decision: block/entity/resource data is deliberately absent. Future content codecs must use stable namespaced registry identifiers rather than vanilla-only enums or Java class names.
- Alternative considered: send a literal array of chunk coordinates. Rejected because a rectangular bounded window is smaller, deterministic and sufficient for the first control-plane contract.
- Alternative considered: start with block palettes now. Rejected because that would commit Step 4 data-model decisions before registry/resource/module extension requirements are proven.

## Risks / Trade-offs

- The radius-1 window is not a useful render distance. It is intentionally a protocol/control-plane acceptance slice.
- A future moving player needs increasing window revisions and cache eviction. Revision exists now so that extension can be additive.
- A dimension change will require a new bootstrap/world-state lifecycle before cross-dimension chunk payloads are accepted.

## Migration Plan

Older clients that do not understand `worldWindowProtocol` continue to reject or ignore unsupported capabilities according to their protocol rules. The current Flutter client is updated in the same Draft PR as the server capability. No persisted world/player format changes.

## Open Questions

- Exact Step 4 chunk section/palette encoding.
- Registry snapshot/delta strategy for vanilla plus modded content.
- Resource-pack/model delivery and compatibility caching.
