package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.booking.response.AccompanyingGuestResponse;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalIssueSeverity;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessIssue;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.CheckInRoomLine;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.dto.booking.response.PrepaymentSummaryResponse;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifies the presentation contract of the rebuilt Check-in Review (Task 33 final layout): reservation identity,
 * Guest ID / Passport Number and nationality flag, passport document, room information, prepayment, the check-in
 * action and Vietnamese labels. Business rules (readiness, eligibility, permissions) are asserted unchanged.
 */
@WebMvcTest(CheckInPageController.class)
@Import({CheckInReviewPresentationTest.Config.class, I18nConfig.class})
class CheckInReviewPresentationTest {

    private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GUEST_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DIRTY_ROOM = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private com.example.hotel.service.booking.FrontDeskQueryService frontDeskQueryService;

    @MockitoBean
    private com.example.hotel.service.room.RoomImageService roomImageService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private com.example.hotel.service.booking.PrepaymentService prepaymentService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms identity, status, OTA source/reference, overview dates and the unchanged confirm action when ready. */
    @Test
    void shouldShowReservationIdentitySourceAndConfirmActionWhenReady() throws Exception {
        stub(review(true, true, false));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(staff()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("R20261002-000001")))
                .andExpect(content().string(containsString(">Confirmed</span>")))
                .andExpect(content().string(containsString("Agoda")))
                .andExpect(content().string(containsString("Ref: AG-1234567890")))
                .andExpect(content().string(containsString("28/09/2026 17:00")))
                .andExpect(content().string(containsString("05/10/2026")))
                .andExpect(content().string(containsString("id=\"readiness-state\"")))
                .andExpect(content().string(containsString("Ready for check-in")))
                .andExpect(content().string(containsString("Confirm Check-in</button>")))
                .andExpect(content().string(not(containsString("check-in-blocked-note"))));
    }

    /** Confirms a blocked arrival keeps backend blockers, the room-linked Reassign Room, no confirm and a blocked note. */
    @Test
    void shouldExplainBlockersKeepReassignRoomAndOfferNoConfirmWhenBlocked() throws Exception {
        stub(review(false, true, true));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(staff()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Needs attention")))
                .andExpect(content().string(containsString("Room DEMO-206 needs cleaning (housekeeping required).")))
                .andExpect(content().string(containsString(
                        "/check-in/reservations/" + RESERVATION_ID + "/rooms/" + DIRTY_ROOM + "/reassign")))
                .andExpect(content().string(containsString("id=\"check-in-blocked-note\"")))
                .andExpect(content().string(not(containsString("Confirm Check-in</button>"))))
                .andExpect(content().string(containsString("Dirty")));
    }

    /** Confirms ID / Passport Number, nationality with its flag, and no avatar are rendered in Guest Information. */
    @Test
    void shouldShowGuestIdPassportNumberAndNationalityFlag() throws Exception {
        stub(review(true, true, false));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(staff()))
                .andExpect(content().string(containsString("ID / Passport Number")))
                .andExpect(content().string(containsString("id=\"guest-id-document\">B1234567<")))
                .andExpect(content().string(containsString("class=\"cir-flag\"")))
                .andExpect(content().string(containsString("🇻🇳")))
                .andExpect(content().string(containsString("Vietnam")))
                .andExpect(content().string(containsString("15/08/1990")))
                .andExpect(content().string(not(containsString("cir-avatar"))));
    }

    /** Confirms a guest without an ID / Passport Number shows a dash, not a made-up value. */
    @Test
    void shouldShowDashWhenGuestHasNoIdDocumentNumber() throws Exception {
        CheckInReviewResponse review = review(true, true, false);
        stub(review);
        when(guestQueryService.findForReservationCreation(any())).thenReturn(new GuestLookupResponse(
                GUEST_ID, "DEMO-G001", "Nguyen Van Minh", null, null, "Vietnam", null, null));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(staff()))
                .andExpect(content().string(containsString("id=\"guest-id-document\">—<")));
    }

    /** Confirms the passport is a compact document row with the secure View Passport link, and an empty state without one. */
    @Test
    void shouldPresentThePassportAsADocumentOrAnEmptyState() throws Exception {
        CheckInReviewResponse withPassport = review(true, true, false);
        stub(withPassport);
        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(staff()))
                .andExpect(content().string(containsString("cir-doc")))
                .andExpect(content().string(containsString(
                        "href=\"/guests/" + GUEST_ID + "/documents/" + withPassport.firstPassportDocumentId() + "/passport\"")))
                .andExpect(content().string(containsString("View Passport")))
                .andExpect(content().string(not(containsString("No passport image on file."))));

        stub(review(true, false, false));
        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(staff()))
                .andExpect(content().string(containsString("No passport image on file.")))
                .andExpect(content().string(not(containsString("View Passport"))))
                .andExpect(content().string(not(containsString("Upload Passport"))));
    }

    /** Confirms each assigned room shows number, type, capacity, live status, dates, nights, rate and total in VND. */
    @Test
    void shouldShowRoomAndStayInformationFromTheReadModel() throws Exception {
        stub(review(true, true, true));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(staff()))
                .andExpect(content().string(containsString("DEMO-205")))
                .andExpect(content().string(containsString("Double Room")))
                .andExpect(content().string(containsString("Capacity: 2")))
                .andExpect(content().string(containsString("Available")))
                .andExpect(content().string(containsString("href=\"/rooms/" + ROOM_ID + "\"")))
                .andExpect(content().string(containsString("Rate (VND)")))
                .andExpect(content().string(containsString("1,200,000")))
                .andExpect(content().string(containsString("1,500,000")))
                .andExpect(content().string(containsString("02/10/2026")))
                .andExpect(content().string(containsString("03/10/2026")))
                .andExpect(content().string(containsString("04/10/2026")))
                .andExpect(content().string(containsString("Total (3 nights)")))
                .andExpect(content().string(containsString("8,100,000 VND")))
                .andExpect(content().string(not(containsString("Floor"))));
    }

    /** Confirms prepayment amounts include VND for a MANAGE_PAYMENT user and the section is absent without it. */
    @Test
    void shouldShowPrepaymentOnlyToThoseAllowedAndWithVnd() throws Exception {
        stub(review(true, true, false));
        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(staff()))
                .andExpect(content().string(containsString("id=\"prepayment-summary\"")))
                .andExpect(content().string(containsString("1,000,000 VND")))
                .andExpect(content().string(containsString("2,600,000 VND")));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID)
                        .with(user("clerk").authorities(new SimpleGrantedAuthority("PERM_CHECK_IN"))))
                .andExpect(content().string(not(containsString("id=\"prepayment-summary\""))));
    }

    /** Confirms the Vietnamese labels, including the ID / Passport Number label and the confirm action. */
    @Test
    void shouldRenderVietnameseLabels() throws Exception {
        stub(review(true, true, false));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(staff()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Xem lại nhận phòng")))
                .andExpect(content().string(containsString("Thông tin khách")))
                .andExpect(content().string(containsString("Số CCCD / Hộ chiếu")))
                .andExpect(content().string(containsString("Xác nhận nhận phòng</button>")))
                .andExpect(content().string(containsString("Mã tham chiếu: AG-1234567890")))
                .andExpect(content().string(containsString("Thanh toán trước")))
                .andExpect(content().string(containsString("Hộ chiếu")));
    }

    private void stub(CheckInReviewResponse review) {
        when(checkInService.review(RESERVATION_ID)).thenReturn(review);
        when(guestQueryService.findForReservationCreation(any())).thenReturn(new GuestLookupResponse(
                GUEST_ID, "DEMO-G001", "Nguyen Van Minh", "guest@example.com", "+84 912 345 678", "Vietnam",
                LocalDate.of(1990, 8, 15), "B1234567"));
        when(prepaymentService.summary(any())).thenReturn(new PrepaymentSummaryResponse("VND", review.totalAmount(),
                new BigDecimal("1000000"), BigDecimal.ZERO, new BigDecimal("1000000"),
                review.totalAmount().subtract(new BigDecimal("1000000")), List.of()));
    }

    private static RequestPostProcessor staff() {
        return user("staff").authorities(
                new SimpleGrantedAuthority("PERM_CHECK_IN"), new SimpleGrantedAuthority("PERM_MANAGE_GUEST"),
                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"), new SimpleGrantedAuthority("PERM_MANAGE_ROOM"));
    }

    private static CheckInReviewResponse review(boolean ready, boolean passport, boolean twoRooms) {
        List<ArrivalReadinessIssue> issues = ready
                ? List.of(new ArrivalReadinessIssue(ArrivalIssueSeverity.INFO, ArrivalIssueCode.ROOM_READY, "DEMO-205"))
                : List.of(new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.ROOM_DIRTY, "DEMO-206", DIRTY_ROOM));
        ArrivalReadiness readiness = new ArrivalReadiness(
                ready ? ArrivalReadinessState.READY : ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.NORMAL, issues);
        List<CheckInRoomLine> rooms = new ArrayList<>();
        rooms.add(new CheckInRoomLine(ROOM_ID, "DEMO-205", "Double Room", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5),
                new BigDecimal("1200000"), 3, new BigDecimal("3600000"), 2, "AVAILABLE"));
        if (twoRooms) {
            rooms.add(new CheckInRoomLine(DIRTY_ROOM, "DEMO-206", "Deluxe Double", LocalDate.of(2026, 10, 2),
                    LocalDate.of(2026, 10, 5), new BigDecimal("1500000"), 3, new BigDecimal("4500000"), 3,
                    ready ? "AVAILABLE" : "DIRTY"));
        }
        return new CheckInReviewResponse(RESERVATION_ID, "R20261002-000001", "CONFIRMED", ready, readiness.timing(),
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 2),
                Instant.parse("2026-10-02T10:00:00Z"), BookingSource.AGODA, "AG-1234567890", GUEST_ID, "Nguyen Van Minh",
                "DEMO-G001", "Vietnam", passport, rooms, new BigDecimal(twoRooms ? "8100000" : "3600000"), "VND", readiness, 2,
                1, List.of(new AccompanyingGuestResponse(UUID.randomUUID(), "DEMO-G002")),
                Instant.parse("2026-09-28T10:00:00Z"), LocalDate.of(1990, 8, 15), "+84 912 345 678", "guest@example.com",
                "Nguyen Van Minh", "+84 912 345 678", "guest@example.com", false, passport ? UUID.randomUUID() : null, 0L);
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class Config {}
}
