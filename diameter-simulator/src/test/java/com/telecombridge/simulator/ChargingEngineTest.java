package com.telecombridge.simulator;

import com.telecombridge.diameter.CcRequestType;
import com.telecombridge.diameter.CreditControlAnswer;
import com.telecombridge.diameter.CreditControlRequest;
import com.telecombridge.diameter.CreditControlRequest.ServiceUnits;
import com.telecombridge.diameter.ResultCode;
import com.telecombridge.diameter.SubscriptionIdType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChargingEngineTest {

    private final ChargingEngine engine = new ChargingEngine("ocs", "realm");

    @Test
    void grantsRequestedUnitsCappedAtQuota() {
        CreditControlAnswer cca = engine.charge(ccr("919876543210", CcRequestType.INITIAL_REQUEST,
                new ServiceUnits(10_000L, 1_000L, null)));

        assertThat(cca.resultCode()).isEqualTo(ResultCode.DIAMETER_SUCCESS);
        assertThat(cca.grantedUnits()).isEqualTo(new ServiceUnits(ChargingEngine.MAX_GRANTED_TIME_SECONDS, 1_000L, null));
        assertThat(cca.requestType()).isEqualTo(CcRequestType.INITIAL_REQUEST);
        assertThat(cca.requestNumber()).isEqualTo(3L);
        assertThat(cca.validityTime()).isEqualTo(ChargingEngine.VALIDITY_TIME_SECONDS);
    }

    @Test
    void grantsDefaultTimeWhenNothingRequested() {
        CreditControlAnswer cca = engine.charge(ccr("919876543210", CcRequestType.EVENT_REQUEST, null));

        assertThat(cca.grantedUnits().time()).isEqualTo(ChargingEngine.DEFAULT_GRANTED_TIME_SECONDS);
    }

    @Test
    void terminationGrantsNothing() {
        CreditControlAnswer cca = engine.charge(ccr("919876543210", CcRequestType.TERMINATION_REQUEST, null));

        assertThat(cca.resultCode()).isEqualTo(ResultCode.DIAMETER_SUCCESS);
        assertThat(cca.grantedUnits()).isNull();
    }

    @Test
    void outOfCreditAndUnknownSubscribers() {
        assertThat(engine.charge(ccr("919876540000", CcRequestType.INITIAL_REQUEST, null)).resultCode())
                .isEqualTo(ResultCode.DIAMETER_CREDIT_LIMIT_REACHED);
        assertThat(engine.charge(ccr("919876549999", CcRequestType.INITIAL_REQUEST, null)).resultCode())
                .isEqualTo(ResultCode.DIAMETER_USER_UNKNOWN);
    }

    private static CreditControlRequest ccr(String msisdn, CcRequestType type, ServiceUnits units) {
        return new CreditControlRequest("s;1;1", "realm", null, "32251@3gpp.org", type, 3,
                SubscriptionIdType.END_USER_E164, msisdn, null, units, null);
    }
}
