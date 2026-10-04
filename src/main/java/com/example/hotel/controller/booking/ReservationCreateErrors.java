package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.exception.LocalizedResponseStatusException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.server.ResponseStatusException;

/**
 * Presentation-only helper for the Create Reservation page. It places a service rejection on the form field a user can
 * correct and builds the ordered summary shown in the global error dialog. It makes no business decision: every rule is
 * still enforced by Bean Validation and {@code ReservationService}.
 */
final class ReservationCreateErrors {

    /** Error code under which a service rejection is registered on the form's binding result. */
    static final String FAILURE_CODE = "reservation.create.failure";

    /** Form field order, matching the page, used to order the dialog summary. */
    private static final List<String> FIELD_ORDER = List.of(
            "guestId",
            "checkInDate",
            "checkOutDate",
            "adultCount",
            "childCount",
            "source",
            "otaBookingReference",
            "rooms",
            "accompanyingGuestIds",
            "bookingContactName",
            "bookingContactPhone",
            "bookingContactEmail",
            "notes",
            "currency");

    /** Matches a Room row field path such as {@code rooms[2].nightlyRate}. */
    private static final Pattern ROOM_ROW_FIELD = Pattern.compile("^rooms\\[(\\d+)]\\.(roomId|nightlyRate)$");

    private static final String RESERVATION_OTA_REFERENCE_REQUIRED = "reservation.ota.error.referenceRequired";
    private static final String RESERVATION_OTA_DUPLICATE = "reservation.ota.error.duplicateIdentity";
    private static final String CHECK_OUT_AFTER_CHECK_IN = "reservation.create.error.checkOutAfterCheckIn";
    private static final String GUEST_NOT_FOUND = "reservation.create.error.guestNotFound";
    private static final String DUPLICATE_ROOM = "reservation.create.error.duplicateRoom";
    private static final String ROOM_NOT_BOOKABLE = "reservation.create.error.roomNotBookable";
    private static final String NIGHTLY_RATE_SCALE = "reservation.create.error.nightlyRateScale";
    private static final String ACCOMPANYING_PREFIX = "reservation.create.error.accompanying";

    private ReservationCreateErrors() {}

    /**
     * Finds the form field a service rejection belongs to.
     *
     * @param exception the rejection raised by the create operation
     * @param form the submitted form, used to locate a Room row by Room identifier
     * @return the binding path of the field to mark, or {@code null} when the rejection concerns no single field
     */
    static String fieldFor(ResponseStatusException exception, CreateRequest form) {
        if (!(exception instanceof LocalizedResponseStatusException localized)) {
            return null;
        }
        String key = localized.getMessageKey();
        return switch (key) {
            case RESERVATION_OTA_REFERENCE_REQUIRED, RESERVATION_OTA_DUPLICATE -> "otaBookingReference";
            case CHECK_OUT_AFTER_CHECK_IN -> "checkOutDate";
            case GUEST_NOT_FOUND -> "guestId";
            case DUPLICATE_ROOM -> "rooms";
            case ROOM_NOT_BOOKABLE -> roomRowField(localized, form, "roomId");
            case NIGHTLY_RATE_SCALE -> roomRowField(localized, form, "nightlyRate");
            default -> key.startsWith(ACCOMPANYING_PREFIX) ? "accompanyingGuestIds" : null;
        };
    }

    private static String roomRowField(LocalizedResponseStatusException exception, CreateRequest form, String property) {
        Object[] arguments = exception.getMessageArguments();
        if (arguments == null || arguments.length < 2 || !(arguments[1] instanceof UUID roomId) || form.rooms() == null) {
            return null;
        }
        for (int index = 0; index < form.rooms().size(); index++) {
            if (form.rooms().get(index) != null && roomId.equals(form.rooms().get(index).roomId())) {
                return "rooms[" + index + "]." + property;
            }
        }
        return null;
    }

    /**
     * Builds the concise, ordered list of problems for the error dialog: one entry per distinct message, in page order,
     * with a Room row problem prefixed by its row number and problems not tied to a field last.
     *
     * @param bindingResult the form's binding result holding field and service errors
     * @param resolver resolves an error to its localized text in the request locale
     * @param rowLabel formats a Room row problem from its one-based row number and message
     * @return the summary entries, empty when there is no error
     */
    static List<String> summary(
            BindingResult bindingResult,
            Function<MessageSourceResolvable, String> resolver,
            BiFunction<Integer, String, String> rowLabel) {
        List<ObjectError> ordered = new ArrayList<>(bindingResult.getAllErrors());
        ordered.sort(Comparator.comparingInt(ReservationCreateErrors::position));
        Set<String> entries = new LinkedHashSet<>();
        for (ObjectError error : ordered) {
            String message = resolver.apply(error);
            Matcher row = error instanceof FieldError fieldError ? ROOM_ROW_FIELD.matcher(fieldError.getField()) : null;
            if (row != null && row.matches()) {
                entries.add(rowLabel.apply(Integer.parseInt(row.group(1)) + 1, message));
            } else {
                entries.add(message);
            }
        }
        return List.copyOf(entries);
    }

    /** Sort key: page order of the field, Room rows by row then Room before rate, global errors last. */
    private static int position(ObjectError error) {
        if (!(error instanceof FieldError fieldError)) {
            return Integer.MAX_VALUE;
        }
        Matcher row = ROOM_ROW_FIELD.matcher(fieldError.getField());
        if (row.matches()) {
            int base = FIELD_ORDER.indexOf("rooms") * 1000 + 1;
            return base + Integer.parseInt(row.group(1)) * 2 + ("nightlyRate".equals(row.group(2)) ? 1 : 0);
        }
        int index = FIELD_ORDER.indexOf(fieldError.getField());
        return index < 0 ? Integer.MAX_VALUE - 1 : index * 1000;
    }
}
