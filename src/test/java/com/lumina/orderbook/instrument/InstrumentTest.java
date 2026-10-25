package com.lumina.orderbook.instrument;

import com.lumina.orderbook.gateway.GatewayException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstrumentTest {

    private final Instrument btc = new Instrument("BTC-USD", new BigDecimal("0.01"), new BigDecimal("0.0001"),
            new BigDecimal("0.0001"), new BigDecimal("100"));

    @Test
    void convertsToAndFromScaledIntegers() {
        assertThat(btc.toTicks(new BigDecimal("50000.25"))).isEqualTo(5_000_025);
        assertThat(btc.toLots(new BigDecimal("1.5"))).isEqualTo(15_000);
        assertThat(btc.price(5_000_025)).isEqualByComparingTo("50000.25");
        assertThat(btc.quantity(15_000)).isEqualByComparingTo("1.5");
    }

    @Test
    void trailingZerosAreNotAProblem() {
        assertThat(btc.toTicks(new BigDecimal("50000.2500000"))).isEqualTo(5_000_025);
        assertThat(btc.toTicks(new BigDecimal("5E+4"))).isEqualTo(5_000_000);
    }

    @Test
    void rejectsOffTickPrices() {
        assertThatThrownBy(() -> btc.toTicks(new BigDecimal("50000.255")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("tick size");
    }

    @Test
    void rejectsOffLotAndOutOfRangeQuantities() {
        assertThatThrownBy(() -> btc.toLots(new BigDecimal("0.00015"))).hasMessageContaining("lot size");
        assertThatThrownBy(() -> btc.toLots(new BigDecimal("0.00001"))).hasMessageContaining("minimum");
        assertThatThrownBy(() -> btc.toLots(new BigDecimal("101"))).hasMessageContaining("maximum");
        assertThatThrownBy(() -> btc.toLots(BigDecimal.ZERO)).hasMessageContaining("positive");
    }

    @Test
    void rejectsNonPositivePricesAndOverflow() {
        assertThatThrownBy(() -> btc.toTicks(new BigDecimal("-1"))).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> btc.toTicks(new BigDecimal("1E+30"))).isInstanceOf(GatewayException.class);
    }
}
