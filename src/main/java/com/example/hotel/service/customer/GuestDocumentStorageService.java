package com.example.hotel.service.customer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Stores sensitive Guest document binaries beneath a configurable private application directory. */
@Service
public class GuestDocumentStorageService {

    private final Path storageRoot;

    /**
     * Creates the storage service using the configured Guest document root.
     *
     * @param storagePath private application-controlled document root
     */
    public GuestDocumentStorageService(@Value("${hotel.storage.guest-documents.path}") String storagePath) {
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    /**
     * Stores an already validated image with a server-controlled UUID storage key.
     *
     * @param upload validated passport image upload
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
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Passport image could not be stored.");
        }
    }

    /**
     * Loads a private file only after the caller has authorized access through document metadata.
     *
     * @param storageKey server-generated document storage key
     * @return the existing private image resource
     * @throws ResponseStatusException if the private file is unavailable
     */
    public Resource load(String storageKey) {
        Path file = resolve(storageKey);
        try {
            Resource resource = new UrlResource(file.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Passport image not found.");
            }
            return resource;
        } catch (java.net.MalformedURLException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Passport image not found.");
        }
    }

    /**
     * Deletes one privately stored file after a failed transaction or successful replacement.
     *
     * @param storageKey server-generated document storage key
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
            throw new IllegalArgumentException("Storage key is outside the configured document root.");
        }
        return resolved;
    }
}
