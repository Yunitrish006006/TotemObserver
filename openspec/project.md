# Project Context

## Purpose

Totem Observer owns the server-authoritative bridge between Minecraft 26.2 and
an eventual multiplayer-only Flutter web client. Minecraft/Fabric remains the
source of truth for world state, player lifecycle and Totem module logic; the
browser presents state and sends bounded semantic input.

The browser client is expected to grow from the current authenticated
`ServerPlayer` admission flow into world synchronization, rendering and full
multiplayer gameplay without weakening Java-client authentication, server
policy, or Totem module authority.

## Tech Stack

- Java 25, Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.154.2 and Fabric
  Loom 1.17.12.
- TotemCore compatibility range: `>=0.7.18 <0.8.0`.
- Flutter 3.47.0 stable for the web client.
- Netty/WebSocket account bridge using `totem-observer-account-v1`.

## Project Conventions

### Code Style

- Treat all browser input as untrusted. UUID, profile name, OP state, world
  state and authoritative gameplay outcomes are server-owned.
- Keep account/authentication transport separate from world/play state where
  practical so authentication changes do not force world codec changes.
- Keep protocol models separate from rendering and UI models. Flutter world
  state must not depend on a specific renderer implementation.
- Prefer additive, versioned protocol capabilities over implicit behavior.

### Architecture Patterns

- Minecraft/Fabric is authoritative. The Flutter client may predict or cache
  presentation state, but server state wins and reconciliation must be possible.
- Generic world transport is module-agnostic. Core chunk, entity, player,
  registry, resource and action contracts MUST NOT hard-code TotemNexus,
  TotemRemnant, TotemAutomata or any other module-specific classes or IDs.
- Content identity is registry-driven and namespaced. Blocks, items, entities,
  particles, sounds, menus/screens and future content use namespaced identifiers
  rather than closed vanilla-only enums.
- Unknown or unsupported namespaced content must degrade safely. A newly
  installed or newer Totem module must not make the entire world session fail
  merely because the Flutter client lacks a specialized renderer or screen.
- Module-specific behavior belongs behind an extension boundary, such as
  capability negotiation plus namespaced protocol handlers, rather than being
  embedded into the generic world codec.
- Resource resolution must be designed for both vanilla and mod-provided
  textures, models, blockstates, fonts, sounds and other assets. The renderer
  must not assume that all renderable content ships with vanilla Minecraft.
- Module screens and semantic UI extensions are separate from world rendering.
  Nexus maps, Automata interfaces, Remnant screens and future module UIs must be
  able to evolve without changing the generic chunk/entity cache format.
- The bridge should negotiate installed/available extension capabilities and
  versions before using module-specific messages. Missing extensions must be a
  supported state.
- Route B custom world/action transport is currently a feasibility path, not a
  permanent commitment. Route A native-protocol work may be revisited before
  Step 4 chunk/entity/action formats become compatibility commitments.

### Testing Strategy

- Use JUnit for protocol/state-machine boundaries and pure codecs.
- Use Fabric GameTests for `ServerPlayer`, world-thread and playerdata behavior.
- Use the dedicated-server 3-JVM E2E for behavior visible to a normal Java
  client.
- Use Flutter analyze/tests/release web builds and Chromium flows for browser
  controller, compatibility and UI boundaries.
- New generic world contracts should include at least one non-vanilla or
  synthetic namespaced-ID acceptance test before they are considered stable, so
  vanilla-only assumptions are caught before later Totem module integration.

### Git Workflow

- Keep architectural work isolated on feature branches and Draft PRs until its
  owning CI suites are green.
- Do not merge a Draft PR unless the user explicitly requests the merge.
- Keep OpenSpec project constraints durable; do not archive project-wide
  extensibility requirements with a single implementation change.

## Domain Context

The current browser flow provides custom account authentication, a stable
server-owned Minecraft identity, real `ServerPlayer` admission, vanilla
playerdata lifecycle, Java-client visibility, and an initial server-authoritative
world bootstrap containing dimension, pose, `gameTime` and Minecraft 26.2
`defaultClockTime`.

The browser does not yet receive chunks/entities/resources/lighting and cannot
send movement, block, inventory, combat or other gameplay actions. `play:false`
remains authoritative until a usable browser gameplay contract exists.

The final compatibility target includes TotemCore and the broader Totem module
family, including Nexus, Remnant, Automata, Excavation, Enchanting, Villagers,
Locksmith and future modules added to the suite. Compatibility must be explicit
and tested; an installed module is not considered browser-supported merely
because the generic connection remains alive.

## Important Constraints

- Future chunk/entity/resource formats MUST be extensible to modded registry
  content from the first stable version. Do not design a vanilla-only wire
  schema that would require a breaking rewrite when Totem modules are added.
- Do not serialize Java implementation class names as browser compatibility
  contracts. Stable namespaced identifiers and explicit protocol versions are
  the compatibility surface.
- Generic world state MUST remain usable when an optional module extension is
  absent, unsupported or newer than the Flutter client.
- Specialized module payloads MUST be bounded, versioned and independently
  rejectable without corrupting the base world session.
- Never move Totem gameplay authority into Dart. The server executes recipes,
  machines, teleport rules, permissions, persistence and other module logic;
  Flutter sends semantic requests and renders authoritative results.
- Do not set `play:true` merely because world metadata, chunks or rendering are
  available. It requires an accepted, usable gameplay input/state contract.
- Do not weaken Java online-mode authentication, bans, whitelist, IP-ban,
  capacity checks or existing Observer security boundaries to simplify browser
  support.

## External Dependencies

- Required runtime compatibility: TotemCore `>=0.7.18 <0.8.0`.
- Optional Totem modules must remain optional from the Observer core world
  protocol's perspective and integrate through explicit extension capability
  boundaries.
