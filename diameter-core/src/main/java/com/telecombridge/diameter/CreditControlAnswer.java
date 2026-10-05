package com.telecombridge.diameter;

import com.telecombridge.diameter.CreditControlRequest.ServiceUnits;

/**
 * Typed view of a Credit-Control-Answer (RFC 4006 section 3.2).
 *
 * <pre>
 * &lt;CCA&gt; ::= &lt; Diameter Header: 272, PXY &gt;
 *           &lt; Session-Id &gt;
 *           { Result-Code } { Origin-Host } { Origin-Realm } { Auth-Application-Id }
 *           { CC-Request-Type } { CC-Request-Number }
 *           [ Granted-Service-Unit ] [ Validity-Time ] [ Error-Message ]
 * </pre>
 *
 * Fields may be {@code null} when the peer answered with a protocol error (E bit) and omitted them.
 */
public record CreditControlAnswer(
        String sessionId,
        long resultCode,
        String originHost,
        String originRealm,
        CcRequestType requestType,
        Long requestNumber,
        ServiceUnits grantedUnits,
        Long validityTime,
        String errorMessage) {

    public DiameterMessage toMessage(DiameterMessage request) {
        DiameterMessage.Builder b = DiameterMessage.answerTo(request)
                .avp(Avp.utf8(AvpCode.SESSION_ID, true, sessionId))
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, true, resultCode))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, true, originHost))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, true, originRealm))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, true, ApplicationId.CREDIT_CONTROL))
                .avp(Avp.enumerated(AvpCode.CC_REQUEST_TYPE, true, requestType.value()))
                .avp(Avp.unsigned32(AvpCode.CC_REQUEST_NUMBER, true, requestNumber));
        if (grantedUnits != null && !grantedUnits.isEmpty()) {
            b.avp(grantedUnits.toAvp(AvpCode.GRANTED_SERVICE_UNIT));
        }
        if (validityTime != null) {
            b.avp(Avp.unsigned32(AvpCode.VALIDITY_TIME, true, validityTime));
        }
        if (errorMessage != null) {
            b.avp(Avp.utf8(AvpCode.ERROR_MESSAGE, false, errorMessage));
        }
        return b.build();
    }

    public static CreditControlAnswer fromMessage(DiameterMessage msg) {
        return new CreditControlAnswer(
                msg.sessionId().orElse(null),
                msg.resultCode().orElseThrow(() -> new MissingAvpException(AvpCode.RESULT_CODE)),
                msg.utf8(AvpCode.ORIGIN_HOST).orElse(null),
                msg.utf8(AvpCode.ORIGIN_REALM).orElse(null),
                msg.avp(AvpCode.CC_REQUEST_TYPE).map(a -> CcRequestType.fromValue(a.asInteger32())).orElse(null),
                msg.unsigned32(AvpCode.CC_REQUEST_NUMBER).orElse(null),
                msg.avp(AvpCode.GRANTED_SERVICE_UNIT).map(ServiceUnits::fromAvp).orElse(null),
                msg.unsigned32(AvpCode.VALIDITY_TIME).orElse(null),
                msg.utf8(AvpCode.ERROR_MESSAGE).orElse(null));
    }
}
