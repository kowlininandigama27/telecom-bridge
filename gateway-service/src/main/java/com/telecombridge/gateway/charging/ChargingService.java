package com.telecombridge.gateway.charging;

import com.telecombridge.diameter.CcRequestType;
import com.telecombridge.diameter.CreditControlAnswer;
import com.telecombridge.diameter.CreditControlRequest;
import com.telecombridge.diameter.CreditControlRequest.ServiceUnits;
import com.telecombridge.diameter.DiameterMessage;
import com.telecombridge.diameter.ResultCode;
import com.telecombridge.diameter.SessionIdGenerator;
import com.telecombridge.diameter.SubscriptionIdType;
import com.telecombridge.gateway.config.DiameterClientProperties;
import com.telecombridge.gateway.diameter.DiameterPeerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/** Translates REST charge requests into Diameter CCRs and CCAs back into JSON. */
@Service
public class ChargingService {

    private static final Logger log = LoggerFactory.getLogger(ChargingService.class);

    private final DiameterPeerClient client;
    private final DiameterClientProperties props;
    private final SessionIdGenerator sessionIds;

    public ChargingService(DiameterPeerClient client, DiameterClientProperties props) {
        this.client = client;
        this.props = props;
        this.sessionIds = new SessionIdGenerator(props.originHost());
    }

    public CompletableFuture<ChargeResponse> charge(ChargeRequest request) {
        CreditControlRequest ccr = toCcr(request);
        long start = System.nanoTime();
        return client.sendCreditControl(ccr).thenApply(answer -> {
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
            ChargeResponse response = toResponse(answer, latencyMs);
            if (log.isDebugEnabled()) {
                log.debug("CCR {} msisdn={} session={} -> {} ({}) in {} ms", ccr.requestType(), request.msisdn(),
                        ccr.sessionId(), response.resultCode(), response.resultCodeName(), String.format("%.1f", latencyMs));
            }
            return response;
        });
    }

    CreditControlRequest toCcr(ChargeRequest request) {
        ChargeRequest.RequestType type = request.requestType() == null ? ChargeRequest.RequestType.INITIAL : request.requestType();
        boolean continuesSession = type == ChargeRequest.RequestType.UPDATE || type == ChargeRequest.RequestType.TERMINATION;
        if (continuesSession && (request.sessionId() == null || request.sessionId().isBlank())) {
            throw new InvalidChargeRequestException("sessionId is required for " + type + " requests");
        }
        String sessionId = request.sessionId() == null || request.sessionId().isBlank() ? sessionIds.next() : request.sessionId();
        ChargeRequest.Units units = request.requestedUnits();
        return new CreditControlRequest(
                sessionId,
                props.destinationRealm(),
                blankToNull(props.destinationHost()),
                request.serviceContextId() == null ? props.serviceContextId() : request.serviceContextId(),
                switch (type) {
                    case INITIAL -> CcRequestType.INITIAL_REQUEST;
                    case UPDATE -> CcRequestType.UPDATE_REQUEST;
                    case TERMINATION -> CcRequestType.TERMINATION_REQUEST;
                    case EVENT -> CcRequestType.EVENT_REQUEST;
                },
                request.requestNumber() == null ? 0 : request.requestNumber(),
                SubscriptionIdType.END_USER_E164,
                request.msisdn(),
                request.serviceIdentifier(),
                units == null ? null : new ServiceUnits(units.time(), units.totalOctets(), units.serviceSpecificUnits()),
                Instant.now());
    }

    static ChargeResponse toResponse(DiameterMessage answer, double latencyMs) {
        CreditControlAnswer cca = CreditControlAnswer.fromMessage(answer);
        ServiceUnits granted = cca.grantedUnits();
        return new ChargeResponse(
                cca.sessionId(),
                cca.resultCode(),
                ResultCode.describe(cca.resultCode()),
                ResultCode.isSuccess(cca.resultCode()),
                cca.requestType() == null ? null : switch (cca.requestType()) {
                    case INITIAL_REQUEST -> ChargeRequest.RequestType.INITIAL;
                    case UPDATE_REQUEST -> ChargeRequest.RequestType.UPDATE;
                    case TERMINATION_REQUEST -> ChargeRequest.RequestType.TERMINATION;
                    case EVENT_REQUEST -> ChargeRequest.RequestType.EVENT;
                },
                cca.requestNumber(),
                granted == null ? null : new ChargeRequest.Units(granted.time(), granted.totalOctets(), granted.serviceSpecificUnits()),
                cca.validityTime(),
                cca.errorMessage(),
                cca.originHost(),
                cca.originRealm(),
                "0x" + Integer.toHexString(answer.hopByHopId()),
                "0x" + Integer.toHexString(answer.endToEndId()),
                Math.round(latencyMs * 100) / 100.0);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
