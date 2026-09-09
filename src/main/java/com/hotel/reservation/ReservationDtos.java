package com.hotel.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Tập hợp các DTO phục vụ API reservation. */
public final class ReservationDtos {
  /** Ngăn tạo instance cho lớp chứa DTO. */
  private ReservationDtos() {}

  /** Dữ liệu một phòng và giá mỗi đêm trong request tạo reservation. */
  public record RoomRequest(
      @NotNull UUID roomId,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal nightlyRate) {}

  /** Dữ liệu request tạo reservation nháp. */
  public record CreateRequest(
      @NotNull UUID guestId,
      @NotNull LocalDate checkInDate,
      @NotNull LocalDate checkOutDate,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @Size(max = 5000) String notes,
      @NotEmpty List<@Valid RoomRequest> rooms) {}

  /** Dữ liệu phản hồi rút gọn của reservation. */
  public record Response(
      UUID id, UUID reservationNumber, String status, BigDecimal totalAmount, String currency) {}
}
