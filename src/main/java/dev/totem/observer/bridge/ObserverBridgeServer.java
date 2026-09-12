package dev.totem.observer.bridge;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Local transport with optional account sessions and dedicated-server player admission. */
public final class ObserverBridgeServer implements AutoCloseable {
    public static final String PATH = "/observer/bridge";
    public static final String PROTOCOL = "totem-observer-bootstrap-v1";
    public static final int MAX_CONNECTIONS = 16;
    public static final int MAX_FRAME_BYTES = 256;
    static final AttributeKey<Boolean> READY = AttributeKey.valueOf("observer.bootstrap.ready");
    private static final AttributeKey<Boolean> ACCOUNT = AttributeKey.valueOf("observer.account.protocol");
    private final ObserverAccountService accounts;
    private final ObserverPlaySessionService playSessions;
    private final ObserverPlayerAdmissionService playerAdmissions;
    private final EventLoopGroup acceptor = new NioEventLoopGroup(1, new DefaultThreadFactory("observer-bridge-accept", true));
    private final EventLoopGroup workers = new NioEventLoopGroup(1, new DefaultThreadFactory("observer-bridge-io", true));
    private final DefaultChannelGroup channels = new DefaultChannelGroup(workers.next(), true);
    private final AtomicInteger connections = new AtomicInteger();
    private Channel listener;
    private boolean closed;

    public ObserverBridgeServer() { this(null, null, null); }
    public ObserverBridgeServer(ObserverAccountService accounts) {
        this(accounts, accounts == null ? null : new ObserverPlaySessionService(), null);
    }
    ObserverBridgeServer(ObserverAccountService accounts, ObserverPlaySessionService playSessions) {
        this(accounts, playSessions, null);
    }
    ObserverBridgeServer(ObserverAccountService accounts, ObserverPlaySessionService playSessions,
                         ObserverPlayerAdmissionService playerAdmissions) {
        if ((accounts == null) != (playSessions == null)) throw new IllegalArgumentException("Account/play session services must match");
        if (playerAdmissions != null && accounts == null) throw new IllegalArgumentException("Player admission requires account sessions");
        this.accounts = accounts;
        this.playSessions = playSessions;
        this.playerAdmissions = playerAdmissions;
    }

