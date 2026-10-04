package com.example.hotel.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a non-blank {@code otaBookingReference} is supplied whenever a Reservation's
 * source is an OTA (not DIRECT). The field is attached to {@code otaBookingReference} so the
 * violation renders as an ordinary field-level error.
 */
@Documented
@Constraint(validatedBy = RequiresOtaBookingReferenceValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface RequiresOtaBookingReference {

    /**
     * Returns the validation message shown when an OTA source is missing its booking reference.
     *
     * @return the validation message
     */
    String message() default "{validation.reservation.otaReference.required}";

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
