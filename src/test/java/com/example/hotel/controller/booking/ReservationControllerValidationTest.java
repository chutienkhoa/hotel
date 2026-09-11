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
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"currency\":\"JPY\",\"rooms\":[]}";
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
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"currency\":\"JPY\",\"rooms\":[{\"roomId\":\"22222222-2222-2222-2222-222222222222\",\"nightlyRate\":0}]}";
        assertDoesNotThrow(
                () ->
                        mvc.perform(
                                        post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isBadRequest()));
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
                                "JPY"));
        String body =
                "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"currency\":\"JPY\",\"rooms\":[{\"roomId\":\"22222222-2222-2222-2222-222222222222\",\"nightlyRate\":100}],\"reservationNumber\":\"CLIENT-OVERRIDE\"}";

        mvc.perform(post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationNumber").value("R20260911-000001"));
    }
}
