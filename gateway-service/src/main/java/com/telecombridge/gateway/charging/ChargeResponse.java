package com.telecombridge.gateway.charging;

import com.fasterxml.jackson.annotation.JsonInclude;

/** JSON representation of the Credit-Control-Answer returned to the REST caller. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeResponse(
        String sessionId,
        long resultCode,
        String resultCodeName,
        boolean success,
        ChargeRequest.RequestType requestType,
        Long requestNumber,
        ChargeRequest.Units grantedUnits,
        Long validityTime,
        String errorMessage,
        String originHost,
        String originRealm,
        String hopByHopId,
        String endToEndId,
        double diameterLatencyMs) {
}
