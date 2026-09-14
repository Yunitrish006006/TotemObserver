# Shared account request pacing

ObserverConnection owns one outbound FIFO budget across authentication, world
state/bootstrap, registry, section, movement, ping and logout requests. The
renderer still owns no network requests; the section scheduler remains the
sole section request owner. Server validation and its 32-frame/second limit
are unchanged.

The budget starts with eight tokens, restores one every 60 ms and retains at
most eight. Thus any one-second interval contains at most 25 sends, including
the initial burst. Refill uses elapsed `Timer.tick` intervals, including missed
callbacks when rendering delays the event loop, and still caps retained credit
at eight. It cannot release an unbounded catch-up burst. At most eight frames of
at most 1,024 characters can wait. Overflow,
oversize or socket write failure closes the connection and discards the queue.
Sequence order is FIFO across all operations, with no priority reordering.

Movement additionally requires an immediately available token and an empty
queue; old movement intent is never queued behind world data. The input owner
must retry current intent on its next sample, subject to the connection's own
100 ms movement cooldown and one-pending-request limit. The input integration
also spaces section and registry requests by 300 ms each when movement is
negotiated. That spacing alone does not reserve input capacity: movement plus
registry, section, outline and hotbar polling can exceed the shared refill rate.

Before assigning a sequence, background work now requires an empty FIFO and
more than two tokens. Section/registry hydration applies this gate once movement
is available; target-outline polling and inventory reads also use it. Explicit
slot selection, movement, use and mining retain immediate foreground access.
Background attempts that cannot acquire budget send nothing and allocate no
sequence or pending deadline. Existing controllers retry current work. Refill
notifies them only when background capacity crosses from unavailable to
available, so a waiting section scheduler resumes without owning a new timer.
Continuous normal movement still leaves bounded capacity for world streaming;
background work is not disabled while walking. Request deadlines continue to
include queue delay for operations that allow queuing.

Disconnect drops pending writes and cancels refill. Logout is best effort;
closing the underlying socket remains the authoritative session cleanup path.
No queue/cache survives reconnect. Registry page hydration and moving-window
cache eviction are separate controller responsibilities.

Validation: full Flutter suite covers mixed FIFO bursts, rolling-window bounds,
overflow/oversize/write failure, idle refill and disconnect cancellation, plus
existing account/world/correction flows. Formal three-workflow results belong
to the stacked PR's Validation record.

Additional regressions simulate a delayed timer that skips 100 intervals,
background-first contention against continuous 10 Hz input, and reentrant
capacity notifications with cleanup. They verify capped credit, FIFO ordering,
at most 25 frames in a sliding second, foreground service and continuing
background progress. These fix concrete budget/timer starvation mechanisms;
they do not establish the sole cause of every observed browser latency spike.
