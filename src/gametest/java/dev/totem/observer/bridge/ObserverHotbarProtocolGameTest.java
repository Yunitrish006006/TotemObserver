package dev.totem.observer.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;

/** Real read-only hotbar wire capture and hostile binding validation. */
public final class ObserverHotbarProtocolGameTest {
    @GameTest(maxTicks = 100_000)
    public void expiredOrRevokedHotbarFailsClosed(GameTestHelper helper) {
        var clock = new java.util.concurrent.atomic.AtomicLong(TimeUnit.SECONDS.toNanos(1));
        var sessions = new ObserverPlaySessionService();
        var admissions = new ObserverPlayerAdmissionService(helper.getLevel().getServer(), clock::get);
        var auth = new ObserverAccountService.Session("hotbar_" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        var result = admissions.open(sessions.open(auth)).thenCompose(admission -> {
            require(admission != null, "Missing expiry admission");
            String dimension = admission.player().level().dimension().identifier().toString();
            return admissions.hotbar(admission, dimension, null, clock.get() - TimeUnit.MILLISECONDS.toNanos(251), () -> true)
                    .thenCompose(expired -> {
                        require(expired == null, "Expired hotbar captured");
                        return admissions.hotbar(admission, dimension, null, clock.get(), () -> false);
                    }).thenAccept(revoked -> require(revoked == null, "Revoked hotbar captured"));
        });
        result.whenComplete((ignored, failure) -> { admissions.close(); sessions.close(); });
        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for hotbar expiry capture");
            result.join();
        }).thenSucceed();
    }

    // Dedicated GameTests tick faster than real time; account hashing and the
    // real socket scenarios are bounded by the 45-second future deadline below.
    @GameTest(maxTicks = 1_000_000)
    public void authenticatedHotbarReadsSelectsAndRejectsHostileRequests(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var pos = helper.absolutePos(new BlockPos(1, 2, 1));
        var currentScenario = new java.util.concurrent.atomic.AtomicInteger(-1);
        var result = CompletableFuture.runAsync(() -> {
            java.nio.file.Path directory = null;
            try {
                directory = Files.createTempDirectory("observer-hotbar-wire-");
                var accounts = new ObserverAccountService(new ObserverAccountStore(directory.resolve("accounts.properties")), true);
                var sessions = new ObserverPlaySessionService();
                var admissions = new ObserverPlayerAdmissionService(server);
                try (var bridge = new ObserverBridgeServer(accounts, sessions, admissions);
                     var client = HttpClient.newHttpClient()) {
                    int port = bridge.start(0, "http://localhost:8080");
                    for (int scenario = 0; scenario < 10; scenario++) {
                        currentScenario.set(scenario);
                        var inbox = new Inbox();
                        var socket = client.newWebSocketBuilder().header("Origin", "http://localhost:8080")
                                .subprotocols(ObserverAuthenticatedExchange.PROTOCOL)
                                .buildAsync(URI.create("ws://127.0.0.1:" + port + ObserverBridgeServer.PATH), inbox)
                                .get(5, TimeUnit.SECONDS);
                        try {
                            var hello = inbox.take("hello");
                            require(hello.get("worldHotbarProtocol").getAsInt() == 1 && !hello.get("play").getAsBoolean(),
                                    "Hotbar capability/play boundary");
                            String name = "use_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                            send(socket, "{\"type\":\"register\",\"username\":\"" + name
                                    + "\",\"password\":\"hotbar-fixture-password\"}");
                            var auth = inbox.take("authenticated");
                            var uuid = UUID.fromString(auth.get("playerUuid").getAsString());
                            var playerRef = new java.util.concurrent.atomic.AtomicReference<net.minecraft.server.level.ServerPlayer>();
                            onServer(server, () -> {
                                var player = server.getPlayerList().getPlayer(uuid);
                                require(player != null, "Missing admitted player");
                                playerRef.set(player);
                                player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                                player.setGameMode(GameType.SURVIVAL);
                                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                                player.getInventory().setItem(0, new ItemStack(net.minecraft.world.item.Items.APPLE, 3));
                                player.getInventory().setItem(2, new ItemStack(net.minecraft.world.item.Items.STICK, 7));
                                player.getInventory().setSelectedSlot(0);
                                player.startUsingItem(InteractionHand.MAIN_HAND);
                            });
                            if (scenario == 1) onServer(server, () -> playerRef.get().setGameMode(GameType.SPECTATOR));
                            send(socket, "{\"type\":\"world_bootstrap\",\"seq\":0}");
                            var bootstrap = inbox.take("world_bootstrap");
                            var request = hotbar(auth, bootstrap, 1);
                            if (scenario >= 2) {
                                switch (scenario) {
                                    case 2 -> request.addProperty("sessionEpoch", auth.get("sessionEpoch").getAsLong() + 1);
                                    case 3 -> request.addProperty("revision", bootstrap.get("revision").getAsInt() + 1);
                                    case 4 -> request.addProperty("subscriptionId", bootstrap.get("subscriptionId").getAsInt() + 1);
                                    case 5 -> request.addProperty("dimension", "minecraft:the_nether");
                                    case 6 -> request.addProperty("seq", 0);
                                    case 7 -> request.addProperty("x", pos.getX());
                                    case 8 -> request.addProperty("protocol", 2);
                                    case 9 -> request.addProperty("slot", 9);
                                }
                                send(socket, request.toString());
                                inbox.closed.get(5, TimeUnit.SECONDS);
                                onServer(server, () -> require(playerRef.get().getInventory().getSelectedSlot() == 0
                                        && playerRef.get().getInventory().getItem(2).getCount() == 7, "Hostile request mutated inventory"));
                                require(inbox.messages.isEmpty(), "Hostile hotbar was acknowledged");
                            } else {
                                send(socket, request.toString());
                                var hotbar = inbox.take("world_hotbar");
                                require(hotbar.size() == 10 && hotbar.get("protocol").getAsInt() == 1
                                        && hotbar.get("seq").getAsInt() == 1
                                        && hotbar.get("sessionEpoch").equals(auth.get("sessionEpoch"))
                                        && hotbar.get("subscriptionId").equals(bootstrap.get("subscriptionId"))
                                        && hotbar.get("revision").equals(bootstrap.get("revision"))
                                        && hotbar.get("dimension").equals(bootstrap.get("dimension")), "Hotbar response binding");
                                if (scenario == 0) {
                                    require(hotbar.get("outcome").getAsString().equals("snapshot") && hotbar.get("selected").getAsInt() == 0,
                                            "Read request changed selection");
                                    var slots = hotbar.getAsJsonArray("slots");
                                    require(slots.size() == 9 && slots.get(0).getAsJsonObject().size() == 2
                                            && slots.get(0).getAsJsonObject().get("count").getAsInt() == 3, "Bounded hotbar missing");
                                    onServer(server, () -> require(playerRef.get().isUsingItem(), "Read stopped item use"));
                                    Thread.sleep(220);
                                    var selection = hotbar(auth, bootstrap, 2); selection.addProperty("slot", 2);
                                    send(socket, selection.toString());
                                    var selected = inbox.take("world_hotbar");
                                    require(selected.get("outcome").getAsString().equals("selected")
                                            && selected.get("selected").getAsInt() == 2
                                            && selected.getAsJsonArray("slots").get(2).getAsJsonObject().get("item").getAsString().equals("minecraft:stick"),
                                            "Selection confirmation missing");
                                    onServer(server, () -> require(playerRef.get().getInventory().getSelectedSlot() == 2
                                            && playerRef.get().getInventory().getItem(2).getCount() == 7
                                            && !playerRef.get().isUsingItem(), "Vanilla selection not backed by player"));
                                    send(socket, selection.toString()); inbox.closed.get(5, TimeUnit.SECONDS);
                                    require(inbox.messages.isEmpty(), "Replay acknowledged");
                                } else {
                                    require(hotbar.get("outcome").getAsString().equals("denied") && hotbar.get("selected").isJsonNull()
                                            && hotbar.get("slots").isJsonNull(), "Denied read exposed inventory");
                                    send(socket, "{\"type\":\"ping\",\"seq\":2}"); inbox.take("pong");
                                }
                            }
                        } finally { socket.abort(); }
                    }
                }
            } catch (Exception failure) { throw new CompletionException(failure); }
            finally {
                if (directory != null) {
                    try {
                        Files.deleteIfExists(directory.resolve("accounts.properties"));
                        Files.deleteIfExists(directory);
                    } catch (java.io.IOException ignored) { }
                }
            }
        }).orTimeout(45, TimeUnit.SECONDS);
        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for authenticated hotbar scenario " + currentScenario.get());
            result.join();
        }).thenSucceed();
    }

    private static JsonObject hotbar(JsonObject auth, JsonObject bootstrap, int seq) {
        var request = new JsonObject();
        request.addProperty("type", "world_hotbar");
        request.addProperty("protocol", 1);
        request.addProperty("seq", seq);
        request.add("sessionEpoch", auth.get("sessionEpoch"));
        request.add("subscriptionId", bootstrap.get("subscriptionId"));
        request.add("revision", bootstrap.get("revision"));
        request.add("dimension", bootstrap.get("dimension"));
        return request;
    }

    private static void onServer(MinecraftServer server, Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        server.execute(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(5, TimeUnit.SECONDS);
    }

    private static void send(WebSocket socket, String text) {
        socket.sendText(text, true).join();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Inbox implements WebSocket.Listener {
        final ArrayBlockingQueue<JsonObject> messages = new ArrayBlockingQueue<>(32);
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final StringBuilder text = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }

        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (text.length() > 8192) throw new AssertionError("Unbounded hotbar fixture response");
            if (last) {
                if (!messages.offer(JsonParser.parseString(text.toString()).getAsJsonObject())) {
                    throw new AssertionError("Hotbar fixture queue full");
                }
                text.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(null);
            return null;
        }

        @Override public void onError(WebSocket socket, Throwable error) {
            closed.completeExceptionally(error);
        }

        JsonObject take(String type) throws Exception {
            var value = messages.poll(8, TimeUnit.SECONDS);
            require(value != null && type.equals(value.get("type").getAsString()), "Unexpected hotbar fixture response: " + type);
            return value;
        }
    }
}
