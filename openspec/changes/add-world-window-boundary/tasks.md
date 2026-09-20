## 1. Specification

- [ ] 1.1 Define the versioned world-window capability and bounded control-plane message.
- [ ] 1.2 Define mod-extensible chunk identity and explicit non-goals for payload/rendering/gameplay.

## 2. Server

- [ ] 2.1 Derive the initial chunk window from the server-thread world bootstrap.
- [ ] 2.2 Advertise and send the world-window contract after successful admission/bootstrap.
- [ ] 2.3 Add JUnit and dedicated-runtime coverage, including negative coordinates and a non-vanilla namespace.

## 3. Flutter

- [ ] 3.1 Add `ChunkKey`, `WorldWindow` and `WorldState` protocol/state models.
- [ ] 3.2 Bind the window to session epoch, dimension and initial authoritative player chunk.
- [ ] 3.3 Reject missing, stale or malformed advertised windows and expose the bounded identity set to UI/tests.

## 4. Validation

- [ ] 4.1 Pass Java unit tests and Minecraft GameTests.
- [ ] 4.2 Pass Flutter format/analyze/test/release web and Chromium account flow.
- [ ] 4.3 Pass Observer 3-JVM E2E and Runtime Validation without changing `play:false`.
