package com.telecombridge.gateway.diameter;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposes the Diameter peer state as {@code diameterPeer} in /actuator/health (and the readiness group). */
@Component("diameterPeer")
public class DiameterPeerHealthIndicator implements HealthIndicator {

    private final DiameterPeerClient client;

    public DiameterPeerHealthIndicator(DiameterPeerClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        PeerState state = client.state();
        Health.Builder builder = state == PeerState.OPEN ? Health.up() : Health.down();
        return builder.withDetail("state", state)
                .withDetail("peer", client.peerOriginHost() == null ? "n/a" : client.peerOriginHost())
                .withDetail("pendingRequests", client.pendingRequests())
                .build();
    }
}
