package com.telecombridge.diameter.netty;

import com.telecombridge.diameter.DiameterMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** Encodes {@link DiameterMessage} into a pooled buffer sized exactly to the message. Stateless. */
@ChannelHandler.Sharable
public class DiameterMessageEncoder extends MessageToByteEncoder<DiameterMessage> {

    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, DiameterMessage msg, boolean preferDirect) {
        int length = msg.length();
        return preferDirect ? ctx.alloc().ioBuffer(length, length) : ctx.alloc().heapBuffer(length, length);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, DiameterMessage msg, ByteBuf out) {
        msg.encode(out);
    }
}
