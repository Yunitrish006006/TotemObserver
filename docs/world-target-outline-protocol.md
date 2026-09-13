# Server target outline protocol v1

Hello advertises `worldTargetOutlineProtocol:1` only with player admission support;
otherwise it is 0. Older clients may ignore this optional capability. `play:false`
and all read-only Screen boundaries remain unchanged.

`world_target_outline` is a read-only request with exactly eight keys: `type`,
`protocol:1`, shared monotonic `seq`, `sessionEpoch`, `subscriptionId`, `revision`,
`dimension`, and the canonical-name `registryFingerprint`. It accepts no target,
camera, reach, item, entity, or geometry supplied by the browser. The existing
strict scalar parser rejects extra/duplicate keys, coercions and frames >1024
characters. Integers and bindings use the existing gameplay ranges; fingerprints
are exactly 64 lowercase hexadecimal characters.

Admission, authentication, epoch, subscription, revision, dimension and registry
identity must match. At most one capture is pending per connection, with a 200 ms
minimum request interval, a 250 ms server execution age bound, and a five-second
response deadline. A pending movement, use, bootstrap or required world refresh
rejects capture. Movement, use and bootstrap cannot begin while capture is
pending. Existing in-flight section reads can finish; a renderer never sends
section requests. Stale, malformed, expired or mismatched operations close the
connection. There is no target queue or target cache on the server.

On the Minecraft server thread, the existing loaded-only targeting code selects
the nearest vanilla outline hit and captures its bounded vanilla selection shape.
The same task captures player XYZ, yaw/pitch, eye Y and server tick. Authorization
and dimension are checked again after capture and before the event-loop reply.
Missing terrain, no hit or unsupported shape yields `target:null`, which is
nonfatal and does not request/generate terrain. A hit never grants permission to
use or mine it; each later interaction must independently validate current state.

The response has exactly 16 keys: the eight request keys plus `serverTick`, `x`,
`y`, `z`, `yaw`, `pitch`, `eyeY`, and `target`. A non-null target has exactly six
keys: integer `x/y/z`, lowercase vanilla `face`, registry `rawId`, and `boxes`.
Each of 1–16 boxes has exactly six finite numbers in minXYZ/maxXYZ order, local
to the target block, bounded to [-1,2] and strictly ordered on each axis. Capture
checks at most 17 grid coordinates per axis before shape enumeration. The encoded
response has an 8192-character hard ceiling.

Consumers must bind the response to the pending request and current session,
window revision, dimension, registry, and authoritative camera. They must clear
the outline on camera/world generation change, disconnect, unsupported/no target
or failed validation; never render a stale outline over a newer camera or cache.
This server slice does not yet add client polling or painting. Selection boxes
are not render models, collision truth, full-cube evidence, or cached block data.
Registry fingerprint semantics are unchanged.

Validation uses ordinary strict-schema unit tests and a registered dedicated
GameTest for real lever partial geometry, server camera/raw-ID bindings, no world
mutation, nonfatal no-target, replay and hostile request rejection. The expected
complete dedicated count is 23. A deterministic clock test separately rejects
expired ingress and revoked authorization. Rate and overlap guards are inspected
in this slice; the wire scenarios do not independently prove their timing. Full CI results are recorded in the stacked PR.
