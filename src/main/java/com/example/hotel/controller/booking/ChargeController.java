package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.request.ChargeVoidRequest;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.service.booking.ChargeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the approved Charge v1 create and list operations for a Stay. */
@RestController
@RequestMapping("/api/stays/{stayId}/charges")
public class ChargeController {

    private final ChargeService chargeService;

    /**
     * Creates the Charge API controller.
     *
     * @param chargeService service that owns Charge v1 operations
     */
    public ChargeController(ChargeService chargeService) {
        this.chargeService = chargeService;
    }

    /**
     * Records a Charge v1 entry for the requested Stay.
     *
     * @param stayId owning Stay identifier
     * @param request validated client-controlled Charge data
     * @return recorded Charge response
     */
    @PostMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    ChargeResponse create(@PathVariable UUID stayId, @Valid @RequestBody ChargeCreateRequest request) {
        return chargeService.create(stayId, request);
    }

    /**
     * Lists the Charges recorded against the requested Stay.
     *
     * @param stayId owning Stay identifier
     * @return stable ordered Charge list
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    List<ChargeResponse> findByStayId(@PathVariable UUID stayId) {
        return chargeService.findByStayId(stayId);
    }

    /**
     * Voids an ACTIVE, non-ROOM Charge recorded against the requested Stay.
     *
     * @param stayId owning Stay identifier (path consistency only; ownership is resolved from the Charge itself)
     * @param chargeId Charge identifier
     * @param request validated client-supplied void reason
     * @return the voided Charge response
     */
    @PostMapping("/{chargeId}/void")
    @PreAuthorize("hasAuthority('PERM_MANAGE_PAYMENT')")
    ChargeResponse voidCharge(
            @PathVariable UUID stayId, @PathVariable UUID chargeId, @Valid @RequestBody ChargeVoidRequest request) {
        return chargeService.voidCharge(chargeId, request);
    }
}
