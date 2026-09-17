package org.allaymc.bedrocktunnel.tunnel;

/**
 * Transport that carries one leg of the tunnel. The two legs are independent, so a capture can
 * mix them, for example accepting a RakNet client while relaying to a NetherNet-only target.
 */
public enum TunnelTransport {
    RAKNET("RakNet"),
    NETHERNET("NetherNet");

    private final String displayName;

    TunnelTransport(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
