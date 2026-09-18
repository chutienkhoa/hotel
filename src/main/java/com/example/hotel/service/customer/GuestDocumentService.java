package com.example.hotel.service.customer;

import com.example.hotel.dto.customer.response.GuestDocumentResponse;
import com.example.hotel.dto.customer.response.GuestPassportImage;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.customer.GuestDocument;
import com.example.hotel.entity.customer.GuestDocumentType;
import com.example.hotel.exception.GuestDocumentValidationException;
import com.example.hotel.repository.customer.GuestDocumentRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages the privately stored passport images owned by each Guest. A Guest may own zero to many
 * PASSPORT_IMAGE documents, representing the booking Guest and any accompanying travelers, without
 * modeling which individual each image belongs to.
 */
@Service
public class GuestDocumentService {

    private static final long MAX_PASSPORT_IMAGE_SIZE = 5L * 1024 * 1024;

    private final GuestDocumentRepository guestDocumentRepository;
    private final GuestDocumentStorageService storageService;

    /**
     * Creates the document service with metadata persistence and private binary storage.
     *
     * @param guestDocumentRepository repository used to persist document metadata
     * @param storageService service used to access private image files
     */
    public GuestDocumentService(
            GuestDocumentRepository guestDocumentRepository, GuestDocumentStorageService storageService) {
        this.guestDocumentRepository = guestDocumentRepository;
        this.storageService = storageService;
    }

    /**
     * Appends newly selected passport images to a Guest's existing documents without touching
     * any of them. Every selected file is validated before any file is stored or any metadata is
     * persisted, so one invalid file rejects the entire batch instead of silently accepting the
     * others.
     *
     * @param guest guest owning the new documents
     * @param uploads optional browser uploads; a {@code null} list or one containing only empty,
     *     untouched optional file inputs is a no-op
     * @param auditUserId authenticated user responsible for the change
     */
    public void addPassportImages(Guest guest, List<MultipartFile> uploads, UUID auditUserId) {
        List<MultipartFile> selectedUploads = selectedFiles(uploads);
        if (selectedUploads.isEmpty()) {
            return;
        }

        List<ValidatedUpload> validatedUploads = selectedUploads.stream().map(this::validate).toList();
        for (int index = 0; index < selectedUploads.size(); index++) {
            MultipartFile upload = selectedUploads.get(index);
            ValidatedUpload validatedUpload = validatedUploads.get(index);
            String storageKey = storageService.store(upload, validatedUpload.extension());
            registerRollbackCleanup(storageKey);
            GuestDocument document = GuestDocument.passportImage(
                    UUID.randomUUID(),
                    guest,
                    validatedUpload.originalName(),
                    validatedUpload.contentType(),
                    validatedUpload.fileSize(),
                    storageKey);
            document.audit(auditUserId);
            guestDocumentRepository.save(document);
        }
    }

