package com.example.hotel.dto.common.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Contains the mutable profile fields supplied when updating an existing Staff member. The Staff
 * Code and active state are never accepted here: the Staff Code is immutable, and active state
 * changes only through the explicit Deactivate/Reactivate operations.
 *
 * @param firstName required first name
 * @param lastName required last name
 * @param phone optional phone number
 * @param email optional email address, validated when supplied
 * @param position optional free-text position (e.g. Receptionist, Housekeeping)
 * @param startDate required employment start date
 * @param notes optional notes
 */
public record StaffUpdateRequest(
        @NotBlank(message = "First name is required.") @Size(max = 100) String firstName,
        @NotBlank(message = "Last name is required.") @Size(max = 100) String lastName,
        @Size(max = 100) String phone,
        @Email(message = "Email must be a valid email address.") @Size(max = 255) String email,
        @Size(max = 100) String position,
        @NotNull(message = "Start date is required.") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @Size(max = 1000) String notes) {}
