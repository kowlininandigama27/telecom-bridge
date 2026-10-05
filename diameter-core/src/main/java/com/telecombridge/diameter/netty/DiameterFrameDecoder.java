package com.telecombridge.diameter.netty;

import com.telecombridge.diameter.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Splits the TCP byte stream into Diameter messages using the 24-bit Message Length at offset 1
 * (which counts the whole message, header included) and decodes each frame into a
 * {@link DiameterMessage}. The pooled frame buffer is always released here, so nothing
 * downstream ever holds a reference-counted buffer.
 */
public class DiameterFrameDecoder extends LengthFieldBasedFrameDecoder {

    public static final int DEFAULT_MAX_MESSAGE_SIZE = 64 * 1024;

    public DiameterFrameDecoder() {
        this(DEFAULT_MAX_MESSAGE_SIZE);
    }

    public DiameterFrameDecoder(int maxMessageSize) {
        // lengthFieldOffset=1, lengthFieldLength=3, lengthAdjustment=-4 (field already counts the
        // 4 bytes of version + length), initialBytesToStrip=0 so the full header stays in the frame.
        super(maxMessageSize, 1, 3, -4, 0, true);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }
        try {
            return DiameterMessage.decode(frame);
        } finally {
            frame.release();
        }
    }
}
