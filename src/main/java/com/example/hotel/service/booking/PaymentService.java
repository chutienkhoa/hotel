package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.PaymentCreateRequest;
import com.example.hotel.dto.booking.response.PaymentResponse;
import com.example.hotel.entity.booking.Payment;
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
        Payment payment = Payment.create(stay, request.amount(), request.method(), request.reference());
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
        BigDecimal totalPaid = zeroIfNull(
                paymentRepository.sumAmountByStayIdAndStatus(stay.getId(), PaymentStatus.PAID));
        if (totalPaid.add(payment.getAmount()).compareTo(totalCharges) > 0) {
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
        if (request.method() == null) {
            throw badRequest("method is required");
        }
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
