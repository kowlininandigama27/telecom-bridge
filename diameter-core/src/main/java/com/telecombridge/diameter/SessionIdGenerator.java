package com.telecombridge.diameter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates globally unique Session-Id values in the RFC 6733 section 8.8 recommended format
 * {@code <DiameterIdentity>;<high 32 bits>;<low 32 bits>}. The high part is the boot time in
 * seconds, so identifiers stay unique across restarts of the same Origin-Host.
 */
public final class SessionIdGenerator {

    private final String originHost;
    private final long high;
    private final AtomicLong low = new AtomicLong();

    public SessionIdGenerator(String originHost) {
        this.originHost = originHost;
        this.high = (System.currentTimeMillis() / 1000) & 0xFFFF_FFFFL;
    }

    public String next() {
        // The low part wraps at 2^32, as RFC 6733 allows.
        return originHost + ';' + high + ';' + (low.getAndIncrement() & 0xFFFF_FFFFL);
    }
}
