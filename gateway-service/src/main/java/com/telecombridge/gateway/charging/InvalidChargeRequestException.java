package com.telecombridge.gateway.charging;

/** Semantically invalid charge request that bean validation cannot express. Mapped to HTTP 400. */
public class InvalidChargeRequestException extends RuntimeException {

    public InvalidChargeRequestException(String message) {
        super(message);
    }
}
