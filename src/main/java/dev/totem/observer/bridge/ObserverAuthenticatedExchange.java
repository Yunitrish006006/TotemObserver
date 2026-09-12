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

/** Account-v1 connection state machine; browser gameplay remains disabled. */
final class ObserverAuthenticatedExchange extends SimpleChannelInboundHandler<WebSocketFrame> {
    static final String PROTOCOL = "totem-observer-account-v1";
    private final ObserverAccountService accounts;
    private final ObserverPlaySessionService playSessions;
    private final ObserverPlayerAdmissionService playerAdmissions;
    private final ObserverWorldBootstrapService worldBootstrap;
    private ObserverAccountService.Session session;
    private ObserverPlaySessionService.Session playSession;
    private ObserverPlayerAdmissionService.Admission playerAdmission;
    private boolean selected, pending, ended;
    private long sequence = -1;
    private io.netty.util.concurrent.ScheduledFuture<?> deadline;

    ObserverAuthenticatedExchange(ObserverAccountService accounts, ObserverPlaySessionService playSessions,
                                  ObserverPlayerAdmissionService playerAdmissions) {
        this(accounts, playSessions, playerAdmissions, null);
    }

    ObserverAuthenticatedExchange(ObserverAccountService accounts, ObserverPlaySessionService playSessions,
                                  ObserverPlayerAdmissionService playerAdmissions,
                                  ObserverWorldBootstrapService worldBootstrap) {
        this.accounts = accounts;
        this.playSessions = playSessions;
        this.playerAdmissions = playerAdmissions;
        this.worldBootstrap = worldBootstrap;
    }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake && PROTOCOL.equals(handshake.selectedSubprotocol())) {
            selected = true;
            ctx.channel().attr(ObserverBridgeServer.READY).set(true);
            String worldCapability = worldBootstrap == null ? "" : ",\"worldProtocol\":" + ObserverWorldBootstrapService.PROTOCOL;
            send(ctx, "{\"type\":\"hello\",\"protocol\":1,\"authentication\":true,\"registration\":" + accounts.registrationAllowed()
                    + ",\"playerIdentityProtocol\":" + ObserverPlaySessionService.PROTOCOL
                    + ",\"playerAdmission\":" + (playerAdmissions != null) + worldCapability + ",\"play\":false}");
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
            if (!"ping".equals(type) || !message.keySet().equals(Set.of("type", "seq"))) { stop(ctx, "Unsupported operation"); return; }
            long next = Long.parseLong(message.get("seq"));
            if (next <= sequence) { stop(ctx, "Invalid sequence"); return; }
            sequence = next;
            send(ctx, "{\"type\":\"pong\",\"seq\":" + next + "}");
        } catch (Exception ignored) { stop(ctx, "Invalid message"); }
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
        if (attached && worldBootstrap != null) {
            worldBootstrap.capture(playerAdmission).whenComplete((snapshot, failure) -> {
                try {
                    ctx.executor().execute(() -> finishWorldBootstrap(ctx, snapshot, failure));
                } catch (RejectedExecutionException ignored) { }
            });
        }
    }

    private void finishWorldBootstrap(ChannelHandlerContext ctx,
                                      ObserverWorldBootstrapService.Snapshot snapshot, Throwable failure) {
        if (ended || !ctx.channel().isActive()) return;
        if (failure != null || snapshot == null
                || !accounts.valid(session) || !playSessions.valid(session, playSession)
                || !playerAdmissions.valid(playSession, playerAdmission)
                || snapshot.sessionEpoch() != playSession.epoch()) {
            stop(ctx, "World bootstrap failed");
            return;
        }
        send(ctx, "{\"type\":\"world_bootstrap\",\"protocol\":" + ObserverWorldBootstrapService.PROTOCOL
                + ",\"sessionEpoch\":" + snapshot.sessionEpoch()
                + ",\"dimension\":\"" + snapshot.dimension()
                + "\",\"x\":" + snapshot.x() + ",\"y\":" + snapshot.y() + ",\"z\":" + snapshot.z()
                + ",\"yaw\":" + snapshot.yaw() + ",\"pitch\":" + snapshot.pitch()
                + ",\"gameTime\":" + snapshot.gameTime() + ",\"dayTime\":" + snapshot.dayTime()
                + ",\"play\":false}");
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
        if (playerAdmission != null) { playerAdmissions.release(playerAdmission); playerAdmission = null; }
        if (playSession != null) { playSessions.release(playSession); playSession = null; }
        if (session != null) { accounts.release(session); session = null; }
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) { ended = true; release(); ctx.fireChannelInactive(); }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ended = true; release(); ctx.close(); }

    private static Map<String, String> parse(String text) throws Exception {
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            var values = new HashMap<String, String>();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!Set.of("type", "username", "password", "seq").contains(key) || values.containsKey(key) || values.size() >= 3) throw new IllegalArgumentException();
                if (key.equals("seq")) {
                    if (reader.peek() != JsonToken.NUMBER) throw new IllegalArgumentException();
                    String value = reader.nextString();
                    if (!value.matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException();
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
