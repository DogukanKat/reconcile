package dev.dogukankat.reconcile.payment.api;

import dev.dogukankat.reconcile.payment.application.AuthorizationService;
import dev.dogukankat.reconcile.payment.application.AuthorizationView;
import dev.dogukankat.reconcile.payment.application.AuthorizeCommand;
import dev.dogukankat.reconcile.payment.application.CaptureCommand;
import dev.dogukankat.reconcile.payment.application.ServiceResult;
import dev.dogukankat.reconcile.payment.application.VoidCommand;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationId;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/authorizations")
public class AuthorizationController {

    private final AuthorizationService service;
    private final IdempotencyHashing hashing;

    public AuthorizationController(AuthorizationService service, IdempotencyHashing hashing) {
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
        return toResponse(service.authorize(command), "/authorizations");
    }

    @PostMapping("/{id}/captures")
    public ResponseEntity<String> capture(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @Valid @RequestBody CaptureRequest request) {
        CaptureCommand command = new CaptureCommand(
                new AuthorizationId(id),
                new MerchantId(merchantId),
                new Money(new BigDecimal(request.amount()), request.currency()),
                idempotencyKey,
                hashing.compute(request));
        return toResponse(service.capture(command), "/captures");
    }

    @PostMapping("/{id}/void")
    public ResponseEntity<String> voidAuthorization(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Merchant-Id") UUID merchantId) {
        VoidCommand command = new VoidCommand(
                new AuthorizationId(id),
                new MerchantId(merchantId),
                idempotencyKey,
                hashing.compute(Map.of()));
        return toResponse(service.voidAuthorization(command), "/authorizations");
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        Optional<AuthorizationView> view = service.findById(new AuthorizationId(id));
        return view.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity
                        .status(404)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"not_found\"}"));
    }

    private ResponseEntity<String> toResponse(ServiceResult result, String pathPrefix) {
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(result.httpStatus())
                .contentType(MediaType.APPLICATION_JSON);
        if (result.resourceId() != null) {
            builder = builder.header(HttpHeaders.LOCATION,
                    pathPrefix + "/" + result.resourceId());
        }
        return builder.body(result.body());
    }
}
