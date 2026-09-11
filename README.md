# TotemObserver

Dedicated Observer View runtime for the Totem Minecraft ecosystem.

TotemObserver owns the cross-cutting runtime behind `/observeui`: server-authoritative session control, spectator view relay, semantic vanilla Screen reconstruction, module-owned Screen transport, remote cursor state, and the read-only client firewall. It is not a framebuffer/video streaming mod.

## Platform

- Minecraft 26.2
- Java 25
- Fabric Loader / Fabric API
- TotemCore `>=0.7.18 <0.8.0`
- initial module version `0.1.0`

## Ownership model

`TotemCore` owns the stable Observer contracts such as `ObserverScreenProvider`, provider discovery, snapshots, contexts and read-only handles. TotemObserver owns transport, negotiation, session/runtime behavior and vanilla Screen adapters. Feature modules such as TotemNexus, TotemRemnant and TotemAutomata continue to own their production Screen providers.

A feature module provider is discovered through the TotemCore entrypoint. Module-owned Screens negotiate through a generic bounded transport and an exact `familyId + protocolVersion` match, so adding a future provider such as `alchemy_cauldron` does not require adding TotemAlchemy knowledge to TotemObserver.

## Commands and world rules

Use `/observeui <player>` to start and `/observeui stop` or Escape to stop. Both players need compatible Observer clients.

| World rule | Default | Effect |
| --- | --- | --- |
| `/gamerule totem:observer_enabled true` | `true` | Enable Observer; `false` stops all sessions, including administrators. |
| `/gamerule totem:observer_allow_friends true` | `false` | Allow non-admin players to observe mutual TotemCore friends. One-way invitations do not qualify. |

Administrators with game-master command permission can observe while the master rule is enabled. Friend access requires both rules. Turning off access, removing a friendship, losing the target or disconnecting ends observation. Return state is saved with the player for recovery after reconnecting or restarting. During observation, movement, teleport and interaction packets are blocked on the server as well as the client.

## Runtime invariants

- Observer sessions are server-authoritative. Players can start from any game mode; the server temporarily uses spectator mode and restores the original mode, dimension, position, rotation and flight state when observation ends.
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

TotemVanillaTweaks 0.1.28 removes its embedded Observer runtime. TotemObserver retains the following incompatibility declaration for older installations:

```json
"breaks": {
  "totem-vanilla-tweaks": "<=0.1.27"
}
```

This prevents both mods from registering the same compatibility packet namespace at once. Use TotemVanillaTweaks 0.1.28 or newer when installing both modules; VanillaTweaks is optional for Observer itself. Install TotemObserver and TotemCore on the server and participating clients.

Nexus protocol 3 and protocol 4 are accepted. The target and observer must advertise the same provider family and protocol; protocol 4 is not converted into protocol 3. Nexus 0.3.21 supplies the detail-aware protocol-4 map provider. Feature modules still own variant validation and the production Screen rendering path.

## Extraction and validation status

Runtime extraction, VanillaTweaks cleanup, Observer GameTests, cross-module integration and dedicated-server/two-client E2E ownership are implemented. The repository's workflows now cover:

- `Build`: unit tests, assembly and extraction invariants.
- `Observer Runtime Validation`: client GameTests, owner-present integration and built-artifact production runtime validation.
- `Observer 3-JVM E2E`: a dedicated server plus separate target and observer clients.

The current source version is 0.1.0. Successful validation does not establish a published release; the Modrinth workflow now supports authenticated dry runs and explicit uploads. Any future change from the compatibility packet namespace to `totem-observer:*` remains a separate protocol migration.

Current-head CI, local build and independent review evidence are recorded in [VALIDATION.md](VALIDATION.md).

The source imported here is based on the fully validated TotemVanillaTweaks Observer branch at commit `7b451cedd5cc4f646df10178b3d14b26ff4689ff`. This repository also independently compiles and assembles the extracted runtime on Java 25 against pinned TotemCore 0.7.18.

## License

Apache License 2.0. See `LICENSE`.

## Modrinth publication

Repository secrets `MODRINTH_TOKEN` and `MODRINTH_PROJECT_ID` select the authorized project, including projects awaiting moderation. Version upload does not approve the project or change its review status.

- Pushes affecting release files on `main` or `release/**` run authenticated, read-only validation. Manual runs also default to `dry_run=true`.
- An explicit manual run on `main` with `dry_run=false` uploads the version only after Build, Observer Runtime Validation and Observer 3-JVM E2E pass for that exact commit.
- The publisher builds against pinned Core 0.7.18, checks JAR identity and incompatibility metadata, requires a version changelog, checks the authenticated member's upload permission, and rejects a conflicting existing version.
- Successful uploads are read back to verify the file SHA-512, version, loader, Minecraft version and dependencies. Identical existing versions are verified without a duplicate upload. Sanitized evidence and the built JAR are retained as workflow artifacts; uploads also record a staging publication marker.
- Read-only validation proves authentication, project access and team upload permission. It cannot prove the token's `VERSION_CREATE` scope without actually creating a version.

Prepare `.github/staging/modrinth-changelog-<version>.md` and update `gradle.properties` for the next release. Review the dry-run artifact before manually selecting an upload run. The Modrinth project may remain under review after upload.
