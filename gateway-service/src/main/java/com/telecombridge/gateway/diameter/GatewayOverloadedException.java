package com.telecombridge.gateway.diameter;

/** Too many in-flight requests or the socket's outbound buffer is full. Mapped to HTTP 503. */
public class GatewayOverloadedException extends RuntimeException {

    public GatewayOverloadedException(String message) {
        super(message);
    }
}
