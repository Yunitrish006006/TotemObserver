# Hotbar protocol v1

Hello advertises optional `worldHotbarProtocol:1` with admission support, otherwise
0. Unknown/absent capabilities are not permission to send operations. `play:false`
and read-only Screen transport remain unchanged.

`world_hotbar` requests have exactly seven keys for a read: `type`, `protocol:1`,
shared monotonic `seq`, `sessionEpoch`, `subscriptionId`, `revision`, `dimension`.
Adding exactly one integer `slot` in 0–8 requests selection. A read never changes
selected slot or stops use of the held item. No request accepts item content,
counts, target, components or inventory arrays. The strict scalar parser retains
its 1024-character ceiling and rejects unknown keys, duplicates and coercion.

The connection checks authenticated admitted identity, epoch, current bootstrap
subscription/revision and dimension, a 200 ms minimum interval, one pending
hotbar request and a five-second deadline. Pending movement, block use, outline,
bootstrap or mandatory world refresh rejects hotbar operations. The reverse
operations also wait for hotbar completion. Existing section reads can finish;
new section requests wait. Renderer/scheduler ownership is unchanged.

The admission service executes on the Minecraft server thread, validating current
authorization/admission/dimension and the original request's 250 ms age before
and after the operation. The driver also checks current PlayerList identity,
loaded connection, life/game mode and inventory-menu state. Reads snapshot nine
slots without invoking vanilla selection; selection uses the previously tested
vanilla carried-slot handler, with preflight and post-event bounded capture.

Responses contain exactly ten keys: the seven read keys, `outcome`, `selected`,
`slots`. Success is `snapshot` for a read or `selected` for a selection, with a
server-selected integer 0–8 and exactly nine `{item,count}` objects. Item IDs
are canonical identifiers up to 256 characters; counts are 0–999. A normal
driver denial returns `denied` with both selected and slots null, nonfatally.
Invalid/stale/expired requests, revoked bindings or unrepresentable snapshots
close the connection. The response ceiling is 4096 characters. No NBT, custom
names, lore, book text or other private item components are projected.

There is no hotbar cache on the server. Later client consumers must treat the
acknowledgment as server truth, never optimistically replace inventory, and
invalidate pending/visible state on disconnect or binding changes. This slice
adds no key bindings, UI, item use/placement, inventory clicking or containers.
Loaded-player requirements also apply to reads; a client must handle a nonfatal
denial before its initial movement completes loading the admitted player.

Validation: strict-schema unit tests and two registered dedicated tests cover
real read/selection semantics, normal denial, hostile binding/schema, replay,
expiry and revocation. Expected complete count is 26. Rate/overlap timing is
inspected, not independently proven by the wire fixture. Actual CI results are
recorded in the stacked PR.
