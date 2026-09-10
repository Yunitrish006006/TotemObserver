package dev.totem.observer.client;

import dev.totem.core.api.v1.client.observer.ObserverReadOnlyScreen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObserverReadOnlyPacketFirewallTest {
    @Test
    void markerMethodDistinguishesProductionAndObserverModes() {
        ObserverReadOnlyScreen production = new Marked(false);
        ObserverReadOnlyScreen observer = new Marked(true);
        assertFalse(ObserverOwnedScreenCoordinator.isReadOnlyObserverScreen(production));
        assertTrue(ObserverOwnedScreenCoordinator.isReadOnlyObserverScreen(observer));
    }

    private record Marked(boolean readOnly) implements ObserverReadOnlyScreen {
        @Override
        public boolean totem$isObserverReadOnly() {
            return readOnly;
        }
    }
}
