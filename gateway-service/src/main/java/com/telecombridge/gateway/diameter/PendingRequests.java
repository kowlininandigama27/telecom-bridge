package com.telecombridge.gateway.diameter;

import com.telecombridge.diameter.DiameterMessage;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Correlates outgoing requests with incoming answers by Hop-by-Hop Identifier.
 * <p>
 * Every entry leaves the map exactly once, through whichever happens first: the answer arrives,
 * the timeout fires, the caller cancels, or the connection drops. {@link ConcurrentHashMap#remove}
 * decides that race atomically, so a future is never completed twice and nothing leaks.
 * Timeouts use a {@link HashedWheelTimer}: O(1) to schedule and cancel, and one thread for any
 * number of in-flight requests.
 */
public final class PendingRequests implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PendingRequests.class);

    private final ConcurrentHashMap<Integer, Pending> inFlight = new ConcurrentHashMap<>(1024);
    private final HashedWheelTimer timer = new HashedWheelTimer(
            new DefaultThreadFactory("diameter-timeouts", true), 10, TimeUnit.MILLISECONDS, 512);

    /**
     * Registers a request that is about to be written.
     *
     * @return a future completed with the answer, or exceptionally with {@link DiameterTimeoutException}
     * @throws IllegalStateException if the Hop-by-Hop Identifier is already in flight
     */
    public CompletableFuture<DiameterMessage> register(int hopByHopId, String description, Duration timeout) {
        Pending pending = new Pending(description);
        if (inFlight.putIfAbsent(hopByHopId, pending) != null) {
            throw new IllegalStateException("Hop-by-Hop Identifier 0x" + Integer.toHexString(hopByHopId) + " already in flight");
        }
        pending.timeout = timer.newTimeout(t -> {
            if (inFlight.remove(hopByHopId, pending)) {
                pending.future.completeExceptionally(new DiameterTimeoutException(
                        "No answer to " + description + " within " + timeout.toMillis() + " ms"));
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        // If the caller gives up (e.g. HTTP client disconnected) free the slot immediately.
        pending.future.whenComplete((answer, error) -> {
            if (error instanceof CancellationException && inFlight.remove(hopByHopId, pending)) {
                pending.cancelTimer();
            }
        });
        return pending.future;
    }

    /** Completes the request matching {@code answer}'s Hop-by-Hop Identifier. */
    public boolean complete(DiameterMessage answer) {
        Pending pending = inFlight.remove(answer.hopByHopId());
        if (pending == null) {
            log.warn("Discarding answer with unknown or expired Hop-by-Hop Identifier: {}", answer);
            return false;
        }
        pending.cancelTimer();
        pending.future.complete(answer);
        return true;
    }

    /** Fails one request, e.g. because its write to the socket failed. */
    public void fail(int hopByHopId, Throwable cause) {
        Pending pending = inFlight.remove(hopByHopId);
        if (pending != null) {
            pending.cancelTimer();
            pending.future.completeExceptionally(cause);
        }
    }

    /** Fails everything in flight, used when the transport connection is lost. */
    public int failAll(Supplier<? extends Throwable> cause) {
        int failed = 0;
        for (Integer id : inFlight.keySet()) {
            Pending pending = inFlight.remove(id);
            if (pending != null) {
                pending.cancelTimer();
                pending.future.completeExceptionally(cause.get());
                failed++;
            }
        }
        return failed;
    }

    public int size() {
        return inFlight.size();
    }

    @Override
    public void close() {
        failAll(() -> new PeerUnavailableException("Diameter client stopped"));
        timer.stop();
    }

    private static final class Pending {
        final CompletableFuture<DiameterMessage> future = new CompletableFuture<>();
        final String description;
        volatile Timeout timeout;

        Pending(String description) {
            this.description = description;
        }

        void cancelTimer() {
            Timeout t = timeout;
            if (t != null) {
                t.cancel();
            }
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
