package com.example.hotel.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a non-blank {@code cancellationReasonDetail} is supplied whenever a cancellation
 * {@code cancellationReasonCode} is OTHER. The field is attached to {@code cancellationReasonDetail} so the
 * violation renders as an ordinary field-level error.
 */
@Documented
@Constraint(validatedBy = RequiresDetailForOtherCancellationReasonValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface RequiresDetailForOtherCancellationReason {

    /**
     * Returns the validation message shown when reason OTHER is missing its detail.
     *
     * @return the validation message
     */
    String message() default "{validation.reservation.cancel.reasonDetail.requiredForOther}";

    /**
     * Returns the Bean Validation groups for this constraint.
     *
     * @return the validation groups
     */
    Class<?>[] groups() default {};

    /**
     * Returns metadata payload types for this constraint.
     *
     * @return the metadata payload types
     */
    Class<? extends Payload>[] payload() default {};
}
