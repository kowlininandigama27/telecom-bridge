package com.telecombridge.diameter;

/** Result-Code values (RFC 6733 section 7.1, RFC 4006 section 9). */
public final class ResultCode {

    public static final int DIAMETER_SUCCESS = 2001;
    public static final int DIAMETER_LIMITED_SUCCESS = 2002;

    public static final int DIAMETER_COMMAND_UNSUPPORTED = 3001;
    public static final int DIAMETER_UNABLE_TO_DELIVER = 3002;
    public static final int DIAMETER_TOO_BUSY = 3004;
    public static final int DIAMETER_APPLICATION_UNSUPPORTED = 3007;
    public static final int DIAMETER_INVALID_HDR_BITS = 3008;
    public static final int DIAMETER_UNKNOWN_PEER = 3010;

    public static final int DIAMETER_UNABLE_TO_COMPLY = 5012;
    public static final int DIAMETER_MISSING_AVP = 5005;
    public static final int DIAMETER_INVALID_AVP_VALUE = 5004;
    public static final int DIAMETER_NO_COMMON_APPLICATION = 5010;

    // RFC 4006
    public static final int DIAMETER_END_USER_SERVICE_DENIED = 4010;
    public static final int DIAMETER_CREDIT_LIMIT_REACHED = 4012;
    public static final int DIAMETER_USER_UNKNOWN = 5030;
    public static final int DIAMETER_RATING_FAILED = 5031;

    private ResultCode() {
    }

    public static boolean isSuccess(long code) {
        return code >= 2000 && code < 3000;
    }

    public static String describe(long code) {
        return switch ((int) code) {
            case DIAMETER_SUCCESS -> "DIAMETER_SUCCESS";
            case DIAMETER_LIMITED_SUCCESS -> "DIAMETER_LIMITED_SUCCESS";
            case DIAMETER_COMMAND_UNSUPPORTED -> "DIAMETER_COMMAND_UNSUPPORTED";
            case DIAMETER_UNABLE_TO_DELIVER -> "DIAMETER_UNABLE_TO_DELIVER";
            case DIAMETER_TOO_BUSY -> "DIAMETER_TOO_BUSY";
            case DIAMETER_APPLICATION_UNSUPPORTED -> "DIAMETER_APPLICATION_UNSUPPORTED";
            case DIAMETER_INVALID_HDR_BITS -> "DIAMETER_INVALID_HDR_BITS";
            case DIAMETER_UNKNOWN_PEER -> "DIAMETER_UNKNOWN_PEER";
            case DIAMETER_UNABLE_TO_COMPLY -> "DIAMETER_UNABLE_TO_COMPLY";
            case DIAMETER_MISSING_AVP -> "DIAMETER_MISSING_AVP";
            case DIAMETER_INVALID_AVP_VALUE -> "DIAMETER_INVALID_AVP_VALUE";
            case DIAMETER_NO_COMMON_APPLICATION -> "DIAMETER_NO_COMMON_APPLICATION";
            case DIAMETER_END_USER_SERVICE_DENIED -> "DIAMETER_END_USER_SERVICE_DENIED";
            case DIAMETER_CREDIT_LIMIT_REACHED -> "DIAMETER_CREDIT_LIMIT_REACHED";
            case DIAMETER_USER_UNKNOWN -> "DIAMETER_USER_UNKNOWN";
            case DIAMETER_RATING_FAILED -> "DIAMETER_RATING_FAILED";
            default -> "RESULT_CODE_" + code;
        };
    }
}
