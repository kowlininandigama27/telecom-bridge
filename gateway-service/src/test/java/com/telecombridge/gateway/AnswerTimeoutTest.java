package com.telecombridge.gateway;

import com.telecombridge.gateway.diameter.DiameterPeerClient;
import com.telecombridge.gateway.diameter.PeerState;
import com.telecombridge.simulator.DiameterSimulator;
import com.telecombridge.simulator.SimulatorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** A server that answers slower than diameter.request-timeout yields 504 Gateway Timeout. */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "diameter.request-timeout=150ms")
class AnswerTimeoutTest {

    static DiameterSimulator slowSimulator;

    @Autowired
    WebTestClient web;

    @Autowired
    DiameterPeerClient client;

    @DynamicPropertySource
    static void diameterPeer(DynamicPropertyRegistry registry) throws InterruptedException {
        slowSimulator = DiameterSimulator.start(SimulatorConfig.defaults().withPort(0)
                .withDelay(Duration.ofMillis(400), Duration.ofMillis(500)));
        registry.add("diameter.peer-host", () -> "127.0.0.1");
        registry.add("diameter.peer-port", slowSimulator::port);
    }

    @AfterAll
    static void stop() {
        slowSimulator.close();
    }

    @Test
    void returns504WhenCcaIsLate() throws Exception {
        TestSupport.awaitState(client, PeerState.OPEN, Duration.ofSeconds(5));

        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"919876543210\"}")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.title").isEqualTo("Diameter answer timeout");

        assertThat(client.pendingRequests()).isZero();
        // the late CCA is discarded and the connection stays usable
        Thread.sleep(600);
        assertThat(client.state()).isEqualTo(PeerState.OPEN);
    }
}
