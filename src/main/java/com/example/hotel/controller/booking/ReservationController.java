package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.dto.booking.response.ReservationDetailResponse;
import com.example.hotel.dto.booking.response.ReservationSummaryResponse;
import com.example.hotel.service.booking.ReservationService;
import com.example.hotel.service.booking.ReservationQueryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cung cấp các API REST cho vòng đời reservation. */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
    private final ReservationService service;
    private final ReservationQueryService reservationQueryService;

    /**
     * Tạo controller với dịch vụ reservation.
     *
     * @param reservationService dịch vụ xử lý reservation
     * @param reservationQueryService dịch vụ đọc reservation
     */
    ReservationController(
            ReservationService reservationService, ReservationQueryService reservationQueryService) {
        service = reservationService;
        this.reservationQueryService = reservationQueryService;
    }

    /**
     * Returns all reservations in the compact format used by list clients.
     *
     * @return the reservation list
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_VIEW_BOOKING')")
    List<ReservationSummaryResponse> findAll() {
        return reservationQueryService.findAll();
    }

    /**
     * Returns the detailed representation of one reservation.
     *
     * @param id the reservation identifier
     * @return the reservation detail
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_VIEW_BOOKING')")
    ReservationDetailResponse findById(@PathVariable UUID id) {
        return reservationQueryService.findById(id);
    }

    /**
     * Tạo reservation nháp mới.
     *
     * @param request dữ liệu reservation cần tạo
     * @return reservation vừa tạo
     */
    @PostMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response create(@Valid @RequestBody CreateRequest request) {
        return service.create(request);
    }

    /**
     * Xác nhận reservation nháp.
     *
     * @param id định danh reservation
     * @return reservation sau khi xác nhận
     */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response confirm(@PathVariable UUID id) {
        return service.confirm(id);
    }

    /**
     * Hủy reservation đã xác nhận.
     *
     * @param id định danh reservation
     * @return reservation sau khi hủy
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    /**
     * Đánh dấu reservation đã xác nhận là no-show.
     *
     * @param id định danh reservation
     * @return reservation sau khi cập nhật
     */
    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response noShow(@PathVariable UUID id) {
        return service.noShow(id);
    }

    /**
     * Thực hiện check-in cho reservation.
     *
     * @param id định danh reservation
     * @return reservation sau khi check-in
     */
    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasAuthority('PERM_CHECK_IN')")
    Response checkIn(@PathVariable UUID id) {
        return service.checkIn(id);
    }

    /**
     * Performs check-out for an eligible checked-in reservation.
     *
     * @param id reservation identifier
     * @return reservation after check-out
     */
    @PostMapping("/{id}/check-out")
    @PreAuthorize("hasAuthority('PERM_CHECK_OUT')")
    Response checkOut(@PathVariable UUID id) {
        return service.checkOut(id);
    }
}
