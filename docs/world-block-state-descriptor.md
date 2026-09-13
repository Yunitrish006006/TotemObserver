# World block-state visual descriptor

The Observer client now has a small descriptor layer between the server-authored block-state registry and future geometry/material code.

## Inputs

The descriptor accepts only the registry data already authenticated by the existing world-registry contract:

- raw block-state ID
- canonical block-state string such as `minecraft:oak_stairs[facing=north,half=bottom,waterlogged=true]`

It parses the namespaced block ID and immutable property map without changing the wire protocol.

## Exact classifications

Only information that can be established directly from the canonical registry entry is marked `exact`:

- vanilla `air`, `cave_air`, and `void_air`
- vanilla `water` and `bubble_column` as water-like fluid entries
- vanilla `lava` as a lava-like fluid entry
- explicit state properties such as `waterlogged=true`

Missing or malformed registry entries remain `unknown`. A numeric ID is never assumed to be air or any other material by itself.

## Heuristic render hints

Known non-air/non-fluid blocks receive a conservative render hint (`opaqueLike`, `cutoutLike`, or `translucentLike`) from the block path. Those hints are explicitly marked `heuristic`.

They are intended only to bootstrap Observer diagnostic geometry and material grouping. They are **not** authoritative Minecraft rules and must not be used as collision, occlusion, redstone, interaction, or gameplay truth.

A later production renderer should replace these hints with model/resource data that reflects the actual server/client resource set.

## Non-goals

This slice does not add block models, textures, model baking, resource packs, lighting, biome tint, shape-aware culling, collision, entities, movement, or interaction. It does not change the server protocol. `play` remains `false`.
