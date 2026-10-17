# Flutter movement protocol consumer

The connection owns protocol negotiation, request sequencing and correction.
`worldMovementProtocol: 1` is required together with admission, identity,
world-state and bootstrap capabilities. Legacy servers remain view-only.
`play` stays false. This consumer does not grant Screen or inventory mutation.

`sendMovement` accepts only bounded intent axes, finite look angles and jump.
It wraps yaw, clamps pitch and sends centidegrees using the exact protocol-1
schema. It sends no position. At most one request is pending, requests are
separated by at least 100 ms, and bootstrap cannot overlap movement. A five
second unanswered request terminates the connection. Disconnect cancels both
timers and clears negotiation, pending sequence and last authoritative tick.

A correction must have exactly the fifteen response keys, integer identity
fields, matching sequence/epoch/subscription/revision/dimension, a strictly
increasing server tick, boolean status and finite bounded coordinates/angles.
Malformed, unsolicited and stale corrections close the connection. Only accepted
server responses update XYZ/yaw/pitch; there is no local position prediction.

The scene retains at most 16,384 immutable faces for its current connection,
visible plan and geometry generation. Registry/section/bootstrap/cache changes
invalidate this generation; movement-only corrections reuse geometry. Every
build still checks admission, dimension and plan validity so stale geometry
cannot survive a world-state boundary. Rendering owns no requests.

Keyboard/mouse focus and pointer capture are implemented by the separate
[first-person input owner](world-first-person-input.md). Moving chunk-window
replanning is owned by the [window lifecycle](world-moving-window.md). This transport alone is not a playable client.

Validation includes malformed/stale correction rejection, single pending and
cooldown behavior, authoritative position ownership, legacy capability fallback,
timeout/disconnect cleanup, and scene generation/validity tests. Formal Build,
3-JVM E2E and Runtime Validation results are recorded in the stacked PR.
