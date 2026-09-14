# Hotbar client v1

The connection accepts optional `worldHotbarProtocol:1` only alongside compatible
admission, identity, bootstrap and movement capabilities. Absent or zero disables
requests; malformed or incompatible negotiation fails closed. `play:false` stays.

`requestHotbar()` reads; `requestHotbar(slot: 0..8)` selects an existing slot.
Both use the shared sequence/pacer and exact current epoch, subscription,
revision and dimension. False means no request is queued. One request is allowed
at a time, with a 250 ms cooldown and five-second response deadline. Movement,
outline, use/preparation and bootstrap conflict with sending. Pending hotbar
blocks new movement, outline, block use, sections and bootstrap; existing section
reads may finish. The connection retries a required window refresh after the ack.

The typed decoder accepts only the ten protocol keys, strict integer bindings,
three outcomes and either exactly nine two-field item/count entries or a denied
response with null selected/slots. Item ID/count and response size bounds match
the server. No item components are retained. Wrong binding, unexpected outcome,
unsolicited response or timeout disconnects and clears state.

`hotbarSnapshot` is the last server confirmation, not a live inventory stream.
Selection is never applied optimistically; resulting server selection wins even
if a server hook changed the requested slot. A denied result replaces earlier
inventory with no slots. Bootstrap refresh, block-use acknowledgment and
disconnect invalidate the retained snapshot. At most one immutable nine-slot
snapshot is retained. Consumers must request a later snapshot to observe external
inventory changes; there is no automatic polling in this layer.

This slice adds no UI, key binding, resource assets, inventory clicking, item
placement or container capability. Renderers own no requests. Existing read-only
Screen safety is unchanged. Local focused tests cover decoding, immutability,
read/select confirmation, denial, stale/malformed input, cooldown, timeout and
operation exclusions. Formal validation is recorded in the stacked PR.
