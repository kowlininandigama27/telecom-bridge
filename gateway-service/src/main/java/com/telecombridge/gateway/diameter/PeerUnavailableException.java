package com.telecombridge.gateway.diameter;

/** The Diameter peer is not connected / not in OKAY state. Mapped to HTTP 503. */
public class PeerUnavailableException extends RuntimeException {

    public PeerUnavailableException(String message) {
        super(message);
    }
}
