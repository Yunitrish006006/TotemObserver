package dev.totem.observer.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObserverWorldWindowTest {
    @Test void initialWindowPreservesNamespacedDimensionAndNegativeChunkCoordinates() {
        var bootstrap = new ObserverWorldBootstrapService.Snapshot(
                42, "totem_nexus:orbit", -0.01, 80.0, -16.0,
                90.0F, 0.0F, 1200, 1200);

        var window = ObserverWorldWindow.initial(bootstrap);

        assertEquals(42, window.sessionEpoch());
        assertEquals("totem_nexus:orbit", window.dimension());
        assertEquals(-1, window.centerChunkX());
        assertEquals(-1, window.centerChunkZ());
        assertEquals(1, window.radius());
        assertEquals(1, window.revision());
    }

    @Test void chunkCoordinateUsesMinecraftFloorSemantics() {
        assertEquals(0, ObserverWorldWindow.chunkCoordinate(15.999));
        assertEquals(1, ObserverWorldWindow.chunkCoordinate(16.0));
        assertEquals(-1, ObserverWorldWindow.chunkCoordinate(-0.001));
        assertEquals(-1, ObserverWorldWindow.chunkCoordinate(-16.0));
        assertEquals(-2, ObserverWorldWindow.chunkCoordinate(-16.001));
    }
}
