package dev.totem.observer.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * E2E-only account-v1 client that proves a real admitted Observer ServerPlayer is broadcast to a
 * separate normal Java client. This class is in the bridge package only to access the test fixture
 * constructor; production lifecycle and admission policy are unchanged.
 */
public final class ObserverBridgeAdmissionE2eCoordinator implements ModInitializer {
    private static final String ORIGIN = "http://localhost:8080";
    private static final String ACCOUNT = "e2e_bridge";
    private static final String PASSWORD = "e2e-bridge-test-password";
    private static final int ADMISSION_TIMEOUT_TICKS = 20 * 60;
    private static final int RELEASE_TIMEOUT_TICKS = 20 * 30;

    private static ObserverBridgeServer bridge;
    private static WebSocket socket;
    private static ScheduledExecutorService heartbeat;
    private static CompletableFuture<ObserverPlayerIdentity> authenticated;
    private static Path accountsFile;
    private static int ticks;
    private static int startedTick = -1;
    private static int releaseTick = -1;
    private static boolean admittedMarked;
    private static boolean releaseRequested;
    private static boolean releasedMarked;
    private static boolean complete;

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("totem.observer.e2e.enabled")) return;
        ServerTickEvents.END_SERVER_TICK.register(ObserverBridgeAdmissionE2eCoordinator::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());
    }

    private static void tick(MinecraftServer server) {
        if (complete) return;
        ticks++;
        try {
            if (authenticated == null) {
                if (!markerExists("target-world-ready.txt")) return;
                start(server);
                return;
            }

            if (!authenticated.isDone()) {
                if (ticks > startedTick + ADMISSION_TIMEOUT_TICKS) {
                    throw new IllegalStateException("Timed out waiting for account-v1 player admission");
                }
                return;
            }

            ObserverPlayerIdentity identity = authenticated.join();
            ServerPlayer admitted = server.getPlayerList().getPlayer(identity.uuid());
            if (admitted == null) {
                if (ticks > startedTick + ADMISSION_TIMEOUT_TICKS) {
                    throw new IllegalStateException("Authenticated Observer player never entered PlayerList");
                }
                return;
            }
            if (!identity.profileName().equals(admitted.getGameProfile().name())) {
                throw new AssertionError("Admitted Observer profile name mismatch");
            }

            if (!admittedMarked) {
                writeIdentityMarker("server-bridge-player-admitted.txt", identity,
                        "Server admitted account-v1 player through PlayerList.\n");
                admittedMarked = true;
                return;
            }

            if (!markerExists("target-bridge-player-visible.txt")) {
                if (ticks > startedTick + ADMISSION_TIMEOUT_TICKS) {
                    throw new IllegalStateException("Target Java client never observed admitted Observer player");
                }
                return;
            }

            if (!releaseRequested) {
                marker("server-bridge-player-release-requested.txt",
                        "Closing account-v1 socket after Target proved player visibility.\n");
                releaseRequested = true;
                releaseTick = ticks;
                stopHeartbeat();
                WebSocket current = socket;
                if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "e2e complete");
                return;
            }

            if (server.getPlayerList().getPlayer(identity.uuid()) != null) {
                if (ticks > releaseTick + RELEASE_TIMEOUT_TICKS) {
                    throw new IllegalStateException("Released account-v1 player remained in PlayerList");
                }
                return;
            }

            if (!releasedMarked) {
                marker("server-bridge-player-released.txt",
                        "Server removed account-v1 player after WebSocket release.\n");
                releasedMarked = true;
                return;
            }

            if (!markerExists("target-bridge-player-removed.txt")) {
                if (ticks > releaseTick + RELEASE_TIMEOUT_TICKS) {
                    throw new IllegalStateException("Target Java client retained released Observer player");
                }
                return;
            }

            marker("server-bridge-player-visibility-complete.txt",
                    "Normal Java client observed account-v1 player add and removal lifecycle.\n");
            complete = true;
            shutdown();
        } catch (CompletionException failure) {
            fail(failure.getCause() == null ? failure : failure.getCause());
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void start(MinecraftServer server) throws IOException {
        Path results = resultsDir();
        Files.createDirectories(results);
        accountsFile = results.resolve("bridge-accounts.properties");
        Files.deleteIfExists(accountsFile);

        var accounts = new ObserverAccountService(new ObserverAccountStore(accountsFile), true);
        var playSessions = new ObserverPlaySessionService();
        var admissions = new ObserverPlayerAdmissionService(server);
        bridge = new ObserverBridgeServer(accounts, playSessions, admissions);
        int port = bridge.start(0, ORIGIN);
        startedTick = ticks;
        authenticated = new CompletableFuture<>();
        HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", ORIGIN)
                .subprotocols(ObserverAuthenticatedExchange.PROTOCOL)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + ObserverBridgeServer.PATH), new AccountListener())
                .whenComplete((connected, failure) -> {
                    if (failure != null) authenticated.completeExceptionally(failure);
                    else socket = connected;
                });
        marker("server-bridge-login-started.txt",
                "Started isolated account-v1 admission fixture on loopback.\n");
    }

    private static final class AccountListener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                handleMessage(webSocket, message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (authenticated != null && !authenticated.isDone()) {
                authenticated.completeExceptionally(new IllegalStateException(
                        "Account-v1 socket closed before authentication: " + statusCode));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (authenticated != null) authenticated.completeExceptionally(error);
        }
    }

    private static void handleMessage(WebSocket webSocket, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.get("type").getAsString();
            if ("hello".equals(type)) {
                if (!json.get("authentication").getAsBoolean()
                        || !json.get("playerAdmission").getAsBoolean()
                        || json.get("play").getAsBoolean()) {
                    throw new IllegalStateException("Account-v1 hello did not advertise admission-only contract");
                }
                webSocket.sendText("{\"type\":\"register\",\"username\":\"" + ACCOUNT
                        + "\",\"password\":\"" + PASSWORD + "\"}", true);
                return;
            }
            if ("authenticated".equals(type)) {
                ObserverPlayerIdentity expected = ObserverPlayerIdentity.forAccount(ACCOUNT);
                UUID actualId = UUID.fromString(json.get("playerUuid").getAsString());
                String actualName = json.get("playerName").getAsString();
                if (!ACCOUNT.equals(json.get("username").getAsString())
                        || !json.get("playerAttached").getAsBoolean()
                        || json.get("play").getAsBoolean()
                        || !expected.uuid().equals(actualId)
                        || !expected.profileName().equals(actualName)) {
                    throw new IllegalStateException("Authenticated account-v1 identity/admission contract mismatch");
                }
                authenticated.complete(expected);
                startHeartbeat();
                return;
            }
            if ("pong".equals(type)) return;
            if ("auth_failed".equals(type)) {
                throw new IllegalStateException("E2E account registration was rejected");
            }
            throw new IllegalStateException("Unexpected account-v1 message type: " + type);
        } catch (Throwable failure) {
            authenticated.completeExceptionally(failure);
        }
    }

    private static void startHeartbeat() {
        if (heartbeat != null) return;
        AtomicLong sequence = new AtomicLong();
        heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "observer-e2e-account-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            WebSocket current = socket;
            if (current != null) {
                long next = sequence.getAndIncrement();
                current.sendText("{\"type\":\"ping\",\"seq\":" + next + "}", true);
            }
        }, 4, 4, TimeUnit.SECONDS);
    }

    private static void stopHeartbeat() {
        ScheduledExecutorService current = heartbeat;
        heartbeat = null;
        if (current != null) current.shutdownNow();
    }

    private static void fail(Throwable failure) {
        if (complete) return;
        complete = true;
        marker("failure-server-bridge-admission.txt", failure.toString() + "\n");
        shutdown();
    }

    private static void shutdown() {
        stopHeartbeat();
        WebSocket currentSocket = socket;
        socket = null;
        if (currentSocket != null && !currentSocket.isOutputClosed()) currentSocket.abort();
        ObserverBridgeServer currentBridge = bridge;
        bridge = null;
        if (currentBridge != null) currentBridge.close();
        Path file = accountsFile;
        accountsFile = null;
        if (file != null) {
            try { Files.deleteIfExists(file); }
            catch (IOException ignored) { }
        }
    }

    private static Path resultsDir() {
        return Path.of(System.getProperty("totem.observer.e2e.results", "build/e2e/results")).toAbsolutePath();
    }

    private static boolean markerExists(String name) {
        return Files.isRegularFile(resultsDir().resolve(name));
    }

    private static void writeIdentityMarker(String name, ObserverPlayerIdentity identity, String note) {
        Properties properties = new Properties();
        properties.setProperty("uuid", identity.uuid().toString());
        properties.setProperty("name", identity.profileName());
        properties.setProperty("account", identity.account());
        StringBuilder content = new StringBuilder();
        content.append("uuid=").append(properties.getProperty("uuid")).append('\n');
        content.append("name=").append(properties.getProperty("name")).append('\n');
        content.append("account=").append(properties.getProperty("account")).append('\n');
        content.append("note=").append(note.strip()).append('\n');
        marker(name, content.toString());
    }

    private static void marker(String name, String content) {
        try {
            Path results = resultsDir();
            Files.createDirectories(results);
            Files.writeString(results.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new RuntimeException("Failed to write E2E bridge marker " + name, error);
        }
    }
}
