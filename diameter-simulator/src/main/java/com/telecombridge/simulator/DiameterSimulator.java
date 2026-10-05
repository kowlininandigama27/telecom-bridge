package com.telecombridge.simulator;

import com.telecombridge.diameter.IdentifierGenerator;
import com.telecombridge.diameter.LocalPeer;
import com.telecombridge.diameter.netty.DiameterFrameDecoder;
import com.telecombridge.diameter.netty.DiameterMessageEncoder;
import com.telecombridge.diameter.netty.NettyTransport;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Standalone Diameter Credit-Control server simulator.
 * Usable as an application ({@link #main}) or embedded in tests ({@link #start}).
 */
public final class DiameterSimulator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiameterSimulator.class);

    private final SimulatorConfig config;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final LongAdder ccrCounter = new LongAdder();
    private final Channel serverChannel;
    private final PreciseDelayScheduler preciseScheduler;

    private DiameterSimulator(SimulatorConfig config) throws InterruptedException {
        this.config = config;
        NettyTransport transport = NettyTransport.best();
        this.bossGroup = transport.newEventLoopGroup(1, new DefaultThreadFactory("sim-accept"));
        this.workerGroup = transport.newEventLoopGroup(config.ioThreads(), new DefaultThreadFactory("sim-io"));

        LocalPeer self = new LocalPeer(config.originHost(), config.originRealm(), localAddress(),
                0, "Telecom-Bridge OCS Simulator", System.currentTimeMillis() / 1000);
        ChargingEngine engine = new ChargingEngine(config.originHost(), config.originRealm());
        IdentifierGenerator ids = new IdentifierGenerator();
        DiameterMessageEncoder encoder = new DiameterMessageEncoder();
        long watchdogSeconds = config.watchdogInterval().toSeconds();
        this.preciseScheduler = config.preciseTimer() ? new PreciseDelayScheduler() : null;

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(transport.serverSocketChannel())
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("idle", new IdleStateHandler(0, 0, watchdogSeconds, TimeUnit.SECONDS))
                                .addLast("frameDecoder", new DiameterFrameDecoder())
                                .addLast("encoder", encoder)
                                .addLast("peer", new SimulatorPeerHandler(self, engine, config, ids, ccrCounter, preciseScheduler));
                    }
                });
        this.serverChannel = bootstrap.bind(config.port()).sync().channel();
        workerGroup.next().scheduleAtFixedRate(new ThroughputLogger(), 10, 10, TimeUnit.SECONDS);
        log.info("Diameter simulator {} listening on {} via {} (CCA delay {}-{} ms, {} timer)", config.originHost(),
                serverChannel.localAddress(), transport, config.minDelay().toMillis(), config.maxDelay().toMillis(),
                config.preciseTimer() ? "precise" : "event-loop");
    }

    public static DiameterSimulator start(SimulatorConfig config) throws InterruptedException {
        return new DiameterSimulator(config);
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public long ccrCount() {
        return ccrCounter.sum();
    }

    public SimulatorConfig config() {
        return config;
    }

    @Override
    public void close() {
        log.info("Shutting down Diameter simulator (served {} CCRs)", ccrCounter.sum());
        serverChannel.close().syncUninterruptibly();
        workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        if (preciseScheduler != null) {
            preciseScheduler.close();
        }
    }

    public static void main(String[] args) throws Exception {
        DiameterSimulator simulator = start(SimulatorConfig.fromEnvironment());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            simulator.close();
            stopped.countDown();
        }, "simulator-shutdown"));
        stopped.await();
    }

    private static InetAddress localAddress() {
        try {
            return InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            return InetAddress.getLoopbackAddress();
        }
    }

    private final class ThroughputLogger implements Runnable {
        private long last;

        @Override
        public void run() {
            long total = ccrCounter.sum();
            if (total != last) {
                log.info("CCR throughput: {} TPS (total {})", (total - last) / 10, total);
                last = total;
            }
        }
    }
}
