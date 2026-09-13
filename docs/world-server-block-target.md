# Server block targeting foundation

`ObserverBlockTargeting.pick` runs only on the Minecraft server thread and derives
the ray from the current ServerPlayer eye position and view vector. It accepts no
client XYZ, face, hit location, raw state or block identifier. The admission owner
must provide a current session/admission authorization predicate; it is checked
before and after raycasting. The exact player must still be in PlayerList.

Reach is the current vanilla block-interaction attribute, capped at six blocks.
Vanilla `BlockGetter.clip` uses `ClipContext.Block.OUTLINE` and `Fluid.NONE`, matching
the inspected Minecraft 26.2 `Entity.pick` path. Thus slabs and other partial shapes
retain vanilla outline behavior; descriptor/name/canOcclude heuristics are absent.
Dead, removed, spectator, sleeping, mounted or dimension-changing players fail
closed. Hits must remain within reach and world border, outside the eye-origin
interior, and pass vanilla `ServerPlayer.mayInteract`.

The clip receives a loaded-only BlockGetter, including shape neighbor queries.
It uses `getChunkNow` and the existing live block-entity map, never a chunk
loading/generation accessor or lazy block-entity promotion. Minecraft 26.2's
`getBlockEntity(..., CHECK)` still promotes pending NBT, so it is deliberately
not used. Absent/removed block entities needed by shape queries invalidate the
ray. Each invocation permits 256 reads within eight blocks
per axis of the eye voxel. A missing chunk, out-of-build-height query, excess read
or out-of-window query invalidates the entire result. Temporary barrier/empty
sentinels stop vanilla traversal but can never become accepted hits. The view and
its counters are discarded after the synchronous call.

This helper performs no interaction or mutation and introduces no network
capability. An interaction endpoint still needs exact-key/type/range/version and
sequence checks, session/subscription/revision/dimension binding, rate limiting,
current admission, and the normal game-mode interaction path. Do not retain a hit
across ticks or use it to bypass later permission/item checks. `play:false` and
Observer read-only Screen transport remain unchanged.

A registered Fabric GameTest verifies a real ServerPlayer, nearest block and
face, revocation, partial slab outline, server reach, spectator denial,
read-budget exhaustion, no pending block-entity NBT promotion and a
missing chunk that remains unloaded. Dedicated GameTest count increases from 16
to 17; formal CI must confirm execution. Minecraft bootstrap-dependent tests are
not placed in plain JUnit.
