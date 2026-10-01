package com.example.hotel.service.booking;

import com.example.hotel.common.SupportedCurrency;
import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.ChargeVoidRequest;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.common.AdditionalRevenue;
import com.example.hotel.entity.common.AdditionalRevenueCategory;
import com.example.hotel.entity.common.AuditLog;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.repository.common.AdditionalRevenueCategoryRepository;
import com.example.hotel.repository.common.AdditionalRevenueRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import java.time.Clock;
import com.example.hotel.entity.booking.ChargeStatus;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.mapper.booking.ChargeMapper;
import com.example.hotel.repository.booking.ChargeRepository;
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

/** Records and retrieves the approved Charge v1 entries for a Stay. */
@Service
public class ChargeService {

    private final ChargeRepository chargeRepository;
    private final StayRepository stayRepository;
    private final ChargeMapper chargeMapper;
    private final AdditionalRevenueRepository additionalRevenues;
    private final AdditionalRevenueCategoryRepository additionalRevenueCategories;
    private final AuditLogRepository auditLogRepository;
    private final StayBalanceService stayBalanceService;
    private final Clock clock;

    /**
     * Creates the Charge service with its required persistence and mapping collaborators.
     *
     * @param chargeRepository repository used to persist Charges
     * @param stayRepository repository used to resolve owning Stays
     * @param chargeMapper mapper used to return client-safe responses
     * @param auditLogRepository repository used to write the approved RECORD_CHARGE audit entry
     * @param stayBalanceService the single authoritative Outstanding calculation, used by the Charge void
     *     negative-balance guard so the folio invariant is never re-implemented here
     */
    public ChargeService(
            ChargeRepository chargeRepository,
            StayRepository stayRepository,
            ChargeMapper chargeMapper,
            AdditionalRevenueRepository additionalRevenues,
            AdditionalRevenueCategoryRepository additionalRevenueCategories,
            AuditLogRepository auditLogRepository,
            StayBalanceService stayBalanceService,
            Clock clock) {
        this.chargeRepository = chargeRepository;
        this.stayRepository = stayRepository;
        this.chargeMapper = chargeMapper;
        this.additionalRevenues = additionalRevenues;
        this.additionalRevenueCategories = additionalRevenueCategories;
        this.auditLogRepository = auditLogRepository;
        this.stayBalanceService = stayBalanceService;
        this.clock = clock;
}

    /**
     * Records a Charge v1 entry against an existing Stay.
     *
     * @param stayId owning Stay identifier
     * @param request client-controlled Charge data
     * @return the recorded Charge
     * @throws ResponseStatusException if the Stay is missing or Charge v1 validation fails
     */
    @Transactional
    public ChargeResponse create(UUID stayId, ChargeCreateRequest request) {
        validatePricing(request);
        // Lock order shared with check-out, Stay Extension and Room Change: the Stay row first, then revalidate its
        // state, so a Charge can never commit after a check-out decided the folio was settled.
        Stay stay = stayRepository.findByIdForUpdate(stayId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stay not found"));
        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw conflict("Charges can be created only for checked-in stays");
        }
        // A Charge carries no currency of its own: it is denominated in the owning Reservation's.
        BigDecimal amount = resolveAmount(request, folioCurrency(stay));
        AdditionalRevenueCategory category = null;
        if (request.type().isGuestServiceRevenue()) {
            category = resolveGuestServiceCategory(stay, request.type());
        }
        Charge charge = Charge.create(
                stay,
                request.type(),
                request.description(),
                request.quantity(),
                request.unitPrice(),
                amount);
        CurrentUser user = currentUser();
        charge.audit(user.id());
        Charge saved = chargeRepository.save(charge);
        auditLogRepository.save(new AuditLog(
                user.id(),
                "RECORD_CHARGE",
                stay.getReservation().getId(),
                null,
                "Charge " + saved.getId() + " type=" + saved.getType() + ", amount=" + saved.getAmount().toPlainString()));
        if (category != null) {
            // Guest service revenue: exactly one linked, system-managed Additional Revenue in the same transaction.
            AdditionalRevenue revenue = AdditionalRevenue.createFromCharge(
                    category,
                    saved,
                    saved.getChargedAt().atZone(clock.getZone()).toLocalDate(),
                    revenueDescription(stay, saved));
            revenue.audit(user.id());
            additionalRevenues.save(revenue);
        }
        return chargeMapper.toResponse(saved);
    }

    private AdditionalRevenueCategory resolveGuestServiceCategory(Stay stay, ChargeType type) {
        if (!AdditionalRevenue.CURRENCY_VND.equals(stay.getReservation().getCurrency())) {
            throw new LocalizedResponseStatusException(
                    HttpStatus.CONFLICT,
                    "payment.folio.charge.error.serviceRevenueCurrency",
                    "Guest service charges require a VND reservation because service revenue is recorded in VND");
        }
        return additionalRevenueCategories.findByCode(type.guestServiceCategoryCode()).orElseThrow(() ->
                new LocalizedResponseStatusException(
                        HttpStatus.CONFLICT,
                        "payment.folio.charge.error.serviceRevenueCategory",
                        "Guest service revenue category is not configured: " + type.guestServiceCategoryCode()));
    }

