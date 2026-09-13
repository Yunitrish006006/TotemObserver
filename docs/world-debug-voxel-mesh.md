# Debug voxel face extraction

This slice turns an authenticated `WorldSectionSnapshot` into a conservative set of diagnostic cube faces. It is a bridge from the raw BlockState slice toward a 3D debug view; it is **not** a Minecraft block-model implementation.

## Face rule

A face is emitted only when all of the following are known:

1. the source snapshot is supplied from the current subscription/revision;
2. the source raw state ID resolves to a canonical registry entry;
3. the source entry is not exact vanilla air;
4. the neighboring cell is available from the same revision, including across section/chunk boundaries; and
5. the neighboring raw state ID resolves to exact vanilla air.

This deliberately under-renders uncertain geometry. Missing neighbor sections and unresolved registry IDs suppress faces instead of inventing surfaces.

## Boundary validation

Cross-section neighbors are accepted only when their `subscriptionId`, `revision`, chunk coordinates and section Y exactly match the requested adjacent section. Stale or mismatched snapshots are treated as missing.

## Material metadata

Each emitted diagnostic face carries:

- world/local block coordinates;
- raw BlockState ID;
- canonical namespaced block ID; and
- the visual render hint from `WorldBlockStateDescriptor`.

The render hint remains heuristic except for exact air/fluid facts. It is not collision or gameplay truth.

## Diagnostics and cost

The result separately counts unresolved source cells, faces suppressed because a neighbor state is unknown, and faces suppressed because the required neighboring section is missing or stale. These are face counts, not distinct section counts.

Block-state descriptors are cached by raw state ID for the duration of one section build so repeated palette states are parsed once per mesh operation.

## Non-goals

The mesher does not reconstruct block models, stairs, slabs, fences, plants, fluid heights, resource-pack geometry, texture UVs, lighting, biome tinting, ambient occlusion, collision, entities, movement or interaction. It does not issue network requests or change the wire protocol. `play` remains `false`.
