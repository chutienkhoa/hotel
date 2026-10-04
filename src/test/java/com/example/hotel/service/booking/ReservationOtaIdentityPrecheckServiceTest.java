package com.example.hotel.service.booking;
import com.example.hotel.exception.LocalizedResponseStatusException;
import static org.mockito.ArgumentMatchers.eq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.example.hotel.dto.booking.request.CreateRequest;
import com.example.hotel.dto.booking.request.RoomRequest;
import com.example.hotel.dto.booking.request.WalkInRequest;
import com.example.hotel.dto.booking.response.Response;
import com.example.hotel.entity.booking.BookingSource;
import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomType;
import com.example.hotel.mapper.booking.ReservationMapper;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.ReservationRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.repository.booking.StayRoomAssignmentRepository;
import com.example.hotel.repository.common.AuditLogRepository;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.service.customer.GuestDocumentService;
import com.example.hotel.service.room.RoomAvailabilityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the read-only OTA identity pre-check used by the OTA Reservation Summary applies the creation rule. */
class ReservationOtaIdentityPrecheckServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final ReservationService service = new ReservationService(
            reservations,
            mock(GuestRepository.class),
            mock(RoomRepository.class),
            mock(StayRepository.class),
            mock(StayRoomAssignmentRepository.class),
            mock(ChargeRepository.class),
            mock(AuditLogRepository.class),
            new ReservationMapper(),
            mock(ReservationNumberGenerator.class),
            mock(StayBalanceService.class),
            mock(RoomAvailabilityService.class),
            mock(PrepaymentService.class),
            Clock.fixed(LocalDate.of(2026, 10, 10).atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    /** Confirms a free (source, reference) pair passes and nothing is saved. */
    @Test
    void shouldAcceptAFreeOtaIdentityWithoutPersisting() {
        when(reservations.existsByOtaIdentity(eq(BookingSource.AGODA), eq("AG-1"), any())).thenReturn(false);

        service.requireOtaIdentityAvailableForNewReservation(BookingSource.AGODA, "AG-1");

        verify(reservations, never()).save(any());
    }

    /** Confirms a used pair is reported as the existing localized 409, and the same reference under another source is free. */
    @Test
    void shouldRejectADuplicateOtaIdentityOnlyForTheSameSource() {
        when(reservations.existsByOtaIdentity(eq(BookingSource.AGODA), eq("AG-1"), any())).thenReturn(true);
        when(reservations.existsByOtaIdentity(eq(BookingSource.BOOKING_COM), eq("AG-1"), any())).thenReturn(false);

        LocalizedResponseStatusException exception = assertThrows(LocalizedResponseStatusException.class,
                () -> service.requireOtaIdentityAvailableForNewReservation(BookingSource.AGODA, "AG-1"));
        assertEquals("reservation.ota.error.duplicateIdentity", exception.getMessageKey());
        service.requireOtaIdentityAvailableForNewReservation(BookingSource.BOOKING_COM, "AG-1");
    }

    /** Confirms a blank reference is rejected for an OTA source. */
    @Test
    void shouldRejectABlankOtaReference() {
        assertThrows(LocalizedResponseStatusException.class,
                () -> service.requireOtaIdentityAvailableForNewReservation(BookingSource.AIRBNB, " "));
    }
}
