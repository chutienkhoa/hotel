package com.example.hotel.controller.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.dto.booking.response.ArrivalIssueCode;
import com.example.hotel.dto.booking.response.ArrivalIssueSeverity;
import com.example.hotel.dto.booking.response.ArrivalReadiness;
import com.example.hotel.dto.booking.response.ArrivalReadinessIssue;
import com.example.hotel.dto.booking.response.ArrivalReadinessState;
import com.example.hotel.dto.booking.response.CheckInReviewResponse;
import com.example.hotel.dto.booking.response.CheckInTiming;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.CheckInService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/** Verifies the Arrival Readiness section on the Check-in Review page: rendering, EN/VI labels and authorization. */
@WebMvcTest(CheckInPageController.class)
@Import({CheckInReadinessPageTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class CheckInReadinessPageTest {

    private static final UUID DIRTY_ROOM = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private com.example.hotel.service.booking.FrontDeskQueryService frontDeskQueryService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private com.example.hotel.service.booking.PrepaymentService prepaymentService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms blockers, ready rooms and warnings render in English with the affected room named. */
    @Test
    void shouldRenderEnglishReadinessWithAffectedRoom() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(review());

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Arrival Readiness")))
                .andExpect(content().string(containsString("Needs attention")))
                .andExpect(content().string(containsString("Blocker")))
                .andExpect(content().string(containsString("Room 102 needs cleaning (housekeeping required).")))
                .andExpect(content().string(containsString("Room 101 is ready.")))
                .andExpect(content().string(containsString("Passport image missing (optional).")))
                .andExpect(content().string(containsString("Arrival overdue")))
                .andExpect(content().string(not(containsString("Confirm Check-in</button>"))));
    }

    /** Confirms the Vietnamese labels are used when Vietnamese is selected. */
    @Test
    void shouldRenderVietnameseReadiness() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(review());

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn())
                        .cookie(new Cookie("pms-lang", "vi")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mức độ sẵn sàng đón khách")))
                .andExpect(content().string(containsString("Cần xử lý")))
                .andExpect(content().string(containsString("Phòng 102 cần dọn")))
                .andExpect(content().string(containsString("Phòng 101 đã sẵn sàng.")))
                .andExpect(content().string(containsString("Chưa có ảnh hộ chiếu")));
    }

    /** Confirms a room blocker offers Reassign Room (English and Vietnamese); a READY review offers none. */
    @Test
    void shouldOfferReassignRoomForRoomBlockersOnly() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(review());
        String link = "/check-in/reservations/" + RESERVATION_ID + "/rooms/" + DIRTY_ROOM + "/reassign";

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn()))
                .andExpect(content().string(containsString(link)))
                .andExpect(content().string(containsString("Reassign Room")));
        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn())
                        .cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Đổi phòng")));
        when(checkInService.review(RESERVATION_ID)).thenReturn(readyReview());
        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn()))
                .andExpect(content().string(not(containsString("/reassign"))));
    }

    /** Confirms the adult-capacity blockers render in English and Vietnamese with their arguments. */
    @Test
    void shouldRenderCapacityBlockersInEnglishAndVietnamese() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(build(false, new ArrivalReadiness(
                ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.NORMAL, List.of(
                        new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.INSUFFICIENT_ADULT_CAPACITY,
                                null, null, 3, 2, null),
                        new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.CAPACITY_NOT_CONFIGURED,
                                null, null, null, null, "Suite")))));

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn()))
                .andExpect(content().string(containsString("Insufficient room capacity: the reservation has 3 adults but the assigned rooms support only 2 adults.")))
                .andExpect(content().string(containsString("Room capacity is not configured for room type Suite.")))
                .andExpect(content().string(not(containsString("Confirm Check-in</button>"))));
        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn()).cookie(new Cookie("pms-lang", "vi")))
                .andExpect(content().string(containsString("Sức chứa phòng không đủ: đặt phòng có 3 người lớn nhưng các phòng được gán chỉ chứa tối đa 2 người lớn.")))
                .andExpect(content().string(containsString("Chưa cấu hình sức chứa cho loại phòng Suite.")));
    }

    /** Confirms a READY review offers the confirm action. */
    @Test
    void shouldOfferConfirmWhenReady() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(readyReview());

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(checkIn()))
                .andExpect(content().string(containsString("Ready for check-in")))
                .andExpect(content().string(containsString("Confirm Check-in")));
    }

    /** Confirms readiness is not exposed to users without CHECK_IN, including MANAGE_ROOM/HOUSEKEEPING holders. */
    @Test
    void shouldNotExposeReadinessWithoutCheckIn() throws Exception {
        when(checkInService.review(RESERVATION_ID)).thenReturn(review());

        mockMvc.perform(get("/check-in/reservations/{id}", RESERVATION_ID).with(user("hk").authorities(
                        new SimpleGrantedAuthority("PERM_MANAGE_HOUSEKEEPING"),
                        new SimpleGrantedAuthority("PERM_MANAGE_ROOM"),
                        new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor checkIn() {
        return user("staff").authorities(new SimpleGrantedAuthority("PERM_CHECK_IN"));
    }

    private static CheckInReviewResponse review() {
        return build(false, new ArrivalReadiness(ArrivalReadinessState.NEEDS_ATTENTION, CheckInTiming.LATE, List.of(
                new ArrivalReadinessIssue(ArrivalIssueSeverity.BLOCKER, ArrivalIssueCode.ROOM_DIRTY, "102", DIRTY_ROOM),
                new ArrivalReadinessIssue(ArrivalIssueSeverity.WARNING, ArrivalIssueCode.ARRIVAL_OVERDUE, null),
                new ArrivalReadinessIssue(ArrivalIssueSeverity.WARNING, ArrivalIssueCode.PASSPORT_MISSING, null),
                new ArrivalReadinessIssue(ArrivalIssueSeverity.INFO, ArrivalIssueCode.ROOM_READY, "101"))));
    }

    private static CheckInReviewResponse readyReview() {
        return build(true, new ArrivalReadiness(ArrivalReadinessState.READY, CheckInTiming.NORMAL, List.of(
                new ArrivalReadinessIssue(ArrivalIssueSeverity.INFO, ArrivalIssueCode.ROOM_READY, "101"))));
    }

    private static CheckInReviewResponse build(boolean eligible, ArrivalReadiness readiness) {
        return new CheckInReviewResponse(RESERVATION_ID, "R20260915-000001", "CONFIRMED", eligible,
                readiness.timing(), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 18),
                Instant.parse("2026-09-18T10:00:00Z"), BookingSource.DIRECT, null, UUID.randomUUID(), "Nguyen Van A",
                "GUEST-001", "Vietnam", false, List.of(), BigDecimal.TEN, "VND", readiness, 1, 0, List.of(),
                Instant.parse("2026-08-25T10:00:00Z"), null, null, null, "Nguyen Van A", null, null, true, null, 0L);
    }

    /** Enables method-security interception for this MVC slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
