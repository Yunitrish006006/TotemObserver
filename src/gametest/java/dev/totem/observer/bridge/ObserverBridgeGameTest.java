package dev.totem.observer.bridge;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;

/** Real WebSocket exchange inside the dedicated Minecraft runtime; networking never blocks ticks. */
public final class ObserverBridgeGameTest {
    @GameTest(maxTicks = 100_000)
    public void dedicatedRuntimeSupportsBootstrapExchange(GameTestHelper helper) {
        exchange(helper, false);
    }

    @GameTest(maxTicks = 100_000)
    public void dedicatedRuntimeSupportsAccountExchange(GameTestHelper helper) {
        exchange(helper, true);
    }

    private void exchange(GameTestHelper helper, boolean authenticated) {
        var result = CompletableFuture.runAsync(() -> {
            java.nio.file.Path temporary = null;
            try {
                if (authenticated) temporary = java.nio.file.Files.createTempDirectory("observer-account-gametest-");
                var accounts = authenticated ? new ObserverAccountService(
                        new ObserverAccountStore(temporary.resolve("accounts.properties")), true) : null;
                try (var bridge = new ObserverBridgeServer(accounts); var client = HttpClient.newHttpClient()) {
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
                                socket.sendText("{\"type\":\"register\",\"username\":\"gametest\",\"password\":\"isolated-test-password\"}", true);
                            } else if (message.startsWith("{\"type\":\"authenticated\"") || message.equals("{\"type\":\"hello\",\"protocol\":1,\"capabilities\":[\"ping\"],\"play\":false}")) {
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
                try { pong.get(9, TimeUnit.SECONDS); } finally { socket.abort(); }
                }
            } catch (Exception e) { throw new CompletionException(e); }
            finally {
                if (temporary != null) {
                    try { java.nio.file.Files.deleteIfExists(temporary.resolve("accounts.properties")); java.nio.file.Files.deleteIfExists(temporary); }
                    catch (java.io.IOException ignored) { }
                }
            }
        }).orTimeout(20, TimeUnit.SECONDS); // GameTest ticks advance faster than network/PBKDF2 wall time.
        helper.startSequence().thenWaitUntil(() -> {
            if (!result.isDone()) helper.fail("Waiting for bridge exchange");
            result.join();
        }).thenSucceed();
    }
}
