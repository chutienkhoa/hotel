package com.example.hotel.common.validation;

import com.example.hotel.dto.customer.response.CountryCatalog;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Applies canonical-country validation to submitted Guest nationality values. */
public class CanonicalNationalityValidator implements ConstraintValidator<CanonicalNationality, String> {

    /**
     * Validates a non-blank value against the canonical country catalogue.
     *
     * <p>Blank values are left to {@code @NotBlank} so the browser receives the required-field
     * message instead of the unsupported-country message.</p>
     *
     * @param value submitted nationality value
     * @param context validation context
     * @return {@code true} when the value is blank or a canonical country name
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || CountryCatalog.isCanonicalCountryName(value);
    }
}
