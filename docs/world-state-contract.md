# Observer world-state v1 contract

Date: 2026-09-13

## Decision

Step 3 continues with an Observer-owned, server-authoritative gameplay data contract (Route B) while the browser-native Minecraft 26.2 stack remains an experimental compatibility path.

This is deliberately reversible. The bridge keeps the authenticated account/session and vanilla `ServerPlayer` lifetime as the authority boundary; a future browser gameplay core can still replace or consume later world-streaming layers without allowing the browser to choose player identity.

As of this decision, upstream Prismarine 26.2 support is not yet a production dependency of TotemObserver. Independent 26.2 protocol/data forks may be used for isolated compatibility probes, but are not pulled into the Flutter runtime by this change.

## Capability

Authenticated `hello` adds:

```json
{"worldStateProtocol":1}
```

`1` is advertised only when a real Minecraft `ServerPlayer` admission service is attached. Identity-only account fixtures advertise `0`.

This capability does **not** mean chunks, entities, lighting, rendering, movement, interaction, inventory, or other gameplay are available. `play` remains `false`.

## Request

After authentication and `playerAttached:true`, the client may request one snapshot:

```json
{"type":"world_state","seq":0}
```

`seq` shares the account-v1 monotonically increasing request sequence with `ping`. Only one world-state request may be in flight at a time.

## Response

The server captures the admitted player only on the Minecraft server thread and returns:

```json
{
  "type":"world_state",
  "protocol":1,
  "seq":0,
  "sessionEpoch":123,
  "dimension":"minecraft:overworld",
  "x":0.0,
  "y":64.0,
  "z":0.0,
  "yaw":0.0,
  "pitch":0.0
}
```

The response is valid only for the current authenticated `sessionEpoch` and the exact request `seq`. The Flutter client rejects mismatched epochs, mismatched request sequences, malformed dimension identifiers, non-finite coordinates/rotation, and pitch outside the Minecraft view range.

## Authority and threading

- UUID/profile name continue to come only from `ObserverPlaySessionService`.
- `ObserverPlayerAdmissionService` remains the owner of the vanilla `ServerPlayer` lifetime.
- `ServerPlayer` world fields are read only on the Minecraft server thread. Netty does not directly inspect world objects.
- Closing/replacing the account session invalidates the admission and therefore invalidates snapshots.
- A snapshot is metadata, not a simulation clock and not a client prediction authority.

## Acceptance covered by this slice

- Dedicated GameTest: real account WebSocket -> vanilla player admission -> server-thread snapshot -> `world_state` response -> heartbeat.
- Dedicated GameTest: direct admitted-player snapshot contains a finite position/rotation and namespaced dimension.
- Flutter controller/widget test: automatically requests the snapshot after admission, validates the epoch/sequence/schema, displays dimension/position/rotation, and clears it on logout.
- Flutter negative test: a snapshot for a different session epoch is rejected.

## Still blocked from `play:true`

`play` must remain `false` until at least the following exist and have dedicated multiplayer acceptance coverage:

1. initial chunk/section data and registry mapping;
2. entity/player visibility data for the browser client;
3. lighting/time/dimension state needed for correct presentation;
4. authoritative movement input plus server correction;
5. block interaction and collision semantics;
6. disconnect/reconnect/dimension-change resynchronization.

The next Route B slice should define the initial world bootstrap envelope (dimension bounds + chunk subscription identity/versioning) before transporting actual chunk payloads.
