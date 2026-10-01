package com.example.hotel.service.room;

import com.example.hotel.common.storage.FileStorageService;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores Room image binaries beneath a configurable private application directory, separate from
 * the Guest passport-document root so the two domains never share a directory or a storage key.
 */
@Service
public class RoomImageStorageService {

    private final FileStorageService storage;

    /**
     * Creates the storage service using the configured Room image root.
     *
     * @param storagePath private application-controlled Room image root
     */
    public RoomImageStorageService(@Value("${hotel.storage.room-images.path}") String storagePath) {
        this.storage = new FileStorageService(Path.of(storagePath), "Room image");
    }

    /**
     * Stores an already validated image with a server-controlled UUID storage key.
     *
     * @param upload validated Room image upload
     * @param extension validated lower-case file extension
     * @return the generated storage key
     * @throws ResponseStatusException if private storage cannot write the file
     */
    public String store(MultipartFile upload, String extension) {
        return storage.store(upload, extension);
    }

    /**
     * Loads a private file only after the caller has authorized access through Room image metadata.
     *
     * @param storageKey server-generated image storage key
     * @return the existing private image resource
     * @throws ResponseStatusException if the private file is unavailable
     */
    public Resource load(String storageKey) {
        return storage.load(storageKey);
    }

    /**
     * Deletes one privately stored file after a failed transaction or successful removal.
     *
     * @param storageKey server-generated image storage key
     */
    public void deleteQuietly(String storageKey) {
        storage.deleteQuietly(storageKey);
    }
}
