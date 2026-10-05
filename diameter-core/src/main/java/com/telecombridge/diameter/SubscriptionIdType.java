package com.telecombridge.diameter;

/** Subscription-Id-Type AVP values (RFC 4006 section 8.47). */
public enum SubscriptionIdType {
    /** MSISDN in international E.164 format. */
    END_USER_E164(0),
    END_USER_IMSI(1),
    END_USER_SIP_URI(2),
    END_USER_NAI(3),
    END_USER_PRIVATE(4);

    private final int value;

    SubscriptionIdType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static SubscriptionIdType fromValue(long value) {
        for (SubscriptionIdType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown Subscription-Id-Type " + value);
    }
}
