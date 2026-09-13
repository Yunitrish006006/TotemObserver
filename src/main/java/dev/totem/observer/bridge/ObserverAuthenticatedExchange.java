package dev.totem.observer.bridge;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Account-v1 connection state machine; bounded world snapshots are read-only and browser gameplay remains disabled. */
final class ObserverAuthenticatedExchange extends SimpleChannelInboundHandler<WebSocketFrame> {
    static final String PROTOCOL = "totem-observer-account-v1";
    static final int WORLD_STATE_PROTOCOL = 1;
    static final int WORLD_BOOTSTRAP_PROTOCOL = 1;
    static final int WORLD_BOOTSTRAP_RADIUS = 2;
    private final ObserverAccountService accounts;
    private final ObserverPlaySessionService playSessions;
    private final ObserverPlayerAdmissionService playerAdmissions;
    private ObserverAccountService.Session session;
    private ObserverPlaySessionService.Session playSession;
    private ObserverPlayerAdmissionService.Admission playerAdmission;
    private boolean selected, pending, ended, worldStatePending, worldBootstrapPending, worldSectionPending;
    private long sequence = -1;
    private boolean movementPending;
    private long lastMovementNanos;
    private boolean blockUsePending, worldRefreshRequired;
    private long lastBlockUseNanos;
    private io.netty.util.concurrent.ScheduledFuture<?> blockUseDeadline;
    private io.netty.util.concurrent.ScheduledFuture<?> movementDeadline;
    private int worldSubscriptionId, worldBootstrapRevision;
    private int worldBootstrapMinY, worldBootstrapHeight, worldBootstrapCenterChunkX, worldBootstrapCenterChunkZ;
    private String worldBootstrapDimension;
    private io.netty.util.concurrent.ScheduledFuture<?> deadline;

