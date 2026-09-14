package dev.totem.observer.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;

/** Actual authenticated wire; server chooses target/progress and owns cancellation. */
public final class ObserverDestroyProtocolGameTest {
    @GameTest(maxTicks = 1_000_000)
    public void miningWireCompletesCancelsAndRejectsHostileBindings(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var pos = helper.absolutePos(new BlockPos(1, 2, 1));
        var scenarioNow = new java.util.concurrent.atomic.AtomicInteger(-1);
        var result = CompletableFuture.runAsync(() -> {
            java.nio.file.Path directory = null;
            try {
                directory = Files.createTempDirectory("observer-destroy-wire-");
                var store = new ObserverAccountStore(directory.resolve("accounts.properties"));
                var accounts = new ObserverAccountService(store, false);
                var sessions = new ObserverPlaySessionService();
                var admissions = new ObserverPlayerAdmissionService(server);
                try (var bridge = new ObserverBridgeServer(accounts, sessions, admissions);
                     var client = HttpClient.newHttpClient()) {
                    int port = bridge.start(0, "http://localhost:8080");
                    for (int scenario = 0; scenario < 12; scenario++) {
                        scenarioNow.set(scenario);
                        System.out.println("Observer mining wire scenario " + scenario);
                        String name = "dig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                        // Fixture setup precedes the login deadline; gameplay still uses real password authentication.
                        require(store.register(name, "mining-fixture-password".toCharArray()), "Fixture account creation failed");
                        var inbox = new Inbox();
                        var socket = client.newWebSocketBuilder().header("Origin", "http://localhost:8080")
                                .subprotocols(ObserverAuthenticatedExchange.PROTOCOL)
                                .buildAsync(URI.create("ws://127.0.0.1:" + port + ObserverBridgeServer.PATH), inbox)
                                .get(5, TimeUnit.SECONDS);
                        try {
                            var hello = inbox.take("hello");
                            require(hello.get("worldBlockDestroyProtocol").getAsInt() == 1 && !hello.get("play").getAsBoolean(),
                                    "Mining capability/play boundary");
                            send(socket, "{\"type\":\"login\",\"username\":\"" + name
                                    + "\",\"password\":\"mining-fixture-password\"}");
                            var auth = inbox.take("authenticated");
                            var uuid = UUID.fromString(auth.get("playerUuid").getAsString());
                            final boolean survivalCompletion = scenario == 0;
                            onServer(server, () -> {
                                var player = server.getPlayerList().getPlayer(uuid);
                                require(player != null, "Missing mining player");
                                player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                                player.setGameMode(GameType.SURVIVAL);
                                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                                helper.setBlock(1, 1, 0, Blocks.AIR);
                                helper.setBlock(1, 2, 0, Blocks.AIR);
                                helper.setBlock(1, 2, 1, survivalCompletion ? Blocks.DIRT : Blocks.OBSIDIAN);
                                player.snapTo(pos.getX() + 0.5, pos.getY() - 1.12, pos.getZ() - 0.5, 0, 0);
                                player.setOnGround(true);
                            });
                            send(socket, "{\"type\":\"world_bootstrap\",\"seq\":0}");
                            var bootstrap = inbox.take("world_bootstrap");
                            var request = mining(auth, bootstrap, 1, 1, "start");
                            if (scenario >= 3 && scenario <= 8) {
                                switch (scenario) {
                                    case 3 -> request.addProperty("sessionEpoch", auth.get("sessionEpoch").getAsLong() + 1);
                                    case 4 -> request.addProperty("revision", bootstrap.get("revision").getAsInt() + 1);
                                    case 5 -> request.addProperty("subscriptionId", bootstrap.get("subscriptionId").getAsInt() + 1);
                                    case 6 -> request.addProperty("dimension", "minecraft:the_nether");
                                    case 7 -> { request.addProperty("seq", 0); request.addProperty("operation", 0); }
                                    case 8 -> request.addProperty("x", pos.getX());
                                }
                                send(socket, request.toString());
                                inbox.closed.get(5, TimeUnit.SECONDS);
                                require(inbox.messages.isEmpty(), "Hostile mining acknowledged");
                            } else {
                                send(socket, request.toString());
                                var started = inbox.take("world_block_destroy");
                                validate(started, auth, bootstrap, 1, 1);
                                require("active".equals(started.get("outcome").getAsString())
                                        && !started.get("refreshRequired").getAsBoolean(), "Mining START not active");
                                if (scenario == 0) {
                                    JsonObject last = started;
                                    int seq = 2;
                                    while (!last.get("refreshRequired").getAsBoolean() && seq <= 22) {
                                        Thread.sleep(100);
                                        send(socket, mining(auth, bootstrap, seq, 1, "hold").toString());
                                        last = inbox.take("world_block_destroy");
                                        validate(last, auth, bootstrap, seq++, 1);
                                    }
                                    require("changed".equals(last.get("outcome").getAsString())
                                            && last.get("progress").getAsFloat() == 1
                                            && last.get("worldMayHaveChanged").getAsBoolean(), "Survival mining never completed");
                                    onServer(server, () -> require(helper.getLevel().getBlockState(pos).isAir(),
                                            "Mining acknowledgment did not remove real dirt"));
                                    send(socket, "{\"type\":\"world_bootstrap\",\"seq\":" + seq + "}");
                                    require(inbox.take("world_bootstrap").get("revision").getAsInt()
                                            == bootstrap.get("revision").getAsInt() + 1, "Mining refresh missing");
                                } else if (scenario == 10) {
                                    send(socket, "{\"type\":\"world_bootstrap\",\"seq\":2}");
                                    var refreshed = inbox.take("world_bootstrap");
                                    Thread.sleep(100);
                                    send(socket, mining(auth, refreshed, 3, 3, "start").toString());
                                    require("active".equals(inbox.take("world_block_destroy").get("outcome").getAsString()),
                                            "Active bootstrap did not release old owner");
                                    send(socket, mining(auth, bootstrap, 4, 1, "cancel").toString());
                                    inbox.closed.get(5, TimeUnit.SECONDS);
                                    require(inbox.messages.isEmpty(), "Old revision affected new operation");
                                } else if (scenario == 11) {
                                    Thread.sleep(650);
                                    send(socket, mining(auth, bootstrap, 2, 1, "hold").toString());
                                    var expired = inbox.take("world_block_destroy");
                                    require("cancelled".equals(expired.get("outcome").getAsString())
                                            && expired.get("refreshRequired").getAsBoolean()
                                            && expired.get("worldMayHaveChanged").getAsBoolean(),
                                            "Silent expiry lost terminal or revived mining");
                                } else if (scenario == 9) {
                                    Thread.sleep(100);
                                    send(socket, mining(auth, bootstrap, 2, 0, "hold").toString());
                                    inbox.closed.get(5, TimeUnit.SECONDS);
                                    require(inbox.messages.isEmpty(), "Wrong operation renewed mining");
                                } else {
                                    send(socket, mining(auth, bootstrap, 2, 1, "cancel").toString());
                                    var cancelled = inbox.take("world_block_destroy");
                                    validate(cancelled, auth, bootstrap, 2, 1);
                                    require("cancelled".equals(cancelled.get("outcome").getAsString())
                                            && cancelled.get("refreshRequired").getAsBoolean()
                                            && cancelled.get("worldMayHaveChanged").getAsBoolean(), "Cancellation lost dirty terminal");
                                    if (scenario == 1) {
                                        // Terminal requires bootstrap before any section is valid again.
                                        send(socket, "{\"type\":\"world_section\",\"seq\":3,\"subscriptionId\":"
                                                + bootstrap.get("subscriptionId") + ",\"revision\":" + bootstrap.get("revision")
                                                + ",\"chunkX\":" + (pos.getX() >> 4) + ",\"chunkZ\":" + (pos.getZ() >> 4)
                                                + ",\"sectionY\":" + (pos.getY() >> 4) + "}");
                                        inbox.closed.get(5, TimeUnit.SECONDS);
                                        require(inbox.messages.isEmpty(), "Stale post-mining section returned");
                                    } else {
                                        send(socket, "{\"type\":\"world_bootstrap\",\"seq\":3}");
                                        var refreshed = inbox.take("world_bootstrap");
                                        Thread.sleep(100);
                                        send(socket, mining(auth, refreshed, 4, 4, "start").toString());
                                        require("active".equals(inbox.take("world_block_destroy").get("outcome").getAsString()),
                                                "New revision could not start mining");
                                        send(socket, mining(auth, refreshed, 5, 1, "cancel").toString());
                                        inbox.closed.get(5, TimeUnit.SECONDS);
                                        require(inbox.messages.isEmpty(), "Old operation cancelled replacement");
                                    }
                                }
                            }
                            if (scenario != 0) onServer(server, () -> require(helper.getLevel().getBlockState(pos).is(Blocks.OBSIDIAN),
                                    "Denied/cancelled mining changed obsidian"));
                        } finally { socket.abort(); }
                    }
                }
            } catch (Throwable failure) { throw new CompletionException("Mining wire scenario " + scenarioNow.get(), failure); }
            finally {
                if (directory != null) try (var paths = Files.walk(directory)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
        }).orTimeout(75, TimeUnit.SECONDS);
        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for mining wire scenario " + scenarioNow.get());
            result.join();
        }).thenSucceed();
    }

