package org.allaymc.bedrocktunnel.tunnel;

import org.cloudburstmc.protocol.bedrock.codec.compat.BedrockCompat;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.common.PacketSignal;

final class RejectingUpstreamHandler implements BedrockPacketHandler {
    private final TunnelServerSession session;

    RejectingUpstreamHandler(TunnelServerSession session) {
        this.session = session;
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        session.setCodec(BedrockCompat.disconnectCompat(packet.getProtocolVersion()));
        session.disconnect("This BedrockTunnel instance already has an active session.", false);
        return PacketSignal.HANDLED;
    }
}
