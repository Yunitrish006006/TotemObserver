# TotemObserver validation — 2026-09-12

This document separates the published `0.1.3` release baseline from later hardening on `main`. Post-release hardening listed below has not been published as a new Modrinth version.

## Published 0.1.3 baseline

Release ref: `a3fb218e16c3fed61147b2b0ee7139dd68457c2c`.

- [Build 34677629673](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34677629673): success.
- [Observer Runtime Validation 34677629724](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34677629724): success.
- [Observer 3-JVM E2E 34677629725](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34677629725): success.
- [Publish Modrinth 34678109190](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34678109190): success.
- `.github/staging/modrinth-published-0.1.3.json` records `totem-observer-0.1.3.jar`, Modrinth version `MpB2Gmda`, matching SHA-512 verification, and release ref `a3fb218e16c3fed61147b2b0ee7139dd68457c2c`. The recorded Modrinth project status was still `processing` after upload verification.

The released integration baseline uses TotemCore `0.7.21+` and Nexus `0.3.23` protocol 5 for authorized terrain-backed Nexus map observation.

## Post-release hardening

The following work landed on `main` after the published release ref:

- `2afa8205` — removed Nexus relay reflection into Observer private native-session maps; the relay now uses the public authoritative session query.
- `fc085a10` — made `ObserverNativeSessionManager` the single authoritative observer-to-target relation and restored camera/return state after failed native negotiation.
- `a7e0d270` — moved the server GameTest fixture to the authoritative native-session state.
- `edc4a094` / `90b0dff1` — added one-warning-per-target Nexus terrain relay diagnostics and reset them with owned-screen session state.
- `62f9506c` / `55767bfe` / `869ce799` / `0ddcf1ee` — froze the legacy owned-screen family table as compatibility-only and enforced the generic `ObserverScreenProvider + ObserverOwnedScreenPayloads` extension boundary in CI.
- `57e46d8e` — moved the 3-JVM E2E fixture to the authoritative native-session state.
- `8b06a731` — removed the remaining client GameTest dependency on the deleted `ObserverSessionManager.TARGET_BY_OBSERVER` map; the production `ObserverNativeSessionManager.start()` relation is now the only session relation used by that fixture.
- `c8b44c52` — stabilized the Flutter browser account fixture by using bounded editor reactivation and exact active-editor value verification instead of a single focus attempt.
- `fe12bf96` — synchronized README documentation with the `0.1.3` architecture, Nexus protocol 5 terrain relay, current release baseline, and post-release diagnostics.

## Final hardening validation

Validated source HEAD: `c8b44c52a287d0ad59f1a108510d92c52ff0c8a1`.

- [Build 34690955028](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34690955028): success. Production compile/tests/assembly, Flutter build, browser account flow, and extraction invariants all passed.
- [Observer Runtime Validation 34690954830](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34690954830): success. Owner-module builds, generic owned-screen boundary guard, validation compile, client GameTests, cross-module production sender Client GameTest, screenshot count, production-namespace client GameTests, and extraction boundaries all passed.
- [Observer 3-JVM E2E 34690954832](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34690954832): success. The dedicated server + target client + observer client path, evidence upload, and E2E extraction boundary all passed.

These three workflows are green on the same code HEAD. The validation-document commit is documentation-only and does not change the validated runtime artifacts.

## Transient failures resolved during hardening

- Server GameTest initially reflected the removed `ObserverSessionManager.TARGET_BY_OBSERVER`; `a7e0d270` moved it to native authoritative state. Subsequent Build validation passed all server GameTests.
- The 3-JVM E2E fixture retained the same stale reflection; `57e46d8e` moved it to `ObserverNativeSessionManager.TARGET_BY_OBSERVER` and restored E2E green.
- The owned-screen registry guard initially omitted the final frozen family because its `awk` parser exited before printing the closing line; `0ddcf1ee` corrected the parser.
- Runtime client GameTest still seeded and cleaned the deleted legacy session map; `8b06a731` removed that duplicate fixture state. Runtime run `34690954830` passed the client GameTests and all later runtime stages.
- Build browser account flow intermittently failed while waiting for Flutter to install/focus the editable DOM element. `c8b44c52` replaced the one-shot focus assumption with bounded reactivation and exact value verification; Build run `34690955028` passed the browser account flow.

## Nexus follow-on audit

Observer runtime validation pins TotemNexus `66c2bd85a9b50a6d4f86241e9513967f854f70f8` (`0.3.23`, provider protocol 5). TotemNexus `master` is only release/CI commits ahead of that runtime source, so the map behavior under review is not caused by an outdated Observer pin.

The current Nexus detail path already has server-authorized protocol-5 geometry plus vanilla map-packet relay, and the owning production Screen overlays finer historical/detail map layers at power-of-two zoom. Existing coverage proves that owner path. The remaining follow-on work is to add Observer-specific regression coverage for finer-layer reconstruction and to revise the previous Observer player-marker requirement: the observer client must never substitute its own local coordinates, while an authorized observed-owner map-local decoration can be relayed/rendered without persisting it into shared map data or sending framebuffer/screenshot data.
