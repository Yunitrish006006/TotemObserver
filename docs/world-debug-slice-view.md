# Observer raw BlockState debug slice

This view is the first visual consumer of Observer's server-authoritative section cache. It is intentionally a diagnostic renderer, not a Minecraft graphics renderer, and `play` remains `false`.

## What it shows

The view renders one horizontal block layer at the authoritative player's current integer block Y. It uses the current visible-world plan's anchor section and composes the bootstrap window up to radius two:

- radius 2: 5×5 chunks = 80×80 block cells;
- radius 1: 3×3 chunks = 48×48 block cells;
- radius 0: one 16×16 chunk.

Only section snapshots already accepted for the exact current `(subscriptionId, revision)` are read. The view never initiates network requests itself; scheduling remains owned by `WorldVisibleViewController` and `WorldSectionScheduler`.

## Cell semantics

Each available cell contains the raw block-state ID from the accepted `WorldSectionSnapshot` at:

```text
blockY = floor(authoritative worldY)
sectionY = floor(blockY / 16)
localY = blockY - sectionY * 16
```

The painter asks the existing registry cache for the canonical state name. It treats only these names as empty background:

- `minecraft:air`
- `minecraft:cave_air`
- `minecraft:void_air`

It never assumes a particular raw numeric ID means air. Other states are assigned deterministic debug colors from their raw ID. If a state name has not been fetched yet, the raw ID color still renders, so the debug view does not block on full registry pagination.

## Partial cache states

A chunk can be in three visual/cache states:

- **available** — its section snapshot contributes 16×16 cells;
- **unavailable** — the current revision received `world_section_unavailable`; the chunk is shown as unavailable rather than retried;
- **pending** — no terminal result exists yet.

The player position is projected into the grid from authoritative `worldX/worldZ` and drawn only when it falls inside the current bootstrap window.

## Safety and invalidation

`WorldDebugSliceSnapshot.create` rejects stale inputs unless:

- world state and bootstrap are both present;
- world-state and bootstrap dimensions match;
- the visible plan's subscription/revision match the current bootstrap;
- the plan's anchor section still matches authoritative player Y.

A bootstrap revision or subscription change therefore cannot mix old section data into a new debug slice.

## Non-goals

This slice does not implement:

- Minecraft block textures or block models;
- transparent/cutout material rules beyond identifying vanilla air names;
- biome tinting;
- sky/block light;
- heightmaps;
- block entities;
- entities or player models;
- camera perspective or 3D geometry;
- live block/chunk deltas;
- movement, collision, interaction or gameplay.

Its purpose is to prove that real server block-state content can be composed into a coherent spatial view before a production renderer is introduced.
