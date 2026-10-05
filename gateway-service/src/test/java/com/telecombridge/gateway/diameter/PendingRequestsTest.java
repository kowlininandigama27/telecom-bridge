package com.telecombridge.gateway.diameter;

import com.telecombridge.diameter.ApplicationId;
import com.telecombridge.diameter.CommandCode;
import com.telecombridge.diameter.DiameterMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingRequestsTest {

    private final PendingRequests pending = new PendingRequests();

    @AfterEach
    void tearDown() {
        pending.close();
    }

    @Test
    void completesFutureMatchingHopByHopId() throws Exception {
        CompletableFuture<DiameterMessage> first = pending.register(1, "CCR", Duration.ofSeconds(5));
        CompletableFuture<DiameterMessage> second = pending.register(2, "CCR", Duration.ofSeconds(5));

        assertThat(pending.complete(answer(2))).isTrue();

        assertThat(second.get(1, TimeUnit.SECONDS).hopByHopId()).isEqualTo(2);
        assertThat(first).isNotDone();
        assertThat(pending.size()).isEqualTo(1);
    }

    @Test
    void timesOutAndRemovesEntry() {
        CompletableFuture<DiameterMessage> future = pending.register(7, "CCR", Duration.ofMillis(50));

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DiameterTimeoutException.class);
        assertThat(pending.size()).isZero();
        // a late answer is discarded, not delivered twice
        assertThat(pending.complete(answer(7))).isFalse();
    }

    @Test
    void cancellationFreesSlot() {
        CompletableFuture<DiameterMessage> future = pending.register(9, "CCR", Duration.ofSeconds(5));

        future.cancel(true);

        assertThat(pending.size()).isZero();
    }

    @Test
    void failAllFailsEverythingInFlight() {
        CompletableFuture<DiameterMessage> a = pending.register(1, "CCR", Duration.ofSeconds(5));
        CompletableFuture<DiameterMessage> b = pending.register(2, "CCR", Duration.ofSeconds(5));

        assertThat(pending.failAll(() -> new PeerUnavailableException("down"))).isEqualTo(2);

        assertThat(a).isCompletedExceptionally();
        assertThat(b).isCompletedExceptionally();
        assertThat(pending.size()).isZero();
    }

    @Test
    void rejectsDuplicateHopByHopId() {
        pending.register(3, "CCR", Duration.ofSeconds(5));

        assertThatThrownBy(() -> pending.register(3, "CCR", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static DiameterMessage answer(int hopByHop) {
        DiameterMessage request = DiameterMessage.request(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .hopByHopId(hopByHop).build();
        return DiameterMessage.answerTo(request).build();
    }
}
