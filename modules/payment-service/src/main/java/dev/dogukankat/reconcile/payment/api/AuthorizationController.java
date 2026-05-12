package dev.dogukankat.reconcile.payment.api;

import dev.dogukankat.reconcile.payment.application.AuthorizationService;
import dev.dogukankat.reconcile.payment.application.AuthorizeCommand;
import dev.dogukankat.reconcile.payment.application.ServiceResult;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/authorizations")
public class AuthorizationController {

    private final AuthorizationService service;
    private final IdempotencyHashing hashing;

    public AuthorizationController(
            AuthorizationService service,
            IdempotencyHashing hashing) {
        this.service = service;
        this.hashing = hashing;
    }

    @PostMapping
    public ResponseEntity<String> authorize(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AuthorizeRequest request) {
        AuthorizeCommand command = new AuthorizeCommand(
                new MerchantId(request.merchantId()),
                new Money(new BigDecimal(request.amount()), request.currency()),
                request.expiresAt(),
                idempotencyKey,
                hashing.compute(request));
        ServiceResult result = service.authorize(command);
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(result.httpStatus())
                .contentType(MediaType.APPLICATION_JSON);
        if (result.resourceId() != null) {
            builder = builder.header(HttpHeaders.LOCATION,
                    "/authorizations/" + result.resourceId());
        }
        return builder.body(result.body());
    }
}
