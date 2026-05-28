package com.example.fintech.transactions.unit;

import com.example.fintech.transactions.domain.model.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void plus_addsAmounts() {
        Money result = Money.of(100, "USD").plus(Money.of(50, "USD"));
        assertThat(result.amount()).isEqualTo(150);
    }

    @Test
    void plus_rejectsDifferentCurrencies() {
        assertThatThrownBy(() -> Money.of(100, "USD").plus(Money.of(50, "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void minus_subtractsAmounts() {
        Money result = Money.of(100, "USD").minus(Money.of(40, "USD"));
        assertThat(result.amount()).isEqualTo(60);
    }

    @Test
    void constructor_rejectsNegative() {
        assertThatThrownBy(() -> Money.of(-1, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isLessThan_comparesAmounts() {
        assertThat(Money.of(100, "USD").isLessThan(Money.of(200, "USD"))).isTrue();
        assertThat(Money.of(200, "USD").isLessThan(Money.of(100, "USD"))).isFalse();
    }
}
