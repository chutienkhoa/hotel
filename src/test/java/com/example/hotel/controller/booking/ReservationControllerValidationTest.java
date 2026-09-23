package com.example.hotel.controller.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.exception.ApiExceptionHandler;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Kiểm tra Bean Validation tại API tạo reservation. */
class ReservationControllerValidationTest {
    private MockMvc mvc;
    private ReservationService reservationService;

    /** Khởi tạo MockMvc với controller và exception handler cho từng test. */
    @BeforeEach
    void setup() {
        reservationService = mock(ReservationService.class);
        mvc =
                MockMvcBuilders.standaloneSetup(new ReservationController(
                                reservationService, mock(ReservationQueryService.class)))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    /** Xác nhận request không có phòng bị từ chối. */
    @Test
    void rejectsEmptyRoomList() {
        String body =
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"adultCount\":2,\"childCount\":1,\"source\":\"DIRECT\",\"currency\":\"VND\",\"rooms\":[]}";
        assertDoesNotThrow(
                () ->
                        mvc.perform(
                                        post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isBadRequest()));
    }

    /** Xác nhận request có giá mỗi đêm bằng không bị từ chối. */
    @Test
    void rejectsZeroNightlyRate() {
        String body =
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"adultCount\":2,\"childCount\":1,\"source\":\"DIRECT\",\"currency\":\"VND\",\"rooms\":[{\"roomId\":\"22222222-2222-2222-2222-222222222222\",\"nightlyRate\":0}]}";
        assertDoesNotThrow(
                () ->
                        mvc.perform(
                                        post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isBadRequest()));
    }

    /** Confirms source is mandatory for every newly created reservation. */
    @Test
    void rejectsReservationWithoutSource() {
        String body =
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"adultCount\":2,\"childCount\":1,\"currency\":\"VND\",\"rooms\":[{\"roomId\":\"22222222-2222-2222-2222-222222222222\",\"nightlyRate\":100000}]}";

        assertDoesNotThrow(
                () -> mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest()));
    }

    /** Confirms an OTA source without an OTA booking reference is rejected. */
    @Test
    void rejectsOtaSourceWithoutOtaBookingReference() {
        String body =
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"adultCount\":2,\"childCount\":1,\"source\":\"AGODA\",\"currency\":\"VND\",\"rooms\":[{\"roomId\":\"22222222-2222-2222-2222-222222222222\",\"nightlyRate\":100000}]}";

        assertDoesNotThrow(
                () -> mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest()));
    }

    /** Confirms a whitespace-only OTA booking reference is rejected for an OTA source. */
    @Test
    void rejectsWhitespaceOnlyOtaBookingReferenceForOtaSource() {
        String body =
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"adultCount\":2,\"childCount\":1,\"source\":\"BOOKING_COM\",\"otaBookingReference\":\"   \",\"currency\":\"VND\",\"rooms\":[{\"roomId\":\"22222222-2222-2222-2222-222222222222\",\"nightlyRate\":100000}]}";

        assertDoesNotThrow(
                () -> mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest()));
    }

    /** Confirms a DIRECT reservation is accepted without an OTA booking reference. */
    @Test
    void allowsDirectSourceWithoutOtaBookingReference() throws Exception {
        when(reservationService.create(any()))
                .thenReturn(new Response(UUID.randomUUID(), "R20260911-000002", "DRAFT", BigDecimal.ONE, "VND"));
        String body =
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"adultCount\":2,\"childCount\":1,\"source\":\"DIRECT\",\"currency\":\"VND\",\"rooms\":[{\"roomId\":\"22222222-2222-2222-2222-222222222222\",\"nightlyRate\":100000}]}";

        mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    /** Confirms that a client-supplied reservation number cannot replace the backend-generated value. */
    @Test
    void ignoresClientSuppliedReservationNumber() throws Exception {
        when(reservationService.create(any()))
                .thenReturn(
                        new Response(
                                UUID.randomUUID(),
                                "R20260911-000001",
                                "DRAFT",
                                BigDecimal.ONE,
                                "VND"));
        String body =
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"adultCount\":2,\"childCount\":1,\"source\":\"DIRECT\",\"currency\":\"VND\",\"rooms\":[{\"roomId\":\"22222222-2222-2222-2222-222222222222\",\"nightlyRate\":100}],\"reservationNumber\":\"CLIENT-OVERRIDE\"}";

        mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationNumber").value("R20260911-000001"));
    }

    private static String body(String counts) {
        return "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\","
                + counts
                + "\"source\":\"DIRECT\",\"currency\":\"VND\",\"rooms\":[{\"roomId\":\"22222222-2222-2222-2222-222222222222\",\"nightlyRate\":100000}]}";
    }

    /** Confirms zero adults, negative children, missing counts and decimal counts are rejected by the API. */
    @Test
    void rejectsInvalidGuestComposition() throws Exception {
        for (String counts : List.of(
                "\"adultCount\":0,\"childCount\":0,",
                "\"adultCount\":-1,\"childCount\":0,",
                "\"adultCount\":1,\"childCount\":-1,",
                "\"childCount\":0,",
                "\"adultCount\":1,",
                "\"adultCount\":null,\"childCount\":0,",
                "\"adultCount\":1.5,\"childCount\":0,",
                "\"adultCount\":1,\"childCount\":0.5,",
                "\"adultCount\":\"1.5\",\"childCount\":0,")) {
            mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body(counts)))
                    .andExpect(status().isBadRequest());
        }
    }

    /** Confirms the API accepts and forwards valid counts (children may be zero, party may exceed room capacity). */
    @Test
    void acceptsValidGuestComposition() throws Exception {
        when(reservationService.create(any()))
                .thenReturn(new Response(UUID.randomUUID(), "R20260911-000003", "DRAFT", BigDecimal.ONE, "VND"));
        for (String counts : List.of("\"adultCount\":1,\"childCount\":0,", "\"adultCount\":3,\"childCount\":2,")) {
            mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body(counts)))
                    .andExpect(status().isOk());
        }
    }
}
