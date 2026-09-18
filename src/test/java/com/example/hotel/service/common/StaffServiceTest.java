package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.request.StaffCreateRequest;
import com.example.hotel.dto.common.request.StaffSearchCriteria;
import com.example.hotel.dto.common.request.StaffUpdateRequest;
import com.example.hotel.dto.common.response.StaffResponse;
import com.example.hotel.entity.common.Staff;
import com.example.hotel.mapper.common.StaffMapper;
import com.example.hotel.repository.common.StaffRepository;
import com.example.hotel.security.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Staff Management create/edit/deactivate/reactivate/search behavior and audit attribution. */
class StaffServiceTest {

    /** Clears the authenticated user established by each test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms a created Staff member is active with a backend-generated, formatted Staff Code. */
    @Test
    void shouldCreateActiveStaffWithBackendGeneratedCode() {
        StaffRepository repository = mock(StaffRepository.class);
        setCurrentUser(UUID.randomUUID());
        when(repository.nextStaffCodeSequence()).thenReturn(1L);
        when(repository.existsByStaffCode("STF-000001")).thenReturn(false);
        when(repository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffResponse response = service(repository).create(
                new StaffCreateRequest("Nguyen", "Van A", "0900000001", "a@example.test", "Receptionist",
                        LocalDate.of(2026, 1, 1), "Notes"));

        assertTrue(response.active());
        assertEquals("STF-000001", response.staffCode());
        assertEquals("Nguyen", response.firstName());
        assertEquals("Receptionist", response.position());
    }

    /** Confirms Staff Code generation retries past an already-used sequence value. */
    @Test
    void shouldSkipAnExistingStaffCodeWhenGenerating() {
        StaffRepository repository = mock(StaffRepository.class);
        setCurrentUser(UUID.randomUUID());
        when(repository.nextStaffCodeSequence()).thenReturn(1L, 2L);
        when(repository.existsByStaffCode("STF-000001")).thenReturn(true);
        when(repository.existsByStaffCode("STF-000002")).thenReturn(false);
        when(repository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffResponse response = service(repository).create(
                new StaffCreateRequest("Tran", "Thi B", null, null, null, LocalDate.of(2026, 1, 1), null));

        assertEquals("STF-000002", response.staffCode());
    }

    /** Confirms a race-condition duplicate-code database violation is converted to a clean conflict. */
    @Test
    void shouldConvertDuplicateCodeDatabaseViolationToConflict() {
        StaffRepository repository = mock(StaffRepository.class);
        setCurrentUser(UUID.randomUUID());
        when(repository.nextStaffCodeSequence()).thenReturn(1L);
        when(repository.existsByStaffCode("STF-000001")).thenReturn(false);
        when(repository.save(any(Staff.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service(repository)
                .create(new StaffCreateRequest("Le", "Van C", null, null, null, LocalDate.of(2026, 1, 1), null)));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms optional blank fields are stored as null rather than empty strings. */
    @Test
    void shouldNormalizeBlankOptionalFieldsToNull() {
        StaffRepository repository = mock(StaffRepository.class);
        setCurrentUser(UUID.randomUUID());
        when(repository.nextStaffCodeSequence()).thenReturn(1L);
        when(repository.existsByStaffCode("STF-000001")).thenReturn(false);
        when(repository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffResponse response = service(repository).create(
                new StaffCreateRequest("Pham", "Van D", "   ", "   ", "   ", LocalDate.of(2026, 1, 1), "   "));

        assertEquals(null, response.phone());
        assertEquals(null, response.email());
        assertEquals(null, response.position());
        assertEquals(null, response.notes());
    }

    /** Confirms the update request DTO never accepts a Staff Code or active field. */
    @Test
    void shouldNotExposeStaffCodeOrActiveOnUpdateRequest() {
        var components = java.util.Arrays.stream(StaffUpdateRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertFalse(components.contains("staffCode"));
        assertFalse(components.contains("active"));
    }

    /** Confirms the create request DTO never accepts a Staff Code, since it is backend-generated. */
    @Test
    void shouldNotExposeStaffCodeOnCreateRequest() {
        var components = java.util.Arrays.stream(StaffCreateRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertFalse(components.contains("staffCode"));
    }

    /** Confirms an existing Staff member's editable profile fields can change via update. */
    @Test
    void shouldUpdateEditableProfileFields() {
        StaffRepository repository = mock(StaffRepository.class);
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.create(
                staffId, "STF-000001", "Nguyen", "Van A", null, null, null, LocalDate.of(2026, 1, 1), null);
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(staffId)).thenReturn(Optional.of(staff));
        when(repository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffResponse response = service(repository).update(
                staffId,
                new StaffUpdateRequest("Nguyen", "Van A", "0911111111", "updated@example.test", "Manager",
                        LocalDate.of(2026, 2, 1), "Promoted"));

        assertEquals("0911111111", response.phone());
        assertEquals("updated@example.test", response.email());
        assertEquals("Manager", response.position());
        assertEquals(LocalDate.of(2026, 2, 1), response.startDate());
        assertEquals("Promoted", response.notes());
    }

    /** Confirms update never changes the Staff Code, even though it is not part of the update request. */
    @Test
    void shouldKeepStaffCodeImmutableAcrossUpdate() {
        StaffRepository repository = mock(StaffRepository.class);
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.create(
                staffId, "STF-000042", "Nguyen", "Van A", null, null, null, LocalDate.of(2026, 1, 1), null);
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(staffId)).thenReturn(Optional.of(staff));
        when(repository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffResponse response = service(repository).update(
                staffId,
                new StaffUpdateRequest("Nguyen", "Thi A", null, null, null, LocalDate.of(2026, 1, 1), null));

        assertEquals("STF-000042", response.staffCode());
    }

    /** Confirms an active Staff member can be deactivated. */
    @Test
    void shouldDeactivateActiveStaff() {
        StaffRepository repository = mock(StaffRepository.class);
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.create(
                staffId, "STF-000001", "Nguyen", "Van A", null, null, null, LocalDate.of(2026, 1, 1), null);
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(staffId)).thenReturn(Optional.of(staff));
        when(repository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffResponse response = service(repository).deactivate(staffId);

        assertFalse(response.active());
    }

    /** Confirms an inactive Staff member can be reactivated. */
    @Test
    void shouldReactivateInactiveStaff() {
        StaffRepository repository = mock(StaffRepository.class);
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.create(
                staffId, "STF-000001", "Nguyen", "Van A", null, null, null, LocalDate.of(2026, 1, 1), null);
        staff.deactivate();
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(staffId)).thenReturn(Optional.of(staff));
        when(repository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StaffResponse response = service(repository).reactivate(staffId);

        assertTrue(response.active());
    }

    /** Confirms deactivating an already-inactive Staff member is rejected as an invalid transition. */
    @Test
    void shouldRejectDeactivatingAlreadyInactiveStaff() {
        StaffRepository repository = mock(StaffRepository.class);
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.create(
                staffId, "STF-000001", "Nguyen", "Van A", null, null, null, LocalDate.of(2026, 1, 1), null);
        staff.deactivate();
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(staffId)).thenReturn(Optional.of(staff));

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service(repository).deactivate(staffId));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms reactivating an already-active Staff member is rejected as an invalid transition. */
    @Test
    void shouldRejectReactivatingAlreadyActiveStaff() {
        StaffRepository repository = mock(StaffRepository.class);
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.create(
                staffId, "STF-000001", "Nguyen", "Van A", null, null, null, LocalDate.of(2026, 1, 1), null);
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(staffId)).thenReturn(Optional.of(staff));

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> service(repository).reactivate(staffId));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms deactivation never deletes the Staff row: the repository is never asked to delete anything. */
    @Test
    void shouldNotDeleteStaffOnDeactivate() {
        StaffRepository repository = mock(StaffRepository.class);
        UUID staffId = UUID.randomUUID();
        Staff staff = Staff.create(
                staffId, "STF-000001", "Nguyen", "Van A", null, null, null, LocalDate.of(2026, 1, 1), null);
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(staffId)).thenReturn(Optional.of(staff));
        when(repository.save(any(Staff.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(repository).deactivate(staffId);

        verify(repository, never()).delete(any(Staff.class));
        verify(repository, never()).deleteById(any());
    }

    /** Confirms search delegates to the repository's Specification-based query with stable Staff Code order. */
    @Test
    void shouldSearchUsingSpecificationInStaffCodeOrder() {
        StaffRepository repository = mock(StaffRepository.class);
        Staff staff = Staff.create(
                UUID.randomUUID(), "STF-000001", "Nguyen", "Van A", null, null, null, LocalDate.of(2026, 1, 1), null);
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<Staff>>any(), any(Sort.class)))
                .thenReturn(List.of(staff));
        StaffSearchCriteria criteria = new StaffSearchCriteria();

        List<StaffResponse> results = service(repository).search(criteria);

        assertEquals(1, results.size());
        assertEquals("STF-000001", results.get(0).staffCode());
        verify(repository).findAll(
                org.mockito.ArgumentMatchers.<Specification<Staff>>any(),
                org.mockito.ArgumentMatchers.eq(Sort.by(Sort.Order.asc("staffCode"))));
    }

    /** Confirms the status filter helpers on the search criteria classify ALL/ACTIVE/INACTIVE correctly. */
    @Test
    void shouldClassifyStatusFilterCorrectly() {
        StaffSearchCriteria all = new StaffSearchCriteria();
        all.setStatus("ALL");
        assertFalse(all.hasStatusFilter());

        StaffSearchCriteria active = new StaffSearchCriteria();
        active.setStatus("ACTIVE");
        assertTrue(active.hasStatusFilter());
        assertTrue(active.isActiveFilter());

        StaffSearchCriteria inactive = new StaffSearchCriteria();
        inactive.setStatus("INACTIVE");
        assertTrue(inactive.hasStatusFilter());
        assertFalse(inactive.isActiveFilter());

        StaffSearchCriteria unset = new StaffSearchCriteria();
        assertFalse(unset.hasStatusFilter());
    }

    /** Confirms the free-text query is trimmed and blank input normalizes to an absent filter. */
    @Test
    void shouldNormalizeBlankQueryToAbsentFilter() {
        StaffSearchCriteria criteria = new StaffSearchCriteria();
        criteria.setQuery("   ");

        criteria.normalize();

        assertEquals(null, criteria.getQuery());
    }

    /** Establishes the authenticated application user used for audit attribution. */
    private void setCurrentUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(userId, "staff-manager"), null));
    }

    /** Creates the Staff service under test with a mocked repository. */
    private StaffService service(StaffRepository repository) {
        return new StaffService(repository, new StaffMapper());
    }
}
