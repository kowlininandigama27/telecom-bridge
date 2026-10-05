package com.telecombridge.simulator;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs actions at absolute {@link System#nanoTime()} deadlines with sub-100 µs accuracy.
 * <p>
 * OS timers overshoot: on macOS a niced process typically wakes 1-2 ms late because of timer
 * coalescing, so a CCA scheduled for "75 ms" would actually leave after ~76.3 ms and the simulator
 * would no longer honour its 50-100 ms contract. This scheduler parks until {@link #SPIN_NANOS}
 * before the earliest deadline and busy-waits the remainder on one dedicated thread. Actions must be
 * cheap and non-blocking (here: {@code channel.writeAndFlush}, which just enqueues to the event loop).
 */
final class PreciseDelayScheduler implements AutoCloseable {

    static final long SPIN_NANOS = 2_000_000;

    private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>(1024);
    private final AtomicLong sequence = new AtomicLong();
    private final Thread thread;
    private volatile boolean running = true;

    PreciseDelayScheduler() {
        this.thread = new Thread(this::loop, "sim-precise-timer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    void schedule(long deadlineNanos, Runnable action) {
        queue.add(new Task(deadlineNanos, sequence.getAndIncrement(), action));
        LockSupport.unpark(thread);
    }

    private void loop() {
        while (running) {
            Task head = queue.peek();
            if (head == null) {
                LockSupport.park(this);
                continue;
            }
            long remaining = head.deadlineNanos - System.nanoTime();
            if (remaining > SPIN_NANOS) {
                LockSupport.parkNanos(this, remaining - SPIN_NANOS);
            } else if (remaining > 0) {
                Thread.onSpinWait();
            } else {
                Task due = queue.poll();
                if (due != null) {
                    due.action.run();
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
    }

    private record Task(long deadlineNanos, long seq, Runnable action) implements Comparable<Task> {
        @Override
        public int compareTo(Task other) {
            int byDeadline = Long.compare(deadlineNanos, other.deadlineNanos);
            return byDeadline != 0 ? byDeadline : Long.compare(seq, other.seq);
        }
    }
}
