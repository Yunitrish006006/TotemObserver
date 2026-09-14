# Server-owned block destruction driver

This internal slice has no browser operation, capability advertisement or automatic
server tick hook. `play` remains false. The next protocol owner must supply exact
session/admission validation, bounded held-input leases and lifecycle cancellation.

## Ownership and vanilla semantics

One driver belongs to one admitted `ServerPlayer`, its original level and game-mode
instance. All calls require the Minecraft server thread. START derives a loaded-only
vanilla outline ray from the current authoritative eye and rotation; it accepts no
client coordinates, block ID, progress or item claims. Build height, border, spawn
protection, game-mode and player interaction restrictions apply.

The driver sends vanilla `START_DESTROY_BLOCK`, then checks the actual game-mode
state. START may attack or instantly destroy a block, so its result marks possible
world mutation even without a later completion. Survival progress comes from the
current vanilla `BlockState.getDestroyProgress`, actual game-mode ticks and original
start tick. The normal `ServerPlayer.tick()` owns `gameMode.tick()`; the driver must
never advance it again. Only progress >= 1 permits vanilla STOP. It does not call
`destroyBlock` directly, set blocks, fabricate drops or alter inventories.

STOP is completion, not release. Vanilla early STOP can schedule delayed destruction;
vanilla ABORT is reach gated and does not clear that delayed flag in Minecraft 26.2.
The minimal accessor therefore exposes progress identity and unconditional cleanup
of both destruction flags plus the crack stage. It injects no changes into ordinary
players' gameplay. Only this explicitly constructed Observer driver calls it.

## Lifecycle, bounds and failure

The eventual owner must invoke validation **before vanilla player ticks**, cancel on
release, disconnect, admission/session/subscription loss and replacement, and pass a
bounded heartbeat/deadman predicate each tick. Authorization, level/game-mode identity,
player-list identity, alive/loaded state, menu, reach/line of sight, loaded target,
unchanged block state, selected slot and full held stack are rechecked. Unexpected
vanilla target/start/delayed state cancels. A repeated call in one server tick cannot
complete a block sooner. Elapsed time is limited to 12,000 ticks; progress must be
finite and positive. Targeting retains its existing six-block/256-read loaded-only
bounds. No chunk is generated or force loaded for targeting.

Cancellation clears vanilla active and delayed destruction and the original level's
crack overlay before forgetting the one owned target. It cannot destroy a block.
Authorization callbacks must be side-effect-free current-state checks. Exceptional
failures during an owned operation cancel before propagation.

This driver stores one target/state/item copy, no world cache and no network queue.
The future wire owner must invalidate affected render data and refresh authoritative
inventory after possible mutation; a driver result is not a browser cache update.

## Non-goals and validation

No mining wire, mouse input, predictive cracks, multi-block queue, item placement,
entity attack, container interaction or persistence policy is introduced here.
Registered Fabric GameTests cover real survival progress/completion, instant creative
START, held-lease loss, authorization loss, look-away, held-item/component and slot changes, out-of-reach and block replacement, adventure
and bedrock denial, and cancellation of real vanilla delayed destruction.
Full Build, Observer 3-JVM E2E and Observer Runtime Validation are required on the PR.

Independent read-only review found no blocking driver issue. Production pre-player-tick
ordering remains a required integration test in the subsequent owner slice. Minecraft
26.2 Player.aiStep synchronizes head yaw during successful movement doTick; the
look-away fixture explicitly sets both body and head yaw before immediate targeting.
