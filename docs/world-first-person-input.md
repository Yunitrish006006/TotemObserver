# First-person input lifecycle

WorldMovementControls owns keyboard and mouse intent separately from the scene.
The player explicitly selects the control button; input becomes active only
after the browser reports successful pointer lock. WASD uses physical keys,
Space holds jump, and Esc releases capture. Text fields outside the world focus
never become gameplay input. Blur, hidden document, pointer-lock loss, connection
loss and disposal clear held keys and unsent mouse deltas. DOM event listeners
are removed on disposal, including pending-lock cleanup.

The controller samples current intent every 110 ms. It sends only when the
connection permits an immediate, negotiated movement request. Pending requests,
the shared frame budget and protocol cooldown remain authoritative client
transport gates. When movement is negotiated, section and registry requests
each have a 300 ms minimum spacing; together their background load stays below
7 requests/second, leaving room for movement and heartbeat within the shared
budget. The section scheduler wakes when its cooldown ends without giving the
renderer network ownership. Mouse deltas are finite, individually bounded and accumulated
within bounded angle ranges. New deltas are based on the latest acknowledged
yaw/pitch. XYZ and displayed angles are never predicted locally.

After capture has first been acquired, releasing it sends zero axes/jump on the
next permitted sample and continues idle authoritative corrections while this
view remains mounted. Server input expires independently after 250 ms, including
when a hidden browser throttles timers. No pending local key history is replayed.
The server's existing collision, permission, admission and revision checks are
unchanged, and play remains false.

The browser adapter uses package:web 1.1.1, already present in the lockfile via
WebSocket support and now explicitly declared. Its Dart-project BSD license is
retained in Flutter's generated license inventory. No new 3D engine is added.
The non-web adapter reports unsupported pointer capture and keeps controls
inactive; native pointer capture is outside this slice.

Validation covers lock-request versus acquisition, held WASD/jump, server-owned
camera correction, rebased mouse deltas, capture loss, Esc, disconnect and
listener disposal. Browser release compilation and formal CI results are
recorded in the stacked PR. Moving-window streaming and a real server/browser
playable smoke are still required before claiming the playable milestone.
