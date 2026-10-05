package com.telecombridge.diameter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * An immutable Diameter AVP (RFC 6733 section 4.1).
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           AVP Code                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V M P r r r r r|                  AVP Length                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        Vendor-ID (opt)                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Data ...
 * +-+-+-+-+-+-+-+-+
 * </pre>
 *
 * The AVP Length covers header and data but <em>not</em> the trailing padding; on the wire
 * each AVP is zero-padded to a 32-bit boundary.
 */
public final class Avp {

    public static final int FLAG_VENDOR = 0x80;
    public static final int FLAG_MANDATORY = 0x40;
    public static final int FLAG_PROTECTED = 0x20;

    static final int HEADER_LENGTH = 8;
    static final int VENDOR_HEADER_LENGTH = 12;

    /** Seconds between the NTP epoch (1900-01-01) and the Unix epoch (1970-01-01). */
    private static final long NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L;
    private static final int ADDRESS_FAMILY_IPV4 = 1;
    private static final int ADDRESS_FAMILY_IPV6 = 2;

    private final int code;
    private final int flags;
    private final long vendorId;
    private final byte[] data;

    private Avp(int code, int flags, long vendorId, byte[] data) {
        this.code = code;
        this.flags = flags & 0xE0;
        this.vendorId = vendorId;
        this.data = data;
    }

    // ---------------------------------------------------------------- factories

    public static Avp of(int code, boolean mandatory, byte[] data) {
        return new Avp(code, mandatory ? FLAG_MANDATORY : 0, 0, data.clone());
    }

    public static Avp vendorSpecific(int code, long vendorId, boolean mandatory, byte[] data) {
        int flags = FLAG_VENDOR | (mandatory ? FLAG_MANDATORY : 0);
        return new Avp(code, flags, vendorId, data.clone());
    }

    public static Avp utf8(int code, boolean mandatory, String value) {
        return new Avp(code, mandatory ? FLAG_MANDATORY : 0, 0, value.getBytes(StandardCharsets.UTF_8));
    }

