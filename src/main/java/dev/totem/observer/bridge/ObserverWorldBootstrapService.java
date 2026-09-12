package dev.totem.observer.bridge;

import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Captures the first server-authoritative world state exposed to the Flutter client.
 *
 * <p>This is deliberately not a chunk stream and does not accept gameplay input. The snapshot is
 * read only on the Minecraft server thread and is bound to the authenticated play-session epoch so
 * a late result from a replaced connection cannot initialize the new browser session.</p>
 */
final class ObserverWorldBootstrapService {
    static final int PROTOCOL = 1;
    private static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    record Snapshot(long sessionEpoch, String dimension,
                    double x, double y, double z, float yaw, float pitch,
                    long gameTime, long dayTime) {
        Snapshot {
            if (sessionEpoch <= 0 || dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("Invalid world bootstrap identity");
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Invalid world bootstrap pose");
            }
            if (!safeInteger(gameTime) || !safeInteger(dayTime)) {
                throw new IllegalArgumentException("World time exceeds browser integer range");
            }
        }
    }

    private final MinecraftServer server;

    ObserverWorldBootstrapService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    CompletableFuture<Snapshot> capture(ObserverPlayerAdmissionService.Admission admission) {
        Objects.requireNonNull(admission, "admission");
        var result = new CompletableFuture<Snapshot>();
        execute(() -> {
            try {
                var player = admission.player();
                if (server.getPlayerList().getPlayer(player.getUUID()) != player) {
                    throw new IllegalStateException("Observer player is no longer admitted");
                }
                var level = player.level();
                result.complete(new Snapshot(
                        admission.playSession().epoch(),
                        level.dimension().identifier().toString(),
                        player.getX(), player.getY(), player.getZ(),
                        player.getYRot(), player.getXRot(),
                        level.getGameTime(), level.getDayTime()));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void execute(Runnable task) {
        if (server.isSameThread()) task.run();
        else server.execute(task);
    }

    private static boolean safeInteger(long value) {
        return value >= -MAX_SAFE_INTEGER && value <= MAX_SAFE_INTEGER;
    }
}
