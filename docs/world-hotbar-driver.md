# Vanilla hotbar selection driver

`ObserverHotbar.select` is a server-thread operation for an admitted player. It
accepts only a slot index 0–8 and a live authorization predicate, never an item,
count, inventory content, target or location. It rejects absent/replaced players,
unloaded connections, dead/spectating/sleeping players, open containers and revoked
authorization. Authorization is checked again immediately before mutation.

The driver reuses Minecraft 26.2 `handleSetCarriedItem` with a bounded vanilla
packet object. That path changes selected slot, stops main-hand item use when
switching slots, and updates the action timestamp. It never accepts a browser
inventory mutation. This is not enabling the Java wire protocol in the browser.

A bounded immutable nine-slot projection includes only canonical item IDs and
counts (0–999), plus the resulting selected slot. Empty slots use the explicit
server `ItemStack.isEmpty()` fact. The snapshot sends no custom names, lore, NBT,
components, container contents or private onboarding-book data. Unsupported
projection data fails closed before changing the selection. After vanilla item-use
events finish, a second bounded capture confirms resulting inventory. A failure
to represent that post-state fails closed and does not fabricate a confirmation
or attempt to undo vanilla events. No cache is retained.

This slice contains no transport endpoint, rate/sequence handling or UI. A later
versioned contract must supply session/admission/sequence/rate checks and current
dimension binding before invoking the driver, then acknowledge server state.
It does not bypass empty-hand block-use rules, grant items, open containers,
enable play:true or weaken Observer read-only Screen capabilities.

The registered dedicated GameTest checks vanilla item-use cancellation and slot
selection, preservation of all inventory stacks including components, bounded
immutable snapshot data, invalid slots, revocation and spectator rejection.
Expected complete dedicated count: 24. Actual validation belongs to the PR.
