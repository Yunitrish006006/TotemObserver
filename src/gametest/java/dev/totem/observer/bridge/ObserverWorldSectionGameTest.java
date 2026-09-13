package dev.totem.observer.bridge;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Verifies one real loaded 16x16x16 section can cross the authenticated bridge as four bounded parts. */
public final class ObserverWorldSectionGameTest {
    @GameTest(maxTicks = 100_000)
    public void dedicatedRuntimeExposesBoundedWorldSectionSnapshot(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var result = CompletableFuture.runAsync(() -> {
            java.nio.file.Path temporary = null;
            try {
                temporary = Files.createTempDirectory("observer-section-gametest-");
                var accounts = new ObserverAccountService(
                        new ObserverAccountStore(temporary.resolve("accounts.properties")), true);
                var playSessions = new ObserverPlaySessionService();
                var admissions = new ObserverPlayerAdmissionService(server);
                try (var bridge = new ObserverBridgeServer(accounts, playSessions, admissions);
                     var client = HttpClient.newHttpClient()) {
                    int port = bridge.start(0, "http://localhost:8080");
                    var completed = new CompletableFuture<Void>();
                    var listener = new WebSocket.Listener() {
                        private final StringBuilder text = new StringBuilder();
                        private final boolean[] parts = new boolean[ObserverWorldSectionCodec.PARTS];
                        private long epoch;
                        private int subscriptionId;
                        private int revision;
                        private int chunkX;
                        private int chunkZ;
                        private int sectionY;
                        private int registryTotal;
                        private String fingerprint;

                        @Override public void onOpen(WebSocket socket) { socket.request(1); }

                        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                            text.append(data);
                            if (last) {
                                String message = text.toString();
                                text.setLength(0);
                                var json = JsonParser.parseString(message).getAsJsonObject();
                                String type = json.get("type").getAsString();
                                switch (type) {
                                    case "hello" -> {
                                        if (json.get("worldSectionProtocol").getAsInt() != 1
                                                || !json.get("playerAdmission").getAsBoolean()) {
                                            fail("World section capability missing");
                                        } else {
                                            socket.sendText("{\"type\":\"register\",\"username\":\"sectiontest\",\"password\":\"isolated-test-password\"}", true);
                                        }
                                    }
                                    case "authenticated" -> {
                                        epoch = json.get("sessionEpoch").getAsLong();
                                        socket.sendText("{\"type\":\"world_bootstrap\",\"seq\":0}", true);
                                    }
                                    case "world_bootstrap" -> {
                                        subscriptionId = json.get("subscriptionId").getAsInt();
                                        revision = json.get("revision").getAsInt();
                                        chunkX = json.get("centerChunkX").getAsInt();
                                        chunkZ = json.get("centerChunkZ").getAsInt();
                                        sectionY = Math.floorDiv(json.get("minY").getAsInt(), 16);
                                        socket.sendText("{\"type\":\"world_registry\",\"seq\":1,\"offset\":0}", true);
                                    }
                                    case "world_registry" -> {
                                        registryTotal = json.get("total").getAsInt();
                                        fingerprint = json.get("fingerprint").getAsString();
                                        socket.sendText("{\"type\":\"world_section\",\"seq\":2,\"subscriptionId\":"
                                                + subscriptionId + ",\"revision\":" + revision + ",\"chunkX\":" + chunkX
                                                + ",\"chunkZ\":" + chunkZ + ",\"sectionY\":" + sectionY + "}", true);
                                    }
                                    case "world_section" -> {
                                        int part = json.get("part").getAsInt();
                                        if (json.get("protocol").getAsInt() != ObserverWorldSectionCodec.PROTOCOL
                                                || json.get("seq").getAsLong() != 2
                                                || json.get("sessionEpoch").getAsLong() != epoch
                                                || json.get("subscriptionId").getAsInt() != subscriptionId
                                                || json.get("revision").getAsInt() != revision
                                                || !fingerprint.equals(json.get("registryFingerprint").getAsString())
                                                || json.get("chunkX").getAsInt() != chunkX
                                                || json.get("chunkZ").getAsInt() != chunkZ
                                                || json.get("sectionY").getAsInt() != sectionY
                                                || json.get("parts").getAsInt() != ObserverWorldSectionCodec.PARTS
                                                || json.get("stateCount").getAsInt() != ObserverWorldSectionCodec.STATES_PER_PART
                                                || part < 0 || part >= parts.length || parts[part]) {
                                            fail("Invalid world section metadata");
                                            break;
                                        }
                                        var decoded = ObserverWorldSectionCodec.decodePart(json.get("data").getAsString());
                                        if (decoded.size() != ObserverWorldSectionCodec.STATES_PER_PART
                                                || decoded.stream().anyMatch(id -> id < 0 || id >= registryTotal)) {
                                            fail("Invalid world section state ids");
                                            break;
                                        }
                                        parts[part] = true;
                                        boolean complete = true;
                                        for (boolean received : parts) complete &= received;
                                        if (complete) socket.sendText("{\"type\":\"ping\",\"seq\":3}", true);
                                    }
                                    case "pong" -> {
                                        if (json.get("seq").getAsLong() != 3) fail("Unexpected heartbeat sequence");
                                        else completed.complete(null);
                                    }
                                    default -> fail("Unexpected bridge response type: " + type);
                                }
                            }
                            socket.request(1);
                            return null;
                        }

                        private void fail(String message) {
                            completed.completeExceptionally(new IllegalStateException(message));
                        }

                        @Override public void onError(WebSocket socket, Throwable error) {
                            completed.completeExceptionally(error);
                        }
                    };
                    var socket = client.newWebSocketBuilder()
                            .header("Origin", "http://localhost:8080")
                            .subprotocols(ObserverAuthenticatedExchange.PROTOCOL)
                            .buildAsync(URI.create("ws://127.0.0.1:" + port + ObserverBridgeServer.PATH), listener)
                            .get(5, TimeUnit.SECONDS);
                    try {
                        completed.get(12, TimeUnit.SECONDS);
                    } finally {
                        if (!socket.isOutputClosed()) socket.abort();
                    }
                }
            } catch (Exception failure) {
                throw new CompletionException(failure);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary.resolve("accounts.properties"));
                        Files.deleteIfExists(temporary);
                    } catch (java.io.IOException ignored) { }
                }
            }
        }).orTimeout(25, TimeUnit.SECONDS);

        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for Observer world section exchange");
            result.join();
        }).thenSucceed();
    }
}
