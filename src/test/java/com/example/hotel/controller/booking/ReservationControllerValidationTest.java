package com.example.hotel.controller.booking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.exception.ApiExceptionHandler;
import com.example.hotel.service.booking.ReservationService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Kiểm tra Bean Validation tại API tạo reservation. */
class ReservationControllerValidationTest {
  private MockMvc mvc;

  @BeforeEach
  /** Khởi tạo MockMvc với controller và exception handler cho từng test. */
  void setup() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ReservationController(mock(ReservationService.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  /** Xác nhận request không có phòng bị từ chối. */
  void rejectsEmptyRoomList() {
    String body =
        "{\"guestId\":\"11111111-1111-1111-1111-111111111111\",\"checkInDate\":\"2027-01-10\",\"checkOutDate\":\"2027-01-12\",\"currency\":\"JPY\",\"rooms\":[]}";
    assertDoesNotThrow(
        () ->
            mvc.perform(
                    post("/api/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()));
  }

  @Test
  /** Xác nhận request có giá mỗi đêm bằng không bị từ chối. */
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
