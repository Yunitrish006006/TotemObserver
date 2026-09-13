# Vanilla movement tick foundation

This internal slice proves the Minecraft 26.2 movement path before adding a
browser protocol. It advertises no capability and changes no admission behavior;
play remains false. There is no browser invocation of this driver yet.

## Ownership and semantics

A future admitted session owns exactly one ObserverMovementDriver. Its caller
must be the Minecraft server thread and must supply a current session/admission
authorization predicate. The driver rechecks this predicate, rejects repeated
execution within the same server tick, and accepts only normalized input intent:
strafe, forward, yaw, pitch and jump. It never accepts position or velocity.
Non-finite input is rejected, direction magnitude is limited to one, yaw wraps
and pitch clamps to [-90,90].

Minecraft 26.2 Player.canSimulateMovement and isEffectiveAi permit server-side
simulation. ServerPlayer.doTick invokes Player.tick, LivingEntity.aiStep and
vanilla travel. The driver uses this path once, applies the listener-equivalent fall damage, movement statistics and known-motion accounting with server displacement/onGround, then updates chunk tracking and
the connection movement baseline. It does not run the remote-client listener's
tickPlayer snap-back to last client XYZ. No replacement gravity, speed,
friction, collision shape or jump formula is introduced.

Movement rejects dead/removed, sleeping, passenger, spectator, flying/gliding,
dimension-changing or unloaded-chunk-touching players. No force-load or chunk
generation API is called by the driver. Input fields clear in finally.

## Integration still required

Transport must authenticate every operation, version/sequence/epoch/dimension
bind requests, rate-limit before enqueueing server work, revalidate current
authorization on every tick and response, and impose a bounded dead-man timeout.
A single owner must continue idle physics and return authoritative correction
without double-ticking vanilla player state. World window revision/eviction and
unsupported mode behavior need transport and runtime coverage before enabling
move. Browser-held buttons and prediction cannot override server state.

## Validation

Pure JUnit tests prove finite validation and input normalization.
The registered Fabric GameTest places a real ServerPlayer, drives separate server
ticks, verifies forward progress, solid-wall/floor collision, jump, a damaging fall/landing, duplicate-tick
rejection and revoked authorization on a later tick. Dedicated test count is
expected to become 12. Fixture-only player placement is not a movement API.
Formal Build, 3-JVM E2E and Runtime results belong in the stacked PR.