    /** Binds IPv4 loopback only until authenticated remote play is implemented. Port 0 is for tests. */
    public synchronized int start(int port, String allowedOrigin) {
        if (closed || listener != null) throw new IllegalStateException("Bridge already started or closed");
        try {
            if (port < 0 || port > 65535 || allowedOrigin == null
                    || !allowedOrigin.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]{1,5}")) {
                throw new IllegalArgumentException("Invalid local bridge configuration");
            }
            var binding = new ServerBootstrap().group(acceptor, workers).channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(4096, 8192))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel ch) {
                            channels.add(ch);
                            ch.pipeline().addLast(new ConnectionLimit(), new IdleStateHandler(15, 0, 0),
                                    new HttpServerCodec(1024, 2048, 1024), new HttpObjectAggregator(1024),
                                    new Admission(allowedOrigin, accounts != null), new FrameBudget(),
                                    new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                                            .websocketPath(PATH).subprotocols(accounts == null ? PROTOCOL : PROTOCOL + "," + ObserverAuthenticatedExchange.PROTOCOL).checkStartsWith(false)
                                            .maxFramePayloadLength(accounts == null ? MAX_FRAME_BYTES : 1024).allowExtensions(false)
                                            .handshakeTimeoutMillis(5000).build()),
                                    new ObserverAuthenticatedExchange(accounts, playSessions, playerAdmissions), new Exchange());
                        }
                    }).bind(new InetSocketAddress("127.0.0.1", port)).awaitUninterruptibly();
            if (!binding.isSuccess()) throw new IllegalStateException("Bridge bind failed", binding.cause());
            listener = binding.channel();
            return ((InetSocketAddress) listener.localAddress()).getPort();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (listener != null) listener.close().syncUninterruptibly();
        channels.close().awaitUninterruptibly();
        if (playerAdmissions != null) playerAdmissions.close();
        if (playSessions != null) playSessions.close();
        if (accounts != null) accounts.close();
        var a = acceptor.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        var w = workers.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        a.awaitUninterruptibly();
        w.awaitUninterruptibly();
    }

    private final class ConnectionLimit extends ChannelInboundHandlerAdapter {
        private boolean counted;
        private ScheduledFuture<?> deadline;
        @Override public void channelActive(ChannelHandlerContext ctx) {
            counted = true;
            if (connections.incrementAndGet() > MAX_CONNECTIONS) { ctx.close(); return; }
            deadline = ctx.executor().schedule(() -> {
                if (!Boolean.TRUE.equals(ctx.channel().attr(READY).get())) ctx.close();
            }, 5, TimeUnit.SECONDS);
            ctx.fireChannelActive();
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) {
            if (deadline != null) deadline.cancel(false);
            if (counted) { counted = false; connections.decrementAndGet(); }
            ctx.fireChannelInactive();
        }
    }

    private static final class Admission extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final String origin;
        private final boolean accounts;
        Admission(String origin, boolean accounts) { this.origin = origin; this.accounts = accounts; }
        @Override protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String protocol = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
            boolean account = accounts && ObserverAuthenticatedExchange.PROTOCOL.equals(protocol);
            if (!request.decoderResult().isSuccess() || !request.method().equals(HttpMethod.GET)
                    || !request.uri().equals(PATH) || !origin.equals(request.headers().get(HttpHeaderNames.ORIGIN))
                    || !(PROTOCOL.equals(protocol) || account)) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            ctx.channel().attr(ACCOUNT).set(account);
            ctx.fireChannelRead(request.retain());
        }
    }

    /** Includes control frames, before Netty consumes ping/pong. No fragment aggregation. */
    static final class FrameBudget extends ChannelInboundHandlerAdapter {
        private long window = System.nanoTime();
        private int frames;
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof WebSocketFrame frame) {
                int maximum = Boolean.TRUE.equals(ctx.channel().attr(ACCOUNT).get()) ? 1024 : MAX_FRAME_BYTES;
                if (frame.content().readableBytes() > maximum) {
                    ReferenceCountUtil.release(message);
                    ctx.writeAndFlush(new CloseWebSocketFrame(1009, "Message too large")).addListener(ChannelFutureListener.CLOSE);
                    return;
                }
                long now = System.nanoTime();
                if (now - window >= TimeUnit.SECONDS.toNanos(1)) { window = now; frames = 0; }
                if (++frames > 32 || !ctx.channel().isWritable()) {
                    ReferenceCountUtil.release(message);
                    ctx.close();
                    return;
                }
            }
            ctx.fireChannelRead(message);
        }
    }

    private static final class Exchange extends SimpleChannelInboundHandler<WebSocketFrame> {
        private static final Pattern PING = Pattern.compile("\\{\"type\":\"ping\",\"seq\":(0|[1-9][0-9]{0,8})}");
        private long sequence = -1;
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                ctx.channel().attr(READY).set(true);
                ctx.writeAndFlush(new TextWebSocketFrame("{\"type\":\"hello\",\"protocol\":1,\"capabilities\":[\"ping\"],\"play\":false}"));
            } else if (event instanceof IdleStateEvent) {
                ctx.close();
            } else ctx.fireUserEventTriggered(event);
        }
        @Override protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (!(frame instanceof TextWebSocketFrame text) || !frame.isFinalFragment()) { reject(ctx); return; }
            var match = PING.matcher(text.text());
            if (!match.matches()) { reject(ctx); return; }
            long next = Long.parseLong(match.group(1));
            if (next <= sequence) { reject(ctx); return; }
            sequence = next;
            ctx.writeAndFlush(new TextWebSocketFrame("{\"type\":\"pong\",\"seq\":" + sequence + "}"));
        }
        private void reject(ChannelHandlerContext ctx) {
            ctx.writeAndFlush(new CloseWebSocketFrame(1008, "Unsupported bootstrap message"))
                    .addListener(ChannelFutureListener.CLOSE);
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
