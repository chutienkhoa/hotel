package com.example.hotel.dto.booking.request;

import com.example.hotel.common.StrictIntegerDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * The only data the dedicated CONFIRMED-reservation guest-composition update accepts: adults, children and the
 * complete Accompanying Guest set. It deliberately has no Primary Guest, date, room, rate, source, currency or notes.
 *
 * @param adultCount new number of adults, at least 1
 * @param childCount new number of children, at least 0
 * @param accompanyingGuestIds complete new Accompanying Guest identifiers (may be empty or {@code null})
 */
public record GuestCompositionUpdateRequest(
        @NotNull(message = "{validation.reservation.adultCount.required}")
                @Min(value = 1, message = "{validation.reservation.adultCount.min}")
                @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer adultCount,
        @NotNull(message = "{validation.reservation.childCount.required}")
                @Min(value = 0, message = "{validation.reservation.childCount.min}")
                @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer childCount,
        List<UUID> accompanyingGuestIds) {

    /**
     * Returns the requested Accompanying Guest identifiers, never {@code null}.
     *
     * @return the identifiers, empty when none were supplied
     */
    public List<UUID> accompanyingGuestIdsOrEmpty() {
        return accompanyingGuestIds == null ? List.of() : accompanyingGuestIds;
    }
}
