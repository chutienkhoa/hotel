package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.request.PaymentRefundRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.dto.booking.response.PrepaymentSummaryResponse;
import com.example.hotel.entity.booking.Payment;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.mapper.booking.PaymentMapper;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Prepayment / Advance Payment V1: money received toward a CONFIRMED Reservation before check-in. A prepayment is an
 * ordinary {@link Payment} row that belongs to the Reservation with no Stay yet; check-in attaches the SAME row to the
 * new Stay. It is PAID immediately, never creates revenue, and is refunded whole. Pre-check-in operations serialize on
 * the Reservation row lock.
 */
@Service
public class PrepaymentService {

    private final ReservationRepository reservations;
    private final StayRepository stays;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final PaymentMapper paymentMapper;
    private final AuditLogRepository audits;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param reservations Reservation repository (row lock)
     * @param stays Stay repository
     * @param payments Payment repository
     * @param paymentService owner of the shared validation and currency conversion
     * @param paymentMapper Payment response mapper
     * @param audits audit repository
     * @param clock hotel business clock used for {@code paidAt}
     */
    public PrepaymentService(
            ReservationRepository reservations,
            StayRepository stays,
            PaymentRepository payments,
            PaymentService paymentService,
            PaymentMapper paymentMapper,
            AuditLogRepository audits,
            Clock clock) {
        this.reservations = reservations;
        this.stays = stays;
        this.payments = payments;
        this.paymentService = paymentService;
        this.paymentMapper = paymentMapper;
        this.audits = audits;
        this.clock = clock;
    }

    /**
     * Records money received before check-in.
     *
     * @param reservationId Reservation identifier
     * @param request amount, currency, optional rate, method and reference
     * @return the PAID prepayment
     */
    @Transactional
    public PaymentResponse record(UUID reservationId, PaymentCreateRequest request) {
        paymentService.validateCreationRequest(request);
        Reservation reservation = lockConfirmedWithoutStay(reservationId);
        PaymentCurrency reservationCurrency = paymentService.resolveReservationCurrency(reservation);
        BigDecimal applied = paymentService.calculateAppliedAmount(
                request.amount(), request.currency(), reservationCurrency, request.exchangeRate());
        String reference = normalize(request.reference());
        if (reference != null && payments.existsLiveWithReference(
                reservationId, request.method(), reference, List.of(PaymentStatus.FAILED, PaymentStatus.REFUNDED))) {
            throw localized(HttpStatus.CONFLICT, "payment.prepayment.error.duplicate",
                    "A payment with the same method and reference already exists for this reservation");
        }
        BigDecimal active = payments.sumActivePrepaymentAppliedAmount(reservationId);
        if (active.add(applied).compareTo(reservation.getTotalAmount()) > 0) {
            throw localized(HttpStatus.CONFLICT, "payment.prepayment.error.overpayment",
                    "Prepayments would exceed the reservation total");
        }
        CurrentUser user = currentUser();
        Payment payment = Payment.createPrepayment(
                reservation, request.amount(), request.currency(), request.exchangeRate(), applied,
                request.method(), reference, Instant.now(clock));
        payment.audit(user.id());
        Payment saved = payments.save(payment);
        audits.save(new AuditLog(user.id(), "RECORD_PREPAYMENT", reservationId, null,
                "Payment " + saved.getId() + " amount=" + saved.getAmount().toPlainString() + " " + saved.getCurrency()
                        + ", applied=" + applied.toPlainString() + " " + reservation.getCurrency()
                        + ", method=" + saved.getMethod()));
        return paymentMapper.toResponse(saved);
    }

