package dev.totem.observer.bridge;

import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObserverBlockStateRegistryTest {
    @Test void visualFlagsMatchAuthoritativeBlockStateMetadata() {
        int total = Block.BLOCK_STATE_REGISTRY.size();
        assertTrue(total > 0);

        int offset = 0;
        int airStates = 0;
        int occludingStates = 0;
        String fingerprint = null;
        while (offset < total) {
            var page = ObserverBlockStateRegistry.page(offset);
            if (fingerprint == null) fingerprint = page.fingerprint();
            assertEquals(fingerprint, page.fingerprint());
            assertEquals(total, page.total());
            assertEquals(offset, page.offset());
            assertEquals(page.states().size(), page.visualFlags().size());
            assertFalse(page.states().isEmpty());
            assertTrue(page.states().size() <= ObserverBlockStateRegistry.PAGE_SIZE);

            for (int index = 0; index < page.states().size(); index++) {
                int rawId = offset + index;
                var state = Block.BLOCK_STATE_REGISTRY.byId(rawId);
                assertNotNull(state);
                int flags = page.visualFlags().get(index);
                assertEquals(state.isAir(), (flags & ObserverBlockStateRegistry.VISUAL_AIR) != 0);
                assertEquals(state.canOcclude(), (flags & ObserverBlockStateRegistry.VISUAL_CAN_OCCLUDE) != 0);
                assertFalse((flags & ObserverBlockStateRegistry.VISUAL_AIR) != 0
                        && (flags & ObserverBlockStateRegistry.VISUAL_CAN_OCCLUDE) != 0);
                if ((flags & ObserverBlockStateRegistry.VISUAL_AIR) != 0) airStates++;
                if ((flags & ObserverBlockStateRegistry.VISUAL_CAN_OCCLUDE) != 0) occludingStates++;
            }
            offset += page.states().size();
        }

        assertEquals(total, offset);
        assertTrue(airStates > 0);
        assertTrue(occludingStates > 0);
    }

    @Test void visualMetadataDoesNotChangeCanonicalRegistryFingerprint() {
        var first = ObserverBlockStateRegistry.page(0);
        assertTrue(first.fingerprint().matches("[0-9a-f]{64}"));
        assertEquals(first.states().size(), first.visualFlags().size());
    }
}
