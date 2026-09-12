package dev.totem.observer.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ObserverAccountTest {
    @TempDir Path directory;
    private static final String PASSWORD = "local-test-password-42";

    @Test void hashesPersistWithoutPlaintextAndRejectWrongPasswords() throws Exception {
        var path = directory.resolve("accounts.properties");
        var store = new ObserverAccountStore(path);
        assertTrue(store.register("alice", PASSWORD.toCharArray()));
        assertFalse(store.register("alice", PASSWORD.toCharArray()));
        assertTrue(store.register("bob", PASSWORD.toCharArray()));
        assertFalse(Files.readString(path).contains(PASSWORD));
        var properties = new java.util.Properties();
        try (var reader = Files.newBufferedReader(path)) { properties.load(reader); }
        assertNotEquals(properties.getProperty("alice"), properties.getProperty("bob"));
        var loaded = new ObserverAccountStore(path);
        assertTrue(loaded.verify("alice", PASSWORD.toCharArray()));
        assertFalse(loaded.verify("alice", "incorrect-password-42".toCharArray()));
        assertFalse(loaded.verify("absent", PASSWORD.toCharArray()));
    }

    @Test void invalidStoreFailsClosedAndWriteFailureDoesNotCreateAccount() throws Exception {
        Path path = directory.resolve("bad.properties");
        Files.writeString(path, "version=1\nalice=broken\n");
        assertThrows(java.io.IOException.class, () -> new ObserverAccountStore(path));
        Path parent = directory.resolve("not-a-directory");
        Files.writeString(parent, "occupied");
        var store = new ObserverAccountStore(parent.resolve("accounts"));
        assertThrows(java.io.IOException.class, () -> store.register("alice", PASSWORD.toCharArray()));
        assertFalse(store.verify("alice", PASSWORD.toCharArray()));
    }

    @Test void registerLoginLogoutReconnectAndRevocation() throws Exception {
        var store = new ObserverAccountStore(directory.resolve("accounts"));
        try (var bridge = new ObserverBridgeServer(new ObserverAccountService(store, true)); var client = HttpClient.newHttpClient()) {
            int port = bridge.start(0, "http://localhost:8080");
            var first = new Listener(); var socket = connect(client, port, first);
            assertTrue(first.next().contains("\"registration\":true"));
            socket.sendText(credentials("register", PASSWORD), true).get();
            assertTrue(first.next().contains("\"type\":\"authenticated\""));
            socket.sendText("{\"type\":\"ping\",\"seq\":0}", true).get();
            assertEquals("{\"type\":\"pong\",\"seq\":0}", first.next());
            var second = new Listener(); var replacement = connect(client, port, second); second.next();
            replacement.sendText(credentials("login", PASSWORD), true).get();
            assertTrue(second.next().contains("\"type\":\"authenticated\""));
            assertEquals(1008, first.closed.get(5, TimeUnit.SECONDS));
            replacement.sendText("{\"type\":\"logout\"}", true).get();
            assertEquals("{\"type\":\"logged_out\"}", second.next());
            second.closed.get(5, TimeUnit.SECONDS);
            var third = new Listener(); var reconnect = connect(client, port, third); third.next();
            reconnect.sendText("{\"type\":\"ping\",\"seq\":0}", true).get();
            assertEquals(1008, third.closed.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void wrongPasswordRegistrationDisabledAndMalformedFieldsFailClosed() throws Exception {
        var store = new ObserverAccountStore(directory.resolve("accounts"));
        store.register("alice", PASSWORD.toCharArray());
        try (var bridge = new ObserverBridgeServer(new ObserverAccountService(store, false)); var client = HttpClient.newHttpClient()) {
            int port = bridge.start(0, "http://localhost:8080");
            for (String message : new String[]{credentials("login", "incorrect-password-42"), credentials("register", PASSWORD),
                    "{\"type\":\"login\",\"type\":\"ping\"}", "{\"type\":\"login\",\"username\":{},\"password\":\"x\"}"}) {
                var listener = new Listener(); var socket = connect(client, port, listener); listener.next();
                socket.sendText(message, true).get();
                if (message.equals(credentials("login", "incorrect-password-42")) || message.equals(credentials("register", PASSWORD))) {
                    assertEquals("{\"type\":\"auth_failed\"}", listener.next());
                }
                assertEquals(1008, listener.closed.get(5, TimeUnit.SECONDS));
                socket.abort();
            }
        }
    }

    @Test void sessionsCannotBeReusedAfterReleaseOrServiceStop() throws Exception {
        try (var service = new ObserverAccountService(new ObserverAccountStore(directory.resolve("accounts")), false)) {
            var revoked = new AtomicInteger();
            var old = service.open("alice", revoked::incrementAndGet);
            var current = service.open("alice", revoked::incrementAndGet);
            assertEquals(1, revoked.get()); assertFalse(service.valid(old)); assertTrue(service.valid(current));
            service.release(old); assertTrue(service.valid(current));
            service.release(current); assertFalse(service.valid(current));
            var last = service.open("bob", revoked::incrementAndGet);
            service.close(); assertFalse(service.valid(last)); assertNull(service.open("bob", () -> {}));
        }
    }

    @Test void rejectedAuthenticationClearsPasswordAndWorkIsGloballyBounded() throws Exception {
        try (var service = new ObserverAccountService(new ObserverAccountStore(directory.resolve("accounts")), false)) {
            char[] denied = PASSWORD.toCharArray();
            assertFalse(service.authenticate("alice", denied, true, ignored -> fail()));
            assertArrayEquals(new char[denied.length], denied);
            for (int i = 0; i < 11; i++) {
                var result = new CompletableFuture<Boolean>();
                assertTrue(service.authenticate("absent", PASSWORD.toCharArray(), false, result::complete));
                assertFalse(result.get(5, TimeUnit.SECONDS));
            }
            assertFalse(service.authenticate("absent", PASSWORD.toCharArray(), false, ignored -> fail()));
        }
    }

    @Test void shutdownFinishesPendingWritesAndClearsQueuedCredentials() throws Exception {
        Path path = directory.resolve("accounts");
        var service = new ObserverAccountService(new ObserverAccountStore(path), true);
        var passwords = new java.util.ArrayList<char[]>();
        var outcomes = new java.util.ArrayList<CompletableFuture<Boolean>>();
        for (int i = 0; i < 4; i++) {
            var password = PASSWORD.toCharArray(); passwords.add(password);
            var outcome = new CompletableFuture<Boolean>(); outcomes.add(outcome);
            assertTrue(service.authenticate("user_" + i, password, true, outcome::complete));
        }
        service.close();
        var reloaded = new ObserverAccountStore(path);
        for (int i = 0; i < 4; i++) {
            assertTrue(outcomes.get(i).isDone());
            assertArrayEquals(new char[PASSWORD.length()], passwords.get(i));
            if (outcomes.get(i).join()) assertTrue(reloaded.verify("user_" + i, PASSWORD.toCharArray()));
        }
    }

    private static String credentials(String type, String password) {
        return "{\"type\":\"" + type + "\",\"username\":\"alice\",\"password\":\"" + password + "\"}";
    }
    private static WebSocket connect(HttpClient client, int port, Listener listener) throws Exception {
        return client.newWebSocketBuilder().header("Origin", "http://localhost:8080")
                .subprotocols(ObserverAuthenticatedExchange.PROTOCOL)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + ObserverBridgeServer.PATH), listener).get(5, TimeUnit.SECONDS);
    }
    private static class Listener implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) { messages.add(buffer.toString()); buffer.setLength(0); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket socket, Throwable cause) { closed.completeExceptionally(cause); }
        String next() throws Exception { var message = messages.poll(9, TimeUnit.SECONDS); assertNotNull(message); return message; }
    }
}
