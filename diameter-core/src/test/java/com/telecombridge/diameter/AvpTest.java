package com.telecombridge.diameter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvpTest {

    @Test
    void utf8AvpIsPaddedToFourOctetsButLengthExcludesPadding() {
        Avp avp = Avp.utf8(AvpCode.ORIGIN_HOST, true, "abcde");

        assertThat(avp.length()).isEqualTo(13);
        assertThat(avp.encodedLength()).isEqualTo(16);
        // code=264, flags=M, length=13, "abcde", 3 zero bytes of padding
        assertThat(hex(avp)).isEqualTo("00000108" + "4000000d" + "6162636465" + "000000");
    }

    @Test
    void alignedValueGetsNoPadding() {
        Avp avp = Avp.utf8(AvpCode.ORIGIN_REALM, true, "abcd");

        assertThat(avp.length()).isEqualTo(12);
        assertThat(hex(avp)).isEqualTo("00000128" + "4000000c" + "61626364");
    }

    @Test
    void productNameIsEncodedWithoutMandatoryBit() {
        Avp avp = Avp.utf8(AvpCode.PRODUCT_NAME, false, "TB");

        assertThat(hex(avp)).isEqualTo("0000010d" + "0000000a" + "5442" + "0000");
    }

    @Test
    void unsigned32CoversFullUnsignedRange() {
        Avp avp = Avp.unsigned32(AvpCode.ORIGIN_STATE_ID, true, 0xFFFF_FFFFL);

        assertThat(hex(avp)).isEqualTo("00000116" + "4000000c" + "ffffffff");
        assertThat(roundTrip(avp).asUnsigned32()).isEqualTo(0xFFFF_FFFFL);
        assertThatThrownBy(() -> Avp.unsigned32(1, true, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsigned64RoundTrips() {
        Avp avp = Avp.unsigned64(AvpCode.CC_TOTAL_OCTETS, true, 0x0102030405060708L);

        assertThat(hex(avp)).isEqualTo("000001a5" + "40000010" + "0102030405060708");
        assertThat(roundTrip(avp).asUnsigned64()).isEqualTo(0x0102030405060708L);
    }

    @Test
    void ipv4AddressCarriesAddressFamily() throws Exception {
        Avp avp = Avp.address(AvpCode.HOST_IP_ADDRESS, true, InetAddress.getByName("127.0.0.1"));

        // length 14 = 8 header + 2 family + 4 address, padded to 16
        assertThat(hex(avp)).isEqualTo("00000101" + "4000000e" + "0001" + "7f000001" + "0000");
        assertThat(roundTrip(avp).asAddress()).isEqualTo(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    void timeUsesNtpEpoch() {
        Avp avp = Avp.time(AvpCode.EVENT_TIMESTAMP, true, Instant.EPOCH);

        // 1970-01-01 is 2208988800 seconds after 1900-01-01
        assertThat(hex(avp)).isEqualTo("00000037" + "4000000c" + "83aa7e80");
        assertThat(roundTrip(avp).asTime()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void groupedSubscriptionIdContainsPaddedChildren() {
        Avp avp = Avp.grouped(AvpCode.SUBSCRIPTION_ID, true,
                Avp.enumerated(AvpCode.SUBSCRIPTION_ID_TYPE, true, SubscriptionIdType.END_USER_E164.value()),
                Avp.utf8(AvpCode.SUBSCRIPTION_ID_DATA, true, "4670"));

        String expected = "000001bb" + "40000020"              // Subscription-Id, length 8 + 12 + 12 = 32
                + "000001c2" + "4000000c" + "00000000"           // Subscription-Id-Type = END_USER_E164
                + "000001bc" + "4000000c" + "34363730";          // Subscription-Id-Data = "4670"
        assertThat(hex(avp)).isEqualTo(expected);

        List<Avp> children = roundTrip(avp).asGrouped();
        assertThat(children).hasSize(2);
        assertThat(children.get(0).asInteger32()).isZero();
        assertThat(children.get(1).asUtf8()).isEqualTo("4670");
    }

    @Test
    void vendorSpecificAvpHasTwelveByteHeader() {
        Avp avp = Avp.vendorSpecific(1000, 10415, true, new byte[] {1});

        assertThat(avp.length()).isEqualTo(13);
        assertThat(hex(avp)).isEqualTo("000003e8" + "c000000d" + "000028af" + "01" + "000000");
        Avp decoded = roundTrip(avp);
        assertThat(decoded.vendorId()).isEqualTo(10415);
        assertThat(decoded).isEqualTo(avp);
    }

    @Test
    void rejectsAvpLengthSmallerThanHeader() {
        ByteBuf buf = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("0000010840000004"));
        try {
            assertThatThrownBy(() -> Avp.decode(buf)).isInstanceOf(DiameterDecodeException.class);
        } finally {
            buf.release();
        }
    }

    @Test
    void rejectsAvpThatOverrunsBuffer() {
        ByteBuf buf = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump("00000108400000200102"));
        try {
            assertThatThrownBy(() -> Avp.decode(buf)).isInstanceOf(DiameterDecodeException.class)
                    .hasMessageContaining("overruns");
        } finally {
            buf.release();
        }
    }

    static String hex(Avp avp) {
        ByteBuf buf = Unpooled.buffer();
        try {
            avp.encode(buf);
            assertThat(buf.readableBytes()).isEqualTo(avp.encodedLength());
            return ByteBufUtil.hexDump(buf);
        } finally {
            buf.release();
        }
    }

    static Avp roundTrip(Avp avp) {
        ByteBuf buf = Unpooled.buffer();
        try {
            avp.encode(buf);
            Avp decoded = Avp.decode(buf);
            assertThat(buf.isReadable()).isFalse();
            return decoded;
        } finally {
            buf.release();
        }
    }
}
