# Left-button mining input

The web input owner now binds left-button hold/release to the existing
[mining confirmation lifecycle](world-destroy-client.md). Right-button use,
WASD, mouse look, Space and number-key hotbar selection retain their bindings.
The renderer reads authoritative state and never sends requests.

## Lifecycle and bounds

The browser adapter forwards left down/up only while this controller owns
pointer lock. The click that requests capture cannot start mining. Focus,
capture, visibility and window-blur loss clear held input. Listeners and
callbacks are removed on disposal. Native capture remains unsupported.

A fresh down prepares one START for at most 750 ms. The controller flushes
the intended yaw/pitch through a zero-input movement request and requires
an applied server correction before START. Preparation suppresses conflicting
use/hotbar/section work; repeated down cannot queue another action.

While held, the existing 110 ms sampler alternates movement and HOLD requests.
Only one request is pending; fresh applied movement precedes each HOLD. This
normally renews within the server's 500 ms lease without an independent
heartbeat. Network stalls, rejected movement or lost physical input stop
renewal. The server remains responsible for lease expiry, actual ray/reach,
block/tool checks, progress and vanilla destruction.

Release attempts CANCEL immediately. If an earlier request is pending, the
sampler prioritizes CANCEL after that reply, even without capture. Disposal
attempts cancellation and stops all timers; if delivery cannot occur the
server lease expires. A replacement input widget cancels any inherited
operation before it can handle new input. No timer survives solely to renew
mining after its input owner disappears.

Every terminal reply triggers the existing bootstrap/cache refresh. A new
physical release/press is required to mine the next block; holding across
completion does not yet automatically retarget. Prior local ownership is
retired before accepting a rapid new press after refresh. Progress text uses
only the latest server confirmation; there is no local block removal,
inventory claim or predicted progress. `play:false` is unchanged.

## Validation

Six mining input tests cover look-before-START, movement/HOLD alternation,
release behind a pending reply, capture loss, invalid look, terminal rearm,
rapid rearm before the next sampler tick and replacement cleanup. These and
existing movement/use/hotbar input tests pass together (15 tests).

The formal Chromium smoke adds a test-only dirt block to the isolated server
fixture. Real pointer-lock mouse input must produce active survival progress,
HOLD messages, actual server block removal and a new bootstrap revision.
Existing collision, jump, chunk crossing, right-button lever use, inventory
conservation and browser-restart registry reuse remain in the same smoke.
An after-mining screenshot captures the existing debug terrain and text UI;
no new pixel assets or imitation vanilla Screens are introduced.

Formal Build, E2E and Runtime results and independent review evidence are
recorded in the PR. The user preview is updated only after those checks pass.
