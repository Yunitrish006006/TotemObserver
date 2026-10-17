# Moving authoritative world window

A valid movement correction compares the player's floored X/Z chunk coordinates
with the current bootstrap center. Only a chunk crossing requests a new window;
sub-block movement, yaw/pitch and motion inside the same chunk retain the current
revision and cache. The browser never supplies the bootstrap center.

When a crossing is detected, the connection marks refresh needed and stops new
movement/section requests. An already pending section must reach its validated
snapshot or unavailable terminal response first. Then the connection requests
bootstrap; the server chooses the center and increments the revision. The
existing old revision remains renderable until this response is accepted.
Accepted bootstrap clears old section/unavailable data and geometry generation,
then the visible controller replans and the scheduler resumes within its budget.
Held input and pointer focus remain mounted across this replacement; the next
movement request binds the new revision. Server input expiry still bounds any
pause while the client drains requests.

There is no cross-revision snapshot reuse. This deliberately avoids inventing
freshness for immutable old snapshots. Refresh happens once per chunk boundary,
not once per movement response. A future overlap-preserving contract can improve
streaming without weakening revision checks.

Vertical plan changes retain only current target keys. A late response for a
previous vertical target is fully validated, then discarded if no longer wanted;
it cannot reinsert stale mesh data. The scheduler waits for the connection's
pending section to finish before assigning a new active request. Snapshot and
unavailable entries have a combined hard cap of 43; visible policy retention
normally supplies that same bound, with conservative oldest/unavailable eviction
for callers outside the policy. Registry names retain their independent bound.

Bootstrap and section requests have five-second deadlines, including queue/drain
limits where applicable. Stale revision responses, malformed parts and timeouts
fail closed. No force-load/generation or world mutation is added. Dimension
transitions still fail closed when they invalidate negotiated movement bindings;
transparent dimension handoff is outside this slice. play remains false.

Tests cover within-chunk reuse, crossing/revision binding, pending-section drain,
late responses, vertical retention, combined cache bounds, timeout cleanup and
actual Chromium input continuity through a fixture-chosen chunk crossing. The
browser fixture is not Minecraft physics evidence; a real browser/server playable
smoke remains the next integration requirement.
