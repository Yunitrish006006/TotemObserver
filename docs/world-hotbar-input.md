# First-person hotbar input

The existing input owner maps captured, focused physical keys 1–9 to slots 0–8.
One unsent latest intent lives for at most 750 ms, expires even while another
operation is pending, and is discarded on focus/capture loss, disconnect or
widget replacement/disposal. Keyboard repeats do not create selections. No item
content is supplied by input. The connection owns wire sequence, cooldown,
authorization bindings and confirmation.

A queued selection waits for previous movement/outline/use responses before
sending. The input loop yields movement dispatch while this bounded intent waits;
server deadman/physics remain authoritative. Use preparation cannot overtake a
queued/in-flight selection. Unsent selections are never replayed after release.
Idle, grounded, captured controls request an authoritative read at most once every
two seconds; held movement/look and use take priority. No renderer polls.

The existing control footer shows the last confirmed slot and item/count, or an
empty-hand/denied/not-yet-synchronized state. It never highlights an optimistic
selection or claims a continuous inventory stream. This text-only debug display
adds no item artwork, container simulation or read-only Screen capability.

Tests cover number-key capture gating, repeats, server-confirmed display, pending
movement, use ordering, queue expiry and capture cleanup. Real browser validation
starts with seven server-fixture sticks in slot one, selects existing empty slot
two through key 2, checks both server selection and conservation, and uses the
lever through the existing authoritative interaction path. Fixture setup alone
sets items; production never deletes onboarding inventory to make use succeed.
A screenshot captures the confirmed hotbar footer. Formal results belong in PR
Validation. Right-click use is provided by the following input slice. `play:false` and read-only Observer Screen safety remain unchanged.
