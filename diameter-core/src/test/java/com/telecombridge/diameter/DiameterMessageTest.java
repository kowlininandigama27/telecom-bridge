package com.telecombridge.diameter;

import com.telecombridge.diameter.CreditControlRequest.ServiceUnits;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiameterMessageTest {

    static final LocalPeer CLIENT = new LocalPeer("gw.telecom-bridge.local", "telecom-bridge.local",
            InetAddress.getLoopbackAddress(), 0, "Telecom-Bridge", 1700000000L);

    @Test
    void cerHeaderMatchesReferenceTrace() {
        DiameterMessage cer = BaseMessages.cer(CLIENT, 0x11223344, 0x55667788, ApplicationId.CREDIT_CONTROL);
        byte[] wire = encode(cer);

        assertThat(wire[0]).isEqualTo((byte) 1);                         // version
        assertThat(medium(wire, 1)).isEqualTo(wire.length);              // Message Length = whole message
        assertThat(wire[4] & 0xFF).isEqualTo(0x80);                      // R bit only
        assertThat(medium(wire, 5)).isEqualTo(257);                      // Command-Code CER
        assertThat(ByteBufUtil.hexDump(wire, 8, 12)).isEqualTo("00000000" + "11223344" + "55667788");
        assertThat(wire.length % 4).isZero();

        DiameterMessage decoded = decode(wire);
        assertThat(decoded.unsigned32(AvpCode.VENDOR_ID)).contains(0L);
        assertThat(decoded.unsigned32(AvpCode.AUTH_APPLICATION_ID)).contains(4L);
        assertThat(decoded.avp(AvpCode.PRODUCT_NAME).orElseThrow().isMandatory()).isFalse();
        assertThat(decoded.avp(AvpCode.HOST_IP_ADDRESS).orElseThrow().asAddress()).isEqualTo(InetAddress.getLoopbackAddress());
    }

    @Test
    void ccrIsProxiableRequestOfCreditControlApplication() {
        CreditControlRequest ccr = sampleCcr();
        byte[] wire = encode(ccr.toMessage(CLIENT, 1, 2));

        assertThat(wire[4] & 0xFF).isEqualTo(0xC0);                      // R + P
        assertThat(medium(wire, 5)).isEqualTo(272);                      // Command-Code CCR
        assertThat(ByteBufUtil.hexDump(wire, 8, 4)).isEqualTo("00000004"); // Application-Id 4
        // Session-Id is the first AVP
        assertThat(ByteBufUtil.hexDump(wire, 20, 4)).isEqualTo("00000107");
    }

    @Test
    void ccrRoundTripsThroughWireFormat() {
        CreditControlRequest ccr = sampleCcr();

        DiameterMessage decoded = decode(encode(ccr.toMessage(CLIENT, 7, 8)));

        assertThat(decoded.hopByHopId()).isEqualTo(7);
        assertThat(decoded.endToEndId()).isEqualTo(8);
        assertThat(CreditControlRequest.fromMessage(decoded)).isEqualTo(ccr);
    }

    @Test
    void ccaRoundTripsAndAnswerKeepsIdentifiers() {
        DiameterMessage request = sampleCcr().toMessage(CLIENT, 0xCAFEBABE, 0x0BADF00D);
        CreditControlAnswer cca = new CreditControlAnswer("s;1;2", ResultCode.DIAMETER_SUCCESS, "ocs", "realm",
                CcRequestType.INITIAL_REQUEST, 0L, new ServiceUnits(60L, 1_000_000L, null), 3600L, null);

        DiameterMessage answer = decode(encode(cca.toMessage(request)));

        assertThat(answer.isRequest()).isFalse();
        assertThat(answer.isProxiable()).isTrue();
        assertThat(answer.hopByHopId()).isEqualTo(0xCAFEBABE);
        assertThat(answer.endToEndId()).isEqualTo(0x0BADF00D);
        assertThat(answer.commandCode()).isEqualTo(CommandCode.CREDIT_CONTROL);
        assertThat(CreditControlAnswer.fromMessage(answer)).isEqualTo(cca);
    }

    @Test
    void protocolErrorAnswerSetsErrorBit() {
        DiameterMessage request = sampleCcr().toMessage(CLIENT, 1, 1);

        DiameterMessage protocolError = BaseMessages.errorAnswer(request, CLIENT, ResultCode.DIAMETER_TOO_BUSY, "busy");
        DiameterMessage permanentFailure = BaseMessages.errorAnswer(request, CLIENT, ResultCode.DIAMETER_USER_UNKNOWN, null);

        assertThat(protocolError.isError()).isTrue();
        assertThat(permanentFailure.isError()).isFalse();
        assertThat(protocolError.sessionId()).contains(request.sessionId().orElseThrow());
    }

    @Test
    void missingMandatoryAvpIsReported() {
        DiameterMessage incomplete = DiameterMessage.request(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .avp(Avp.utf8(AvpCode.SESSION_ID, true, "s"))
                .build();

        assertThatThrownBy(() -> CreditControlRequest.fromMessage(incomplete))
                .isInstanceOfSatisfying(MissingAvpException.class,
                        e -> assertThat(e.avpCode()).isEqualTo(AvpCode.ORIGIN_HOST));
    }

    @Test
    void rejectsWrongVersionAndLengthMismatch() {
        byte[] wire = encode(BaseMessages.dwr(CLIENT, 1, 1));

        byte[] badVersion = wire.clone();
        badVersion[0] = 2;
        assertThatThrownBy(() -> decode(badVersion)).hasMessageContaining("version");

        byte[] badLength = wire.clone();
        badLength[3] = (byte) (badLength[3] + 4);
        assertThatThrownBy(() -> decode(badLength)).hasMessageContaining("Message Length");
    }

    static CreditControlRequest sampleCcr() {
        return new CreditControlRequest(
                "gw.telecom-bridge.local;1700000000;42",
                "ocs.telecom-bridge.local",
                null,
                "32251@3gpp.org",
                CcRequestType.INITIAL_REQUEST,
                0,
                SubscriptionIdType.END_USER_E164,
                "919876543210",
                1L,
                new ServiceUnits(60L, 1_048_576L, null),
                Instant.ofEpochSecond(1_760_000_000L));
    }

    static byte[] encode(DiameterMessage msg) {
        ByteBuf buf = Unpooled.buffer();
        try {
            msg.encode(buf);
            assertThat(buf.readableBytes()).isEqualTo(msg.length());
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    static DiameterMessage decode(byte[] wire) {
        ByteBuf buf = Unpooled.wrappedBuffer(wire);
        try {
            return DiameterMessage.decode(buf);
        } finally {
            buf.release();
        }
    }

    static int medium(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 16) | ((b[offset + 1] & 0xFF) << 8) | (b[offset + 2] & 0xFF);
    }
}
