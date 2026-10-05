package com.telecombridge.diameter;

/**
 * Builders for the RFC 6733 base-protocol commands, laid out in the order of the CCF grammar
 * of each command. Every base-protocol message uses Application-Id 0 and never sets the P bit.
 */
public final class BaseMessages {

    /** Disconnect-Cause REBOOTING (RFC 6733 section 5.4.3). */
    public static final int DISCONNECT_CAUSE_REBOOTING = 0;

    private BaseMessages() {
    }

    /**
     * <pre>
     * &lt;CER&gt; ::= &lt; Diameter Header: 257, REQ &gt;
     *           { Origin-Host } { Origin-Realm } 1* { Host-IP-Address } { Vendor-Id } { Product-Name }
     *           [ Origin-State-Id ] * [ Auth-Application-Id ] [ Firmware-Revision ]
     * </pre>
     */
    public static DiameterMessage cer(LocalPeer peer, int hopByHop, int endToEnd, int... authApplicationIds) {
        DiameterMessage.Builder b = DiameterMessage.request(CommandCode.CAPABILITIES_EXCHANGE, ApplicationId.COMMON)
                .hopByHopId(hopByHop)
                .endToEndId(endToEnd)
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, true, peer.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, true, peer.originRealm()))
                .avp(Avp.address(AvpCode.HOST_IP_ADDRESS, true, peer.hostIpAddress()))
                .avp(Avp.unsigned32(AvpCode.VENDOR_ID, true, peer.vendorId()))
                // Product-Name and Firmware-Revision MUST NOT have the M bit (RFC 6733 table 5.3).
                .avp(Avp.utf8(AvpCode.PRODUCT_NAME, false, peer.productName()))
                .avp(Avp.unsigned32(AvpCode.ORIGIN_STATE_ID, true, peer.originStateId()));
        for (int appId : authApplicationIds) {
            b.avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, true, Integer.toUnsignedLong(appId)));
        }
        return b.avp(Avp.unsigned32(AvpCode.FIRMWARE_REVISION, false, 1)).build();
    }

    /**
     * <pre>
     * &lt;CEA&gt; ::= &lt; Diameter Header: 257 &gt;
     *           { Result-Code } { Origin-Host } { Origin-Realm } 1* { Host-IP-Address } { Vendor-Id }
     *           { Product-Name } [ Origin-State-Id ] [ Error-Message ] * [ Auth-Application-Id ]
     * </pre>
     */
    public static DiameterMessage cea(DiameterMessage cer, LocalPeer peer, long resultCode, String errorMessage,
                                      int... authApplicationIds) {
        DiameterMessage.Builder b = DiameterMessage.answerTo(cer)
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, true, resultCode))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, true, peer.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, true, peer.originRealm()))
                .avp(Avp.address(AvpCode.HOST_IP_ADDRESS, true, peer.hostIpAddress()))
                .avp(Avp.unsigned32(AvpCode.VENDOR_ID, true, peer.vendorId()))
                .avp(Avp.utf8(AvpCode.PRODUCT_NAME, false, peer.productName()))
                .avp(Avp.unsigned32(AvpCode.ORIGIN_STATE_ID, true, peer.originStateId()));
        if (errorMessage != null) {
            b.avp(Avp.utf8(AvpCode.ERROR_MESSAGE, false, errorMessage));
        }
        for (int appId : authApplicationIds) {
            b.avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, true, Integer.toUnsignedLong(appId)));
        }
        return b.build();
    }

    /** {@code <DWR> ::= < Diameter Header: 280, REQ > { Origin-Host } { Origin-Realm } [ Origin-State-Id ]} */
    public static DiameterMessage dwr(LocalPeer peer, int hopByHop, int endToEnd) {
        return DiameterMessage.request(CommandCode.DEVICE_WATCHDOG, ApplicationId.COMMON)
                .hopByHopId(hopByHop)
                .endToEndId(endToEnd)
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, true, peer.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, true, peer.originRealm()))
                .avp(Avp.unsigned32(AvpCode.ORIGIN_STATE_ID, true, peer.originStateId()))
                .build();
    }

    /** {@code <DWA> ::= < Diameter Header: 280 > { Result-Code } { Origin-Host } { Origin-Realm } [ Origin-State-Id ]} */
    public static DiameterMessage dwa(DiameterMessage dwr, LocalPeer peer) {
        return DiameterMessage.answerTo(dwr)
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, true, ResultCode.DIAMETER_SUCCESS))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, true, peer.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, true, peer.originRealm()))
                .avp(Avp.unsigned32(AvpCode.ORIGIN_STATE_ID, true, peer.originStateId()))
                .build();
    }

    /** {@code <DPR> ::= < Diameter Header: 282, REQ > { Origin-Host } { Origin-Realm } { Disconnect-Cause }} */
    public static DiameterMessage dpr(LocalPeer peer, int hopByHop, int endToEnd, int disconnectCause) {
        return DiameterMessage.request(CommandCode.DISCONNECT_PEER, ApplicationId.COMMON)
                .hopByHopId(hopByHop)
                .endToEndId(endToEnd)
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, true, peer.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, true, peer.originRealm()))
                .avp(Avp.enumerated(AvpCode.DISCONNECT_CAUSE, true, disconnectCause))
                .build();
    }

    /** {@code <DPA> ::= < Diameter Header: 282 > { Result-Code } { Origin-Host } { Origin-Realm }} */
    public static DiameterMessage dpa(DiameterMessage dpr, LocalPeer peer) {
        return DiameterMessage.answerTo(dpr)
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, true, ResultCode.DIAMETER_SUCCESS))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, true, peer.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, true, peer.originRealm()))
                .build();
    }

    /**
     * Generic answer used for protocol errors (3xxx, E bit set) or permanent failures to a request
     * of any command.
     */
    public static DiameterMessage errorAnswer(DiameterMessage request, LocalPeer peer, long resultCode, String errorMessage) {
        DiameterMessage.Builder b = DiameterMessage.answerTo(request);
        if (resultCode >= 3000 && resultCode < 4000) {
            b.error();
        }
        request.sessionId().ifPresent(s -> b.avp(Avp.utf8(AvpCode.SESSION_ID, true, s)));
        b.avp(Avp.unsigned32(AvpCode.RESULT_CODE, true, resultCode))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, true, peer.originHost()))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, true, peer.originRealm()));
        if (errorMessage != null) {
            b.avp(Avp.utf8(AvpCode.ERROR_MESSAGE, false, errorMessage));
        }
        return b.build();
    }
}
