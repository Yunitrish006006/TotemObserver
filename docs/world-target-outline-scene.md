# Authoritative debug selection display

The existing movement input owner polls for server outlines only while captured,
focused, grounded, and idle for 330 ms with no held movement/jump keys, look
deltas, or queued use. Captures are separated by at least 500 ms. An unsuccessful
immediate-send attempt releases its polling lease so an outstanding movement
response cannot indefinitely starve selection. The lease is set before send to
prevent reentrant connection notifications from issuing another request.

Movement and look input clear the displayed selection immediately. Capture/focus
loss, disconnect, connection replacement and disposal cancel polling ownership.
There is at most one connection-owned outline request; no polling history or
queue is retained. Ordinary idle movement keeps a still-current outline, while
camera changes, cache generations and the client 500 ms TTL still invalidate it.
An E press may prepare while an outline read is in flight, then waits for that
read before sending its existing idle-look confirmation and use intent. No
interaction is sent concurrently with capture or without server validation.

The renderer consumes the current immutable snapshot and draws its real vanilla
selection boxes as a white debug wireframe, at most 16 boxes / 192 edges. Each edge
is clipped against near/far and all viewport planes before perspective division.
The projection requires exact authoritative camera identity and the existing
standing eye height; unsupported eye heights suppress selection. Invalid or
oversized viewports produce no lines. Scene semantics uses the same projected
edge visibility at the actual viewport, so rejected/clipped geometry cannot
announce a displayed server outline. Work and output are bounded and immutable.

This is a selection overlay, not a partial block surface model. It does not fill
stairs/levers with cubes, change occlusion/collision facts, own section requests,
or enable gameplay capabilities. Existing cache-derived face targeting remains
the fallback when no current server outline exists. As with the prior debug
selection overlay, lines are painted over terrain; this is not depth-buffered
vanilla rendering. The server performs the nearest-hit selection independently.

The change follows the existing debug scene's crisp, simple line convention and
Totem art-direction requirements. No raster sprite or texture assets are added.
Pure projection and widget tests cover bounded partial geometry, clipping, stale
camera rejection, idle polling, held-key priority and capture-loss invalidation.
The real browser/Minecraft smoke now requires a server-selected partial lever
outline and the production semantics state before saving server-lever-outline.png,
then continues E use, movement, collision, jumping and window-refresh checks.
The screenshot must be visually reviewed; semantics alone is not visual proof.
Actual CI and screenshot review results are recorded in the stacked PR.
