package com.example.hotel.service.customer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.customer.GuestDocument;
import com.example.hotel.entity.customer.GuestDocumentType;
import com.example.hotel.exception.GuestDocumentValidationException;
import com.example.hotel.repository.customer.GuestDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Verifies private Guest passport upload validation and one-document replacement behavior. */
class GuestDocumentServiceTest {

    @TempDir
    Path storageDirectory;

    /** Confirms a valid JPEG creates metadata with a server-generated key rather than the upload filename. */
    @Test
    void shouldStoreValidJpegWithServerGeneratedStorageKey() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        when(repository.findByGuestIdAndDocumentType(guest.getId(), GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(Optional.empty());
        when(repository.save(any(GuestDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.storeOrReplacePassport(
                guest,
                file("passport.JPG", "image/jpeg", "jpeg-data"),
                UUID.randomUUID());

        ArgumentCaptor<GuestDocument> documentCaptor = ArgumentCaptor.forClass(GuestDocument.class);
        verify(repository).save(documentCaptor.capture());
        GuestDocument document = documentCaptor.getValue();
        assertEquals("passport.JPG", document.getOriginalName());
        assertEquals("image/jpeg", document.getContentType());
        assertNotEquals("passport.JPG", document.getStorageKey());
        assertDoesNotThrow(() -> storageService.load(document.getStorageKey()));
    }

    /** Confirms PNG uploads are accepted and invalid MIME, extension, and size values are rejected before storage. */
    @Test
    void shouldValidateAllowedPassportImageTypesAndSize() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        when(repository.findByGuestIdAndDocumentType(guest.getId(), GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(Optional.empty());
        when(repository.save(any(GuestDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> service.storeOrReplacePassport(
                guest, file("passport.png", "image/png", "png-data"), UUID.randomUUID()));
        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.storeOrReplacePassport(guest, file("passport.pdf", "application/pdf", "pdf"), UUID.randomUUID()));
        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.storeOrReplacePassport(guest, file("passport.jpg", "image/png", "jpeg"), UUID.randomUUID()));
        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.storeOrReplacePassport(
                        guest,
                        new MockMultipartFile(
                                "passportImage",
                                "large.jpg",
                                "image/jpeg",
                                new byte[5 * 1024 * 1024 + 1]),
                        UUID.randomUUID()));
    }

    /** Confirms a passport image at the exact five-mebibyte business boundary is accepted. */
    @Test
    void shouldAcceptPassportImageAtFiveMegabyteBoundary() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        when(repository.findByGuestIdAndDocumentType(guest.getId(), GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(Optional.empty());
        when(repository.save(any(GuestDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> service.storeOrReplacePassport(
                guest,
                new MockMultipartFile(
                        "passportImage", "boundary.jpg", "image/jpeg", new byte[5 * 1024 * 1024]),
                UUID.randomUUID()));
    }

    /** Confirms an oversized upload is rejected before it can create metadata or a physical file. */
    @Test
    void shouldRejectOversizedPassportBeforeStorage() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);

        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.storeOrReplacePassport(
                        guest(),
                        new MockMultipartFile(
                                "passportImage",
                                "too-large.jpg",
                                "image/jpeg",
                                new byte[5 * 1024 * 1024 + 1]),
                        UUID.randomUUID()));

        verifyNoInteractions(repository);
        assertEquals(0, storageDirectory.toFile().listFiles().length);
    }

    /** Confirms an oversized replacement leaves the existing passport metadata and file intact. */
    @Test
    void shouldKeepExistingPassportWhenReplacementExceedsSizeLimit() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        String oldStorageKey = storageService.store(file("old.jpg", "image/jpeg", "old"), "jpg");
        GuestDocument existing = GuestDocument.passportImage(
                UUID.randomUUID(), guest, "old.jpg", "image/jpeg", 3, oldStorageKey);
        existing.audit(UUID.randomUUID());
        when(repository.findByGuestIdAndDocumentType(guest.getId(), GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(Optional.of(existing));

        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.storeOrReplacePassport(
                        guest,
                        new MockMultipartFile(
                                "passportImage",
                                "too-large.png",
                                "image/png",
                                new byte[5 * 1024 * 1024 + 1]),
                        UUID.randomUUID()));

        assertEquals("old.jpg", existing.getOriginalName());
        assertDoesNotThrow(() -> storageService.load(oldStorageKey));
        verifyNoInteractions(repository);
    }

    /** Confirms a successful replacement keeps one metadata record and removes only the obsolete physical file. */
    @Test
    void shouldReplacePassportOnlyAfterNewImageIsStored() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        String oldStorageKey = storageService.store(file("old.jpg", "image/jpeg", "old"), "jpg");
        GuestDocument existing = GuestDocument.passportImage(
                UUID.randomUUID(), guest, "old.jpg", "image/jpeg", 3, oldStorageKey);
        existing.audit(UUID.randomUUID());
        when(repository.findByGuestIdAndDocumentType(guest.getId(), GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        service.storeOrReplacePassport(guest, file("new.png", "image/png", "new"), UUID.randomUUID());

        assertEquals("new.png", existing.getOriginalName());
        assertEquals("image/png", existing.getContentType());
        assertNotEquals(oldStorageKey, existing.getStorageKey());
        assertDoesNotThrow(() -> storageService.load(existing.getStorageKey()));
        assertThrows(ResponseStatusException.class, () -> storageService.load(oldStorageKey));
        verify(repository).save(existing);
    }

    /** Creates a Guest instance suitable for document metadata ownership tests. */
    private Guest guest() {
        return Guest.create(
                UUID.randomUUID(), "G000001", "First", "Last", null, null, "Japan", null, null);
    }

    /** Creates one small in-memory multipart file. */
    private MockMultipartFile file(String filename, String contentType, String content) {
        return new MockMultipartFile(
                "passportImage", filename, contentType, content.getBytes(StandardCharsets.UTF_8));
    }
}
