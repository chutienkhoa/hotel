package com.example.hotel.controller.booking;

import com.example.hotel.dto.booking.request.BookingContactUpdateRequest;
import com.example.hotel.dto.booking.request.CancelReservationRequest;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.GuestCompositionUpdateRequest;
import com.example.hotel.dto.booking.request.NoShowReservationRequest;
import com.example.hotel.dto.booking.request.NotesUpdateRequest;
import com.example.hotel.dto.booking.request.OtaReferenceCorrectionRequest;
import com.example.hotel.dto.booking.request.ReservationDateChangeRequest;
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
     * Updates only the guest composition (adults, children, Accompanying Guests) of a CONFIRMED reservation.
     *
     * @param id reservation identifier
     * @param request new adults, children and complete Accompanying Guest set
     * @return the reservation after the update
     */
    @PostMapping("/{id}/guest-composition")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response updateGuestComposition(
            @PathVariable UUID id, @Valid @RequestBody GuestCompositionUpdateRequest request) {
        return service.updateConfirmedGuestComposition(id, request);
    }

    /**
     * Changes only the dates and derived totals of an eligible CONFIRMED reservation.
     *
     * @param id reservation identifier
     * @param request replacement planned dates
     * @return the reservation after the date change
     */
    @PostMapping("/{id}/change-dates")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response changeDates(@PathVariable UUID id, @Valid @RequestBody ReservationDateChangeRequest request) {
        return service.changeConfirmedDates(id, request);
    }

    /**
     * Corrects only the OTA booking reference of an eligible CONFIRMED reservation.
     *
     * @param id reservation identifier
     * @param request corrected OTA booking reference
     * @return the reservation after the correction
     */
    @PostMapping("/{id}/correct-ota-reference")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response correctOtaReference(
            @PathVariable UUID id, @Valid @RequestBody OtaReferenceCorrectionRequest request) {
        return service.correctOtaBookingReference(id, request);
    }

    /**
     * Changes only the Booking Contact (name, phone, email) of an eligible reservation. Available through DRAFT,
     * CONFIRMED and CHECKED_IN; rejected once CHECKED_OUT, CANCELLED or NO_SHOW.
     *
     * @param id reservation identifier
     * @param request replacement Booking Contact values
     * @return the reservation after the update
     */
    @PostMapping("/{id}/booking-contact")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response updateBookingContact(@PathVariable UUID id, @Valid @RequestBody BookingContactUpdateRequest request) {
        return service.updateBookingContact(id, request);
    }

    /**
     * Changes only the internal operational Reservation Notes of an eligible reservation. Available through
     * DRAFT, CONFIRMED and CHECKED_IN; rejected once CHECKED_OUT, CANCELLED or NO_SHOW.
     *
     * @param id reservation identifier
     * @param request replacement notes
     * @return the reservation after the update
     */
    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response updateNotes(@PathVariable UUID id, @Valid @RequestBody NotesUpdateRequest request) {
        return service.updateReservationNotes(id, request);
    }

    /**
     * Hủy reservation đã xác nhận, yêu cầu một lý do hủy hợp lệ.
     *
     * @param id định danh reservation
     * @param request the required cancellation reason
     * @return reservation sau khi hủy
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response cancel(@PathVariable UUID id, @Valid @RequestBody CancelReservationRequest request) {
        return service.cancel(id, request);
    }

    /**
     * Đánh dấu reservation đã xác nhận là no-show, yêu cầu một lý do bắt buộc.
     *
     * @param id định danh reservation
     * @param request the required no-show reason
     * @return reservation sau khi cập nhật
     */
    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAuthority('PERM_MANAGE_BOOKING')")
    Response noShow(@PathVariable UUID id, @Valid @RequestBody NoShowReservationRequest request) {
        return service.noShow(id, request);
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
