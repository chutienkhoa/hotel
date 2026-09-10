package com.example.hotel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
/** Kiểm tra Flyway áp dụng đầy đủ migration trên PostgreSQL. */
class MigrationIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  /**
   * Cung cấp thông tin kết nối của PostgreSQL Testcontainers cho Spring.
   *
   * @param r registry chứa các property động
   */
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired org.flywaydb.core.Flyway flyway;

  @Test
  /** Xác nhận không còn migration chờ và migration đầu tiên đã được áp dụng. */
  void migrationIsCurrent() {
    assertEquals(0, flyway.info().pending().length);
    assertEquals(1, flyway.info().applied().length);
  }
}
