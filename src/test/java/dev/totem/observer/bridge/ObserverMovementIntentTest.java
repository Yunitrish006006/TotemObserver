package dev.totem.observer.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObserverMovementIntentTest {
    @Test void boundsAndNormalizesInputWithoutCoordinates() {
        var input = new ObserverMovementIntent(4, -4, 540, 120, true);
        assertEquals(1, Math.hypot(input.strafe(), input.forward()), 0.00001);
        assertEquals(-180, input.yaw());
        assertEquals(90, input.pitch());
        assertTrue(input.jump());
        assertEquals(0, ObserverMovementIntent.idle(0, 0).forward());
    }

    @Test void rejectsEveryNonFiniteInput() {
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ObserverMovementIntent(bad, 0, 0, 0, false));
            assertThrows(IllegalArgumentException.class, () -> new ObserverMovementIntent(0, bad, 0, 0, false));
            assertThrows(IllegalArgumentException.class, () -> new ObserverMovementIntent(0, 0, bad, 0, false));
            assertThrows(IllegalArgumentException.class, () -> new ObserverMovementIntent(0, 0, 0, bad, false));
        }
    }
}
