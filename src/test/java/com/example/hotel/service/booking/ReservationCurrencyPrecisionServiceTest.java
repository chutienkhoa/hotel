package com.example.hotel.service.booking;

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

/**
 * Verifies the approved V1 Reservation money rules at the authoritative service boundary: a Reservation
 * is denominated in VND only (USD remains a Payment tender currency, never a Reservation currency), and a
 * staff-entered nightly rate carrying more precision than the currency has is rejected rather than silently
 * rounded.
 *
 * <p>Nothing may be persisted when either rule fails, so each rejection also asserts that no
 * Reservation was saved.</p>
 */
class ReservationCurrencyPrecisionServiceTest {

    private static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 10);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 10, 12);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final GuestRepository guests = mock(GuestRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final ReservationNumberGenerator numbers = mock(ReservationNumberGenerator.class);
    private final ReservationService service = new ReservationService(
            reservations,
            guests,
            rooms,
            mock(StayRepository.class),
            mock(StayRoomAssignmentRepository.class),
            mock(ChargeRepository.class),
            mock(AuditLogRepository.class),
            new ReservationMapper(),
            numbers,
            mock(StayBalanceService.class),
            mock(RoomAvailabilityService.class),
            mock(PrepaymentService.class),
            Clock.fixed(CHECK_IN.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    private final Guest guest =
            Guest.create(UUID.randomUUID(), "G-1", "Ann", "Lee", null, null, "Vietnam", null, null);
    private final Room room = room("101");

    private final CheckInService checkIn = new CheckInService(
            reservations,
            mock(ReservationQueryService.class),
            service,
            rooms,
            mock(RoomAvailabilityService.class),
            mock(StayRepository.class),
            guests,
            mock(GuestMapper.class),
            mock(GuestDocumentService.class),
            Clock.fixed(CHECK_IN.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));

    /** Authenticates and stubs the lookups every creation needs. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(UUID.randomUUID(), "manager"), null, List.of()));
        when(guests.findById(guest.getId())).thenReturn(Optional.of(guest));
        when(rooms.findAllById(any())).thenReturn(List.of(room));
        when(numbers.generate()).thenReturn("R20261010-000001");
        when(reservations.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms the V1 Reservation currency, VND, is accepted and recorded on the Reservation. */
    @Test
    void shouldAcceptVndReservationCurrency() {
        Response response = service.create(request("VND", new BigDecimal("1000000")));

        assertEquals("VND", response.currency());
    }

    /**
     * Confirms a USD Reservation is rejected at the service boundary whatever the rate, so a crafted REST or form
     * request cannot bypass the UI dropdown or the DTO validation. USD stays valid only as a Payment currency.
     */
    @ParameterizedTest
    @ValueSource(strings = {"20", "20.50", "20.501", "1000000"})
    void shouldRejectUsdReservationCurrency(String nightlyRate) {
        assertRejected(request("USD", new BigDecimal(nightlyRate)), "Reservation currency must be VND");
    }

    /** Confirms editing a DRAFT cannot switch it to (or keep it in) USD either. */
    @Test
    void shouldRejectUsdWhenEditingDraft() {
        Reservation draft = new Reservation(
                UUID.randomUUID(), "R20261010-000001", guest, CHECK_IN, CHECK_OUT, "USD", null);
        when(reservations.findById(draft.getId())).thenReturn(Optional.of(draft));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.updateDraft(draft.getId(), request("USD", new BigDecimal("20"))));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Reservation currency must be VND", exception.getReason());
        assertEquals("USD", draft.getCurrency());
    }

    /** Confirms Walk-in cannot create a USD Reservation; nothing is persisted. */
    @Test
    void walkInShouldRejectUsdReservationCurrency() {
        WalkInRequest walkIn = new WalkInRequest(guest.getId(), CHECK_OUT, 2, 0, "USD", null,
                List.of(new RoomRequest(room.getId(), new BigDecimal("20"))));

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> checkIn.confirmWalkIn(walkIn));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Reservation currency must be VND", exception.getReason());
        verify(reservations, never()).save(any(Reservation.class));
    }

    /** Confirms OTA Booking Not Entered cannot create a USD Reservation; nothing is persisted. */
    @Test
    void otaEntryShouldRejectUsdReservationCurrency() {
        CreateRequest otaEntry = new CreateRequest(
                guest.getId(), CHECK_IN, CHECK_OUT, 2, 0, BookingSource.AGODA, "AG-1", "USD", null,
                List.of(new RoomRequest(room.getId(), new BigDecimal("20"))), List.of());

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> checkIn.createOtaEntry(otaEntry));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Reservation currency must be VND", exception.getReason());
        verify(reservations, never()).save(any(Reservation.class));
    }

    /**
     * Confirms every other ISO 4217 code is rejected at the service boundary, not only by the UI
     * dropdown. A Reservation in an unsupported currency could previously be created through the REST
     * API and then never settled, because no Payment can be recorded against it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"EUR", "JPY", "GBP"})
    void shouldRejectUnsupportedIsoCurrency(String currency) {
        assertRejected(request(currency, new BigDecimal("1000000")), "Unsupported currency");
    }

    /** Confirms a malformed or wrongly cased currency code is rejected. */
    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "vnd", "VN", "VNDD", ""})
    void shouldRejectInvalidCurrencyCode(String currency) {
        assertRejected(request(currency, new BigDecimal("1000000")), "Unsupported currency");
    }

    /** Confirms a whole-dong nightly rate is accepted and priced across the booked nights. */
    @Test
    void shouldAcceptWholeDongNightlyRate() {
        Response response = service.create(request("VND", new BigDecimal("1000000")));

        // Two booked nights at 1,000,000 VND.
        assertEquals(0, new BigDecimal("2000000").compareTo(response.totalAmount()));
    }

    /** Confirms a fractional-dong nightly rate is rejected rather than silently rounded. */
    @Test
    void shouldRejectFractionalDongNightlyRate() {
        assertRejected(request("VND", new BigDecimal("1000.5")), "Rate exceeds currency precision");
    }

    /**
     * Confirms the Reservation total stays the exact sum of its room lines once each rate has been
     * normalized, so no rounding is introduced between a room line and the Reservation total.
     */
    @Test
    void shouldKeepReservationTotalEqualToTheSumOfItsRoomLines() {
        Room second = room("102");
        when(rooms.findAllById(any())).thenReturn(List.of(room, second));
        CreateRequest request = new CreateRequest(
                guest.getId(), CHECK_IN, CHECK_OUT, 2, 0, BookingSource.DIRECT, null, "VND", null,
                List.of(new RoomRequest(room.getId(), new BigDecimal("500000")),
                        new RoomRequest(second.getId(), new BigDecimal("750000"))),
                List.of());

        Response response = service.create(request);

        // Two nights of 500,000 plus two nights of 750,000.
        assertEquals(0, new BigDecimal("2500000").compareTo(response.totalAmount()));
    }

    /**
     * Asserts a creation request is rejected with 400 and nothing is persisted.
     *
     * @param request invalid creation request
     * @param expectedReason expected rejection reason
     */
    private void assertRejected(CreateRequest request, String expectedReason) {
        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service.create(request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals(expectedReason, exception.getReason());
        verify(reservations, never()).save(any(Reservation.class));
    }

    /**
     * Creates a Room whose type has enough adult capacity for these tests.
     *
     * @param number room number
     * @return the Room fixture
     */
    private static Room room(String number) {
        RoomType type = mock(RoomType.class);
        when(type.getCapacity()).thenReturn(4);
        return Room.create(UUID.randomUUID(), number, type, "1");
    }

    /**
     * Creates a single-room creation request.
     *
     * @param currency Reservation currency code
     * @param nightlyRate staff-entered nightly rate
     * @return the creation request
     */
    private CreateRequest request(String currency, BigDecimal nightlyRate) {
        return new CreateRequest(
                guest.getId(), CHECK_IN, CHECK_OUT, 2, 0, BookingSource.DIRECT, null, currency, null,
                List.of(new RoomRequest(room.getId(), nightlyRate)), List.of());
    }
}
