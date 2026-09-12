package dev.totem.observer.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ObserverBridgeServerTest {
    private static final String ORIGIN = "http://localhost:8080";
    private final ObserverBridgeServer server = new ObserverBridgeServer();
    private final HttpClient client = HttpClient.newHttpClient();
    private int port;

    @AfterEach void cleanup() { server.close(); client.close(); }

    @Test void exchangesMessagesAndReconnectsWithNewSequence() throws Exception {
        port = server.start(0, ORIGIN);
        for (int i = 0; i < 2; i++) {
            var listener = new Listener();
            var socket = connect(listener, ORIGIN, ObserverBridgeServer.PROTOCOL);
            assertEquals(ObserverBridgeServer.PROTOCOL, socket.getSubprotocol());
            assertEquals("{\"type\":\"hello\",\"protocol\":1,\"capabilities\":[\"ping\"],\"play\":false}", listener.next());
            socket.sendText("{\"type\":\"ping\",\"seq\":1}", true).get(3, TimeUnit.SECONDS);
            assertEquals("{\"type\":\"pong\",\"seq\":1}", listener.next());
            socket.sendClose(1000, "done").get(3, TimeUnit.SECONDS);
            assertEquals(1000, listener.closed.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void rejectsWrongOriginAndProtocolBeforeUpgrade() {
        port = server.start(0, ORIGIN);
        for (String[] options : new String[][]{{"http://untrusted.invalid", ObserverBridgeServer.PROTOCOL}, {ORIGIN, "unknown-v2"}}) {
            var failure = assertThrows(ExecutionException.class, () -> connect(new Listener(), options[0], options[1]));
            var handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
            assertEquals(403, handshake.getResponse().statusCode());
        }
    }

    @Test void rejectsReplayAndDoesNotDispatchGameplay() throws Exception {
        port = server.start(0, ORIGIN);
        var listener = new Listener();
        var socket = connect(listener, ORIGIN, ObserverBridgeServer.PROTOCOL);
        listener.next();
        socket.sendText("{\"type\":\"ping\",\"seq\":9}", true).get(3, TimeUnit.SECONDS);
        listener.next();
        socket.sendText("{\"type\":\"ping\",\"seq\":9}", true).get(3, TimeUnit.SECONDS);
        assertEquals(1008, listener.closed.get(3, TimeUnit.SECONDS));
        var other = new Listener();
        var second = connect(other, ORIGIN, ObserverBridgeServer.PROTOCOL);
        other.next();
        second.sendText("{\"type\":\"move\",\"seq\":1}", true).get(3, TimeUnit.SECONDS);
        assertEquals(1008, other.closed.get(3, TimeUnit.SECONDS));
    }

    @Test void rejectsOversizeBinaryAndFragmentedMessages() throws Exception {
        port = server.start(0, ORIGIN);
        for (int mode = 0; mode < 3; mode++) {
            var listener = new Listener();
            var socket = connect(listener, ORIGIN, ObserverBridgeServer.PROTOCOL);
            listener.next();
            if (mode == 0) socket.sendText("x".repeat(257), true).get(3, TimeUnit.SECONDS);
            if (mode == 1) socket.sendBinary(ByteBuffer.wrap(new byte[]{1}), true).get(3, TimeUnit.SECONDS);
            if (mode == 2) socket.sendText("{", false).get(3, TimeUnit.SECONDS);
            assertEquals(mode == 0 ? 1009 : 1008, listener.closed.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void closingReleasesListenerAndActiveConnections() throws Exception {
        port = server.start(0, ORIGIN);
        var listener = new Listener();
        connect(listener, ORIGIN, ObserverBridgeServer.PROTOCOL);
        listener.next();
        server.close();
        listener.closed.get(3, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> connect(new Listener(), ORIGIN, ObserverBridgeServer.PROTOCOL));
        try (var replacement = new ObserverBridgeServer()) {
            assertEquals(port, replacement.start(port, ORIGIN));
        }
    }

    @Test void failedBindReleasesResourcesAndCannotRestart() {
        port = server.start(0, ORIGIN);
        try (var second = new ObserverBridgeServer()) {
            assertThrows(RuntimeException.class, () -> second.start(port, ORIGIN));
            assertThrows(IllegalStateException.class, () -> second.start(0, ORIGIN));
        }
    }

    @Test void rejectsLeadingZeroSequence() throws Exception {
        port = server.start(0, ORIGIN);
        var listener = new Listener();
        var socket = connect(listener, ORIGIN, ObserverBridgeServer.PROTOCOL);
        listener.next();
        socket.sendText("{\"type\":\"ping\",\"seq\":01}", true).get(3, TimeUnit.SECONDS);
        assertEquals(1008, listener.closed.get(3, TimeUnit.SECONDS));
    }

    @Test void capsAcceptedConnectionsIncludingIncompleteHandshakes() throws Exception {
        port = server.start(0, ORIGIN);
        var sockets = new java.util.ArrayList<WebSocket>();
        try {
            for (int i = 0; i < ObserverBridgeServer.MAX_CONNECTIONS; i++) {
                var listener = new Listener();
                sockets.add(connect(listener, ORIGIN, ObserverBridgeServer.PROTOCOL));
                listener.next();
            }
            try (var extra = new Socket("127.0.0.1", port)) {
                extra.setSoTimeout(2000);
                assertEquals(-1, extra.getInputStream().read());
            }
        } finally { sockets.forEach(WebSocket::abort); }
    }

    @Test void incompleteHttpHandshakeExpires() throws Exception {
        port = server.start(0, ORIGIN);
        try (var incomplete = new Socket("127.0.0.1", port)) {
            incomplete.setSoTimeout(7000);
            incomplete.getOutputStream().write("GET /observer/bridge HTTP/1.1\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(-1, incomplete.getInputStream().read());
        }
    }

    @Test @Timeout(25) void upgradedConnectionSurvivesHandshakeDeadlineThenExpiresWhenIdle() throws Exception {
        port = server.start(0, ORIGIN);
        var listener = new Listener();
        connect(listener, ORIGIN, ObserverBridgeServer.PROTOCOL);
        listener.next();
        assertThrows(TimeoutException.class, () -> listener.closed.get(6, TimeUnit.SECONDS));
        listener.closed.get(12, TimeUnit.SECONDS);
    }

    @Test void controlFramesShareTheRateBudget() {
        var channel = new io.netty.channel.embedded.EmbeddedChannel(new ObserverBridgeServer.FrameBudget());
        try {
            for (int i = 0; i < 32; i++) {
                assertTrue(channel.writeInbound(new io.netty.handler.codec.http.websocketx.PingWebSocketFrame()));
                io.netty.util.ReferenceCountUtil.release(channel.readInbound());
            }
            var overflow = new io.netty.handler.codec.http.websocketx.PingWebSocketFrame();
            assertFalse(channel.writeInbound(overflow));
            assertFalse(channel.isActive());
            assertEquals(0, overflow.refCnt());
        } finally { channel.finishAndReleaseAll(); }
    }

    private WebSocket connect(Listener listener, String origin, String protocol) throws Exception {
        return client.newWebSocketBuilder().header("Origin", origin).subprotocols(protocol)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + ObserverBridgeServer.PATH), listener)
                .get(3, TimeUnit.SECONDS);
    }

    private static final class Listener implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { messages.add(partial.toString()); partial.setLength(0); }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        String next() throws InterruptedException {
            String value = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(value, "Missing server message");
            return value;
        }
    }
}
