package com.telecombridge.gateway.charging;

import com.telecombridge.diameter.ResultCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/v1/charge}. Fully non-blocking: the Reactor Netty I/O thread hands the CCR to
 * the Diameter event loop and returns immediately; the HTTP response is written when the CCA's
 * future completes.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChargeController {

    private final ChargingService chargingService;

    public ChargeController(ChargingService chargingService) {
        this.chargingService = chargingService;
    }

    @PostMapping(path = "/charge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ChargeResponse>> charge(@Valid @RequestBody ChargeRequest request) {
        return Mono.fromFuture(() -> chargingService.charge(request))
                .map(response -> ResponseEntity.status(httpStatusFor(response.resultCode())).body(response));
    }

    /**
     * The CCA is always returned in the body; the HTTP status summarises the Result-Code so that
     * plain HTTP clients and load balancers can react without parsing Diameter semantics.
     */
    static HttpStatus httpStatusFor(long resultCode) {
        if (ResultCode.isSuccess(resultCode)) {
            return HttpStatus.OK;
        }
        return switch ((int) resultCode) {
            case ResultCode.DIAMETER_CREDIT_LIMIT_REACHED -> HttpStatus.PAYMENT_REQUIRED;
            case ResultCode.DIAMETER_END_USER_SERVICE_DENIED -> HttpStatus.FORBIDDEN;
            case ResultCode.DIAMETER_USER_UNKNOWN -> HttpStatus.NOT_FOUND;
            case ResultCode.DIAMETER_MISSING_AVP, ResultCode.DIAMETER_INVALID_AVP_VALUE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case ResultCode.DIAMETER_TOO_BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }
}
