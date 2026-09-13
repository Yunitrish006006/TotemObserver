# Current-world target highlight

The debug scene now draws a thin white outline on the face under the crosshair.
It uses the server-corrected standing-eye camera and the bounded cache raycast;
there is no independent local camera or interaction request.

Each scene build recomputes the suggestion against the current connection and
visible plan. It requires current admission, matching dimension, subscription,
revision and vertical anchor, then requires the exact block/state/face to exist
in the bounded mesh used for this frame. A face omitted by mesh truncation is
not selectable, nor is a hit closer than the near clipping plane. Missing cache, unsupported geometry, a moved camera or stale
world identity removes selection. Targets are not retained across builds.

The highlight uses the same perspective projection and clipping as the terrain.
The outline adds at most one projected face; selection scans at most 32 voxels
and the existing 16,384-face mesh budget. It adds no network/cache owner, protocol
or dependency. Screen-reader semantics identify the selected debug block.

This is a visual suggestion only. The terrain allowlist does not establish
collision, vanilla model geometry or interaction permission. A later gameplay
endpoint must validate its own current server ray and all interaction rules.
`play:false` and the read-only Observer Screen boundary remain unchanged.

Focused scene tests cover current-camera selection, missing/truncated mesh,
evicted cache, changed dimension/revision and admission loss. The real browser
smoke remains part of Build; screenshots and formal validation are recorded in
the stacked PR.
