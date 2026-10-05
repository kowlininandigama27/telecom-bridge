package com.telecombridge.gateway;

import com.telecombridge.gateway.diameter.DiameterPeerClient;
import com.telecombridge.gateway.diameter.PeerState;
import com.telecombridge.simulator.DiameterSimulator;
import com.telecombridge.simulator.SimulatorConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error handling: Diameter server down → 503 Service Unavailable with Retry-After, and recovery
 * through automatic reconnect + CER/CEA once the server comes back.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "diameter.reconnect-min-delay=100ms",
        "diameter.reconnect-max-delay=300ms"
})
class PeerFailureTest {

    static int diameterPort;

    @Autowired
    WebTestClient web;

    @Autowired
    DiameterPeerClient client;

    @DynamicPropertySource
    static void diameterPeer(DynamicPropertyRegistry registry) throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            diameterPort = socket.getLocalPort();
        }
        registry.add("diameter.peer-host", () -> "127.0.0.1");
        registry.add("diameter.peer-port", () -> diameterPort);
    }

    @Test
    void returns503WhileServerIsDownAndRecoversWhenItComesBack() throws Exception {
        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"919876543210\"}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody().jsonPath("$.title").isEqualTo("Diameter peer unavailable");

        web.get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(503);

        try (DiameterSimulator simulator = DiameterSimulator.start(SimulatorConfig.defaults().withPort(diameterPort))) {
            TestSupport.awaitState(client, PeerState.OPEN, Duration.ofSeconds(5));

            web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"msisdn\":\"919876543210\"}")
                    .exchange()
                    .expectStatus().isOk();
            web.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
            assertThat(simulator.ccrCount()).isEqualTo(1);
        }

        // server stopped again: connection loss is detected and requests fail fast
        TestSupport.awaitState(client, PeerState.CLOSED, Duration.ofSeconds(5));
        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"919876543210\"}")
                .exchange()
                .expectStatus().isEqualTo(503);
    }
}
