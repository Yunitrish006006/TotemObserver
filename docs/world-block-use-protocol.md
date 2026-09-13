# World block use protocol 1

The account-v1 hello adds `worldBlockUseProtocol:1` when player admission is
available (`0` otherwise). Existing clients ignore this additive field. `play`
remains false. This narrow capability allows only empty-hand vanilla lever and
stone-button use; it grants no container, inventory, entity or read-only Screen
input access. See [the driver](world-block-use-driver.md) for Minecraft rules.

## Request and response

Exactly seven request keys; no target coordinates, face, raw ID or item claims:

```json
{"type":"world_block_use","protocol":1,"seq":7,"sessionEpoch":42,"subscriptionId":1,"revision":1,"dimension":"minecraft:overworld"}
```

The existing flat JSON parser enforces at most 1024 characters, canonical
unsigned integers, no duplicate keys or recursive values. Sequence is 0 through
999999999, epoch is a positive JSON-safe integer, subscription/revision are
positive bounded integers, and dimension is an identifier of at most 128 chars.
Unknown keys, versions and malformed values close the connection.

The response contains exactly nine keys:

```json
{"type":"world_block_use","protocol":1,"seq":7,"sessionEpoch":42,"subscriptionId":1,"revision":1,"dimension":"minecraft:overworld","outcome":"applied","refreshRequired":true}
```

Outcomes are `applied`, `no_target`, `unsupported`, or `denied`. Only `applied`
requires refresh. Ordinary unsupported targets and denied gameplay conditions
are nonfatal responses; stale session/admission/bindings, queue expiration or
transport/protocol violations close the connection. Responses are bound to the
request's original subscription/revision, not a newly invented mesh identity.

## Ownership, lifecycle and bounds

The exchange owns shared monotonic sequence checks, exact session epoch and
current bootstrap dimension/subscription/revision checks. At most one use is
pending, with a 200 ms ingress interval and a five-second response deadline.
Use cannot overlap pending movement, section or bootstrap requests. Those
requests also cannot start while use is pending. Registry reads and independent
world-state snapshots remain read-only and may continue.

Admission marshals the request to the Minecraft server thread, rejects inputs
older than 250 ms, and rechecks active admission, current authentication/session
and actual player dimension through the authorization predicate used by the
driver. It replaces retained movement intent with idle before use; normal
server physics continues. The driver raycasts the server's current eye/view,
checks real held stacks and calls vanilla game-mode use. No client XYZ enters
the action. Disconnect/revocation releases admission and cancels deadlines.

## World refresh barrier

After an applied action, section, movement and further use requests fail closed
until a successful new bootstrap. The client must drain prior work before use,
clear old geometry on `refreshRequired:true`, request bootstrap, and rebuild its
bounded cache under that new revision. A successful bootstrap advances revision
in the same dimension, or starts a new subscription after a dimension change.
Old section responses cannot be outstanding across the use action. No new cache
or queue is allocated by this protocol; existing bounded cache ownership stays
with the visible-world controller, never the renderer.

This is a snapshot refresh barrier, not a general live block-update stream.
Delayed vanilla effects (such as a button releasing or redstone updates later)
still need subsequent snapshot refresh. The upcoming client integration must
address periodic world changes and render unsupported partial geometry honestly.

## Validation and non-goals

`ObserverBlockUseRequestTest` exercises exact schema and hostile scalar/target
input. The registered `ObserverBlockUseProtocolGameTest` uses real authenticated
WebSockets and a real admitted ServerPlayer to verify lever mutation, bound
acknowledgments, post-use stale section rejection, new-revision continuation,
and hostile epoch/subscription/revision/dimension/sequence/version/XYZ rejection.
Dedicated suite count increases from 18 to 19. Complete Build, 3-JVM E2E and
Runtime Validation evidence belongs in the stacked PR after CI completes.

This slice exposes the server protocol. Browser input/ack handling, partial-block
visuals, selected-slot/inventory contracts, mining, placement, entities and
containers follow separately. It does not claim those are already playable.
