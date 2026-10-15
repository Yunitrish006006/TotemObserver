package dev.totem.observer.bridge;

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

/** Verifies the registry mapping boundary in a real dedicated-server WebSocket exchange. */
public final class ObserverWorldRegistryGameTest {
    @GameTest(maxTicks = 100_000)
    public void dedicatedRuntimeExposesStableBlockStateRegistryPage(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var result = CompletableFuture.runAsync(() -> {
            java.nio.file.Path temporary = null;
            try {
                var localPage = ObserverBlockStateRegistry.page(0);
                if (localPage.offset() != 0 || localPage.total() <= 0 || localPage.states().isEmpty()
                        || localPage.states().size() > ObserverBlockStateRegistry.PAGE_SIZE
                        || !localPage.fingerprint().matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("Local block-state registry snapshot was invalid");
                }

                temporary = Files.createTempDirectory("observer-registry-gametest-");
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

                        @Override public void onOpen(WebSocket socket) { socket.request(1); }

                        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                            text.append(data);
                            if (last) {
                                String message = text.toString();
                                text.setLength(0);
                                if (message.startsWith("{\"type\":\"hello\"")) {
                                    if (!message.contains("\"playerAdmission\":true")
                                            || !message.contains("\"worldRegistryProtocol\":1")) {
                                        completed.completeExceptionally(new IllegalStateException("Registry capability missing"));
                                    } else {
                                        socket.sendText("{\"type\":\"register\",\"username\":\"registrytest\",\"password\":\"isolated-test-password\"}", true);
                                    }
                                } else if (message.startsWith("{\"type\":\"authenticated\"")) {
                                    socket.sendText("{\"type\":\"world_registry\",\"seq\":0,\"offset\":0}", true);
                                } else if (message.startsWith("{\"type\":\"world_registry\"")) {
                                    if (!message.contains("\"protocol\":1")
                                            || !message.contains("\"seq\":0")
                                            || !message.contains("\"sessionEpoch\":")
                                            || !message.contains("\"fingerprint\":\"" + localPage.fingerprint() + "\"")
                                            || !message.contains("\"offset\":0")
                                            || !message.contains("\"total\":" + localPage.total())
                                            || !message.contains("\"states\":[\"" + localPage.states().getFirst() + "\"")) {
                                        completed.completeExceptionally(new IllegalStateException("Registry page response was incomplete"));
                                    } else {
                                        socket.sendText("{\"type\":\"ping\",\"seq\":1}", true);
                                    }
                                } else if (message.equals("{\"type\":\"pong\",\"seq\":1}")) {
                                    completed.complete(null);
                                } else {
                                    completed.completeExceptionally(new IllegalStateException("Unexpected bridge response: " + message));
                                }
                            }
                            socket.request(1);
                            return null;
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
                        completed.get(9, TimeUnit.SECONDS);
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
        }).orTimeout(20, TimeUnit.SECONDS);

        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for Observer registry exchange");
            result.join();
        }).thenSucceed();
    }
}
