package com.example.hotel.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Defines the currencies supported in V1 and the monetary precision that follows from each one.
 *
 * <p>The set covers both Reservation/Folio amounts and Payment tender currencies. A new Reservation is
 * VND only in V1 (enforced by {@code ReservationService}); USD remains supported as a Payment tender
 * currency and for historical Reservations.</p>
 *
 * <p>This is the single place that knows the supported currency set and its fraction digits, so
 * request validation, the Reservation/Payment/Charge services and the database CHECK constraint
 * cannot drift apart. It deliberately replaces {@code java.util.Currency}, whose default fraction
 * digits cover every ISO 4217 code rather than the fixed V1 set.</p>
 *
 * <p>Two distinct rules use these fraction digits:</p>
 *
 * <ul>
 *   <li><b>User-entered</b> amounts are checked with {@link #hasValidPrecision(BigDecimal)} and
 *       rejected when they carry more precision than the currency has, so a staff-entered value is
 *       never silently changed.</li>
 *   <li><b>Calculated</b> amounts (currency conversion, itemized pricing) are rounded with
 *       {@link #normalize(BigDecimal)} using HALF_UP, so no economically meaningless sub-minor-unit
 *       remainder can survive into a balance.</li>
 * </ul>
 */
public enum SupportedCurrency {

    /** Vietnamese dong: whole units only. */
    VND(0),

    /** United States dollar: two fraction digits. */
    USD(2);

    private final int fractionDigits;

    /**
     * Creates a supported currency with its V1 fraction digits.
     *
     * @param fractionDigits number of fraction digits a monetary amount in this currency may carry
     */
    SupportedCurrency(int fractionDigits) {
        this.fractionDigits = fractionDigits;
    }

    /**
     * Resolves a supported currency from its ISO 4217 code.
     *
     * @param code currency code, for example {@code "VND"}
     * @return the matching supported currency, or empty when the code is not supported in V1
     */
    public static Optional<SupportedCurrency> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (SupportedCurrency currency : values()) {
            if (currency.name().equals(code)) {
                return Optional.of(currency);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the number of fraction digits a monetary amount in this currency may carry.
     *
     * @return the V1 fraction digits of this currency
     */
    public int fractionDigits() {
        return fractionDigits;
    }

    /**
     * Determines whether a user-entered monetary amount fits this currency without losing value.
     *
     * <p>Trailing zeros are insignificant, so {@code 20.50} is valid USD and {@code 1000} is valid
     * VND, while {@code 20.501} and {@code 1000.5} are not.</p>
     *
     * @param amount monetary amount to check
     * @return {@code true} when the amount can be represented exactly in this currency
     */
    public boolean hasValidPrecision(BigDecimal amount) {
        return amount != null && amount.stripTrailingZeros().scale() <= fractionDigits;
    }

    /**
     * Normalizes a calculated monetary amount to this currency's fraction digits, rounding HALF_UP.
     *
     * <p>Applied to an amount that already satisfies {@link #hasValidPrecision(BigDecimal)} this
     * only fixes the representation and never changes the value.</p>
     *
     * @param amount calculated monetary amount
     * @return the amount at this currency's fraction digits
     */
    public BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(fractionDigits, RoundingMode.HALF_UP);
    }
}
