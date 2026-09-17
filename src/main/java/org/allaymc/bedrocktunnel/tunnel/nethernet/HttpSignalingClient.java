package org.allaymc.bedrocktunnel.tunnel.nethernet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling;
import org.cloudburstmc.netty.util.nethernet.Identity;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Client half of the NetherNet HTTP signaling protocol, used when the tunnel connects out to a
 * target that reaches players through an HTTP signaling endpoint such as a BDS with NetherNet
 * enabled or another BedrockTunnel instance.
 * <p>
 * The protocol is one round trip: {@code POST /v1/join/<localNetworkId>} carrying the SDP offer,
 * answered with the SDP body. The transport's signal bus is built for trickle ICE, so the offer
 * is held back until candidate gathering goes quiet and every candidate that arrived in between
 * is folded into the posted body, which is what a single-shot exchange requires. The posted offer
 * also carries a self-signed identity assertion binding the player the tunnel is connecting on
 * behalf of, which servers ask for before they accept an offer.
 */
final class HttpSignalingClient implements NetherNetClientSignaling {
    private static final Logger LOGGER = LogManager.getLogger(HttpSignalingClient.class);

    private static final long CANDIDATE_QUIET_MILLIS = 700;
    private static final long GATHER_LIMIT_MILLIS = 5_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final float TOKEN_LIFETIME_MINUTES = 60f;
    private static final String SIGNATURE_ALGORITHM = AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final InetSocketAddress signalingAddress;
    private final KeyPair identityKey;
    private final String identityDomain;
    private final String playerXuid;
    private final String playerName;
    private final String localNetworkId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bedrock-tunnel-nethernet-signaling");
        thread.setDaemon(true);
        return thread;
    });

    private volatile SignalHandler signalHandler;
    private volatile NotFoundHandler notFoundHandler;
    private volatile boolean closed;

    private final List<String> candidates = new ArrayList<>();
    private long connectionId;
    private String offer;
    private ScheduledFuture<?> quietTimer;
    private ScheduledFuture<?> gatherTimer;
    private boolean posted;

    HttpSignalingClient(InetSocketAddress signalingAddress, KeyPair identityKey, String identityDomain, String playerXuid, String playerName) {
        this.signalingAddress = signalingAddress;
        this.identityKey = identityKey;
        this.identityDomain = identityDomain;
        this.playerXuid = playerXuid;
        this.playerName = playerName;
    }

    @Override
    public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
        // No STUN or TURN: reaching every configured server has to finish before the offer can be
        // posted, which only slows a direct or LAN connection down.
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        // "<TYPE> <connectionId> <payload>", as the transport formats every outbound signal
        String[] parts = data.split(" ", 3);
        if (parts.length < 3) {
            return;
        }
        long id;
        try {
            id = Long.parseUnsignedLong(parts[1]);
        } catch (NumberFormatException exception) {
            return;
        }
        this.scheduler.execute(() -> this.onSignal(id, parts[0], parts[2]));
    }

    private void onSignal(long id, String type, String payload) {
        if (this.closed || this.posted) {
            return;
        }
        switch (type) {
            case NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST -> {
                this.connectionId = id;
                this.offer = payload;
                this.gatherTimer = this.scheduler.schedule(this::post, GATHER_LIMIT_MILLIS, TimeUnit.MILLISECONDS);
                this.rearmQuietTimer();
            }
            case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> {
                this.candidates.add(payload);
                this.rearmQuietTimer();
            }
            default -> LOGGER.debug("Ignoring outbound NetherNet signal {} on HTTP signaling", type);
        }
    }

    /**
     * Silence is the only completion cue the signal bus offers; there is no gathering-complete
     * message, so every candidate restarts the wait and the gather limit caps it.
     */
    private void rearmQuietTimer() {
        if (this.offer == null) {
            return;
        }
        if (this.quietTimer != null) {
            this.quietTimer.cancel(false);
        }
        this.quietTimer = this.scheduler.schedule(this::post, CANDIDATE_QUIET_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void post() {
        if (this.closed || this.posted || this.offer == null) {
            return;
        }
        this.posted = true;
        this.cancelTimers();

        String body = SdpMerge.withCandidates(this.offer, this.candidates);
        try {
            body = SdpMerge.withIdentity(body, this.buildIdentityValue(body));
        } catch (Exception exception) {
            this.fail("unable to sign the identity assertion: " + exception);
            return;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(this.joinUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/sdp")
                .header("Accept", "application/sdp")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenCompleteAsync((response, error) -> this.onAnswer(response, error), this.scheduler);
    }

    /**
     * The base64 {@code a=identity} value: a JWT naming the connecting player and the signing
     * key, plus a detached JWS over the offer fingerprints that lets the server check the key
     * really belongs to this offer.
     */
    private String buildIdentityValue(String offerSdp) throws Exception {
        JsonWebSignature fingerprints = new JsonWebSignature();
        fingerprints.setPayload(IdentityUtils.getCanonicalFingerprintJson(offerSdp));
        fingerprints.setKey(this.identityKey.getPrivate());
        fingerprints.setAlgorithmHeaderValue(SIGNATURE_ALGORITHM);
        String[] parts = fingerprints.getCompactSerialization().split("\\.");
        // Detached form: the payload travels as the SDP itself, so the middle segment is dropped
        String detached = parts[0] + ".." + parts[2];

        Identity.Assertion assertion = new Identity.Assertion(this.buildToken(), detached);
        return new Identity(new Identity.Idp(this.identityDomain, "default"), assertion).toBase64();
    }

    private String buildToken() throws Exception {
        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", Base64.getEncoder().encodeToString(this.identityKey.getPublic().getEncoded()));
        claims.setClaim("xid", this.playerXuid == null ? "" : this.playerXuid);
        claims.setClaim("xname", this.playerName == null ? "" : this.playerName);
        claims.setIssuer(this.identityDomain);
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(TOKEN_LIFETIME_MINUTES);

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(this.identityKey.getPrivate());
        jws.setAlgorithmHeaderValue(SIGNATURE_ALGORITHM);
        return jws.getCompactSerialization();
    }

    private void onAnswer(HttpResponse<String> response, Throwable error) {
        if (this.closed) {
            return;
        }
        if (error != null) {
            this.fail("signaling request failed: " + error.getMessage());
            return;
        }
        if (response.statusCode() / 100 != 2) {
            this.fail("signaling endpoint returned HTTP " + response.statusCode());
            return;
        }

        String answer = response.body();
        // A refusal can arrive as a 2xx carrying a short status body instead of an SDP
        if (answer == null || !answer.startsWith("v=")) {
            this.fail("signaling endpoint returned a 2xx that is not an SDP answer: "
                    + (answer == null ? "empty body" : answer.strip()));
            return;
        }

        SignalHandler handler = this.signalHandler;
        if (handler != null) {
            handler.onSignal(NetherNetConstants.buildSignalConnectResponse(this.connectionId, answer));
        }
    }

    private void fail(String reason) {
        LOGGER.warn("NetherNet signaling to {} failed: {}", this.signalingAddress, reason);
        NotFoundHandler handler = this.notFoundHandler;
        if (handler != null) {
            handler.onNotFound(reason);
        }
    }

    private String joinUrl() {
        InetAddress host = this.signalingAddress.getAddress();
        String literal = host == null ? this.signalingAddress.getHostString() : host.getHostAddress();
        // Already resolved, so the URL never triggers another lookup. IPv6 needs brackets.
        return "http://" + (literal.indexOf(':') >= 0 ? "[" + literal + "]" : literal)
                + ":" + this.signalingAddress.getPort()
                + "/v1/join/" + this.localNetworkId;
    }

    private void cancelTimers() {
        if (this.quietTimer != null) {
            this.quietTimer.cancel(false);
        }
        if (this.gatherTimer != null) {
            this.gatherTimer.cancel(false);
        }
    }

    @Override
    public void setSignalHandler(long connectionId, SignalHandler handler) {
        this.signalHandler = handler;
    }

    @Override
    public void removeSignalHandler(long connectionId) {
        this.signalHandler = null;
    }

    @Override
    public void setNotFoundHandler(NotFoundHandler handler) {
        this.notFoundHandler = handler;
    }

    @Override
    public String getLocalNetworkId() {
        return this.localNetworkId;
    }

    @Override
    public boolean isActive() {
        return !this.closed;
    }

    @Override
    public void close() {
        this.closed = true;
        this.scheduler.execute(() -> {
            this.cancelTimers();
            this.scheduler.shutdown();
        });
    }

    /**
     * SDP text handling for the single-shot HTTP exchange.
     */
    private static final class SdpMerge {
        private SdpMerge() {
        }

        /**
         * Folds trickled candidates into the offer as session level attributes and closes the
         * list, which is the shape a one-request exchange has to deliver.
         */
        static String withCandidates(String sdp, List<String> candidates) {
            String eol = eol(sdp);
            StringBuilder out = new StringBuilder(sdp.length() + candidates.size() * 96);
            for (String line : lines(sdp)) {
                if (!line.isEmpty()) {
                    out.append(line).append(eol);
                }
            }
            for (String candidate : candidates) {
                out.append("a=").append(candidate.trim()).append(eol);
            }
            return out.append("a=end-of-candidates").append(eol).toString();
        }

        /**
         * Inserts the identity assertion as a session level attribute ahead of the first media
         * line, where the NetherNet spec expects it. The assertion was computed over the
         * fingerprint lines, which sit above the insertion point, so this does not invalidate it.
         */
        static String withIdentity(String sdp, String value) {
            String eol = eol(sdp);
            String attribute = "a=identity:" + value;
            StringBuilder out = new StringBuilder(sdp.length() + value.length() + 16);
            boolean inserted = false;
            for (String line : lines(sdp)) {
                if (line.isEmpty()) {
                    continue;
                }
                if (!inserted && line.startsWith("m=")) {
                    out.append(attribute).append(eol);
                    inserted = true;
                }
                out.append(line).append(eol);
            }
            if (!inserted) {
                out.append(attribute).append(eol);
            }
            return out.toString();
        }

        private static String[] lines(String sdp) {
            return sdp.split("\r\n|\n", -1);
        }

        private static String eol(String sdp) {
            return sdp.contains("\r\n") ? "\r\n" : "\n";
        }
    }
}
