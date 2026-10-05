package com.telecombridge.diameter;

/** A mandatory AVP of a command's grammar is absent; maps to DIAMETER_MISSING_AVP (5005). */
public class MissingAvpException extends DiameterDecodeException {

    private final int avpCode;

    public MissingAvpException(int avpCode) {
        super("Missing mandatory AVP " + AvpCode.name(avpCode));
        this.avpCode = avpCode;
    }

    public int avpCode() {
        return avpCode;
    }
}
