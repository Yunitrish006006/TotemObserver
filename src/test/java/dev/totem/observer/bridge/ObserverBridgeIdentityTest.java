package dev.totem.observer.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ObserverBridgeIdentityTest {
    @TempDir Path directory;
    private static final String PASSWORD = "local-test-password-42";

    @Test void reconnectKeepsServerDerivedIdentityAndRotatesSessionEpoch() throws Exception {
        var store = new ObserverAccountStore(directory.resolve("accounts"));
        try (var bridge = new ObserverBridgeServer(new ObserverAccountService(store, true));
             var client = HttpClient.newHttpClient()) {
            int port = bridge.start(0, "http://localhost:8080");

            var first = new Listener();
            var firstSocket = connect(client, port, first);
            assertTrue(first.next().contains("\"playerIdentityProtocol\":1"));
            firstSocket.sendText(credentials("register"), true).get();
            String firstAuthenticated = first.next();
            assertIdentity(firstAuthenticated, "alice");
            firstSocket.sendText("{\"type\":\"logout\"}", true).get();
            assertEquals("{\"type\":\"logged_out\"}", first.next());
            first.closed.get(5, TimeUnit.SECONDS);

            var second = new Listener();
            var secondSocket = connect(client, port, second);
            second.next();
            secondSocket.sendText(credentials("login"), true).get();
            String secondAuthenticated = second.next();
            assertIdentity(secondAuthenticated, "alice");

            assertEquals(field(firstAuthenticated, "playerUuid"), field(secondAuthenticated, "playerUuid"));
            assertEquals(field(firstAuthenticated, "playerName"), field(secondAuthenticated, "playerName"));
            assertNotEquals(number(firstAuthenticated, "sessionEpoch"), number(secondAuthenticated, "sessionEpoch"));
            secondSocket.abort();
        }
    }

    private static void assertIdentity(String message, String account) {
        var expected = ObserverPlayerIdentity.forAccount(account);
        assertTrue(message.contains("\"type\":\"authenticated\""));
        assertEquals(expected.uuid().toString(), field(message, "playerUuid"));
        assertEquals(expected.profileName(), field(message, "playerName"));
        assertTrue(number(message, "sessionEpoch") > 0);
        assertTrue(message.endsWith("\"play\":false}"));
    }

    private static String field(String message, String key) {
        var match = Pattern.compile("\\\"" + key + "\\\":\\\"([^\\\"]+)\\\"").matcher(message);
        assertTrue(match.find(), () -> "Missing " + key);
        return match.group(1);
    }

    private static long number(String message, String key) {
        var match = Pattern.compile("\\\"" + key + "\\\":([0-9]+)").matcher(message);
        assertTrue(match.find(), () -> "Missing " + key);
        return Long.parseLong(match.group(1));
    }

    private static String credentials(String type) {
        return "{\"type\":\"" + type + "\",\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static WebSocket connect(HttpClient client, int port, Listener listener) throws Exception {
        return client.newWebSocketBuilder().header("Origin", "http://localhost:8080")
                .subprotocols(ObserverAuthenticatedExchange.PROTOCOL)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + ObserverBridgeServer.PATH), listener)
                .get(5, TimeUnit.SECONDS);
    }

    private static class Listener implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) { messages.add(buffer.toString()); buffer.setLength(0); }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            closed.complete(code);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable cause) { closed.completeExceptionally(cause); }
        String next() throws Exception {
            var message = messages.poll(9, TimeUnit.SECONDS);
            assertNotNull(message);
            return message;
        }
    }
}
