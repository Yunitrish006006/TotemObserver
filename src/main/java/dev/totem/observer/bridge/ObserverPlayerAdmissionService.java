package dev.totem.observer.bridge;

import com.mojang.authlib.GameProfile;
import dev.totem.observer.TotemObserver;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the Minecraft-side lifetime of authenticated Observer players.
 *
 * <p>All world/player mutations and snapshots are marshalled onto the Minecraft server thread. The browser never
 * supplies a profile or UUID: those arrive only through {@link ObserverPlaySessionService}.</p>
 */
public final class ObserverPlayerAdmissionService implements AutoCloseable {
    private static final InetSocketAddress BRIDGE_ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    public record Admission(ObserverPlaySessionService.Session playSession, ServerPlayer player) {
        public Admission {
            Objects.requireNonNull(playSession, "playSession");
            Objects.requireNonNull(player, "player");
            if (!playSession.identity().uuid().equals(player.getUUID())) {
                throw new IllegalArgumentException("Player UUID does not match reserved identity");
            }
        }
    }

    /** Minimal server-authoritative state only; this is not a chunk/world streaming contract. */
    public record WorldState(String dimension, double x, double y, double z, float yaw, float pitch) {
        public WorldState {
            Objects.requireNonNull(dimension, "dimension");
            if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Invalid world state");
            }
        }
    }

    private record Active(Admission admission, ObserverClientConnection connection) {}
    private record Pending(ObserverPlaySessionService.Session playSession,
                           CompletableFuture<Admission> result,
                           PrepareSpawnTask spawnTask,
                           ObserverClientConnection connection,
                           CommonListenerCookie cookie) {}

    private final MinecraftServer server;
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    /** Server-thread only; pending spawn preparation is advanced once per Minecraft tick. */
    private final Map<String, Pending> pending = new HashMap<>();
    private volatile boolean closed;

    public ObserverPlayerAdmissionService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        // PrepareSpawnTask deliberately spans ticks while spawn chunks are located/loaded. MinecraftServer
        // has no tickable removal API, so a closed service leaves only this constant-time no-op callback.
        execute(() -> server.addTickable(this::tickPendingAdmissions));
    }

    /** Completes only after vanilla spawn preparation and PlayerList admission have run on the server thread. */
    public CompletableFuture<Admission> open(ObserverPlaySessionService.Session playSession) {
        Objects.requireNonNull(playSession, "playSession");
        var result = new CompletableFuture<Admission>();
        execute(() -> beginOpen(playSession, result));
        return result;
    }

    /** Safe for the bridge I/O thread: authoritative membership is mirrored in a concurrent map. */
    public boolean valid(ObserverPlaySessionService.Session playSession, Admission admission) {
        if (closed || playSession == null || admission == null || !admission.playSession().equals(playSession)) return false;
        var current = active.get(playSession.account());
        return current != null && current.admission().equals(admission);
    }

    /** Captures the currently admitted player's location only on the Minecraft server thread. */
    public CompletableFuture<WorldState> snapshot(Admission admission) {
        var result = new CompletableFuture<WorldState>();
        if (admission == null) {
            result.complete(null);
            return result;
        }
        execute(() -> {
            try {
                if (!valid(admission.playSession(), admission)) {
                    result.complete(null);
                    return;
                }
                var player = admission.player();
                result.complete(new WorldState(
                        player.level().dimension().identifier().toString(),
                        player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Removal is asynchronous when called from a Netty thread; PlayerList.remove performs the save. */
    public void release(Admission admission) {
        if (admission == null) return;
        execute(() -> {
            var current = active.get(admission.playSession().account());
            if (current != null && current.admission().equals(admission)) removeNow(current);
        });
    }

    private void beginOpen(ObserverPlaySessionService.Session playSession, CompletableFuture<Admission> result) {
        if (closed) {
            result.complete(null);
            return;
        }
        try {
            String account = playSession.account();
            var previousPending = pending.get(account);
            if (previousPending != null) cancelPending(previousPending);

            var previous = active.get(account);
            if (previous != null) removeNow(previous);

            var identity = playSession.identity();
            var playerList = server.getPlayerList();
            if (playerList.getPlayer(identity.uuid()) != null || playerList.getPlayerByName(identity.profileName()) != null) {
                throw new IllegalStateException("Observer player identity is already present");
            }

            var nameAndId = new NameAndId(identity.uuid(), identity.profileName());
            // Reuse vanilla ban/whitelist/capacity policy. The bridge is loopback-only, so its transport address is loopback.
            var rejection = playerList.canPlayerLogin(BRIDGE_ADDRESS, nameAndId);
            if (rejection != null) {
                throw new IllegalStateException("Observer player rejected by server admission policy: " + rejection.getString());
            }

            var profile = new GameProfile(identity.uuid(), identity.profileName());
            var clientInformation = ClientInformation.createDefault();
            var connection = new ObserverClientConnection(server);
            var cookie = new CommonListenerCookie(profile, 0, clientInformation, false);
            var spawnTask = new PrepareSpawnTask(server, nameAndId);
            var value = new Pending(playSession, result, spawnTask, connection, cookie);
            pending.put(account, value);
            try {
                // PrepareSpawnTask currently emits no configuration packet from start; if that changes,
                // this embedded admission still has no Minecraft client to consume configuration traffic.
                spawnTask.start(packet -> { });
            } catch (Throwable failure) {
                failPending(value, failure);
            }
        } catch (Throwable failure) {
            TotemObserver.LOGGER.warn("Observer player admission failed for account {}", playSession.account(), failure);
            result.completeExceptionally(failure);
        }
    }

    /** Advances vanilla PlayerSpawnFinder/chunk preparation without blocking the Minecraft server thread. */
    private void tickPendingAdmissions() {
        if (closed || pending.isEmpty()) return;
        for (var value : new ArrayList<>(pending.values())) {
            String account = value.playSession().account();
            if (pending.get(account) != value) continue;
            if (value.result().isCancelled()) {
                cancelPending(value);
                continue;
            }
            try {
                if (!value.spawnTask().tick()) continue;
                ServerPlayer player = value.spawnTask().spawnPlayer(value.connection(), value.cookie());
                value.spawnTask().close();
                if (!pending.remove(account, value)) {
                    if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
                    value.connection().closeEmbeddedChannel();
                    continue;
                }
                var admission = new Admission(value.playSession(), player);
                active.put(account, new Active(admission, value.connection()));
                value.result().complete(admission);
            } catch (Throwable failure) {
                cleanupFailedPlacement(value);
                failPending(value, failure);
            }
        }
    }

    private void cleanupFailedPlacement(Pending value) {
        var identity = value.playSession().identity();
        var player = server.getPlayerList().getPlayer(identity.uuid());
        if (player != null && identity.profileName().equals(player.getGameProfile().name())
                && active.get(value.playSession().account()) == null) {
            server.getPlayerList().remove(player);
        }
    }

    private void failPending(Pending value, Throwable failure) {
        if (!pending.remove(value.playSession().account(), value)) return;
        try {
            value.spawnTask().close();
        } finally {
            value.connection().closeEmbeddedChannel();
        }
        TotemObserver.LOGGER.warn("Observer player admission failed for account {}", value.playSession().account(), failure);
        value.result().completeExceptionally(failure);
    }

    private void cancelPending(Pending value) {
        if (!pending.remove(value.playSession().account(), value)) return;
        try {
            value.spawnTask().close();
        } finally {
            value.connection().closeEmbeddedChannel();
        }
        value.result().complete(null);
    }

    private void removeNow(Active value) {
        if (!active.remove(value.admission().playSession().account(), value)) return;
        var player = value.admission().player();
        if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
        value.connection().closeEmbeddedChannel();
    }

    private void execute(Runnable task) {
        if (server.isSameThread()) task.run();
        else server.execute(task);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        execute(() -> {
            for (var value : new ArrayList<>(pending.values())) cancelPending(value);
            pending.clear();
            for (var value : new ArrayList<>(active.values())) removeNow(value);
            active.clear();
        });
    }

    /**
     * Local packet sink used so vanilla owns a normal ServerGamePacketListenerImpl. World packets are
     * discarded until Step 4. Native keepalive challenges are answered internally so the vanilla
     * listener stays alive; this never creates gameplay input or bypasses the Observer auth session.
     */
    private static final class ObserverClientConnection extends Connection {
        private final MinecraftServer server;
        private final EmbeddedChannel embeddedChannel;
        private volatile ServerCommonPacketListener serverListener;

        ObserverClientConnection(MinecraftServer server) {
            super(PacketFlow.SERVERBOUND);
            this.server = server;
            embeddedChannel = new EmbeddedChannel(this);
        }

        @Override public void setReadOnly() {}
        @Override public void handleDisconnection() {}
        @Override public void setListenerForServerboundHandshake(PacketListener listener) {}
        @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
            if (listener instanceof ServerCommonPacketListener common) serverListener = common;
        }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
                var target = serverListener;
                if (target != null) {
                    server.execute(() -> {
                        if (serverListener == target) target.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
                    });
                }
            }
        }

        void closeEmbeddedChannel() {
            serverListener = null;
            embeddedChannel.close();
        }
    }
}
