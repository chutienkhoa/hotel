package com.example.hotel.entity.customer;

import com.example.hotel.entity.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Stores metadata for one privately held Guest document without storing its binary content in
 * the database. A Guest may own zero to many PASSPORT_IMAGE documents; each is an independently
 * secured stored file representing the booking Guest or an accompanying traveler, without
 * modeling which individual it belongs to.
 */
@Entity
@Table(name = "guest_document")
public class GuestDocument extends AuditedEntity {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "guest_id", nullable = false)
    private Guest guest;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 32)
    private GuestDocumentType documentType;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "storage_key", nullable = false, unique = true, length = 255)
    private String storageKey;

    /** Creates an empty entity for JPA. */
    protected GuestDocument() {}

    /**
     * Creates metadata for a newly stored passport image.
     *
     * @param id document identifier
     * @param guest owning guest
     * @param originalName original upload filename retained only as metadata
     * @param contentType validated media type
     * @param fileSize validated file size in bytes
     * @param storageKey server-generated private storage key
     * @return the new passport document metadata
     */
    public static GuestDocument passportImage(
            UUID id,
            Guest guest,
            String originalName,
            String contentType,
            long fileSize,
            String storageKey) {
        GuestDocument document = new GuestDocument();
        document.id = id;
        document.guest = guest;
        document.documentType = GuestDocumentType.PASSPORT_IMAGE;
        document.originalName = originalName;
        document.contentType = contentType;
        document.fileSize = fileSize;
        document.storageKey = storageKey;
        return document;
    }

    /**
     * Returns the document identifier.
     *
     * @return the document identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning guest.
     *
     * @return the owning guest
     */
    public Guest getGuest() {
        return guest;
    }

    /**
     * Returns the document type.
     *
     * @return the document type
     */
    public GuestDocumentType getDocumentType() {
        return documentType;
    }

    /**
     * Returns the original uploaded filename.
     *
     * @return the original filename
     */
    public String getOriginalName() {
        return originalName;
    }

    /**
     * Returns the validated content type.
     *
     * @return the media type
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Returns the stored file size in bytes.
     *
     * @return the file size
     */
    public long getFileSize() {
        return fileSize;
    }

    /**
     * Returns the private server-generated storage key.
     *
     * @return the storage key
     */
    public String getStorageKey() {
        return storageKey;
    }
}
