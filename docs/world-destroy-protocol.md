# Block destruction protocol v1

Account-v1 hello adds `worldBlockDestroyProtocol:1` only when real player admission
is available, otherwise zero. Existing capabilities remain additive and `play:false`
is unchanged. This slice exposes mining to a compatible client; Flutter mouse input
and mesh refresh wiring follow separately.

## Requests and replies

Exact request keys (no target, item, block state, elapsed time or claimed progress):

```json
{"type":"world_block_destroy","protocol":1,"seq":7,"sessionEpoch":42,"subscriptionId":1,"revision":1,"dimension":"minecraft:overworld","operation":7,"action":"start"}
```

`action` is exactly `start`, `hold` or `cancel`. START's operation ID equals its
sequence; HOLD/CANCEL name an earlier START sequence while their own sequence is
strictly newer. IDs/sequences use canonical unsigned JSON integers in 0..999999999.
The current authenticated session epoch, subscription, revision and dimension must
match exactly. Dimension is a resource identifier up to 128 characters. Strings in
numeric fields, duplicate/extra/missing keys, unknown actions/versions, nested values,
noncanonical numeric forms or mismatched bindings fail closed.

A reply has exactly 13 keys: `type`, `protocol`, `seq`, `sessionEpoch`, `subscriptionId`,
`revision`, `dimension`, `operation`, `action`, `outcome`, `progress`,
`worldMayHaveChanged`, `refreshRequired`. It acknowledges the current request and
original operation under the original world binding. Outcomes are `active`, `changed`,
`denied`, `cancelled`; progress is a finite server-derived float in [0,1]. No client
coordinate/BlockState claim participates in mutation. The encoded reply is <=1024
characters. The request retains the existing <=1024-byte/frame and parser bounds.

## Scheduling, authority and lifecycle

Only one mining request may be pending, mutually exclusive with pending movement,
use, hotbar, outline, bootstrap and section work. START/HOLD have an 80 ms minimum
interval in addition to the transport's existing bounded global pacing. CANCEL may
arrive immediately after START; it still obeys single-pending, operation identity,
monotonic sequence, binding and global frame limits. The result deadline is five
seconds. The admission owner rejects requests older than 250 ms and gives held input
a 500 ms lease. The lease uses receipt time, cannot revive after expiry, and never
lets the client claim progress. The existing pre-vanilla tick lifecycle derives
actual progress and revalidates the authoritative target/permissions/player/item.

An atomic operation binding is invalidated before bootstrap or release reaches the
server queue, and after acknowledging a terminal. The START predicate stays attached
to the original operation; fresh HOLD input cannot replace it. Server-side completion
or expiry can happen with no pending request. The owner retains that terminal until
a subsequent HOLD/CANCEL observes it or an explicit bootstrap consumes it.

Movement can continue between mining requests; server look/reach/position changes
can cancel the current target. Section requests, block use and hotbar requests are
blocked while an operation remains unconsumed; the compatible client must coordinate
these through its existing controller/scheduler. Renderer ownership does not change.

## World and inventory synchronization

START can invoke vanilla attack/enchantment behavior even before a block is removed.
`worldMayHaveChanged` therefore remains monotonic through cancellation/expiry and is
not synonymous with `outcome:changed`. Active replies do not force a full bootstrap
mid-dig. Every terminal sets `refreshRequired:true`, including a no-mutation denial,
so the owner is consumed before another START. The client must obtain bootstrap and
refresh relevant world/inventory projections before resuming actions. Old section
requests are rejected during mining and behind the terminal refresh barrier.

Explicit bootstrap is also allowed to cancel an active operation, invalidating its
lease before snapshot capture. It consumes the old result and advances the normal
revision; no old response is relabeled. A new operation uses a new START sequence and
binding. Disconnect/replacement removes the admitted player and owned mining state.

## Non-goals and validation

No auto-next-block, predictive destruction, arbitrary mutations, item placement,
entity attack or container capability is added. Observer read-only Screen transport
remains unchanged. World meshes and sections are not persisted by this protocol.

JUnit covers strict schema/number/action/version/operation parsing without Minecraft registry
bootstrap. Registered Fabric GameTest uses real authenticated WebSockets and admitted
players to cover survival completion, cancellation/dirty retention, active and terminal
bootstrap, natural lease expiry and terminal retrieval, stale sections, old operations,
old revisions, forged coordinates, mismatched session/subscription/dimension. The dedicated count increases from 31 to 32. Formal Build, Observer
3-JVM E2E and Observer Runtime Validation are required; validation does not claim Flutter
mining input until the next client slice is connected.
