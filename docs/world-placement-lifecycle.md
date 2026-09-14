# Placement admission and dedicated policy validation

The [internal vanilla placement driver](world-placement-driver.md) now has an
admission-owned entrypoint. No browser request or capability is enabled here.
The forthcoming exchange must provide exact version/sequence/session/world
binding checks, rate limits, a pending-request deadline and a refresh barrier.

## Admission ownership

`placeBlock` runs on the Minecraft server thread and rechecks a nonnegative
request age of at most 250 ms, current admission, session authorization and
actual dimension. These checks remain in the predicate used immediately before
vanilla action and are checked again after it returns. Post-action invalidation
retires the admission through the exception cleanup path. It rejects a pending movement response and changes retained
movement intent to idle before placement.

If a mining owner exists, placement cancels mining and returns a denied result
with `refreshRequired:true`. It retains the mining terminal until bootstrap
consumes it; repeated placement cannot overwrite it or start a new action.
Otherwise the real placement driver runs, and its possible-mutation flag
requires a fresh world/inventory synchronization even if vanilla returns a
denied placement. No inventory or block prediction is introduced.

Any exception retires the exact current admission and removes its ServerPlayer,
motion/mining ownership and embedded connection before completing the failed
future. This includes failures after vanilla already changed a block or held
stack. A package-private constructor seam permits a test to throw after an
actual successful vanilla placement; it is not a browser-controlled action
implementation. The public constructor always uses the real driver.

The entrypoint allocates no additional queue or cache. The eventual transport
must not invoke it concurrently with conflicting gameplay/world requests or
allow follow-up mutations across a required refresh.

## Real dedicated spawn-protection probe

GameTestServer's base spawn-protection policy returns false. The isolated
browser fixture now starts with `spawn-protection=16` and runs a dedicated
policy probe before opening its account bridge. A temporary unrelated operator
enables vanilla protection; the acting real ServerPlayer remains unprivileged.
The probe verifies protection is active at support and destination, that
placement changes neither block nor inventory, then removes the temporary
operator and confirms the same player/ray can place and consume exactly one
item. This positive control distinguishes permission rejection from an invalid
ray or collision fixture.

Cleanup restores the test destination to air, removes the temporary operator
and player, and closes the embedded channel. These changes exist only in the
fresh isolated browser fixture, never the production mod or user preview.
The regular browser smoke then runs with the ordinary empty-operator-list
vanilla policy. Its evidence includes `placement-protection-result.json`.

## Validation

The registered admission GameTest covers stale/future/revoked/wrong-dimension
input, real placement and inventory consumption, unconsumed mining cancellation,
bootstrap consumption and admission cleanup after a real mutation throws or
revokes authorization. Dedicated GameTest count becomes 36. Java and browser-fixture compilation pass
locally; complete runtime results belong in the stacked PR Validation record.
The formal Build browser smoke must pass the dedicated policy probe before
this evidence can be used to enable the later placement wire capability.

The existing loaded-world ray test remains distinct from a loaded-hit with
unloaded-adjacent-destination test. Preflight checks still reject missing chunks;
normal vanilla neighbor callbacks are not claimed to be wholly loaded-only.
`play:false` and Observer Screen read-only transport remain unchanged.
