package com.example.hotel.common.validation;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.entity.booking.BookingSource;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Requires a non-blank OTA booking reference whenever a Reservation's source is not DIRECT. */
public class RequiresOtaBookingReferenceValidator
        implements ConstraintValidator<RequiresOtaBookingReference, CreateRequest> {

    /**
     * Validates the submitted source and OTA booking reference together, attaching any violation
     * to the {@code otaBookingReference} field so it renders as a normal field-level error.
     *
     * @param request submitted reservation create/edit form data
     * @param context validation context
     * @return {@code true} when the source is DIRECT, unset, or an OTA source with a non-blank
     *     reference
     */
    @Override
    public boolean isValid(CreateRequest request, ConstraintValidatorContext context) {
        if (request == null || request.source() == null || request.source() == BookingSource.DIRECT) {
            return true;
        }
        if (request.otaBookingReference() != null && !request.otaBookingReference().isBlank()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("otaBookingReference")
                .addConstraintViolation();
        return false;
    }
}
