# Block-state visual metadata groundwork

This slice derives minimal, immutable visual metadata beside the existing server-authoritative block-state registry. It deliberately does not change the WebSocket wire contract yet.

## Flags

Each raw block-state ID receives a small bit field:

- bit `0` (`VISUAL_AIR`) — the authoritative Minecraft `BlockState` reports `isAir()`
- bit `1` (`VISUAL_CAN_OCCLUDE`) — the authoritative Minecraft `BlockState` reports `canOcclude()`

The flags are stored in the same raw-ID order as `Block.BLOCK_STATE_REGISTRY` and are paged with the same page boundaries as canonical block-state names.

## Important semantics

`canOcclude` is **not** treated as a promise that a state is a full cube, uses a cube model, is opaque in every render layer, or has a full collision shape. It is only server-derived metadata that later rendering work may use as one input.

The existing registry fingerprint continues to hash canonical state names only. Visual metadata is deterministic auxiliary data derived from the same immutable registry snapshot, so this groundwork does not change section payload identity or cache keys.

## Validation

Unit coverage walks the complete block-state registry and verifies that every paged visual flag matches the corresponding authoritative `BlockState.isAir()` and `BlockState.canOcclude()` values, that air and occluding states are both present, and that no state is simultaneously marked air and occluding.

## Next slice

The next slice may extend `world_registry` pages with these visual flags in a backwards-compatible field and teach Flutter to cache them. Only after the client has server-derived shape/render metadata should Observer attempt safe voxel meshing.
