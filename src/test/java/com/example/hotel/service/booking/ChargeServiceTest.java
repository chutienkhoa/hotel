package com.example.hotel.service.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.booking.request.ChargeCreateRequest;
import com.example.hotel.dto.booking.response.ChargeResponse;
import com.example.hotel.entity.booking.Charge;
import com.example.hotel.entity.booking.ChargeType;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import com.example.hotel.mapper.booking.ChargeMapper;
import com.example.hotel.repository.booking.ChargeRepository;
import com.example.hotel.repository.booking.StayRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Charge v1 validation, ownership, audit attribution, and stable Stay-scoped listing. */
class ChargeServiceTest {

    /** Clears authentication established by a test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms Charge creation owns identity, charge time, and audit data on the backend. */
    @Test
    void shouldCreateChargeForSpecifiedStayWithBackendControlledFields() {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Stay stay = stay(stayId);
        setCurrentUser(userId);
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChargeResponse response = chargeService(chargeRepository, stayRepository)
                .create(stayId, request(ChargeType.ROOM, null, null, new BigDecimal("100.00")));

        ArgumentCaptor<Charge> captor = ArgumentCaptor.forClass(Charge.class);
        verify(chargeRepository).save(captor.capture());
        Charge saved = captor.getValue();
        assertEquals(stay, saved.getStay());
        assertNotNull(saved.getId());
        assertNotNull(saved.getChargedAt());
        assertEquals(userId, saved.getCreatedBy());
        assertEquals(userId, saved.getUpdatedBy());
        assertEquals(saved.getId(), response.id());
        assertEquals(stayId, response.stayId());
    }

    /** Confirms all approved Charge v1 types can be recorded. */
    @ParameterizedTest
    @MethodSource("supportedTypes")
    void shouldAcceptSupportedChargeType(ChargeType type) {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChargeResponse response = chargeService(chargeRepository, stayRepository)
                .create(stayId, request(type, null, null, BigDecimal.ONE));

        assertEquals(type.name(), response.type());
    }

    /** Confirms reserved TAX and DISCOUNT classifications are rejected in Charge v1. */
    @ParameterizedTest
    @MethodSource("reservedTypes")
    void shouldRejectReservedChargeType(ChargeType type) {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> chargeService(chargeRepository, stayRepository)
                        .create(UUID.randomUUID(), request(type, null, null, BigDecimal.ONE)));

