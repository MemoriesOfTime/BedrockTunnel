package org.allaymc.bedrocktunnel.tunnel.nethernet;

import io.netty.channel.Channel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSessionFactory;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SnappyCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.ZlibCompression;
import org.cloudburstmc.protocol.common.util.Zlib;

import javax.crypto.SecretKey;

/**
 * A {@link BedrockPeer} for NetherNet channels.
 * <p>
 * Bedrock over NetherNet follows the RakNet 11 compression rules (raw zlib, algorithm byte once
 * the protocol is past 1.20.60), but the channel carries no RakNet options, so compression setup
 * cannot consult {@code RAK_PROTOCOL_VERSION} like the default peer does. Bedrock-layer
 * encryption does not exist on this transport either: the data channel already runs inside DTLS,
 * and a server that offers the handshake anyway expects the client to keep the stream plaintext
 * after acknowledging it.
 */
class NetherNetBedrockPeer extends BedrockPeer {
    private static final Logger LOGGER = LogManager.getLogger(NetherNetBedrockPeer.class);

    private static final CompressionStrategy ZLIB_RAW_STRATEGY = new SimpleCompressionStrategy(new ZlibCompression(Zlib.RAW));
    private static final CompressionStrategy SNAPPY_STRATEGY = new SimpleCompressionStrategy(new SnappyCompression());
    private static final CompressionStrategy NOOP_STRATEGY = new SimpleCompressionStrategy(new NoopCompression());

    NetherNetBedrockPeer(Channel channel, BedrockSessionFactory sessionFactory) {
        super(channel, sessionFactory);
    }

    @Override
    public void setCompression(PacketCompressionAlgorithm algorithm) {
        CompressionStrategy strategy = switch (algorithm) {
            case ZLIB -> ZLIB_RAW_STRATEGY;
            case SNAPPY -> SNAPPY_STRATEGY;
            case NONE -> NOOP_STRATEGY;
        };
        // The inherited strategy overload picks the prefixed form on its own from the codec version.
        this.setCompression(strategy);
    }

    @Override
    public void enableEncryption(SecretKey secretKey) {
        LOGGER.warn("Ignoring Bedrock encryption request on NetherNet: the data channel is already inside DTLS");
    }
}
