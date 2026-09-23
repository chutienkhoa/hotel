package com.example.hotel.common.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Verifies the generic storage primitive shared by Guest documents and Room images: key generation, safe root-relative resolution, and best-effort delete/load behavior. */
class FileStorageServiceTest {

    @TempDir
    Path root;

    /** Confirms a stored file is retrievable and its generated key is a UUID-based name, not the client filename. */
    @Test
    void shouldStoreWithGeneratedKeyAndLoadItBack() {
        FileStorageService storage = new FileStorageService(root, "Test file");
        MockMultipartFile upload = new MockMultipartFile("file", "irrelevant.jpg", "image/jpeg", "data".getBytes());

        String key = storage.store(upload, "jpg");

        assertTrue(key.matches("^[0-9a-fA-F-]{36}\\.jpg$"));
        assertDoesNotThrow(() -> storage.load(key));
    }

    /** Confirms a storage key attempting to traverse outside the configured root is rejected, never resolved. */
    @Test
    void shouldRejectPathTraversalAttempt() {
        FileStorageService storage = new FileStorageService(root, "Test file");

        assertThrows(IllegalArgumentException.class, () -> storage.load("../outside.jpg"));
        assertThrows(IllegalArgumentException.class, () -> storage.load("../../etc/passwd"));
    }

    /** Confirms loading a key with no corresponding file produces a clean not-found response. */
    @Test
    void shouldReturnNotFoundForMissingFile() {
        FileStorageService storage = new FileStorageService(root, "Test file");

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> storage.load("00000000-0000-0000-0000-000000000000.jpg"));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    /** Confirms deleting a non-existent file is a silent no-op, never throwing. */
    @Test
    void shouldDeleteQuietlyEvenWhenFileDoesNotExist() {
        FileStorageService storage = new FileStorageService(root, "Test file");

        assertDoesNotThrow(() -> storage.deleteQuietly("does-not-exist.jpg"));
    }

    /** Confirms deleting a path-traversal key never throws (best-effort, safe by construction). */
    @Test
    void shouldNotThrowWhenDeletingATraversalKey() {
        FileStorageService storage = new FileStorageService(root, "Test file");

        assertDoesNotThrow(() -> storage.deleteQuietly("../outside.jpg"));
    }
}
