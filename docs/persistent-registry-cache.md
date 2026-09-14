# Persistent canonical registry cache

Browser restarts retain decoded canonical BlockState name pages in IndexedDB
`totem-observer-registry-v1`, store `cache`, key `latest`. This first persistence
slice covers names/raw IDs only. It does not persist world sections, visual flags,
models, textures, accounts, endpoints, credentials or session bindings.

Every authenticated admission still requests registry page zero. Only its fresh
server fingerprint and total unlock stored pages. The existing canonical-name
fingerprint is unchanged; it does not establish visual metadata identity. Storage
is local render evidence, never collision or gameplay authority. A modified local
cache cannot grant a server action. Page zero from the server overrides disk.

Version 1 stores one structured-clone record with version, fingerprint, total and
up to 256 pages of eight canonical strings (512 characters maximum each). Offset,
page size, canonical syntax, identity and duplicates are checked before accepting
the record. A malformed record is discarded as a whole. The persistent page map
and disk record use the same bounded insertion/access order; another fingerprint
replaces the retained registry. Browser quota/security errors are nonfatal misses.
Browser eviction remains possible. There is no claim of permanent disk retention.

During a bounded two-second restore only additional registry requests wait; world
and movement scheduling retain their existing ownership. Completion must match
the current connection generation and server registry. Disconnect rejects late
results. Visible-policy eviction from the active names map can reload a retained
page without networking. At most one write runs plus one coalesced latest dirty
snapshot; slow writes cannot grow a queue. IndexedDB open/transactions have their
own two-second limits, abort/close cleanup, and version-change close handling.
An unavailable native adapter uses the existing network path; native filesystem
persistence remains a later slice.

The stored strings are already decoded wire content and IndexedDB uses structured
clone rather than downloading/parsing a registry JSON frame on each reuse. This
does not eliminate browser deserialization or later descriptor/mesh work. Dynamic
sections still require a cross-session content validation contract before they can
be reused safely; subscription/revision alone is not persistent content identity.

Validation includes fresh client instances sharing storage, skipped page requests,
active-cache eviction reuse, corruption/fingerprint mismatch/storage failure,
late disconnect completion and bounded immutable page retention. The real browser
smoke closes Chromium, reopens the same profile, authenticates again, requires a
fresh page-zero fingerprint and world sections, then rejects repeated requests
for cached nonzero pages. Formal CI results are recorded in the stacked PR.

## Java 26.2 reference

Inspected the pinned Minecraft 26.2 merged client classes directly with `javap`:
`ClientConfigurationPacketListenerImpl.handleRegistryData/handleSelectKnownPacks`,
`ClientChunkCache.replaceWithPacketData/drop/updateViewCenter/updateViewRadius`,
`ClientPacketListener.handleLevelChunkWithLight/handleChunkBlocksUpdate/handleBlockUpdate/handleLightUpdatePacket/handleForgetLevelChunk`,
and `DownloadedPackSource` with `DownloadQueue.cacheDir`.
These separate configuration/registry, resource downloads and live world updates.
Observer retains that separation. This cache is an Observer extension, not a claim
that vanilla multiplayer persists decoded remote chunks between launches. Future
section validation/deltas must preserve live update/unload ownership separately
from reusable immutable content.

The browser fixture uses a cobblestone floor. It asserts that this canonical name
was saved on a nonzero page, then after Chromium restart uses real mouse input
and server camera correction to aim at the floor. The production scene must
announce `準星選取 minecraft:cobblestone` without re-requesting that page. This
positive consumption check prevents a stalled hydrator from passing merely by
sending no requests.
