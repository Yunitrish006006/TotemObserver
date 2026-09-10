# TotemObserver

Dedicated Observer View runtime for the Totem Minecraft ecosystem.

TotemObserver owns the cross-cutting runtime behind `/observe`: server-authoritative session control, spectator view relay, semantic vanilla Screen reconstruction, module-owned Screen transport, remote cursor state, and the read-only client firewall. It is not a framebuffer/video streaming mod.

## Platform

- Minecraft 26.2
- Java 25
- Fabric Loader / Fabric API
- TotemCore `>=0.7.18 <0.8.0`
- initial module version `0.1.0`

## Ownership model

`TotemCore` owns the stable Observer contracts such as `ObserverScreenProvider`, provider discovery, snapshots, contexts and read-only handles. TotemObserver owns transport, negotiation, session/runtime behavior and vanilla Screen adapters. Feature modules such as TotemNexus, TotemRemnant and TotemAutomata continue to own their production Screen providers.

A feature module provider is discovered through the TotemCore entrypoint. Module-owned Screens negotiate through a generic bounded transport and an exact `familyId + protocolVersion` match, so adding a future provider such as `alchemy_cauldron` does not require adding TotemAlchemy knowledge to TotemObserver.

## Runtime invariants

- Observer sessions are server-authoritative and spectator-only.
- Reconstructed Screens are read-only; Observer input must not mutate the target player's game state.
- Semantic state is bounded and sequence-checked.
- Module-owned payload interpretation remains with the owning provider.
- Reserved vanilla families cannot be claimed by optional feature modules.
- Unsupported or incompatible optional providers fail closed without taking down the Observer session.
- Production code contains no screenshot, framebuffer, texture-frame or video transport fallback.

## Source layout

```text
src/main/java/dev/totem/observer/
  TotemObserver.java
  TotemObserverClient.java
  runtime/   session, relay and registration runtime
  network/   versioned semantic payloads and capability negotiation
  client/    target capture, reconstruction and Observer UI
  mixin/     runtime/client safety hooks and vanilla Screen accessors
```

## Protocol compatibility

The mod ID is `totem-observer`, but the initial extraction deliberately keeps the existing Observer v4 wire namespace as `totem-vanilla-tweaks`. Repository extraction and packet migration are separate compatibility events; packet IDs are not renamed just because the implementation moved repositories.

Until TotemVanillaTweaks publishes a release with its embedded Observer runtime removed, TotemObserver declares:

```json
"breaks": {
  "totem-vanilla-tweaks": "<=0.1.27"
}
```

This prevents both mods from registering the same compatibility packet namespace at once. The constraint can be revised after the VanillaTweaks cleanup release.

## Migration sequence

1. Merge the extraction-seam and module-agnostic provider changes in TotemVanillaTweaks.
2. Merge and release the first TotemObserver module.
3. Remove the embedded Observer runtime, payload registration and Observer-only mixins/tests from the next TotemVanillaTweaks release.
4. Move the full Observer GameTest, cross-module integration and dedicated-server/two-client E2E ownership into this repository.
5. Treat any future change from the compatibility packet namespace to `totem-observer:*` as a deliberate protocol migration with its own compatibility plan.

The source imported here is based on the fully validated TotemVanillaTweaks Observer branch at commit `7b451cedd5cc4f646df10178b3d14b26ff4689ff`. This repository also independently compiles and assembles the extracted runtime on Java 25 against pinned TotemCore 0.7.18.

## License

Apache License 2.0. See `LICENSE`.
