package com.telecombridge.gateway.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Diameter client settings bound from {@code diameter.*}.
 *
 * @param peerHost           Diameter server (OCS) host
 * @param peerPort           Diameter server port, 3868 by default
 * @param originHost         our Origin-Host
 * @param originRealm        our Origin-Realm
 * @param destinationRealm   Destination-Realm put in every CCR
 * @param destinationHost    optional Destination-Host for CCRs
 * @param serviceContextId   default Service-Context-Id (32251@3gpp.org = 3GPP Ro PS charging)
 * @param connectTimeout     TCP connect timeout
 * @param requestTimeout     max time to wait for an answer before the REST call gets 504
 * @param watchdogInterval   Tw from RFC 3539: idle time before a DWR is sent
 * @param reconnectMinDelay  initial reconnect back-off
 * @param reconnectMaxDelay  cap of the exponential reconnect back-off
 * @param maxPendingRequests in-flight request limit before shedding load with 503
 * @param ioThreads          Netty event-loop threads for the Diameter connection
 */
@Validated
@ConfigurationProperties(prefix = "diameter")
public record DiameterClientProperties(
        @NotBlank @DefaultValue("localhost") String peerHost,
        @Min(1) @Max(65535) @DefaultValue("3868") int peerPort,
        @NotBlank @DefaultValue("gw.telecom-bridge.local") String originHost,
        @NotBlank @DefaultValue("telecom-bridge.local") String originRealm,
        @NotBlank @DefaultValue("telecom-bridge.local") String destinationRealm,
        String destinationHost,
        @NotBlank @DefaultValue("32251@3gpp.org") String serviceContextId,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("1s") Duration requestTimeout,
        @DefaultValue("30s") Duration watchdogInterval,
        @DefaultValue("500ms") Duration reconnectMinDelay,
        @DefaultValue("30s") Duration reconnectMaxDelay,
        @Min(1) @DefaultValue("10000") int maxPendingRequests,
        @Min(1) @DefaultValue("1") int ioThreads) {
}
