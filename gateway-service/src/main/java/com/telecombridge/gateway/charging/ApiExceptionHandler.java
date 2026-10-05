package com.telecombridge.gateway.charging;

import com.telecombridge.gateway.diameter.DiameterTimeoutException;
import com.telecombridge.gateway.diameter.GatewayOverloadedException;
import com.telecombridge.gateway.diameter.PeerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Maps failures to RFC 9457 problem responses:
 * <ul>
 *   <li>Diameter peer down / not yet OPEN → 503 Service Unavailable + Retry-After</li>
 *   <li>Gateway over its in-flight limit → 503 Service Unavailable + Retry-After</li>
 *   <li>No CCA within {@code diameter.request-timeout} → 504 Gateway Timeout</li>
 *   <li>Invalid JSON / validation errors → 400 Bad Request</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<ProblemDetail> unwrap(CompletionException e) throws Throwable {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof PeerUnavailableException p) {
            return peerUnavailable(p);
        }
        if (cause instanceof GatewayOverloadedException o) {
            return overloaded(o);
        }
        if (cause instanceof DiameterTimeoutException t) {
            return timeout(t);
        }
        throw cause;
    }

    @ExceptionHandler(PeerUnavailableException.class)
    public ResponseEntity<ProblemDetail> peerUnavailable(PeerUnavailableException e) {
        log.warn("503 Diameter peer unavailable: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Diameter peer unavailable", e.getMessage(), true);
    }

    @ExceptionHandler(GatewayOverloadedException.class)
    public ResponseEntity<ProblemDetail> overloaded(GatewayOverloadedException e) {
        log.warn("503 gateway overloaded: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Gateway overloaded", e.getMessage(), true);
    }

    @ExceptionHandler(DiameterTimeoutException.class)
    public ResponseEntity<ProblemDetail> timeout(DiameterTimeoutException e) {
        log.warn("504 Diameter timeout: {}", e.getMessage());
        return problem(HttpStatus.GATEWAY_TIMEOUT, "Diameter answer timeout", e.getMessage(), false);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ProblemDetail> validation(WebExchangeBindException e) {
        String detail = e.getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid charge request", detail, false);
    }

    @ExceptionHandler({InvalidChargeRequestException.class, ServerWebInputException.class})
    public ResponseEntity<ProblemDetail> badRequest(Exception e) {
        String detail = e instanceof ServerWebInputException w ? w.getReason() : e.getMessage();
        return problem(HttpStatus.BAD_REQUEST, "Invalid charge request", detail, false);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail, boolean retryable) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (retryable) {
            builder.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return builder.body(body);
    }
}
