package com.example.hotel.service.room;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.room.response.RoomImageFile;
import com.example.hotel.dto.room.response.RoomImageResponse;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomImage;
import com.example.hotel.exception.RoomImageValidationException;
import com.example.hotel.repository.room.RoomImageRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies Room Images V1 business rules in isolation: batch validation (including actual image
 * content probing), the count cap, primary-image invariants, and Room ownership isolation.
 */
class RoomImageServiceTest {

    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID OTHER_ROOM_ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @TempDir
    Path storageDirectory;

    private RoomRepository roomRepository;
    private RoomImageRepository roomImageRepository;
    private RoomImageService service;
    private Room room;

    @BeforeEach
    void setUp() {
        roomRepository = mock(RoomRepository.class);
        roomImageRepository = mock(RoomImageRepository.class);
        RoomImageStorageService storageService = new RoomImageStorageService(storageDirectory.toString());
        service = new RoomImageService(roomRepository, roomImageRepository, storageService);
        room = Room.create(ROOM_ID, "101", null, "1");
        when(roomRepository.lockAllByIdIn(List.of(ROOM_ID))).thenReturn(List.of(room));
        when(roomImageRepository.save(any(RoomImage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(ACTOR, "manager"), null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Zero-image / historical Room
    // ---------------------------------------------------------------------------------------------------------

    /** Confirms a Room with no images returns an empty list, not an error. */
    @Test
    void shouldReturnEmptyListForRoomWithZeroImages() {
        when(roomImageRepository.findByRoomIdOrderByCreatedAtAscIdAsc(ROOM_ID)).thenReturn(List.of());

        List<RoomImageResponse> images = service.findByRoomId(ROOM_ID);

        assertTrue(images.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Upload validation
    // ---------------------------------------------------------------------------------------------------------

    /** Confirms a genuine JPEG is accepted and becomes the first (primary) image. */
    @Test
    void shouldAcceptValidJpegAndBecomePrimary() {
        stubEmptyRoom();

        service.addImages(ROOM_ID, List.of(jpeg("room.jpg")));

        RoomImage saved = capturedSave();
        assertEquals("room.jpg", saved.getOriginalFilename());
        assertEquals("image/jpeg", saved.getContentType());
        assertTrue(saved.isPrimary());
    }

    /** Confirms a stored image can be loaded back through the service using its generated storage key. */
    @Test
    void shouldStoreAndLoadRoundTrip() {
        stubEmptyRoom();

        service.addImages(ROOM_ID, List.of(jpeg("room.jpg")));
        RoomImage saved = capturedSave();
        when(roomImageRepository.findByIdAndRoomId(saved.getId(), ROOM_ID)).thenReturn(Optional.of(saved));

        RoomImageFile loaded = service.loadImage(ROOM_ID, saved.getId());

        assertEquals("image/jpeg", loaded.contentType());
        assertEquals("room.jpg", loaded.originalFilename());
        assertTrue(loaded.resource().exists());
    }

    /** Confirms a genuine PNG is accepted. */
    @Test
    void shouldAcceptValidPng() {
        stubEmptyRoom();

        service.addImages(ROOM_ID, List.of(png("room.png")));

        RoomImage saved = capturedSave();
        assertEquals("image/png", saved.getContentType());
    }

    /** Confirms a zero-byte upload is rejected before storage. */
    @Test
    void shouldRejectZeroByteUpload() {
        stubEmptyRoom();
        MockMultipartFile empty = new MockMultipartFile("images", "room.jpg", "image/jpeg", new byte[0]);

        assertThrows(RoomImageValidationException.class, () -> service.addImages(ROOM_ID, List.of(empty)));
        verifyNothingSaved();
    }

    /** Confirms an oversized upload is rejected before storage. */
    @Test
    void shouldRejectOversizedUpload() {
        stubEmptyRoom();
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile oversized = new MockMultipartFile("images", "room.jpg", "image/jpeg", tooLarge);

        assertThrows(RoomImageValidationException.class, () -> service.addImages(ROOM_ID, List.of(oversized)));
        verifyNothingSaved();
    }

    /** Confirms an unsupported declared extension/type is rejected. */
    @Test
    void shouldRejectUnsupportedType() {
        stubEmptyRoom();
        MockMultipartFile pdf = new MockMultipartFile("images", "room.pdf", "application/pdf", "not-an-image".getBytes());

        assertThrows(RoomImageValidationException.class, () -> service.addImages(ROOM_ID, List.of(pdf)));
        verifyNothingSaved();
    }

    /**
     * Confirms the ACTUAL decoded content is verified: a non-image payload with a spoofed JPEG
     * filename and Content-Type passes the declared-metadata checks but is still rejected once the
     * bytes fail to decode as a real image.
     */
    @Test
    void shouldRejectFakeImagePayloadWithSpoofedFilenameAndContentType() {
        stubEmptyRoom();
        MockMultipartFile fake =
                new MockMultipartFile("images", "room.jpg", "image/jpeg", "this is not really a jpeg".getBytes());

        RoomImageValidationException exception = assertThrows(
                RoomImageValidationException.class, () -> service.addImages(ROOM_ID, List.of(fake)));
        assertTrue(exception.getMessage().toLowerCase().contains("content"));
        verifyNothingSaved();
    }

    /** Confirms real PNG bytes declared/named as a JPEG are rejected: actual content must match the declared type. */
    @Test
    void shouldRejectContentTypeMismatchBetweenActualAndDeclaredFormat() {
        stubEmptyRoom();
        MockMultipartFile mislabeled = new MockMultipartFile("images", "room.jpg", "image/jpeg", pngBytes());

        assertThrows(RoomImageValidationException.class, () -> service.addImages(ROOM_ID, List.of(mislabeled)));
        verifyNothingSaved();
    }

    /** Confirms an overly long filename is rejected. */
    @Test
    void shouldRejectFilenameLongerThan255Characters() {
        stubEmptyRoom();
        String longName = "r".repeat(252) + ".jpg";
        MockMultipartFile file = new MockMultipartFile("images", longName, "image/jpeg", jpegBytes());

        assertThrows(RoomImageValidationException.class, () -> service.addImages(ROOM_ID, List.of(file)));
        verifyNothingSaved();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Count cap
    // ---------------------------------------------------------------------------------------------------------

    /** Confirms uploading exactly up to the 10-image cap is allowed. */
    @Test
    void shouldAllowUploadUpToTenImages() {
        when(roomImageRepository.countByRoomId(ROOM_ID)).thenReturn(8L);

        service.addImages(ROOM_ID, List.of(jpeg("a.jpg"), png("b.png")));

        List<RoomImage> saved = capturedSaves();
        assertEquals(2, saved.size());
    }

    /** Confirms a batch that would exceed 10 images is rejected in its entirety, with nothing stored. */
    @Test
    void shouldRejectBatchExceedingTenImages() {
        when(roomImageRepository.countByRoomId(ROOM_ID)).thenReturn(8L);

        assertThrows(
                RoomImageValidationException.class,
                () -> service.addImages(ROOM_ID, List.of(jpeg("a.jpg"), png("b.png"), jpeg("c.jpg"))));
        verifyNothingSaved();
    }

    /** Confirms one invalid file rejects the WHOLE batch: no earlier valid file is left stored. */
    @Test
    void shouldRejectEntireBatchWhenOneFileIsInvalid() {
        stubEmptyRoom();
        MockMultipartFile valid = jpeg("good.jpg");
        MockMultipartFile invalid = new MockMultipartFile("images", "bad.jpg", "image/jpeg", "garbage".getBytes());

        assertThrows(
                RoomImageValidationException.class, () -> service.addImages(ROOM_ID, List.of(valid, invalid)));
        verifyNothingSaved();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Primary-image invariants
    // ---------------------------------------------------------------------------------------------------------

    /** Confirms only the first image in a batch uploaded to an empty Room becomes primary; later ones do not. */
    @Test
    void shouldMakeOnlyFirstUploadedImagePrimaryWhenRoomWasEmpty() {
        stubEmptyRoom();

        service.addImages(ROOM_ID, List.of(jpeg("first.jpg"), png("second.png")));

        List<RoomImage> saved = capturedSaves();
        assertTrue(saved.get(0).isPrimary());
        assertFalse(saved.get(1).isPrimary());
    }

    /** Confirms uploads to a Room that already has images do not automatically become primary. */
    @Test
    void shouldNotAutoPrimaryWhenRoomAlreadyHasImages() {
        when(roomImageRepository.countByRoomId(ROOM_ID)).thenReturn(1L);

        service.addImages(ROOM_ID, List.of(jpeg("extra.jpg")));

        assertFalse(capturedSave().isPrimary());
    }

    /** Confirms setting a non-primary image as primary clears the previous primary first. */
    @Test
    void shouldSetPrimaryAndClearPreviousPrimary() {
        RoomImage current = existingImage(true);
        RoomImage target = existingImage(false);
        when(roomImageRepository.findByIdAndRoomId(target.getId(), ROOM_ID)).thenReturn(Optional.of(target));
        when(roomImageRepository.findByRoomIdAndPrimaryTrue(ROOM_ID)).thenReturn(Optional.of(current));

        service.setPrimary(ROOM_ID, target.getId());

        assertFalse(current.isPrimary());
        assertTrue(target.isPrimary());
    }

    /** Confirms setting an already-primary image as primary is a harmless no-op. */
    @Test
    void shouldNoOpWhenSettingAlreadyPrimaryImage() {
        RoomImage current = existingImage(true);
        when(roomImageRepository.findByIdAndRoomId(current.getId(), ROOM_ID)).thenReturn(Optional.of(current));

        assertDoesNotThrow(() -> service.setPrimary(ROOM_ID, current.getId()));
        assertTrue(current.isPrimary());
    }

    /** Confirms deleting a non-primary image does not touch the primary image. */
    @Test
    void shouldDeleteNonPrimaryImageWithoutPromoting() {
        RoomImage primary = existingImage(true);
        RoomImage nonPrimary = existingImage(false);
        when(roomImageRepository.findByIdAndRoomId(nonPrimary.getId(), ROOM_ID)).thenReturn(Optional.of(nonPrimary));

        service.removeImage(ROOM_ID, nonPrimary.getId());

        assertTrue(primary.isPrimary());
    }

    /** Confirms deleting the primary image promotes the oldest remaining image to primary. */
    @Test
    void shouldPromoteOldestRemainingImageWhenPrimaryIsDeleted() {
        RoomImage primary = existingImage(true);
        RoomImage oldestRemaining = existingImage(false);
        RoomImage newerRemaining = existingImage(false);
        when(roomImageRepository.findByIdAndRoomId(primary.getId(), ROOM_ID)).thenReturn(Optional.of(primary));
        when(roomImageRepository.findByRoomIdOrderByCreatedAtAscIdAsc(ROOM_ID))
                .thenReturn(List.of(oldestRemaining, newerRemaining));

        service.removeImage(ROOM_ID, primary.getId());

        assertTrue(oldestRemaining.isPrimary());
        assertFalse(newerRemaining.isPrimary());
    }

    /** Confirms deleting the last remaining image leaves the Room with zero primary images. */
    @Test
    void shouldLeaveZeroPrimaryWhenLastImageIsDeleted() {
        RoomImage onlyImage = existingImage(true);
        when(roomImageRepository.findByIdAndRoomId(onlyImage.getId(), ROOM_ID)).thenReturn(Optional.of(onlyImage));
        when(roomImageRepository.findByRoomIdOrderByCreatedAtAscIdAsc(ROOM_ID)).thenReturn(List.of());

        assertDoesNotThrow(() -> service.removeImage(ROOM_ID, onlyImage.getId()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Room isolation / IDOR
    // ---------------------------------------------------------------------------------------------------------

    /** Confirms an image belonging to one Room can never be loaded, removed, or promoted through another Room's id. */
    @Test
    void shouldRejectCrossRoomImageAccess() {
        RoomImage imageOfOtherRoom = existingImage(true);
        when(roomImageRepository.findByIdAndRoomId(imageOfOtherRoom.getId(), OTHER_ROOM_ID)).thenReturn(Optional.empty());
        when(roomRepository.lockAllByIdIn(List.of(OTHER_ROOM_ID)))
                .thenReturn(List.of(Room.create(OTHER_ROOM_ID, "202", null, "2")));

        assertThrows(ResponseStatusException.class, () -> service.loadImage(OTHER_ROOM_ID, imageOfOtherRoom.getId()));
        assertThrows(ResponseStatusException.class, () -> service.removeImage(OTHER_ROOM_ID, imageOfOtherRoom.getId()));
        assertThrows(ResponseStatusException.class, () -> service.setPrimary(OTHER_ROOM_ID, imageOfOtherRoom.getId()));
    }

    /** Confirms uploading to a Room that does not exist is rejected. */
    @Test
    void shouldRejectUploadForMissingRoom() {
        UUID missingRoomId = UUID.randomUUID();
        when(roomRepository.lockAllByIdIn(List.of(missingRoomId))).thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service.addImages(missingRoomId, List.of(jpeg("a.jpg"))));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------------------------------------------

    /** Confirms the storage key is server-generated (a UUID + validated extension), never the client filename. */
    @Test
    void shouldGenerateStorageKeyRatherThanTrustingClientFilename() {
        stubEmptyRoom();

        service.addImages(ROOM_ID, List.of(jpeg("../../etc/passwd.jpg")));

        RoomImage saved = capturedSave();
        assertFalse(saved.getStorageKey().contains(".."));
        assertTrue(saved.getStorageKey().matches("^[0-9a-fA-F-]{36}\\.jpg$"));
        assertEquals("../../etc/passwd.jpg", saved.getOriginalFilename(), "original filename is metadata only");
    }

    /** Confirms loading an image whose physical file is missing produces a clean not-found response, not a leak. */
    @Test
    void shouldReturnCleanNotFoundWhenPhysicalFileIsMissing() {
        RoomImage orphanMetadata = RoomImage.create(
                UUID.randomUUID(), room, "gone.jpg", "image/jpeg", 123L, "missing-key.jpg", true);
        when(roomImageRepository.findByIdAndRoomId(orphanMetadata.getId(), ROOM_ID))
                .thenReturn(Optional.of(orphanMetadata));

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service.loadImage(ROOM_ID, orphanMetadata.getId()));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertFalse(exception.getReason().contains(storageDirectory.toString()), "must not leak the filesystem path");
    }

    /** Confirms a newly stored file is deleted if the surrounding transaction rolls back. */
    @Test
    void shouldCleanUpNewlyStoredFileOnTransactionRollback() {
        stubEmptyRoom();
        TransactionSynchronizationManager.initSynchronization();
        RoomImage saved;
        try {
            service.addImages(ROOM_ID, List.of(jpeg("rollback.jpg")));
            saved = capturedSave();
            when(roomImageRepository.findByIdAndRoomId(saved.getId(), ROOM_ID)).thenReturn(Optional.of(saved));
            assertTrue(service.loadImage(ROOM_ID, saved.getId()).resource().exists(), "file exists before rollback");
        } finally {
            triggerRollbackAndClear();
        }

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service.loadImage(ROOM_ID, saved.getId()));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private void stubEmptyRoom() {
        when(roomImageRepository.countByRoomId(ROOM_ID)).thenReturn(0L);
    }

    private RoomImage capturedSave() {
        return capturedSaves().get(0);
    }

    private List<RoomImage> capturedSaves() {
        ArgumentCaptor<RoomImage> captor = ArgumentCaptor.forClass(RoomImage.class);
        org.mockito.Mockito.verify(roomImageRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    private void verifyNothingSaved() {
        org.mockito.Mockito.verify(roomImageRepository, org.mockito.Mockito.never()).save(any());
    }

    private RoomImage existingImage(boolean primary) {
        return RoomImage.create(
                UUID.randomUUID(), room, "existing.jpg", "image/jpeg", 1024L, UUID.randomUUID() + ".jpg", primary);
    }

    private MockMultipartFile jpeg(String filename) {
        return new MockMultipartFile("images", filename, "image/jpeg", jpegBytes());
    }

    private MockMultipartFile png(String filename) {
        return new MockMultipartFile("images", filename, "image/png", pngBytes());
    }

    private byte[] jpegBytes() {
        return encode("jpg");
    }

    private byte[] pngBytes() {
        return encode("png");
    }

    private byte[] encode(String format) {
        try {
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void triggerRollbackAndClear() {
        try {
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
