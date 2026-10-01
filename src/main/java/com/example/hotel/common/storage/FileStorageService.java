package com.example.hotel.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generic, domain-agnostic primitive for storing binary uploads beneath a private,
 * application-controlled directory: server-generated storage keys, safe root-relative path
 * resolution, store/load/best-effort-delete. Extracted from the original Guest passport-image
 * storage implementation ({@code GuestDocumentStorageService}, unchanged) because its low-level
 * filesystem behavior is not specific to any one domain. Each domain (Guest documents, Room
 * images, ...) owns its own instance configured with its own private root, so file keys never
 * collide across domains and one domain's files can never be reached through another's storage
 * key.
 */
public class FileStorageService {

    private final Path storageRoot;
    private final String resourceName;

    /**
     * Creates the storage primitive for one private domain root.
     *
     * @param storageRoot private application-controlled root directory for this domain
     * @param resourceName human-readable resource name used in browser-safe error messages
     *     (for example {@code "Room image"})
     */
    public FileStorageService(Path storageRoot, String resourceName) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.resourceName = resourceName;
    }

    /**
     * Stores an already validated upload with a server-controlled UUID storage key.
     *
     * @param upload validated upload
     * @param extension validated lower-case file extension
     * @return the generated storage key
     * @throws ResponseStatusException if private storage cannot write the file
     */
    public String store(MultipartFile upload, String extension) {
        String storageKey = UUID.randomUUID() + "." + extension;
        Path destination = resolve(storageKey);
        try {
            Files.createDirectories(storageRoot);
            try (InputStream input = upload.getInputStream()) {
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return storageKey;
        } catch (IOException exception) {
            deleteQuietly(storageKey);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, resourceName + " could not be stored.");
        }
    }

    /**
     * Loads a private file only after the caller has authorized access through its own domain
     * metadata.
     *
     * @param storageKey server-generated storage key
     * @return the existing private resource
     * @throws ResponseStatusException if the private file is unavailable
     */
    public Resource load(String storageKey) {
        Path file = resolve(storageKey);
        try {
            Resource resource = new UrlResource(file.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, resourceName + " not found.");
            }
            return resource;
        } catch (MalformedURLException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, resourceName + " not found.");
        }
    }

    /**
     * Deletes one privately stored file after a failed transaction or successful removal.
     *
     * @param storageKey server-generated storage key
     */
    public void deleteQuietly(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException | IllegalArgumentException ignored) {
            // A failed cleanup must not expose a filesystem path or mask the business operation result.
        }
    }

    /**
     * Resolves a storage key while preventing traversal outside the configured private root.
     *
     * @param storageKey server-generated storage key
     * @return the safe resolved path
     */
    private Path resolve(String storageKey) {
        Path resolved = storageRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Storage key is outside the configured storage root.");
        }
        return resolved;
    }
}
