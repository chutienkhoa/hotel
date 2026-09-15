package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.Payment;
import com.example.hotel.entity.booking.PaymentCurrency;
import com.example.hotel.entity.booking.PaymentStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.mapper.booking.PaymentMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.PaymentRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * Creates the Payment service with the persistence collaborators required by Payment v1.
     *
     * @param paymentRepository repository used to persist and lock Payments
     * @param stayRepository repository used to resolve and lock Stays
     * @param chargeRepository repository used only for overpayment-prevention aggregation
     * @param paymentMapper mapper used to return client-safe Payment responses
     */
    public PaymentService(
            PaymentRepository paymentRepository,
            StayRepository stayRepository,
            ChargeRepository chargeRepository,
            PaymentMapper paymentMapper) {
        this.paymentRepository = paymentRepository;
        this.stayRepository = stayRepository;
        this.chargeRepository = chargeRepository;
        this.paymentMapper = paymentMapper;
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
        Stay stay = findStay(stayId);
        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw conflict("Payments can be created only for checked-in stays");
        }
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
        Stay stay = findStayForUpdate(payment.getStay().getId());
        requireCheckedIn(stay);
        BigDecimal totalCharges = zeroIfNull(chargeRepository.sumAmountByStayId(stay.getId()));
        BigDecimal totalPaidApplied = zeroIfNull(
                paymentRepository.sumAppliedAmountByStayIdAndStatus(stay.getId(), PaymentStatus.PAID));
        if (totalPaidApplied.add(payment.getAppliedAmount()).compareTo(totalCharges) > 0) {
            throw conflict("Payment would exceed total charges");
        }
        payment.markPaid();
        payment.audit(currentUser().id());
        return paymentMapper.toResponse(paymentRepository.save(payment));
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
        requireCheckedIn(findStayForUpdate(payment.getStay().getId()));
        transitionToFailed(payment);
        return paymentMapper.toResponse(paymentRepository.save(payment));
    }

    /**
     * Refunds a paid Payment using the same Payment record.
     *
     * @param paymentId Payment identifier
     * @return refunded Payment response
     */
    @Transactional
    public PaymentResponse refund(UUID paymentId) {
        Payment payment = findPaymentForUpdate(paymentId);
        requireCheckedIn(findStayForUpdate(payment.getStay().getId()));
        try {
            payment.refund();
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        payment.audit(currentUser().id());
        return paymentMapper.toResponse(paymentRepository.save(payment));
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
    private void validateCreationRequest(PaymentCreateRequest request) {
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("amount must be greater than zero");
        }
        if (request.currency() == null) {
            throw badRequest("currency is required");
        }
        if (request.method() == null) {
            throw badRequest("method is required");
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
        try {
            return PaymentCurrency.valueOf(stay.getReservation().getCurrency());
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
    private BigDecimal calculateAppliedAmount(
            BigDecimal amount,
            PaymentCurrency paymentCurrency,
            PaymentCurrency reservationCurrency,
            BigDecimal exchangeRate) {
        if (paymentCurrency == reservationCurrency) {
            if (exchangeRate != null) {
                throw badRequest("exchangeRate must not be supplied for a same-currency payment");
            }
            return amount;
        }
        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("exchangeRate must be greater than zero for a cross-currency payment");
        }
        BigDecimal appliedAmount = paymentCurrency == PaymentCurrency.USD
                ? amount.multiply(exchangeRate).setScale(6, RoundingMode.HALF_UP)
                : amount.divide(exchangeRate, 6, RoundingMode.HALF_UP);
        if (appliedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("calculated appliedAmount must be greater than zero");
        }
        return appliedAmount;
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
