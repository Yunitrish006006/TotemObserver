# Shared account request pacing

ObserverConnection owns one outbound FIFO budget across authentication, world
state/bootstrap, registry, section, movement, ping and logout requests. The
renderer still owns no network requests; the section scheduler remains the
sole section request owner. Server validation and its 32-frame/second limit
are unchanged.

The budget starts with eight tokens, restores one every 60 ms and retains at
most eight. Thus any one-second interval contains at most 25 sends, including
the initial burst. It does not catch up missed timer callbacks with a large
burst. At most eight frames of at most 1,024 characters can wait. Overflow,
oversize or socket write failure closes the connection and discards the queue.
Sequence order is FIFO across all operations, with no priority reordering.

Movement additionally requires an immediately available token and an empty
queue; old movement intent is never queued behind world data. The input owner
must retry current intent on its next sample, subject to the connection's own
100 ms movement cooldown and one-pending-request limit. Request deadlines
continue to include queue delay.

Disconnect drops pending writes and cancels refill. Logout is best effort;
closing the underlying socket remains the authoritative session cleanup path.
No queue/cache survives reconnect. Registry page hydration and moving-window
cache eviction are separate controller responsibilities.

Validation: full Flutter suite covers mixed FIFO bursts, rolling-window bounds,
overflow/oversize/write failure, idle refill and disconnect cancellation, plus
existing account/world/correction flows. Formal three-workflow results belong
to the stacked PR's Validation record.
