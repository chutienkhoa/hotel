package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.Payment;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.mapper.booking.PaymentMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

    /** Confirms a Payment cannot be created for a checked-out Stay. */
    @Test
    void shouldRejectPaymentCreationForCheckedOutStay() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_OUT);
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
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
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service(paymentRepository, stayRepository, mock(ChargeRepository.class))
                        .create(stayId, request(BigDecimal.ONE, PaymentMethod.CASH, null)));

        assertEquals(404, exception.getStatusCode().value());
    }

    /** Confirms every approved Payment method is accepted and an optional reference may be absent. */
    @ParameterizedTest
    @MethodSource("paymentMethods")
    void shouldAcceptApprovedMethodAndOptionalReference(PaymentMethod method) {
        PaymentResponse response = createValidPayment(method, null);

        assertEquals(method.name(), response.method());
        assertNull(response.reference());
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

        fixture.service().refund(fixture.payment().getId());

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
        fixture.payment().markPaid();
        when(fixture.stay().getStatus()).thenReturn(StayStatus.CHECKED_OUT);

        assertConflict(() -> fixture.service().refund(fixture.payment().getId()));
    }

    /** Confirms listing uses the repository result scoped to the requested Stay. */
    @Test
    void shouldListOnlyPaymentsForRequestedStay() {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        Payment first = Payment.create(stay, BigDecimal.ONE, PaymentMethod.CASH, null);
        Payment second = Payment.create(stay, BigDecimal.TEN, PaymentMethod.OTHER, "REF");
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(paymentRepository.findByStayIdOrderByCreatedAtAscIdAsc(stayId)).thenReturn(List.of(first, second));

        List<PaymentResponse> payments = service(paymentRepository, stayRepository, mock(ChargeRepository.class))
                .findByStayId(stayId);

        assertEquals(List.of(first.getId(), second.getId()), payments.stream().map(PaymentResponse::id).toList());
    }

    /** Supplies all approved Payment methods. */
    private static Stream<PaymentMethod> paymentMethods() {
        return Stream.of(
                PaymentMethod.CASH,
                PaymentMethod.CREDIT_CARD,
                PaymentMethod.BANK_TRANSFER,
                PaymentMethod.OTA,
                PaymentMethod.OTHER);
    }

    /** Creates a valid pending Payment and returns its API representation. */
    private PaymentResponse createValidPayment(PaymentMethod method, String reference) {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
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

    /** Confirms an operation returns the standard conflict response. */
    private void assertConflict(org.junit.jupiter.api.function.Executable operation) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, operation);
        assertEquals(409, exception.getStatusCode().value());
    }

    /** Builds a fixture for a state-transition test. */
    private Fixture transitionFixture(BigDecimal paymentAmount, BigDecimal totalCharges, BigDecimal totalPaid) {
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId, StayStatus.CHECKED_IN);
        Payment payment = Payment.create(stay, paymentAmount, PaymentMethod.CASH, null);
        payment.audit(UUID.randomUUID());
        setCurrentUser(UUID.randomUUID());
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
        when(stayRepository.findByIdForUpdate(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.sumAmountByStayId(stayId)).thenReturn(totalCharges);
        when(paymentRepository.sumAmountByStayIdAndStatus(stayId, PaymentStatus.PAID)).thenReturn(totalPaid);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new Fixture(service(paymentRepository, stayRepository, chargeRepository), paymentRepository, stayRepository, stay, payment);
    }

    /** Creates a client-controlled Payment request fixture. */
    private PaymentCreateRequest request(BigDecimal amount, PaymentMethod method, String reference) {
        return new PaymentCreateRequest(amount, method, reference);
    }

    /** Creates a mocked Stay fixture with the required identifier and status. */
    private Stay stay(UUID id, StayStatus status) {
        Stay stay = mock(Stay.class);
        when(stay.getId()).thenReturn(id);
        when(stay.getStatus()).thenReturn(status);
        return stay;
    }

    /** Creates the Payment service under test. */
    private PaymentService service(
            PaymentRepository paymentRepository,
            StayRepository stayRepository,
            ChargeRepository chargeRepository) {
        return new PaymentService(paymentRepository, stayRepository, chargeRepository, new PaymentMapper());
    }

    /** Establishes the authenticated user used for Payment audit attribution. */
    private void setCurrentUser(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(id, "payment-manager"), null));
    }

    /** Holds one mocked Payment-transition scenario. */
    private record Fixture(
            PaymentService service,
            PaymentRepository paymentRepository,
            StayRepository stayRepository,
            Stay stay,
            Payment payment) {}
}
