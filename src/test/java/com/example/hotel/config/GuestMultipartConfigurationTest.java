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
 * failing at the multipart-parsing layer before the business rule ever runs.
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
        assertEquals(DataSize.ofMegabytes(7), maxRequestSize);
        assertTrue(maxFileSize.toBytes() > passportBusinessLimit.toBytes(),
                "max-file-size must exceed the 5 MB Passport business limit so oversized uploads reach the validator");
        assertTrue(maxRequestSize.toBytes() > maxFileSize.toBytes(),
                "max-request-size must exceed max-file-size to leave room for multipart overhead");
    }
}
