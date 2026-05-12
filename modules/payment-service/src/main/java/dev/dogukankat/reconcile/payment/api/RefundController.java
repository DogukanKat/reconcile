package dev.dogukankat.reconcile.payment.api;

import dev.dogukankat.reconcile.payment.application.RefundCommand;
import dev.dogukankat.reconcile.payment.application.RefundService;
import dev.dogukankat.reconcile.payment.application.ServiceResult;
import dev.dogukankat.reconcile.payment.authorization.CaptureId;
import dev.dogukankat.reconcile.payment.authorization.MerchantId;
import dev.dogukankat.reconcile.payment.authorization.Money;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/captures")
public class RefundController {

    private final RefundService service;
    private final IdempotencyHashing hashing;

    public RefundController(RefundService service, IdempotencyHashing hashing) {
        this.service = service;
        this.hashing = hashing;
    }

    @PostMapping("/{id}/refunds")
    public ResponseEntity<String> refund(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @Valid @RequestBody RefundRequest request) {
        RefundCommand command = new RefundCommand(
                new CaptureId(id),
                new MerchantId(merchantId),
                new Money(new BigDecimal(request.amount()), request.currency()),
                idempotencyKey,
                hashing.compute(request));
        ServiceResult result = service.refund(command);

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(result.httpStatus())
                .contentType(MediaType.APPLICATION_JSON);
        if (result.resourceId() != null) {
            builder = builder.header(HttpHeaders.LOCATION,
                    "/refunds/" + result.resourceId());
        }
        return builder.body(result.body());
    }
}
