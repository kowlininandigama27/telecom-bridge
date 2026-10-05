package com.telecombridge.simulator;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.AvpCode;
import com.telecombridge.diameter.BaseMessages;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.CreditControlRequest;
import com.telecombridge.diameter.DiameterDecodeException;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.IdentifierGenerator;
import com.telecombridge.diameter.LocalPeer;
import com.telecombridge.diameter.MissingAvpException;
import com.telecombridge.diameter.ResultCode;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Server side of one Diameter peer connection. One instance per channel; all state is confined
 * to the channel's event loop, so no synchronisation is needed.
 */
final class SimulatorPeerHandler extends SimpleChannelInboundHandler<DiameterMessage> {

    private static final Logger log = LoggerFactory.getLogger(SimulatorPeerHandler.class);

    private final LocalPeer self;
    private final ChargingEngine engine;
    private final SimulatorConfig config;
    private final IdentifierGenerator ids;
    private final LongAdder ccrCounter;
    private final PreciseDelayScheduler preciseScheduler;

    private boolean capabilitiesExchanged;
    private String remoteHost = "<unknown>";

    SimulatorPeerHandler(LocalPeer self, ChargingEngine engine, SimulatorConfig config, IdentifierGenerator ids,
                         LongAdder ccrCounter, PreciseDelayScheduler preciseScheduler) {
        this.self = self;
        this.engine = engine;
        this.config = config;
        this.ids = ids;
        this.ccrCounter = ccrCounter;
        this.preciseScheduler = preciseScheduler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("Peer connected from {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Peer {} disconnected ({})", remoteHost, ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DiameterMessage msg) {
        if (!msg.isRequest()) {
            // Only DWA is expected (answers to our own watchdogs).
            log.debug("<- {}", msg);
            return;
        }
        if (!capabilitiesExchanged && msg.commandCode() != CommandCode.CAPABILITIES_EXCHANGE) {
            log.warn("{} received before CER from {}, rejecting", msg.mnemonic(), ctx.channel().remoteAddress());
            ctx.writeAndFlush(BaseMessages.errorAnswer(msg, self, ResultCode.DIAMETER_UNKNOWN_PEER, "CER required first"))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        switch (msg.commandCode()) {
            case CommandCode.CAPABILITIES_EXCHANGE -> onCer(ctx, msg);
            case CommandCode.DEVICE_WATCHDOG -> {
                log.debug("<- {}", msg);
                ctx.writeAndFlush(BaseMessages.dwa(msg, self));
            }
            case CommandCode.DISCONNECT_PEER -> {
                log.info("DPR from {}, closing connection", remoteHost);
                ctx.writeAndFlush(BaseMessages.dpa(msg, self)).addListener(ChannelFutureListener.CLOSE);
            }
            case CommandCode.CREDIT_CONTROL -> onCcr(ctx, msg);
            default -> ctx.writeAndFlush(BaseMessages.errorAnswer(msg, self, ResultCode.DIAMETER_COMMAND_UNSUPPORTED,
                    "Command " + msg.commandCode() + " not supported"));
        }
    }

    private void onCer(ChannelHandlerContext ctx, DiameterMessage cer) {
        remoteHost = cer.utf8(AvpCode.ORIGIN_HOST).orElse("<missing>");
        boolean supportsCreditControl = cer.avps(AvpCode.AUTH_APPLICATION_ID).stream()
                .mapToLong(a -> a.asUnsigned32())
                .anyMatch(id -> id == ApplicationId.CREDIT_CONTROL || id == Integer.toUnsignedLong(ApplicationId.RELAY));
        if (!supportsCreditControl) {
            log.warn("CER from {} has no common application, answering 5010", remoteHost);
            ctx.writeAndFlush(BaseMessages.cea(cer, self, ResultCode.DIAMETER_NO_COMMON_APPLICATION,
                    "Only Auth-Application-Id 4 is supported")).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        capabilitiesExchanged = true;
        log.info("CER from {} (realm {}), answering CEA 2001", remoteHost, cer.utf8(AvpCode.ORIGIN_REALM).orElse("?"));
        ctx.writeAndFlush(BaseMessages.cea(cer, self, ResultCode.DIAMETER_SUCCESS, null, ApplicationId.CREDIT_CONTROL));
    }

    private void onCcr(ChannelHandlerContext ctx, DiameterMessage ccr) {
        long receivedNanos = System.nanoTime();
        if (ccr.applicationId() != ApplicationId.CREDIT_CONTROL) {
            ctx.writeAndFlush(BaseMessages.errorAnswer(ccr, self, ResultCode.DIAMETER_APPLICATION_UNSUPPORTED, null));
            return;
        }
        DiameterMessage answer;
        try {
            answer = engine.charge(CreditControlRequest.fromMessage(ccr)).toMessage(ccr);
        } catch (MissingAvpException e) {
            log.warn("Rejecting CCR hbh=0x{}: {}", Integer.toHexString(ccr.hopByHopId()), e.getMessage());
            answer = BaseMessages.errorAnswer(ccr, self, ResultCode.DIAMETER_MISSING_AVP, e.getMessage());
        } catch (DiameterDecodeException | IllegalArgumentException e) {
            log.warn("Rejecting CCR hbh=0x{}: {}", Integer.toHexString(ccr.hopByHopId()), e.getMessage());
            answer = BaseMessages.errorAnswer(ccr, self, ResultCode.DIAMETER_INVALID_AVP_VALUE, e.getMessage());
        }
        ccrCounter.increment();
        DiameterMessage cca = answer;
        // Simulated OCS latency, measured from CCR receipt. Never sleep on the event loop: each
        // in-flight CCR costs a timer entry, not a thread.
        long delayNanos = ThreadLocalRandom.current().nextLong(config.minDelay().toNanos(), config.maxDelay().toNanos() + 1);
        Runnable reply = () -> {
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(cca);
                log.debug("-> {}", cca);
            }
        };
        if (preciseScheduler != null) {
            preciseScheduler.schedule(receivedNanos + delayNanos, reply);
        } else {
            ctx.executor().schedule(reply, receivedNanos + delayNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.ALL_IDLE && capabilitiesExchanged) {
            log.debug("Connection idle, sending DWR to {}", remoteHost);
            ctx.writeAndFlush(BaseMessages.dwr(self, ids.nextHopByHopId(), ids.nextEndToEndId()));
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Closing connection to {} after error: {}", remoteHost, cause.toString());
        ctx.close();
    }
}