    private static JsonObject mining(JsonObject auth, JsonObject bootstrap, int seq, int operation, String action) {
        var request = new JsonObject();
        request.addProperty("type", "world_block_destroy"); request.addProperty("protocol", 1);
        request.addProperty("seq", seq); request.add("sessionEpoch", auth.get("sessionEpoch"));
        request.add("subscriptionId", bootstrap.get("subscriptionId")); request.add("revision", bootstrap.get("revision"));
        request.add("dimension", bootstrap.get("dimension")); request.addProperty("operation", operation);
        request.addProperty("action", action); return request;
    }
    private static void validate(JsonObject reply, JsonObject auth, JsonObject bootstrap, int seq, int operation) {
        require(reply.size() == 13 && reply.get("protocol").getAsInt() == 1 && reply.get("seq").getAsInt() == seq
                && reply.get("operation").getAsInt() == operation && reply.get("sessionEpoch").equals(auth.get("sessionEpoch"))
                && reply.get("subscriptionId").equals(bootstrap.get("subscriptionId"))
                && reply.get("revision").equals(bootstrap.get("revision"))
                && reply.get("dimension").equals(bootstrap.get("dimension")), "Mining response binding");
    }
    private static void onServer(MinecraftServer server, Runnable task) throws Exception {
        var done = new CompletableFuture<Void>();
        server.execute(() -> { try { task.run(); done.complete(null); } catch (Throwable failure) { done.completeExceptionally(failure); } });
        done.get(5, TimeUnit.SECONDS);
    }
    private static void send(WebSocket socket, String text) { socket.sendText(text, true).join(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Inbox implements WebSocket.Listener {
        final ArrayBlockingQueue<JsonObject> messages = new ArrayBlockingQueue<>(32);
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final StringBuilder text = new StringBuilder();
        volatile String closeReason = "open";
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (text.length() > 8192) throw new AssertionError("Unbounded mining response");
            if (last) {
                if (!messages.offer(JsonParser.parseString(text.toString()).getAsJsonObject())) throw new AssertionError("Mining queue full");
                text.setLength(0);
            }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closeReason = status + ":" + reason.substring(0, Math.min(reason.length(), 128));
            closed.complete(null); return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        JsonObject take(String type) throws Exception {
            // The production login deadline is ten seconds; do not time out the fixture before it.
            var value = messages.poll(type.equals("authenticated") ? 12 : 8, TimeUnit.SECONDS);
            require(value != null && type.equals(value.get("type").getAsString()), "Expected " + type + ", received "
                    + (value == null ? "<timeout>; channel=" + closeReason : value.get("type")));
            return value;
        }
    }
}
