package com.example.hotel.dto.booking.response;

/**
 * One required form field the operator left empty, for the shared feedback dialog on the Walk-in and OTA forms.
 *
 * @param path binding path of the rejected field, such as {@code adultCount} or {@code rooms[1].nightlyRate}
 * @param labelKey suffix of the {@code checkin.validation.field.*} message that names the field
 * @param detail optional qualifier shown after the field name, such as the Room No. of a missing rate
 */
public record MissingRequiredField(String path, String labelKey, String detail) {}