    /**
     * Removes one specific passport image belonging to a Guest.
     *
     * @param guestId Guest identifier the document must belong to
     * @param documentId document identifier to remove
     * @throws ResponseStatusException if no matching passport document exists for that Guest
     */
    @Transactional
    public void removePassportImage(UUID guestId, UUID documentId) {
        GuestDocument document = findPassportEntity(guestId, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Passport image not found."));
        String storageKey = document.getStorageKey();
        guestDocumentRepository.delete(document);
        registerCommittedDeletionCleanup(storageKey);
    }

    /**
     * Returns safe metadata for every passport image owned by a Guest, in deterministic display
     * order, used by Guest detail and edit pages.
     *
     * @param guestId Guest identifier
     * @return the Guest's passport documents, oldest first
     */
    public List<GuestDocumentResponse> findPassports(UUID guestId) {
        return guestDocumentRepository
                .findByGuestIdAndDocumentTypeOrderByCreatedAtAscIdAsc(guestId, GuestDocumentType.PASSPORT_IMAGE)
                .stream()
                .map(document -> new GuestDocumentResponse(document.getId(), document.getOriginalName()))
                .toList();
    }

    /**
     * Determines whether a Guest has at least one passport image on file. V1 does not require
     * the passport image count to match the number of staying people.
     *
     * @param guestId Guest identifier
     * @return {@code true} when at least one passport image exists
     */
    public boolean hasPassport(UUID guestId) {
        return guestDocumentRepository.existsByGuestIdAndDocumentType(guestId, GuestDocumentType.PASSPORT_IMAGE);
    }

    /**
     * Loads one specific passport image for an already authorized Guest-management or Check-in
     * request. The requested document must belong to the requested Guest and be a passport image,
     * preventing an unrelated Guest/document identifier pairing from resolving.
     *
     * @param guestId Guest identifier the document must belong to
     * @param documentId requested document identifier
     * @return image resource and response-safe content metadata
     * @throws ResponseStatusException if no matching passport image exists for that Guest
     */
    public GuestPassportImage loadPassport(UUID guestId, UUID documentId) {
        GuestDocument document = findPassportEntity(guestId, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Passport image not found."));
        return new GuestPassportImage(
                storageService.load(document.getStorageKey()), document.getContentType(), document.getOriginalName());
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
     * Validates the permitted passport image size, MIME type, and extension before storage.
     *
     * @param upload selected multipart upload
     * @return normalized safe metadata for the upload
     * @throws GuestDocumentValidationException if the upload is invalid
     */
    private ValidatedUpload validate(MultipartFile upload) {
        String originalName = upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename().trim();
        if (upload.isEmpty()) {
            throw new GuestDocumentValidationException("Passport image must not be empty.");
        }
        if (upload.getSize() > MAX_PASSPORT_IMAGE_SIZE) {
            throw new GuestDocumentValidationException("Passport image must not exceed 5 MB.");
        }
        if (originalName.isEmpty() || originalName.length() > 255) {
            throw new GuestDocumentValidationException("Passport image file name is invalid.");
        }

        String extension = extensionOf(originalName);
        String contentType = upload.getContentType() == null ? "" : upload.getContentType().toLowerCase(Locale.ROOT);
        if (!("jpg".equals(extension) || "jpeg".equals(extension) || "png".equals(extension))) {
            throw new GuestDocumentValidationException("Passport image must be a JPG or PNG file.");
        }
        if (!("image/jpeg".equals(contentType) || "image/png".equals(contentType))) {
            throw new GuestDocumentValidationException("Passport image must be a JPEG or PNG image.");
        }
        if (("png".equals(extension) && !"image/png".equals(contentType))
                || (("jpg".equals(extension) || "jpeg".equals(extension)) && !"image/jpeg".equals(contentType))) {
            throw new GuestDocumentValidationException("Passport image file type does not match its extension.");
        }
        return new ValidatedUpload(originalName, contentType, upload.getSize(), extension);
    }

    /**
     * Returns a lower-case extension from a browser filename without using it as a filesystem path.
     *
     * @param originalName browser-provided file name
     * @return the normalized extension, or an empty value when absent
     */
    private String extensionOf(String originalName) {
        int lastDot = originalName.lastIndexOf('.');
        return lastDot < 1 || lastDot == originalName.length() - 1
                ? ""
                : originalName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Loads one passport document metadata record only when it belongs to the given Guest.
     *
     * @param guestId Guest identifier the document must belong to
     * @param documentId requested document identifier
     * @return matching passport document when present
     */
    private Optional<GuestDocument> findPassportEntity(UUID guestId, UUID documentId) {
        return guestDocumentRepository.findByIdAndGuestIdAndDocumentType(
                documentId, guestId, GuestDocumentType.PASSPORT_IMAGE);
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
     * Removes a deleted document's physical file only after its database removal commits
     * successfully, so a rolled-back deletion never leaves the metadata pointing at a missing file.
     *
     * @param storageKey removed document's private storage key
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
    private record ValidatedUpload(String originalName, String contentType, long fileSize, String extension) {}
}
