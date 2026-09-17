package org.allaymc.bedrocktunnel.tunnel.nethernet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;

/**
 * Builds the bedrock pipeline on a NetherNet channel, mirroring the library's
 * {@code BedrockChannelInitializer} with the RakNet frame id codec swapped for the NetherNet
 * frame codec. Above that everything matches the RakNet path: an initially uncompressed,
 * unprefixed batch flow that switches once the NetworkSettings handshake negotiates an algorithm.
 */
public abstract class NetherNetChannelInitializer<T extends BedrockSession> extends ChannelInitializer<Channel> {
    private static final BedrockBatchDecoder BATCH_DECODER = new BedrockBatchDecoder();
    private static final CompressionStrategy NOOP_STRATEGY = new SimpleCompressionStrategy(new NoopCompression());

    private final PacketDirection direction;

    public NetherNetChannelInitializer(PacketDirection direction) {
        this.direction = direction;
    }

    @Override
    protected final void initChannel(Channel channel) throws Exception {
        channel.attr(PacketDirection.ATTRIBUTE).set(direction);

        channel.pipeline()
                .addLast(NetherNetFrameCodec.NAME, new NetherNetFrameCodec())
                .addLast(CompressionCodec.NAME, new CompressionCodec(NOOP_STRATEGY, false))
                .addLast(BedrockBatchDecoder.NAME, BATCH_DECODER)
                .addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder());
        channel.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
        channel.pipeline().addLast(BedrockPeer.NAME, new NetherNetBedrockPeer(channel, this::createSession));

        this.postInitChannel(channel);
    }

    protected void postInitChannel(Channel channel) throws Exception {
    }

    private final T createSession(BedrockPeer peer, int subClientId) {
        T session = this.createSession0(peer, subClientId);
        this.initSession(session);
        return session;
    }

    public abstract T createSession0(BedrockPeer peer, int subClientId);

    protected abstract void initSession(T session);
}
