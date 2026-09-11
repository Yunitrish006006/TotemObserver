package dev.totem.observer.e2e;

import dev.totem.observer.network.ObserverNativePayloads;
import dev.totem.observer.network.ObserverNativeScreenPayloads;
import dev.totem.observer.network.ObserverPayloads;
import dev.totem.observer.runtime.ObserverSessionManager;
import dev.totem.observer.runtime.ObserverGameRules;
import dev.totem.observer.runtime.ObserverReturnState;
import dev.totem.core.api.v1.social.TotemFriendshipApi;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Test-only coordinator for the dedicated-server + two-client Observer View E2E. */
public final class ObserverE2eCommon implements ModInitializer {
    private static final String TARGET_NAME = "Target";
    private static final String OBSERVER_NAME = "Observer";
    private static final int CLIENT_JOIN_TIMEOUT_TICKS = 20 * 90;
    private static final int PAYLOAD_ADVERTISEMENT_TIMEOUT_TICKS = 20 * 90;

    private static boolean started;
    private static boolean cleanedUp;
    private static int ticks;
    private static int firstClientSeenTick = -1;
    private static int bothConnectAttemptsStartedTick = -1;
    private static int bothClientsSeenTick = -1;
    private static UUID targetId;
    private static UUID observerId;
    private static Vec3 originalPosition;

    @Override
    public void onInitialize() {
        if (!enabled()) {
            return;
        }
        ServerTickEvents.END_SERVER_TICK.register(ObserverE2eCommon::tickServer);
    }

    private static void tickServer(MinecraftServer server) {
        if (cleanedUp) {
            return;
        }
        ticks++;
        try {
            if (!started) {
                ServerPlayer target = findPlayer(server, TARGET_NAME);
                ServerPlayer observer = findPlayer(server, OBSERVER_NAME);
                boolean oneClientPresent = target != null || observer != null;

                if (bothConnectAttemptsStartedTick < 0
                        && markerExists("target-connect-started.txt")
                        && markerExists("observer-connect-started.txt")) {
                    bothConnectAttemptsStartedTick = ticks;
                }

                if (oneClientPresent && firstClientSeenTick < 0) {
                    firstClientSeenTick = ticks;
                    marker(
                            "server-first-client-seen.txt",
                            "tick=" + ticks + "\ntargetPresent=" + (target != null)
                                    + "\nobserverPresent=" + (observer != null) + "\n"
                    );
                }

                if (target == null || observer == null) {
                    if (deadlineExpired(ticks, bothConnectAttemptsStartedTick, CLIENT_JOIN_TIMEOUT_TICKS)) {
                        fail(
                                "server",
                                "Timed out waiting for Target and Observer clients to join; targetPresent="
                                        + (target != null) + ", observerPresent=" + (observer != null)
                        );
                        cleanedUp = true;
                    }
                    return;
                }

                if (bothClientsSeenTick < 0) {
                    bothClientsSeenTick = ticks;
                }

                if (!supportsObserverPayloads(target, observer)) {
                    if (deadlineExpired(
                            ticks,
                            bothClientsSeenTick,
                            PAYLOAD_ADVERTISEMENT_TIMEOUT_TICKS
                    )) {
                        fail("server", "Connected clients never advertised all protocol-native Observer payloads");
                        cleanedUp = true;
                    }
                    return;
                }

                observer.setGameMode(GameType.SURVIVAL);
                originalPosition = observer.position();
                var rules = server.overworld().getGameRules();
                rules.set(ObserverGameRules.ENABLED, false, server);
                if (invokeProductionStart(observer, target) != 0 || observer.isSpectator()) {
                    throw new AssertionError("Disabled Observer changed the player's mode");
                }
                rules.set(ObserverGameRules.ENABLED, true, server);
                rules.set(ObserverGameRules.ALLOW_FRIENDS, true, server);
                TotemFriendshipApi.inviteOrAccept(server, observer.getUUID(), target.getUUID());
                if (invokeProductionStart(observer, target) != 0) {
                    throw new AssertionError("One-way invitation allowed observation");
                }
                TotemFriendshipApi.inviteOrAccept(server, target.getUUID(), observer.getUUID());
                int result = invokeProductionStart(observer, target);
                if (result != 1) {
                    throw new AssertionError("ObserverSessionManager.start returned " + result);
                }

                if (!observer.isSpectator() || !observer.hasAttached(ObserverReturnState.TYPE)) {
                    throw new AssertionError("Survival admission did not preserve a return state");
                }
                targetId = target.getUUID();
                observerId = observer.getUUID();
                if (!targetId.equals(targetMap().get(observerId))) {
                    throw new AssertionError("Production start did not register observer -> target session");
                }

                started = true;
                marker("server-session-started.txt",
                        "observer=" + observerId + "\ntarget=" + targetId
                                + "\nprotocol=" + ObserverNativePayloads.PROTOCOL_VERSION
                                + "\nframebuffer_transport=false\n");
                return;
            }

            boolean sessionPresent = observerId != null && targetMap().containsKey(observerId);
            if (!sessionPresent) {
                ServerPlayer observer = findPlayer(server, OBSERVER_NAME);
                if (observer == null || observer.gameMode.getGameModeForPlayer() != GameType.SURVIVAL
                        || observer.hasAttached(ObserverReturnState.TYPE)
                        || observer.position().distanceToSqr(originalPosition) > 0.01) {
                    throw new AssertionError("Observer stop did not restore survival and original position");
                }
                TotemFriendshipApi.removeRelationship(server, observerId, targetId);
                marker("server-cleanup-ok.txt", "Observer Stop removed protocol-native session state.\n");
                cleanedUp = true;
            }
        } catch (Throwable error) {
            fail("server", error.toString());
            cleanedUp = true;
        }
    }

