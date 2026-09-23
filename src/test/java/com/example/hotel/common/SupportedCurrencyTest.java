package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.entity.booking.PaymentCurrency;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the single source of the V1 supported currency set and its monetary precision rules: which
 * currencies exist, which staff-entered amounts are exact enough to accept, and how a calculated
 * amount is normalized.
 */
class SupportedCurrencyTest {

    /** Confirms V1 supports exactly VND and USD. */
    @Test
    void shouldSupportExactlyVndAndUsd() {
        assertEquals(List.of(SupportedCurrency.VND, SupportedCurrency.USD),
                Arrays.asList(SupportedCurrency.values()));
    }

    /** Confirms the approved V1 fraction digits. */
    @Test
    void shouldExposeApprovedFractionDigits() {
        assertEquals(0, SupportedCurrency.VND.fractionDigits());
        assertEquals(2, SupportedCurrency.USD.fractionDigits());
    }

    /**
     * Confirms the Payment tender currencies and the Reservation currencies stay the same set, so a
     * Payment can never be tendered in a currency the Folio rules do not know.
     */
    @Test
    void shouldCoverEveryPaymentTenderCurrency() {
        assertEquals(
                Arrays.stream(PaymentCurrency.values()).map(Enum::name).toList(),
                Arrays.stream(SupportedCurrency.values()).map(Enum::name).toList());
    }

    /** Confirms supported codes resolve and unsupported ones do not. */
    @ParameterizedTest
    @ValueSource(strings = {"VND", "USD"})
    void shouldResolveSupportedCode(String code) {
        assertEquals(Optional.of(SupportedCurrency.valueOf(code)), SupportedCurrency.find(code));
    }

    /** Confirms any other ISO 4217 code, a malformed code, lower case and null are all unsupported. */
    @ParameterizedTest
    @ValueSource(strings = {"EUR", "JPY", "GBP", "XYZ", "vnd", "", "VNDD"})
    void shouldRejectUnsupportedCode(String code) {
        assertEquals(Optional.empty(), SupportedCurrency.find(code));
    }

    /** Confirms a null code is unsupported rather than throwing. */
    @Test
    void shouldTreatNullCodeAsUnsupported() {
        assertEquals(Optional.empty(), SupportedCurrency.find(null));
    }

    /**
     * Confirms which staff-entered amounts fit each currency. Trailing zeros are insignificant, so
     * "20.50" is exact USD and "1000" is exact VND, while a real fractional dong is not.
     */
    @ParameterizedTest
    @CsvSource({
        "VND, 1000, true",
        "VND, 1000.00, true",
        "VND, 0.5, false",
        "VND, 1000.5, false",
        "VND, 100.123456, false",
        "USD, 20, true",
        "USD, 20.5, true",
        "USD, 20.50, true",
        "USD, 20.500, true",
        "USD, 20.501, false",
        "USD, 39.999960, false"
    })
    void shouldAcceptOnlyAmountsExpressibleInTheCurrency(String code, BigDecimal amount, boolean valid) {
        assertEquals(valid, SupportedCurrency.valueOf(code).hasValidPrecision(amount));
    }

    /** Confirms a null amount never counts as valid precision. */
    @Test
    void shouldTreatNullAmountAsInvalidPrecision() {
        assertFalse(SupportedCurrency.VND.hasValidPrecision(null));
        assertFalse(SupportedCurrency.USD.hasValidPrecision(null));
    }

    /** Confirms a calculated amount is rounded HALF_UP to the currency's fraction digits. */
    @ParameterizedTest
    @CsvSource({
        "USD, 39.999960, 40.00",
        "USD, 119.345984, 119.35",
        "USD, 20.005, 20.01",
        "USD, 20, 20.00",
        "VND, 999999.6, 1000000",
        "VND, 0.5, 1",
        "VND, 1000000, 1000000"
    })
    void shouldNormalizeCalculatedAmountToCurrencyPrecision(String code, BigDecimal raw, String expected) {
        assertEquals(new BigDecimal(expected), SupportedCurrency.valueOf(code).normalize(raw));
    }

    /** Confirms normalizing an already-exact amount only fixes its scale and never its value. */
    @Test
    void shouldNeverChangeTheValueOfAnAlreadyExactAmount() {
        BigDecimal usd = new BigDecimal("20.5");
        assertTrue(SupportedCurrency.USD.hasValidPrecision(usd));
        assertEquals(0, usd.compareTo(SupportedCurrency.USD.normalize(usd)));
        assertEquals(2, SupportedCurrency.USD.normalize(usd).scale());

        BigDecimal vnd = new BigDecimal("1000000.00");
        assertTrue(SupportedCurrency.VND.hasValidPrecision(vnd));
        assertEquals(0, vnd.compareTo(SupportedCurrency.VND.normalize(vnd)));
        assertEquals(0, SupportedCurrency.VND.normalize(vnd).scale());
    }
}
