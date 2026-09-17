package com.example.hotel.common.validation;

import com.example.hotel.dto.booking.request.RoomChangeRequest;
import com.example.hotel.entity.booking.RoomChangeReason;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Requires non-blank notes whenever a Room Change {@code reason} is OTHER. */
public class RequiresNotesForOtherReasonValidator
        implements ConstraintValidator<RequiresNotesForOtherReason, RoomChangeRequest> {

    /**
     * Validates the submitted reason and notes together, attaching any violation to the
     * {@code notes} field so it renders as a normal field-level error.
     *
     * @param request submitted Room Change form data
     * @param context validation context
     * @return {@code true} when reason is not OTHER, unset, or OTHER with non-blank notes
     */
    @Override
    public boolean isValid(RoomChangeRequest request, ConstraintValidatorContext context) {
        if (request == null || request.reason() != RoomChangeReason.OTHER) {
            return true;
        }
        if (request.notes() != null && !request.notes().isBlank()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("notes")
                .addConstraintViolation();
        return false;
    }
}
