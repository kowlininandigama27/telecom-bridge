package com.telecombridge.diameter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Typed view of a Credit-Control-Request (RFC 4006 section 3.1).
 *
 * <pre>
 * &lt;CCR&gt; ::= &lt; Diameter Header: 272, REQ, PXY &gt;
 *           &lt; Session-Id &gt;
 *           { Origin-Host } { Origin-Realm } { Destination-Realm } { Auth-Application-Id }
 *           { Service-Context-Id } { CC-Request-Type } { CC-Request-Number }
 *           [ Destination-Host ] [ Origin-State-Id ] [ Event-Timestamp ]
 *          *[ Subscription-Id ] [ Service-Identifier ] [ Requested-Service-Unit ]
 * </pre>
 *
 * Optional numeric fields are {@code null} when absent.
 */
public record CreditControlRequest(
        String sessionId,
        String destinationRealm,
        String destinationHost,
        String serviceContextId,
        CcRequestType requestType,
        long requestNumber,
        SubscriptionIdType subscriptionIdType,
        String subscriptionIdData,
        Long serviceIdentifier,
        ServiceUnits requestedUnits,
        Instant eventTimestamp) {

    public CreditControlRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(destinationRealm, "destinationRealm");
        Objects.requireNonNull(serviceContextId, "serviceContextId");
        Objects.requireNonNull(requestType, "requestType");
    }

    public DiameterMessage toMessage(LocalPeer origin, int hopByHop, int endToEnd) {
        DiameterMessage.Builder b = DiameterMessage.request(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .proxiable()
                .hopByHopId(hopByHop)
                .endToEndId(endToEnd)
                // Session-Id SHOULD be the first AVP (RFC 6733 section 8.8).
                .avp(Avp.utf8(AvpCode.SESSION_ID, true, sessionId))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, true, origin.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, true, origin.originRealm()))
                .avp(Avp.utf8(AvpCode.DESTINATION_REALM, true, destinationRealm))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, true, ApplicationId.CREDIT_CONTROL))
                .avp(Avp.utf8(AvpCode.SERVICE_CONTEXT_ID, true, serviceContextId))
                .avp(Avp.enumerated(AvpCode.CC_REQUEST_TYPE, true, requestType.value()))
                .avp(Avp.unsigned32(AvpCode.CC_REQUEST_NUMBER, true, requestNumber));
        if (destinationHost != null) {
            b.avp(Avp.utf8(AvpCode.DESTINATION_HOST, true, destinationHost));
        }
        b.avp(Avp.unsigned32(AvpCode.ORIGIN_STATE_ID, true, origin.originStateId()));
        if (eventTimestamp != null) {
            b.avp(Avp.time(AvpCode.EVENT_TIMESTAMP, true, eventTimestamp));
        }
        if (subscriptionIdData != null) {
            b.avp(Avp.grouped(AvpCode.SUBSCRIPTION_ID, true,
                    Avp.enumerated(AvpCode.SUBSCRIPTION_ID_TYPE, true, subscriptionIdType.value()),
                    Avp.utf8(AvpCode.SUBSCRIPTION_ID_DATA, true, subscriptionIdData)));
        }
        if (serviceIdentifier != null) {
            b.avp(Avp.unsigned32(AvpCode.SERVICE_IDENTIFIER, true, serviceIdentifier));
        }
        if (requestedUnits != null && !requestedUnits.isEmpty()) {
            b.avp(requestedUnits.toAvp(AvpCode.REQUESTED_SERVICE_UNIT));
        }
        return b.build();
    }

    /**
     * Parses a CCR, failing with {@link MissingAvpException} when a mandatory (curly-brace) AVP of
     * the grammar is absent so the receiver can answer DIAMETER_MISSING_AVP.
     */
    public static CreditControlRequest fromMessage(DiameterMessage msg) {
        String sessionId = require(msg, AvpCode.SESSION_ID).asUtf8();
        require(msg, AvpCode.ORIGIN_HOST);
        require(msg, AvpCode.ORIGIN_REALM);
        String destinationRealm = require(msg, AvpCode.DESTINATION_REALM).asUtf8();
        require(msg, AvpCode.AUTH_APPLICATION_ID);
        String serviceContextId = require(msg, AvpCode.SERVICE_CONTEXT_ID).asUtf8();
        CcRequestType type = CcRequestType.fromValue(require(msg, AvpCode.CC_REQUEST_TYPE).asInteger32());
        long number = require(msg, AvpCode.CC_REQUEST_NUMBER).asUnsigned32();

        SubscriptionIdType subType = null;
        String subData = null;
        Avp subscription = msg.avp(AvpCode.SUBSCRIPTION_ID).orElse(null);
        if (subscription != null) {
            for (Avp child : subscription.asGrouped()) {
                if (child.code() == AvpCode.SUBSCRIPTION_ID_TYPE) {
                    subType = SubscriptionIdType.fromValue(child.asInteger32());
                } else if (child.code() == AvpCode.SUBSCRIPTION_ID_DATA) {
                    subData = child.asUtf8();
                }
            }
        }
        return new CreditControlRequest(
                sessionId,
                destinationRealm,
                msg.utf8(AvpCode.DESTINATION_HOST).orElse(null),
                serviceContextId,
                type,
                number,
                subType,
                subData,
                msg.unsigned32(AvpCode.SERVICE_IDENTIFIER).orElse(null),
                msg.avp(AvpCode.REQUESTED_SERVICE_UNIT).map(ServiceUnits::fromAvp).orElse(null),
                msg.avp(AvpCode.EVENT_TIMESTAMP).map(Avp::asTime).orElse(null));
    }

    private static Avp require(DiameterMessage msg, int code) {
        return msg.avp(code).orElseThrow(() -> new MissingAvpException(code));
    }

    /** Requested/Granted/Used-Service-Unit content (RFC 4006 sections 8.17-8.21). */
    public record ServiceUnits(Long time, Long totalOctets, Long serviceSpecificUnits) {

        public boolean isEmpty() {
            return time == null && totalOctets == null && serviceSpecificUnits == null;
        }

        public Avp toAvp(int groupedCode) {
            List<Avp> children = new ArrayList<>(3);
            if (time != null) {
                children.add(Avp.unsigned32(AvpCode.CC_TIME, true, time));
            }
            if (totalOctets != null) {
                children.add(Avp.unsigned64(AvpCode.CC_TOTAL_OCTETS, true, totalOctets));
            }
            if (serviceSpecificUnits != null) {
                children.add(Avp.unsigned64(AvpCode.CC_SERVICE_SPECIFIC_UNITS, true, serviceSpecificUnits));
            }
            return Avp.grouped(groupedCode, true, children);
        }

        public static ServiceUnits fromAvp(Avp grouped) {
            Long time = null;
            Long octets = null;
            Long units = null;
            for (Avp child : grouped.asGrouped()) {
                switch (child.code()) {
                    case AvpCode.CC_TIME -> time = child.asUnsigned32();
                    case AvpCode.CC_TOTAL_OCTETS -> octets = child.asUnsigned64();
                    case AvpCode.CC_SERVICE_SPECIFIC_UNITS -> units = child.asUnsigned64();
                    default -> { /* other unit types (Money, Input/Output-Octets) are ignored */ }
                }
            }
            return new ServiceUnits(time, octets, units);
        }
    }
}
