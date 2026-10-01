package com.example.hotel.entity.room;

import com.example.hotel.entity.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Stores metadata for one privately held photo of a physical Room without storing its binary
 * content in the database. A Room may own zero to many RoomImage rows; at most one may be the
 * primary image at any time (enforced by a database partial unique index on {@code room_id}
 * where {@code is_primary}).
 */
@Entity
@Table(name = "room_image")
public class RoomImage extends AuditedEntity {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(name = "storage_key", nullable = false, unique = true, length = 255)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    /** Creates an empty entity for JPA. */
    protected RoomImage() {}

    /**
     * Creates metadata for a newly stored Room image.
     *
     * @param id image identifier
     * @param room owning Room
     * @param originalFilename original upload filename retained only as display metadata
     * @param contentType validated media type
     * @param fileSize validated file size in bytes
     * @param storageKey server-generated private storage key
     * @param primary whether this image becomes the Room's primary image
     * @return the new Room image metadata
     */
    public static RoomImage create(
            UUID id,
            Room room,
            String originalFilename,
            String contentType,
            long fileSize,
            String storageKey,
            boolean primary) {
        RoomImage image = new RoomImage();
        image.id = id;
        image.room = room;
        image.originalFilename = originalFilename;
        image.contentType = contentType;
        image.fileSize = fileSize;
        image.storageKey = storageKey;
        image.primary = primary;
        return image;
    }

    /**
     * Returns the image identifier.
     *
     * @return the image identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning Room.
     *
     * @return the owning Room
     */
    public Room getRoom() {
        return room;
    }

    /**
     * Returns the private server-generated storage key.
     *
     * @return the storage key
     */
    public String getStorageKey() {
        return storageKey;
    }

    /**
     * Returns the original uploaded filename.
     *
     * @return the original filename
     */
    public String getOriginalFilename() {
        return originalFilename;
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
     * Returns whether this is the Room's current primary image.
     *
     * @return {@code true} when this image is the Room's primary image
     */
    public boolean isPrimary() {
        return primary;
    }

    /** Marks this image as the Room's primary image. */
    public void markPrimary() {
        primary = true;
    }

    /** Clears this image's primary flag. */
    public void clearPrimary() {
        primary = false;
    }
}
