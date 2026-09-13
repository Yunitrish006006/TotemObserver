# First-person block-use input

The debug first-person controls advertise `E 使用` only when the server negotiated
block-use capability. E is a temporary debug use binding, not inventory input.
Actual pointer capture and primary keyboard focus are required. Only a fresh
key-down queues use; repeats do not repeat interaction.

The input owner retains at most one use intent. A 750 ms connection-owned lease
pauses new section requests so prior section work can drain. The lease expires
independently of widget sampling; a monotonic Stopwatch also rejects expired
intents if event-loop stalls delay the timer callback. The input owner snapshots the current camera
plus accumulated mouse deltas, freezes further look accumulation, sends an idle
movement/look intent, waits for its authoritative correction, and then attempts
`sendBlockUse`. It never sends block coordinates or performs a local mutation.
The normal 110 ms sample loop and shared pacer remain the only send cadence.

The idle look flush avoids using unconfirmed local camera direction and stops
held direction/jump before interaction. A rejected look correction cancels use. The captured
aim is the visible authoritative camera plus unsent deltas at key-down, even if
an earlier look request is still in flight; that earlier correction does not
silently change the captured aim. The server derives the ray from
its actual player state at execution, so the action is not a guarantee that an
old client target remains selected. On applied acknowledgment, the connection
consumer clears old world geometry and requires bootstrap refresh before play
continues. New section work resumes after the preparation lease ends; pending
use and refresh retain their own section barriers.

Esc, pointer-lock loss, focus loss, connection replacement, disconnect and widget
disposal cancel unsent use. An already-sent server action cannot be retracted by
focus loss. A stalled drain cancels the local intent after 750 ms and never
replays it after a later response. Input can be retried with a new key-down.

Tests cover no input before capture, idle look flush before use, one request
while holding the key, suppression of new section work, capture-loss cancellation
and lease expiration. Existing movement and connection regression tests remain
required. Complete CI includes browser screenshots for the updated control hint;
actual browser/server lever interaction and partial-block visibility need their
own following evidence. No new raster assets are introduced.

This slice does not add right-click use, inventory key handling, partial-block
models, or general live block changes. The existing read-only Screen firewall
and `play:false` remain unchanged.
