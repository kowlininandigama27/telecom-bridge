package com.telecombridge.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PreciseDelaySchedulerTest {

    @Test
    void runsActionsInDeadlineOrderCloseToDeadline() throws Exception {
        try (PreciseDelayScheduler scheduler = new PreciseDelayScheduler()) {
            List<Integer> order = new CopyOnWriteArrayList<>();
            List<Long> lateness = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(3);
            long now = System.nanoTime();
            long[] deadlines = {now + 30_000_000, now + 10_000_000, now + 20_000_000};
            for (int i = 0; i < deadlines.length; i++) {
                int id = i;
                long deadline = deadlines[i];
                scheduler.schedule(deadline, () -> {
                    lateness.add(System.nanoTime() - deadline);
                    order.add(id);
                    done.countDown();
                });
            }

            assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly(1, 2, 0);
            assertThat(lateness).allMatch(late -> late >= 0 && late < TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
