package com.example.hotel.controller.booking;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.RoomChangeCandidateResponse;
import com.example.hotel.dto.booking.response.RoomChangeFormResponse;
import com.example.hotel.dto.booking.response.RoomChangeReviewResponse;
import com.example.hotel.dto.booking.response.RoomChangeReviewRoom;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.room.response.RoomImageFile;
import com.example.hotel.entity.booking.RoomChangeReason;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.RoomChangeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Room Change MVC authorization and CSRF requirements. */
@WebMvcTest(RoomChangePageController.class)
@Import(RoomChangePageControllerTest.MethodSecurityTestConfiguration.class)
class RoomChangePageControllerTest {

    private static final UUID RESERVATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROOM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** Selects Vietnamese through the application language cookie, the same mechanism the header switcher uses. */
    private static final Cookie VIETNAMESE = new Cookie("pms-lang", "vi");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomChangeService roomChangeService;

    @MockitoBean
    private JwtService jwtService;

    /** Confirms ADMIN, MANAGER, and STAFF can all access the Room Change form. */
    @Test
    void shouldAllowAdminManagerAndStaffToAccessRoomChangeForm() throws Exception {
        when(roomChangeService.candidateRooms(RESERVATION_ID, ROOM_ID)).thenReturn(List.of());

        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("admin").authorities(changeRoomAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("manager").authorities(changeRoomAuthority())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isOk());
    }

