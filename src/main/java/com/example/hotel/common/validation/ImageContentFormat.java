package com.example.hotel.common.validation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * The image formats the application accepts for uploaded photos and scanned documents, together
 * with the single primitive that determines the ACTUAL format of uploaded bytes.
 *
 * <p>This exists so every upload boundary that must verify real content (Room images, Guest
 * passport images) shares one implementation instead of trusting — or separately re-implementing
 * checks on — the client-declared file extension and {@code Content-Type}, either of which a
 * caller can set freely. Callers keep their own domain rules (size, filename, declared type,
 * message keys, storage); only the content probe is shared.</p>
 */
public enum ImageContentFormat {

    /** A genuinely decodable JPEG image. */
    JPEG,

    /** A genuinely decodable PNG image. */
    PNG;

    /**
     * Determines the actual format of the supplied bytes by decoding them with the JDK's built-in
     * image readers, never trusting a client-declared extension or {@code Content-Type}. Bytes
     * that fail to decode, or that decode to any other format, are reported as absent.
     *
     * <p>No maximum width/height is applied: no such limit is approved, so a genuine supported
     * image is never rejected merely for its dimensions. Callers bound the byte length themselves
     * before calling this.</p>
     *
     * @param bytes the uploaded file's raw bytes
     * @return the detected format, or empty when the content is not a supported, decodable image
     */
    public static Optional<ImageContentFormat> detect(byte[] bytes) {
        try (ImageInputStream inputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (inputStream == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(inputStream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(inputStream, true, true);
                Optional<ImageContentFormat> declared = normalize(reader.getFormatName());
                if (declared.isEmpty()) {
                    return Optional.empty();
                }
                // The reader must be able to produce the actual raster, not merely claim a format for the header.
                BufferedImage decoded = reader.read(0);
                return decoded == null ? Optional.empty() : declared;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    /**
     * Maps an ImageIO reader format name onto a supported format.
     *
     * @param formatName raw reader-supplied format name
     * @return the matching supported format, or empty when it is neither
     */
    private static Optional<ImageContentFormat> normalize(String formatName) {
        if (formatName == null) {
            return Optional.empty();
        }
        String upper = formatName.toUpperCase(Locale.ROOT);
        if (upper.contains("JPEG") || upper.contains("JPG")) {
            return Optional.of(JPEG);
        }
        if (upper.contains("PNG")) {
            return Optional.of(PNG);
        }
        return Optional.empty();
    }
}
