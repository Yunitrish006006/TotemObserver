# Client target outline ownership

The optional `worldTargetOutlineProtocol:1` capability requires admitted player
identity, movement, bootstrap and registry support. Missing/zero support disables
requests; unknown versions and inconsistent capability combinations fail closed.

`ObserverConnection.requestTargetOutline()` sends one immediate eight-key request
with current session, revision, dimension and registry bindings. It sends no
camera or target suggestion. False means no queued request. Requests require a
free outbound pacer, no pending movement/use/outline, no block-use preparation or
window refresh, and a 250 ms cooldown. An existing section read may finish; new
section requests, movement, use and bootstrap wait for the outline response.
There is a five-second response deadline. The renderer owns no network requests.

`WorldTargetOutlineSnapshot` parses the exact 16-key response and immutable target
data. Target objects have exactly six keys, 1–16 boxes with six finite ordered
coordinates in [-1,2], a valid face and raw ID within the current registry count.
Position, rotation, sequence and epoch bounds are checked without coercion. The
existing 8192-character receive limit applies before JSON parsing. The connection
checks the exact pending request, session, subscription/revision, dimension,
fingerprint, non-stale server tick, build height and a conservative eight-block
per-axis bound around the captured eye. Selection remains read-only evidence;
this bound is not server interaction validation or a replacement for raycasting.

Only one outline is retained. Its getter requires identical current authoritative
XYZ/yaw/pitch and the cache geometry generation at request time. Null targets and
camera/generation mismatch display nothing. A 500 ms timer clears the snapshot;
a monotonic Stopwatch also prevents a delayed event loop from extending its
display lifetime. Nonzero movement, jump, changed look, use or bootstrap submission clears it
immediately; an unchanged idle heartbeat preserves a still-current selection.
Disconnect cancels timers and clears both request and display state.

`clearTargetOutline()` invalidates display ownership, including a capture still
in flight, without cancelling its wire request. The eventual reply must still
validate and retire that request, but cannot restore the cleared display.
Callers managing focus/pointer lifecycle use `clearTargetOutline(notify: true)`
to invalidate the display and request repaint; internal submission paths avoid
reentrant notification until their pending state is established.
No history, target cache, polling timer or renderer is added by this slice.
The following UI slice owns bounded polling and painting actual selection boxes.
These boxes are not full-block models, collision truth or interaction grants.

Validation covers immutable copies, exact bindings, malformed/oversized geometry,
unknown IDs, nonfatal no-target/stale camera, cancellation, TTL, refresh, request
exclusion, capability compatibility, unsolicited responses and timeout cleanup.
The focused suite also includes existing block-use client regression tests.
Formal CI evidence belongs to the stacked PR; no `play` capability is enabled.
