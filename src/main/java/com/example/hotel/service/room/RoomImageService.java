package com.example.hotel.service.room;

import com.example.hotel.dto.room.response.RoomImageFile;
import com.example.hotel.dto.room.response.RoomImageResponse;
import com.example.hotel.entity.room.Room;
import com.example.hotel.entity.room.RoomImage;
import com.example.hotel.exception.LocalizedResponseStatusException;
import com.example.hotel.exception.RoomImageValidationException;
import com.example.hotel.repository.room.RoomImageRepository;
import com.example.hotel.repository.room.RoomRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages the privately stored photos owned by each physical Room, independent from RoomType and
 * from {@code Room.status}. A Room may own zero to {@value #MAX_IMAGES_PER_ROOM} images; when at
 * least one exists, exactly one is the primary image, enforced by both a database partial unique
 * index and this service's transactional behavior.
 */
@Service
public class RoomImageService {

    private static final long MAX_ROOM_IMAGE_SIZE = 5L * 1024 * 1024;
    private static final int MAX_IMAGES_PER_ROOM = 10;

    private final RoomRepository roomRepository;
    private final RoomImageRepository roomImageRepository;
    private final RoomImageStorageService storageService;

    /**
     * Creates the Room image service with its persistence and private-storage collaborators.
     *
     * @param roomRepository repository used to lock the owning Room
     * @param roomImageRepository repository used to persist image metadata
     * @param storageService service used to access private image files
     */
    public RoomImageService(
            RoomRepository roomRepository,
            RoomImageRepository roomImageRepository,
            RoomImageStorageService storageService) {
        this.roomRepository = roomRepository;
        this.roomImageRepository = roomImageRepository;
        this.storageService = storageService;
    }

    /**
     * Returns safe metadata for every image owned by a Room, in deterministic display order.
     *
     * @param roomId Room identifier
     * @return the Room's images, oldest first
     */
    @Transactional(readOnly = true)
    public List<RoomImageResponse> findByRoomId(UUID roomId) {
        return roomImageRepository.findByRoomIdOrderByCreatedAtAscIdAsc(roomId).stream()
                .map(image -> new RoomImageResponse(image.getId(), image.getOriginalFilename(), image.isPrimary()))
                .toList();
    }

    /**
     * Appends newly selected images to a Room's existing images without touching any of them.
     * Every selected file, and the resulting total image count, is validated before any file is
     * stored or any metadata is persisted, so one invalid file — or a batch that would exceed
     * {@value #MAX_IMAGES_PER_ROOM} images — rejects the entire batch instead of partially
     * accepting it. The Room row is locked first, so concurrent uploads to the same Room are
     * serialized and the count/primary invariants stay correct under a race.
     *
     * @param roomId Room identifier
     * @param uploads optional browser uploads; a {@code null} list or one containing only empty,
     *     untouched optional file inputs is a no-op
     * @throws ResponseStatusException if the Room does not exist
     * @throws RoomImageValidationException if the batch is invalid
     */
    @Transactional
    public void addImages(UUID roomId, List<MultipartFile> uploads) {
        List<MultipartFile> selectedUploads = selectedFiles(uploads);
        if (selectedUploads.isEmpty()) {
            return;
        }
        UUID auditUserId = currentUser().id();
        Room room = lockRoom(roomId);
        long currentCount = roomImageRepository.countByRoomId(roomId);
        if (currentCount + selectedUploads.size() > MAX_IMAGES_PER_ROOM) {
            throw new RoomImageValidationException(
                    "room.image.error.tooMany",
                    "This upload would exceed the maximum of " + MAX_IMAGES_PER_ROOM + " images per room.");
        }
        List<ValidatedUpload> validatedUploads = selectedUploads.stream().map(this::validate).toList();

        boolean needsPrimary = currentCount == 0;
        for (int index = 0; index < selectedUploads.size(); index++) {
            MultipartFile upload = selectedUploads.get(index);
            ValidatedUpload validated = validatedUploads.get(index);
            String storageKey = storageService.store(upload, validated.extension());
            registerRollbackCleanup(storageKey);
            RoomImage image = RoomImage.create(
                    UUID.randomUUID(),
                    room,
                    validated.originalFilename(),
                    validated.contentType(),
                    validated.fileSize(),
                    storageKey,
                    needsPrimary && index == 0);
            image.audit(auditUserId);
            roomImageRepository.save(image);
        }
    }

    /**
     * Removes one specific image belonging to a Room. If the removed image was the primary image
     * and other images remain, the oldest remaining image automatically becomes primary in the
     * same transaction; if none remain, the Room has zero primary images.
     *
     * @param roomId Room identifier the image must belong to
     * @param imageId image identifier to remove
     * @throws ResponseStatusException if no matching image exists for that Room
     */
    @Transactional
    public void removeImage(UUID roomId, UUID imageId) {
        UUID auditUserId = currentUser().id();
        lockRoom(roomId);
        RoomImage image = roomImageRepository
                .findByIdAndRoomId(imageId, roomId)
                .orElseThrow(() -> new LocalizedResponseStatusException(
                        HttpStatus.NOT_FOUND, "room.image.error.notFound", "Room image not found."));
        boolean wasPrimary = image.isPrimary();
        String storageKey = image.getStorageKey();
        roomImageRepository.delete(image);
        if (wasPrimary) {
            // Flush the delete before re-querying so the promoted image is chosen from the images that
            // actually remain, and so the partial unique index never transiently sees two primaries.
            roomImageRepository.flush();
            roomImageRepository.findByRoomIdOrderByCreatedAtAscIdAsc(roomId).stream()
                    .findFirst()
                    .ifPresent(oldest -> {
                        oldest.markPrimary();
                        oldest.audit(auditUserId);
                        roomImageRepository.save(oldest);
                    });
        }
        registerCommittedDeletionCleanup(storageKey);
    }

    /**
     * Sets one specific image as the Room's primary image, clearing the previous primary (if any)
     * first so the database partial unique index is never transiently violated.
     *
     * @param roomId Room identifier the image must belong to
     * @param imageId image identifier to promote
     * @throws ResponseStatusException if no matching image exists for that Room
     */
    @Transactional
    public void setPrimary(UUID roomId, UUID imageId) {
        UUID auditUserId = currentUser().id();
        lockRoom(roomId);
        RoomImage target = roomImageRepository
                .findByIdAndRoomId(imageId, roomId)
                .orElseThrow(() -> new LocalizedResponseStatusException(
                        HttpStatus.NOT_FOUND, "room.image.error.notFound", "Room image not found."));
        if (target.isPrimary()) {
            return;
        }
        roomImageRepository.findByRoomIdAndPrimaryTrue(roomId).ifPresent(current -> {
            current.clearPrimary();
            current.audit(auditUserId);
            roomImageRepository.save(current);
        });
        roomImageRepository.flush();
        target.markPrimary();
        target.audit(auditUserId);
        roomImageRepository.save(target);
    }

    /**
     * Loads one specific image for an already authorized Room Management request. The requested
     * image must belong to the requested Room, preventing an unrelated Room/image identifier
     * pairing from resolving.
     *
     * @param roomId Room identifier the image must belong to
     * @param imageId requested image identifier
     * @return image resource and response-safe content metadata
     * @throws ResponseStatusException if no matching image exists for that Room
     */
    @Transactional(readOnly = true)
    public RoomImageFile loadImage(UUID roomId, UUID imageId) {
        RoomImage image = roomImageRepository
                .findByIdAndRoomId(imageId, roomId)
                .orElseThrow(() -> new LocalizedResponseStatusException(
                        HttpStatus.NOT_FOUND, "room.image.error.notFound", "Room image not found."));
        return new RoomImageFile(
                storageService.load(image.getStorageKey()), image.getContentType(), image.getOriginalFilename());
    }

    /**
     * Loads and pessimistically locks one Room to serialize concurrent image mutations for it,
     * matching the existing Room Management/check-in locking pattern.
     *
     * @param roomId Room identifier
     * @return the locked Room
     * @throws ResponseStatusException if the Room does not exist
     */
    private Room lockRoom(UUID roomId) {
        return roomRepository.lockAllByIdIn(List.of(roomId)).stream()
                .findFirst()
                .orElseThrow(() -> new LocalizedResponseStatusException(
                        HttpStatus.NOT_FOUND, "room.image.error.roomNotFound", "Room not found."));
    }

    /**
     * Resolves the current JWT or session principal to the audit identity used by persisted entities.
     *
     * @return the authenticated application user
     * @throws ResponseStatusException if the active principal is not an application user
     */
    private CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.getUsername());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated user");
    }

    /**
     * Determines whether the multipart request contains an actual selected file rather than the
     * empty part emitted by an untouched optional browser file input.
     *
     * @param upload optional multipart upload
     * @return {@code true} when the browser supplied a file name or content
     */
    private boolean hasSelectedFile(MultipartFile upload) {
        return upload != null
                && (!upload.isEmpty()
                        || (upload.getOriginalFilename() != null && !upload.getOriginalFilename().isBlank()));
    }

    /**
     * Filters a submitted multipart file list down to actually selected files.
     *
     * @param uploads optional browser uploads
     * @return the selected files, in submitted order; never {@code null}
     */
    private List<MultipartFile> selectedFiles(List<MultipartFile> uploads) {
        if (uploads == null) {
            return List.of();
        }
        return uploads.stream().filter(this::hasSelectedFile).toList();
    }

    /**
     * Validates the permitted Room image size, filename, declared MIME type/extension, and the
     * ACTUAL decoded image content before storage. Unlike the existing Guest passport upload, this
     * does not stop at the client-declared extension and Content-Type: the bytes are decoded and
     * their real format is verified, so a non-image file (or an image of an unsupported format)
     * renamed with a spoofed filename and Content-Type is rejected.
     *
     * @param upload selected multipart upload
     * @return normalized safe metadata for the upload
     * @throws RoomImageValidationException if the upload is invalid
     */
    private ValidatedUpload validate(MultipartFile upload) {
        String originalFilename = upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename().trim();
        if (upload.isEmpty()) {
            throw new RoomImageValidationException("room.image.error.empty", "Room image must not be empty.");
        }
        if (upload.getSize() > MAX_ROOM_IMAGE_SIZE) {
            throw new RoomImageValidationException("room.image.error.tooLarge", "Room image must not exceed 5 MB.");
        }
        if (originalFilename.isEmpty() || originalFilename.length() > 255) {
            throw new RoomImageValidationException(
                    "room.image.error.invalidFilename", "Room image file name is invalid.");
        }

        String extension = extensionOf(originalFilename);
        String contentType = upload.getContentType() == null ? "" : upload.getContentType().toLowerCase(Locale.ROOT);
        if (!("jpg".equals(extension) || "jpeg".equals(extension) || "png".equals(extension))) {
            throw new RoomImageValidationException(
                    "room.image.error.unsupportedType", "Room image must be a JPG or PNG file.");
        }
        if (!("image/jpeg".equals(contentType) || "image/png".equals(contentType))) {
            throw new RoomImageValidationException(
                    "room.image.error.unsupportedContentType", "Room image must be a JPEG or PNG image.");
        }
        boolean declaredPng = "png".equals(extension);
        if (declaredPng != "image/png".equals(contentType)) {
            throw new RoomImageValidationException(
                    "room.image.error.typeMismatch", "Room image file type does not match its extension.");
        }

        byte[] bytes = readBytes(upload);
        String actualFormat = probeSupportedImageFormat(bytes);
        if (actualFormat == null) {
            throw new RoomImageValidationException(
                    "room.image.error.invalidContent", "Room image file content is not a valid JPEG or PNG image.");
        }
        boolean actualPng = "PNG".equals(actualFormat);
        if (actualPng != declaredPng) {
            throw new RoomImageValidationException(
                    "room.image.error.contentMismatch", "Room image file content does not match its declared type.");
        }
        return new ValidatedUpload(originalFilename, contentType, upload.getSize(), extension);
    }

    /**
     * Reads the full upload into memory so its actual bytes can be probed; bounded by the size
     * check already enforced above.
     *
     * @param upload selected multipart upload
     * @return the upload's raw bytes
     * @throws RoomImageValidationException if the upload cannot be read
     */
    private byte[] readBytes(MultipartFile upload) {
        try {
            return upload.getBytes();
        } catch (IOException exception) {
            throw new RoomImageValidationException("room.image.error.unreadable", "Room image could not be read.");
        }
    }

    /**
     * Decodes the uploaded bytes with the JDK's built-in image I/O readers and returns the actual
     * detected format, never trusting the client-declared extension or Content-Type. A file that
     * fails to decode or decodes to an unsupported format is rejected. There is no maximum
     * width/height: no such limit is approved, so a genuine supported JPEG/PNG is never rejected
     * merely for its dimensions.
     *
     * @param bytes the uploaded file's raw bytes
     * @return {@code "JPEG"} or {@code "PNG"}, or {@code null} when the content is not a
     *     supported, decodable image
     */
    private String probeSupportedImageFormat(byte[] bytes) {
        try (ImageInputStream inputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (inputStream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(inputStream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(inputStream, true, true);
                String normalizedFormat = normalizeFormat(reader.getFormatName());
                if (normalizedFormat == null) {
                    return null;
                }
                BufferedImage decoded = reader.read(0);
                return decoded == null ? null : normalizedFormat;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * Normalizes an ImageIO reader format name to {@code "JPEG"} or {@code "PNG"}.
     *
     * @param formatName raw reader-supplied format name
     * @return the normalized format, or {@code null} when it is neither supported format
     */
    private String normalizeFormat(String formatName) {
        if (formatName == null) {
            return null;
        }
        String upper = formatName.toUpperCase(Locale.ROOT);
        if (upper.contains("JPEG") || upper.contains("JPG")) {
            return "JPEG";
        }
        if (upper.contains("PNG")) {
            return "PNG";
        }
        return null;
    }

    /**
     * Returns a lower-case extension from a browser filename without using it as a filesystem path.
     *
     * @param originalFilename browser-provided file name
     * @return the normalized extension, or an empty value when absent
     */
    private String extensionOf(String originalFilename) {
        int lastDot = originalFilename.lastIndexOf('.');
        return lastDot < 1 || lastDot == originalFilename.length() - 1
                ? ""
                : originalFilename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Removes a newly written physical file if the surrounding database transaction rolls back.
     *
     * @param storageKey newly generated storage key
     */
    private void registerRollbackCleanup(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storageService.deleteQuietly(storageKey);
                }
            }
        });
    }

    /**
     * Removes a deleted image's physical file only after its database removal commits
     * successfully, so a rolled-back deletion never leaves the metadata pointing at a missing file.
     *
     * @param storageKey removed image's private storage key
     */
    private void registerCommittedDeletionCleanup(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storageService.deleteQuietly(storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storageService.deleteQuietly(storageKey);
            }
        });
    }

    /** Holds validated upload metadata before it is persisted. */
    private record ValidatedUpload(String originalFilename, String contentType, long fileSize, String extension) {}
}