    private String revenueDescription(Stay stay, Charge charge) {
        String detail = charge.getDescription() == null || charge.getDescription().isBlank()
                ? ""
                : ": " + charge.getDescription().trim();
        return "Folio charge " + charge.getType().name() + " (" + stay.getReservation().getReservationNumber() + ")"
                + detail;
    }

    /**
     * Voids an ACTIVE, non-ROOM Charge: it stops counting toward Total Charges but the row remains
     * physically present. A manual guest-service Charge's linked Additional Revenue is voided in the
     * same transaction, so it also stops counting toward revenue reports; if that linked revenue is
     * unexpectedly missing or already inconsistent, the whole operation is rejected and nothing is
     * committed rather than leaving the Folio and reports silently divergent.
     *
     * @param chargeId Charge identifier
     * @param request client-supplied void reason
     * @return the voided Charge response
     * @throws ResponseStatusException if the Charge does not exist, is a ROOM charge, is not ACTIVE,
     *     its Stay is not checked in, or its linked Additional Revenue is missing/inconsistent
     */
    @Transactional
    public ChargeResponse voidCharge(UUID chargeId, ChargeVoidRequest request) {
        String reason = requireReason(request);
        // Charge row lock first (already identified by id), then its Stay: mirrors the existing
        // Payment mutate-by-id convention (markPaid/markFailed/refund lock the leaf row before the
        // Stay), unlike Charge creation which locks the Stay first because no Charge row exists yet.
        Charge charge = chargeRepository.findByIdForUpdate(chargeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charge not found"));
        Stay stay = stayRepository.findByIdForUpdate(charge.getStay().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stay not found"));
        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw new LocalizedResponseStatusException(
                    HttpStatus.CONFLICT,
                    "payment.folio.charge.error.voidNotCheckedIn",
                    "Charges can be voided only for checked-in stays");
        }
        // Localized pre-validation of the same two invariants Charge.voidCharge() itself enforces.
        // The entity guard below runs regardless (defense-in-depth) and stays English-only/domain-level,
        // since Charge must not depend on MessageSource/i18n; by construction it can never actually
        // reject here once these checks already passed, because the Charge row has been locked since
        // before this point and neither its type nor its status can change out from under this call.
        if (charge.getType() == ChargeType.ROOM) {
            throw new LocalizedResponseStatusException(
                    HttpStatus.CONFLICT, "payment.folio.charge.error.voidRoomNotAllowed", "ROOM charges cannot be voided");
        }
        if (charge.getStatus() != ChargeStatus.ACTIVE) {
            throw new LocalizedResponseStatusException(
                    HttpStatus.CONFLICT, "payment.folio.charge.error.voidNotActive", "Only an active charge can be voided");
        }
        // Approved V1 invariant: a void may never leave the folio overpaid. Outstanding is read from the single
        // authoritative calculation (ACTIVE Charges minus PAID Payment appliedAmount) BEFORE anything is mutated,
        // so the arithmetic never depends on Hibernate flush ordering. The read happens while this transaction
        // already holds the Stay row lock taken above, and every Payment mutation (create, record-paid, mark-paid,
        // mark-failed, refund, void) takes that same Stay row lock first, so a concurrent payment change cannot
        // slip between this check and the commit. V1 has no OVERPAID folio state: staff correct or void the
        // erroneous Payment first, then void the erroneous Charge.
        BigDecimal outstandingAfterVoid = stayBalanceService.calculate(stay.getId())
                .outstanding()
                .subtract(charge.getAmount());
        if (outstandingAfterVoid.signum() < 0) {
            throw new LocalizedResponseStatusException(
                    HttpStatus.CONFLICT,
                    "payment.folio.charge.error.voidWouldOverpay",
                    "Voiding this charge would leave the folio overpaid; correct the payment first");
        }
        try {
            charge.voidCharge(reason);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        CurrentUser user = currentUser();
        if (charge.getType().isGuestServiceRevenue()) {
            AdditionalRevenue revenue = additionalRevenues.findByChargeIdForUpdate(charge.getId())
                    .orElseThrow(() -> new LocalizedResponseStatusException(
                            HttpStatus.CONFLICT,
                            "payment.folio.charge.error.voidRevenueInconsistent",
                            "Linked Additional Revenue is missing or inconsistent for this Charge"));
            try {
                revenue.voidForChargeCorrection(reason, clock.instant(), user.id());
            } catch (IllegalStateException exception) {
                throw conflict(exception.getMessage());
            }
            revenue.audit(user.id());
            additionalRevenues.save(revenue);
        }
        charge.audit(user.id());
        Charge saved = chargeRepository.save(charge);
        auditLogRepository.save(new AuditLog(
                user.id(),
                "VOID_CHARGE",
                stay.getReservation().getId(),
                "Charge " + saved.getId() + " status=ACTIVE",
                "Charge " + saved.getId() + " status=VOIDED"));
        return chargeMapper.toResponse(saved);
    }

    /**
     * Validates the client-supplied void reason independently of REST Bean Validation.
     *
     * @param request Charge void request
     * @return trimmed, non-blank reason
     * @throws ResponseStatusException if the reason is missing or blank
     */
    private String requireReason(ChargeVoidRequest request) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank()) {
            throw new LocalizedResponseStatusException(
                    HttpStatus.BAD_REQUEST, "payment.folio.charge.error.voidReasonRequired", "reason is required");
        }
        return reason.trim();
    }

