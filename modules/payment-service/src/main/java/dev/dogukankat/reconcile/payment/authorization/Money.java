package dev.dogukankat.reconcile.payment.authorization;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {

    public static final int SCALE = 4;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-negative: " + amount);
        }
        if (amount.scale() > SCALE) {
            throw new IllegalArgumentException(
                    "amount precision exceeds " + SCALE + " decimal places: " + amount);
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid ISO 4217 currency code: " + currency, e);
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public boolean isLessThanOrEqualTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) <= 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
