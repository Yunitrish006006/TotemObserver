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
- TotemNexus `6542bc21b68ecb3438085c400eaa9e16ce34ba63` — added protocol-5 Observer regression coverage proving real finer map-layer submission and a bounded observed-target map-local player decoration without observer-local fallback.
- `149d8c70` — moved the Observer Runtime Validation and 3-JVM E2E Nexus lockstep checkout to the validated Nexus map fix.
- `6d467cb0` — aligned the shared owner-module build invariant with the same Nexus lockstep ref so runtime/E2E validation builds the intended source rather than rejecting it before Gradle execution.

## Final hardening validation

### Session/runtime hardening baseline

Validated source HEAD: `c8b44c52a287d0ad59f1a108510d92c52ff0c8a1`.

- [Build 34690955028](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34690955028): success. Production compile/tests/assembly, Flutter build, browser account flow, and extraction invariants all passed.
- [Observer Runtime Validation 34690954830](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34690954830): success. Owner-module builds, generic owned-screen boundary guard, validation compile, client GameTests, cross-module production sender Client GameTest, screenshot count, production-namespace client GameTests, and extraction boundaries all passed.
- [Observer 3-JVM E2E 34690954832](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34690954832): success. The dedicated server + target client + observer client path, evidence upload, and E2E extraction boundary all passed.

### Nexus protocol-5 map integration

Validated source HEAD: `6d467cb00f58694f1b7494fdf8e6ac8709f3d553`, with TotemNexus pinned to `6542bc21b68ecb3438085c400eaa9e16ce34ba63` (`0.3.23`, provider protocol 5).

- [Build 34698212299](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34698212299): success. Observer Java/server tests, Flutter build, stabilized browser account flow, and extraction invariants all passed.
- [Observer Runtime Validation 34698212323](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34698212323): success. The pinned Nexus source built as a production owner module, the generic owned-screen boundary passed, client GameTests passed, the cross-module production sender Client GameTest and screenshots passed, production-namespace Client GameTests passed, and extraction boundaries passed.
- [Observer 3-JVM E2E 34698212350](https://github.com/Yunitrish006006/TotemObserver/actions/runs/34698212350): success. The dedicated server + target client + observer client path ran against the pinned Nexus map fix, uploaded evidence, and passed the E2E extraction boundary.

These three workflows are green on the same `6d467cb0` code/configuration HEAD. The later validation-document commit is documentation-only and does not change the runtime artifacts validated by those runs.

## Transient failures resolved during hardening

- Server GameTest initially reflected the removed `ObserverSessionManager.TARGET_BY_OBSERVER`; `a7e0d270` moved it to native authoritative state. Subsequent Build validation passed all server GameTests.
- The 3-JVM E2E fixture retained the same stale reflection; `57e46d8e` moved it to `ObserverNativeSessionManager.TARGET_BY_OBSERVER` and restored E2E green.
- The owned-screen registry guard initially omitted the final frozen family because its `awk` parser exited before printing the closing line; `0ddcf1ee` corrected the parser.
- Runtime client GameTest still seeded and cleaned the deleted legacy session map; `8b06a731` removed that duplicate fixture state. Runtime run `34690954830` passed the client GameTests and all later runtime stages.
- Build browser account flow intermittently failed while waiting for Flutter to install/focus the editable DOM element. `c8b44c52` replaced the one-shot focus assumption with bounded reactivation and exact value verification; subsequent Build runs passed the browser account flow.
- After the Nexus lockstep checkout was updated at `149d8c70`, Runtime run `34691931865` and E2E run `34691931875` failed at `Build pinned Observer owner modules` before product tests because `.github/scripts/build-observer-integration-jars.sh` still asserted the previous Nexus SHA. `6d467cb0` aligned that CI invariant; runs `34698212323` and `34698212350` then passed the complete Runtime and 3-JVM paths.

## Nexus protocol-5 map follow-on — completed

TotemNexus source `6542bc21b68ecb3438085c400eaa9e16ce34ba63` passed Nexus Build run `34691710921`, including compile/tests, server GameTests, client GameTests, the new Observer map regression, production map runtime regressions, and evidence preservation.

The new Nexus regression proves the two map requirements end-to-end at the production Screen layer:

- Observer zoom at `2x` submits the compatible scale-1 map detail layer, and `4x` submits both scale-1 and scale-0 detail layers. This is real cached vanilla map detail composition, not coarse-pixel magnification.
- The observed target's player marker is relayed only as a bounded vanilla-style map-local decoration: off-map state, signed map-local X/Y bytes, and rotation `0..15`. Raw target world coordinates are not added for this marker.
- If target marker metadata is absent, the Observer Screen renders no player marker. It never substitutes the observer client's own `Minecraft.player` position.
- Map pixels remain outside semantic Observer snapshots and continue to arrive only through the separately authorized protocol-5 Nexus terrain/vanilla-map-packet relay.

Observer Runtime Validation and the dedicated-server two-client E2E now pin this Nexus source, so the map detail and player-marker behavior is covered both in Nexus-owned Client GameTests and in the extracted TotemObserver lockstep integration.