    ObserverAuthenticatedExchange(ObserverAccountService accounts, ObserverPlaySessionService playSessions,
                                  ObserverPlayerAdmissionService playerAdmissions) {
        this.accounts = accounts;
        this.playSessions = playSessions;
        this.playerAdmissions = playerAdmissions;
    }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake && PROTOCOL.equals(handshake.selectedSubprotocol())) {
            selected = true;
            ctx.channel().attr(ObserverBridgeServer.READY).set(true);
            send(ctx, "{\"type\":\"hello\",\"protocol\":1,\"authentication\":true,\"registration\":" + accounts.registrationAllowed()
                    + ",\"playerIdentityProtocol\":" + ObserverPlaySessionService.PROTOCOL
                    + ",\"playerAdmission\":" + (playerAdmissions != null)
                    + ",\"worldStateProtocol\":" + (playerAdmissions == null ? 0 : WORLD_STATE_PROTOCOL)
                    + ",\"worldBootstrapProtocol\":" + (playerAdmissions == null ? 0 : WORLD_BOOTSTRAP_PROTOCOL)
                    + ",\"worldRegistryProtocol\":" + (playerAdmissions == null ? 0 : ObserverBlockStateRegistry.PROTOCOL)
                    + ",\"worldSectionProtocol\":" + (playerAdmissions == null ? 0 : ObserverWorldSectionCodec.PROTOCOL)
                    + ",\"worldMovementProtocol\":" + (playerAdmissions == null ? 0 : ObserverMovementRequest.PROTOCOL)
                    + ",\"worldBlockUseProtocol\":" + (playerAdmissions == null ? 0 : ObserverBlockUseRequest.PROTOCOL)
                    + ",\"play\":false}");
            deadline = ctx.executor().schedule(() -> stop(ctx, "Login timed out"), 10, TimeUnit.SECONDS);
        } else if (selected && event instanceof IdleStateEvent) stop(ctx, "Connection idle");
        else ctx.fireUserEventTriggered(event);
    }

    @Override protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!selected) { ctx.fireChannelRead(frame.retain()); return; }
        if (ended) return;
        if (!(frame instanceof TextWebSocketFrame text) || !frame.isFinalFragment()) { stop(ctx, "Invalid message"); return; }
        try {
            Map<String, String> message = parse(text.text());
            String type = message.get("type");
            if (session == null) {
                if (pending || !("login".equals(type) || "register".equals(type))
                        || !message.keySet().equals(Set.of("type", "username", "password"))) { stop(ctx, "Login required"); return; }
                String name = message.get("username");
                char[] password = message.get("password").toCharArray();
                if (!ObserverAccountStore.validName(name) || !ObserverAccountStore.validPassword(password)) {
                    Arrays.fill(password, '\0'); failLogin(ctx); return;
                }
                pending = true;
                boolean submitted = accounts.authenticate(name, password, "register".equals(type), success -> {
                    try { ctx.executor().execute(() -> finishLogin(ctx, name, success)); }
                    catch (RejectedExecutionException ignored) { /* Channel/server already stopped. */ }
                });
                if (!submitted) failLogin(ctx);
                return;
            }
            if (!accounts.valid(session) || !playSessions.valid(session, playSession)
                    || (playerAdmissions != null && !playerAdmissions.valid(playSession, playerAdmission))) {
                stop(ctx, "Session revoked"); return;
            }
            if ("logout".equals(type) && message.size() == 1) {
                send(ctx, "{\"type\":\"logged_out\"}"); stop(ctx, "Logged out"); return;
            }
            if ("world_movement".equals(type)) {
                requestMovement(ctx, ObserverMovementRequest.from(message));
                return;
            }
            if ("world_block_use".equals(type)) {
                requestBlockUse(ctx, ObserverBlockUseRequest.from(message));
                return;
            }
            boolean ping = "ping".equals(type);
            boolean worldState = "world_state".equals(type) && playerAdmissions != null;
            boolean worldBootstrap = "world_bootstrap".equals(type) && playerAdmissions != null;
            boolean worldRegistry = "world_registry".equals(type) && playerAdmissions != null;
            boolean worldSection = "world_section".equals(type) && playerAdmissions != null;
            Set<String> expectedKeys = worldRegistry
                    ? Set.of("type", "seq", "offset")
                    : worldSection
                    ? Set.of("type", "seq", "subscriptionId", "revision", "chunkX", "chunkZ", "sectionY")
                    : Set.of("type", "seq");
            if (!(ping || worldState || worldBootstrap || worldRegistry || worldSection)
                    || !message.keySet().equals(expectedKeys)) {
                stop(ctx, "Unsupported operation"); return;
            }
            long next = Long.parseLong(message.get("seq"));
            if (next <= sequence) { stop(ctx, "Invalid sequence"); return; }
            sequence = next;
            if (ping) send(ctx, "{\"type\":\"pong\",\"seq\":" + next + "}");
            else if (worldState) requestWorldState(ctx, next);
            else if (worldBootstrap) requestWorldBootstrap(ctx, next);
            else if (worldRegistry) sendWorldRegistry(ctx, next, Integer.parseInt(message.get("offset")));
            else requestWorldSection(ctx, next,
                        Integer.parseInt(message.get("subscriptionId")), Integer.parseInt(message.get("revision")),
                        Integer.parseInt(message.get("chunkX")), Integer.parseInt(message.get("chunkZ")),
                        Integer.parseInt(message.get("sectionY")));
        } catch (Exception ignored) { stop(ctx, "Invalid message"); }
    }

    private void requestMovement(ChannelHandlerContext ctx, ObserverMovementRequest request) {
        long now = System.nanoTime();
        if (playerAdmissions == null || playerAdmission == null || movementPending || worldBootstrapPending
                || blockUsePending || worldRefreshRequired
                || request.seq() <= sequence || request.sessionEpoch() != playSession.epoch()
                || request.subscriptionId() != worldSubscriptionId || request.revision() != worldBootstrapRevision
                || !request.dimension().equals(worldBootstrapDimension)
                || (lastMovementNanos != 0 && now - lastMovementNanos < TimeUnit.MILLISECONDS.toNanos(80))) {
            stop(ctx, "Movement unavailable");
            return;
        }
        sequence = request.seq();
        lastMovementNanos = now;
        movementPending = true;
        var expectedAuthentication = session;
        var expectedSession = playSession;
        var expectedAdmission = playerAdmission;
        movementDeadline = ctx.executor().schedule(() -> stop(ctx, "Movement timed out"), 5, TimeUnit.SECONDS);
        playerAdmissions.move(expectedAdmission, request.dimension(), request.intent(), now,
                () -> accounts.valid(expectedAuthentication)
                        && playSessions.valid(expectedAuthentication, expectedSession))
                .whenComplete((result, failure) -> {
                    try {
                        ctx.executor().execute(() -> {
                            movementPending = false;
                            if (movementDeadline != null) movementDeadline.cancel(false);
                            if (ended || !ctx.channel().isActive()) return;
                            if (failure != null || result == null || session != expectedAuthentication
                                    || playSession != expectedSession || playerAdmission != expectedAdmission
                                    || !accounts.valid(expectedAuthentication)
                                    || !playSessions.valid(expectedAuthentication, expectedSession)
                                    || !playerAdmissions.valid(expectedSession, expectedAdmission)
                                    || request.subscriptionId() != worldSubscriptionId
                                    || request.revision() != worldBootstrapRevision
                                    || !request.dimension().equals(result.state().dimension())) {
                                stop(ctx, "Movement unavailable");
                                return;
                            }
                            var state = result.state();
                            send(ctx, "{\"type\":\"world_movement\",\"protocol\":1,\"seq\":" + request.seq()
                                    + ",\"sessionEpoch\":" + expectedSession.epoch()
                                    + ",\"subscriptionId\":" + request.subscriptionId()
                                    + ",\"revision\":" + request.revision()
                                    + ",\"serverTick\":" + result.serverTick() + ",\"applied\":" + result.applied()
                                    + ",\"onGround\":" + result.onGround()
                                    + ",\"dimension\":\"" + state.dimension() + "\",\"x\":" + state.x()
                                    + ",\"y\":" + state.y() + ",\"z\":" + state.z()
                                    + ",\"yaw\":" + state.yaw() + ",\"pitch\":" + state.pitch() + "}");
                        });
                    } catch (RejectedExecutionException ignored) { /* Release owns admission cleanup. */ }
                });
    }

    private void requestBlockUse(ChannelHandlerContext ctx, ObserverBlockUseRequest request) {
        long now = System.nanoTime();
        if (playerAdmissions == null || playerAdmission == null || blockUsePending || movementPending
                || worldBootstrapPending || worldSectionPending || worldRefreshRequired
                || request.seq() <= sequence || request.sessionEpoch() != playSession.epoch()
                || request.subscriptionId() != worldSubscriptionId || request.revision() != worldBootstrapRevision
                || !request.dimension().equals(worldBootstrapDimension)
                || (lastBlockUseNanos != 0 && now - lastBlockUseNanos < TimeUnit.MILLISECONDS.toNanos(200))) {
            stop(ctx, "Block use unavailable");
            return;
        }
        sequence = request.seq();
        lastBlockUseNanos = now;
        blockUsePending = true;
        var expectedAuthentication = session;
        var expectedSession = playSession;
        var expectedAdmission = playerAdmission;
        blockUseDeadline = ctx.executor().schedule(() -> stop(ctx, "Block use timed out"), 5, TimeUnit.SECONDS);
        playerAdmissions.useBlock(expectedAdmission, request.dimension(), now,
                () -> accounts.valid(expectedAuthentication)
                        && playSessions.valid(expectedAuthentication, expectedSession))
                .whenComplete((result, failure) -> {
                    try {
                        ctx.executor().execute(() -> {
                            blockUsePending = false;
                            if (blockUseDeadline != null) blockUseDeadline.cancel(false);
                            if (ended || !ctx.channel().isActive()) return;
                            if (failure != null || result == null || session != expectedAuthentication
                                    || playSession != expectedSession || playerAdmission != expectedAdmission
                                    || !accounts.valid(expectedAuthentication)
                                    || !playSessions.valid(expectedAuthentication, expectedSession)
                                    || !playerAdmissions.valid(expectedSession, expectedAdmission)
                                    || request.subscriptionId() != worldSubscriptionId
                                    || request.revision() != worldBootstrapRevision) {
                                stop(ctx, "Block use unavailable");
                                return;
                            }
                            worldRefreshRequired = result.outcome() == ObserverBlockUse.Outcome.APPLIED;
                            send(ctx, "{\"type\":\"world_block_use\",\"protocol\":1,\"seq\":" + request.seq()
                                    + ",\"sessionEpoch\":" + expectedSession.epoch()
                                    + ",\"subscriptionId\":" + request.subscriptionId()
                                    + ",\"revision\":" + request.revision()
                                    + ",\"dimension\":\"" + request.dimension() + "\",\"outcome\":\""
                                    + result.outcome().name().toLowerCase(Locale.ROOT)
                                    + "\",\"refreshRequired\":" + worldRefreshRequired + "}");
                        });
                    } catch (RejectedExecutionException ignored) { /* Release owns admission cleanup. */ }
                });
    }

    private void requestWorldState(ChannelHandlerContext ctx, long requestSequence) {
        if (worldStatePending || playerAdmission == null) { stop(ctx, "World state unavailable"); return; }
        worldStatePending = true;
        var expectedSession = playSession;
        var expectedAdmission = playerAdmission;
        playerAdmissions.snapshot(expectedAdmission).whenComplete((snapshot, failure) -> {
            try {
                ctx.executor().execute(() -> finishWorldState(ctx, requestSequence, expectedSession, expectedAdmission, snapshot, failure));
            } catch (RejectedExecutionException ignored) { }
        });
    }

    private void finishWorldState(ChannelHandlerContext ctx, long requestSequence,
                                  ObserverPlaySessionService.Session expectedSession,
                                  ObserverPlayerAdmissionService.Admission expectedAdmission,
                                  ObserverPlayerAdmissionService.WorldState snapshot, Throwable failure) {
        worldStatePending = false;
        if (ended || !ctx.channel().isActive()) return;
        if (failure != null || snapshot == null || playSession != expectedSession || playerAdmission != expectedAdmission
                || !accounts.valid(session) || !playSessions.valid(session, expectedSession)
                || !playerAdmissions.valid(expectedSession, expectedAdmission)) {
            stop(ctx, "World state unavailable");
            return;
        }
        send(ctx, "{\"type\":\"world_state\",\"protocol\":" + WORLD_STATE_PROTOCOL
                + ",\"seq\":" + requestSequence + ",\"sessionEpoch\":" + expectedSession.epoch()
                + ",\"dimension\":\"" + snapshot.dimension() + "\",\"x\":" + snapshot.x()
                + ",\"y\":" + snapshot.y() + ",\"z\":" + snapshot.z()
                + ",\"yaw\":" + snapshot.yaw() + ",\"pitch\":" + snapshot.pitch() + "}");
    }

    private void requestWorldBootstrap(ChannelHandlerContext ctx, long requestSequence) {
        if (worldBootstrapPending || movementPending || blockUsePending || playerAdmission == null) { stop(ctx, "World bootstrap unavailable"); return; }
        worldBootstrapPending = true;
        var expectedSession = playSession;
        var expectedAdmission = playerAdmission;
        playerAdmissions.bootstrap(expectedAdmission).whenComplete((snapshot, failure) -> {
            try {
                ctx.executor().execute(() -> finishWorldBootstrap(
                        ctx, requestSequence, expectedSession, expectedAdmission, snapshot, failure));
            } catch (RejectedExecutionException ignored) { }
        });
    }

    private void finishWorldBootstrap(ChannelHandlerContext ctx, long requestSequence,
                                      ObserverPlaySessionService.Session expectedSession,
                                      ObserverPlayerAdmissionService.Admission expectedAdmission,
                                      ObserverPlayerAdmissionService.WorldBootstrap snapshot, Throwable failure) {
        worldBootstrapPending = false;
        if (ended || !ctx.channel().isActive()) return;
        if (failure != null || snapshot == null || playSession != expectedSession || playerAdmission != expectedAdmission
                || !accounts.valid(session) || !playSessions.valid(session, expectedSession)
                || !playerAdmissions.valid(expectedSession, expectedAdmission)) {
            stop(ctx, "World bootstrap unavailable");
            return;
        }
        if (!snapshot.dimension().equals(worldBootstrapDimension)) {
            worldBootstrapDimension = snapshot.dimension();
            worldSubscriptionId++;
            worldBootstrapRevision = 1;
        } else {
            worldBootstrapRevision++;
        }
        worldBootstrapMinY = snapshot.minY();
        worldBootstrapHeight = snapshot.height();
        worldBootstrapCenterChunkX = snapshot.centerChunkX();
        worldBootstrapCenterChunkZ = snapshot.centerChunkZ();
        worldRefreshRequired = false;
        send(ctx, "{\"type\":\"world_bootstrap\",\"protocol\":" + WORLD_BOOTSTRAP_PROTOCOL
                + ",\"seq\":" + requestSequence + ",\"sessionEpoch\":" + expectedSession.epoch()
                + ",\"subscriptionId\":" + worldSubscriptionId + ",\"revision\":" + worldBootstrapRevision
                + ",\"dimension\":\"" + snapshot.dimension() + "\",\"minY\":" + snapshot.minY()
                + ",\"height\":" + snapshot.height() + ",\"centerChunkX\":" + snapshot.centerChunkX()
                + ",\"centerChunkZ\":" + snapshot.centerChunkZ() + ",\"radius\":" + WORLD_BOOTSTRAP_RADIUS + "}");
    }

    private void sendWorldRegistry(ChannelHandlerContext ctx, long requestSequence, int offset) {
        if (playerAdmission == null) { stop(ctx, "World registry unavailable"); return; }
        var page = ObserverBlockStateRegistry.page(offset);
        var states = new StringJoiner(",", "[", "]");
        for (String state : page.states()) states.add("\"" + state + "\"");
        send(ctx, "{\"type\":\"world_registry\",\"protocol\":" + ObserverBlockStateRegistry.PROTOCOL
                + ",\"seq\":" + requestSequence + ",\"sessionEpoch\":" + playSession.epoch()
                + ",\"fingerprint\":\"" + page.fingerprint() + "\",\"offset\":" + page.offset()
                + ",\"total\":" + page.total() + ",\"states\":" + states + "}");
    }

    private void requestWorldSection(ChannelHandlerContext ctx, long requestSequence, int subscriptionId, int revision,
                                     int chunkX, int chunkZ, int sectionY) {
        int minSectionY = Math.floorDiv(worldBootstrapMinY, 16);
        int maxSectionY = Math.floorDiv(worldBootstrapMinY + worldBootstrapHeight - 1, 16);
        if (worldSectionPending || blockUsePending || worldRefreshRequired || playerAdmission == null || worldBootstrapDimension == null
                || subscriptionId != worldSubscriptionId || revision != worldBootstrapRevision
                || Math.abs((long) chunkX - worldBootstrapCenterChunkX) > WORLD_BOOTSTRAP_RADIUS
                || Math.abs((long) chunkZ - worldBootstrapCenterChunkZ) > WORLD_BOOTSTRAP_RADIUS
                || sectionY < minSectionY || sectionY > maxSectionY) {
            stop(ctx, "World section unavailable");
            return;
        }
        worldSectionPending = true;
        var expectedSession = playSession;
        var expectedAdmission = playerAdmission;
        int expectedSubscriptionId = worldSubscriptionId;
        int expectedRevision = worldBootstrapRevision;
        String expectedDimension = worldBootstrapDimension;
        playerAdmissions.section(expectedAdmission, chunkX, chunkZ, sectionY).whenComplete((snapshot, failure) -> {
            try {
                ctx.executor().execute(() -> finishWorldSection(ctx, requestSequence, expectedSession, expectedAdmission,
                        expectedSubscriptionId, expectedRevision, expectedDimension, chunkX, chunkZ, sectionY,
                        snapshot, failure));
            } catch (RejectedExecutionException ignored) { }
        });
    }

    private void finishWorldSection(ChannelHandlerContext ctx, long requestSequence,
                                    ObserverPlaySessionService.Session expectedSession,
                                    ObserverPlayerAdmissionService.Admission expectedAdmission,
                                    int expectedSubscriptionId, int expectedRevision, String expectedDimension,
                                    int chunkX, int chunkZ, int sectionY,
                                    ObserverPlayerAdmissionService.WorldSection snapshot, Throwable failure) {
        worldSectionPending = false;
        if (ended || !ctx.channel().isActive()) return;
        if (failure != null || playSession != expectedSession || playerAdmission != expectedAdmission
                || worldSubscriptionId != expectedSubscriptionId || worldBootstrapRevision != expectedRevision
                || !Objects.equals(worldBootstrapDimension, expectedDimension)
                || !accounts.valid(session) || !playSessions.valid(session, expectedSession)
                || !playerAdmissions.valid(expectedSession, expectedAdmission)) {
            stop(ctx, "World section unavailable");
            return;
        }
        var registry = ObserverBlockStateRegistry.page(0);
        if (snapshot == null) {
            send(ctx, "{\"type\":\"world_section_unavailable\",\"protocol\":" + ObserverWorldSectionCodec.PROTOCOL
                    + ",\"seq\":" + requestSequence + ",\"sessionEpoch\":" + expectedSession.epoch()
                    + ",\"subscriptionId\":" + expectedSubscriptionId + ",\"revision\":" + expectedRevision
                    + ",\"registryFingerprint\":\"" + registry.fingerprint() + "\""
                    + ",\"dimension\":\"" + expectedDimension + "\",\"chunkX\":" + chunkX
                    + ",\"chunkZ\":" + chunkZ + ",\"sectionY\":" + sectionY
                    + ",\"reason\":\"not_loaded\"}");
            return;
        }
        if (!expectedDimension.equals(snapshot.dimension()) || snapshot.chunkX() != chunkX
                || snapshot.chunkZ() != chunkZ || snapshot.sectionY() != sectionY) {
            stop(ctx, "World section unavailable");
            return;
        }
        int[] stateIds = snapshot.stateIds();
        for (int part = 0; part < ObserverWorldSectionCodec.PARTS; part++) {
            String encoded = ObserverWorldSectionCodec.encodePart(stateIds, part);
            send(ctx, "{\"type\":\"world_section\",\"protocol\":" + ObserverWorldSectionCodec.PROTOCOL
                    + ",\"seq\":" + requestSequence + ",\"sessionEpoch\":" + expectedSession.epoch()
                    + ",\"subscriptionId\":" + expectedSubscriptionId + ",\"revision\":" + expectedRevision
                    + ",\"registryFingerprint\":\"" + registry.fingerprint() + "\""
                    + ",\"dimension\":\"" + expectedDimension + "\",\"chunkX\":" + chunkX
                    + ",\"chunkZ\":" + chunkZ + ",\"sectionY\":" + sectionY + ",\"part\":" + part
                    + ",\"parts\":" + ObserverWorldSectionCodec.PARTS
                    + ",\"stateCount\":" + ObserverWorldSectionCodec.STATES_PER_PART
                    + ",\"data\":\"" + encoded + "\"}");
        }
    }

    private void finishLogin(ChannelHandlerContext ctx, String name, boolean success) {
        if (ended || !ctx.channel().isActive()) return;
        pending = false;
        if (!success) { failLogin(ctx); return; }
        session = accounts.open(name, () -> {
            try { ctx.executor().execute(() -> stop(ctx, "Session revoked")); }
            catch (RejectedExecutionException ignored) { }
        });
        if (session == null) { stop(ctx, "Service stopped"); return; }
        playSession = playSessions.open(session);
        if (playSession == null) { stop(ctx, "Service stopped"); return; }

        if (playerAdmissions == null) {
            finishAuthenticated(ctx, name, false);
            return;
        }

        pending = true;
        playerAdmissions.open(playSession).whenComplete((admission, failure) -> {
            try {
                ctx.executor().execute(() -> finishAdmission(ctx, name, admission, failure));
            } catch (RejectedExecutionException ignored) {
                if (admission != null) playerAdmissions.release(admission);
            }
        });
    }

    private void finishAdmission(ChannelHandlerContext ctx, String name,
                                 ObserverPlayerAdmissionService.Admission admission, Throwable failure) {
        pending = false;
        if (ended || !ctx.channel().isActive()) {
            if (admission != null) playerAdmissions.release(admission);
            return;
        }
        if (failure != null || admission == null || !playerAdmissions.valid(playSession, admission)) {
            if (admission != null) playerAdmissions.release(admission);
            stop(ctx, "Player admission failed");
            return;
        }
        playerAdmission = admission;
        finishAuthenticated(ctx, name, true);
    }

    private void finishAuthenticated(ChannelHandlerContext ctx, String name, boolean attached) {
        if (deadline != null) deadline.cancel(false);
        deadline = ctx.executor().schedule(() -> stop(ctx, "Session expired"), 15, TimeUnit.MINUTES);
        var identity = playSession.identity();
        send(ctx, "{\"type\":\"authenticated\",\"username\":\"" + name
                + "\",\"expiresInSeconds\":900,\"playerUuid\":\"" + identity.uuid()
                + "\",\"playerName\":\"" + identity.profileName()
                + "\",\"sessionEpoch\":" + playSession.epoch()
                + ",\"playerAttached\":" + attached + ",\"play\":false}");
    }

    private void failLogin(ChannelHandlerContext ctx) {
        send(ctx, "{\"type\":\"auth_failed\"}"); stop(ctx, "Authentication failed");
    }
    private static void send(ChannelHandlerContext ctx, String value) { ctx.writeAndFlush(new TextWebSocketFrame(value)); }
    private void stop(ChannelHandlerContext ctx, String reason) {
        if (ended) return;
        ended = true;
        release();
        ctx.writeAndFlush(new CloseWebSocketFrame(1008, reason)).addListener(ChannelFutureListener.CLOSE);
    }
    private void release() {
        if (deadline != null) deadline.cancel(false);
        if (movementDeadline != null) movementDeadline.cancel(false);
        if (blockUseDeadline != null) blockUseDeadline.cancel(false);
        blockUsePending = false;
        movementPending = false;
        worldStatePending = false;
        worldBootstrapPending = false;
        worldSectionPending = false;
        if (playerAdmission != null) { playerAdmissions.release(playerAdmission); playerAdmission = null; }
        if (playSession != null) { playSessions.release(playSession); playSession = null; }
        if (session != null) { accounts.release(session); session = null; }
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) { ended = true; release(); ctx.fireChannelInactive(); }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ended = true; release(); ctx.close(); }

    static Map<String, String> parse(String text) throws Exception {
        if (text.length() > 1024) throw new IllegalArgumentException();
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            var values = new HashMap<String, String>();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!Set.of("type", "username", "password", "seq", "offset", "subscriptionId", "revision",
                        "chunkX", "chunkZ", "sectionY", "protocol", "sessionEpoch", "dimension",
                        "strafe", "forward", "yaw", "pitch", "jump").contains(key)
                        || values.containsKey(key) || values.size() >= ObserverMovementRequest.KEYS.size()) {
                    throw new IllegalArgumentException();
                }
                if (Set.of("seq", "offset", "subscriptionId", "revision", "chunkX", "chunkZ", "sectionY",
                        "protocol", "sessionEpoch", "strafe", "forward", "yaw", "pitch", "jump").contains(key)) {
                    if (reader.peek() != JsonToken.NUMBER) throw new IllegalArgumentException();
                    String value = reader.nextString();
                    boolean valid = switch (key) {
                        case "chunkX", "chunkZ" -> value.matches("0|-?[1-9][0-9]{0,6}");
                        case "sectionY" -> value.matches("0|-?[1-9][0-9]{0,3}");
                        case "sessionEpoch" -> value.matches("[1-9][0-9]{0,15}");
                        case "strafe", "forward", "yaw", "pitch", "jump" -> value.matches("0|-?[1-9][0-9]{0,4}");
                        default -> value.matches("0|[1-9][0-9]{0,8}");
                    };
                    if (!valid) throw new IllegalArgumentException();
                    values.put(key, value);
                } else {
                    if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException();
                    values.put(key, reader.nextString());
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
            return values;
        }
    }
}
