package com.telecombridge.diameter;

/** Diameter command codes used by this gateway (RFC 6733 section 3.1, RFC 4006 section 3). */
public final class CommandCode {

    public static final int CAPABILITIES_EXCHANGE = 257;
    public static final int CREDIT_CONTROL = 272;
    public static final int DEVICE_WATCHDOG = 280;
    public static final int DISCONNECT_PEER = 282;

    private CommandCode() {
    }

    /** Short mnemonic such as {@code CCR} / {@code CCA} used in logs. */
    public static String mnemonic(int commandCode, boolean request) {
        String base = switch (commandCode) {
            case CAPABILITIES_EXCHANGE -> "CE";
            case CREDIT_CONTROL -> "CC";
            case DEVICE_WATCHDOG -> "DW";
            case DISCONNECT_PEER -> "DP";
            default -> "CMD(" + commandCode + ")";
        };
        return base + (request ? "R" : "A");
    }
}
