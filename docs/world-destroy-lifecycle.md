# Admission-owned mining lifecycle

This slice connects the internal vanilla destruction driver to admission ownership
and actual player ticks. It does not expose a WebSocket mining operation or change
`play:false`. Transport negotiation, exact request schema, sequence/subscription/
revision binding and browser input are the next slice's responsibility.

## Tick ordering and ownership

Each admitted player can own one mining operation, including one retained terminal
result. A bounded lookup keyed by those players dispatches only Observer mining.
The mixin runs immediately before `ServerPlayerGameMode.tick()` inside
`ServerPlayer.tick()`. It does not add another physics/game-mode tick. Ordinary
players with no registered mining operation continue unchanged.

A global START_SERVER_TICK callback would leave level tick work between validation
and vanilla's block access. The per-player hook instead rechecks authorization,
loaded target, ray, permissions, held stack and lease immediately before that access.
It completes through the existing driver's vanilla STOP only at progress >= 1.
Both active/delayed flags are cleared before vanilla continues after completion or
cancellation. Unexpected exceptions suppress this Observer player's remaining tick;
the failed operation cannot continue silently. Admission removal clears ownership.

Registration and START are separate: admission retains the owner before START can
mutate. A failed action tears down admission, even if cleanup also fails. Cleanup
attempts happen before removing the player; the final lookup removal requires the
player no longer be admitted, so uncleared state never becomes unguarded gameplay.

## Intent lifetime and terminal results

Internal START/HOLD/CANCEL carry an operation ID in 0..999999999. Incoming requests
must be at most 250 ms old, never from the future. START seeds a 500 ms held lease;
HOLD may renew it from its original receipt time only while the old lease is still
alive. A late heartbeat cannot revive an expired operation. Wrong operation IDs
cannot affect a replacement. Driver progress uses server game-mode ticks, never
client elapsed time or claimed progress.

The original authorization predicate and dimension remain attached to the operation.
The future transport must bind that predicate to the original session/subscription/
revision and invalidate it on lifecycle changes. A fresh HOLD predicate cannot
replace the START binding. One terminal result remains until explicit synchronization;
new START cannot overwrite it. Movement may continue; block use and hotbar selection
cancel active mining. Hotbar reads do not cancel it.

The possible-world-mutation flag is monotonic from START through cancellation,
expiry or completion. A caller must not interpret CANCELLED as “nothing changed”.
Bootstrap closes the operation before taking a fresh world-window snapshot and is
the explicit terminal-consumption boundary. The future wire must clear stale section,
mesh and inventory projections through this synchronization path; it cannot re-label
an old terminal with a new revision. Release/replacement/service close remove the
player and both admission/dispatch ownership. No operation survives reconnect.

## Bounds, failures and non-goals

There is one operation, one target and one result per admitted player, no history or
pending operation queue. Existing admission capacity and original targeting bounds
apply. Tick callbacks never perform network requests. No chunks are force loaded for
mining and no browser state directly mutates blocks or inventory. Transport request
frequency/deadlines still require separate enforcement before this internal API is
exposed. There is no auto-next-block, client progress prediction, container capability
or read-only Screen firewall change.

## Validation

Registered dedicated tests cover actual hook-driven survival completion and terminal
retention, operation identity, expiry without revival, and admission slot/bootstrap/
release ownership. The ordering regression puts vanilla delayed destruction one tick
before completion, expires the lease, then calls real `ServerPlayer.tick()` and
checks the block survives. An injection after game-mode tick would fail that test.
The dedicated suite count increases from 28 to 31. Full Build, Observer 3-JVM E2E and
Observer Runtime Validation own formal regression validation. Independent review
required separating ownership from potentially mutating START; implementation and
coverage were revised accordingly.
