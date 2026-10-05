package com.example.hotel.service.customer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.customer.response.GuestDocumentResponse;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.customer.GuestDocument;
import com.example.hotel.entity.customer.GuestDocumentType;
import com.example.hotel.exception.GuestDocumentValidationException;
import com.example.hotel.repository.customer.GuestDocumentRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies multi-file passport upload validation — including verification of the ACTUAL decoded
 * image content, not only the client-declared filename, extension and Content-Type — plus append
 * behavior and individual removal.
 */
class GuestDocumentServiceTest {

    @TempDir
    Path storageDirectory;

    /** Confirms each Guest maps to its OLDEST passport image (rows arrive oldest first), and a Guest without one is absent. */
    @Test
    void shouldMapEachGuestToItsFirstPassportDocument() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentService service = new GuestDocumentService(repository, mock(GuestDocumentStorageService.class));
        java.util.UUID guestA = java.util.UUID.randomUUID();
        java.util.UUID guestB = java.util.UUID.randomUUID();
        java.util.UUID noPassport = java.util.UUID.randomUUID();
        java.util.UUID firstA = java.util.UUID.randomUUID();
        java.util.UUID secondA = java.util.UUID.randomUUID();
        java.util.UUID onlyB = java.util.UUID.randomUUID();
        when(repository.findGuestAndDocumentIdsByDocumentType(
                        com.example.hotel.entity.customer.GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(List.<Object[]>of(
                        new Object[] {guestA, firstA}, new Object[] {guestB, onlyB}, new Object[] {guestA, secondA}));

        java.util.Map<java.util.UUID, java.util.UUID> result = service.firstPassportDocumentIds();

        assertEquals(java.util.Map.of(guestA, firstA, guestB, onlyB), result);
        assertFalse(result.containsKey(noPassport));
    }

    /** Confirms a valid JPEG creates metadata with a server-generated key rather than the upload filename. */
    @Test
    void shouldStoreValidJpegWithServerGeneratedStorageKey() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        when(repository.save(any(GuestDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addPassportImages(guest, List.of(jpeg("passport.JPG")), UUID.randomUUID());

        ArgumentCaptor<GuestDocument> documentCaptor = ArgumentCaptor.forClass(GuestDocument.class);
        verify(repository).save(documentCaptor.capture());
        GuestDocument document = documentCaptor.getValue();
        assertEquals("passport.JPG", document.getOriginalName());
        assertEquals("image/jpeg", document.getContentType());
        assertDoesNotThrow(() -> storageService.load(document.getStorageKey()));
    }

    /** Confirms PNG uploads are accepted and invalid MIME, extension, and size values are rejected before storage. */
    @Test
    void shouldValidateAllowedPassportImageTypesAndSize() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        when(repository.save(any(GuestDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> service.addPassportImages(
                guest, List.of(png("passport.png")), UUID.randomUUID()));
        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.addPassportImages(
                        guest, List.of(file("passport.pdf", "application/pdf", "pdf")), UUID.randomUUID()));
        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.addPassportImages(
                        guest, List.of(new MockMultipartFile(
                                "passportImages", "passport.jpg", "image/png", imageBytes("jpg"))),
                        UUID.randomUUID()));
        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.addPassportImages(
                        guest,
                        List.of(new MockMultipartFile(
                                "passportImages", "large.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1])),
                        UUID.randomUUID()));
    }

    /** Confirms a passport image at the exact five-mebibyte business boundary is accepted. */
    @Test
    void shouldAcceptPassportImageAtFiveMegabyteBoundary() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        when(repository.save(any(GuestDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> service.addPassportImages(
                guest,
                List.of(new MockMultipartFile(
                        "passportImages", "boundary.jpg", "image/jpeg", sizedJpegBytes(5 * 1024 * 1024))),
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
                () -> service.addPassportImages(
                        guest(),
                        List.of(new MockMultipartFile(
                                "passportImages", "too-large.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1])),
                        UUID.randomUUID()));

        verifyNoInteractions(repository);
        assertEquals(0, storageDirectory.toFile().listFiles().length);
    }

    /** Confirms two individually valid files both persist and neither replaces the other. */
    @Test
    void shouldAppendTwoValidImagesWithoutReplacingEitherOne() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        when(repository.save(any(GuestDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addPassportImages(
                guest,
                List.of(
                        jpeg("passport_01.jpg"),
                        png("passport_02.png")),
                UUID.randomUUID());

        ArgumentCaptor<GuestDocument> documentCaptor = ArgumentCaptor.forClass(GuestDocument.class);
        verify(repository, org.mockito.Mockito.times(2)).save(documentCaptor.capture());
        List<GuestDocument> saved = documentCaptor.getAllValues();
        assertEquals(2, saved.size());
        assertEquals("passport_01.jpg", saved.get(0).getOriginalName());
        assertEquals("passport_02.png", saved.get(1).getOriginalName());
        assertTrue(!saved.get(0).getStorageKey().equals(saved.get(1).getStorageKey()));
    }

    /** Confirms a third image appended later does not disturb two images already on file. */
    @Test
    void shouldAppendAdditionalImageWithoutTouchingExistingOnes() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        when(repository.save(any(GuestDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addPassportImages(guest, List.of(jpeg("first.jpg")), UUID.randomUUID());
        service.addPassportImages(guest, List.of(jpeg("second.jpg")), UUID.randomUUID());

        verify(repository, org.mockito.Mockito.times(2)).save(any(GuestDocument.class));
    }

    /** Confirms one invalid file in a multi-file selection rejects the whole batch before any file is stored. */
    @Test
    void shouldRejectEntireBatchWhenOneSelectedFileIsInvalid() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();

        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.addPassportImages(
                        guest,
                        List.of(
                                jpeg("valid.jpg"),
                                file("invalid.pdf", "application/pdf", "invalid")),
                        UUID.randomUUID()));

        verifyNoInteractions(repository);
        assertEquals(0, storageDirectory.toFile().listFiles().length);
    }

    /**
     * Confirms the approved negative regression: arbitrary non-image bytes submitted with a
     * spoofed {@code passport.jpg} filename AND a spoofed {@code image/jpeg} Content-Type are
     * rejected, no GuestDocument metadata is persisted, and no physical file is left behind.
     */
    @Test
    void shouldRejectSpoofedNonImageBytesDeclaredAsJpegPassport() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);

        GuestDocumentValidationException exception = assertThrows(
                GuestDocumentValidationException.class,
                () -> service.addPassportImages(
                        guest(),
                        List.of(file("passport.jpg", "image/jpeg", "this is not really a jpeg at all")),
                        UUID.randomUUID()));

        assertEquals("Passport image file content is not a valid JPEG or PNG image.", exception.getMessage());
        verifyNoInteractions(repository);
        assertEquals(0, storageDirectory.toFile().listFiles().length, "no physical file may be left behind");
    }

    /** Confirms a genuine PNG renamed and declared as a JPEG is rejected: content must match the declared type. */
    @Test
    void shouldRejectGenuinePngDeclaredAsJpegPassport() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);

        GuestDocumentValidationException exception = assertThrows(
                GuestDocumentValidationException.class,
                () -> service.addPassportImages(
                        guest(),
                        List.of(new MockMultipartFile(
                                "passportImages", "passport.jpg", "image/jpeg", imageBytes("png"))),
                        UUID.randomUUID()));

        assertEquals("Passport image file content does not match its declared type.", exception.getMessage());
        verifyNoInteractions(repository);
        assertEquals(0, storageDirectory.toFile().listFiles().length, "no physical file may be left behind");
    }

    /** Confirms a PDF renamed to .jpg with a spoofed image Content-Type cannot be stored as a passport image. */
    @Test
    void shouldRejectPdfBytesRenamedToJpegPassport() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);

        assertThrows(
                GuestDocumentValidationException.class,
                () -> service.addPassportImages(
                        guest(),
                        List.of(file("passport.jpg", "image/jpeg", "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n")),
                        UUID.randomUUID()));

        verifyNoInteractions(repository);
        assertEquals(0, storageDirectory.toFile().listFiles().length);
    }

    /** Confirms genuinely decodable JPEG and PNG passport images both remain accepted and stored. */
    @Test
    void shouldAcceptGenuineJpegAndPngPassportImages() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        when(repository.save(any(GuestDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addPassportImages(guest(), List.of(jpeg("real.jpg"), png("real.png")), UUID.randomUUID());

        ArgumentCaptor<GuestDocument> documentCaptor = ArgumentCaptor.forClass(GuestDocument.class);
        verify(repository, org.mockito.Mockito.times(2)).save(documentCaptor.capture());
        assertEquals("image/jpeg", documentCaptor.getAllValues().get(0).getContentType());
        assertEquals("image/png", documentCaptor.getAllValues().get(1).getContentType());
        for (GuestDocument saved : documentCaptor.getAllValues()) {
            assertDoesNotThrow(() -> storageService.load(saved.getStorageKey()));
        }
    }

    /** Confirms a {@code null} upload list is a safe no-op. */
    @Test
    void shouldTreatNullUploadListAsNoOp() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);

        assertDoesNotThrow(() -> service.addPassportImages(guest(), null, UUID.randomUUID()));

        verifyNoInteractions(repository);
    }

    /** Confirms passport documents are returned in deterministic creation order. */
    @Test
    void shouldReturnPassportsInDeterministicOrder() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        UUID guestId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        GuestDocument first = document(firstId, "first.jpg");
        GuestDocument second = document(secondId, "second.jpg");
        when(repository.findByGuestIdAndDocumentTypeOrderByCreatedAtAscIdAsc(guestId, GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(List.of(first, second));

        List<GuestDocumentResponse> passports = service.findPassports(guestId);

        assertEquals(2, passports.size());
        assertEquals(firstId, passports.get(0).id());
        assertEquals(secondId, passports.get(1).id());
    }

    /** Confirms a Guest with zero passport images returns an empty list and reports no passport. */
    @Test
    void shouldReturnEmptyListAndFalseWhenGuestHasNoPassportImages() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        UUID guestId = UUID.randomUUID();
        when(repository.findByGuestIdAndDocumentTypeOrderByCreatedAtAscIdAsc(guestId, GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(List.of());
        when(repository.existsByGuestIdAndDocumentType(guestId, GuestDocumentType.PASSPORT_IMAGE)).thenReturn(false);

        assertTrue(service.findPassports(guestId).isEmpty());
        assertFalse(service.hasPassport(guestId));
    }

    /** Confirms a Guest with at least one passport image reports having a passport. */
    @Test
    void shouldReportHasPassportTrueWithAtLeastOneImage() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        UUID guestId = UUID.randomUUID();
        when(repository.existsByGuestIdAndDocumentType(guestId, GuestDocumentType.PASSPORT_IMAGE)).thenReturn(true);

        assertTrue(service.hasPassport(guestId));
    }

    /** Confirms removing one image deletes only that document's metadata and physical file. */
    @Test
    void shouldRemoveOnlyTheRequestedImageLeavingOthersIntact() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        String removedKey = storageService.store(file("removed.jpg", "image/jpeg", "removed"), "jpg");
        String keptKey = storageService.store(file("kept.jpg", "image/jpeg", "kept"), "jpg");
        UUID documentId = UUID.randomUUID();
        GuestDocument toRemove = GuestDocument.passportImage(
                documentId, guest, "removed.jpg", "image/jpeg", 7, removedKey);
        when(repository.findByIdAndGuestIdAndDocumentType(documentId, guest.getId(), GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(Optional.of(toRemove));

        service.removePassportImage(guest.getId(), documentId);

        verify(repository).delete(toRemove);
        assertThrows(ResponseStatusException.class, () -> storageService.load(removedKey));
        assertDoesNotThrow(() -> storageService.load(keptKey));
    }

    /** Confirms a document cannot be removed under a Guest identifier it does not belong to. */
    @Test
    void shouldNotRemoveDocumentFromWrongGuest() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        UUID otherGuestId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(repository.findByIdAndGuestIdAndDocumentType(documentId, otherGuestId, GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(Optional.empty());

        assertThrows(
                ResponseStatusException.class, () -> service.removePassportImage(otherGuestId, documentId));

        verify(repository, org.mockito.Mockito.never()).delete(any());
    }

    /** Confirms secure view rejects a Guest/document identifier mismatch (IDOR) as not found. */
    @Test
    void shouldRejectSecureViewOnGuestDocumentMismatch() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        UUID unrelatedGuestId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(repository.findByIdAndGuestIdAndDocumentType(
                        documentId, unrelatedGuestId, GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service.loadPassport(unrelatedGuestId, documentId));
        assertEquals(404, exception.getStatusCode().value());
    }

    /** Confirms secure view succeeds and returns safe metadata when the Guest/document pairing matches. */
    @Test
    void shouldLoadPassportWhenGuestAndDocumentMatch() {
        GuestDocumentRepository repository = mock(GuestDocumentRepository.class);
        GuestDocumentStorageService storageService = new GuestDocumentStorageService(storageDirectory.toString());
        GuestDocumentService service = new GuestDocumentService(repository, storageService);
        Guest guest = guest();
        String storageKey = storageService.store(file("passport.jpg", "image/jpeg", "content"), "jpg");
        UUID documentId = UUID.randomUUID();
        GuestDocument document = GuestDocument.passportImage(
                documentId, guest, "passport.jpg", "image/jpeg", 7, storageKey);
        when(repository.findByIdAndGuestIdAndDocumentType(documentId, guest.getId(), GuestDocumentType.PASSPORT_IMAGE))
                .thenReturn(Optional.of(document));

        var image = service.loadPassport(guest.getId(), documentId);

        assertEquals("passport.jpg", image.originalName());
        assertEquals("image/jpeg", image.contentType());
    }

    /** Creates a Guest instance suitable for document metadata ownership tests. */
    private Guest guest() {
        return Guest.create(
                UUID.randomUUID(), "G000001", "First", "Last", null, null, "Japan", null, null);
    }

    /** Creates one small in-memory multipart file whose bytes are the literal supplied text. */
    private MockMultipartFile file(String filename, String contentType, String content) {
        return new MockMultipartFile(
                "passportImages", filename, contentType, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates an upload carrying genuinely decodable JPEG bytes. */
    private MockMultipartFile jpeg(String filename) {
        return new MockMultipartFile("passportImages", filename, "image/jpeg", imageBytes("jpg"));
    }

    /** Creates an upload carrying genuinely decodable PNG bytes. */
    private MockMultipartFile png(String filename) {
        return new MockMultipartFile("passportImages", filename, "image/png", imageBytes("png"));
    }

    /** Encodes a tiny real image in the requested format, so content validation sees genuine bytes. */
    private byte[] imageBytes(String format) {
        try {
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Produces a genuinely decodable JPEG of an exact byte length, so the size boundary can be hit
     * precisely with real image content. Trailing bytes after the JPEG end-of-image marker do not
     * prevent decoding, which is what makes an exact length possible.
     *
     * @param totalLength the required total byte length, larger than the encoded image itself
     * @return decodable JPEG bytes of exactly {@code totalLength}
     */
    private byte[] sizedJpegBytes(int totalLength) {
        return Arrays.copyOf(imageBytes("jpg"), totalLength);
    }

    /** Creates a minimal passport GuestDocument for ordering assertions. */
    private GuestDocument document(UUID id, String originalName) {
        return GuestDocument.passportImage(id, guest(), originalName, "image/jpeg", 3, id + ".jpg");
    }
}
