## ADDED Requirements

### Requirement: Negotiated world-window control plane
The authenticated bridge SHALL advertise a versioned world-window capability only when an admitted-player world bootstrap is available, and SHALL send the initial window after the bootstrap without enabling gameplay.

#### Scenario: Initial control plane is advertised
- **WHEN** a browser authenticates to a dedicated server with player admission and world bootstrap enabled
- **THEN** the hello advertises `worldWindowProtocol: 1`
- **AND** the server sends `world_bootstrap` followed by `world_window`
- **AND** both messages retain `play:false`

### Requirement: Session-bound bounded chunk identity
A world window SHALL be bound to the active session epoch and namespaced dimension, SHALL use bounded chunk coordinates/radius and a positive revision, and the initial center SHALL be the chunk containing the authoritative bootstrap pose.

#### Scenario: Negative Minecraft coordinates use floor semantics
- **WHEN** the authoritative player position is slightly below zero on an axis
- **THEN** the corresponding center chunk is `-1`, not `0`

#### Scenario: Stale or mismatched window is rejected
- **WHEN** Flutter receives a window with a different session epoch, dimension, invalid bounds or an initial center that does not match the bootstrap pose
- **THEN** the browser session fails closed rather than accepting the window into world state

### Requirement: Mod-extensible chunk identity boundary
Generic chunk identity SHALL use a namespaced dimension plus numeric chunk coordinates and SHALL NOT depend on a closed vanilla dimension/content enum. Future block, entity and resource payloads SHALL remain independently versioned and registry-driven.

#### Scenario: Non-vanilla namespace remains valid
- **WHEN** a valid window uses a dimension such as `totem_nexus:orbit`
- **THEN** the generic world-state layer accepts and preserves that namespaced identity without requiring Nexus-specific code

### Requirement: No premature chunk payload or gameplay authority
The world-window capability SHALL identify the bounded chunk set only. It SHALL NOT transmit block palettes, lighting, entities, resources or permit movement, block, inventory or combat actions in this change.

#### Scenario: Window synchronization completes while gameplay remains disabled
- **WHEN** Flutter receives a valid initial world window
- **THEN** it can expose the derived chunk identities for future cache population
- **AND** no chunk content is rendered from this message
- **AND** `play:false` remains authoritative
