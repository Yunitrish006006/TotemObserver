# Observer visible-world view policy

This client-side policy is the bridge between individual `world_section` requests and a future renderer. It automatically chooses a small, bounded set of section snapshots around the authoritative Observer player and feeds them through `WorldSectionScheduler`.

It does not change the wire protocol and does not enable gameplay. `play` remains `false`.

## Preconditions

A plan exists only while all of the following are true:

- an authoritative `world_state` has been accepted;
- an authoritative `world_bootstrap` has been accepted;
- `worldSectionProtocol: 1` and the registry/bootstrap prerequisites make section requests available;
- `world_state.dimension` exactly matches `world_bootstrap.dimension`;
- the player's authoritative Y coordinate maps to a section inside the bootstrap dimension height.

If any prerequisite disappears, automatic section scheduling stops and the current plan is dropped.

## Normal radius-2 plan

For the normal bootstrap radius of two chunks, the plan contains at most 43 unique sections:

1. the player's vertical section across the center 3×3 chunks — 9 sections;
2. one section below across the center 3×3 chunks — 9 sections when inside the dimension;
3. one section above across the center 3×3 chunks — 9 sections when inside the dimension;
4. the remaining outer ring of the player's vertical section inside the bootstrap 5×5 window — 16 sections.

Total: `9 + 9 + 9 + 16 = 43` sections. At the bottom or top of a dimension the unavailable vertical layer is omitted, reducing the target count to 34. Smaller bootstrap radii are respected and produce smaller plans.

This 43-section ceiling fits below the scheduler's 64 queued-section budget and avoids downloading the full vertical dimension. A normal Overworld bootstrap with 24 vertical sections and 25 horizontal chunks would otherwise expose up to 600 section coordinates.

## Priority

Each horizontal square is ordered center-first by Manhattan distance from the bootstrap center. Ties use chunk Z and then chunk X for deterministic ordering.

The local 3×3 player layer is therefore requested first, followed by the local layer below, the local layer above, and finally the distant radius-2 ring. This makes the first cached volume useful for a future nearby-world renderer before bandwidth is spent on the outer edge.

## Revision ownership

A plan is identified by:

- `subscriptionId`;
- `revision`;
- authoritative player `anchorSectionY`.

The controller only refills the scheduler when this context changes. Successful section snapshots and `world_section_unavailable` markers remain owned by `ObserverConnection` and are already bound to the current bootstrap revision.

When a newer bootstrap revision is accepted, the section scheduler clears old active/queued work, ObserverConnection invalidates old section results, and the view controller computes a fresh plan. A future live player-state update that crosses a vertical section boundary will likewise produce a new plan because `anchorSectionY` changes.

## Progress

The controller exposes four bounded counters for UI/debugging:

- target sections;
- resolved sections;
- available section snapshots;
- unavailable/not-loaded sections.

`resolved = available + unavailable`. Unavailable sections count as terminal only for the exact current revision and do not cause retry loops.

## Non-goals

This policy does not provide:

- block rendering or textures/models;
- biome or lighting data;
- heightmaps or block entities;
- entity/player rendering;
- live block/chunk deltas;
- continuous movement-driven world-state polling;
- movement, collision, interaction, inventory actions or gameplay.

The next rendering slice can consume this bounded cache to build a debug geometry/occupancy view without changing server authority or world-request bounds.
