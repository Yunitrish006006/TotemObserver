# Debug voxel face extraction

This slice turns an authenticated `WorldSectionSnapshot` into a conservative set of diagnostic cube faces. It is a bridge from the raw BlockState slice toward a 3D debug view; it is **not** a Minecraft block-model implementation.

## Face rule

A face is emitted only when all of the following are known:

1. the source snapshot is supplied from the current subscription/revision;
2. the source raw state ID resolves to a canonical registry entry;
3. the source entry is one of the explicitly listed basic debug terrain blocks and is neither fluid nor waterlogged;
4. the neighboring cell is available from the same revision, including across section/chunk boundaries; and
5. the neighboring raw state ID resolves to exact vanilla air.

Source identity must equal the caller-provided current expected key before any registry or neighbor lookup. The immutable result carries that key; consumers must discard it on session/registry replacement, revision changes, cache eviction or source/neighbor replacement.

This deliberately under-renders uncertain geometry. Missing neighbor sections and unresolved registry IDs suppress faces instead of inventing surfaces.

## Boundary validation

Cross-section neighbors are accepted only when their `subscriptionId`, `revision`, chunk coordinates and section Y exactly match the requested adjacent section. Stale or mismatched snapshots are treated as missing.

## Material metadata

Each emitted diagnostic face carries:

- world/local block coordinates;
- raw BlockState ID;
- canonical namespaced block ID; and
- the visual render hint from `WorldBlockStateDescriptor`.

The render hint remains heuristic except for exact air/fluid facts. It is not collision or gameplay truth. Neither opaqueLike nor server canOcclude enables cube geometry. This debug-only allowlist includes basic stone/soil/sand terrain; arbitrary names, partial blocks, cutout, translucent, fluid and waterlogged sources are omitted. It is an explicit visualization approximation, not a resource-pack model guarantee. Unsupported non-air neighbors still suppress faces; they never count as known air.

## Diagnostics and cost

The result separately counts unresolved source cells, faces suppressed because a neighbor state is unknown, and faces suppressed because the required neighboring section is missing or stale. These are face counts, not distinct section counts.

Each build visits at most 4096 source cells and six neighbors per visited terrain cell, emits at most 8192 faces, and sets truncated when the cap prevents further output. Diagnostic counts then describe only visited work. Unsupported source cells have a separate counter. Output lists are immutable. No persistent mesh or descriptor cache is added.

Block-state descriptors are cached by raw state ID for the duration of one section build so repeated palette states are parsed once per mesh operation.

## Non-goals

The mesher does not reconstruct block models, stairs, slabs, fences, plants, fluid heights, resource-pack geometry, texture UVs, lighting, biome tinting, ambient occlusion, collision, entities, movement or interaction. It does not issue network requests or change the wire protocol. `play` remains `false`.


## Ownership and validation

The visible-world controller/scheduler remains the only section request owner.
Callbacks must read the current admitted connection cache and registry; they must
not fetch data. Dimension, session epoch and fingerprint remain connection-owned:
a mesh cannot outlive that connection/registry generation even if numeric section
keys repeat. There is no wire change and no server state mutation.

Tests cover exact-air exposure, unknown IDs including zero, missing/stale
neighbors, all six boundaries at negative coordinates, stale source identity,
unsupported geometry, immutable output and bounded checkerboard work.
Formal validation uses Build (including 11 registered dedicated GameTests),
Observer 3-JVM E2E and Observer Runtime Validation; actual run evidence belongs
in the stacked PR after completion.

Implementation lineage: retains the reviewed extraction and descriptor caching
from experimental commits eff8294, b350ec9 and fd5dbf0 plus their tests/docs.
Temporary workflow/formatter diagnostic commits are intentionally excluded.