    /**
     * Refunds one prepayment in full before check-in. The Reservation stays CONFIRMED.
     *
     * @param reservationId Reservation identifier
     * @param paymentId prepayment identifier
     * @param request refund reason
     * @return the REFUNDED payment
     */
    @Transactional
    public PaymentResponse refund(UUID reservationId, UUID paymentId, PaymentRefundRequest request) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
        }
        Reservation reservation = lockConfirmedWithoutStay(reservationId);
        Payment payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        if (!payment.getReservation().getId().equals(reservation.getId())
                || payment.getStay() != null
                || payment.getStatus() != PaymentStatus.PAID) {
            throw localized(HttpStatus.CONFLICT, "payment.prepayment.error.refundState",
                    "Only an active prepayment of this reservation can be refunded here");
        }
        try {
            payment.refund(reason.trim());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
        CurrentUser user = currentUser();
        payment.audit(user.id());
        Payment saved = payments.save(payment);
        audits.save(new AuditLog(user.id(), "REFUND_PAYMENT", reservationId,
                "Payment " + payment.getId() + " status=PAID",
                "Payment " + payment.getId() + " status=REFUNDED, reason=" + reason.trim()));
        return paymentMapper.toResponse(saved);
    }

    /**
     * Summarizes a Reservation's prepayments with two bounded queries.
     *
     * @param reservationId Reservation identifier
     * @return the summary
     */
    @Transactional(readOnly = true)
    public PrepaymentSummaryResponse summary(UUID reservationId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        List<Payment> rows = payments.findPrepaymentsByReservationId(reservationId);
        BigDecimal active = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        for (Payment row : rows) {
            if (row.getStatus() == PaymentStatus.PAID) {
                active = active.add(row.getAppliedAmount());
            } else if (row.getStatus() == PaymentStatus.REFUNDED) {
                refunded = refunded.add(row.getAppliedAmount());
            }
        }
        return new PrepaymentSummaryResponse(
                reservation.getCurrency(),
                reservation.getTotalAmount(),
                active,
                refunded,
                active.add(refunded),
                reservation.getTotalAmount().subtract(active),
                rows.stream().map(paymentMapper::toResponse).toList());
    }

    /**
     * Rejects a lifecycle operation while the Reservation still holds active prepayments. Called by cancel and
     * no-show under the Reservation lock.
     *
     * @param reservationId Reservation identifier
     * @param messageKey localized message key of the blocked operation
     * @param detail English detail
     */
    public void requireNoActivePrepayments(UUID reservationId, String messageKey, String detail) {
        if (payments.sumActivePrepaymentAppliedAmount(reservationId).signum() > 0) {
            throw localized(HttpStatus.CONFLICT, messageKey, detail);
        }
    }

    /**
     * Returns the total of active PAID prepayments using the single repository definition shared by prepayment
     * recording, cancellation/no-show guards and confirmed-reservation date changes.
     *
     * @param reservationId Reservation identifier
     * @return active prepayment amount in the Reservation currency
     */
    @Transactional(readOnly = true)
    public BigDecimal activePaidTotal(UUID reservationId) {
        return payments.sumActivePrepaymentAppliedAmount(reservationId);
    }

    /**
     * Attaches the Reservation's active prepayments (the SAME rows) to the Stay just created by check-in, in one
     * bounded locked query, and audits the application once.
     *
     * @param reservation the checking-in Reservation (its row lock is held by the caller)
     * @param stay the new Stay
     * @param user actor
     */
    public void applyToStay(Reservation reservation, Stay stay, CurrentUser user) {
        List<Payment> active = payments.findActivePrepaymentsForUpdate(reservation.getId());
        if (active.isEmpty()) {
            return;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Payment payment : active) {
            payment.attachToStay(stay);
            payment.audit(user.id());
            total = total.add(payment.getAppliedAmount());
        }
        audits.save(new AuditLog(user.id(), "APPLY_PREPAYMENT", reservation.getId(), null,
                "count=" + active.size() + ", applied=" + total.toPlainString() + " " + reservation.getCurrency()
                        + ", stay=" + stay.getId()));
    }

    private Reservation lockConfirmedWithoutStay(UUID reservationId) {
        Reservation reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        if (reservation.getStatus() != ReservationStatus.CONFIRMED || stays.existsByReservationId(reservationId)) {
            throw localized(HttpStatus.CONFLICT, "payment.prepayment.error.notConfirmed",
                    "Prepayments are only available for a confirmed reservation that has not checked in");
        }
        return reservation;
    }

    private static String normalize(String reference) {
        if (reference == null) {
            return null;
        }
        String trimmed = reference.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static LocalizedResponseStatusException localized(HttpStatus status, String key, String detail) {
        return new LocalizedResponseStatusException(status, key, detail);
    }

    private CurrentUser currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.username());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated user");
    }
}
