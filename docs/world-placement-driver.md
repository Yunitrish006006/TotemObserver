# Vanilla held-block placement groundwork

`ObserverBlockPlacement` is an internal, server-thread-only driver. This slice
does not enable a network capability or change empty-hand block-use v1. A later
versioned admission/exchange slice must provide authenticated sequence and
world binding checks, input age/rate limits, idle movement, cancellation of
mining, bounded acknowledgment and mandatory synchronization after possible
mutation. `play:false` and the read-only Screen firewall remain unchanged.

## Initial scope

The actual server main-hand stack must be the exact vanilla dirt, stone,
cobblestone or oak-planks item, backed by the ordinary `BlockItem` class.
Offhand must be empty; an open gameplay container is denied. The server ray
chooses the support block and hit face. Supports are exact dirt, grass block,
stone, cobblestone, bedrock or oak planks; this is a gameplay capability
allowlist, not a render-geometry classification. Containers, other interactive
supports, complex items, replacements, fluids and redirected placement
contexts remain unsupported in this groundwork. Broader normal item use and
container actions require subsequent capabilities rather than removal of the
Observer read-only boundary.

Both support and adjacent destination must be already loaded, within build
height and world border, outside spawn protection, and permitted by the
player/level. Destination must be air. Checks precede `BlockPlaceContext`
construction because that constructor reads clicked state. Its chosen
position must equal the validated destination and it must not replace the
support. Server targeting supplies current admission identity, life/dimension
state, finite eye/view, vanilla outline/reach and bounded loaded-only reads.

Before action, the driver rechecks authorization, original level/game-mode
owner and mode, selected slot, stack identity and full components/count,
offhand, cooldown, target states and position permissions. It calls normal
`ServerPlayerGameMode.useItemOn` with the real server stack. Vanilla owns
collision, adventure item predicates, placement callbacks and survival versus
creative item consumption. The driver never inserts blocks or decrements
inventory itself.

## Result and failure behavior

`APPLIED` requires the actual destination to contain the held BlockItem's
block after vanilla returns; `consumesAction()` alone is not treated as proof.
Once vanilla is entered, `worldMayHaveChanged` is true even if placement is
denied, because callbacks can have effects. A transport owner must synchronize
world and inventory whenever this flag is true, and fail closed by retiring
admission if the action throws. Preflight rejection has no mutation claim.
No queue, persistent history or cache is allocated by the driver.

Loaded checks guarantee preflight does not fetch/generate missing chunks.
They do not claim that the complete vanilla callback/neighbor-update path has
no other chunk access. Those updates are normal Minecraft gameplay semantics;
the renderer still cannot load chunks or mutate the world.

## Validation

Two registered Fabric GameTests exercise real survival placement and one-item
consumption, creative non-consumption, authorization/held-stack invalidation,
adventure denial, player collision, container-support rejection and offhand/
unsupported-item boundaries, cooldown and component/slot changes, border-separated
support/destination, out-of-height eyes and missing-world rejection without
loading the missing chunk. Registry-dependent tests run in the dedicated
server, not plain JUnit. Formal suite count becomes 34 once this slice is
validated; exact results and remaining validation evidence belong in its PR.

The base `MinecraftServer.isUnderSpawnProtection` used by GameTest returns
false in 26.2. These GameTests therefore do not prove dedicated-server spawn
protection behavior. The driver calls the server's actual policy, and dedicated
protection plus action-exception admission cleanup must be covered before a
network capability invokes this driver. They are not waived by this internal
groundwork or by green generic GameTests.
