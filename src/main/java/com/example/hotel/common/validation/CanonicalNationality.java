package com.example.hotel.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Validates that a Guest nationality is one of the canonical country names offered by the UI. */
@Documented
@Constraint(validatedBy = CanonicalNationalityValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface CanonicalNationality {

    /**
     * Returns the validation message shown for an unsupported country value.
     *
     * @return the validation message
     */
    String message() default "Nationality is not supported.";

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
