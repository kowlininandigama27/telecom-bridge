package com.telecombridge.diameter;

import java.util.Map;

/** AVP codes from RFC 6733 (base protocol) and RFC 4006 (credit control). */
public final class AvpCode {

    // RFC 6733 base protocol
    public static final int USER_NAME = 1;
    public static final int EVENT_TIMESTAMP = 55;
    public static final int HOST_IP_ADDRESS = 257;
    public static final int AUTH_APPLICATION_ID = 258;
    public static final int ACCT_APPLICATION_ID = 259;
    public static final int VENDOR_SPECIFIC_APPLICATION_ID = 260;
    public static final int SESSION_ID = 263;
    public static final int ORIGIN_HOST = 264;
    public static final int SUPPORTED_VENDOR_ID = 265;
    public static final int VENDOR_ID = 266;
    public static final int FIRMWARE_REVISION = 267;
    public static final int RESULT_CODE = 268;
    public static final int PRODUCT_NAME = 269;
    public static final int DISCONNECT_CAUSE = 273;
    public static final int ORIGIN_STATE_ID = 278;
    public static final int FAILED_AVP = 279;
    public static final int ERROR_MESSAGE = 281;
    public static final int DESTINATION_REALM = 283;
    public static final int DESTINATION_HOST = 293;
    public static final int TERMINATION_CAUSE = 295;
    public static final int ORIGIN_REALM = 296;
    public static final int INBAND_SECURITY_ID = 299;

    // RFC 4006 credit control
    public static final int CC_REQUEST_NUMBER = 415;
    public static final int CC_REQUEST_TYPE = 416;
    public static final int CC_SERVICE_SPECIFIC_UNITS = 417;
    public static final int CC_TIME = 420;
    public static final int CC_TOTAL_OCTETS = 421;
    public static final int GRANTED_SERVICE_UNIT = 431;
    public static final int RATING_GROUP = 432;
    public static final int REQUESTED_ACTION = 436;
    public static final int REQUESTED_SERVICE_UNIT = 437;
    public static final int SERVICE_IDENTIFIER = 439;
    public static final int SUBSCRIPTION_ID_DATA = 444;
    public static final int USED_SERVICE_UNIT = 446;
    public static final int VALIDITY_TIME = 448;
    public static final int SUBSCRIPTION_ID_TYPE = 450;
    public static final int MULTIPLE_SERVICES_INDICATOR = 455;
    public static final int SUBSCRIPTION_ID = 443;
    public static final int SERVICE_CONTEXT_ID = 461;

    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(USER_NAME, "User-Name"),
            Map.entry(EVENT_TIMESTAMP, "Event-Timestamp"),
            Map.entry(HOST_IP_ADDRESS, "Host-IP-Address"),
            Map.entry(AUTH_APPLICATION_ID, "Auth-Application-Id"),
            Map.entry(ACCT_APPLICATION_ID, "Acct-Application-Id"),
            Map.entry(VENDOR_SPECIFIC_APPLICATION_ID, "Vendor-Specific-Application-Id"),
            Map.entry(SESSION_ID, "Session-Id"),
            Map.entry(ORIGIN_HOST, "Origin-Host"),
            Map.entry(SUPPORTED_VENDOR_ID, "Supported-Vendor-Id"),
            Map.entry(VENDOR_ID, "Vendor-Id"),
            Map.entry(FIRMWARE_REVISION, "Firmware-Revision"),
            Map.entry(RESULT_CODE, "Result-Code"),
            Map.entry(PRODUCT_NAME, "Product-Name"),
            Map.entry(DISCONNECT_CAUSE, "Disconnect-Cause"),
            Map.entry(ORIGIN_STATE_ID, "Origin-State-Id"),
            Map.entry(FAILED_AVP, "Failed-AVP"),
            Map.entry(ERROR_MESSAGE, "Error-Message"),
            Map.entry(DESTINATION_REALM, "Destination-Realm"),
            Map.entry(DESTINATION_HOST, "Destination-Host"),
            Map.entry(TERMINATION_CAUSE, "Termination-Cause"),
            Map.entry(ORIGIN_REALM, "Origin-Realm"),
            Map.entry(INBAND_SECURITY_ID, "Inband-Security-Id"),
            Map.entry(CC_REQUEST_NUMBER, "CC-Request-Number"),
            Map.entry(CC_REQUEST_TYPE, "CC-Request-Type"),
            Map.entry(CC_SERVICE_SPECIFIC_UNITS, "CC-Service-Specific-Units"),
            Map.entry(CC_TIME, "CC-Time"),
            Map.entry(CC_TOTAL_OCTETS, "CC-Total-Octets"),
            Map.entry(GRANTED_SERVICE_UNIT, "Granted-Service-Unit"),
            Map.entry(RATING_GROUP, "Rating-Group"),
            Map.entry(REQUESTED_ACTION, "Requested-Action"),
            Map.entry(REQUESTED_SERVICE_UNIT, "Requested-Service-Unit"),
            Map.entry(SERVICE_IDENTIFIER, "Service-Identifier"),
            Map.entry(SUBSCRIPTION_ID_DATA, "Subscription-Id-Data"),
            Map.entry(USED_SERVICE_UNIT, "Used-Service-Unit"),
            Map.entry(VALIDITY_TIME, "Validity-Time"),
            Map.entry(SUBSCRIPTION_ID_TYPE, "Subscription-Id-Type"),
            Map.entry(MULTIPLE_SERVICES_INDICATOR, "Multiple-Services-Indicator"),
            Map.entry(SUBSCRIPTION_ID, "Subscription-Id"),
            Map.entry(SERVICE_CONTEXT_ID, "Service-Context-Id"));

    private AvpCode() {
    }

    public static String name(int code) {
        return NAMES.getOrDefault(code, "AVP(" + code + ")");
    }
}