    private static boolean markerExists(String fileName) {
        return Files.isRegularFile(resultsDir().resolve(fileName));
    }

    private static boolean deadlineExpired(int currentTick, int startedTick, int timeoutTicks) {
        return startedTick >= 0 && currentTick > startedTick + timeoutTicks;
    }

    private static boolean supportsObserverPayloads(ServerPlayer target, ServerPlayer observer) {
        return ServerPlayNetworking.canSend(target, ObserverNativePayloads.NativeControl.TYPE)
                && ServerPlayNetworking.canSend(observer, ObserverNativePayloads.NativeSession.TYPE)
                && ServerPlayNetworking.canSend(observer, ObserverNativePayloads.NativeViewRelay.TYPE)
                && ServerPlayNetworking.canSend(observer, ObserverNativeScreenPayloads.ContainerRelay.TYPE)
                && ServerPlayNetworking.canSend(observer, ObserverPayloads.ScreenRelay.TYPE);
    }

    private static int invokeProductionStart(ServerPlayer observer, ServerPlayer target) {
        try {
            return observer.level().getServer().getCommands().getDispatcher().execute(
                    "observeui " + target.getGameProfile().name(), observer.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            throw new RuntimeException("Failed to execute /observeui as a non-OP friend", error);
        }
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (name.equals(player.getGameProfile().name())) {
                return player;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, UUID> targetMap() {
        return (Map<UUID, UUID>) staticField(ObserverSessionManager.class, "TARGET_BY_OBSERVER");
    }

    private static Object staticField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new RuntimeException("Missing test-observed field " + owner.getSimpleName() + "." + name, error);
        }
    }

    private static boolean enabled() {
        return Boolean.getBoolean("totem.observer.e2e.enabled");
    }

    static Path resultsDir() {
        String configured = System.getProperty("totem.observer.e2e.results", "build/e2e/results");
        return Path.of(configured).toAbsolutePath();
    }

    static void marker(String fileName, String content) {
        try {
            Path directory = resultsDir();
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(fileName), content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new RuntimeException("Failed to write E2E marker " + fileName, error);
        }
    }

    static void fail(String role, String message) {
        marker("failure-" + role + ".txt", message + "\n");
    }
}
