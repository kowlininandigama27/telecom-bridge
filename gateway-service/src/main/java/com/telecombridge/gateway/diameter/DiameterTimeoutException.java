package com.telecombridge.gateway.diameter;

/** No answer arrived for a request within the configured timeout. Mapped to HTTP 504. */
public class DiameterTimeoutException extends RuntimeException {

    public DiameterTimeoutException(String message) {
        super(message);
    }
}
