package com.telecombridge.gateway;

import com.telecombridge.gateway.diameter.DiameterPeerClient;
import com.telecombridge.gateway.diameter.PeerState;

import java.time.Duration;

public final class TestSupport {

    private TestSupport() {
    }

    public static void awaitState(DiameterPeerClient client, PeerState expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (client.state() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Peer state " + client.state() + " != " + expected + " after " + timeout);
            }
            Thread.sleep(20);
        }
    }
}
