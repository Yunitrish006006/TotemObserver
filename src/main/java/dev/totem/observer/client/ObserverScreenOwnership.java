package dev.totem.observer.client;

import dev.totem.core.api.v1.client.observer.ObserverScreenProvider;
import net.minecraft.client.gui.screens.Screen;
import java.util.Collection;

/** Local ownership is separate from whether a peer can receive an owner's snapshots. */
public final class ObserverScreenOwnership {
    private ObserverScreenOwnership() { }
    public static boolean claims(Screen screen, Collection<ObserverScreenProvider> candidates, long sequence) {
        if (screen == null) return false;
        for (var provider : candidates) {
            // Optional ownership contract v1: the provider may also implement Predicate<Screen>.
            // Ownership is independent of successful encoding or peer capability negotiation.
            if (provider instanceof java.util.function.Predicate<?> predicate) {
                try {
                    @SuppressWarnings("unchecked") var owner = (java.util.function.Predicate<Screen>)predicate;
                    if (owner.test(screen)) { return true; }
                } catch (RuntimeException invalidOwnership) {
                    return true; // An invalid owner cannot grant a native fallback.
                }
                continue;
            }
            try {
                var snapshot = provider.capture(screen, sequence);
                if (snapshot.isPresent() && provider.supports(snapshot.orElseThrow())) {
                    return true;
                }
            } catch (RuntimeException invalid) {
                // The ordinary owner capture path logs and closes invalid states.
            }
        }
        return false;
    }

}