    public static Avp unsigned32(int code, boolean mandatory, long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Unsigned32 out of range: " + value);
        }
        byte[] b = new byte[4];
        putInt(b, 0, (int) value);
        return new Avp(code, mandatory ? FLAG_MANDATORY : 0, 0, b);
    }

    public static Avp integer32(int code, boolean mandatory, int value) {
        byte[] b = new byte[4];
        putInt(b, 0, value);
        return new Avp(code, mandatory ? FLAG_MANDATORY : 0, 0, b);
    }

    /** Enumerated is derived from Integer32 (RFC 6733 section 4.3.1). */
    public static Avp enumerated(int code, boolean mandatory, int value) {
        return integer32(code, mandatory, value);
    }

    /** Unsigned64; values above {@link Long#MAX_VALUE} are carried in the sign bit. */
    public static Avp unsigned64(int code, boolean mandatory, long value) {
        byte[] b = new byte[8];
        putInt(b, 0, (int) (value >>> 32));
        putInt(b, 4, (int) value);
        return new Avp(code, mandatory ? FLAG_MANDATORY : 0, 0, b);
    }

    /** Address type: 2-octet address family (IANA) followed by the address (RFC 6733 section 4.3.1). */
    public static Avp address(int code, boolean mandatory, InetAddress address) {
        byte[] raw = address.getAddress();
        byte[] b = new byte[2 + raw.length];
        int family = raw.length == 4 ? ADDRESS_FAMILY_IPV4 : ADDRESS_FAMILY_IPV6;
        b[0] = (byte) (family >>> 8);
        b[1] = (byte) family;
        System.arraycopy(raw, 0, b, 2, raw.length);
        return new Avp(code, mandatory ? FLAG_MANDATORY : 0, 0, b);
    }

    /** Time type: seconds since 1900-01-01 00:00 UTC, as in NTP (RFC 6733 section 4.3.1). */
    public static Avp time(int code, boolean mandatory, Instant instant) {
        long ntpSeconds = instant.getEpochSecond() + NTP_EPOCH_OFFSET_SECONDS;
        byte[] b = new byte[4];
        putInt(b, 0, (int) ntpSeconds);
        return new Avp(code, mandatory ? FLAG_MANDATORY : 0, 0, b);
    }

    /** Grouped AVP: the data is the concatenation of the (padded) encoded child AVPs. */
    public static Avp grouped(int code, boolean mandatory, List<Avp> children) {
        int size = 0;
        for (Avp child : children) {
            size += child.encodedLength();
        }
        ByteBuf buf = Unpooled.buffer(size, size);
        try {
            for (Avp child : children) {
                child.encode(buf);
            }
            byte[] b = new byte[size];
            buf.readBytes(b);
            return new Avp(code, mandatory ? FLAG_MANDATORY : 0, 0, b);
        } finally {
            buf.release();
        }
    }

    public static Avp grouped(int code, boolean mandatory, Avp... children) {
        return grouped(code, mandatory, Arrays.asList(children));
    }

    // ---------------------------------------------------------------- accessors

    public int code() {
        return code;
    }

    public int flags() {
        return flags;
    }

    public long vendorId() {
        return vendorId;
    }

    public boolean isVendorSpecific() {
        return (flags & FLAG_VENDOR) != 0;
    }

    public boolean isMandatory() {
        return (flags & FLAG_MANDATORY) != 0;
    }

    public byte[] data() {
        return data.clone();
    }

    public int dataLength() {
        return data.length;
    }

    public String asUtf8() {
        return new String(data, StandardCharsets.UTF_8);
    }

    public long asUnsigned32() {
        requireLength(4);
        return getInt(data, 0) & 0xFFFF_FFFFL;
    }

    public int asInteger32() {
        requireLength(4);
        return getInt(data, 0);
    }

    public long asUnsigned64() {
        requireLength(8);
        return ((long) getInt(data, 0) << 32) | (getInt(data, 4) & 0xFFFF_FFFFL);
    }

    public InetAddress asAddress() {
        if (data.length != 6 && data.length != 18) {
            throw new DiameterDecodeException("Unsupported Address length " + data.length + " for " + AvpCode.name(code));
        }
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(data, 2, data.length));
        } catch (UnknownHostException e) {
            throw new DiameterDecodeException("Invalid Address: " + e.getMessage());
        }
    }

    public Instant asTime() {
        requireLength(4);
        long ntpSeconds = getInt(data, 0) & 0xFFFF_FFFFL;
        return Instant.ofEpochSecond(ntpSeconds - NTP_EPOCH_OFFSET_SECONDS);
    }

    public List<Avp> asGrouped() {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        List<Avp> children = new ArrayList<>(4);
        while (buf.isReadable()) {
            children.add(decode(buf));
        }
        return Collections.unmodifiableList(children);
    }

    // ---------------------------------------------------------------- wire format

    /** AVP Length field value: header + data, excluding padding. */
    public int length() {
        return (isVendorSpecific() ? VENDOR_HEADER_LENGTH : HEADER_LENGTH) + data.length;
    }

    /** Bytes this AVP occupies on the wire, including padding to a 4-octet boundary. */
    public int encodedLength() {
        return pad4(length());
    }

    public void encode(ByteBuf out) {
        int length = length();
        out.writeInt(code);
        out.writeByte(flags);
        out.writeMedium(length);
        if (isVendorSpecific()) {
            out.writeInt((int) vendorId);
        }
        out.writeBytes(data);
        out.writeZero(pad4(length) - length);
    }

    /** Decodes one AVP (and consumes its padding) from the reader index of {@code in}. */
    public static Avp decode(ByteBuf in) {
        if (in.readableBytes() < HEADER_LENGTH) {
            throw new DiameterDecodeException("Truncated AVP header: " + in.readableBytes() + " bytes left");
        }
        int code = in.readInt();
        int flags = in.readUnsignedByte();
        int length = in.readUnsignedMedium();
        boolean vendor = (flags & FLAG_VENDOR) != 0;
        int headerLength = vendor ? VENDOR_HEADER_LENGTH : HEADER_LENGTH;
        if (length < headerLength) {
            throw new DiameterDecodeException("AVP " + AvpCode.name(code) + " length " + length + " < header " + headerLength);
        }
        long vendorId = vendor ? in.readUnsignedInt() : 0;
        int dataLength = length - headerLength;
        int padding = pad4(length) - length;
        if (in.readableBytes() < dataLength) {
            throw new DiameterDecodeException("AVP " + AvpCode.name(code) + " overruns message: needs "
                    + dataLength + " bytes, " + in.readableBytes() + " left");
        }
        byte[] data = new byte[dataLength];
        in.readBytes(data);
        // Tolerate a peer that omits the padding of the very last AVP instead of rejecting the message.
        in.skipBytes(Math.min(padding, in.readableBytes()));
        return new Avp(code, flags, vendorId, data);
    }

    static int pad4(int length) {
        return (length + 3) & ~3;
    }

    // ---------------------------------------------------------------- helpers

    private void requireLength(int expected) {
        if (data.length != expected) {
            throw new DiameterDecodeException(AvpCode.name(code) + " expected " + expected + " bytes, got " + data.length);
        }
    }

    private static void putInt(byte[] b, int offset, int v) {
        b[offset] = (byte) (v >>> 24);
        b[offset + 1] = (byte) (v >>> 16);
        b[offset + 2] = (byte) (v >>> 8);
        b[offset + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Avp other && code == other.code && flags == other.flags
                && vendorId == other.vendorId && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * code + flags) + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        String flagStr = (isVendorSpecific() ? "V" : "") + (isMandatory() ? "M" : "") + ((flags & FLAG_PROTECTED) != 0 ? "P" : "");
        return AvpCode.name(code) + "[" + flagStr + "]=" + HexFormat.of().formatHex(data);
    }
}
