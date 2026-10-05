package com.telecombridge.diameter;

/** Diameter Application-Ids (RFC 6733 section 2.4, RFC 4006 section 1.3). */
public final class ApplicationId {

    /** Diameter common messages: CER/CEA, DWR/DWA, DPR/DPA. */
    public static final int COMMON = 0;
    /** Diameter Credit-Control Application (Ro/Gy). */
    public static final int CREDIT_CONTROL = 4;
    /** Relay application, 0xffffffff. */
    public static final int RELAY = 0xFFFFFFFF;

    private ApplicationId() {
    }
}
