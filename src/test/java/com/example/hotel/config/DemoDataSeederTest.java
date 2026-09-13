package com.example.hotel.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the dev seeder never alters an existing marked demo dataset. */
@ExtendWith(MockitoExtension.class)
class DemoDataSeederTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldNotWriteWhenTheDemoMarkerAlreadyExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("demo-data-seeder")))
                .thenReturn(1);

        new DemoDataSeeder().seed(
                jdbcTemplate,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));

        verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), eq("demo-data-seeder"));
        verifyNoMoreInteractions(jdbcTemplate);
    }
}
