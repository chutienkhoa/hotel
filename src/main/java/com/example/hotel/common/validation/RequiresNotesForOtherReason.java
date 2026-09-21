package com.example.hotel.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a non-blank {@code notes} is supplied whenever a Room Change {@code reason} is
 * OTHER. The field is attached to {@code notes} so the violation renders as an ordinary
 * field-level error.
 */
@Documented
@Constraint(validatedBy = RequiresNotesForOtherReasonValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface RequiresNotesForOtherReason {

    /**
     * Returns the validation message shown when reason OTHER is missing its notes.
     *
     * @return the validation message
     */
    String message() default "Notes are required when reason is OTHER.";

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
