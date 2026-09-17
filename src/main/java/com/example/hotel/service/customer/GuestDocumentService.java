package com.example.hotel.service.customer;

import com.example.hotel.dto.customer.response.GuestDocumentResponse;
import com.example.hotel.dto.customer.response.GuestPassportImage;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.entity.customer.GuestDocument;
import com.example.hotel.entity.customer.GuestDocumentType;
import com.example.hotel.exception.GuestDocumentValidationException;
import com.example.hotel.repository.customer.GuestDocumentRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Manages one optional, privately stored passport image for each Guest. */
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
     * Stores a new passport image or safely replaces the existing one when a file was selected.
     *
     * @param guest guest owning the document
     * @param upload optional browser upload
     * @param auditUserId authenticated user responsible for the change
     */
    public void storeOrReplacePassport(Guest guest, MultipartFile upload, UUID auditUserId) {
        if (!hasSelectedFile(upload)) {
            return;
        }

        ValidatedUpload validatedUpload = validate(upload);
        String newStorageKey = storageService.store(upload, validatedUpload.extension());
        registerRollbackCleanup(newStorageKey);
        try {
            Optional<GuestDocument> existing = findPassportEntity(guest.getId());
            if (existing.isPresent()) {
                GuestDocument document = existing.get();
                String oldStorageKey = document.getStorageKey();
                document.replace(
                        validatedUpload.originalName(),
                        validatedUpload.contentType(),
                        validatedUpload.fileSize(),
                        newStorageKey);
                document.audit(auditUserId);
                guestDocumentRepository.save(document);
                registerCommittedReplacementCleanup(oldStorageKey);
                return;
            }

            GuestDocument document = GuestDocument.passportImage(
                    UUID.randomUUID(),
                    guest,
                    validatedUpload.originalName(),
                    validatedUpload.contentType(),
                    validatedUpload.fileSize(),
                    newStorageKey);
            document.audit(auditUserId);
            guestDocumentRepository.save(document);
        } catch (RuntimeException exception) {
            storageService.deleteQuietly(newStorageKey);
            throw exception;
        }
    }

    /**
     * Returns safe metadata used by Guest detail and edit pages.
     *
     * @param guestId Guest identifier
     * @return passport metadata when one exists
     */
    public Optional<GuestDocumentResponse> findPassport(UUID guestId) {
        return findPassportEntity(guestId).map(document -> new GuestDocumentResponse(document.getOriginalName()));
    }

    /**
     * Loads one passport image for an already authorized Guest-management request.
     *
     * @param guestId Guest identifier
     * @return image resource and response-safe content metadata
     * @throws ResponseStatusException if no passport image exists
     */
    public GuestPassportImage loadPassport(UUID guestId) {
        GuestDocument document = findPassportEntity(guestId)
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
     * Loads the one permitted passport metadata record for a Guest.
     *
     * @param guestId Guest identifier
     * @return matching passport document when present
     */
    private Optional<GuestDocument> findPassportEntity(UUID guestId) {
        return guestDocumentRepository.findByGuestIdAndDocumentType(guestId, GuestDocumentType.PASSPORT_IMAGE);
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
     * Removes an obsolete passport only after replacement metadata commits successfully.
     *
     * @param oldStorageKey previous private storage key
     */
    private void registerCommittedReplacementCleanup(String oldStorageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storageService.deleteQuietly(oldStorageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storageService.deleteQuietly(oldStorageKey);
            }
        });
    }

    /** Holds validated upload metadata before it is persisted. */
    private record ValidatedUpload(String originalName, String contentType, long fileSize, String extension) {}
}
