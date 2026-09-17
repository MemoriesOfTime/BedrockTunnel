package org.allaymc.bedrocktunnel.tunnel.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;

import java.util.List;

/**
 * Moves a compressed batch between the NetherNet data channel and the bedrock pipeline.
 * <p>
 * A RakNet frame carries the batch behind a {@code 0xFE} frame id byte; a NetherNet data channel
 * message carries the batch by itself, so this codec only converts between the raw {@link ByteBuf}
 * the transport hands over and the {@link BedrockBatchWrapper} the rest of the pipeline expects.
 */
final class NetherNetFrameCodec extends MessageToMessageCodec<ByteBuf, BedrockBatchWrapper> {
    static final String NAME = "nethernet-frame-codec";

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
        if (msg.getCompressed() == null) {
            throw new IllegalStateException("Bedrock batch was not compressed");
        }
        out.add(msg.getCompressed().retainedSlice());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        if (!msg.isReadable()) {
            return;
        }
        out.add(BedrockBatchWrapper.newInstance(msg.readRetainedSlice(msg.readableBytes()), null));
    }
}
