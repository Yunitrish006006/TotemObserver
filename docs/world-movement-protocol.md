# World movement protocol 1

The account-v1 hello advertises worldMovementProtocol:1 only when player
admission is present. Old clients can ignore this additive capability. play
remains false; movement does not authorize interactions, inventory or read-only
Observer Screen input.

## Request

Exactly twelve keys, no arrays/nested objects:

    {"type":"world_movement","protocol":1,"seq":7,"sessionEpoch":42,
     "subscriptionId":1,"revision":1,"dimension":"minecraft:overworld",
     "strafe":0,"forward":1,"yaw":9000,"pitch":0,"jump":0}

All scalars except type/dimension are canonical JSON integers (no strings,
fraction, exponent, negative zero or duplicate keys). Axes are -1/0/1; positive
strafe is left, positive forward is forward. Yaw/pitch are hundredths of degrees,
bounded to ±36000/±18000 before wrapping/clamping to Minecraft look range.
Jump is 0/1. Diagonal movement is normalized. There is no XYZ, velocity, item,
block/entity target, client-grounded flag or duration field.

Sequence shares the existing strictly increasing account sequence space.
Epoch must match the current authenticated play session. Subscription/revision
and dimension must exactly match the current bootstrap. The admission service
rechecks actual server dimension and live authentication on the server thread.
Identifier regex alone never establishes a valid dimension.

## Scheduling and expiry

One pending movement response per connection, minimum 80ms between requests,
within the unchanged whole-connection 32 frames/second and 1024-byte inbound
budget. A five-second response deadline closes stalled requests. Clients should
use at most 10 inputs/second and coordinate with section/registry traffic.

First accepted input creates at most one motion entry per admission, globally
bounded by the bridge's 16 connection limit. The server ticks the vanilla
movement driver at most once each server tick. A received input persists for at
most 250ms of monotonic wall time; measured from WebSocket ingress (including server queue delay); afterward zero-input vanilla ticks continue
gravity and friction while authorization remains valid. Input fields are never
latched on the ServerPlayer outside the driver's call. Session release removes
the motion entry and completes any pending response without movement.

The initial control request completes vanilla player-load gating for the
Observer-owned admitted player. Unsupported player states produce an
authoritative response with applied:false; they never trigger teleport,
spectator/flying transitions or client-invented collision.

## Response and correction

world_movement echoes protocol/seq/sessionEpoch/subscriptionId/revision, and
includes serverTick, applied, onGround, dimension, x/y/z/yaw/pitch. Position and
ground contact come only from vanilla server simulation. Each pending response
is fulfilled after a server tick, with all session and bootstrap bindings checked
again before transmission. Consumers must reject unexpected/stale responses and
replace prediction with this state. The response is the live authoritative state
update; the old world_state snapshot remains available for compatibility.

Bootstrap and movement requests mutually exclude pending revision changes.
Input already older than 250ms when its server task starts is rejected before
creating a motion entry.
Dimension/session replacement fails closed. A later moving-window slice must
coordinate bootstrap refresh with movement/section requests on chunk crossings;
this protocol alone does not expand the visible cache.

## Failure behavior and validation

Malformed/unknown/extra keys, stale sequence/epoch/subscription/revision,
dimension mismatch, overlapping/flooded requests and revoked admission close
the account connection. No unbounded queue, persistent cross-session motion
cache or renderer-owned network request is introduced.

JUnit validates strict payload shape, types, numeric syntax and ranges.
Registered dedicated GameTests exercise real authenticated WebSocket input,
server-backed correction, jump, dead-man expiry and ten hostile binding/schema/overlap
cases with unchanged server state, plus expired ingress and revoked admission.
Expected dedicated count is 15 (three added to the twelve-test foundation).
Formal Build/3-JVM E2E/Runtime evidence is recorded in the stacked PR.
Browser keyboard/mouse UX and moving window integration remain next slices.

Admission services use one stable Fabric END_SERVER_TICK callback and a concurrent
set of live services. Construction/closure never modifies Minecraft's tickable
list; closure removes the service instead of retaining per-service no-op
callbacks. Only the matching server invokes a service, and all Minecraft state
access remains on its thread. A dedicated lifecycle test opens and closes a real
admission from inside the Fabric tick callback. General server task scheduling
is insufficient to protect vanilla tickable-list iteration because nested task
processing may execute queued work before that iteration ends.

## Expiry at the simulation tick

An intent can be fresh when admitted to the motion queue and exceed its 250 ms
age limit before the next server tick. In that case the driver still performs
idle physics, but the correction must report `applied:false`. Successful idle
simulation is not confirmation of the requested direction, jump or look. This
also prevents a block-use preparation from treating an expired look as applied.

`inputExpiringAfterEnqueueCannotAcknowledgeApplied` uses a package-private monotonic
clock seam to advance past the deadline between enqueue and tick without stalling
the server thread. It checks unapplied status, unchanged aim/horizontal position
and continuing gravity. Production always uses System.nanoTime. The wire test
retries explicit unapplied corrections at most four times with fresh sequence
numbers and a 100 ms interval, and still requires real movement and jumping.
Its bounded enclosure prevents accelerated GameTest ticks from moving the player
off a small floor while input is still valid. A server-side aim change after
expiry must survive later idle ticks, so the wall cannot mask stale intent replay.
