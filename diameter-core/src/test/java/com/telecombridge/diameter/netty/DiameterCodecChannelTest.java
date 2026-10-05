package com.telecombridge.diameter.netty;

import com.telecombridge.diameter.BaseMessages;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.LocalPeer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiameterCodecChannelTest {

    static final LocalPeer PEER = new LocalPeer("a.example", "example", InetAddress.getLoopbackAddress(), 0, "test", 1);

    @Test
    void reassemblesMessagesSplitAcrossTcpSegments() {
        EmbeddedChannel channel = new EmbeddedChannel(new DiameterFrameDecoder());
        DiameterMessage dwr = BaseMessages.dwr(PEER, 10, 20);
        ByteBuf wire = encode(dwr);

        // deliver one byte at a time
        while (wire.isReadable()) {
            channel.writeInbound(wire.readRetainedSlice(1));
        }
        wire.release();

        DiameterMessage decoded = channel.readInbound();
        assertThat(decoded.hopByHopId()).isEqualTo(10);
        assertThat(decoded.mnemonic()).isEqualTo("DWR");
        assertThat(channel.finish()).isFalse();
    }

    @Test
    void splitsMultipleMessagesInOneSegment() {
        EmbeddedChannel channel = new EmbeddedChannel(new DiameterFrameDecoder());
        ByteBuf both = Unpooled.buffer();
        BaseMessages.dwr(PEER, 1, 1).encode(both);
        BaseMessages.dwr(PEER, 2, 2).encode(both);

        channel.writeInbound(both);

        assertThat(((DiameterMessage) channel.readInbound()).hopByHopId()).isEqualTo(1);
        assertThat(((DiameterMessage) channel.readInbound()).hopByHopId()).isEqualTo(2);
        assertThat(channel.finish()).isFalse();
    }

    @Test
    void encoderProducesExactlySizedBuffer() {
        EmbeddedChannel channel = new EmbeddedChannel(new DiameterMessageEncoder());
        DiameterMessage cer = BaseMessages.cer(PEER, 1, 1, 4);

        channel.writeOutbound(cer);

        ByteBuf out = channel.readOutbound();
        try {
            assertThat(out.readableBytes()).isEqualTo(cer.length());
            assertThat(DiameterMessage.decode(out).hopByHopId()).isEqualTo(1);
        } finally {
            out.release();
        }
        assertThat(channel.finish()).isFalse();
    }

    @Test
    void rejectsOversizedFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new DiameterFrameDecoder(64));
        ByteBuf wire = encode(BaseMessages.cer(PEER, 1, 1, 4));

        assertThatThrownBy(() -> channel.writeInbound(wire)).isInstanceOf(TooLongFrameException.class);
        channel.finishAndReleaseAll();
    }

    static ByteBuf encode(DiameterMessage msg) {
        ByteBuf buf = Unpooled.buffer();
        msg.encode(buf);
        return buf;
    }
}