    /** Confirms a user without CHANGE_ROOM cannot access the Room Change form. */
    @Test
    void shouldForbidUserWithoutChangeRoomPermission() throws Exception {
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("viewer").authorities(new SimpleGrantedAuthority("PERM_VIEW_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms MANAGE_BOOKING alone does not substitute for CHANGE_ROOM. */
    @Test
    void shouldForbidManageBookingAloneWithoutChangeRoom() throws Exception {
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("editor").authorities(new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms CHECK_IN alone does not substitute for CHANGE_ROOM. */
    @Test
    void shouldForbidCheckInAloneWithoutChangeRoom() throws Exception {
        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("frontdesk").authorities(new SimpleGrantedAuthority("PERM_CHECK_IN"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms the review POST requires CSRF like every other mutating action. */
    @Test
    void shouldRequireCsrfForReviewPost() throws Exception {
        mockMvc.perform(post("/reservations/{id}/rooms/{roomId}/change/review", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms the confirm POST requires CSRF like every other mutating action. */
    @Test
    void shouldRequireCsrfForConfirmPost() throws Exception {
        mockMvc.perform(post("/reservations/{id}/rooms/{roomId}/change/confirm", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isForbidden());
    }

    /** Confirms a structurally valid review submission renders the Review template. */
    @Test
    void shouldRenderReviewOnValidSubmission() throws Exception {
        UUID targetRoomId = UUID.randomUUID();
        when(roomChangeService.review(
                        org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                        org.mockito.ArgumentMatchers.eq(ROOM_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(reviewView(targetRoomId, true));

        mockMvc.perform(post("/reservations/{id}/rooms/{roomId}/change/review", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority()))
                        .with(csrf())
                        .param("targetRoomId", targetRoomId.toString())
                        .param("reason", "GUEST_REQUEST"))
                .andExpect(status().isOk());
    }

    /** Confirms the Review compares the current and new room, with the status each will get after confirmation. */
    @Test
    void shouldRenderCurrentAndNewRoomComparisonWithResultingStates() throws Exception {
        UUID targetRoomId = UUID.randomUUID();
        when(roomChangeService.review(
                        org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                        org.mockito.ArgumentMatchers.eq(ROOM_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(reviewView(targetRoomId, true));

        mockMvc.perform(reviewPost(targetRoomId, "GUEST_REQUEST"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("201")))
                .andExpect(content().string(containsString("305")))
                .andExpect(content().string(containsString("Double Room")))
                .andExpect(content().string(containsString("Triple Room")))
                .andExpect(content().string(containsString("Đang có khách")))
                .andExpect(content().string(containsString("Trống")))
                .andExpect(content().string(containsString("Sẽ chuyển thành")))
                .andExpect(content().string(containsString("Cần dọn")))
                .andExpect(content().string(containsString("status-badge--dirty")))
                .andExpect(content().string(containsString("status-badge--occupied")))
                .andExpect(content().string(containsString("Điều gì sẽ xảy ra")))
                .andExpect(content().string(containsString("Phòng hiện tại 201 sẽ chuyển thành")))
                .andExpect(content().string(containsString("Phòng mới 305 sẽ chuyển thành")))
                .andExpect(content().string(containsString("Nguyễn Văn An")))
                .andExpect(content().string(containsString("Ngày trả phòng dự kiến")))
                .andExpect(content().string(containsString("/change/images/" + targetRoomId)));
    }

    /** Confirms the submitted reason is shown with its localized label, never the raw enum constant. */
    @Test
    void shouldShowLocalizedReasonLabelInsteadOfRawEnum() throws Exception {
        UUID targetRoomId = UUID.randomUUID();
        when(roomChangeService.review(
                        org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                        org.mockito.ArgumentMatchers.eq(ROOM_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(reviewView(targetRoomId, true));

        mockMvc.perform(reviewPost(targetRoomId, "GUEST_REQUEST"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Yêu cầu của khách")))
                .andExpect(content().string(not(containsString(">GUEST_REQUEST<"))));
    }

    /** Confirms the Review states that pricing, charges and payments are unchanged and lists the real effects. */
    @Test
    void shouldExplainThatPricingAndChargesAreUnchanged() throws Exception {
        UUID targetRoomId = UUID.randomUUID();
        when(roomChangeService.review(
                        org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                        org.mockito.ArgumentMatchers.eq(ROOM_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(reviewView(targetRoomId, true));

        mockMvc.perform(reviewPost(targetRoomId, "GUEST_REQUEST"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Giá của đặt phòng hiện tại sẽ không thay đổi.")))
                .andExpect(content().string(containsString("Các khoản phí và thanh toán hiện có sẽ không thay đổi.")))
                .andExpect(content().string(containsString("Lịch sử phòng sẽ ghi nhận lần đổi phòng này.")))
                .andExpect(content().string(not(containsString("Add Charge"))))
                .andExpect(content().string(not(containsString("Adjustment"))));
    }

    /** Confirms the Review serves only the safe Room Change image route and falls back to the bed icon otherwise. */
    @Test
    void shouldServeOnlyTheRoomChangeImageRouteAndFallBackWhenNoImage() throws Exception {
        UUID targetRoomId = UUID.randomUUID();
        when(roomChangeService.review(
                        org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                        org.mockito.ArgumentMatchers.eq(ROOM_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(reviewView(targetRoomId, false));

        mockMvc.perform(reviewPost(targetRoomId, "GUEST_REQUEST"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/change/images/" + targetRoomId))))
                .andExpect(content().string(containsString("room-change-compare__photo")));
    }

    /** Confirms the Review exposes no editable business fields beyond the hidden values the Confirm step resubmits. */
    @Test
    void shouldNotRenderUnsupportedControlsOnReview() throws Exception {
        UUID targetRoomId = UUID.randomUUID();
        when(roomChangeService.review(
                        org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                        org.mockito.ArgumentMatchers.eq(ROOM_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(reviewView(targetRoomId, true));

        mockMvc.perform(reviewPost(targetRoomId, "GUEST_REQUEST"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<select"))))
                .andExpect(content().string(not(containsString("<textarea"))))
                .andExpect(content().string(not(containsString("type=\"number\""))))
                .andExpect(content().string(not(containsString("type=\"text\""))));
    }

    /** Confirms the success feedback names both rooms, localized through MessageSource. */
    @Test
    void shouldFlashLocalizedSuccessWithBothRoomNumbers() throws Exception {
        UUID targetRoomId = UUID.randomUUID();
        when(roomChangeService.review(
                        org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                        org.mockito.ArgumentMatchers.eq(ROOM_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(reviewView(targetRoomId, true));
        when(roomChangeService.changeRoom(
                        org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                        org.mockito.ArgumentMatchers.eq(ROOM_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Response(RESERVATION_ID, "R20260917-000001", "CHECKED_IN", BigDecimal.ZERO, "VND"));

        mockMvc.perform(post("/reservations/{id}/rooms/{roomId}/change/confirm", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority()))
                        .with(csrf())
                        .cookie(VIETNAMESE)
                        .param("targetRoomId", targetRoomId.toString())
                        .param("reason", "GUEST_REQUEST"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "Đổi phòng thành công: 201 → 305"));
    }

    /** Builds a Review POST with the given target room and reason. */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reviewPost(
            UUID targetRoomId, String reason) {
        return post("/reservations/{id}/rooms/{roomId}/change/review", RESERVATION_ID, ROOM_ID)
                .with(user("staff").authorities(changeRoomAuthority()))
                .with(csrf())
                .cookie(VIETNAMESE)
                .param("targetRoomId", targetRoomId.toString())
                .param("reason", reason);
    }

    /**
     * Builds a Review view: the current room 201 (occupied, becomes DIRTY) and the target room 305 (available,
     * becomes OCCUPIED), with the stay facts and a notes value.
     */
    private static RoomChangeReviewResponse reviewView(UUID targetRoomId, boolean targetHasImage) {
        return new RoomChangeReviewResponse(
                RESERVATION_ID,
                "R20260917-000001",
                "Nguyễn Văn An",
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 22),
                5,
                2,
                1,
                new RoomChangeReviewRoom(ROOM_ID, "201", "Double Room", 2, "OCCUPIED", "DIRTY", false),
                new RoomChangeReviewRoom(targetRoomId, "305", "Triple Room", 3, "AVAILABLE", "OCCUPIED", targetHasImage),
                RoomChangeReason.GUEST_REQUEST,
                null);
    }

    /** Confirms the form shows the current room context and one selectable radio per eligible replacement room. */
    @Test
    void shouldRenderCurrentRoomAndSelectableCandidateRooms() throws Exception {
        UUID targetRoomId = UUID.randomUUID();
        when(roomChangeService.formView(RESERVATION_ID, ROOM_ID)).thenReturn(new RoomChangeFormResponse(
                RESERVATION_ID,
                "R20260917-000001",
                ROOM_ID,
                "201",
                "Double Room",
                "OCCUPIED",
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 22),
                5,
                2,
                1,
                new BigDecimal("1000000"),
                new BigDecimal("5000000"),
                "VND",
                false,
                true,
                0));
        when(roomChangeService.candidateRooms(RESERVATION_ID, ROOM_ID)).thenReturn(List.of(
                new RoomChangeCandidateResponse(targetRoomId, "305", "Triple Room", 3, false)));

        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("R20260917-000001")))
                .andExpect(content().string(containsString("Double Room")))
                .andExpect(content().string(containsString("Triple Room")))
                .andExpect(content().string(containsString("value=\"" + targetRoomId + "\"")))
                .andExpect(content().string(containsString("/css/reservation/room-change.css")));
    }

    /** Confirms a CHANGE_ROOM user can read the current room's primary image through the Change Room boundary. */
    @Test
    void shouldServePrimaryImageToChangeRoomUser() throws Exception {
        when(roomChangeService.changeRoomImage(RESERVATION_ID, ROOM_ID, ROOM_ID))
                .thenReturn(new RoomImageFile(new ByteArrayResource(new byte[] {1, 2, 3}), "image/png", "room.png"));

        mockMvc.perform(get("/reservations/{id}/rooms/{currentRoomId}/change/images/{roomId}",
                        RESERVATION_ID, ROOM_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Disposition", containsString("inline")));
    }

    /** Confirms the image boundary is not available without the CHANGE_ROOM permission. */
    @Test
    void shouldForbidRoomImageWithoutChangeRoomPermission() throws Exception {
        mockMvc.perform(get("/reservations/{id}/rooms/{currentRoomId}/change/images/{roomId}",
                        RESERVATION_ID, ROOM_ID, ROOM_ID)
                        .with(user("guest").authorities(new SimpleGrantedAuthority("PERM_MANAGE_ROOM"))))
                .andExpect(status().isForbidden());
    }

    /** Confirms an image outside the workflow or without a primary image is reported as not found. */
    @Test
    void shouldReturnNotFoundForRoomImageOutsideWorkflow() throws Exception {
        UUID otherRoomId = UUID.randomUUID();
        when(roomChangeService.changeRoomImage(RESERVATION_ID, ROOM_ID, otherRoomId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Room image not found"));

        mockMvc.perform(get("/reservations/{id}/rooms/{currentRoomId}/change/images/{roomId}",
                        RESERVATION_ID, ROOM_ID, otherRoomId)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isNotFound());
    }

    /** Confirms rooms without a primary image render the neutral fallback, and only real images get an image URL. */
    @Test
    void shouldRenderImageUrlOnlyForRoomsWithPrimaryImage() throws Exception {
        UUID withImage = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID withoutImage = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(roomChangeService.formView(RESERVATION_ID, ROOM_ID)).thenReturn(openFormView(false));
        when(roomChangeService.candidateRooms(RESERVATION_ID, ROOM_ID)).thenReturn(List.of(
                new RoomChangeCandidateResponse(withImage, "301", "Twin Room", 2, true),
                new RoomChangeCandidateResponse(withoutImage, "302", "Twin Room", 2, false)));

        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/change/images/" + withImage)))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("/change/images/" + withoutImage))));
    }

    /** Confirms a closed date window renders a blocked state with no selectable replacement room. */
    @Test
    void shouldRenderBlockedStateWithoutSelectableCandidates() throws Exception {
        when(roomChangeService.formView(RESERVATION_ID, ROOM_ID)).thenReturn(openFormView(false, false));
        when(roomChangeService.candidateRooms(RESERVATION_ID, ROOM_ID)).thenReturn(List.of(
                new RoomChangeCandidateResponse(UUID.randomUUID(), "389", "Twin Room", 2, false)));

        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"room-change-blocked\"")))
                .andExpect(content().string(containsString("room-change-grid--blocked")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"targetRoomId\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("389"))));
    }

    /** Builds an open Room Change form view for the test room. */
    private static RoomChangeFormResponse openFormView(boolean currentRoomHasImage) {
        return openFormView(currentRoomHasImage, true);
    }

    /** Builds the Room Change form view with the supplied date-window and image state. */
    private static RoomChangeFormResponse openFormView(boolean currentRoomHasImage, boolean roomChangeOpen) {
        return new RoomChangeFormResponse(
                RESERVATION_ID,
                "R20260917-000001",
                ROOM_ID,
                "201",
                "Double Room",
                "OCCUPIED",
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 22),
                5,
                2,
                1,
                new BigDecimal("1000000"),
                new BigDecimal("5000000"),
                "VND",
                currentRoomHasImage,
                roomChangeOpen,
                0);
    }

    /** Confirms Stay Information reuses the Reservation Detail date classes and shows the overdue suffix. */
    @Test
    void shouldRenderStayDatesWithSharedClassesAndOverdueSuffix() throws Exception {
        RoomChangeFormResponse base = openFormView(false);
        when(roomChangeService.formView(RESERVATION_ID, ROOM_ID)).thenReturn(new RoomChangeFormResponse(
                base.reservationId(), base.reservationNumber(), base.currentRoomId(), base.currentRoomNumber(),
                base.currentRoomTypeName(), base.currentRoomStatus(), base.checkInDate(), base.checkOutDate(),
                base.nights(), base.adultCount(), base.childCount(), base.nightlyRate(), base.totalAmount(),
                base.currency(), false, false, 3));

        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"date-value--check-in\"")))
                .andExpect(content().string(containsString("class=\"date-value--check-out\"")))
                .andExpect(content().string(containsString("(3 days overdue)")));
    }

    /** Confirms no overdue suffix is rendered when the stay is not overdue. */
    @Test
    void shouldNotRenderOverdueSuffixWhenNotOverdue() throws Exception {
        when(roomChangeService.formView(RESERVATION_ID, ROOM_ID)).thenReturn(openFormView(false));

        mockMvc.perform(get("/reservations/{id}/rooms/{roomId}/change", RESERVATION_ID, ROOM_ID)
                        .with(user("staff").authorities(changeRoomAuthority())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("overdue)"))));
    }

    /** Builds the CHANGE_ROOM authority. */
    private static List<SimpleGrantedAuthority> changeRoomAuthority() {
        return List.of(new SimpleGrantedAuthority("PERM_CHANGE_ROOM"));
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
