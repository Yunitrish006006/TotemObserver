package dev.totem.observer.bridge;

/**
 * Bounded world-stream control plane derived from the authoritative world bootstrap.
 *
 * <p>The window identifies chunks only. It intentionally carries no block, entity, resource or
 * lighting payload so those future codecs can remain registry-driven and independently versioned.</p>
 */
final class ObserverWorldWindow {
    static final int PROTOCOL = 1;
    static final int INITIAL_RADIUS = 1;
    static final long INITIAL_REVISION = 1;
    private static final int MAX_RADIUS = 8;
    private static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    record Snapshot(long sessionEpoch, String dimension, int centerChunkX, int centerChunkZ,
                    int radius, long revision) {
        Snapshot {
            if (sessionEpoch <= 0 || dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("Invalid world-window identity");
            }
            if (radius < 0 || radius > MAX_RADIUS || revision <= 0 || revision > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("Invalid world-window bounds");
            }
        }
    }

    private ObserverWorldWindow() {}

    static Snapshot initial(ObserverWorldBootstrapService.Snapshot bootstrap) {
        if (bootstrap == null) throw new IllegalArgumentException("World bootstrap is required");
        return new Snapshot(
                bootstrap.sessionEpoch(), bootstrap.dimension(),
                chunkCoordinate(bootstrap.x()), chunkCoordinate(bootstrap.z()),
                INITIAL_RADIUS, INITIAL_REVISION);
    }

    static int chunkCoordinate(double blockCoordinate) {
        if (!Double.isFinite(blockCoordinate)) {
            throw new IllegalArgumentException("Invalid block coordinate");
        }
        long block = (long) Math.floor(blockCoordinate);
        long chunk = Math.floorDiv(block, 16L);
        if (chunk < Integer.MIN_VALUE || chunk > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Chunk coordinate outside protocol range");
        }
        return (int) chunk;
    }
}
