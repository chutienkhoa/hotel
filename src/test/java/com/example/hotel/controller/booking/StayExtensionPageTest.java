package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.booking.request.StayExtensionRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.StayExtensionPreviewResponse;
import com.example.hotel.dto.booking.response.StayExtensionPreviewResponse.Folio;
import com.example.hotel.dto.booking.response.StayExtensionPreviewResponse.Room;
import com.example.hotel.dto.booking.response.StayExtensionPreviewResponse.State;
import com.example.hotel.dto.room.response.RoomImageFile;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.exception.StayExtensionException;
import com.example.hotel.exception.StayExtensionException.Reason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.StayExtensionService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifies the Extend Stay wizard routes (Select, Review, Confirm), the current-room image, the REST operation, and the
 * EXTEND_STAY authorization on every one of them. Business rules are covered by StayExtensionServiceTest.
 */
@WebMvcTest({StayExtensionPageController.class, StayExtensionController.class})
@Import({StayExtensionPageTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class StayExtensionPageTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 20);
    private static final LocalDate CURRENT_OUT = LocalDate.of(2026, 9, 22);
    private static final LocalDate NEW_OUT = LocalDate.of(2026, 9, 24);
    private static final BigDecimal RATE = new BigDecimal("1000000");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StayExtensionService service;

    @MockitoBean
    private JwtService jwtService;

    private static RequestPostProcessor manager() {
        return user("staff").authorities(new SimpleGrantedAuthority("PERM_EXTEND_STAY"));
    }

    /** A preview whose amounts follow the state: a valid date carries nights × rate, an unselected date carries none. */
    private StayExtensionPreviewResponse preview(State state, LocalDate requested, Folio folio) {
        boolean valid = state == State.AVAILABLE || state == State.ROOM_CONFLICT;
        long nights = valid ? 2 : 0;
        BigDecimal amount = valid ? RATE.multiply(BigDecimal.valueOf(nights)) : null;
        return new StayExtensionPreviewResponse(ID, "R20260920-000001", "Ann Lee", "G-1", BookingSource.DIRECT, 2, 1, "VND",
                CHECK_IN, CURRENT_OUT, LocalDate.of(2026, 9, 23), requested, state, 2, nights, 2 + nights,
                List.of(new Room(ROOM_ID, "201", "Single", RATE, amount, state == State.ROOM_CONFLICT, false)),
                amount, new BigDecimal("2000000"), new BigDecimal("2000000").add(amount == null ? BigDecimal.ZERO : amount),
                folio, null, CURRENT_OUT.plusDays(62));
    }

    private static Folio folio() {
        return new Folio(new BigDecimal("2000000"), BigDecimal.ZERO, new BigDecimal("2000000"), new BigDecimal("4000000"));
    }

    /** Confirms the Select screen renders the current stay, the date prompt and no room or rate control. */
    @Test
    void shouldShowTheSelectScreenWithoutRoomOrRateControls() throws Exception {
        when(service.preview(eq(ID), eq(null), eq(false))).thenReturn(preview(State.NOT_SELECTED, null, null));

        mockMvc.perform(get("/reservations/{id}/stay-extension", ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("R20260920-000001")))
                .andExpect(content().string(containsString("id=\"stay-extension-current-checkout\"")))
                .andExpect(content().string(containsString("name=\"expectedCurrentCheckOutDate\"")))
                .andExpect(content().string(containsString("name=\"newCheckOutDate\"")))
                .andExpect(content().string(containsString("Select a new check-out date to preview the extension.")))
                .andExpect(content().string(not(containsString("name=\"roomId\""))))
                .andExpect(content().string(not(containsString("name=\"nightlyRate\""))))
                .andExpect(content().string(not(containsString("name=\"rateType\""))))
                .andExpect(content().string(not(containsString("outstanding balance"))));
    }

    /** Confirms the Select screen exposes the live regions and the data the client needs for an immediate estimate. */
    @Test
    void shouldExposeLiveRegionsAndEstimateData() throws Exception {
        when(service.preview(eq(ID), eq(NEW_OUT), eq(false))).thenReturn(preview(State.AVAILABLE, NEW_OUT, null));

        mockMvc.perform(get("/reservations/{id}/stay-extension", ID).param("newCheckOutDate", "2026-09-24").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-live=\"selection\"")))
                .andExpect(content().string(containsString("data-live=\"rates\"")))
                .andExpect(content().string(containsString("data-live=\"summary\"")))
                .andExpect(content().string(containsString("data-live=\"actions\"")))
                .andExpect(content().string(containsString("data-current-checkout=\"2026-09-22\"")))
                .andExpect(content().string(containsString("data-nightly-rate=\"1000000\"")))
                .andExpect(content().string(containsString("data-min-date=\"2026-09-23\"")))
                .andExpect(content().string(containsString("data-checking-text=")))
                .andExpect(content().string(containsString("data-check-failed-text=")));
    }

    /** Confirms the Next action is disabled, not dead-but-enabled, until the proposed date is available. */
    @Test
    void shouldDisableNextUntilTheDateIsAvailable() throws Exception {
        when(service.preview(eq(ID), eq(NEW_OUT), eq(false))).thenReturn(preview(State.ROOM_CONFLICT, NEW_OUT, null));

        mockMvc.perform(get("/reservations/{id}/stay-extension", ID).param("newCheckOutDate", "2026-09-24").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This room is not available for the selected extension.")))
                .andExpect(content().string(containsString("Ask a manager or authorized staff member to change the room.")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern("(?s).*id=\"stay-extension-next\"[^>]*disabled.*")));
    }

    /** Confirms an available date shows the enabled Next action that posts the date to the Review step. */
    @Test
    void shouldEnableNextWhenAvailable() throws Exception {
        when(service.preview(eq(ID), eq(NEW_OUT), eq(false))).thenReturn(preview(State.AVAILABLE, NEW_OUT, null));

        mockMvc.perform(get("/reservations/{id}/stay-extension", ID).param("newCheckOutDate", "2026-09-24").with(manager()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/reservations/" + ID + "/stay-extension/review\"")))
                .andExpect(content().string(containsString("value=\"2026-09-24\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*id=\"stay-extension-next\"[^>]*disabled.*"))));
    }

    /** Confirms every EXTEND_STAY route refuses users without the permission, including MANAGE_BOOKING and CHANGE_ROOM. */
    @Test
    void shouldForbidUsersWithoutExtendStay() throws Exception {
        for (String authority : List.of("PERM_CHECK_IN", "PERM_CHECK_OUT", "PERM_VIEW_BOOKING", "PERM_MANAGE_PAYMENT",
                "PERM_MANAGE_BOOKING", "PERM_CHANGE_ROOM")) {
            RequestPostProcessor other = user("u").authorities(new SimpleGrantedAuthority(authority));
            mockMvc.perform(get("/reservations/{id}/stay-extension", ID).with(other)).andExpect(status().isForbidden());
            mockMvc.perform(post("/reservations/{id}/stay-extension/review", ID).with(other).with(csrf())
                            .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(other).with(csrf())
                            .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/reservations/{id}/stay-extension/rooms/{roomId}/image", ID, ROOM_ID).with(other))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/reservations/{id}/stay-extension", ID).with(other).with(csrf())
                            .contentType("application/json")
                            .content("{\"expectedCurrentCheckOutDate\":\"2026-09-22\",\"newCheckOutDate\":\"2026-09-24\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(service, never()).review(any(), any(), anyBoolean());
        verify(service, never()).extend(any(), any());
    }

    /** Confirms the Review screen shows the Confirm Extension action only for a confirmable extension. */
    @Test
    void shouldRenderReviewWithConfirmOnlyWhenAvailable() throws Exception {
        when(service.review(eq(ID), any(), eq(false))).thenReturn(preview(State.AVAILABLE, NEW_OUT, null));

        mockMvc.perform(post("/reservations/{id}/stay-extension/review", ID).with(manager()).with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"stay-extension-confirm\"")))
                .andExpect(content().string(containsString("Confirm Extension")))
                .andExpect(content().string(containsString("2,000,000")))
                .andExpect(content().string(not(containsString("name=\"nightlyRate\""))))
                .andExpect(content().string(not(containsString("name=\"roomId\""))))
                .andExpect(content().string(not(containsString("id=\"stay-extension-folio\""))));
    }

    /** Confirms the folio impact appears on Review only when the caller may see payment data (MANAGE_PAYMENT). */
    @Test
    void shouldShowFolioImpactOnlyWithManagePayment() throws Exception {
        when(service.review(eq(ID), any(), eq(true))).thenReturn(preview(State.AVAILABLE, NEW_OUT, folio()));

        mockMvc.perform(post("/reservations/{id}/stay-extension/review", ID).with(user("m").authorities(
                                new SimpleGrantedAuthority("PERM_EXTEND_STAY"), new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT")))
                        .with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"stay-extension-folio\"")));
    }

    /** Confirms a stale Review re-renders the Select screen with the localized stale message (EN and VI). */
    @Test
    void shouldShowLocalizedStaleErrorOnTheSelectScreen() throws Exception {
        when(service.review(eq(ID), any(), eq(false)))
                .thenThrow(new StayExtensionException(Reason.STALE_CHECK_OUT_DATE, "stale"));
        when(service.preview(eq(ID), eq(NEW_OUT), eq(false))).thenReturn(preview(State.AVAILABLE, NEW_OUT, null));

        mockMvc.perform(post("/reservations/{id}/stay-extension/review", ID).with(manager()).with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-21").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The planned check-out changed since this form was opened")));
        mockMvc.perform(post("/reservations/{id}/stay-extension/review", ID).with(manager()).with(csrf())
                        .cookie(new Cookie("pms-lang", "vi"))
                        .param("expectedCurrentCheckOutDate", "2026-09-21").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngày trả phòng dự kiến đã thay đổi")));
    }

    /** Confirms a room conflict found at Review is shown on the Select screen with a Change Room route, and no Confirm. */
    @Test
    void shouldShowRoomConflictWithChangeRoomGuidance() throws Exception {
        when(service.review(eq(ID), any(), eq(false)))
                .thenThrow(new StayExtensionException(Reason.INVENTORY_CONFLICT, "conflict", "201"));
        when(service.preview(eq(ID), eq(NEW_OUT), eq(false))).thenReturn(preview(State.ROOM_CONFLICT, NEW_OUT, null));
        RequestPostProcessor withChangeRoom = user("staff").authorities(
                new SimpleGrantedAuthority("PERM_EXTEND_STAY"), new SimpleGrantedAuthority("PERM_CHANGE_ROOM"));

        mockMvc.perform(post("/reservations/{id}/stay-extension/review", ID).with(withChangeRoom).with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Room 201 is already booked or occupied")))
                .andExpect(content().string(containsString("/reservations/" + ID + "/rooms/" + ROOM_ID + "/change")))
                .andExpect(content().string(not(containsString("id=\"stay-extension-confirm\""))));
    }

    /** Confirms the Confirm step applies the extension and redirects (PRG), and requires CSRF. */
    @Test
    void shouldRequireCsrfAndRedirectOnSuccess() throws Exception {
        mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(manager())
                        .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isForbidden());
        when(service.extend(eq(ID), any())).thenReturn(new Response(ID, "R1", "CHECKED_IN", BigDecimal.TEN, "VND"));

        mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(manager()).with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        "/reservations/" + ID));
    }

    /** Confirms a recoverable rejection on Confirm returns to the Select screen with the proposed date, not a dead end. */
    @Test
    void shouldRecoverOnTheSelectScreenWhenConfirmIsRejected() throws Exception {
        when(service.extend(eq(ID), any())).thenThrow(new StayExtensionException(Reason.INVENTORY_CONFLICT, "conflict", "201"));
        when(service.preview(eq(ID), eq(NEW_OUT), eq(false))).thenReturn(preview(State.ROOM_CONFLICT, NEW_OUT, null));

        mockMvc.perform(post("/reservations/{id}/stay-extension", ID).with(manager()).with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-22").param("newCheckOutDate", "2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Room 201 is already booked or occupied")))
                .andExpect(content().string(containsString("name=\"expectedCurrentCheckOutDate\"")));
    }

    /** Confirms a missing date on Review is reported on the Select screen rather than applied. */
    @Test
    void shouldRejectMissingDateWithoutMutating() throws Exception {
        when(service.preview(eq(ID), eq(null), eq(false))).thenReturn(preview(State.NOT_SELECTED, null, null));

        mockMvc.perform(post("/reservations/{id}/stay-extension/review", ID).with(manager()).with(csrf())
                        .param("expectedCurrentCheckOutDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Select a new check-out date first.")));
        verify(service, never()).extend(any(), any());
    }

    /** Confirms the current room photo is served inline from the validated primary image, for the Stay's own room. */
    @Test
    void shouldServeTheCurrentRoomImage() throws Exception {
        when(service.currentRoomImage(ID, ROOM_ID)).thenReturn(
                new RoomImageFile(new ByteArrayResource("img".getBytes()), "image/jpeg", "room.jpg"));

        mockMvc.perform(get("/reservations/{id}/stay-extension/rooms/{roomId}/image", ID, ROOM_ID).with(manager()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Type", "image/jpeg"));
    }

    /** Confirms the REST route returns the structured status and maps a conflict to 409. */
    @Test
    void shouldExposeTheRestOperation() throws Exception {
        when(service.extend(eq(ID), any())).thenThrow(new StayExtensionException(Reason.INVENTORY_CONFLICT, "conflict", "201"));

        mockMvc.perform(post("/api/reservations/{id}/stay-extension", ID).with(manager()).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedCurrentCheckOutDate\":\"2026-09-22\",\"newCheckOutDate\":\"2026-09-24\"}"))
                .andExpect(status().isConflict());
        verify(service).extend(eq(ID), eq(new StayExtensionRequest(CURRENT_OUT, NEW_OUT)));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
