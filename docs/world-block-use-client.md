# Block-use client lifecycle

`ObserverConnection` consumes the optional `worldBlockUseProtocol` hello field.
Absent or zero disables use. Version 1 requires admission, identity, movement
and bootstrap capability; malformed/unknown versions fail closed. `play` remains
false. The old client can ignore the new server field; the new client never
sends use to a server that does not advertise it.

`sendBlockUse()` sends exactly the seven intent keys in
[protocol 1](world-block-use-protocol.md). It returns false without queueing when
movement, section, use or bootstrap work is pending, a world refresh is needed,
the shared outbound pacer cannot send immediately, or the 220 ms cooldown is
active. It never claims target coordinates, items or resulting state. Input UI
owns focus and the decision to retry after draining outstanding requests; the
renderer owns neither requests nor input retries.

At most one use is pending, with a five-second response deadline. During use,
new movement, sections and bootstrap requests are suppressed. Independent
registry reads and heartbeat traffic retain the existing bounded shared pacer.
A response must contain exactly nine fields with integer version/sequence/epoch/
subscription/revision, the current dimension, one of four known outcomes and a
boolean refresh flag consistent with that outcome. Unsolicited, stale, malformed
or incompatible responses disconnect and clear all session state.

An `applied` acknowledgment immediately clears section snapshots, unavailable
markers and wanted-section ownership, increments geometry generation, and
requests bootstrap. No mesh can retain the previous generation while waiting.
The existing bootstrap path advances revision, replans the bounded visible
window and resumes movement. `denied`, `no_target` and `unsupported` are nonfatal
outcomes and preserve the existing cache. No local world/inventory mutation is
predicted. Disconnect cancels both use timers and resets capability, pending
sequence and last outcome.

`world_block_use_client_test.dart` covers intent shape, mutual exclusion,
refresh/revision continuation, cooldown, ordinary denied outcomes, hostile and
unsolicited responses, absent capability, timeout and cleanup. Existing
movement/connection regression tests and static analysis accompany this slice;
complete Build, 3-JVM E2E and Runtime Validation results are recorded in its PR.

This is the connection consumer, not an enabled mouse/key control. Actual input,
partial-target visibility, later vanilla scheduled block changes and hotbar
selection remain following slices. It neither opens gameplay containers nor
changes any read-only Observer Screen contract.
