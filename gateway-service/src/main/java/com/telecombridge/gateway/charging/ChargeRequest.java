package com.telecombridge.gateway.charging;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * JSON body of {@code POST /api/v1/charge}.
 *
 * <pre>
 * {
 *   "msisdn": "919876543210",
 *   "requestType": "INITIAL",
 *   "sessionId": null,
 *   "requestNumber": 0,
 *   "serviceContextId": "32251@3gpp.org",
 *   "serviceIdentifier": 1,
 *   "requestedUnits": { "time": 60, "totalOctets": 1048576 }
 * }
 * </pre>
 * Only {@code msisdn} is required. {@code sessionId} is generated for INITIAL/EVENT requests and must
 * be supplied for UPDATE/TERMINATION so they join the existing Diameter session.
 */
public record ChargeRequest(
        @NotBlank @Pattern(regexp = "\\d{6,15}", message = "must be an E.164 number of 6-15 digits") String msisdn,
        RequestType requestType,
        @Size(max = 256) String sessionId,
        @PositiveOrZero @Max(0xFFFF_FFFFL) Long requestNumber,
        @Size(max = 128) String serviceContextId,
        @PositiveOrZero @Max(0xFFFF_FFFFL) Long serviceIdentifier,
        @Valid Units requestedUnits) {

    public enum RequestType { INITIAL, UPDATE, TERMINATION, EVENT }

    /** Requested-Service-Unit / Granted-Service-Unit content. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Units(
            @PositiveOrZero @Max(0xFFFF_FFFFL) Long time,
            @PositiveOrZero Long totalOctets,
            @PositiveOrZero Long serviceSpecificUnits) {
    }
}
