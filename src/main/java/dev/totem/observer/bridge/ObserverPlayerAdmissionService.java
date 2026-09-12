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
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the Minecraft-side lifetime of authenticated Observer players.
 *
 * <p>All world/player mutations are marshalled onto the Minecraft server thread. The browser never
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

    private record Active(Admission admission, ObserverClientConnection connection) {}

    private final MinecraftServer server;
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ObserverPlayerAdmissionService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    /** Completes only after PlayerList admission has run on the Minecraft server thread. */
    public CompletableFuture<Admission> open(ObserverPlaySessionService.Session playSession) {
        Objects.requireNonNull(playSession, "playSession");
        var result = new CompletableFuture<Admission>();
        execute(() -> {
            try {
                if (closed) { result.complete(null); return; }
                result.complete(openNow(playSession));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Safe for the bridge I/O thread: authoritative membership is mirrored in a concurrent map. */
    public boolean valid(ObserverPlaySessionService.Session playSession, Admission admission) {
        if (closed || playSession == null || admission == null || !admission.playSession().equals(playSession)) return false;
        var current = active.get(playSession.account());
        return current != null && current.admission().equals(admission);
    }

    /** Removal is asynchronous when called from a Netty thread; PlayerList.remove performs the save. */
    public void release(Admission admission) {
        if (admission == null) return;
        execute(() -> {
            var current = active.get(admission.playSession().account());
            if (current != null && current.admission().equals(admission)) removeNow(current);
        });
    }

    private Admission openNow(ObserverPlaySessionService.Session playSession) {
        var identity = playSession.identity();
        var playerList = server.getPlayerList();

        var previous = active.get(playSession.account());
        if (previous != null) removeNow(previous);

        if (playerList.getPlayer(identity.uuid()) != null || playerList.getPlayerByName(identity.profileName()) != null) {
            throw new IllegalStateException("Observer player identity is already present");
        }

        var profile = new GameProfile(identity.uuid(), identity.profileName());
        var player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        // Reuse vanilla ban/whitelist/capacity policy. The bridge is loopback-only, so its transport address is loopback.
        if (playerList.canPlayerLogin(BRIDGE_ADDRESS, player.nameAndId()) != null) {
            throw new IllegalStateException("Observer player rejected by server admission policy");
        }

        var connection = new ObserverClientConnection(server);
        boolean placed = false;
        try (var problems = new ProblemReporter.ScopedCollector(player.problemPath(), TotemObserver.LOGGER)) {
            var loaded = playerList.loadPlayerData(player.nameAndId())
                    .map(tag -> TagValueInput.create(problems, player.registryAccess(), tag));
            loaded.ifPresent(player::load);

            playerList.placeNewPlayer(connection, player,
                    new CommonListenerCookie(profile, 0, player.clientInformation(), false));
            placed = true;
            loaded.ifPresent(input -> {
                player.loadAndSpawnEnderPearls(input);
                player.loadAndSpawnParentVehicle(input);
            });
            // Re-apply loaded coordinates to the server game listener/chunk tracker after PlayerList setup.
            player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            var admission = new Admission(playSession, player);
            active.put(playSession.account(), new Active(admission, connection));
            return admission;
        } catch (Throwable failure) {
            if (placed && playerList.getPlayer(player.getUUID()) == player) playerList.remove(player);
            connection.closeEmbeddedChannel();
            throw failure;
        }
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
