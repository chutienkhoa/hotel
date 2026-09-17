package com.example.hotel.service.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.customer.request.GuestCreateRequest;
import com.example.hotel.dto.customer.request.GuestUpdateRequest;
import com.example.hotel.dto.customer.response.GuestResponse;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.customer.GuestRepository;
import com.example.hotel.security.CurrentUser;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Verifies guest-code ownership and authenticated-user audit behavior in guest operations. */
class GuestServiceTest {

    /** Clears the authentication established by an individual guest-service test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Confirms guest creation uses the sequence-backed code and current authenticated user.
     */
    @Test
    void shouldCreateGuestWithGeneratedCodeAndAuditUser() {
        GuestRepository guestRepository = mock(GuestRepository.class);
        GuestService guestService = new GuestService(guestRepository, new GuestMapper(), mock(GuestDocumentService.class));
        UUID creatorId = UUID.randomUUID();
        setCurrentUser(creatorId);
        when(guestRepository.nextGuestCodeSequence()).thenReturn(1L);
        when(guestRepository.existsByGuestCode("G000001")).thenReturn(false);
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GuestResponse created = guestService.create(createRequest());
        ArgumentCaptor<Guest> savedGuest = ArgumentCaptor.forClass(Guest.class);
        verify(guestRepository).save(savedGuest.capture());

        assertEquals("G000001", created.guestCode());
        assertEquals(creatorId, savedGuest.getValue().getCreatedBy());
    }

    /** Confirms a generated guest code remains unique when a legacy code already uses a sequence value. */
    @Test
    void shouldSkipAnExistingGuestCodeWhenGeneratingGuestCode() {
        GuestRepository guestRepository = mock(GuestRepository.class);
        GuestService guestService = new GuestService(guestRepository, new GuestMapper(), mock(GuestDocumentService.class));
        setCurrentUser(UUID.randomUUID());
        when(guestRepository.nextGuestCodeSequence()).thenReturn(1L, 2L);
        when(guestRepository.existsByGuestCode("G000001")).thenReturn(true);
        when(guestRepository.existsByGuestCode("G000002")).thenReturn(false);
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GuestResponse created = guestService.create(createRequest());

        assertEquals("G000002", created.guestCode());
    }

    /**
     * Confirms profile updates preserve the initial creator, refresh the updater, and keep the code immutable.
     */
    @Test
    void shouldPreserveCreatorAndGuestCodeWhenUpdatingGuest() {
        GuestRepository guestRepository = mock(GuestRepository.class);
        GuestService guestService = new GuestService(guestRepository, new GuestMapper(), mock(GuestDocumentService.class));
        UUID guestId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID updaterId = UUID.randomUUID();
        Guest guest = Guest.create(
                guestId,
                "G000123",
                "Original",
                "Guest",
                null,
                null,
                null,
                null,
                null);
        guest.audit(creatorId);
        when(guestRepository.findById(guestId)).thenReturn(Optional.of(guest));
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        setCurrentUser(updaterId);

        GuestResponse updated = guestService.update(guestId, updateRequest());

        assertEquals("G000123", updated.guestCode());
        assertEquals("Updated", updated.firstName());
        assertEquals(creatorId, guest.getCreatedBy());
        assertEquals(updaterId, guest.getUpdatedBy());
    }

    /** Confirms client request contracts cannot carry the immutable guest code. */
    @Test
    void shouldNotExposeGuestCodeInWriteRequestContracts() {
        assertFalse(Arrays.stream(GuestCreateRequest.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("guestCode")));
        assertFalse(Arrays.stream(GuestUpdateRequest.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("guestCode")));
    }

    /**
     * Establishes the application principal used by a guest service operation.
     *
     * @param userId authenticated application user identifier
     */
    private void setCurrentUser(UUID userId) {
        CurrentUser user = new CurrentUser(userId, "guest-manager");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }

    /**
     * Creates valid guest profile data for a creation test.
     *
     * @return the guest creation request
     */
    private GuestCreateRequest createRequest() {
        return new GuestCreateRequest(
                "First",
                "Last",
                "guest@example.com",
                "0123456789",
                "Japan",
                LocalDate.of(1990, 1, 1),
                "Tokyo");
    }

    /**
     * Creates updated guest profile data for an update test.
     *
     * @return the guest update request
     */
    private GuestUpdateRequest updateRequest() {
        return new GuestUpdateRequest(
                "Updated",
                "Guest",
                "updated@example.com",
                "0987654321",
                "Japan",
                LocalDate.of(1991, 2, 2),
                "Osaka");
    }
}
