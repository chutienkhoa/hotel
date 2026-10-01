package com.example.hotel.common.validation;

import com.example.hotel.dto.booking.request.CancelReservationRequest;
import com.example.hotel.entity.booking.CancellationReasonCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Requires non-blank {@code cancellationReasonDetail} whenever a cancellation reason is OTHER. */
public class RequiresDetailForOtherCancellationReasonValidator
        implements ConstraintValidator<RequiresDetailForOtherCancellationReason, CancelReservationRequest> {

    /**
     * Validates the submitted reason code and detail together, attaching any violation to the
     * {@code cancellationReasonDetail} field so it renders as a normal field-level error.
     *
     * @param request submitted cancellation request data
     * @param context validation context
     * @return {@code true} when the reason code is not OTHER, unset, or OTHER with non-blank detail
     */
    @Override
    public boolean isValid(CancelReservationRequest request, ConstraintValidatorContext context) {
        if (request == null || request.cancellationReasonCode() != CancellationReasonCode.OTHER) {
            return true;
        }
        if (request.cancellationReasonDetail() != null && !request.cancellationReasonDetail().isBlank()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("cancellationReasonDetail")
                .addConstraintViolation();
        return false;
    }
}
