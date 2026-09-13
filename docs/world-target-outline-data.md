# Server-derived target outline data

`ObserverTargetOutline.capture` is a server-thread-only internal data layer for
selection geometry. It first delegates target selection to `ObserverBlockTargeting`
using the actual player's eye, view, reach, permissions and current authorization.
It then reads that block's vanilla outline shape with `CollisionContext.of(player)`
through the same loaded-only view policy. It never uses canOcclude, block-name
heuristics, collision shape, textures or registry numeric order as geometry truth.

The immutable result contains one target position, hit face, raw BlockState ID,
and a copied list of block-local axis-aligned boxes. These boxes describe vanilla
outline shape, not necessarily model geometry or collision. They grant no right
to interact; the server must repeat targeting and validation on a later action.
The existing canonical-name registry fingerprint is unchanged.

## Bounds and failures

Shape grids are limited to 17 coordinates on each axis before box enumeration,
so at most 16 cubed grid cells are visited by vanilla's box decomposition. At most
16 boxes are retained; enumeration aborts when a seventeenth would be emitted.
No unbounded `toAabbs` list is allocated. Every bound must be finite, inside local
[-1, 2], and strictly ordered. Empty, oversized, out-of-range and over-budget
shapes return no outline. Unsupported partial geometry is never replaced by a
full cube.

Raycasting and shape capture each use a loaded-only `LoadedView` with 256 reads,
eight-block coordinate offsets and build-height checks. Missing chunks and
unavailable/pending block-entity data invalidate the result without loading,
generating or promoting anything. Authorization is checked again after geometry
capture. The returned lists and block position cannot change when the world
changes later. There is no cache, scheduler, network request or global retained
shape map in this layer.

## Validation and following integration

The registered `ObserverTargetOutlineGameTest` checks actual lever partial shape,
stone shape, raw state identity, immutable output across later world mutation,
revocation before/after shape capture, empty/out-of-range shapes, grid complexity
and box-count overflow. Dedicated count increases from 19 to 20. Minecraft
bootstrap-dependent coverage remains in Fabric GameTest, not ordinary JUnit.

This slice provides data only. Transport must bind a later response to the current
session, subscription/revision, registry fingerprint and authoritative camera.
Client consumption must bound arrays, discard stale evidence and invalidate it
on camera/world changes. Rendering can then display partial target outlines
without granting interaction authority. The renderer must not own network
requests. This is not a resource-pack/model pipeline, inventory/container support
or a change to play:false or read-only Observer Screen input.
