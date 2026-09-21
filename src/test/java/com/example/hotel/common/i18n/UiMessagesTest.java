package com.example.hotel.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.hotel.exception.LocalizedResponseStatusException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Verifies message arguments, enum display labels, and key-carrying business errors. */
class UiMessagesTest {

    private static final Locale VI = Locale.of("vi");

    private final UiMessages messages = new UiMessages(source());

    private static ResourceBundleMessageSource source() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /** Confirms MessageFormat arguments replace string concatenation. */
    @Test
    void shouldFormatMessageArguments() {
        assertEquals("Showing 1–10 of 83", messages.get(Locale.ENGLISH, "table.summary", "1", "10", "83"));
        assertEquals("Hiển thị 1–10 trên 83", messages.get(VI, "table.summary", "1", "10", "83"));
        assertEquals("Staff member STF-000001 created successfully.",
                messages.get(Locale.ENGLISH, "staff.flash.created", "STF-000001"));
    }

    /** Confirms enum values keep their internal value and only the display label is translated. */
    @Test
    void shouldTranslateEnumLabelsOnly() {
        assertEquals("Checked In", messages.enumLabel(Locale.ENGLISH, "reservationStatus", "CHECKED_IN"));
        assertEquals("Đã nhận phòng", messages.enumLabel(VI, "reservationStatus", "CHECKED_IN"));
        assertEquals("Out of Order", messages.enumLabel(Locale.ENGLISH, "roomStatus", "OUT_OF_ORDER"));
        assertEquals("Ngừng sử dụng", messages.enumLabel(VI, "roomStatus", "OUT_OF_ORDER"));
        assertEquals("UNKNOWN_VALUE", messages.enumLabel(VI, "reservationStatus", "UNKNOWN_VALUE"));
        assertEquals("", messages.enumLabel(VI, "reservationStatus", null));
    }

    /** Confirms a key-carrying exception is translated per locale while its reason stays English. */
    @Test
    void shouldTranslateKeyCarryingBusinessErrorButKeepEnglishReason() {
        LocalizedResponseStatusException error = new LocalizedResponseStatusException(
                HttpStatus.CONFLICT, "staff.error.cannotDeactivate", "Staff cannot deactivate from its current state");
        try {
            LocaleContextHolder.setLocale(VI);
            assertEquals("Không thể ngừng hoạt động nhân viên ở trạng thái hiện tại", messages.error(error));
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            assertEquals("Staff cannot deactivate from its current state", messages.error(error));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
        assertEquals("Staff cannot deactivate from its current state", error.getReason());
        assertEquals(409, error.getStatusCode().value());
    }

    /** Confirms a plain ResponseStatusException keeps its existing reason (unmigrated code is unchanged). */
    @Test
    void shouldKeepPlainExceptionReason() {
        assertEquals("Legacy English reason",
                messages.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Legacy English reason")));
        assertEquals("Bad Request", messages.error(new ResponseStatusException(HttpStatus.BAD_REQUEST)));
    }
}