        assertEquals(400, exception.getStatusCode().value());
    }

    /** Confirms omitted quantity and unit price are accepted descriptive-field values. */
    @Test
    void shouldAllowQuantityAndUnitPriceToBothBeAbsent() {
        assertCreateSucceeds(request(ChargeType.SERVICE, null, null, BigDecimal.ONE));
    }

    /** Confirms quantity and unit price may both be supplied without recalculating amount. */
    @Test
    void shouldAllowQuantityAndUnitPriceToBothBePresentWithoutRecalculatingAmount() {
        BigDecimal amount = new BigDecimal("19.99");
        ChargeResponse response = assertCreateSucceeds(
                request(ChargeType.BREAKFAST, new BigDecimal("2"), new BigDecimal("10"), amount));

        assertEquals(0, amount.compareTo(response.amount()));
    }

    /** Confirms incomplete quantity and unit-price pairs are rejected. */
    @ParameterizedTest
    @MethodSource("incompleteQuantityAndUnitPricePairs")
    void shouldRejectIncompleteQuantityAndUnitPricePair(BigDecimal quantity, BigDecimal unitPrice) {
        assertBadRequest(request(ChargeType.ROOM, quantity, unitPrice, BigDecimal.ONE));
    }

    /** Confirms non-positive quantity, negative unit price, and non-positive amount are rejected. */
    @ParameterizedTest
    @MethodSource("invalidMonetaryValues")
    void shouldRejectInvalidChargeValues(
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        assertBadRequest(request(ChargeType.ROOM, quantity, unitPrice, amount));
    }

    /** Confirms requests cannot carry server-controlled identity, timestamp, or audit fields. */
    @Test
    void shouldNotExposeServerControlledFieldsInChargeCreateRequest() {
        List<String> componentNames = Arrays.stream(ChargeCreateRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertFalse(componentNames.contains("id"));
        assertFalse(componentNames.contains("chargedAt"));
        assertFalse(componentNames.contains("createdAt"));
        assertFalse(componentNames.contains("createdBy"));
        assertFalse(componentNames.contains("updatedAt"));
        assertFalse(componentNames.contains("updatedBy"));
    }

    /** Confirms creation rejects a missing owning Stay. */
    @Test
    void shouldRejectCreationForMissingStay() {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findById(stayId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> chargeService(chargeRepository, stayRepository)
                        .create(stayId, request(ChargeType.ROOM, null, null, BigDecimal.ONE)));

        assertEquals(404, exception.getStatusCode().value());
    }

    /** Confirms Charge creation cannot mutate a checked-out Folio. */
    @Test
    void shouldRejectChargeCreationForCheckedOutStay() {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId);
        when(stay.getStatus()).thenReturn(StayStatus.CHECKED_OUT);
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> chargeService(chargeRepository, stayRepository)
                        .create(stayId, request(ChargeType.ROOM, null, null, BigDecimal.ONE)));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms a Stay-scoped list contains only the repository's stable ordered results for that Stay. */
    @Test
    void shouldListChargesForRequestedStayOnly() {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId);
        Charge first = Charge.create(stay, ChargeType.ROOM, null, null, null, BigDecimal.ONE);
        Charge second = Charge.create(stay, ChargeType.BREAKFAST, null, null, null, BigDecimal.TEN);
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.findByStayIdOrderByChargedAtAscIdAsc(stayId))
                .thenReturn(List.of(first, second));

        List<ChargeResponse> charges = chargeService(chargeRepository, stayRepository).findByStayId(stayId);

        assertEquals(List.of(first.getId(), second.getId()), charges.stream().map(ChargeResponse::id).toList());
        assertEquals(List.of(stayId, stayId), charges.stream().map(ChargeResponse::stayId).toList());
        verify(chargeRepository).findByStayIdOrderByChargedAtAscIdAsc(stayId);
    }

    /** Confirms listing rejects a missing Stay instead of returning an unscoped list. */
    @Test
    void shouldRejectListingForMissingStay() {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        when(stayRepository.findById(stayId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> chargeService(chargeRepository, stayRepository).findByStayId(stayId));

        assertEquals(404, exception.getStatusCode().value());
    }

    /**
     * Supplies the supported Charge v1 types.
     *
     * @return approved Charge classifications
     */
    private static Stream<ChargeType> supportedTypes() {
        return Stream.of(
                ChargeType.ROOM,
                ChargeType.BREAKFAST,
                ChargeType.EXTRA_BED,
                ChargeType.LAUNDRY,
                ChargeType.MINIBAR,
                ChargeType.SERVICE,
                ChargeType.OTHER);
    }

    /**
     * Supplies reserved classifications that Charge v1 must reject.
     *
     * @return reserved Charge classifications
     */
    private static Stream<ChargeType> reservedTypes() {
        return Stream.of(ChargeType.TAX, ChargeType.DISCOUNT);
    }

    /**
     * Supplies invalid descriptive-field pairs.
     *
     * @return incomplete quantity and unit-price pairs
     */
    private static Stream<Arguments> incompleteQuantityAndUnitPricePairs() {
        return Stream.of(
                Arguments.of(BigDecimal.ONE, null),
                Arguments.of(null, BigDecimal.ZERO));
    }

    /**
     * Supplies invalid positive and non-negative monetary values.
     *
     * @return invalid quantity, unit price, and amount combinations
     */
    private static Stream<Arguments> invalidMonetaryValues() {
        return Stream.of(
                Arguments.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE),
                Arguments.of(BigDecimal.ONE, new BigDecimal("-0.01"), BigDecimal.ONE),
                Arguments.of(null, null, BigDecimal.ZERO));
    }

    /**
     * Executes a valid creation request with an existing Stay and authenticated user.
     *
     * @param request Charge request to persist
     * @return created Charge response
     */
    private ChargeResponse assertCreateSucceeds(ChargeCreateRequest request) {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        UUID stayId = UUID.randomUUID();
        Stay stay = stay(stayId);
        setCurrentUser(UUID.randomUUID());
        when(stayRepository.findById(stayId)).thenReturn(Optional.of(stay));
        when(chargeRepository.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        return chargeService(chargeRepository, stayRepository).create(stayId, request);
    }

    /**
     * Confirms a request fails service-level Charge v1 validation.
     *
     * @param request invalid Charge request
     */
    private void assertBadRequest(ChargeCreateRequest request) {
        ChargeRepository chargeRepository = mock(ChargeRepository.class);
        StayRepository stayRepository = mock(StayRepository.class);
        setCurrentUser(UUID.randomUUID());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> chargeService(chargeRepository, stayRepository).create(UUID.randomUUID(), request));

        assertEquals(400, exception.getStatusCode().value());
    }

    /**
     * Creates a Charge request fixture.
     *
     * @param type charge type
     * @param quantity optional quantity
     * @param unitPrice optional unit price
     * @param amount authoritative amount
     * @return Charge creation request
     */
    private ChargeCreateRequest request(
            ChargeType type, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
        return new ChargeCreateRequest(type, null, quantity, unitPrice, amount);
    }

    /**
     * Creates a mocked Stay with the requested identifier.
     *
     * @param stayId Stay identifier
     * @return Stay fixture
     */
    private Stay stay(UUID stayId) {
        Stay stay = mock(Stay.class);
        when(stay.getId()).thenReturn(stayId);
        when(stay.getStatus()).thenReturn(StayStatus.CHECKED_IN);
        return stay;
    }

    /**
     * Creates a Charge service with mocked repositories and the production mapper.
     *
     * @param chargeRepository Charge repository
     * @param stayRepository Stay repository
     * @return configured Charge service
     */
    private ChargeService chargeService(ChargeRepository chargeRepository, StayRepository stayRepository) {
        return new ChargeService(chargeRepository, stayRepository, new ChargeMapper());
    }

    /**
     * Establishes the authenticated user used for server-controlled audit values.
     *
     * @param userId authenticated application-user identifier
     */
    private void setCurrentUser(UUID userId) {
        CurrentUser user = new CurrentUser(userId, "payment-manager");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }
}
