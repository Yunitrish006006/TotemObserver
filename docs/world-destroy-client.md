# Flutter mining confirmation lifecycle

This slice consumes [block destruction protocol v1](world-destroy-protocol.md).
It adds no mouse binding and does not change `play:false` or the read-only
Observer Screen transport. The next input slice owns physical hold/release.

## Ownership and protocol

`ObserverConnection` negotiates optional `worldBlockDestroyProtocol:1` only
alongside admission, identity, movement and bootstrap capabilities. Missing or
zero capability disables mining. Invalid advertised values fail closed.
`sendBlockDestroy` sends only START/HOLD/CANCEL intent with the current
epoch/subscription/revision/dimension. START uses its request sequence as its
operation ID; HOLD/CANCEL retain that ID. No target, progress, item or position
claim is sent. `WorldDestroySnapshot` is an immutable server confirmation.

One request can be pending. The shared frame pacer must admit the request
immediately; mining intents are never queued for later replay. START/HOLD use
a 100 ms client cooldown; CANCEL bypasses that cooldown. A 5 second missing
reply closes the connection. Optional 750 ms preparation pauses new section
requests while the input owner drains earlier work and flushes look intent.
Preparation expires and never sends START itself.

The connection never manufactures HOLD messages. Only a future input owner
with current physical input may renew the server's 500 ms lease. Movement is
allowed between mining replies. Section requests, use and hotbar work wait
until the current operation is consumed; an outline request may run between
mining requests. Conflicting pending requests cannot overlap.

## Confirmation and invalidation

Replies are bounded to 1024 characters and exactly thirteen fields. The
decoder requires strict integer versions and identities, finite bounded
progress, valid outcome/action combinations, and matching pending sequence,
operation, action and current world binding. Unsolicited, stale or malformed
replies close the connection. ACTIVE requires positive progress and a true
world-may-have-changed flag. That flag cannot regress within an operation.
A fresh denied START may legitimately report false.

Confirmed possible mutation invalidates the held inventory snapshot. Every
terminal outcome retires selection geometry, section snapshots, unavailable
markers and requested-section bookkeeping, then requests a new bootstrap.
This consumes the server's retained terminal owner before another START.
An explicit bootstrap also retires the client operation. Old-revision replies
cannot revive it. Disconnect clears operation, confirmation, timers and
preparation. No local block or inventory mutation is inferred from progress.

The visible-world controller remains the only section request owner. Existing
revision and cache bounds continue to apply; this layer retains only one
operation and one scalar confirmation, with no growing history or mesh cache.

## Validation

Eight focused Flutter tests cover request shape, confirmation ownership,
pending exclusions, active/terminal bootstrap, immediate cancel, malformed and
stale replies, dirty-flag monotonicity, optional capability, bounded preparation,
reply size/deadline and disconnect cleanup. Together with existing hotbar and
block-use tests, all 19 pass locally; Flutter analyze passes. Dependencies were
resolved with `flutter pub get --enforce-lockfile` before Dart formatting.
Independent read-only review found no remaining blockers. Formal Build,
3-JVM E2E and Runtime results are recorded on the slice PR when complete.
