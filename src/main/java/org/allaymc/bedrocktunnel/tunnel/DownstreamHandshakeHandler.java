package org.allaymc.bedrocktunnel.tunnel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannel;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import dev.mot.protocol.extension.NetEaseCompression;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.bedrock.util.JsonUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jose4j.json.JsonUtil;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;

import javax.crypto.SecretKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Map;

public final class DownstreamHandshakeHandler implements BedrockPacketHandler {
    private static final Logger LOGGER = LogManager.getLogger(DownstreamHandshakeHandler.class);

    private final TunnelClientSession session;
    private final TunnelController controller;
    private final TunnelRuntime runtime;

    DownstreamHandshakeHandler(TunnelClientSession session, TunnelController controller, TunnelRuntime runtime) {
        this.session = session;
        this.controller = controller;
        this.runtime = runtime;
    }

    @Override
    public PacketSignal handle(NetworkSettingsPacket packet) {
        if (runtime.config().codec().netEase()) {
            session.getPeer().setCompression(new SimpleCompressionStrategy(new NetEaseCompression()));
        } else {
            session.setCompression(packet.getCompressionAlgorithm());
        }
        if (!runtime.markDownstreamLoginSent()) {
            LOGGER.warn("Ignoring downstream NetworkSettings for {} because login was already sent", runtime.config().targetLabel());
            return PacketSignal.HANDLED;
        }
        LoginPacket loginPacket = LoginForgery.forgeLogin(runtime);
        LOGGER.info(
                "Downstream network settings received for {}: compression={}, threshold={}, loginPayload={}",
                runtime.config().codec().displayVersion(),
                packet.getCompressionAlgorithm(),
                packet.getCompressionThreshold(),
                loginPacket.getAuthPayload().getClass().getSimpleName()
        );
        session.sendPacketImmediately(loginPacket);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ServerToClientHandshakePacket packet) {
        if (session.getPeer().getChannel() instanceof NetherNetChannel) {
            // NetherNet dropped the Bedrock-layer encryption the handshake used to switch on (the
            // data channel already runs inside DTLS), but servers still send the packet and the
            // login flow only moves on once it is acknowledged.
            session.sendPacketImmediately(new ClientToServerHandshakePacket());
            LOGGER.info("Downstream NetherNet handshake acknowledged for {}", runtime.config().targetLabel());
            controller.onTunnelEstablished(runtime);
            return PacketSignal.HANDLED;
        }

        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(packet.getJwt());
            Map<String, Object> saltJwt = JsonUtil.parseJson(jws.getUnverifiedPayload());
            String x5u = jws.getHeader(HeaderParameterNames.X509_URL);
            ECPublicKey serverKey = EncryptionUtils.parseKey(x5u);
            SecretKey key = EncryptionUtils.getSecretKey(
                    runtime.proxyKeyPair().getPrivate(),
                    serverKey,
                    Base64.getDecoder().decode(JsonUtils.childAsType(saltJwt, "salt", String.class))
            );
            session.enableEncryption(key);
            session.sendPacketImmediately(new ClientToServerHandshakePacket());
            LOGGER.info("Downstream server handshake completed for {}", runtime.config().targetLabel());
            controller.onTunnelEstablished(runtime);
            return PacketSignal.HANDLED;
        } catch (Exception exception) {
            LOGGER.error("Unable to establish downstream encryption for {}", runtime.config().targetLabel(), exception);
            session.disconnect("Unable to establish downstream encryption", false);
            return PacketSignal.HANDLED;
        }
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        controller.handleDownstreamClosed(runtime, session, reason);
    }
}
