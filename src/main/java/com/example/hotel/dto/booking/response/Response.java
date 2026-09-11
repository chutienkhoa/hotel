package com.example.hotel.dto.booking.response;

import java.math.BigDecimal;
import java.util.UUID;

/** Dữ liệu phản hồi rút gọn của reservation. */
public record Response(
        UUID id, String reservationNumber, String status, BigDecimal totalAmount, String currency) {}
