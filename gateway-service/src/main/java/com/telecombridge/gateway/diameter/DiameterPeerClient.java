package com.telecombridge.gateway.diameter;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.AvpCode;
import com.telecombridge.diameter.BaseMessages;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.CreditControlRequest;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.IdentifierGenerator;
import com.telecombridge.diameter.LocalPeer;
import com.telecombridge.diameter.ResultCode;
import com.telecombridge.diameter.netty.DiameterFrameDecoder;
import com.telecombridge.diameter.netty.DiameterMessageEncoder;
import com.telecombridge.diameter.netty.NettyTransport;
import com.telecombridge.gateway.config.DiameterClientProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * Asynchronous Diameter client holding one persistent TCP connection to the OCS peer.
 *
 * <h2>Lifecycle</h2>
 * {@code CLOSED → CONNECTING → WAIT_CEA → OPEN}. On startup the client connects and sends CER;
 * only after a CEA with Result-Code 2001 is application traffic (CCR) allowed. When the connection
 * is lost every in-flight request fails fast with {@link PeerUnavailableException} and a reconnect is
 * scheduled with exponential back-off and jitter. On shutdown a DPR is sent before the socket is closed.
 *
 * <h2>Watchdog (RFC 3539)</h2>
 * If nothing is sent or received for Tw ({@code diameter.watchdog-interval}) a DWR is sent; if its
 * DWA does not arrive within another Tw the connection is considered failed and is re-established.
 * DWRs sent by the peer are answered with DWA.
 *
 * <h2>Threading</h2>
 * {@link #send} never blocks: it registers a future in {@link PendingRequests} keyed by Hop-by-Hop
 * Identifier, hands the message to the Netty event loop and returns. The event loop completes the
 * future when the matching answer is decoded.
 */
@Component
public class DiameterPeerClient implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DiameterPeerClient.class);
    private static final Duration DPR_TIMEOUT = Duration.ofSeconds(1);

    private final DiameterClientProperties props;
    private final LocalPeer self;
    private final IdentifierGenerator ids = new IdentifierGenerator();
    private final PendingRequests pending = new PendingRequests();
    private final AtomicReference<PeerState> state = new AtomicReference<>(PeerState.CLOSED);
    private final NettyTransport transport;
    private final EventLoopGroup group;
    private final Bootstrap bootstrap;

    private final Timer ccrTimer;
    private final Counter reconnects;

    private volatile Channel channel;
    private volatile boolean running;
    private volatile String peerOriginHost;
    private final AtomicInteger reconnectAttempt = new AtomicInteger();

    public DiameterPeerClient(DiameterClientProperties props, MeterRegistry registry) {
        this.props = props;
        this.self = new LocalPeer(props.originHost(), props.originRealm(), localAddress(), 0,
                "Telecom-Bridge Gateway", System.currentTimeMillis() / 1000);
        this.transport = NettyTransport.best();
        this.group = transport.newEventLoopGroup(props.ioThreads(), new DefaultThreadFactory("diameter-io", true));
        DiameterMessageEncoder encoder = new DiameterMessageEncoder();
        this.bootstrap = new Bootstrap()
                .group(group)
                .channel(transport.socketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) props.connectTimeout().toMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(256 * 1024, 1024 * 1024))
                .remoteAddress(props.peerHost(), props.peerPort())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                // Coalesce flushes of back-to-back writes into fewer syscalls under load.
                                .addLast("flush", new FlushConsolidationHandler(64, true))
                                .addLast("idle", new IdleStateHandler(0, 0,
                                        props.watchdogInterval().toMillis(), TimeUnit.MILLISECONDS))
                                .addLast("frameDecoder", new DiameterFrameDecoder())
                                .addLast("encoder", encoder)
                                .addLast("peer", new PeerHandler());
                    }
                });

        this.ccrTimer = Timer.builder("diameter.client.ccr")
                .description("CCR to CCA round-trip time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.reconnects = Counter.builder("diameter.client.reconnects").register(registry);
        Gauge.builder("diameter.client.pending", pending, PendingRequests::size)
                .description("Requests awaiting an answer").register(registry);
        Gauge.builder("diameter.client.peer.open", state, s -> s.get() == PeerState.OPEN ? 1 : 0)
                .description("1 when the Diameter peer connection is OPEN").register(registry);
    }

    // ------------------------------------------------------------------ public API

    /** Sends a CCR built from {@code ccr} and completes with the CCA. */
    public CompletableFuture<DiameterMessage> sendCreditControl(CreditControlRequest ccr) {
        long start = System.nanoTime();
        return send(hbh -> ccr.toMessage(self, hbh, ids.nextEndToEndId()), props.requestTimeout())
                .whenComplete((answer, error) -> ccrTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS));
    }

    public PeerState state() {
        return state.get();
    }

    public String peerOriginHost() {
        return peerOriginHost;
    }

    public int pendingRequests() {
        return pending.size();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        running = true;
        log.info("Starting Diameter client {} -> {}:{} ({} transport)", self.originHost(), props.peerHost(), props.peerPort(), transport);
        connect();
    }

    @Override
    public void stop() {
        running = false;
        Channel ch = channel;
        if (ch != null && state.compareAndSet(PeerState.OPEN, PeerState.CLOSING)) {
            log.info("Sending DPR to {}", peerOriginHost);
            try {
                sendOn(ch, hbh -> BaseMessages.dpr(self, hbh, ids.nextEndToEndId(), BaseMessages.DISCONNECT_CAUSE_REBOOTING), DPR_TIMEOUT)
                        .get(DPR_TIMEOUT.toMillis() + 200, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("No DPA before shutdown: {}", e.toString());
            }
        }
        if (ch != null) {
            ch.close().syncUninterruptibly();
        }
        pending.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        state.set(PeerState.CLOSED);
        log.info("Diameter client stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start before the web server accepts traffic and stop after it has drained. */
    @Override
    public int getPhase() {
        return 0;
    }

    // ------------------------------------------------------------------ internals

    private CompletableFuture<DiameterMessage> send(IntFunction<DiameterMessage> builder, Duration timeout) {
        Channel ch = channel;
        if (state.get() != PeerState.OPEN || ch == null || !ch.isActive()) {
            return CompletableFuture.failedFuture(new PeerUnavailableException(
                    "Diameter peer " + props.peerHost() + ":" + props.peerPort() + " is " + state.get()));
        }
        if (pending.size() >= props.maxPendingRequests()) {
            return CompletableFuture.failedFuture(new GatewayOverloadedException(
                    "Too many in-flight Diameter requests (" + pending.size() + ")"));
        }
        if (!ch.isWritable()) {
            return CompletableFuture.failedFuture(new GatewayOverloadedException("Diameter socket send buffer is full"));
        }
        return sendOn(ch, builder, timeout);
    }

    private CompletableFuture<DiameterMessage> sendOn(Channel ch, IntFunction<DiameterMessage> builder, Duration timeout) {
        int hopByHop = ids.nextHopByHopId();
        DiameterMessage request = builder.apply(hopByHop);
        CompletableFuture<DiameterMessage> future = pending.register(hopByHop, request.mnemonic(), timeout);
        ch.writeAndFlush(request).addListener(write -> {
            if (!write.isSuccess()) {
                pending.fail(hopByHop, new PeerUnavailableException("Write failed: " + write.cause()));
            }
        });
        if (log.isDebugEnabled()) {
            log.debug("-> {}", request);
        }
        return future;
    }

    private void connect() {
        if (!running || !state.compareAndSet(PeerState.CLOSED, PeerState.CONNECTING)) {
            return;
        }
        ChannelFuture connectFuture = bootstrap.connect();
        connectFuture.addListener(f -> {
            if (!f.isSuccess()) {
                log.warn("Connect to {}:{} failed: {}", props.peerHost(), props.peerPort(), f.cause().toString());
                state.set(PeerState.CLOSED);
                scheduleReconnect();
                return;
            }
            Channel ch = connectFuture.channel();
            channel = ch;
            // Set the state before registering the close listener: if the socket is already closed the
            // listener runs immediately and must be able to move WAIT_CEA back to CLOSED and reconnect.
            state.set(PeerState.WAIT_CEA);
            ch.closeFuture().addListener(cf -> onDisconnected(ch));
            log.info("TCP connected {} -> {}, sending CER", ch.localAddress(), ch.remoteAddress());
            sendOn(ch, hbh -> BaseMessages.cer(self, hbh, ids.nextEndToEndId(), ApplicationId.CREDIT_CONTROL),
                    props.requestTimeout().multipliedBy(5))
                    .whenComplete((cea, error) -> onCapabilitiesExchangeAnswer(ch, cea, error));
        });
    }

    private void onCapabilitiesExchangeAnswer(Channel ch, DiameterMessage cea, Throwable error) {
        if (error != null) {
            log.error("Capabilities exchange failed: {}", error.toString());
            ch.close();
            return;
        }
        long resultCode = cea.resultCode().orElse(0L);
        if (resultCode != ResultCode.DIAMETER_SUCCESS) {
            log.error("CEA rejected with {} ({}): {}", resultCode, ResultCode.describe(resultCode),
                    cea.utf8(AvpCode.ERROR_MESSAGE).orElse(""));
            ch.close();
            return;
        }
        peerOriginHost = cea.utf8(AvpCode.ORIGIN_HOST).orElse("<unknown>");
        if (state.compareAndSet(PeerState.WAIT_CEA, PeerState.OPEN)) {
            reconnectAttempt.set(0);
            log.info("Diameter peer {} (realm {}) OPEN, CEA Result-Code {}", peerOriginHost,
                    cea.utf8(AvpCode.ORIGIN_REALM).orElse("?"), resultCode);
        }
    }

    private void onDisconnected(Channel ch) {
        if (channel == ch) {
            channel = null;
        }
        PeerState previous = state.getAndSet(PeerState.CLOSED);
        int failed = pending.failAll(() -> new PeerUnavailableException("Diameter connection lost"));
        log.warn("Diameter connection to {} closed (was {}), failed {} in-flight requests", ch.remoteAddress(), previous, failed);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running || group.isShuttingDown()) {
            return;
        }
        int attempt = Math.min(reconnectAttempt.getAndIncrement(), 16);
        long base = Math.min(props.reconnectMaxDelay().toMillis(), props.reconnectMinDelay().toMillis() << attempt);
        long delay = base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
        reconnects.increment();
        log.info("Reconnecting to {}:{} in {} ms (attempt {})", props.peerHost(), props.peerPort(), delay, attempt + 1);
        group.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private static InetAddress localAddress() {
        try {
            return InetAddress.getLocalHost();
        } catch (Exception e) {
            return InetAddress.getLoopbackAddress();
        }
    }

    /** Per-connection inbound handler; runs on the channel's event loop. */
    private final class PeerHandler extends SimpleChannelInboundHandler<DiameterMessage> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage msg) {
            if (log.isDebugEnabled()) {
                log.debug("<- {}", msg);
            }
            if (!msg.isRequest()) {
                pending.complete(msg);
                return;
            }
            switch (msg.commandCode()) {
                case CommandCode.DEVICE_WATCHDOG -> ctx.writeAndFlush(BaseMessages.dwa(msg, self));
                case CommandCode.DISCONNECT_PEER -> {
                    log.info("Peer {} sent DPR, closing connection", peerOriginHost);
                    state.set(PeerState.CLOSING);
                    ctx.writeAndFlush(BaseMessages.dpa(msg, self)).addListener(f -> ctx.close());
                }
                default -> ctx.writeAndFlush(BaseMessages.errorAnswer(msg, self, ResultCode.DIAMETER_COMMAND_UNSUPPORTED, null));
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.ALL_IDLE && state.get() == PeerState.OPEN) {
                Channel ch = ctx.channel();
                log.debug("Connection idle for {}, sending DWR", props.watchdogInterval());
                sendOn(ch, hbh -> BaseMessages.dwr(self, hbh, ids.nextEndToEndId()), props.watchdogInterval())
                        .whenComplete((dwa, error) -> {
                            if (error != null) {
                                log.warn("Watchdog failed ({}), closing connection to {}", error.getMessage(), peerOriginHost);
                                ch.close();
                            }
                        });
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Diameter connection error, closing {}: {}", ctx.channel().remoteAddress(), cause.toString());
            ctx.close();
        }
    }
}
