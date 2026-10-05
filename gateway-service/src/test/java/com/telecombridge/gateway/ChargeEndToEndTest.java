package com.telecombridge.gateway;

import com.telecombridge.gateway.charging.ChargeResponse;
import com.telecombridge.gateway.diameter.DiameterPeerClient;
import com.telecombridge.gateway.diameter.PeerState;
import com.telecombridge.simulator.DiameterSimulator;
import com.telecombridge.simulator.SimulatorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Full stack: WebFlux → Diameter client → TCP → embedded simulator, with the real 50-100 ms delay. */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChargeEndToEndTest {

    static DiameterSimulator simulator;

    @Autowired
    WebTestClient web;

    @Autowired
    DiameterPeerClient client;

    @DynamicPropertySource
    static void diameterPeer(DynamicPropertyRegistry registry) throws InterruptedException {
        simulator = DiameterSimulator.start(SimulatorConfig.defaults().withPort(0));
        registry.add("diameter.peer-host", () -> "127.0.0.1");
        registry.add("diameter.peer-port", simulator::port);
    }

    @AfterAll
    static void stopSimulator() {
        simulator.close();
    }

    @BeforeEach
    void peerIsOpen() throws InterruptedException {
        TestSupport.awaitState(client, PeerState.OPEN, Duration.ofSeconds(5));
    }

    @Test
    void initialRequestReturnsCcaSuccess() {
        ChargeResponse response = web.post().uri("/api/v1/charge")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"msisdn":"919876543210","requestType":"INITIAL","requestedUnits":{"time":60,"totalOctets":1048576}}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChargeResponse.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.resultCode()).isEqualTo(2001);
        assertThat(response.success()).isTrue();
        assertThat(response.sessionId()).startsWith("gw.telecom-bridge.local;");
        assertThat(response.grantedUnits().time()).isEqualTo(60);
        assertThat(response.grantedUnits().totalOctets()).isEqualTo(1048576);
        assertThat(response.originHost()).isEqualTo("ocs.telecom-bridge.local");
        assertThat(response.diameterLatencyMs()).isBetween(45.0, 1000.0);
    }

    @Test
    void sessionCanBeUpdatedAndTerminated() {
        ChargeResponse initial = charge("""
                {"msisdn":"919876543210"}""", 200);
        ChargeResponse update = charge("""
                {"msisdn":"919876543210","requestType":"UPDATE","requestNumber":1,"sessionId":"%s"}"""
                .formatted(initial.sessionId()), 200);
        ChargeResponse termination = charge("""
                {"msisdn":"919876543210","requestType":"TERMINATION","requestNumber":2,"sessionId":"%s"}"""
                .formatted(initial.sessionId()), 200);

        assertThat(update.sessionId()).isEqualTo(initial.sessionId());
        assertThat(update.requestNumber()).isEqualTo(1);
        assertThat(termination.grantedUnits()).isNull();
    }

    @Test
    void ocsRejectionsMapToHttpStatus() {
        assertThat(charge("""
                {"msisdn":"919876540000"}""", 402).resultCodeName()).isEqualTo("DIAMETER_CREDIT_LIMIT_REACHED");
        assertThat(charge("""
                {"msisdn":"919876549999"}""", 404).resultCodeName()).isEqualTo("DIAMETER_USER_UNKNOWN");
    }

    @Test
    void invalidPayloadIsRejectedWith400() {
        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"abc\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(d -> assertThat(d.toString()).contains("msisdn"));

        web.post().uri("/api/v1/charge").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"msisdn\":\"919876543210\",\"requestType\":\"UPDATE\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void concurrentRequestsAreCorrelatedByHopByHopId() {
        List<ChargeResponse> responses = Flux.range(0, 200)
                .flatMap(i -> web.post().uri("/api/v1/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"msisdn\":\"9198765%05d\"}".formatted(i + 1))
                        .exchange()
                        .returnResult(ChargeResponse.class)
                        .getResponseBody(), 200)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(responses).hasSize(200);
        assertThat(responses).allMatch(r -> r.resultCode() == 2001);
        assertThat(responses.stream().map(ChargeResponse::hopByHopId).distinct()).hasSize(200);
        assertThat(responses.stream().map(ChargeResponse::sessionId).distinct()).hasSize(200);
        assertThat(client.pendingRequests()).isZero();
    }

    private ChargeResponse charge(String json, int expectedStatus) {
        return web.post().uri("/api/v1/charge")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(ChargeResponse.class)
                .returnResult().getResponseBody();
    }
}
