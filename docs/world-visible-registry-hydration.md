# Visible registry hydration

The application owns a WorldRegistryHydrator next to its visible-world controller
and section scheduler. Every 100 ms it examines at most the current 43 section
references. It rescans raw IDs only when the plan or immutable snapshots change;
registry replies and camera-only corrections do not rescan section contents.
No renderer requests registry or section data.

The bounded selection follows center-first target order, then section raw-ID
order. It includes page zero and at most 255 additional eight-state pages.
At the cap it omits remaining unknown geometry; it does not cycle pages to
pretend an oversized visible palette fits. Requests are serialized by the
connection's pending-registry gate and shared outbound FIFO. At most one page
is requested per pump. Missing/unknown canonical states still fail closed.

Changing visible contents evicts canonical names outside the selected pages.
The connection also enforces an independent 4,096-name ceiling with oldest-page
eviction, covering callers outside this controller. A late response for the
previous selection can add at most eight names; registry identity is immutable
and session/fingerprint-bound, so that response remains valid metadata, without
reviving stale sections. Disconnect clears all names, references and pending
state. Widget disposal cancels the pump.

Registry responses must contain the full requested page (eight states or the
exact final remainder). The pending page has a five-second response deadline;
short/mismatched/stalled responses terminate the connection. Canonical-name
fingerprint semantics and independent server visual facts are unchanged.

Validation covers visible page selection, no repeated requests, pending gating,
replacement eviction, dimension mismatch, bounded high-diversity input, disposal,
connection cache ceiling, malformed short pages and timeout. Formal workflow
results are recorded in the stacked PR. No textures or gameplay truth are
inferred from these names.
