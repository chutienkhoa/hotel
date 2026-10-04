package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.common.request.AdditionalRevenueCreateRequest;
import com.example.hotel.dto.common.request.ExpenseCreateRequest;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.common.AdditionalRevenuePaymentMethod;
import com.example.hotel.entity.common.ExpensePaymentMethod;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the numeric-field convention of the PMS: the server keeps rejecting a negative, zero or out-of-range number
 * whatever the browser let through, and no template brings back a free-text or browser-native numeric field outside the
 * shared {@code data-numeric} behaviour.
 */
class NumericFieldValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final BigDecimal TOO_MANY_DIGITS = new BigDecimal("12345678901234");
    private static final BigDecimal TOO_MANY_DECIMALS = new BigDecimal("1.1234567");

    private static boolean rejects(Object request, String property) {
        return VALIDATOR.validate(request).stream().anyMatch(v -> v.getPropertyPath().toString().endsWith(property));
    }

    private static PaymentCreateRequest payment(BigDecimal amount, BigDecimal rate) {
        return new PaymentCreateRequest(amount, PaymentCurrency.USD, rate, PaymentMethod.CASH, null);
    }

    private static ChargeCreateRequest charge(BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        return new ChargeCreateRequest(ChargeType.MINIBAR, null, quantity, unitPrice, amount);
    }

    /** Confirms a fixed Charge amount must be positive and within the stored precision. */
    @Test
    void shouldRejectInvalidChargeAmounts() {
        assertFalse(rejects(charge(null, null, new BigDecimal("1000000")), "amount"));
        assertFalse(rejects(charge(null, null, new BigDecimal("12.50")), "amount"));
        assertTrue(rejects(charge(null, null, new BigDecimal("-1")), "amount"));
        assertTrue(rejects(charge(null, null, BigDecimal.ZERO), "amount"));
        assertTrue(rejects(charge(null, null, TOO_MANY_DIGITS), "amount"));
        assertTrue(rejects(charge(null, null, TOO_MANY_DECIMALS), "amount"));
    }

    /** Confirms itemized quantity must be positive and unit price non-negative, both within the stored precision. */
    @Test
    void shouldRejectInvalidItemizedChargeValues() {
        assertFalse(rejects(charge(new BigDecimal("3"), new BigDecimal("50000"), null), "quantity"));
        assertTrue(rejects(charge(new BigDecimal("-1"), new BigDecimal("50000"), null), "quantity"));
        assertTrue(rejects(charge(BigDecimal.ZERO, new BigDecimal("50000"), null), "quantity"));
        assertTrue(rejects(charge(new BigDecimal("3"), new BigDecimal("-1"), null), "unitPrice"));
        assertTrue(rejects(charge(new BigDecimal("3"), TOO_MANY_DIGITS, null), "unitPrice"));
        assertTrue(rejects(charge(TOO_MANY_DIGITS, new BigDecimal("1"), null), "quantity"));
    }

    /** Confirms a Payment amount and exchange rate must be positive and within the stored precision. */
    @Test
    void shouldRejectInvalidPaymentAmountsAndExchangeRates() {
        assertFalse(rejects(payment(new BigDecimal("1250.75"), new BigDecimal("25000.123456")), "amount"));
        assertFalse(rejects(payment(new BigDecimal("1250.75"), new BigDecimal("25000.123456")), "exchangeRate"));
        assertTrue(rejects(payment(new BigDecimal("-5"), null), "amount"));
        assertTrue(rejects(payment(BigDecimal.ZERO, null), "amount"));
        assertTrue(rejects(payment(TOO_MANY_DIGITS, null), "amount"));
        assertTrue(rejects(payment(BigDecimal.ONE, new BigDecimal("-1")), "exchangeRate"));
        assertTrue(rejects(payment(BigDecimal.ONE, TOO_MANY_DECIMALS), "exchangeRate"));
    }

    /** Confirms Expense and Additional Revenue amounts must be positive and within the stored precision. */
    @Test
    void shouldRejectInvalidExpenseAndAdditionalRevenueAmounts() {
        for (BigDecimal bad : List.of(new BigDecimal("-1"), BigDecimal.ZERO, TOO_MANY_DIGITS)) {
            assertTrue(rejects(new ExpenseCreateRequest(UUID.randomUUID(), bad, LocalDate.now(),
                    ExpensePaymentMethod.CASH, null), "amount"), "expense " + bad);
            assertTrue(rejects(new AdditionalRevenueCreateRequest(UUID.randomUUID(), bad, LocalDate.now(),
                    AdditionalRevenuePaymentMethod.CASH, null), "amount"), "additional revenue " + bad);
        }
        assertFalse(rejects(new ExpenseCreateRequest(UUID.randomUUID(), new BigDecimal("1000000"), LocalDate.now(),
                ExpensePaymentMethod.CASH, null), "amount"));
    }

    /** Confirms a nightly rate must be positive and within the stored precision. */
    @Test
    void shouldRejectInvalidNightlyRates() {
        UUID room = UUID.randomUUID();
        assertFalse(rejects(new RoomRequest(room, new BigDecimal("1500000")), "nightlyRate"));
        assertTrue(rejects(new RoomRequest(room, new BigDecimal("-1")), "nightlyRate"));
        assertTrue(rejects(new RoomRequest(room, BigDecimal.ZERO), "nightlyRate"));
        assertTrue(rejects(new RoomRequest(room, TOO_MANY_DIGITS), "nightlyRate"));
    }

    /**
     * Confirms every template field bound to a numeric value opts in to the shared behaviour, and that no template uses
     * a browser-native number input or the retired money formatter.
     */
    @Test
    void shouldKeepEveryNumericTemplateFieldOnTheSharedBehaviour() throws IOException {
        Pattern numericField = Pattern.compile(
                "th:field=\"\\*\\{[A-Za-z.\\[\\]_]*(amount|quantity|unitPrice|exchangeRate|adultCount|childCount|nightlyRate)\\}\"");
        List<String> problems = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file);
                if (html.contains("type=\"number\"") || html.contains("js-money-input")) {
                    problems.add(file + ": browser-native number input or retired money formatter");
                }
                Matcher matcher = numericField.matcher(html);
                while (matcher.find()) {
                    int start = html.lastIndexOf("<input", matcher.start());
                    int end = html.indexOf("/>", matcher.end());
                    String input = html.substring(start, end < 0 ? html.length() : end);
                    if (!input.contains("data-numeric=")) {
                        problems.add(file + ": " + matcher.group());
                    }
                }
            }
        }
        assertEquals(List.of(), problems);
    }
}
