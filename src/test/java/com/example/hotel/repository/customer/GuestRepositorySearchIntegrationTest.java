package com.example.hotel.repository.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.hotel.dto.customer.request.GuestSearchCriteria;
import com.example.hotel.dto.customer.response.GuestListResponse;
import com.example.hotel.service.customer.GuestQueryService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the Guest Management list filters and pagination against real PostgreSQL filtering,
 * exercising {@link GuestQueryService#findPage} end-to-end rather than a mocked specification.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GuestRepositorySearchIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private GuestQueryService guestQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Supplies Testcontainers database connection properties. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Confirms no active filters returns every Guest ordered deterministically by guest code. */
    @Test
    @Transactional
    void shouldReturnAllGuestsOrderedByGuestCodeWhenNoFilterIsActive() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> page = guestQueryService.findPage(new GuestSearchCriteria(), 0);

        assertEquals(3, page.getTotalElements());
        assertEquals(
                List.of("G000001", "G000002", "G000003"),
                page.getContent().stream().map(GuestListResponse::guestCode).toList());
    }

    /** Confirms a trimmed, partial Guest Code filter matches only the owning Guest. */
    @Test
    @Transactional
    void shouldMatchGuestCodePartially() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> page = search(criteria -> criteria.setGuestCode("  00002  "));

        assertSingleMatch(page, "G000002");
    }

    /** Confirms a partial first-name filter matches only the owning Guest. */
    @Test
    @Transactional
    void shouldMatchFirstNamePartially() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> page = search(criteria -> criteria.setFirstName("hoa"));

        assertSingleMatch(page, "G000001");
    }

    /** Confirms a partial last-name filter matches only the owning Guest. */
    @Test
    @Transactional
    void shouldMatchLastNamePartially() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> page = search(criteria -> criteria.setLastName("mit"));

        assertSingleMatch(page, "G000003");
    }

    /** Confirms a partial email filter matches only the owning Guest. */
    @Test
    @Transactional
    void shouldMatchEmailPartially() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> page = search(criteria -> criteria.setEmail("yuki.tanaka"));

        assertSingleMatch(page, "G000002");
    }

    /** Confirms selecting a canonical country matches the Guest whose stored nationality is that country. */
    @Test
    @Transactional
    void shouldMatchNationalityByCanonicalCountrySelection() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> page = search(criteria -> criteria.setNationality("United States"));

        assertSingleMatch(page, "G000003");
    }

    /** Confirms nationality country-selection matching is case-insensitive. */
    @Test
    @Transactional
    void shouldMatchNationalityCaseInsensitively() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> page = search(criteria -> criteria.setNationality("VIETNAM"));

        assertSingleMatch(page, "G000001");
    }

    /** Confirms selecting a canonical country also matches a Guest with the known legacy demonym stored. */
    @Test
    @Transactional
    void shouldMatchNationalityFilterAgainstKnownLegacyDemonym() {
        UUID userId = insertUser();
        seedThreeGuests(userId);
        insertGuest(userId, "G000004", "Aiko", "Sato", "aiko.sato@example.jp", "Japanese");

        Page<GuestListResponse> page = search(criteria -> criteria.setNationality("Japan"));

        assertEquals(2, page.getTotalElements());
        assertEquals(
                List.of("G000002", "G000004"),
                page.getContent().stream().map(GuestListResponse::guestCode).toList());
    }

    /** Confirms a blank filter value is ignored rather than treated as a literal empty match. */
    @Test
    @Transactional
    void shouldIgnoreBlankFilterValues() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> page = search(criteria -> {
            criteria.setFirstName("   ");
            criteria.setLastName(null);
        });

        assertEquals(3, page.getTotalElements());
    }

    /** Confirms two populated filters combine with AND semantics, not OR semantics. */
    @Test
    @Transactional
    void shouldCombineMultipleFiltersWithAndSemantics() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> matching = search(criteria -> {
            criteria.setFirstName("Khoa");
            criteria.setNationality("Vietnam");
        });
        Page<GuestListResponse> nonMatching = search(criteria -> {
            criteria.setFirstName("Khoa");
            criteria.setNationality("Japan");
        });

        assertSingleMatch(matching, "G000001");
        assertEquals(
                0,
                nonMatching.getTotalElements(),
                "firstName=Khoa (matches G000001) AND nationality=Japan (matches G000002) "
                        + "must match neither Guest under AND semantics");
    }

    /** Confirms a filter matching no Guest returns an empty page rather than falling back to all Guests. */
    @Test
    @Transactional
    void shouldReturnNoResultsWhenFilterMatchesNoGuest() {
        UUID userId = insertUser();
        seedThreeGuests(userId);

        Page<GuestListResponse> page = search(criteria -> criteria.setNationality("Atlantis"));

        assertEquals(0, page.getTotalElements());
        assertTrue(page.getContent().isEmpty());
    }

    /** Confirms more than one page size worth of Guests paginates deterministically by guest code. */
    @Test
    @Transactional
    void shouldPaginateAcrossMultiplePagesWhenMoreThanTenGuestsExist() {
        UUID userId = insertUser();
        for (int i = 11; i >= 1; i--) {
            insertGuest(
                    userId,
                    String.format("PAGE%05d", i),
                    "First" + i,
                    "Last" + i,
                    "guest" + i + "@example.com",
                    "Vietnam");
        }

        Page<GuestListResponse> firstPage = guestQueryService.findPage(new GuestSearchCriteria(), 0);
        Page<GuestListResponse> secondPage = guestQueryService.findPage(new GuestSearchCriteria(), 1);

        assertEquals(11, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(10, firstPage.getContent().size());
        assertEquals(1, secondPage.getContent().size());
        assertEquals(
                List.of("PAGE00001", "PAGE00002", "PAGE00003", "PAGE00004", "PAGE00005",
                        "PAGE00006", "PAGE00007", "PAGE00008", "PAGE00009", "PAGE00010"),
                firstPage.getContent().stream().map(GuestListResponse::guestCode).toList());
        assertEquals("PAGE00011", secondPage.getContent().get(0).guestCode());
    }

    /** Runs a normalized Guest filter search through the production query service. */
    private Page<GuestListResponse> search(Consumer<GuestSearchCriteria> configurer) {
        GuestSearchCriteria criteria = new GuestSearchCriteria();
        configurer.accept(criteria);
        criteria.normalize();
        return guestQueryService.findPage(criteria, 0);
    }

    /** Asserts a search result contains exactly one Guest with the expected guest code. */
    private void assertSingleMatch(Page<GuestListResponse> page, String expectedGuestCode) {
        assertEquals(1, page.getTotalElements());
        assertEquals(expectedGuestCode, page.getContent().get(0).guestCode());
    }

    /** Inserts three distinguishable Guests covering every filterable field. */
    private void seedThreeGuests(UUID userId) {
        insertGuest(userId, "G000001", "Khoa", "Chu", "khoa.chu@example.com", "Vietnam");
        insertGuest(userId, "G000002", "Yuki", "Tanaka", "yuki.tanaka@example.jp", "Japan");
        insertGuest(userId, "G000003", "John", "Smith", "john.smith@example.us", "United States");
    }

    /** Inserts one authenticated audit user required by guest fixture rows. */
    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, password_hash, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, TRUE, ?, ?)",
                userId,
                "guest-search-" + userId,
                "not-used-in-test",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        return userId;
    }

    /** Inserts one Guest fixture row with every filterable field populated. */
    private void insertGuest(
            UUID userId,
            String guestCode,
            String firstName,
            String lastName,
            String email,
            String nationality) {
        jdbcTemplate.update(
                "INSERT INTO guest (id, guest_code, first_name, last_name, email, nationality, "
                        + "created_at, created_by, updated_at, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                guestCode,
                firstName,
                lastName,
                email,
                nationality,
                Timestamp.from(Instant.now()),
                userId,
                Timestamp.from(Instant.now()),
                userId);
    }
}
