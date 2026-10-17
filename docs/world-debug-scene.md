# Debug perspective scene

The account screen now displays bounded colored terrain from the current
visible-world cache. The existing scheduler/controller is still the only request
owner. No protocol or capability changes; play remains false.

The camera uses server world_state feet XYZ plus a provisional standing eye
offset of 1.62, and Minecraft yaw/pitch (yaw zero south, positive pitch down).
Vertical FOV is 70 degrees, near clip 0.08 blocks, far clip 64 blocks. Viewport
aspect follows Flutter layout. Mouse/keyboard input now belongs to the separate [input owner](world-first-person-input.md)
and versioned movement protocol. Pose-specific eye height remains future work.

## Rendering and bounds

The scene visits at most the visible policy's 43 sections, with the mesher's
4096-cell and 8192-face per-section bounds, and retains at most 16384 faces total.
Missing cache entries are skipped. Source and neighboring keys are validated by
the mesher. Only explicitly listed basic debug terrain is approximated as cubes;
fluid, partial, cutout and translucent models remain unsupported.

Faces are backface-culled, clipped against all six camera frustum planes before
perspective division, and painted far to near by mean clipped depth. This
diagnostic ordering is adequate for ordinary nearby terrain, but is not a
per-pixel depth buffer and may show ordering artifacts in overlapping projections.
No model, texture, lighting or faithful resource-pack rendering is claimed.
Colors use a deterministic canonical block-ID hash plus fixed face shading;
numeric registry order carries no semantic meaning.

## Lifetime and security

Collection reads the live connection's registry and snapshots synchronously,
with no persistent mesh or derived registry cache. Each rebuild collects anew,
so revision/session/registry replacement and evictions cannot retain old meshes.
The parent connection listener rebuilds the view on state/cache changes.
Collection rejects offline/unadmitted state, missing registry/world/bootstrap,
dimension mismatch and stale plan keys. The renderer never sends packets or
mutates player, world, inventory or the Observer read-only Screen infrastructure.

## Validation

Projection tests exercise perspective, far-to-near ordering, near/side clipping,
backface rejection, Minecraft yaw/pitch, invalid inputs and stable colors.
Cache tests cover eviction and stale revision. Widget tests cover partial cache
and resize. Formal Build, 3-JVM E2E and Runtime Validation evidence is recorded
in the stacked PR; the existing browser screenshot covers account flow only (its fixture has no
admitted world). A reproducible terrain fixture is painted through the production
Canvas path and saved as build/account-browser-evidence/debug-scene-fixture.png;
Build uploads it with browser evidence. This is renderer evidence, not proof of
live-server movement or a gameplay E2E.
