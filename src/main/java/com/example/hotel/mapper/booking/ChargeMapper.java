package com.example.hotel.mapper.booking;

import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.entity.booking.Charge;
import org.springframework.stereotype.Component;

/** Converts Charge entities into client-safe response data. */
@Component
public class ChargeMapper {

    /**
     * Maps one Charge entity to its response representation.
     *
     * @param charge source Charge
     * @return client-safe Charge response
     */
    public ChargeResponse toResponse(Charge charge) {
        return toResponse(charge, null);
    }

    /**
     * Maps one Charge entity to its response representation, including the "added by" username already resolved
     * by the caller (batch-resolved to avoid an N+1 lookup per row).
     *
     * @param charge source Charge
     * @param addedByUsername the username of the staff member who recorded the Charge, or {@code null}
     * @return client-safe Charge response
     */
    public ChargeResponse toResponse(Charge charge, String addedByUsername) {
        return new ChargeResponse(
                charge.getId(),
                charge.getStay().getId(),
                charge.getType().name(),
                charge.getDescription(),
                charge.getQuantity(),
                charge.getUnitPrice(),
                charge.getAmount(),
                charge.getChargedAt(),
                charge.getStatus().name(),
                charge.getVoidReason(),
                addedByUsername);
    }
}
