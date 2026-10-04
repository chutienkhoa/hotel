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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

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

    /** Confirms a Guest created without identity fields stores both as absent (backward compatible). */
    @Test
    void shouldCreateGuestWithoutIdentityFields() {
        GuestRepository guestRepository = mock(GuestRepository.class);
        GuestService guestService = new GuestService(guestRepository, new GuestMapper(), mock(GuestDocumentService.class));
        setCurrentUser(UUID.randomUUID());
        when(guestRepository.nextGuestCodeSequence()).thenReturn(1L);
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GuestResponse created = guestService.create(new GuestCreateRequest("A", "B", null, null, "Japan", null, null));

        assertEquals(null, created.idDocumentNumber());
        assertEquals(null, created.dateOfBirth());
    }

    /** Confirms the ID / Passport Number is trimmed and a blank value is stored as absent. */
    @Test
    void shouldTrimIdDocumentNumberAndStoreBlankAsAbsent() {
        GuestRepository guestRepository = mock(GuestRepository.class);
        GuestService guestService = new GuestService(guestRepository, new GuestMapper(), mock(GuestDocumentService.class));
        setCurrentUser(UUID.randomUUID());
        when(guestRepository.nextGuestCodeSequence()).thenReturn(1L);
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GuestResponse trimmed = guestService.create(new GuestCreateRequest(
                "A", "B", null, null, "Japan", LocalDate.of(1990, 8, 15), "  C1234567  ", null));
        GuestResponse blank = guestService.create(new GuestCreateRequest(
                "A", "B", null, null, "Japan", null, "   ", null));

        assertEquals("C1234567", trimmed.idDocumentNumber());
        assertEquals(LocalDate.of(1990, 8, 15), trimmed.dateOfBirth());
        assertEquals(null, blank.idDocumentNumber());
    }

    /** Confirms Edit Guest persists a changed identity number on the Guest itself. */
    @Test
    void shouldUpdateIdDocumentNumberOnTheGuest() {
        GuestRepository guestRepository = mock(GuestRepository.class);
        GuestService guestService = new GuestService(guestRepository, new GuestMapper(), mock(GuestDocumentService.class));
        UUID guestId = UUID.randomUUID();
        Guest guest = Guest.create(guestId, "G000123", "Original", "Guest", null, null, null, null, "OLD-1", null);
        when(guestRepository.findById(guestId)).thenReturn(Optional.of(guest));
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        setCurrentUser(UUID.randomUUID());

        GuestResponse updated = guestService.update(guestId, new GuestUpdateRequest(
                "Updated", "Guest", null, null, "Japan", null, " NEW-2 ", null));

        assertEquals("NEW-2", updated.idDocumentNumber());
        assertEquals("NEW-2", guest.getIdDocumentNumber());
    }

    /** Confirms the legacy profile update (no identity argument) leaves a stored ID number untouched. */
    @Test
    void shouldKeepIdDocumentNumberWhenLegacyProfileUpdateIsUsed() {
        Guest guest = Guest.create(UUID.randomUUID(), "G1", "A", "B", null, null, null, null, "KEEP-1", null);

        guest.updateProfile("C", "D", null, null, null, null, null);

        assertEquals("KEEP-1", guest.getIdDocumentNumber());
    }

    /** Confirms a future date of birth and an overlong ID number violate the request constraints, and a past date does not. */
    @Test
    void shouldValidateDateOfBirthAndIdDocumentNumberLength() {
        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        GuestCreateRequest ok = new GuestCreateRequest(
                "A", "B", null, null, "Vietnam", LocalDate.now().minusYears(30), "X".repeat(50), null);
        GuestCreateRequest future = new GuestCreateRequest(
                "A", "B", null, null, "Vietnam", LocalDate.now().plusDays(1), null, null);
        GuestCreateRequest tooLong = new GuestCreateRequest(
                "A", "B", null, null, "Vietnam", null, "X".repeat(51), null);

        assertFalse(validator.validateProperty(ok, "dateOfBirth").iterator().hasNext());
        assertFalse(validator.validateProperty(ok, "idDocumentNumber").iterator().hasNext());
        assertEquals(1, validator.validateProperty(future, "dateOfBirth").size());
        assertEquals(1, validator.validateProperty(tooLong, "idDocumentNumber").size());
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

    /** Confirms Create Guest passes every selected passport image through to document storage as one batch. */
    @Test
    void shouldPassMultiplePassportImagesToDocumentServiceOnCreate() {
        GuestRepository guestRepository = mock(GuestRepository.class);
        GuestDocumentService guestDocumentService = mock(GuestDocumentService.class);
        GuestService guestService = new GuestService(guestRepository, new GuestMapper(), guestDocumentService);
        UUID creatorId = UUID.randomUUID();
        setCurrentUser(creatorId);
        when(guestRepository.nextGuestCodeSequence()).thenReturn(1L);
        when(guestRepository.existsByGuestCode("G000001")).thenReturn(false);
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        List<MultipartFile> passportImages = List.of(
                new MockMultipartFile("passportImages", "p1.jpg", "image/jpeg", "one".getBytes()),
                new MockMultipartFile("passportImages", "p2.jpg", "image/jpeg", "two".getBytes()));

        guestService.create(createRequest(), passportImages);

        ArgumentCaptor<Guest> guestCaptor = ArgumentCaptor.forClass(Guest.class);
        verify(guestDocumentService).addPassportImages(guestCaptor.capture(), org.mockito.ArgumentMatchers.eq(passportImages), org.mockito.ArgumentMatchers.eq(creatorId));
        assertEquals("G000001", guestCaptor.getValue().getGuestCode());
    }

    /** Confirms Edit Guest appends newly selected passport images without touching existing ones. */
    @Test
    void shouldAppendPassportImagesOnUpdateWithoutReplacingExistingOnes() {
        GuestRepository guestRepository = mock(GuestRepository.class);
        GuestDocumentService guestDocumentService = mock(GuestDocumentService.class);
        GuestService guestService = new GuestService(guestRepository, new GuestMapper(), guestDocumentService);
        UUID guestId = UUID.randomUUID();
        UUID updaterId = UUID.randomUUID();
        Guest guest = Guest.create(guestId, "G000123", "Original", "Guest", null, null, null, null, null);
        when(guestRepository.findById(guestId)).thenReturn(Optional.of(guest));
        when(guestRepository.save(any(Guest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        setCurrentUser(updaterId);
        List<MultipartFile> passportImages = List.of(
                new MockMultipartFile("passportImages", "new.jpg", "image/jpeg", "new".getBytes()));

        guestService.update(guestId, updateRequest(), passportImages);

        verify(guestDocumentService).addPassportImages(guest, passportImages, updaterId);
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
