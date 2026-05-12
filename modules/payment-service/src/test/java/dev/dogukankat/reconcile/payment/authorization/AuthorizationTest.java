package dev.dogukankat.reconcile.payment.authorization;

import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.AuthFailed;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Authorized;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Expired;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Initiated;
import dev.dogukankat.reconcile.payment.authorization.AuthorizationStatus.Voided;
import dev.dogukankat.reconcile.payment.event.DomainEvent.AuthorizationExpired;
import dev.dogukankat.reconcile.payment.event.DomainEvent.AuthorizationFailed;
import dev.dogukankat.reconcile.payment.event.DomainEvent.AuthorizationVoided;
import dev.dogukankat.reconcile.payment.event.DomainEvent.PaymentAuthorized;
import dev.dogukankat.reconcile.payment.event.DomainEvent.PaymentCaptured;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationTest {

    private static final Instant T0 = Instant.parse("2026-05-11T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-11T10:05:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-05-18T10:00:00Z");
    private static final MerchantId MERCHANT = new MerchantId(UUID.randomUUID());
    private static final Money AMOUNT = new Money(new BigDecimal("100.00"), "USD");

    @Test
    void initiateStartsInInitiatedState() {
        Authorization a = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0);

        assertThat(a.status()).isInstanceOf(Initiated.class);
        assertThat(((Initiated) a.status()).timestamp()).isEqualTo(T0);
        assertThat(a.merchantId()).isEqualTo(MERCHANT);
        assertThat(a.authorizedAmount()).isEqualTo(AMOUNT);
        assertThat(a.captures()).isEmpty();
    }

    @Test
    void authorizeMovesInitiatedToAuthorized() {
        Authorization a = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0);

        AuthorizationResult r = a.authorize(T1);

        assertThat(r.next().status()).isInstanceOf(Authorized.class);
        assertThat(((Authorized) r.next().status()).timestamp()).isEqualTo(T1);
        assertThat(r.events()).hasSize(1).first().isInstanceOf(PaymentAuthorized.class);
    }

    @Test
    void authorizeRejectedFromNonInitiatedState() {
        Authorization authorized = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0)
                .authorize(T1).next();

        assertThatThrownBy(() -> authorized.authorize(T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires Initiated");
    }

    @Test
    void failAuthorizationMovesToAuthFailedWithReason() {
        Authorization a = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0);

        AuthorizationResult r = a.failAuthorization("network declined", T1);

        assertThat(r.next().status()).isInstanceOf(AuthFailed.class);
        assertThat(((AuthFailed) r.next().status()).reason()).isEqualTo("network declined");
        assertThat(r.events()).hasSize(1).first().isInstanceOf(AuthorizationFailed.class);
    }

    @Test
    void voidMovesAuthorizedToVoidedWhenNoCaptures() {
        Authorization a = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0)
                .authorize(T1).next();

        AuthorizationResult r = a.voidAuthorization(T1);

        assertThat(r.next().status()).isInstanceOf(Voided.class);
        assertThat(r.events()).hasSize(1).first().isInstanceOf(AuthorizationVoided.class);
    }

    @Test
    void voidRejectedWhenCapturesExist() {
        Authorization a = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0)
                .authorize(T1).next()
                .capture(new Money(new BigDecimal("50.00"), "USD"), T1).next();

        assertThatThrownBy(() -> a.voidAuthorization(T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("captures");
    }

    @Test
    void expireMovesAuthorizedToExpiredAfterExpiresAt() {
        Authorization a = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0)
                .authorize(T1).next();
        Instant after = EXPIRES_AT.plusSeconds(1);

        AuthorizationResult r = a.expire(after);

        assertThat(r.next().status()).isInstanceOf(Expired.class);
        assertThat(r.events()).hasSize(1).first().isInstanceOf(AuthorizationExpired.class);
    }

    @Test
    void expireRejectedBeforeExpiresAt() {
        Authorization a = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0)
                .authorize(T1).next();
        Instant before = EXPIRES_AT.minusSeconds(1);

        assertThatThrownBy(() -> a.expire(before))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiredAt");
    }

    @Test
    void captureAddsPendingCapture() {
        Authorization a = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0)
                .authorize(T1).next();

        AuthorizationResult r = a.capture(new Money(new BigDecimal("30.00"), "USD"), T1);

        assertThat(r.next().captures()).hasSize(1);
        assertThat(r.next().captures().get(0).status()).isEqualTo(CaptureStatus.PENDING);
        assertThat(r.events()).hasSize(1).first().isInstanceOf(PaymentCaptured.class);
    }

    @Test
    void captureRejectedWhenSumWouldExceedAuthorized() {
        // The domain doesn't expose a "succeed a capture" transition yet
        // (that arrives with the network simulator in a later turn).
        // For now, hand-build an Authorization carrying two SUCCEEDED
        // captures totalling 80, and verify the invariant kicks in when
        // a third capture would push the total over 100.
        Authorization a = Authorization.initiate(MERCHANT, AMOUNT, EXPIRES_AT, T0)
                .authorize(T1).next();
        Capture c1 = new Capture(CaptureId.generate(),
                new Money(new BigDecimal("40.00"), "USD"),
                CaptureStatus.SUCCEEDED, T1, T1);
        Capture c2 = new Capture(CaptureId.generate(),
                new Money(new BigDecimal("40.00"), "USD"),
                CaptureStatus.SUCCEEDED, T1, T1);
        Authorization withCaptures = new Authorization(
                a.id(), a.merchantId(), a.authorizedAmount(),
                a.expiresAt(), a.status(), List.of(c1, c2));

        assertThatThrownBy(() -> withCaptures.capture(
                new Money(new BigDecimal("30.00"), "USD"), T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds");
    }
}
