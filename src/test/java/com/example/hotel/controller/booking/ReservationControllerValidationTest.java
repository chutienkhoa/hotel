package com.example.hotel.controller.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.exception.ApiExceptionHandler;
import com.example.hotel.service.booking.ReservationQueryService;
import com.example.hotel.service.booking.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Kiểm tra Bean Validation tại API tạo reservation. */
class ReservationControllerValidationTest {
    private MockMvc mvc;

    /** Khởi tạo MockMvc với controller và exception handler cho từng test. */
    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(new ReservationController(
                                mock(ReservationService.class), mock(ReservationQueryService.class)))
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
}
