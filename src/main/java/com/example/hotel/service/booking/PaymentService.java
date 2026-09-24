package com.example.hotel.service.booking;

import com.example.hotel.common.SupportedCurrency;
import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.request.PaymentVoidRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.Payment;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.mapper.booking.PaymentMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Records, lists, and transitions Payment v1 records without calculating an outstanding balance. */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final StayRepository stayRepository;
    private final ChargeRepository chargeRepository;
    private final PaymentMapper paymentMapper;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    /**
     * Creates the Payment service with the persistence collaborators required by Payment v1.
     *
     * @param paymentRepository repository used to persist and lock Payments
     * @param stayRepository repository used to resolve and lock Stays
     * @param chargeRepository repository used only for overpayment-prevention aggregation
     * @param paymentMapper mapper used to return client-safe Payment responses
     * @param auditLogRepository repository used to write the approved REFUND_PAYMENT audit entry
     * @param clock authoritative hotel business clock used for {@code paidAt}
     */
    public PaymentService(
            PaymentRepository paymentRepository,
            StayRepository stayRepository,
            ChargeRepository chargeRepository,
            PaymentMapper paymentMapper,
            AuditLogRepository auditLogRepository,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.stayRepository = stayRepository;
        this.chargeRepository = chargeRepository;
        this.paymentMapper = paymentMapper;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    /**
     * Creates a pending Payment for a checked-in Stay.
     *
     * @param stayId owning Stay identifier from the URL path
     * @param request client-controlled Payment data
     * @return created pending Payment
     */
    @Transactional
    public PaymentResponse create(UUID stayId, PaymentCreateRequest request) {
        validateCreationRequest(request);
        // Stay lock first (shared with check-out), then revalidate that the folio is still open.
        Stay stay = findStayForUpdate(stayId);
        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw conflict("Payments can be created only for checked-in stays");
        }
        requireNoLiveDuplicateReference(stay, request);
        PaymentCurrency reservationCurrency = resolveReservationCurrency(stay);
        BigDecimal appliedAmount = calculateAppliedAmount(
                request.amount(), request.currency(), reservationCurrency, request.exchangeRate());
        Payment payment = Payment.create(
                stay,
                request.amount(),
                request.currency(),
                request.exchangeRate(),
                appliedAmount,
                request.method(),
                request.reference());
        payment.audit(currentUser().id());
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    /**
     * Atomically creates and marks as paid a Payment for money already received by Staff — the
     * approved shortcut for ordinary manual hotel payments, so Staff need not create a PENDING
     * Payment and separately mark it paid. Reuses the exact same validation, currency-conversion,
     * and overpayment-prevention logic as {@link #create} and {@link #markPaid}; no intermediate
     * PENDING Payment is ever persisted, and any failure leaves nothing persisted since every step
     * runs inside this one transaction. The existing {@link #create} + {@link #markPaid} flow
     * remains fully available and unchanged for Payments that must stay PENDING (e.g. awaiting
     * bank/gateway/OTA confirmation). It also shares the duplicate-reference guard described in
     * {@link #requireNoLiveDuplicateReference}.
     *
     * @param stayId owning Stay identifier from the URL path
     * @param request client-controlled Payment data
     * @return the created, already-paid Payment response
     */
    @Transactional
    public PaymentResponse recordPaid(UUID stayId, PaymentCreateRequest request) {
        validateCreationRequest(request);
        Stay stay = findStayForUpdate(stayId);
        requireCheckedIn(stay);
        requireNoLiveDuplicateReference(stay, request);
        PaymentCurrency reservationCurrency = resolveReservationCurrency(stay);
        BigDecimal appliedAmount = calculateAppliedAmount(
                request.amount(), request.currency(), reservationCurrency, request.exchangeRate());
        BigDecimal totalCharges = zeroIfNull(chargeRepository.sumAmountByStayId(stay.getId()));
        BigDecimal totalPaidApplied = zeroIfNull(
                paymentRepository.sumAppliedAmountByStayIdAndStatus(stay.getId(), PaymentStatus.PAID));
        if (totalPaidApplied.add(appliedAmount).compareTo(totalCharges) > 0) {
            throw conflict("Payment would exceed total charges");
        }
        Payment payment = Payment.create(
                stay,
                request.amount(),
                request.currency(),
                request.exchangeRate(),
                appliedAmount,
                request.method(),
                request.reference());
        payment.markPaid(Instant.now(clock));
        CurrentUser user = currentUser();
        payment.audit(user.id());
        Payment saved = paymentRepository.save(payment);
        recordPaymentAudit(user, stay, saved);
        return paymentMapper.toResponse(saved);
    }

    /**
     * Lists Payments belonging to an existing Stay in stable creation order.
     *
     * @param stayId owning Stay identifier
     * @return ordered Payment responses
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> findByStayId(UUID stayId) {
        findStay(stayId);
        return paymentRepository.findByStayIdOrderByCreatedAtAscIdAsc(stayId).stream()
                .map(paymentMapper::toResponse)
                .toList();
    }

    /**
     * Marks a pending Payment as paid after preventing an overpayment for its Stay.
     *
     * @param paymentId Payment identifier
     * @return paid Payment response
     */
    @Transactional
    public PaymentResponse markPaid(UUID paymentId) {
        Payment payment = findPaymentForUpdate(paymentId);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw conflict("Invalid payment state transition");
        }
        Stay stay = findStayForUpdate(payment.getStay() == null ? null : payment.getStay().getId());
        requireCheckedIn(stay);
        BigDecimal totalCharges = zeroIfNull(chargeRepository.sumAmountByStayId(stay.getId()));
        BigDecimal totalPaidApplied = zeroIfNull(
                paymentRepository.sumAppliedAmountByStayIdAndStatus(stay.getId(), PaymentStatus.PAID));
        if (totalPaidApplied.add(payment.getAppliedAmount()).compareTo(totalCharges) > 0) {
            throw conflict("Payment would exceed total charges");
        }
        payment.markPaid(Instant.now(clock));
        CurrentUser user = currentUser();
        payment.audit(user.id());
        Payment saved = paymentRepository.save(payment);
        recordPaymentAudit(user, stay, saved);
        return paymentMapper.toResponse(saved);
    }

    /**
     * Marks a pending Payment as failed.
     *
     * @param paymentId Payment identifier
     * @return failed Payment response
     */
    @Transactional
    public PaymentResponse markFailed(UUID paymentId) {
        Payment payment = findPaymentForUpdate(paymentId);
        requireCheckedIn(findStayForUpdate(payment.getStay() == null ? null : payment.getStay().getId()));
        transitionToFailed(payment);
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    /**
     * Refunds a paid Payment using the same Payment record. V1 supports full refund only; the
     * approved refund reason is required and is persisted on the same row. {@code updatedAt}/
     * {@code updatedBy} remain the authoritative refund timestamp/user, since REFUNDED is
     * terminal. Writes the approved {@code REFUND_PAYMENT} audit entry using the existing
     * AuditLog convention (same style as {@code ReservationService}/{@code RoomChangeService}).
     *
     * @param paymentId Payment identifier
     * @param request client-supplied refund reason
     * @return refunded Payment response
     */
    @Transactional
    public PaymentResponse refund(UUID paymentId, PaymentRefundRequest request) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank()) {
            throw badRequest("reason is required");
        }
        String trimmedReason = reason.trim();
        Payment payment = findPaymentForUpdate(paymentId);
        Stay stay = findStayForUpdate(payment.getStay() == null ? null : payment.getStay().getId());
        requireCheckedIn(stay);
        try {
            payment.refund(trimmedReason);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        CurrentUser user = currentUser();
        payment.audit(user.id());
        Payment saved = paymentRepository.save(payment);
        auditLogRepository.save(new AuditLog(
                user.id(),
                "REFUND_PAYMENT",
                stay.getReservation().getId(),
                "Payment " + payment.getId() + " status=PAID",
                "Payment " + payment.getId() + " status=REFUNDED, reason=" + trimmedReason));
        return paymentMapper.toResponse(saved);
    }

    /**
     * Voids a paid Payment: it stops contributing to Total Payments, but the row remains physically
     * present. Reserved for a Payment recorded in error (wrong amount, wrong method, or a duplicate
     * entry) where no money was actually received or returned; when money actually needs to be
     * handed back to the guest, use {@link #refund(UUID, PaymentRefundRequest)} instead. The two
     * remain distinct in every persisted field: status ({@code VOIDED} vs {@code REFUNDED}), reason
     * column ({@code voidReason} vs {@code refundReason}), and AuditLog action ({@code VOID_PAYMENT}
     * vs {@code REFUND_PAYMENT}).
     *
     * @param paymentId Payment identifier
     * @param request client-supplied void reason
     * @return voided Payment response
     */
    @Transactional
    public PaymentResponse voidPayment(UUID paymentId, PaymentVoidRequest request) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank()) {
            throw badRequest("reason is required");
        }
        String trimmedReason = reason.trim();
        Payment payment = findPaymentForUpdate(paymentId);
        Stay stay = findStayForUpdate(payment.getStay() == null ? null : payment.getStay().getId());
        requireCheckedIn(stay);
        try {
            payment.voidPayment(trimmedReason);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        CurrentUser user = currentUser();
        payment.audit(user.id());
        Payment saved = paymentRepository.save(payment);
        auditLogRepository.save(new AuditLog(
                user.id(),
                "VOID_PAYMENT",
                stay.getReservation().getId(),
                "Payment " + payment.getId() + " status=PAID",
                "Payment " + payment.getId() + " status=VOIDED"));
        return paymentMapper.toResponse(saved);
    }

    /**
     * Rejects recording the same real-world Payment twice for one Reservation. A Payment is a
     * duplicate when its owning Reservation already has a LIVE Payment with the SAME method and the
     * SAME trimmed, non-blank reference — exactly the tuple, and exactly the
     * {@link PaymentRepository#existsLiveWithReference} definition of "live" (anything other than
     * FAILED, REFUNDED or VOIDED), that the pre-check-in prepayment guard already uses. Extending
     * that one rule to the Stay-level boundaries means one reference cannot be recorded once before
     * check-in and again on the folio.
     *
     * <p>Its purpose is operational: it stops a manual retry or a double-submitted form from
     * recording one bank transfer or card transaction twice. It is not payment-gateway idempotency.</p>
     *
     * <p>Deliberate boundaries of the rule, so it never rejects a legitimately distinct Payment:</p>
     * <ul>
     *   <li>a blank or absent reference never collides — several cash payments are normal;</li>
     *   <li>the method is part of the tuple, so the same text under a different method is allowed;</li>
     *   <li>a corrected Payment (VOIDED/REFUNDED, or FAILED) releases its reference immediately, so
     *       the corrected entry can be recorded with the same real-world reference.</li>
     * </ul>
     *
     * <p>Concurrency: every caller already holds the Stay's {@code PESSIMISTIC_WRITE} lock, so two
     * concurrent requests for the same Stay are serialized and the second one's check runs only
     * after the first one's Payment is visible.</p>
     *
     * @param stay the locked owning Stay
     * @param request client-controlled Payment data
     * @throws LocalizedResponseStatusException if an identical live Payment already exists
     */
    private void requireNoLiveDuplicateReference(Stay stay, PaymentCreateRequest request) {
        String reference = normalizeReference(request.reference());
        if (reference == null) {
            return;
        }
        boolean duplicate = paymentRepository.existsLiveWithReference(
                stay.getReservation().getId(),
                request.method(),
                reference,
                List.of(PaymentStatus.FAILED, PaymentStatus.REFUNDED, PaymentStatus.VOIDED));
        if (duplicate) {
            throw new LocalizedResponseStatusException(
                    HttpStatus.CONFLICT,
                    "payment.folio.payment.error.duplicateReference",
                    "A payment with the same method and reference already exists for this reservation");
        }
    }

    /**
     * Normalizes a submitted reference for the duplicate guard the same way the prepayment guard
     * does: surrounding whitespace is ignored and a blank reference counts as no reference. What is
     * persisted is unchanged.
     *
     * @param reference client-supplied reference, possibly {@code null}
     * @return the trimmed reference, or {@code null} when absent or blank
     */
    private static String normalizeReference(String reference) {
        if (reference == null) {
            return null;
        }
        String trimmed = reference.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Writes the approved {@code RECORD_PAYMENT} audit entry for an ordinary Payment that just
     * became PAID (the one-step shortcut in {@link #recordPaid} or the two-step confirmation in
     * {@link #markPaid}). Never used for a prepayment, an applied prepayment, or a refund, which
     * keep their own distinct {@code RECORD_PREPAYMENT}/{@code APPLY_PREPAYMENT}/{@code
     * REFUND_PAYMENT} audit entries.
     *
     * @param user actor who recorded the payment
     * @param stay owning Stay, used to resolve the Reservation audit target
     * @param payment the newly PAID Payment
     */
    private void recordPaymentAudit(CurrentUser user, Stay stay, Payment payment) {
        auditLogRepository.save(new AuditLog(
                user.id(),
                "RECORD_PAYMENT",
                stay.getReservation().getId(),
                null,
                "Payment " + payment.getId() + " amount=" + payment.getAmount().toPlainString() + " "
                        + payment.getCurrency() + ", applied=" + payment.getAppliedAmount().toPlainString()
                        + ", method=" + payment.getMethod()));
    }

    /**
     * Applies the pending-to-failed transition and records its authenticated updater.
     *
     * @param payment locked Payment to transition
     */
    private void transitionToFailed(Payment payment) {
        try {
            payment.markFailed();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        payment.audit(currentUser().id());
    }

    /**
     * Validates creation rules independently of REST Bean Validation.
     *
     * @param request Payment request to validate
     */
    void validateCreationRequest(PaymentCreateRequest request) {
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("amount must be greater than zero");
        }
        if (request.currency() == null) {
            throw badRequest("currency is required");
        }
        // The tender amount is denominated in the tender currency, not the Folio currency.
        if (!precisionOf(request.currency()).hasValidPrecision(request.amount())) {
            throw badRequest("amount exceeds " + request.currency() + " currency precision");
        }
        if (request.method() == null) {
            throw badRequest("method is required");
        }
        if (request.method() == PaymentMethod.OTA
                && (request.reference() == null || request.reference().isBlank())) {
            throw badRequest("reference is required for OTA payments");
        }
    }

    /**
     * Resolves the owning Reservation's currency as a {@link PaymentCurrency}.
     *
     * @param stay owning Stay
     * @return the Reservation's currency
     * @throws ResponseStatusException if the Reservation's currency is not a supported Payment currency
     */
    private PaymentCurrency resolveReservationCurrency(Stay stay) {
        return resolveReservationCurrency(stay.getReservation());
    }

    /**
     * Resolves a Reservation's currency as a {@link PaymentCurrency}.
     *
     * @param reservation owning Reservation
     * @return the Reservation's currency
     * @throws ResponseStatusException if the currency is not a supported Payment currency
     */
    PaymentCurrency resolveReservationCurrency(com.example.hotel.entity.booking.Reservation reservation) {
        try {
            return PaymentCurrency.valueOf(reservation.getCurrency());
        } catch (IllegalArgumentException exception) {
            throw conflict("Reservation currency is not supported for Payment");
        }
    }

    /**
     * Calculates the amount applied to the Folio, in the Reservation's currency.
     *
     * <p>This is the single authoritative Payment currency-conversion calculation: every other
     * part of the codebase (overpayment validation, {@code StayBalanceService}, the Folio display)
     * only ever reads the already-computed {@link Payment#getAppliedAmount()} and must never
     * reinterpret {@code exchangeRate} itself. The canonical meaning of {@code exchangeRate} is
     * always "1 USD = exchangeRate VND", regardless of which side is the Payment currency or the
     * Reservation currency; a reciprocal rate is never calculated or stored.</p>
     *
     * @param amount amount actually received, in {@code paymentCurrency}
     * @param paymentCurrency currency the amount was actually received in
     * @param reservationCurrency owning Reservation's currency
     * @param exchangeRate client-supplied "1 USD = exchangeRate VND" rate; must be {@code null}
     *     for a same-currency Payment and a positive value for a cross-currency Payment
     * @return the calculated applied amount, in {@code reservationCurrency}
     */
    BigDecimal calculateAppliedAmount(
            BigDecimal amount,
            PaymentCurrency paymentCurrency,
            PaymentCurrency reservationCurrency,
            BigDecimal exchangeRate) {
        SupportedCurrency folioCurrency = precisionOf(reservationCurrency);
        if (paymentCurrency == reservationCurrency) {
            if (exchangeRate != null) {
                throw badRequest("exchangeRate must not be supplied for a same-currency payment");
            }
            return folioCurrency.normalize(amount);
        }
        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("exchangeRate must be greater than zero for a cross-currency payment");
        }
        // Rounded exactly once, straight to the Folio currency, so no intermediate scale can leave a
        // sub-minor-unit remainder behind and no value is rounded twice.
        BigDecimal appliedAmount = paymentCurrency == PaymentCurrency.USD
                ? folioCurrency.normalize(amount.multiply(exchangeRate))
                : amount.divide(exchangeRate, folioCurrency.fractionDigits(), RoundingMode.HALF_UP);
        if (appliedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("calculated appliedAmount must be greater than zero");
        }
        return appliedAmount;
    }

    /**
     * Resolves the monetary precision rules of a Payment currency, used for both the tender currency
     * and the owning Reservation's Folio currency.
     *
     * @param currency Payment tender or Folio currency
     * @return the matching supported currency
     */
    private static SupportedCurrency precisionOf(PaymentCurrency currency) {
        return SupportedCurrency.valueOf(currency.name());
    }

    /**
     * Finds a Stay by identifier.
     *
     * @param stayId Stay identifier
     * @return existing Stay
     */
    private Stay findStay(UUID stayId) {
        return stayRepository
                .findById(stayId)
                .orElseThrow(() -> notFound("Stay"));
    }

    /**
     * Locks a Stay to serialize Payment transitions that affect its paid-total context.
     *
     * @param stayId Stay identifier
     * @return locked Stay
     */
    private Stay findStayForUpdate(UUID stayId) {
        if (stayId == null) {
            throw conflict("A prepayment belongs to its reservation until check-in; use the prepayment operations");
        }
        return stayRepository
                .findByIdForUpdate(stayId)
                .orElseThrow(() -> notFound("Stay"));
    }

    /**
     * Locks a Payment before applying an explicit state transition.
     *
     * @param paymentId Payment identifier
     * @return locked Payment
     */
    private Payment findPaymentForUpdate(UUID paymentId) {
        return paymentRepository
                .findByIdForUpdate(paymentId)
                .orElseThrow(() -> notFound("Payment"));
    }

    /**
     * Ensures financial Payment transitions cannot mutate a closed Folio.
     *
     * @param stay locked owning Stay
     * @throws ResponseStatusException if the Stay is not checked in
     */
    private void requireCheckedIn(Stay stay) {
        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw conflict("Payment transitions are allowed only for checked-in stays");
        }
    }

    /**
     * Converts a nullable aggregate result to zero without providing an outstanding-balance service.
     *
     * @param value aggregate value
     * @return aggregate value or zero
     */
    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Resolves the current JWT or session principal for audit attribution.
     *
     * @return authenticated application user
     */
    private CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.getUsername());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated user");
    }

    /**
     * Creates a standard not-found exception.
     *
     * @param resourceName missing resource name
     * @return not-found exception
     */
    private ResponseStatusException notFound(String resourceName) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, resourceName + " not found");
    }

    /**
     * Creates a standard bad-request exception.
     *
     * @param message validation message
     * @return bad-request exception
     */
    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Creates a standard conflict exception.
     *
     * @param message conflict message
     * @return conflict exception
     */
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
