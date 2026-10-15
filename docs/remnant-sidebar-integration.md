# Remnant native sidebar integration

Native inventory, crafting, enchanting and chest screens with a selectable
Remnant backpack defer to the module-owned `remnant_sidebar@1` provider.
Ordinary and death variants use the actual Mojang Screen/Menu rendering path.
Legacy native transport captures only native slots, excluding appended sidecars.

Optional provider ownership contract v1 uses `Predicate<Screen>` independently
of capture success or peer capabilities. A claimed screen suppresses competing
legacy capture even on failure. Providers without the predicate retain compatible
capture-based ownership; no selectable backpack permits legacy native fallback.
Missing/incompatible providers remain unsupported metadata, with no substitute
Screen or framebuffer transport. No TotemCore API change is required.

This isolated integration carries the existing released Minecraft 26.3 native
API adaptations (sign text slots, advancement positioning, SDL input callbacks)
and their existing tests. The source version aligns to the already released
Observer 0.1.5 baseline. Authentication and readonly cancellation predicates stay
unchanged. The published 0.1.5 bridge capability baseline is retained; unrelated dirty
world/browser branch work is excluded.

## Validation (2026-10-15)

- Production and GameTest compilation and JAR assembly passed on the isolated
  mainline worktree. Of 35 unit tests, 34 passed initially; the account work-budget
  test reached its overall 30-second timeout during concurrent graphics tests.
  Its isolated rerun after graphics stopped passed, without changing production
  authentication, limits or the test timeout.
- All eight real-target-capture sidebar combinations passed dedicated-server +
  Target JVM + Observer JVM owner-UI scoped E2E: initial/later contents, rendered
  remote cursor, readonly inputs, close, Stop and server cleanup. The scope also
  retained legacy native/other-owner reconstruction and unsupported fallback.
- This owner-UI profile excludes only the unrelated account-v1 admission
  coordinator and visibility fixture. Production authentication/whitelisting is
  unchanged. Full account-v1 admission remains outside the passing scope; its
  previous policy-close1008 issue is not claimed resolved.
- Remnant passed 81 server GameTests, optional VanillaTweaks-present/absent client
  profiles, final native sidebar client tests and separate-JVM save/reload.
- Independent review cleared the isolated implementation/prerequisites and final
  evidence. Native crafting and death-chest E2E screenshots were visually reviewed.

Remnant candidate SHA256:
`895f5a76e4a31ec28c0cf103e2ec43f49c1fd52d77808fdd61e8aeb8ff10f12f`.
Observer assembled JAR SHA256:
`591246ee2bd198e36a6e3938584ee67df47c1ded8963901c3ee663d8394e0a7b`.

Local evidence (logs, exact scoped runner/profile, source manifests, screenshots,
JARs and review record): /tmp/remnant-sidebar-integration-evidence-20261015-6v5eylbk.

No publication is requested. Other sidebar UI families remain deferred.

CI dependency pins and local default JAR paths align to the verified 26.3 suite;
the Remnant runtime fixture pins the integrated sidebar commit `44d46e00776b02e84fe3f121877edd87bbd30438`.
Existing mainline job selection and graphics launch behavior are preserved.
Shell syntax and pin consistency were checked locally; no manual runtime CI
workflow was dispatched.
