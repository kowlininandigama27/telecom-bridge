package com.telecombridge.simulator;

import com.telecombridge.diameter.CcRequestType;
import com.telecombridge.diameter.CreditControlAnswer;
import com.telecombridge.diameter.CreditControlRequest;
import com.telecombridge.diameter.CreditControlRequest.ServiceUnits;
import com.telecombridge.diameter.ResultCode;

/**
 * Toy Online Charging System rules, deterministic so that tests and demos can trigger each
 * outcome from the MSISDN alone:
 * <ul>
 *   <li>MSISDN ending in {@code 0000} → 4012 DIAMETER_CREDIT_LIMIT_REACHED</li>
 *   <li>MSISDN ending in {@code 9999} → 5030 DIAMETER_USER_UNKNOWN</li>
 *   <li>otherwise → 2001, granting the requested units capped at the per-request quota</li>
 * </ul>
 */
public final class ChargingEngine {

    static final long MAX_GRANTED_TIME_SECONDS = 3_600;
    static final long MAX_GRANTED_OCTETS = 100L * 1024 * 1024;
    static final long DEFAULT_GRANTED_TIME_SECONDS = 300;
    static final long VALIDITY_TIME_SECONDS = 3_600;

    private final String originHost;
    private final String originRealm;

    public ChargingEngine(String originHost, String originRealm) {
        this.originHost = originHost;
        this.originRealm = originRealm;
    }

    public CreditControlAnswer charge(CreditControlRequest ccr) {
        String msisdn = ccr.subscriptionIdData() == null ? "" : ccr.subscriptionIdData();
        if (msisdn.endsWith("9999")) {
            return answer(ccr, ResultCode.DIAMETER_USER_UNKNOWN, null, null, "Subscriber not provisioned");
        }
        if (msisdn.endsWith("0000") && ccr.requestType() != CcRequestType.TERMINATION_REQUEST) {
            return answer(ccr, ResultCode.DIAMETER_CREDIT_LIMIT_REACHED, null, null, "Insufficient balance");
        }
        if (ccr.requestType() == CcRequestType.TERMINATION_REQUEST) {
            return answer(ccr, ResultCode.DIAMETER_SUCCESS, null, null, null);
        }
        return answer(ccr, ResultCode.DIAMETER_SUCCESS, grant(ccr.requestedUnits()), VALIDITY_TIME_SECONDS, null);
    }

    private static ServiceUnits grant(ServiceUnits requested) {
        if (requested == null || requested.isEmpty()) {
            return new ServiceUnits(DEFAULT_GRANTED_TIME_SECONDS, null, null);
        }
        return new ServiceUnits(
                requested.time() == null ? null : Math.min(requested.time(), MAX_GRANTED_TIME_SECONDS),
                requested.totalOctets() == null ? null : Math.min(requested.totalOctets(), MAX_GRANTED_OCTETS),
                requested.serviceSpecificUnits());
    }

    private CreditControlAnswer answer(CreditControlRequest ccr, long resultCode, ServiceUnits granted,
                                       Long validity, String errorMessage) {
        return new CreditControlAnswer(ccr.sessionId(), resultCode, originHost, originRealm, ccr.requestType(),
                ccr.requestNumber(), granted, validity, errorMessage);
    }
}
