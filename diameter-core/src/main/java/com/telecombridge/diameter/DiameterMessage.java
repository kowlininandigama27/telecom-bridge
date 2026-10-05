package com.telecombridge.diameter;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * An immutable Diameter message (RFC 6733 section 3).
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Version    |                 Message Length                |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | command flags |                  Command Code                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Application-ID                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      Hop-by-Hop Identifier                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      End-to-End Identifier                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  AVPs ...
 * +-+-+-+-+-+-+-+-+-+-+-+-+-
 * </pre>
 */
public final class DiameterMessage {

    public static final int VERSION = 1;
    public static final int HEADER_LENGTH = 20;

    public static final int FLAG_REQUEST = 0x80;
    public static final int FLAG_PROXIABLE = 0x40;
    public static final int FLAG_ERROR = 0x20;
    public static final int FLAG_RETRANSMIT = 0x10;

    private final int flags;
    private final int commandCode;
    private final int applicationId;
    private final int hopByHopId;
    private final int endToEndId;
    private final List<Avp> avps;

    private DiameterMessage(Builder b) {
        this.flags = b.flags;
        this.commandCode = b.commandCode;
        this.applicationId = b.applicationId;
        this.hopByHopId = b.hopByHopId;
        this.endToEndId = b.endToEndId;
        this.avps = Collections.unmodifiableList(new ArrayList<>(b.avps));
    }

    public static Builder request(int commandCode, int applicationId) {
        return new Builder(commandCode, applicationId).flags(FLAG_REQUEST);
    }

    /**
     * Starts an answer to {@code request}: same Command-Code, Application-Id, Hop-by-Hop and
     * End-to-End identifiers, R bit cleared and P bit copied (RFC 6733 section 6.2).
     */
    public static Builder answerTo(DiameterMessage request) {
        return new Builder(request.commandCode, request.applicationId)
                .flags(request.flags & FLAG_PROXIABLE)
                .hopByHopId(request.hopByHopId)
                .endToEndId(request.endToEndId);
    }

    // ---------------------------------------------------------------- header

    public int flags() {
        return flags;
    }

    public int commandCode() {
        return commandCode;
    }

    public int applicationId() {
        return applicationId;
    }

    public int hopByHopId() {
        return hopByHopId;
    }

    public int endToEndId() {
        return endToEndId;
    }

    public boolean isRequest() {
        return (flags & FLAG_REQUEST) != 0;
    }

    public boolean isProxiable() {
        return (flags & FLAG_PROXIABLE) != 0;
    }

    public boolean isError() {
        return (flags & FLAG_ERROR) != 0;
    }

    public String mnemonic() {
        return CommandCode.mnemonic(commandCode, isRequest());
    }

    // ---------------------------------------------------------------- AVPs

    public List<Avp> avps() {
        return avps;
    }

    public Optional<Avp> avp(int code) {
        for (Avp avp : avps) {
            if (avp.code() == code) {
                return Optional.of(avp);
            }
        }
        return Optional.empty();
    }

    public List<Avp> avps(int code) {
        List<Avp> found = new ArrayList<>(2);
        for (Avp avp : avps) {
            if (avp.code() == code) {
                found.add(avp);
            }
        }
        return found;
    }

    public Optional<String> utf8(int code) {
        return avp(code).map(Avp::asUtf8);
    }

    public Optional<Long> unsigned32(int code) {
        return avp(code).map(Avp::asUnsigned32);
    }

    public Optional<Long> resultCode() {
        return unsigned32(AvpCode.RESULT_CODE);
    }

    public Optional<String> sessionId() {
        return utf8(AvpCode.SESSION_ID);
    }

    // ---------------------------------------------------------------- wire format

    public int length() {
        int length = HEADER_LENGTH;
        for (Avp avp : avps) {
            length += avp.encodedLength();
        }
        return length;
    }

    public void encode(ByteBuf out) {
        out.writeByte(VERSION);
        out.writeMedium(length());
        out.writeByte(flags);
        out.writeMedium(commandCode);
        out.writeInt(applicationId);
        out.writeInt(hopByHopId);
        out.writeInt(endToEndId);
        for (Avp avp : avps) {
            avp.encode(out);
        }
    }

    /** Decodes exactly one complete message occupying all readable bytes of {@code frame}. */
    public static DiameterMessage decode(ByteBuf frame) {
        if (frame.readableBytes() < HEADER_LENGTH) {
            throw new DiameterDecodeException("Message shorter than header: " + frame.readableBytes());
        }
        int version = frame.readUnsignedByte();
        if (version != VERSION) {
            throw new DiameterDecodeException("Unsupported Diameter version " + version);
        }
        int length = frame.readUnsignedMedium();
        if (length != frame.readableBytes() + 4) {
            throw new DiameterDecodeException("Message Length " + length + " does not match frame size " + (frame.readableBytes() + 4));
        }
        if ((length & 3) != 0) {
            throw new DiameterDecodeException("Message Length " + length + " is not a multiple of 4");
        }
        Builder b = new Builder(0, 0)
                .flags(frame.readUnsignedByte())
                .commandCode(frame.readUnsignedMedium())
                .applicationId(frame.readInt())
                .hopByHopId(frame.readInt())
                .endToEndId(frame.readInt());
        while (frame.isReadable()) {
            b.avp(Avp.decode(frame));
        }
        return b.build();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128)
                .append(mnemonic())
                .append(" app=").append(Integer.toUnsignedString(applicationId))
                .append(" flags=").append(isRequest() ? 'R' : '-').append(isProxiable() ? 'P' : '-').append(isError() ? 'E' : '-')
                .append(" hbh=0x").append(Integer.toHexString(hopByHopId))
                .append(" e2e=0x").append(Integer.toHexString(endToEndId));
        sessionId().ifPresent(s -> sb.append(" session=").append(s));
        resultCode().ifPresent(rc -> sb.append(" result=").append(rc));
        return sb.toString();
    }

    public static final class Builder {
        private int flags;
        private int commandCode;
        private int applicationId;
        private int hopByHopId;
        private int endToEndId;
        private final List<Avp> avps = new ArrayList<>(16);

        private Builder(int commandCode, int applicationId) {
            this.commandCode = commandCode;
            this.applicationId = applicationId;
        }

        public Builder flags(int flags) {
            this.flags = flags & 0xF0;
            return this;
        }

        public Builder proxiable() {
            this.flags |= FLAG_PROXIABLE;
            return this;
        }

        public Builder error() {
            this.flags |= FLAG_ERROR;
            return this;
        }

        public Builder commandCode(int commandCode) {
            this.commandCode = commandCode;
            return this;
        }

        public Builder applicationId(int applicationId) {
            this.applicationId = applicationId;
            return this;
        }

        public Builder hopByHopId(int hopByHopId) {
            this.hopByHopId = hopByHopId;
            return this;
        }

        public Builder endToEndId(int endToEndId) {
            this.endToEndId = endToEndId;
            return this;
        }

        public Builder avp(Avp avp) {
            avps.add(avp);
            return this;
        }

        public Builder avps(List<Avp> more) {
            avps.addAll(more);
            return this;
        }

        public DiameterMessage build() {
            return new DiameterMessage(this);
        }
    }
}
