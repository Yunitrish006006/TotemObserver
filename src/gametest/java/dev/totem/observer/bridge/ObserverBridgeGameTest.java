package dev.totem.observer.bridge;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.*;

/** Real WebSocket/player exchange inside the dedicated Minecraft runtime; networking never blocks ticks. */
public final class ObserverBridgeGameTest {
    @GameTest(maxTicks = 100_000)
    public void dedicatedRuntimeSupportsBootstrapExchange(GameTestHelper helper) {
        exchange(helper, false);
    }

    @GameTest(maxTicks = 100_000)
    public void dedicatedRuntimeSupportsAccountExchange(GameTestHelper helper) {
        exchange(helper, true);
    }

    @GameTest(maxTicks = 100_000)
    public void admittedPlayerReplacementSavesAndReloadsVanillaPlayerData(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String account = "gt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var playSessions = new ObserverPlaySessionService();
        var admissions = new ObserverPlayerAdmissionService(server);
        var authA = new ObserverAccountService.Session(account, UUID.randomUUID());
        var playA = playSessions.open(authA);

        var result = admissions.open(playA).thenCompose(first -> {
            if (first == null) throw new AssertionError("Observer player admission returned null");
            var playerA = first.player();
            if (server.getPlayerList().getPlayer(playerA.getUUID()) != playerA) {
                throw new AssertionError("Admitted Observer player is not in PlayerList");
            }

            playerA.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            playerA.setHealth(13.0F);
            UUID expectedId = playerA.getUUID();
            String expectedName = playerA.getGameProfile().name();

            // Re-authentication replaces the active identity without explicitly releasing the old admission first.
            var authB = new ObserverAccountService.Session(account, UUID.randomUUID());
            var playB = playSessions.open(authB);
            return admissions.open(playB).thenAccept(second -> {
                if (second == null || second.player() == playerA
                        || !second.player().getUUID().equals(expectedId)
                        || !second.player().getGameProfile().name().equals(expectedName)) {
                    throw new AssertionError("Observer replacement did not restore the same server-owned identity");
                }
                if (server.getPlayerList().getPlayer(expectedId) != second.player()) {
                    throw new AssertionError("Replacement Observer player is not the sole PlayerList entry for its UUID");
                }
                if (admissions.valid(playA, first)) {
                    throw new AssertionError("Replaced Observer admission remained valid");
                }
                if (!second.player().getInventory().getItem(0).is(Items.DIAMOND)
                        || second.player().getInventory().getItem(0).getCount() != 7
                        || second.player().getHealth() != 13.0F) {
                    throw new AssertionError("Observer replacement did not reload vanilla playerdata");
                }

                // A delayed cleanup from the old socket must not remove the replacement player.
                admissions.release(first);
                if (server.getPlayerList().getPlayer(expectedId) != second.player()
                        || !admissions.valid(playB, second)) {
                    throw new AssertionError("Stale Observer release removed the replacement player");
                }

                admissions.release(second);
                playSessions.release(playB);
                if (server.getPlayerList().getPlayer(expectedId) != null) {
                    throw new AssertionError("Released Observer player remained in PlayerList");
                }
            });
        });
        result.whenComplete((ignored, failure) -> {
            admissions.close();
            playSessions.close();
        });

        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for asynchronous Observer spawn preparation");
            result.join();
        }).thenSucceed();
    }

    private void exchange(GameTestHelper helper, boolean authenticated) {
        var server = helper.getLevel().getServer();
        var result = CompletableFuture.runAsync(() -> {
            java.nio.file.Path temporary = null;
            try {
                if (authenticated) temporary = java.nio.file.Files.createTempDirectory("observer-account-gametest-");
                var accounts = authenticated ? new ObserverAccountService(
                        new ObserverAccountStore(temporary.resolve("accounts.properties")), true) : null;
                var playSessions = authenticated ? new ObserverPlaySessionService() : null;
                var admissions = authenticated ? new ObserverPlayerAdmissionService(server) : null;
                try (var bridge = authenticated
                        ? new ObserverBridgeServer(accounts, playSessions, admissions)
                        : new ObserverBridgeServer(accounts);
                     var client = HttpClient.newHttpClient()) {
                    int port = bridge.start(0, "http://localhost:8080");
                    var pong = new CompletableFuture<Void>();
                    var listener = new WebSocket.Listener() {
                        private final StringBuilder text = new StringBuilder();
                        @Override public void onOpen(WebSocket socket) { socket.request(1); }
                        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                            text.append(data);
                            if (last) {
                                String message = text.toString();
                                text.setLength(0);
                                if (authenticated && message.startsWith("{\"type\":\"hello\"")) {
                                    if (!message.contains("\"playerAdmission\":true")) {
                                        pong.completeExceptionally(new IllegalStateException("Player admission capability missing"));
                                    } else {
                                        socket.sendText("{\"type\":\"register\",\"username\":\"gametest\",\"password\":\"isolated-test-password\"}", true);
                                    }
                                } else if (message.startsWith("{\"type\":\"authenticated\"")) {
                                    if (authenticated && !message.contains("\"playerAttached\":true")) {
                                        pong.completeExceptionally(new IllegalStateException("Authenticated player was not attached"));
                                    } else {
                                        socket.sendText("{\"type\":\"ping\",\"seq\":0}", true);
                                    }
                                } else if (message.equals("{\"type\":\"hello\",\"protocol\":1,\"capabilities\":[\"ping\"],\"play\":false}")) {
                                    socket.sendText("{\"type\":\"ping\",\"seq\":0}", true);
                                } else if (message.equals("{\"type\":\"pong\",\"seq\":0}")) pong.complete(null);
                                else pong.completeExceptionally(new IllegalStateException("Unexpected bridge response"));
                            }
                            socket.request(1);
                            return null;
                        }
                        @Override public void onError(WebSocket socket, Throwable error) { pong.completeExceptionally(error); }
                    };
                    var socket = client.newWebSocketBuilder().header("Origin", "http://localhost:8080")
                            .subprotocols(authenticated ? ObserverAuthenticatedExchange.PROTOCOL : ObserverBridgeServer.PROTOCOL)
                            .buildAsync(URI.create("ws://127.0.0.1:" + port + ObserverBridgeServer.PATH), listener)
                            .get(5, TimeUnit.SECONDS);
                    try {
                        pong.get(9, TimeUnit.SECONDS);
                        if (authenticated) {
                            UUID expectedId = ObserverPlayerIdentity.forAccount("gametest").uuid();
                            awaitPlayerPresence(server, expectedId, true);
                            socket.abort();
                            awaitPlayerPresence(server, expectedId, false);
                        }
                    } finally {
                        if (!socket.isOutputClosed()) socket.abort();
                    }
                }
            } catch (Exception e) { throw new CompletionException(e); }
            finally {
                if (temporary != null) {
                    try { java.nio.file.Files.deleteIfExists(temporary.resolve("accounts.properties")); java.nio.file.Files.deleteIfExists(temporary); }
                    catch (java.io.IOException ignored) { }
                }
            }
        }).orTimeout(20, TimeUnit.SECONDS);
        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for bridge exchange");
            result.join();
        }).thenSucceed();
    }

    private static void awaitPlayerPresence(MinecraftServer server, UUID playerId, boolean expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var check = new CompletableFuture<Boolean>();
            server.execute(() -> {
                try {
                    check.complete((server.getPlayerList().getPlayer(playerId) != null) == expected);
                } catch (Throwable failure) {
                    check.completeExceptionally(failure);
                }
            });
            if (check.get(2, TimeUnit.SECONDS)) return;
            Thread.sleep(20L);
        }
        throw new IllegalStateException("Timed out waiting for Observer player presence=" + expected);
    }
}
