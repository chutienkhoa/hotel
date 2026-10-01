package com.example.hotel.common.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.entity.booking.BookingSource;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the source-dependent OTA booking reference cross-field validation rule. */
class RequiresOtaBookingReferenceValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** Confirms a DIRECT reservation is valid without an OTA booking reference. */
    @Test
    void shouldAllowDirectSourceWithoutOtaBookingReference() {
        assertTrue(validator.validate(request(BookingSource.DIRECT, null)).isEmpty());
    }

    /** Confirms AGODA requires a non-blank OTA booking reference. */
    @Test
    void shouldRequireOtaBookingReferenceForAgoda() {
        assertFalse(validator.validate(request(BookingSource.AGODA, null)).isEmpty());
        assertTrue(validator.validate(request(BookingSource.AGODA, "123456789")).isEmpty());
    }

    /** Confirms BOOKING_COM requires a non-blank OTA booking reference. */
    @Test
    void shouldRequireOtaBookingReferenceForBookingCom() {
        assertFalse(validator.validate(request(BookingSource.BOOKING_COM, null)).isEmpty());
        assertTrue(validator.validate(request(BookingSource.BOOKING_COM, "BK-987654")).isEmpty());
    }

    /** Confirms AIRBNB requires a non-blank OTA booking reference. */
    @Test
    void shouldRequireOtaBookingReferenceForAirbnb() {
        assertFalse(validator.validate(request(BookingSource.AIRBNB, null)).isEmpty());
        assertTrue(validator.validate(request(BookingSource.AIRBNB, "HMABC123")).isEmpty());
    }

    /** Confirms a whitespace-only OTA booking reference is rejected for an OTA source. */
    @Test
    void shouldRejectWhitespaceOnlyOtaBookingReference() {
        assertFalse(validator.validate(request(BookingSource.AGODA, "   ")).isEmpty());
    }

    /** Confirms a non-numeric OTA booking reference is accepted without any format requirement. */
    @Test
    void shouldAcceptNonNumericOtaBookingReference() {
        assertTrue(validator.validate(request(BookingSource.AIRBNB, "HM-ABC-123")).isEmpty());
    }

    /** Builds a structurally valid CreateRequest for the supplied source and OTA booking reference. */
    private CreateRequest request(BookingSource source, String otaBookingReference) {
        return new CreateRequest(
                UUID.randomUUID(),
                LocalDate.of(2027, 1, 10),
                LocalDate.of(2027, 1, 12),
                1,
                0,
                source,
                otaBookingReference,
                "VND",
                null,
                List.of(new RoomRequest(UUID.randomUUID(), new BigDecimal("100"))),
                List.of());
    }
}
