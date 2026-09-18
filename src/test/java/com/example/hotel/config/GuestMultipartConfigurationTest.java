package com.example.hotel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

/**
 * Verifies the servlet multipart limits stay above the 5 MB Passport Image business limit, so an
 * oversized upload reaches the Guest passport validator (and its friendly field error) instead of
 * failing at the multipart-parsing layer before the business rule ever runs, and that the total
 * request limit is bounded but wide enough for a reasonable multi-image Guest passport upload
 * (several individually valid <= 5 MB files selected in one request).
 */
class GuestMultipartConfigurationTest {

    /** Confirms both multipart limits are bounded but strictly greater than the 5 MB business limit. */
    @Test
    void shouldConfigureMultipartLimitsAboveFiveMegabytePassportBusinessLimit() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        DataSize maxFileSize = DataSize.parse(properties.getProperty("spring.servlet.multipart.max-file-size"));
        DataSize maxRequestSize = DataSize.parse(properties.getProperty("spring.servlet.multipart.max-request-size"));
        DataSize passportBusinessLimit = DataSize.ofMegabytes(5);

        assertEquals(DataSize.ofMegabytes(6), maxFileSize);
        assertEquals(DataSize.ofMegabytes(40), maxRequestSize);
        assertTrue(maxFileSize.toBytes() > passportBusinessLimit.toBytes(),
                "max-file-size must exceed the 5 MB Passport business limit so oversized uploads reach the validator");
        assertTrue(maxRequestSize.toBytes() > maxFileSize.toBytes(),
                "max-request-size must exceed max-file-size to leave room for multipart overhead");
    }

    /** Confirms the total request limit fits several individually valid passport images at once. */
    @Test
    void shouldFitSeveralValidPassportImagesInOneRequest() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        DataSize maxRequestSize = DataSize.parse(properties.getProperty("spring.servlet.multipart.max-request-size"));
        DataSize passportBusinessLimit = DataSize.ofMegabytes(5);

        long imagesThatFit = maxRequestSize.toBytes() / passportBusinessLimit.toBytes();

        assertTrue(imagesThatFit >= 6,
                "max-request-size must comfortably fit multiple 5 MB passport images (e.g. a family booking) "
                        + "in one Create/Edit Guest submission, not just a single image");
    }
}
