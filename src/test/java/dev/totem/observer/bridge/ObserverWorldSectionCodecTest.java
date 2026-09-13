package dev.totem.observer.bridge;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ObserverWorldSectionCodecTest {
    @Test void fourPartsRoundTripAllSectionStateIdsWithinFrameBudget() {
        var states = new int[ObserverWorldSectionCodec.STATES_PER_SECTION];
        for (int index = 0; index < states.length; index++) {
            states[index] = switch (index % 6) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> 127;
                case 3 -> 128;
                case 4 -> 16_383;
                default -> ObserverWorldSectionCodec.MAX_STATE_ID;
            };
        }

        for (int part = 0; part < ObserverWorldSectionCodec.PARTS; part++) {
            String encoded = ObserverWorldSectionCodec.encodePart(states, part);
            assertTrue(encoded.length() <= 4096);
            var decoded = ObserverWorldSectionCodec.decodePart(encoded);
            assertEquals(ObserverWorldSectionCodec.STATES_PER_PART, decoded.size());
            int start = part * ObserverWorldSectionCodec.STATES_PER_PART;
            for (int offset = 0; offset < decoded.size(); offset++) {
                assertEquals(states[start + offset], decoded.get(offset));
            }
        }
    }

    @Test void rejectsMalformedNonCanonicalAndOutOfRangePayloads() {
        var states = new int[ObserverWorldSectionCodec.STATES_PER_SECTION];
        Arrays.fill(states, 1);
        assertThrows(IllegalArgumentException.class, () -> ObserverWorldSectionCodec.encodePart(states, -1));
        states[0] = ObserverWorldSectionCodec.MAX_STATE_ID + 1;
        assertThrows(IllegalArgumentException.class, () -> ObserverWorldSectionCodec.encodePart(states, 0));

        assertThrows(IllegalArgumentException.class, () -> ObserverWorldSectionCodec.decodePart("!!!!"));
        assertThrows(IllegalArgumentException.class, () -> ObserverWorldSectionCodec.decodePart("gAA="));
        assertThrows(IllegalArgumentException.class, () -> ObserverWorldSectionCodec.decodePart("AQ=="));
    }
}
