package com.telecombridge.diameter;

/** CC-Request-Type AVP values (RFC 4006 section 8.3). */
public enum CcRequestType {
    INITIAL_REQUEST(1),
    UPDATE_REQUEST(2),
    TERMINATION_REQUEST(3),
    EVENT_REQUEST(4);

    private final int value;

    CcRequestType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static CcRequestType fromValue(long value) {
        for (CcRequestType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown CC-Request-Type " + value);
    }
}
