package com.telecombridge.loadtest;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free latency histogram with 10 µs resolution up to 10 s (values above are clamped into the
 * last bucket). 1M buckets × 8 bytes = 8 MB, fixed for the whole run, so recording never allocates.
 */
final class LatencyHistogram {

    private static final long BUCKET_NANOS = 10_000;
    private static final int BUCKETS = 1_000_000;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private final LongAdder total = new LongAdder();
    private final LongAdder sumNanos = new LongAdder();
    private final LongAccumulator max = new LongAccumulator(Math::max, 0);

    void record(long nanos) {
        int bucket = (int) Math.min(BUCKETS - 1, Math.max(0, nanos / BUCKET_NANOS));
        counts.incrementAndGet(bucket);
        total.increment();
        sumNanos.add(nanos);
        max.accumulate(nanos);
    }

    long count() {
        return total.sum();
    }

    double meanMillis() {
        long n = total.sum();
        return n == 0 ? 0 : sumNanos.sum() / (double) n / 1e6;
    }

    double maxMillis() {
        return max.get() / 1e6;
    }

    /** Upper bound of the bucket containing the given percentile, in milliseconds. */
    double percentileMillis(double percentile) {
        long n = total.sum();
        if (n == 0) {
            return 0;
        }
        long target = (long) Math.ceil(n * percentile / 100.0);
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts.get(i);
            if (seen >= target) {
                return (i + 1) * BUCKET_NANOS / 1e6;
            }
        }
        return maxMillis();
    }

    /** Clears all counts; used to start a new reporting interval. */
    void reset() {
        for (int i = 0; i < BUCKETS; i++) {
            counts.set(i, 0);
        }
        total.reset();
        sumNanos.reset();
        max.reset();
    }
}
