package com.example.hotel.dto.booking.request;

import com.example.hotel.common.validation.RequiresOtaBookingReference;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.common.StrictIntegerDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Dữ liệu request tạo reservation nháp. */
@RequiresOtaBookingReference
public record CreateRequest(
        @NotNull(message = "{validation.reservation.guestId.required}") UUID guestId,
        @NotNull(message = "{validation.reservation.checkInDate.required}") LocalDate checkInDate,
        @NotNull(message = "{validation.reservation.checkOutDate.required}") LocalDate checkOutDate,
        @NotNull(message = "{validation.reservation.adultCount.required}")
                @Min(value = 1, message = "{validation.reservation.adultCount.min}")
                @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer adultCount,
        @NotNull(message = "{validation.reservation.childCount.required}")
                @Min(value = 0, message = "{validation.reservation.childCount.min}")
                @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer childCount,
        @NotNull(message = "{validation.reservation.source.required}") BookingSource source,
        @Size(max = 255, message = "{validation.reservation.otaReference.max}") String otaBookingReference,
        @NotBlank(message = "{validation.reservation.currency.required}")
                @Pattern(regexp = "VND", message = "{validation.reservation.currency.supported}")
                String currency,
        @Size(max = 5000, message = "{validation.reservation.notes.max}") String notes,
        @NotEmpty(message = "{validation.reservation.rooms.required}") List<@Valid RoomRequest> rooms,
        List<UUID> accompanyingGuestIds,
        @Size(max = 200, message = "{validation.reservation.contactName.max}") String bookingContactName,
        @Size(max = 100, message = "{validation.reservation.contactPhone.max}") String bookingContactPhone,
        @Size(max = 255, message = "{validation.reservation.contactEmail.max}") String bookingContactEmail) {

    /**
     * Creates a request without explicit Booking Contact values, for callers that predate Booking Contact (Walk-in
     * and existing tests/fixtures). {@code ReservationService.create} defaults Booking Contact from the Primary
     * Guest whenever every explicit value is blank, so this constructor still produces a Reservation with a
     * Booking Contact snapshot.
     *
     * @param guestId identifier of the Primary Guest
     * @param checkInDate planned check-in date
     * @param checkOutDate planned check-out date
     * @param adultCount number of adults, at least 1
     * @param childCount number of children, at least 0
     * @param source booking source
     * @param otaBookingReference staff-entered OTA reference, required for a non-DIRECT source
     * @param currency Reservation currency code; V1 Reservations are VND only
     * @param notes optional reservation notes
     * @param rooms requested room/rate assignments
     * @param accompanyingGuestIds optional Accompanying Guest identifiers
     */
    public CreateRequest(
            UUID guestId,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            Integer adultCount,
            Integer childCount,
            BookingSource source,
            String otaBookingReference,
            String currency,
            String notes,
            List<RoomRequest> rooms,
            List<UUID> accompanyingGuestIds) {
        this(guestId, checkInDate, checkOutDate, adultCount, childCount, source, otaBookingReference, currency,
                notes, rooms, accompanyingGuestIds, null, null, null);
    }

    /**
     * Returns the requested Accompanying Guest identifiers, never {@code null}.
     *
     * @return the identifiers, empty when none were supplied
     */
    public List<UUID> accompanyingGuestIdsOrEmpty() {
        return accompanyingGuestIds == null ? List.of() : accompanyingGuestIds;
    }
}
