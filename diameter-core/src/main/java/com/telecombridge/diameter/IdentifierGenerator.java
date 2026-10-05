package com.telecombridge.diameter;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hop-by-Hop and End-to-End identifier sources (RFC 6733 section 3).
 * <ul>
 *   <li>Hop-by-Hop: monotonically increasing from a random start value.</li>
 *   <li>End-to-End: high 12 bits from the low 12 bits of the current time in seconds, low 20 bits
 *       a random start value that is incremented per request.</li>
 * </ul>
 * Both counters wrap around naturally at 2^32 and are lock-free.
 */
public final class IdentifierGenerator {

    private final AtomicInteger hopByHop = new AtomicInteger(ThreadLocalRandom.current().nextInt());
    private final AtomicInteger endToEnd;

    public IdentifierGenerator() {
        int timeBits = (int) ((System.currentTimeMillis() / 1000) & 0xFFF) << 20;
        int randomBits = ThreadLocalRandom.current().nextInt(1 << 20);
        this.endToEnd = new AtomicInteger(timeBits | randomBits);
    }

    public int nextHopByHopId() {
        return hopByHop.getAndIncrement();
    }

    public int nextEndToEndId() {
        return endToEnd.getAndIncrement();
    }
}
