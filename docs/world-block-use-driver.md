# First vanilla block-use driver

The first `ObserverBlockUse` capability is deliberately limited to empty-hand
use of the exact vanilla lever and stone-button block identities. The driver
derives the target through the server's current outline raycast, checks the
actual held stacks and open menu, and invokes `ServerPlayerGameMode.useItemOn`
with the real main-hand stack and server hit. It never directly toggles state,
invents inventory, accepts client target coordinates or opens a container.

The call is server-thread-only and rechecks the admission owner's authorization
before targeting and immediately before the vanilla action. The vanilla player
load handshake must have completed. Both hands must be empty and the player's
current menu must be its inventory menu. Targeting retains current PlayerList,
reach, loaded-world, world-border and permission checks. Vanilla game-mode rules
remain the final interaction path. The exact block allowlist defines capability
scope, not render geometry or collision truth.

The result distinguishes applied, no target, unsupported and denied outcomes and
includes only the server-derived target when appropriate. Results must not be
retained as authorization. The future transport owner must serialize and rate
limit requests, bind session/sequence/subscription/revision/dimension, reject stale
ingress, and refresh world cache after a successful mutation. There is no network
endpoint in this foundation slice, no retained queue/cache, and `play:false`
remains unchanged. Redstone effects after accepted use are owned by Minecraft.

Container opening, held-item use/placement, destruction, entities and inventory
are separate capabilities. No read-only Observer Screen firewall is relaxed.
Partial lever/button geometry must not be represented as a guessed full cube by
the client; this driver does not change renderer classification.

A registered Fabric GameTest proves vanilla lever/button state changes, revoked
authorization and held-item denial without mutation, and container exclusion.
Dedicated GameTest count increases from 17 to 18. Formal CI results belong in the
stacked PR; this internal driver alone does not make browser use available.
