package com.example.hotel.controller.booking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import com.example.hotel.config.I18nConfig;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.security.JwtService;
import com.example.hotel.service.booking.FolioReconciliationService;
import com.example.hotel.service.booking.PrepaymentService;
import com.example.hotel.service.booking.ReservationActivityQueryService;
import com.example.hotel.service.booking.ReservationDetailEligibilityService;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.StayBalanceService;
import com.example.hotel.service.booking.StayExtensionService;
import com.example.hotel.service.booking.StayQueryService;
import com.example.hotel.service.booking.StayRoomAssignmentQueryService;
import com.example.hotel.service.customer.GuestQueryService;
import com.example.hotel.service.room.RoomQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies how Cancel, Mark as No-show and Confirm report their outcome to Reservation Detail: a prepayment rejection
 * reaches the shared error dialog with {@code View Prepayments} only for users who may open it, other rejections are
 * plain business errors, and invalid input never reaches the service.
 */
@WebMvcTest(ReservationPageController.class)
@Import({ReservationCancelNoShowFeedbackTest.MethodSecurityTestConfiguration.class, I18nConfig.class})
class ReservationCancelNoShowFeedbackTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private ReservationQueryService reservationQueryService;

    @MockitoBean
    private GuestQueryService guestQueryService;

    @MockitoBean
    private RoomQueryService roomQueryService;

    @MockitoBean
    private StayQueryService stayQueryService;

    @MockitoBean
    private StayBalanceService stayBalanceService;

    @MockitoBean
    private StayRoomAssignmentQueryService stayRoomAssignmentQueryService;

    @MockitoBean
    private StayExtensionService stayExtensionService;

    @MockitoBean
    private FolioReconciliationService folioReconciliationService;

    @MockitoBean
    private PrepaymentService prepaymentService;

    @MockitoBean
    private ReservationActivityQueryService reservationActivityQueryService;

    @MockitoBean
    private ReservationDetailEligibilityService detailEligibilityService;

    @MockitoBean
    private JwtService jwtService;

    private static RequestPostProcessor bookingAndPayment() {
        return user("m").authorities(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"), new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"),
                new SimpleGrantedAuthority("PERM_MANAGE_PAYMENT"));
    }

    private static RequestPostProcessor bookingOnly() {
        return user("b").authorities(
                new SimpleGrantedAuthority("PERM_VIEW_BOOKING"), new SimpleGrantedAuthority("PERM_MANAGE_BOOKING"));
    }

    private static LocalizedResponseStatusException prepaymentBlock(String key) {
        return new LocalizedResponseStatusException(HttpStatus.CONFLICT, key, "blocked");
    }

    /** Confirms a cancellation blocked by an active prepayment explains it and offers View Prepayments to a payer. */
    @Test
    void shouldOfferViewPrepaymentsWhenAnActivePrepaymentBlocksCancellation() throws Exception {
        doThrow(prepaymentBlock("payment.prepayment.error.blocksCancel")).when(reservationService).cancel(eq(ID), any());

        mockMvc.perform(post("/reservations/{id}/cancel", ID).param("cancellationReasonCode", "GUEST_REQUEST")
                        .with(bookingAndPayment()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + ID))
                .andExpect(flash().attribute("errorMessage", "This reservation has an active prepayment. The prepayment must "
                        + "be refunded or voided before the reservation can be cancelled."))
                .andExpect(flash().attribute("feedbackActionUrl", "/reservations/" + ID + "/prepayments"))
                .andExpect(flash().attribute("feedbackActionLabel", "View Prepayments"));
    }

    /** Confirms the same rejection never links to a page the user may not open. */
    @Test
    void shouldNotOfferViewPrepaymentsWithoutManagePayment() throws Exception {
        doThrow(prepaymentBlock("payment.prepayment.error.blocksCancel")).when(reservationService).cancel(eq(ID), any());

        mockMvc.perform(post("/reservations/{id}/cancel", ID).param("cancellationReasonCode", "GUEST_REQUEST")
                        .with(bookingOnly()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + ID))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(flash().attributeCount(1));
    }

    /** Confirms a no-show blocked by an active prepayment uses the same dialog treatment. */
    @Test
    void shouldOfferViewPrepaymentsWhenAnActivePrepaymentBlocksNoShow() throws Exception {
        doThrow(prepaymentBlock("payment.prepayment.error.blocksNoShow")).when(reservationService).noShow(eq(ID), any());

        mockMvc.perform(post("/reservations/{id}/no-show", ID).param("noShowReason", "Did not arrive")
                        .with(bookingAndPayment()).with(csrf()))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(flash().attribute("feedbackActionUrl", "/reservations/" + ID + "/prepayments"));
    }

    /** Confirms any other rejection is an ordinary business error without a recovery link. */
    @Test
    void shouldReportOtherRejectionsWithoutARecoveryLink() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Invalid reservation state transition"))
                .when(reservationService).cancel(eq(ID), any());

        mockMvc.perform(post("/reservations/{id}/cancel", ID).param("cancellationReasonCode", "GUEST_REQUEST")
                        .with(bookingAndPayment()).with(csrf()))
                .andExpect(flash().attribute("errorMessage", "Invalid reservation state transition"))
                .andExpect(flash().attributeCount(1));
    }

    /** Confirms a missing reason is rejected before the service is called and reported through the shared dialog. */
    @Test
    void shouldRejectMissingReasonsWithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/reservations/{id}/cancel", ID).with(bookingAndPayment()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + ID))
                .andExpect(flash().attributeExists("errorMessage"));
        mockMvc.perform(post("/reservations/{id}/no-show", ID).with(bookingAndPayment()).with(csrf()))
                .andExpect(flash().attributeExists("errorMessage"));
        verify(reservationService, never()).cancel(any(), any());
        verify(reservationService, never()).noShow(any(), any());
    }

    /** Confirms a successful Confirm reports a localized success message and returns to Detail. */
    @Test
    void shouldConfirmWithALocalizedSuccessMessage() throws Exception {
        mockMvc.perform(post("/reservations/{id}/confirm", ID).with(bookingOnly()).with(csrf()))
                .andExpect(redirectedUrl("/reservations/" + ID))
                .andExpect(flash().attribute("successMessage", "Reservation confirmed successfully."));
        verify(reservationService).confirm(ID);
    }

    /** Confirms users without MANAGE_BOOKING are forbidden from every Detail lifecycle POST. */
    @Test
    void shouldForbidLifecycleActionsWithoutManageBooking() throws Exception {
        RequestPostProcessor viewer = user("v").authorities(List.of(new SimpleGrantedAuthority("PERM_VIEW_BOOKING")));

        for (String path : List.of("confirm", "cancel", "no-show")) {
            mockMvc.perform(post("/reservations/{id}/" + path, ID).with(viewer).with(csrf()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
    }

    /** Enables method-security interception for this MVC authorization test slice. */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {}
}
