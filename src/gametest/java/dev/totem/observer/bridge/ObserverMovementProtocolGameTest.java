package dev.totem.observer.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;

/** Real authenticated wire requests drive the admitted vanilla ServerPlayer. */
public final class ObserverMovementProtocolGameTest {
    @GameTest(maxTicks = 100_000)
    public void authenticatedMovementCorrectsAndExpiresInput(GameTestHelper helper) {
        run(helper, false);
    }

    @GameTest(maxTicks = 100_000)
    public void hostileMovementCannotCrossSessionOrRevisionBoundary(GameTestHelper helper) {
        run(helper, true);
    }

    @GameTest(maxTicks = 100_000)
    public void expiredIngressAndRevocationCannotStartMotion(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var sessions = new ObserverPlaySessionService();
        var admissions = new ObserverPlayerAdmissionService(server);
        String account = "delay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var authentication = new ObserverAccountService.Session(account, UUID.randomUUID());
        var session = sessions.open(authentication);
        var result = admissions.open(session).thenCompose(admission -> {
            require(admission != null, "Missing delayed-input admission");
            var player = admission.player();
            var position = player.position();
            var dimension = player.level().dimension().identifier().toString();
            var input = new ObserverMovementIntent(0, 1, 90, 0, true);
            return admissions.move(admission, dimension, input, System.nanoTime() - TimeUnit.SECONDS.toNanos(1), () -> true)
                    .thenCompose(expired -> {
                        require(expired == null && player.position().equals(position), "Delayed ingress revived expired input");
                        return admissions.move(admission, dimension, input, System.nanoTime(), () -> false);
                    }).thenAccept(revoked -> {
                        require(revoked == null && player.position().equals(position), "Revoked input changed player");
                    });
        });
        result.whenComplete((ignored, failure) -> { admissions.close(); sessions.close(); });
        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for expired movement validation");
            result.join();
        }).thenSucceed();
    }

    @GameTest(maxTicks = 100_000)
    public void admissionConstructionInsideTickCallbackIsSafe(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var completed = new CompletableFuture<Void>();
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        ServerTickEvents.END_SERVER_TICK.register(tickingServer -> {
            if (tickingServer != server || !ran.compareAndSet(false, true)) return;
            var sessions = new ObserverPlaySessionService();
            var admissions = new ObserverPlayerAdmissionService(server);
            String account = "tick_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            var authentication = new ObserverAccountService.Session(account, UUID.randomUUID());
            admissions.open(sessions.open(authentication)).whenComplete((admission, failure) -> {
                admissions.close();
                sessions.close();
                if (failure != null) completed.completeExceptionally(failure);
                else if (admission == null) completed.completeExceptionally(new AssertionError("Missing tick admission"));
                else completed.complete(null);
            });
        });
        helper.startSequence().thenWaitUntil(() -> {
            if (!completed.isDone()) helper.fail("Waiting for tick-callback admission lifecycle");
            completed.join();
        }).thenSucceed();
    }

    private static void run(GameTestHelper helper, boolean hostile) {
        var server = helper.getLevel().getServer();
        // Capture fixture location on the test/server thread; all world access below is marshalled.
        var origin = helper.absolutePos(new BlockPos(1, 1, 1));
        var result = CompletableFuture.runAsync(() -> {
            java.nio.file.Path directory = null;
            try {
                directory = Files.createTempDirectory("observer-movement-protocol-");
                var accounts = new ObserverAccountService(new ObserverAccountStore(directory.resolve("accounts.properties")), true);
                var sessions = new ObserverPlaySessionService();
                var admissions = new ObserverPlayerAdmissionService(server);
                try (var bridge = new ObserverBridgeServer(accounts, sessions, admissions);
                     var client = HttpClient.newHttpClient()) {
                    int port = bridge.start(0, "http://localhost:8080");
                    int cases = hostile ? 10 : 1;
                    for (int scenario = 0; scenario < cases; scenario++) {
                        var inbox = new Inbox();
                        var socket = client.newWebSocketBuilder().header("Origin", "http://localhost:8080")
                                .subprotocols(ObserverAuthenticatedExchange.PROTOCOL)
                                .buildAsync(URI.create("ws://127.0.0.1:" + port + ObserverBridgeServer.PATH), inbox)
                                .get(5, TimeUnit.SECONDS);
                        try {
                            var hello = inbox.take("hello");
                            require(hello.get("worldMovementProtocol").getAsInt() == 1 && !hello.get("play").getAsBoolean(),
                                    "Movement capability/play boundary changed");
                            String name = "mv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                            send(socket, "{\"type\":\"register\",\"username\":\"" + name
                                    + "\",\"password\":\"movement-fixture-password\"}");
                            var auth = inbox.take("authenticated");
                            var uuid = UUID.fromString(auth.get("playerUuid").getAsString());
                            var playerRef = new java.util.concurrent.atomic.AtomicReference<net.minecraft.server.level.ServerPlayer>();
                            onServer(server, () -> {
                                var player = server.getPlayerList().getPlayer(uuid);
                                require(player != null, "No admitted player");
                                playerRef.set(player);
                                var level = player.level();
                                for (int x = -1; x <= 1; x++) {
                                    for (int z = -1; z <= 1; z++) {
                                        for (int y = 0; y < 4; y++) {
                                            level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                                        }
                                        level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                                    }
                                }
                                player.setGameMode(GameType.SURVIVAL);
                                player.snapTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0, 0);
                                player.setDeltaMovement(Vec3.ZERO);
                                player.setOnGround(true);
                            });
                            send(socket, "{\"type\":\"world_bootstrap\",\"seq\":0}");
                            var bootstrap = inbox.take("world_bootstrap");
                            send(socket, "{\"type\":\"world_state\",\"seq\":1}");
                            var before = inbox.take("world_state");
                            var request = movement(auth, bootstrap, 2);
                            if (hostile) {
                                switch (scenario) {
                                    case 0 -> request.addProperty("sessionEpoch", 0);
                                    case 1 -> request.addProperty("revision", bootstrap.get("revision").getAsInt() + 1);
                                    case 2 -> request.addProperty("dimension", "minecraft:the_nether");
                                    case 3 -> request.addProperty("x", 30_000_000);
                                    case 4 -> request.addProperty("protocol", 2);
                                    case 5 -> request.addProperty("seq", 1);
                                    case 6 -> request.addProperty("forward", 2);
                                    case 7 -> request.addProperty("subscriptionId", bootstrap.get("subscriptionId").getAsInt() + 1);
                                    case 8 -> request.addProperty("dimension", "https://private.example");
                                    case 9 -> {
                                        send(socket, "{\"type\":\"world_bootstrap\",\"seq\":2}");
                                        request.addProperty("seq", 3);
                                    }
                                }
                                send(socket, request.toString());
                                inbox.closed.get(5, TimeUnit.SECONDS);
                                require(inbox.messages.stream().noneMatch(v -> "world_movement".equals(v.get("type").getAsString())),
                                        "Invalid movement was acknowledged");
                                onServer(server, () -> {
                                    var player = playerRef.get();
                                    require(player.getX() == before.get("x").getAsDouble()
                                            && player.getY() == before.get("y").getAsDouble()
                                            && player.getZ() == before.get("z").getAsDouble()
                                            && player.getYRot() == before.get("yaw").getAsFloat(),
                                            "Rejected movement mutated authoritative player before closing");
                                });
                            } else {
                                send(socket, request.toString());
                                var moved = inbox.take("world_movement");
                                require(moved.get("seq").getAsInt() == 2 && moved.get("sessionEpoch").equals(auth.get("sessionEpoch"))
                                        && moved.get("subscriptionId").equals(bootstrap.get("subscriptionId"))
                                        && moved.get("revision").equals(bootstrap.get("revision"))
                                        && moved.get("applied").getAsBoolean(), "Invalid movement acknowledgment");
                                require(moved.get("z").getAsDouble() != before.get("z").getAsDouble(), "Input did not move real player");
                                onServer(server, () -> require(server.getPlayerList().getPlayer(uuid).getZ() >= moved.get("z").getAsDouble(),
                                        "Response position not backed by ServerPlayer"));
                                // No new input: held direction expires after 250ms, idle physics settles.
                                Thread.sleep(650);
                                send(socket, "{\"type\":\"world_state\",\"seq\":3}");
                                var settled = inbox.take("world_state");
                                Thread.sleep(300);
                                send(socket, "{\"type\":\"world_state\",\"seq\":4}");
                                var later = inbox.take("world_state");
                                require(Math.abs(later.get("z").getAsDouble() - settled.get("z").getAsDouble()) < 0.03,
                                        "Expired input remained latched");
                                var jump = movement(auth, bootstrap, 5);
                                jump.addProperty("forward", 0);
                                jump.addProperty("jump", 1);
                                send(socket, jump.toString());
                                var jumped = inbox.take("world_movement");
                                require(jumped.get("serverTick").getAsInt() > moved.get("serverTick").getAsInt()
                                        && jumped.get("y").getAsDouble() > later.get("y").getAsDouble()
                                        && !jumped.get("onGround").getAsBoolean(), "Jump correction missing");
                                // Reusing a successful movement sequence must revoke, never replay.
                                send(socket, jump.toString());
                                inbox.closed.get(5, TimeUnit.SECONDS);
                            }
                        } finally {
                            socket.abort();
                        }
                    }
                }
            } catch (Exception failure) {
                throw new CompletionException(failure);
            } finally {
                if (directory != null) {
                    try {
                        Files.deleteIfExists(directory.resolve("accounts.properties"));
                        Files.deleteIfExists(directory);
                    } catch (java.io.IOException ignored) {}
                }
            }
        }).orTimeout(45, TimeUnit.SECONDS);
        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for authenticated movement exchange");
            result.join();
        }).thenSucceed();
    }

    private static JsonObject movement(JsonObject auth, JsonObject bootstrap, int seq) {
        var request = new JsonObject();
        request.addProperty("type", "world_movement");
        request.addProperty("protocol", 1);
        request.addProperty("seq", seq);
        request.add("sessionEpoch", auth.get("sessionEpoch"));
        request.add("subscriptionId", bootstrap.get("subscriptionId"));
        request.add("revision", bootstrap.get("revision"));
        request.add("dimension", bootstrap.get("dimension"));
        request.addProperty("strafe", 0);
        request.addProperty("forward", 1);
        request.addProperty("yaw", 0);
        request.addProperty("pitch", 0);
        request.addProperty("jump", 0);
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
            if (text.length() > 8192) throw new AssertionError("Unbounded movement fixture response");
            if (last) {
                if (!messages.offer(JsonParser.parseString(text.toString()).getAsJsonObject())) {
                    throw new AssertionError("Movement fixture queue full");
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
            require(value != null && type.equals(value.get("type").getAsString()), "Unexpected movement fixture response: " + type);
            return value;
        }
    }
}
