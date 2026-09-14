package com.example.hotel.service.booking;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.mapper.booking.ChargeMapper;
import com.example.hotel.repository.booking.ChargeRepository;
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

/** Records and retrieves the approved Charge v1 entries for a Stay. */
@Service
public class ChargeService {

    private final ChargeRepository chargeRepository;
    private final StayRepository stayRepository;
    private final ChargeMapper chargeMapper;

    /**
     * Creates the Charge service with its required persistence and mapping collaborators.
     *
     * @param chargeRepository repository used to persist Charges
     * @param stayRepository repository used to resolve owning Stays
     * @param chargeMapper mapper used to return client-safe responses
     */
    public ChargeService(
            ChargeRepository chargeRepository, StayRepository stayRepository, ChargeMapper chargeMapper) {
        this.chargeRepository = chargeRepository;
        this.stayRepository = stayRepository;
        this.chargeMapper = chargeMapper;
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
        BigDecimal amount = validateAndResolveAmount(request);
        Stay stay = findStay(stayId);
        if (stay.getStatus() != StayStatus.CHECKED_IN) {
            throw conflict("Charges can be created only for checked-in stays");
        }
        Charge charge = Charge.create(
                stay,
                request.type(),
                request.description(),
                request.quantity(),
                request.unitPrice(),
                amount);
        charge.audit(currentUser().id());
        return chargeMapper.toResponse(chargeRepository.save(charge));
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
     * Validates pricing rules and resolves the authoritative amount for a new Charge.
     *
     * @param request Charge data to validate
     * @return fixed client amount or backend-calculated itemized amount
     * @throws ResponseStatusException if a Charge v1 rule is not satisfied
     */
    private BigDecimal validateAndResolveAmount(ChargeCreateRequest request) {
        if (request.type() == null || !request.type().isSupportedInV1()) {
            throw badRequest("Unsupported Charge v1 type");
        }
        if ((request.quantity() == null) != (request.unitPrice() == null)) {
            throw badRequest("quantity and unitPrice must both be present or absent");
        }
        if (request.quantity() == null) {
            if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw badRequest("fixed charge amount must be greater than zero");
            }
            return request.amount();
        }
        if (request.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("quantity must be greater than zero");
        }
        if (request.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw badRequest("unitPrice must be greater than or equal to zero");
        }
        if (request.amount() != null) {
            throw badRequest("itemized charges must not provide amount");
        }
        BigDecimal amount = request.quantity()
                .multiply(request.unitPrice())
                .setScale(6, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("calculated itemized amount must be greater than zero");
        }
        return amount;
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
