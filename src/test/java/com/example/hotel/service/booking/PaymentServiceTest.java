package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.request.PaymentVoidRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.Payment;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.mapper.booking.PaymentMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Payment v1 creation, transitions, overpayment validation, audit, and stable listing. */
class PaymentServiceTest {

    /** Clears authentication established by each test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms creation generates a pending Payment with no paid timestamp and authenticated audit user. */
    @Test
    void shouldCreatePendingPaymentForCheckedInStay() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        setCurrentUser(userId);
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = service(paymentRepository, stayRepository, chargeRepository)
                .create(stayId, request(BigDecimal.TEN, PaymentMethod.CASH, null));

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment payment = captor.getValue();
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertNull(payment.getPaidAt());
        assertEquals(userId, payment.getCreatedBy());
        assertEquals(userId, payment.getUpdatedBy());
        assertEquals(stayId, response.stayId());
        assertEquals(PaymentStatus.PENDING.name(), response.status());
    }

    /** Case 1: a VND Payment into a VND Reservation requires no exchange rate. */
    @Test
    void shouldApplyVndPaymentToVndReservationWithoutConversion() {
        PaymentResponse response =
                createPayment(new BigDecimal("3000000"), PaymentCurrency.VND, null, "VND");

        assertEquals("VND", response.currency());
        assertNull(response.exchangeRate());
        assertEquals(0, new BigDecimal("3000000").compareTo(response.appliedAmount()));
    }

    /** Case 2: a USD Payment into a USD Reservation requires no exchange rate. */
    @Test
    void shouldApplyUsdPaymentToUsdReservationWithoutConversion() {
        PaymentResponse response = createPayment(new BigDecimal("120"), PaymentCurrency.USD, null, "USD");

        assertEquals("USD", response.currency());
        assertNull(response.exchangeRate());
        assertEquals(0, new BigDecimal("120").compareTo(response.appliedAmount()));
    }

    /** Case 3: a USD Payment into a VND Reservation multiplies by the "1 USD = X VND" rate. */
    @Test
    void shouldApplyUsdPaymentToVndReservationByMultiplyingExchangeRate() {
        PaymentResponse response = createPayment(
                new BigDecimal("120"), PaymentCurrency.USD, new BigDecimal("25000"), "VND");

        assertEquals(0, new BigDecimal("3000000").compareTo(response.appliedAmount()));
    }

    /** Case 4: a VND Payment into a USD Reservation divides by the "1 USD = X VND" rate directly. */
    @Test
    void shouldApplyVndPaymentToUsdReservationByDividingExchangeRate() {
        PaymentResponse response = createPayment(
                new BigDecimal("3000000"), PaymentCurrency.VND, new BigDecimal("25000"), "USD");

        assertEquals(0, new BigDecimal("120").compareTo(response.appliedAmount()));
    }

    /**
     * Confirms a non-round exchange rate is applied via one direct BigDecimal division into the
     * Folio currency, and not via a rounded reciprocal, which would silently lose precision (e.g.
     * 1/25137 rounded to scale 6 and multiplied back would incorrectly yield exactly 120, and
     * rounding to scale 6 before rounding again to USD would round twice).
     */
    @Test
    void shouldDivideDirectlyForNonRoundExchangeRateWithoutReciprocalPrecisionLoss() {
        PaymentResponse response = createPayment(
                new BigDecimal("3000000"), PaymentCurrency.VND, new BigDecimal("25137"), "USD");

        BigDecimal expected =
                new BigDecimal("3000000").divide(new BigDecimal("25137"), 2, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(response.appliedAmount()));
        assertEquals(0, new BigDecimal("119.35").compareTo(response.appliedAmount()));
        assertNotEquals(0, new BigDecimal("120").compareTo(response.appliedAmount()));
    }

    // ------------------------------------------------- tender and Folio precision

    /** Confirms a tender amount is validated against the currency the guest actually paid in. */
    @ParameterizedTest
    @CsvSource({"VND, 1000000", "VND, 1000000.00", "USD, 20", "USD, 20.5", "USD, 20.50"})
    void shouldAcceptTenderAmountThatFitsItsOwnCurrency(PaymentCurrency currency, BigDecimal amount) {
        PaymentResponse response = createPayment(amount, currency, null, currency.name());

        assertEquals(0, amount.compareTo(response.amount()));
    }

    /**
     * Confirms a tender amount carrying more precision than its own currency is rejected rather than
     * silently rounded: fractional dong and a third USD decimal are both data-entry errors.
     */
    @ParameterizedTest
    @CsvSource({"VND, 1000.5", "VND, 0.5", "USD, 20.501", "USD, 39.999960"})
    void shouldRejectTenderAmountExceedingItsOwnCurrencyPrecision(PaymentCurrency currency, BigDecimal amount) {
        assertBadRequest(request(amount, currency, null, PaymentMethod.CASH, null));
    }

    /** Confirms a USD tender is validated as USD even when the Folio it settles is in VND. */
    @Test
    void shouldValidateTenderPrecisionAgainstTheTenderCurrencyNotTheFolio() {
        assertBadRequest(request(
                new BigDecimal("40.005"), PaymentCurrency.USD, new BigDecimal("25000"), PaymentMethod.CASH, null));
    }

    /** Confirms a USD tender applied to a VND Folio is normalized to whole dong. */
    @Test
    void shouldNormalizeUsdTenderAppliedToVndFolioToWholeDong() {
        PaymentResponse response = createPayment(
                new BigDecimal("40"), PaymentCurrency.USD, new BigDecimal("25000"), "VND");

        assertEquals(new BigDecimal("1000000"), response.appliedAmount());
        assertEquals(0, response.appliedAmount().scale());
    }

    /** Confirms a fractional dong result of a USD tender is rounded HALF_UP to whole dong. */
    @Test
    void shouldRoundUsdTenderAppliedToVndFolioHalfUp() {
        PaymentResponse response = createPayment(
                new BigDecimal("40.50"), PaymentCurrency.USD, new BigDecimal("24691.35"), "VND");

        // 40.50 x 24,691.35 = 999,999.675 VND.
        assertEquals(new BigDecimal("1000000"), response.appliedAmount());
    }

    /**
     * Confirms a VND tender applied to a USD Folio is normalized to cents, so no sub-cent remainder
     * can survive into the Folio balance. 999,999 / 25,000 is 39.99996 before normalization.
     */
    @Test
    void shouldNormalizeVndTenderAppliedToUsdFolioToCents() {
        PaymentResponse response = createPayment(
                new BigDecimal("999999"), PaymentCurrency.VND, new BigDecimal("25000"), "USD");

        assertEquals(new BigDecimal("40.00"), response.appliedAmount());
        assertEquals(2, response.appliedAmount().scale());
    }

    /** Confirms a same-currency Payment's applied amount is carried at the Folio's own precision. */
    @Test
    void shouldCarrySameCurrencyAppliedAmountAtFolioPrecision() {
        PaymentResponse usd = createPayment(new BigDecimal("20.5"), PaymentCurrency.USD, null, "USD");
        assertEquals(new BigDecimal("20.50"), usd.appliedAmount());

        PaymentResponse vnd = createPayment(new BigDecimal("1000000"), PaymentCurrency.VND, null, "VND");
        assertEquals(new BigDecimal("1000000"), vnd.appliedAmount());
    }

    /**
     * Confirms a cross-currency tender too small to register in the Folio currency is rejected rather
     * than stored as a zero-value Payment: 1 VND is worth less than a cent.
     */
    @Test
    void shouldRejectCrossCurrencyTenderTooSmallToRegisterInTheFolioCurrency() {
        assertBadRequestForStay(
                request(BigDecimal.ONE, PaymentCurrency.VND, new BigDecimal("25000"), PaymentMethod.CASH, null),
                "USD");
    }

    /** Confirms a same-currency Payment must not supply an exchange rate. */
    @Test
    void shouldRejectExchangeRateForSameCurrencyPayment() {
        assertBadRequestForStay(
                request(BigDecimal.TEN, PaymentCurrency.VND, new BigDecimal("25000"), PaymentMethod.CASH, null),
                "VND");
    }

    /** Confirms a cross-currency Payment requires an exchange rate. */
    @Test
    void shouldRejectMissingExchangeRateForCrossCurrencyPayment() {
        assertBadRequestForStay(
                request(BigDecimal.TEN, PaymentCurrency.USD, null, PaymentMethod.CASH, null), "VND");
    }

    /** Confirms a non-positive exchange rate is rejected for a cross-currency Payment. */
    @Test
    void shouldRejectNonPositiveExchangeRate() {
        assertBadRequestForStay(
                request(BigDecimal.TEN, PaymentCurrency.USD, BigDecimal.ZERO, PaymentMethod.CASH, null), "VND");
    }

    /** Confirms overpayment is rejected by comparing the applied VND amount, not the raw USD amount. */
    @Test
    void shouldRejectCrossCurrencyOverpaymentUsingAppliedAmount() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN, "VND");
        Payment payment = Payment.create(
                stay,
                new BigDecimal("200"),
                PaymentCurrency.USD,
                new BigDecimal("25000"),
                new BigDecimal("5000000.000000"),
                PaymentMethod.CASH,
                null);
        payment.audit(UUID.randomUUID());
        setCurrentUser(UUID.randomUUID());
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(new BigDecimal("3000000"));
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(BigDecimal.ZERO);

        assertConflict(() ->
                service(paymentRepository, stayRepository, chargeRepository).markPaid(payment.getId()));

        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    /** Confirms refund preserves every field of the immutable currency/applied-amount snapshot. */
    @Test
    void shouldRefundCrossCurrencyPaymentWithoutChangingItsSnapshot() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN, "VND");
        Payment payment = Payment.create(
                stay,
                new BigDecimal("120"),
                PaymentCurrency.USD,
                new BigDecimal("25000"),
                new BigDecimal("3000000.000000"),
                PaymentMethod.CASH,
                "REF-1");
        payment.audit(UUID.randomUUID());
        payment.markPaid(Instant.now());
        setCurrentUser(UUID.randomUUID());
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        BigDecimal amount = payment.getAmount();
        PaymentCurrency currency = payment.getCurrency();
        BigDecimal exchangeRate = payment.getExchangeRate();
        BigDecimal appliedAmount = payment.getAppliedAmount();
        var paidAt = payment.getPaidAt();

        service(paymentRepository, stayRepository, chargeRepository)
                .refund(payment.getId(), new PaymentRefundRequest("Guest requested refund"));

        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(amount, payment.getAmount());
        assertEquals(currency, payment.getCurrency());
        assertEquals(exchangeRate, payment.getExchangeRate());
        assertEquals(appliedAmount, payment.getAppliedAmount());
        assertEquals(paidAt, payment.getPaidAt());
    }

    /** Confirms a Payment cannot be created for a checked-out Stay. */
    @Test
    void shouldRejectPaymentCreationForCheckedOutStay() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_OUT);
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        setCurrentUser(UUID.randomUUID());

        assertConflict(() -> service(paymentRepository, stayRepository, mock(ChargeRepository.class))
                .create(stayId, request(BigDecimal.ONE, PaymentMethod.CASH, null)));
    }

    /** Confirms Payment creation rejects a missing Stay. */
    @Test
    void shouldRejectPaymentCreationForMissingStay() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        when(stayRepository.findById(stayId)).thenReturn(Optional.empty());
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.empty());
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service(paymentRepository, stayRepository, mock(ChargeRepository.class))
                        .create(stayId, request(BigDecimal.ONE, PaymentMethod.CASH, null)));

        assertEquals(404, exception.getStatusCode().value());
    }

    /** Confirms every non-OTA approved Payment method is accepted and an optional reference may be absent. */
    @ParameterizedTest
    @MethodSource("nonOtaPaymentMethods")
    void shouldAcceptApprovedMethodAndOptionalReference(PaymentMethod method) {
        PaymentResponse response = createValidPayment(method, null);

        assertEquals(method.name(), response.method());
        assertNull(response.reference());
    }

    /** Confirms OTA is rejected with a null reference. */
    @Test
    void shouldRejectOtaPaymentWithNullReference() {
        assertBadRequest(request(BigDecimal.TEN, PaymentMethod.OTA, null));
    }

    /** Confirms OTA is rejected with a blank reference. */
    @Test
    void shouldRejectOtaPaymentWithBlankReference() {
        assertBadRequest(request(BigDecimal.TEN, PaymentMethod.OTA, "   "));
    }

    /** Confirms OTA is accepted once a non-blank reference is supplied. */
    @Test
    void shouldAcceptOtaPaymentWithReference() {
        PaymentResponse response = createValidPayment(PaymentMethod.OTA, "OTA-REF-1");

        assertEquals(PaymentMethod.OTA.name(), response.method());
        assertEquals("OTA-REF-1", response.reference());
    }

    /** Confirms the OTA reference rule also applies to the direct record-paid operation. */
    @Test
    void shouldRejectOtaRecordPaidWithoutReference() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service(paymentRepository, stayRepository, chargeRepository)
                        .recordPaid(stayId, request(BigDecimal.TEN, PaymentMethod.OTA, null)));

        assertEquals(400, exception.getStatusCode().value());
    }

    /** Confirms direct service callers cannot create a non-positive Payment amount. */
    @Test
    void shouldRejectNonPositiveAmount() {
        assertBadRequest(request(BigDecimal.ZERO, PaymentMethod.CASH, null));
    }

    /** Confirms the create request excludes all server-controlled fields. */
    @Test
    void shouldNotExposeServerControlledFieldsInCreateRequest() {
        List<String> components = Arrays.stream(PaymentCreateRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertFalse(components.contains("id"));
        assertFalse(components.contains("stayId"));
        assertFalse(components.contains("appliedAmount"));
        assertFalse(components.contains("status"));
        assertFalse(components.contains("paidAt"));
        assertFalse(components.contains("createdAt"));
        assertFalse(components.contains("createdBy"));
        assertFalse(components.contains("updatedAt"));
        assertFalse(components.contains("updatedBy"));
    }

    /** Confirms mark-paid sets the backend payment time when the payment remains within total charges. */
    @Test
    void shouldMarkPendingPaymentPaidWithoutOverpayment() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("90"));

        PaymentResponse response = fixture.service().markPaid(fixture.payment().getId());

        assertEquals(PaymentStatus.PAID, fixture.payment().getStatus());
        assertNotNull(fixture.payment().getPaidAt());
        assertEquals(PaymentStatus.PAID.name(), response.status());
        verify(fixture.stayRepository()).findByIdForUpdate(fixture.stay().getId());
    }

    /** Confirms an overpayment leaves the pending Payment state and paid timestamp unchanged. */
    @Test
    void shouldRejectMarkPaidWhenItWouldExceedCharges() {
        Fixture fixture = transitionFixture(new BigDecimal("11"), new BigDecimal("100"), new BigDecimal("90"));

        assertConflict(() -> fixture.service().markPaid(fixture.payment().getId()));

        assertEquals(PaymentStatus.PENDING, fixture.payment().getStatus());
        assertNull(fixture.payment().getPaidAt());
    }

    /** Confirms pending Payments may fail but failed Payments cannot subsequently become paid. */
    @Test
    void shouldMarkPendingPaymentFailedAndRejectLaterMarkPaid() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);

        fixture.service().markFailed(fixture.payment().getId());

        assertEquals(PaymentStatus.FAILED, fixture.payment().getStatus());
        assertNull(fixture.payment().getPaidAt());
        assertConflict(() -> fixture.service().markPaid(fixture.payment().getId()));
    }

    /** Confirms refunds use the same Payment record and preserve amount and original paid time. */
    @Test
    void shouldRefundPaidPaymentWithoutChangingAmountOrPaidAt() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markPaid(fixture.payment().getId());
        BigDecimal amount = fixture.payment().getAmount();
        var paidAt = fixture.payment().getPaidAt();

        fixture.service().refund(fixture.payment().getId(), new PaymentRefundRequest("Guest requested refund"));

        assertEquals(PaymentStatus.REFUNDED, fixture.payment().getStatus());
        assertEquals(amount, fixture.payment().getAmount());
        assertEquals(paidAt, fixture.payment().getPaidAt());
        assertConflict(() -> fixture.service().markPaid(fixture.payment().getId()));
    }

    /** Confirms a closed Folio rejects the pending-to-paid Payment transition. */
    @Test
    void shouldRejectMarkPaidForCheckedOutStay() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        when(fixture.stay().getStatus()).thenReturn(StayStatus.CHECKED_OUT);

        assertConflict(() -> fixture.service().markPaid(fixture.payment().getId()));
    }

    /** Confirms a closed Folio rejects the pending-to-failed Payment transition. */
    @Test
    void shouldRejectMarkFailedForCheckedOutStay() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        when(fixture.stay().getStatus()).thenReturn(StayStatus.CHECKED_OUT);

        assertConflict(() -> fixture.service().markFailed(fixture.payment().getId()));
    }

    /** Confirms a closed Folio rejects the paid-to-refunded Payment transition. */
    @Test
    void shouldRejectRefundForCheckedOutStay() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.payment().markPaid(Instant.now());
        when(fixture.stay().getStatus()).thenReturn(StayStatus.CHECKED_OUT);

        assertConflict(() -> fixture.service()
                .refund(fixture.payment().getId(), new PaymentRefundRequest("Guest requested refund")));
    }

    /** Confirms listing uses the repository result scoped to the requested Stay. */
    @Test
    void shouldListOnlyPaymentsForRequestedStay() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        Payment first = Payment.create(
                stay, BigDecimal.ONE, PaymentCurrency.VND, null, BigDecimal.ONE, PaymentMethod.CASH, null);
        Payment second = Payment.create(
                stay, BigDecimal.TEN, PaymentCurrency.VND, null, BigDecimal.TEN, PaymentMethod.OTHER, "REF");
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.findByStayIdOrderByCreatedAtAscIdAsc(stayId)).thenReturn(List.of(first, second));

        List<PaymentResponse> payments = service(paymentRepository, stayRepository, mock(ChargeRepository.class))
                .findByStayId(stayId);

        assertEquals(List.of(first.getId(), second.getId()), payments.stream().map(PaymentResponse::id).toList());
    }

    /** Confirms record-paid creates an already-PAID Payment atomically, with no PENDING intermediate. */
    @Test
    void shouldRecordPaidPaymentAtomicallyWithNoPendingIntermediateState() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(new BigDecimal("100"));
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(BigDecimal.ZERO);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = service(paymentRepository, stayRepository, chargeRepository)
                .recordPaid(stayId, request(BigDecimal.TEN, PaymentMethod.CASH, null));

        assertEquals(PaymentStatus.PAID.name(), response.status());
        assertNotNull(response.paidAt());
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertEquals(PaymentStatus.PAID, captor.getValue().getStatus());
        // save() is invoked exactly once for the whole operation: no separate PENDING-creating
        // save precedes it, so no PENDING state is ever committed for this Payment.
        verify(paymentRepository, org.mockito.Mockito.times(1)).save(any(Payment.class));
    }

    /** Confirms record-paid writes exactly one RECORD_PAYMENT audit entry targeting the Reservation. */
    @Test
    void shouldWriteRecordPaymentAuditLogOnRecordPaid() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        UUID stayId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        setCurrentUser(userId);
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(new BigDecimal("100"));
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(BigDecimal.ZERO);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(paymentRepository, stayRepository, chargeRepository, auditLogRepository, Clock.systemDefaultZone())
                .recordPaid(stayId, request(BigDecimal.TEN, PaymentMethod.CASH, null));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals("RECORD_PAYMENT", audit.getAction());
        assertEquals(stay.getReservation().getId(), audit.getEntityId());
        assertEquals(userId, audit.getUserId());
    }

    /** Confirms mark-paid (the two-step confirmation flow) also writes exactly one RECORD_PAYMENT entry. */
    @Test
    void shouldWriteRecordPaymentAuditLogOnMarkPaid() {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        Fixture fixture = transitionFixture(
                BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("90"), auditLogRepository);

        fixture.service().markPaid(fixture.payment().getId());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertEquals("RECORD_PAYMENT", captor.getValue().getAction());
    }

    /** Confirms creating a still-PENDING Payment writes no RECORD_PAYMENT entry: it is not yet recorded as paid. */
    @Test
    void shouldNotWriteRecordPaymentAuditLogForPendingCreation() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(paymentRepository, stayRepository, chargeRepository, auditLogRepository, Clock.systemDefaultZone())
                .create(stayId, request(BigDecimal.TEN, PaymentMethod.CASH, null));

        verify(auditLogRepository, org.mockito.Mockito.never()).save(any(AuditLog.class));
    }

    /** Confirms record-paid derives paidAt from the injected authoritative Clock, not wall-clock time. */
    @Test
    void shouldRecordPaidUsingAuthoritativeClock() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(new BigDecimal("100"));
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(BigDecimal.ZERO);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Instant fixedInstant = Instant.parse("2026-09-17T03:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("Asia/Ho_Chi_Minh"));

        PaymentResponse response = service(
                        paymentRepository, stayRepository, chargeRepository, mock(AuditLogRepository.class), fixedClock)
                .recordPaid(stayId, request(BigDecimal.TEN, PaymentMethod.CASH, null));

        assertEquals(fixedInstant, response.paidAt());
    }

    /** Confirms record-paid applies the same-currency rule (no conversion). */
    @Test
    void shouldRecordPaidSameCurrencyWithoutConversion() {
        PaymentResponse response = recordPaidPayment(new BigDecimal("50"), PaymentCurrency.VND, null, "VND");

        assertNull(response.exchangeRate());
        assertEquals(0, new BigDecimal("50").compareTo(response.appliedAmount()));
    }

    /** Confirms record-paid applies the same cross-currency conversion as pending creation. */
    @Test
    void shouldRecordPaidCrossCurrencyUsingExchangeRate() {
        PaymentResponse response =
                recordPaidPayment(new BigDecimal("120"), PaymentCurrency.USD, new BigDecimal("25000"), "VND");

        assertEquals(0, new BigDecimal("3000000").compareTo(response.appliedAmount()));
    }

    /** Confirms record-paid rejects an amount that would exceed total charges. */
    @Test
    void shouldRejectRecordPaidOverpayment() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(new BigDecimal("100"));
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(new BigDecimal("90"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service(paymentRepository, stayRepository, chargeRepository)
                        .recordPaid(stayId, request(new BigDecimal("11"), PaymentMethod.CASH, null)));

        assertEquals(409, exception.getStatusCode().value());
        verify(paymentRepository, org.mockito.Mockito.never()).save(any(Payment.class));
    }

    /** Confirms record-paid rejects a Stay that is not currently checked in. */
    @Test
    void shouldRejectRecordPaidForNonCheckedInStay() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_OUT);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));

        assertConflict(() -> service(paymentRepository, stayRepository, chargeRepository)
                .recordPaid(stayId, request(BigDecimal.TEN, PaymentMethod.CASH, null)));
        verify(paymentRepository, org.mockito.Mockito.never()).save(any(Payment.class));
    }

    /** Confirms record-paid rejects a non-positive amount, same as pending creation. */
    @Test
    void shouldRejectRecordPaidNonPositiveAmount() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service(paymentRepository, stayRepository, chargeRepository)
                        .recordPaid(UUID.randomUUID(), request(BigDecimal.ZERO, PaymentMethod.CASH, null)));

        assertEquals(400, exception.getStatusCode().value());
    }

    /**
     * Confirms two staff attempting to record-paid the same remaining Outstanding concurrently
     * cannot both succeed: {@code recordPaid} locks the Stay via the same
     * {@code findByIdForUpdate} pessimistic-write path {@code markPaid} already relies on, then
     * recomputes totals under that lock before allowing PAID. This test simulates the second
     * attempt observing the first attempt's already-applied total (as the real pessimistic lock
     * would serialize it to see), which is the same technique the existing
     * {@code shouldRejectMarkPaidWhenItWouldExceedCharges} test uses; true multi-threaded/DB-level
     * lock contention is out of reach for a Mockito unit test and is not claimed here.
     */
    @Test
    void shouldRejectSecondConcurrentRecordPaidThatWouldExceedRemainingOutstanding() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(new BigDecimal("1000000"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // First transaction observes zero already paid and succeeds.
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(BigDecimal.ZERO, new BigDecimal("1000000"));

        PaymentResponse first = service(paymentRepository, stayRepository, chargeRepository)
                .recordPaid(stayId, request(new BigDecimal("1000000"), PaymentMethod.CASH, null));
        assertEquals(PaymentStatus.PAID.name(), first.status());

        // Second transaction, now observing the first's already-applied total under the same
        // lock-then-recompute pattern, must be rejected rather than allowed to overpay.
        assertConflict(() -> service(paymentRepository, stayRepository, chargeRepository)
                .recordPaid(stayId, request(new BigDecimal("1000000"), PaymentMethod.CASH, null)));
    }

    /**
     * Confirms the approved Stay-level duplicate guard: the first record-paid with a non-blank
     * method+reference succeeds, and a second identical live Payment for the same Reservation is
     * rejected with a conflict and never persisted.
     */
    @Test
    void shouldRejectSecondLivePaymentWithTheSameMethodAndReference() {
        DuplicateFixture fixture = duplicateFixture();

        PaymentResponse first = fixture.service().recordPaid(
                fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.BANK_TRANSFER, "FT123"));
        assertEquals(PaymentStatus.PAID.name(), first.status());
        fixture.markReferenceLive(PaymentMethod.BANK_TRANSFER, "FT123");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().recordPaid(
                        fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.BANK_TRANSFER, "FT123")));

        assertEquals(409, exception.getStatusCode().value());
        assertEquals(
                "A payment with the same method and reference already exists for this reservation",
                exception.getReason());
        verify(fixture.paymentRepository(), org.mockito.Mockito.times(1)).save(any(Payment.class));
    }

    /**
     * Confirms the duplicate rejection does not depend on amounts: it still applies while the
     * aggregate would stay far below Total Charges, so it can never be mistaken for the
     * overpayment guard.
     */
    @Test
    void shouldRejectDuplicateReferenceEvenWhenTotalChargesWouldStillCoverIt() {
        DuplicateFixture fixture = duplicateFixture();
        fixture.markReferenceLive(PaymentMethod.BANK_TRANSFER, "FT123");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> fixture.service().recordPaid(
                        fixture.stayId(), request(new BigDecimal("1"), PaymentMethod.BANK_TRANSFER, "FT123")));

        assertEquals(409, exception.getStatusCode().value());
        verify(fixture.paymentRepository(), org.mockito.Mockito.never()).save(any(Payment.class));
    }

    /** Confirms surrounding whitespace never lets the same real-world reference through twice. */
    @Test
    void shouldTreatSurroundingWhitespaceAsTheSameReference() {
        DuplicateFixture fixture = duplicateFixture();
        fixture.markReferenceLive(PaymentMethod.BANK_TRANSFER, "FT123");

        assertConflict(() -> fixture.service().recordPaid(
                fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.BANK_TRANSFER, "  FT123  ")));
    }

    /**
     * Confirms the guard is scoped to the approved tuple: the SAME reference text under a DIFFERENT
     * method is a different real-world payment and stays allowed.
     */
    @Test
    void shouldAllowTheSameReferenceTextUnderADifferentMethod() {
        DuplicateFixture fixture = duplicateFixture();
        fixture.markReferenceLive(PaymentMethod.BANK_TRANSFER, "FT123");

        PaymentResponse response = fixture.service().recordPaid(
                fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.CREDIT_CARD, "FT123"));

        assertEquals(PaymentStatus.PAID.name(), response.status());
        assertEquals("FT123", response.reference());
    }

    /** Confirms blank and absent references never collide, so repeated cash payments stay possible. */
    @Test
    void shouldAllowRepeatedPaymentsWithABlankOrAbsentReference() {
        DuplicateFixture fixture = duplicateFixture();

        assertEquals(PaymentStatus.PAID.name(), fixture.service()
                .recordPaid(fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.CASH, null)).status());
        assertEquals(PaymentStatus.PAID.name(), fixture.service()
                .recordPaid(fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.CASH, "   ")).status());
        assertEquals(PaymentStatus.PAID.name(), fixture.service()
                .recordPaid(fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.CASH, null)).status());

        verify(fixture.paymentRepository(), org.mockito.Mockito.times(3)).save(any(Payment.class));
        verify(fixture.paymentRepository(), org.mockito.Mockito.never())
                .existsLiveWithReference(any(), any(), any(), any());
    }

    /**
     * Confirms a corrected Payment behaves consistently with the existing
     * {@code existsLiveWithReference} semantics: a VOIDED, REFUNDED or FAILED Payment is not live,
     * so the corrected entry may reuse the same real-world method and reference.
     */
    @Test
    void shouldAllowReusingTheReferenceOfACorrectedNonLivePayment() {
        DuplicateFixture fixture = duplicateFixture();

        PaymentResponse corrected = fixture.service().recordPaid(
                fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.BANK_TRANSFER, "FT999"));

        assertEquals(PaymentStatus.PAID.name(), corrected.status());
        // The repository's live definition excludes FAILED/REFUNDED/VOIDED, so after the erroneous row is
        // voided or refunded it no longer reports a live duplicate and the corrected entry is accepted.
        assertEquals(PaymentStatus.PAID.name(), fixture.service().recordPaid(
                fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.BANK_TRANSFER, "FT999")).status());
        verify(fixture.paymentRepository(), org.mockito.Mockito.times(2)).existsLiveWithReference(
                fixture.reservationId(),
                PaymentMethod.BANK_TRANSFER,
                "FT999",
                List.of(PaymentStatus.FAILED, PaymentStatus.REFUNDED, PaymentStatus.VOIDED));
    }

    /** Confirms the PENDING creation boundary applies the same duplicate guard as record-paid. */
    @Test
    void shouldRejectDuplicateReferenceWhenCreatingAPendingPayment() {
        DuplicateFixture fixture = duplicateFixture();
        fixture.markReferenceLive(PaymentMethod.BANK_TRANSFER, "FT500");

        assertConflict(() -> fixture.service()
                .create(fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.BANK_TRANSFER, "FT500")));
        verify(fixture.paymentRepository(), org.mockito.Mockito.never()).save(any(Payment.class));
    }

    /** Confirms the duplicate check happens only under the Stay lock the folio boundary already takes. */
    @Test
    void shouldCheckForDuplicatesOnlyAfterLockingTheStay() {
        DuplicateFixture fixture = duplicateFixture();

        fixture.service().recordPaid(
                fixture.stayId(), request(new BigDecimal("100"), PaymentMethod.BANK_TRANSFER, "FT777"));

        var order = org.mockito.Mockito.inOrder(fixture.stayRepository(), fixture.paymentRepository());
        order.verify(fixture.stayRepository()).findByIdForUpdate(fixture.stayId());
        order.verify(fixture.paymentRepository())
                .existsLiveWithReference(any(), any(), any(), any());
        order.verify(fixture.paymentRepository()).save(any(Payment.class));
    }

    /** Confirms refund rejects a blank reason. */
    @Test
    void shouldRejectRefundWithBlankReason() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markPaid(fixture.payment().getId());

        assertBadRequestForOperation(
                () -> fixture.service().refund(fixture.payment().getId(), new PaymentRefundRequest("   ")));
        assertEquals(PaymentStatus.PAID, fixture.payment().getStatus());
    }

    /** Confirms refund rejects a missing reason. */
    @Test
    void shouldRejectRefundWithNullReason() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markPaid(fixture.payment().getId());

        assertBadRequestForOperation(() -> fixture.service().refund(fixture.payment().getId(), null));
        assertEquals(PaymentStatus.PAID, fixture.payment().getStatus());
    }

    /** Confirms a valid refund reason is trimmed and persisted on the same Payment row. */
    @Test
    void shouldPersistTrimmedRefundReason() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markPaid(fixture.payment().getId());

        fixture.service().refund(fixture.payment().getId(), new PaymentRefundRequest("  Guest cancelled  "));

        assertEquals("Guest cancelled", fixture.payment().getRefundReason());
    }

    /**
     * Confirms refund records the refunding user as updatedBy, the authoritative refund-user
     * metadata (no separate refundedBy field is added). {@code updatedAt} is populated by the JPA
     * {@code @PreUpdate} lifecycle callback, which only fires under a real persistence context, so
     * it is not independently observable in this Mockito-only unit test and is not asserted here.
     */
    @Test
    void shouldRetainUpdatedByAsRefundAuditMetadata() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markPaid(fixture.payment().getId());
        UUID refunderId = UUID.randomUUID();
        setCurrentUser(refunderId);

        fixture.service().refund(fixture.payment().getId(), new PaymentRefundRequest("Guest cancelled"));

        assertEquals(refunderId, fixture.payment().getUpdatedBy());
    }

    /** Confirms a successful refund writes the approved REFUND_PAYMENT audit entry. */
    @Test
    void shouldWriteRefundPaymentAuditLogOnSuccessfulRefund() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        Payment payment = Payment.create(
                stay, BigDecimal.TEN, PaymentCurrency.VND, null, BigDecimal.TEN, PaymentMethod.CASH, null);
        payment.audit(UUID.randomUUID());
        payment.markPaid(Instant.now());
        setCurrentUser(UUID.randomUUID());
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(paymentRepository, stayRepository, chargeRepository, auditLogRepository, Clock.systemDefaultZone())
                .refund(payment.getId(), new PaymentRefundRequest("Guest cancelled"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        // Exactly one audit row: refund must never also write a RECORD_PAYMENT entry.
        verify(auditLogRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertEquals("REFUND_PAYMENT", captor.getValue().getAction());
    }

    /** Confirms a PAID Payment can be voided, distinct from refund: VOIDED status, voidReason, VOID_PAYMENT audit. */
    @Test
    void shouldVoidPaidPayment() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markPaid(fixture.payment().getId());

        PaymentResponse response = fixture.service()
                .voidPayment(fixture.payment().getId(), new PaymentVoidRequest("Wrong amount entered"));

        assertEquals(PaymentStatus.VOIDED, fixture.payment().getStatus());
        assertEquals("Wrong amount entered", fixture.payment().getVoidReason());
        assertNull(fixture.payment().getRefundReason());
        assertEquals("VOIDED", response.status());
    }

    /** Confirms void rejects a blank reason without transitioning the Payment. */
    @Test
    void shouldRejectVoidWithBlankReason() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markPaid(fixture.payment().getId());

        assertBadRequestForOperation(
                () -> fixture.service().voidPayment(fixture.payment().getId(), new PaymentVoidRequest("   ")));
        assertEquals(PaymentStatus.PAID, fixture.payment().getStatus());
    }

    /** Confirms a PENDING Payment cannot be voided (only PAID can). */
    @Test
    void shouldRejectVoidForPendingPayment() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);

        assertConflict(() -> fixture.service()
                .voidPayment(fixture.payment().getId(), new PaymentVoidRequest("mistake")));
        assertEquals(PaymentStatus.PENDING, fixture.payment().getStatus());
    }

    /** Confirms a FAILED Payment cannot be voided. */
    @Test
    void shouldRejectVoidForFailedPayment() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markFailed(fixture.payment().getId());

        assertConflict(() -> fixture.service()
                .voidPayment(fixture.payment().getId(), new PaymentVoidRequest("mistake")));
        assertEquals(PaymentStatus.FAILED, fixture.payment().getStatus());
    }

    /** Confirms a REFUNDED Payment cannot be voided: refund and void never cross into each other. */
    @Test
    void shouldRejectVoidForRefundedPayment() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markPaid(fixture.payment().getId());
        fixture.service().refund(fixture.payment().getId(), new PaymentRefundRequest("Guest requested refund"));

        assertConflict(() -> fixture.service()
                .voidPayment(fixture.payment().getId(), new PaymentVoidRequest("mistake")));
        assertEquals(PaymentStatus.REFUNDED, fixture.payment().getStatus());
    }

    /** Confirms an already-VOIDED Payment cannot be voided again (terminal state, prevents double-submit). */
    @Test
    void shouldRejectVoidingAlreadyVoidedPayment() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.service().markPaid(fixture.payment().getId());
        fixture.service().voidPayment(fixture.payment().getId(), new PaymentVoidRequest("first void"));

        assertConflict(() -> fixture.service()
                .voidPayment(fixture.payment().getId(), new PaymentVoidRequest("second attempt")));
        assertEquals(PaymentStatus.VOIDED, fixture.payment().getStatus());
    }

    /** Confirms void is rejected once the owning Stay has checked out (post-checkout immutability). */
    @Test
    void shouldRejectVoidForCheckedOutStay() {
        Fixture fixture = transitionFixture(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        fixture.payment().markPaid(Instant.now());
        when(fixture.stay().getStatus()).thenReturn(StayStatus.CHECKED_OUT);

        assertConflict(() -> fixture.service()
                .voidPayment(fixture.payment().getId(), new PaymentVoidRequest("too late")));
    }

    /** Confirms a successful void writes exactly one VOID_PAYMENT audit row, never RECORD_PAYMENT or REFUND_PAYMENT. */
    @Test
    void shouldWriteVoidPaymentAuditLogExactlyOnce() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        Payment payment = Payment.create(
                stay, BigDecimal.TEN, PaymentCurrency.VND, null, BigDecimal.TEN, PaymentMethod.CASH, null);
        payment.audit(UUID.randomUUID());
        payment.markPaid(Instant.now());
        setCurrentUser(UUID.randomUUID());
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(paymentRepository, stayRepository, chargeRepository, auditLogRepository, Clock.systemDefaultZone())
                .voidPayment(payment.getId(), new PaymentVoidRequest("Duplicate entry"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertEquals("VOID_PAYMENT", captor.getValue().getAction());
    }

    /** Confirms the AuditLog row never carries the raw void reason text. */
    @Test
    void shouldNotWriteRawVoidReasonToAuditLog() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        Payment payment = Payment.create(
                stay, BigDecimal.TEN, PaymentCurrency.VND, null, BigDecimal.TEN, PaymentMethod.CASH, null);
        payment.audit(UUID.randomUUID());
        payment.markPaid(Instant.now());
        setCurrentUser(UUID.randomUUID());
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String secretReason = "VERY-SENSITIVE-REASON-TEXT";

        service(paymentRepository, stayRepository, chargeRepository, auditLogRepository, Clock.systemDefaultZone())
                .voidPayment(payment.getId(), new PaymentVoidRequest(secretReason));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog audit = captor.getValue();
        Object oldValue = org.springframework.test.util.ReflectionTestUtils.getField(audit, "oldValue");
        Object newValue = org.springframework.test.util.ReflectionTestUtils.getField(audit, "newValue");
        assertFalse(String.valueOf(oldValue).contains(secretReason));
        assertFalse(String.valueOf(newValue).contains(secretReason));
    }

    /** Confirms invalid record-paid input returns the standard bad-request response. */
    private void assertBadRequestForOperation(org.junit.jupiter.api.function.Executable operation) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, operation);
        assertEquals(400, exception.getStatusCode().value());
    }

    /** Creates a record-paid Payment for a checked-in Stay whose Reservation has the given currency. */
    private PaymentResponse recordPaidPayment(
            BigDecimal amount, PaymentCurrency currency, BigDecimal exchangeRate, String reservationCurrency) {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN, reservationCurrency);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(new BigDecimal("999999999"));
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(BigDecimal.ZERO);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return service(paymentRepository, stayRepository, chargeRepository)
                .recordPaid(stayId, request(amount, currency, exchangeRate, PaymentMethod.CASH, null));
    }

    /** Supplies every approved Payment method except OTA, whose reference is mandatory. */
    private static Stream<PaymentMethod> nonOtaPaymentMethods() {
        return Stream.of(
                PaymentMethod.CASH, PaymentMethod.CREDIT_CARD, PaymentMethod.BANK_TRANSFER, PaymentMethod.OTHER);
    }

    /** Creates a valid pending Payment and returns its API representation. */
    private PaymentResponse createValidPayment(PaymentMethod method, String reference) {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return service(paymentRepository, stayRepository, mock(ChargeRepository.class))
                .create(stayId, request(BigDecimal.ONE, method, reference));
    }

    /** Confirms invalid Payment creation input returns the standard bad-request response. */
    private void assertBadRequest(PaymentCreateRequest request) {
        setCurrentUser(UUID.randomUUID());
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service(mock(PaymentRepository.class), mock(StayRepository.class), mock(ChargeRepository.class))
                        .create(UUID.randomUUID(), request));
        assertEquals(400, exception.getStatusCode().value());
    }

    /**
     * Confirms invalid currency/exchange-rate input returns the standard bad-request response,
     * for validation that only runs once the owning Stay has already been resolved.
     */
    private void assertBadRequestForStay(PaymentCreateRequest request, String reservationCurrency) {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN, reservationCurrency);
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service(paymentRepository, stayRepository, mock(ChargeRepository.class))
                        .create(stayId, request));

        assertEquals(400, exception.getStatusCode().value());
    }

    /** Confirms an operation returns the standard conflict response. */
    private void assertConflict(org.junit.jupiter.api.function.Executable operation) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, operation);
        assertEquals(409, exception.getStatusCode().value());
    }

    /** Creates a Payment for a checked-in Stay whose Reservation has the given currency. */
    private PaymentResponse createPayment(
            BigDecimal amount, PaymentCurrency currency, BigDecimal exchangeRate, String reservationCurrency) {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN, reservationCurrency);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return service(paymentRepository, stayRepository, mock(ChargeRepository.class))
                .create(stayId, request(amount, currency, exchangeRate, PaymentMethod.CASH, null));
    }

    /** Builds a fixture for a state-transition test using a same-currency VND Payment. */
    private Fixture transitionFixture(BigDecimal paymentAmount, BigDecimal totalCharges, BigDecimal totalPaid) {
        return transitionFixture(paymentAmount, totalCharges, totalPaid, mock(AuditLogRepository.class));
    }

    /** Builds a fixture for a state-transition test with an explicit AuditLogRepository to inspect. */
    private Fixture transitionFixture(
            BigDecimal paymentAmount, BigDecimal totalCharges, BigDecimal totalPaid, AuditLogRepository auditLogRepository) {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        Payment payment = Payment.create(
                stay, paymentAmount, PaymentCurrency.VND, null, paymentAmount, PaymentMethod.CASH, null);
        payment.audit(UUID.randomUUID());
        setCurrentUser(UUID.randomUUID());
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(totalCharges);
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(totalPaid);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PaymentService service = service(
                paymentRepository, stayRepository, chargeRepository, auditLogRepository, Clock.systemDefaultZone());
        return new Fixture(service, paymentRepository, stayRepository, stay, payment);
    }

    /** Creates a client-controlled, same-currency (VND) Payment request fixture. */
    private PaymentCreateRequest request(BigDecimal amount, PaymentMethod method, String reference) {
        return request(amount, PaymentCurrency.VND, null, method, reference);
    }

    /** Creates a client-controlled Payment request fixture with explicit currency and rate. */
    private PaymentCreateRequest request(
            BigDecimal amount,
            PaymentCurrency currency,
            BigDecimal exchangeRate,
            PaymentMethod method,
            String reference) {
        return new PaymentCreateRequest(amount, currency, exchangeRate, method, reference);
    }

    /** Creates a mocked Stay fixture whose Reservation currency defaults to VND. */
    private Stay stay(UUID id, StayStatus status) {
        return stay(id, status, "VND");
    }

    /** Creates a mocked Stay fixture with the required identifier, status, and Reservation currency. */
    private Stay stay(UUID id, StayStatus status, String reservationCurrency) {
        Stay stay = mock(Stay.class);
        when(stay.getId()).thenReturn(id);
        when(stay.getStatus()).thenReturn(status);
        Reservation reservation = mock(Reservation.class);
        when(reservation.getCurrency()).thenReturn(reservationCurrency);
        when(reservation.getId()).thenReturn(UUID.randomUUID());
        when(stay.getReservation()).thenReturn(reservation);
        return stay;
    }

    /** Creates the Payment service under test with a real (non-fixed) Clock and a mocked AuditLogRepository. */
    private PaymentService service(
            PaymentRepository paymentRepository,
            StayRepository stayRepository,
            ChargeRepository chargeRepository) {
        return service(
                paymentRepository,
                stayRepository,
                chargeRepository,
                mock(AuditLogRepository.class),
                Clock.systemDefaultZone());
    }

    /** Creates the Payment service under test with full control over its Clock and AuditLogRepository. */
    private PaymentService service(
            PaymentRepository paymentRepository,
            StayRepository stayRepository,
            ChargeRepository chargeRepository,
            AuditLogRepository auditLogRepository,
            Clock clock) {
        return new PaymentService(
                paymentRepository, stayRepository, chargeRepository, new PaymentMapper(), auditLogRepository, clock);
    }

    /** Establishes the authenticated user used for Payment audit attribution. */
    private void setCurrentUser(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(id, "payment-manager"), null));
    }

    /**
     * Creates a checked-in Stay fixture with generous Total Charges and no live duplicate reference
     * yet, used by the duplicate-reference guard tests. The Reservation identifier is stable so the
     * guard can be observed against the exact tuple it queries.
     */
    private DuplicateFixture duplicateFixture() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Stay stay = mock(Stay.class);
        when(stay.getId()).thenReturn(stayId);
        when(stay.getStatus()).thenReturn(StayStatus.CHECKED_IN);
        Reservation reservation = mock(Reservation.class);
        when(reservation.getCurrency()).thenReturn("VND");
        when(reservation.getId()).thenReturn(reservationId);
        when(stay.getReservation()).thenReturn(reservation);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(new BigDecimal("999999999"));
        when(paymentRepository.sumAppliedAmountByStayIdAndStatus(stayId, PaymentStatus.PAID))
                .thenReturn(BigDecimal.ZERO);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PaymentService service = service(paymentRepository, stayRepository, chargeRepository);
        return new DuplicateFixture(service, paymentRepository, stayRepository, stayId, reservationId);
    }

    /** Holds one duplicate-reference scenario and can declare a tuple already live. */
    private record DuplicateFixture(
            PaymentService service,
            PaymentRepository paymentRepository,
            StayRepository stayRepository,
            UUID stayId,
            UUID reservationId) {

        /**
         * Declares that the Reservation already holds a live Payment for the given tuple, which is
         * what the second attempt observes once the Stay lock has serialized the two requests.
         *
         * @param method the payment method of the already recorded Payment
         * @param reference the trimmed non-blank reference of the already recorded Payment
         */
        void markReferenceLive(PaymentMethod method, String reference) {
            when(paymentRepository.existsLiveWithReference(
                            reservationId,
                            method,
                            reference,
                            List.of(PaymentStatus.FAILED, PaymentStatus.REFUNDED, PaymentStatus.VOIDED)))
                    .thenReturn(true);
        }
    }

    /** Holds one mocked Payment-transition scenario. */
    private record Fixture(
            PaymentService service,
            PaymentRepository paymentRepository,
            StayRepository stayRepository,
            Stay stay,
            Payment payment) {}
}
