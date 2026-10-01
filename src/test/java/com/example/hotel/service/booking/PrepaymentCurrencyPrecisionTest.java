package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.Payment;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.mapper.booking.PaymentMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Confirms a prepayment reuses the Payment currency rules unchanged: the tender amount is validated
 * against the tender currency, the applied amount is normalized into the Reservation currency, and
 * the Reservation total cap is compared against that already-converted applied amount rather than
 * against the raw tender.
 */
class PrepaymentCurrencyPrecisionTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal RATE = new BigDecimal("25000");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final StayRepository stays = mock(StayRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final PaymentService paymentService = new PaymentService(
            payments,
            stays,
            mock(ChargeRepository.class),
            new PaymentMapper(),
            audits,
            Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZONE));
    private final PrepaymentService service = new PrepaymentService(
            reservations,
            stays,
            payments,
            paymentService,
            new PaymentMapper(),
            audits,
            Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZONE));

    private final UUID reservationId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(UUID.randomUUID(), "cashier"), null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms a USD prepayment toward a VND Reservation is credited in whole dong. */
    @Test
    void shouldNormalizeUsdPrepaymentIntoVndReservationCurrency() {
        arrange("VND", new BigDecimal("2000000"), BigDecimal.ZERO);

        PaymentResponse response = service.record(
                reservationId, request(new BigDecimal("40"), PaymentCurrency.USD, RATE));

        assertEquals(new BigDecimal("1000000"), response.appliedAmount());
        assertEquals(0, response.appliedAmount().scale());
        assertEquals(0, new BigDecimal("40").compareTo(response.amount()));
        assertEquals(PaymentCurrency.USD.name(), response.currency());
    }

    /** Confirms a VND prepayment toward a USD Reservation is credited in whole cents. */
    @Test
    void shouldNormalizeVndPrepaymentIntoUsdReservationCurrency() {
        arrange("USD", new BigDecimal("100.00"), BigDecimal.ZERO);

        PaymentResponse response = service.record(
                reservationId, request(new BigDecimal("999999"), PaymentCurrency.VND, RATE));

        // 999,999 / 25,000 is 39.99996 before normalization.
        assertEquals(new BigDecimal("40.00"), response.appliedAmount());
        assertEquals(2, response.appliedAmount().scale());
    }

    /**
     * Confirms the Reservation total cap is applied to the converted applied amount, not the tender:
     * 60 USD converts to 1,500,000 VND, which exceeds the 1,000,000 VND Reservation total even though
     * the raw tender number is far smaller.
     */
    @Test
    void shouldCapPrepaymentsUsingTheConvertedAppliedAmount() {
        arrange("VND", new BigDecimal("1000000"), BigDecimal.ZERO);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.record(
                reservationId, request(new BigDecimal("60"), PaymentCurrency.USD, RATE)));

        assertEquals(409, exception.getStatusCode().value());
        verify(payments, never()).save(any(Payment.class));
    }

    /** Confirms an existing prepayment total and a converted new one are capped together. */
    @Test
    void shouldCapPrepaymentsAgainstTheExistingActiveTotal() {
        arrange("VND", new BigDecimal("1000000"), new BigDecimal("800000"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.record(
                reservationId, request(new BigDecimal("10"), PaymentCurrency.USD, RATE)));

        assertEquals(409, exception.getStatusCode().value());
        verify(payments, never()).save(any(Payment.class));
    }

    /** Confirms a converted prepayment that exactly reaches the Reservation total is accepted. */
    @Test
    void shouldAcceptAConvertedPrepaymentThatExactlyReachesTheReservationTotal() {
        arrange("VND", new BigDecimal("1000000"), BigDecimal.ZERO);

        PaymentResponse response = service.record(
                reservationId, request(new BigDecimal("40"), PaymentCurrency.USD, RATE));

        assertEquals(new BigDecimal("1000000"), response.appliedAmount());
    }

    /** Confirms a prepayment tender amount is validated against the currency actually received. */
    @Test
    void shouldRejectPrepaymentTenderExceedingItsOwnCurrencyPrecision() {
        arrange("VND", new BigDecimal("2000000"), BigDecimal.ZERO);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.record(
                reservationId, request(new BigDecimal("1000.5"), PaymentCurrency.VND, null)));

        assertEquals(400, exception.getStatusCode().value());
        verify(payments, never()).save(any(Payment.class));
    }

    /**
     * Stubs a CONFIRMED Reservation with no Stay yet and its current active prepayment total.
     *
     * @param currency Reservation currency
     * @param totalAmount Reservation total, the prepayment cap
     * @param activePrepayments already-recorded active prepayment total, in Reservation currency
     */
    private void arrange(String currency, BigDecimal totalAmount, BigDecimal activePrepayments) {
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn(reservationId);
        when(reservation.getStatus()).thenReturn(ReservationStatus.CONFIRMED);
        when(reservation.getCurrency()).thenReturn(currency);
        when(reservation.getTotalAmount()).thenReturn(totalAmount);
        when(reservations.findByIdForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(stays.existsByReservationId(reservationId)).thenReturn(false);
        when(payments.sumActivePrepaymentAppliedAmount(reservationId)).thenReturn(activePrepayments);
        when(payments.existsLiveWithReference(eq(reservationId), any(PaymentMethod.class), any(), anyList()))
                .thenReturn(false);
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Creates a prepayment request.
     *
     * @param amount tender amount
     * @param currency tender currency
     * @param exchangeRate "1 USD = rate VND", or {@code null} for a same-currency prepayment
     * @return the request
     */
    private PaymentCreateRequest request(BigDecimal amount, PaymentCurrency currency, BigDecimal exchangeRate) {
        return new PaymentCreateRequest(amount, currency, exchangeRate, PaymentMethod.CASH, null);
    }
}
