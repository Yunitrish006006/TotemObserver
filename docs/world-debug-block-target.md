# Debug block targeting data

`WorldDebugBlockRaycast` traverses cached voxels from an eye origin using
Minecraft yaw/pitch conventions. It returns the nearest supported debug block,
entry face, distance, raw state and exact subscription/revision section key.
The immutable result is a render suggestion, never an authoritative gameplay hit.

Traversal is limited to six blocks and 32 cells. It advances through known air
only; missing or mismatched sections, unknown registry entries and unsupported
geometry stop the ray. Fluids, cutout/translucent blocks, partial blocks and
waterlogged states cannot be targeted or penetrated. The mesher's existing
explicit terrain policy is shared; `canOcclude` and `opaqueLike` do not establish
geometry. Raw ID zero has no special meaning.

Negative coordinates use floor division. Cross-chunk and cross-section lookups
must return the exact requested key. An origin inside a block or a ray crossing
an exact voxel edge/corner or lying on a boundary plane produces no target, avoiding an invented entry face
or traversal through an ambiguous gap. Invalid/nonfinite camera or reach data
returns no target before accessing the cache.

The caller owns camera/session/dimension validity and supplies snapshots and
registry names from one synchronous current-world view. Do not retain targets
across corrections, disconnects, dimension changes or revisions. This data layer
owns no network request or retained cache. Scene integration must additionally
require the hit face to exist in the currently rendered bounded mesh, so mesh
truncation cannot produce an invisible selection.

No interaction packet is introduced by this slice. A later server interaction
contract must independently raycast from its current player state and validate
dimension, reach, line of sight, current blocks, permission and game mode. The
six-block debug limit grants no server reach allowance. `play:false` and the
read-only Screen transport remain unchanged.

Unit coverage includes all six directions, nearest face, negative/cross-section
coordinates, missing/stale data, unsupported states, reach edges, malformed
camera, ambiguous corners and work bounds. Formal Build/E2E/runtime results are
recorded in the stacked PR after completion.