    /**
     * Lists the Charges recorded for one existing Stay in stable chronological order.
     *
     * @param stayId owning Stay identifier
     * @return Charge responses ordered by charged time and identifier
     * @throws ResponseStatusException if the Stay does not exist
     */
    @Transactional(readOnly = true)
    public List<ChargeResponse> findByStayId(UUID stayId) {
        findStay(stayId);
        return chargeRepository.findByStayIdOrderByChargedAtAscIdAsc(stayId).stream()
                .map(chargeMapper::toResponse)
                .toList();
    }

    /**
     * Validates the currency-independent Charge v1 pricing rules before the owning Stay is resolved.
     *
     * @param request Charge data to validate
     * @throws ResponseStatusException if a Charge v1 rule is not satisfied
     */
    private void validatePricing(ChargeCreateRequest request) {
        if (request.type() == null || !request.type().isSupportedInV1()) {
            throw badRequest("Unsupported Charge v1 type");
        }
        if (request.type() == ChargeType.ROOM) {
            throw badRequest("ROOM charges are created only by the system (check-in and Stay Extension)");
        }
        if ((request.quantity() == null) != (request.unitPrice() == null)) {
            throw badRequest("quantity and unitPrice must both be present or absent");
        }
        if (request.quantity() == null) {
            if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw badRequest("fixed charge amount must be greater than zero");
            }
            return;
        }
        if (request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("quantity must be greater than zero");
        }
        if (request.quantity().stripTrailingZeros().scale() > 0) {
            throw badRequest("quantity must be a whole number");
        }
        if (request.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw badRequest("unitPrice must be greater than or equal to zero");
        }
        if (request.amount() != null) {
            throw badRequest("itemized charges must not provide amount");
        }
        if (request.quantity().multiply(request.unitPrice()).compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("calculated itemized amount must be greater than zero");
        }
    }

    /**
     * Resolves the authoritative Charge amount in the Folio currency.
     *
     * <p>A staff-entered monetary value carrying more precision than the Folio currency has is
     * rejected rather than silently rounded. The itemized calculation is normalized to that same
     * currency, so a Charge amount is always expressible in real money.</p>
     *
     * @param request already structurally validated Charge data
     * @param currency owning Reservation's currency
     * @return fixed client amount or backend-calculated itemized amount, in {@code currency}
     * @throws ResponseStatusException if a monetary value does not fit the Folio currency
     */
    private BigDecimal resolveAmount(ChargeCreateRequest request, SupportedCurrency currency) {
        if (request.quantity() == null) {
            if (!currency.hasValidPrecision(request.amount())) {
                throw badRequest("amount exceeds " + currency + " currency precision");
            }
            return currency.normalize(request.amount());
        }
        if (!currency.hasValidPrecision(request.unitPrice())) {
            throw badRequest("unitPrice exceeds " + currency + " currency precision");
        }
        return currency.normalize(request.quantity().multiply(request.unitPrice()));
    }

    /**
     * Resolves the currency every Charge of a Stay is denominated in.
     *
     * @param stay owning Stay
     * @return the owning Reservation's currency
     * @throws ResponseStatusException if that currency is not supported in V1
     */
    private SupportedCurrency folioCurrency(Stay stay) {
        return SupportedCurrency.find(stay.getReservation().getCurrency())
                .orElseThrow(() -> conflict("Reservation currency is not supported for Charge"));
    }

    /**
     * Finds the Stay required to own a Charge.
     *
     * @param stayId Stay identifier
     * @return existing Stay
     * @throws ResponseStatusException if no Stay exists for the identifier
     */
    private Stay findStay(UUID stayId) {
        return stayRepository
                .findById(stayId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stay not found"));
    }

    /**
     * Resolves the current JWT or session principal for audit attribution.
     *
     * @return authenticated application user
     * @throws ResponseStatusException if no application user is authenticated
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
     * Creates a standard bad-request exception for Charge v1 validation.
     *
     * @param message validation message
     * @return bad-request exception
     */
    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Creates a standard conflict exception for an invalid Charge lifecycle operation.
     *
     * @param message user-safe conflict explanation
     * @return conflict exception
     */
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
