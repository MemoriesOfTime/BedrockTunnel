package org.allaymc.bedrocktunnel.tunnel.nethernet;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import org.allaymc.bedrocktunnel.BedrockTunnelPaths;
import org.allaymc.bedrocktunnel.tunnel.TunnelStartConfig;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetClientChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetServerChannel;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling.PongData;
import org.cloudburstmc.netty.util.nethernet.NetherNetLogging;
import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry points for the NetherNet legs of a tunnel. The listen side serves the HTTP signaling
 * endpoint of the NetherNet protocol on the TCP listen port, the way the Mojang onboarding guide
 * has dedicated servers do it, so a client that speaks NetherNet reaches the tunnel by pointing
 * at the same address it would point at over RakNet. The target side posts a join request to the
 * signaling endpoint the target serves.
 */
public final class NetherNetTransports {
    private static final String IDENTITY_DOMAIN = "bedrocktunnel.local";
    private static final int GAME_TYPE_CREATIVE = 1;
    private static final int SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS = 30;
    private static final int CLIENT_HANDSHAKE_TIMEOUT_MS = 20_000;
    // The HTTP signaling client posts a single offer, so the transport's re-offer retry would
    // only spend time on an exchange that can no longer succeed.
    private static final int CLIENT_HANDSHAKE_ATTEMPTS = 1;

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private NetherNetTransports() {
    }

    /**
     * Loads the native libdatachannel library and tames its logging. Safe to call repeatedly;
     * only the first call does work. A missing native surfaces here as an exception, which the
     * caller turns into the regular startup error dialog.
     */
    public static void ensureInitialized() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        tel.schich.libdatachannel.LibDataChannelArchDetect.initialize();
        NetherNetLogging.setNativeLogLevel(System.getProperty("bedrocktunnel.nethernetLog", "WARN"));
    }

    /**
     * A server bootstrap whose NetherNet server channel serves HTTP signaling on the listen port.
     */
    public static ServerBootstrap serverBootstrap(
            NioEventLoopGroup group,
            TunnelStartConfig config,
            ChannelInitializer<Channel> childHandler
    ) throws IOException {
        ensureInitialized();
        return new ServerBootstrap()
                .group(group)
                .channelFactory(serverChannelFactory(config))
                .option(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS, SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS)
                .childHandler(childHandler);
    }

    /**
     * A bootstrap whose NetherNet client channel joins the target through its HTTP signaling
     * endpoint. The exchange plus ICE takes far longer than the transport's default 3 second
     * handshake window, so the timeout is raised here. The player identity travels in the
     * assertion the target reads out of the posted offer.
     */
    public static Bootstrap clientBootstrap(
            NioEventLoopGroup group,
            TunnelStartConfig config,
            ChannelInitializer<Channel> handler,
            String playerXuid,
            String playerName
    ) throws IOException {
        ensureInitialized();
        return new Bootstrap()
                .group(group)
                .channelFactory(clientChannelFactory(config.targetAddress(), loadIdentity().keyPair(), playerXuid, playerName))
                .option(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, CLIENT_HANDSHAKE_TIMEOUT_MS)
                .option(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS, CLIENT_HANDSHAKE_ATTEMPTS)
                .handler(handler);
    }

    /**
     * The persistent NetherNet identity. Clients pin the public key, so a fresh one on every
     * start would prompt every returning player again.
     */
    private static ServerIdentity loadIdentity() throws IOException {
        Path identityPem = BedrockTunnelPaths.dataDirectory().resolve("nethernet-identity.pem");
        try {
            return ServerIdentity.fromPemOrCreate(identityPem.toFile(), IDENTITY_DOMAIN);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Unable to load or create the NetherNet identity key " + identityPem, exception);
        }
    }

    /**
     * Builds the signaling endpoint the NetherNet listen port serves.
     */
    private static NetherNetHTTPSignaling createServerSignaling(TunnelStartConfig config) throws IOException {
        ServerIdentity identity = loadIdentity();

        PongData pong = new PongData.Builder()
                .setServerName("BedrockTunnel")
                .setProtocol(config.codec().protocolVersion())
                .setVersion(config.codec().minecraftVersion())
                .setLevelName("MITM Packet Tunnel")
                .setGameType(GAME_TYPE_CREATIVE)
                .setPlayerCount(0)
                .setMaxPlayerCount(1)
                // The tunnel signs its own chains instead of the auth service
                .setOnlineAuth(false)
                .setSelfSignedAuth(true)
                .build();

        try {
            return new NetherNetHTTPSignaling.Builder()
                    .setIdentity(identity)
                    // A debugging tool has no auth service behind it to vet client tokens with
                    .setTokenTrust(TokenTrust.ANY)
                    .setMotdProvider((host, clientAddress) -> pong)
                    .build();
        } catch (Exception exception) {
            throw new IOException("Unable to build the NetherNet signaling endpoint", exception);
        }
    }

    private static ChannelFactory<NetherNetServerChannel> serverChannelFactory(TunnelStartConfig config) throws IOException {
        return NetherNetChannelFactory.server(createServerSignaling(config));
    }

    private static ChannelFactory<NetherNetClientChannel> clientChannelFactory(
            InetSocketAddress signalingAddress,
            KeyPair identityKey,
            String playerXuid,
            String playerName
    ) {
        return NetherNetChannelFactory.client(
                new HttpSignalingClient(signalingAddress, identityKey, IDENTITY_DOMAIN, playerXuid, playerName));
    }
}
