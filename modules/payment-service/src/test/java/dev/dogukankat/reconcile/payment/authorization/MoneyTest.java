package dev.dogukankat.reconcile.payment.authorization;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> new Money(null, "USD"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullCurrency() {
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-1.00"), "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void acceptsZeroAmount() {
        Money zero = Money.zero("USD");
        assertThat(zero.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.isPositive()).isFalse();
    }

    @Test
    void rejectsInvalidCurrencyCode() {
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, "XYZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 4217");
    }

    @Test
    void rejectsScaleAboveFour() {
        assertThatThrownBy(() -> new Money(new BigDecimal("1.00001"), "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precision");
    }

    @Test
    void normalizesScaleToFour() {
        Money m = new Money(new BigDecimal("100.50"), "USD");
        assertThat(m.amount().scale()).isEqualTo(4);
        assertThat(m.amount()).isEqualByComparingTo(new BigDecimal("100.5000"));
    }

    @Test
    void addsSameCurrency() {
        Money a = new Money(new BigDecimal("100.00"), "USD");
        Money b = new Money(new BigDecimal("50.00"), "USD");
        assertThat(a.add(b).amount()).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    void rejectsAddingDifferentCurrency() {
        Money usd = new Money(new BigDecimal("100"), "USD");
        Money eur = new Money(new BigDecimal("50"), "EUR");
        assertThatThrownBy(() -> usd.add(eur))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void compareLessThanOrEqualTo() {
        Money small = new Money(new BigDecimal("50"), "USD");
        Money big = new Money(new BigDecimal("100"), "USD");
        assertThat(small.isLessThanOrEqualTo(big)).isTrue();
        assertThat(big.isLessThanOrEqualTo(small)).isFalse();
        assertThat(big.isLessThanOrEqualTo(big)).isTrue();
    }
}
