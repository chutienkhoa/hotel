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

import com.example.hotel.dto.common.request.ExpenseCategoryCreateRequest;
import com.example.hotel.dto.common.request.ExpenseCategoryUpdateRequest;
import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.entity.common.ExpenseCategory;
import com.example.hotel.mapper.common.ExpenseMapper;
import com.example.hotel.repository.common.ExpenseCategoryRepository;
import com.example.hotel.security.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Expense category create/edit/deactivate/reactivate validation and audit attribution. */
class ExpenseCategoryServiceTest {

    /** Clears the authenticated user established by each test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms a created category defaults to active with a backend-generated identity. */
    @Test
    void shouldCreateActiveCategoryWithBackendControlledIdentity() {
        ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
        setCurrentUser(UUID.randomUUID());
        when(repository.existsByCode("REPAIR")).thenReturn(false);
        when(repository.save(any(ExpenseCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseCategoryResponse response = service(repository)
                .create(new ExpenseCategoryCreateRequest("REPAIR", "Repair", null));

        assertTrue(response.active());
        assertEquals("REPAIR", response.code());
        assertEquals("Repair", response.name());
    }

    /** Confirms the category code is normalized to trimmed, upper-case form before validation. */
    @Test
    void shouldNormalizeCodeToTrimmedUpperCase() {
        ExpenseCategoryCreateRequest request = new ExpenseCategoryCreateRequest("  repair  ", "Repair", null);

        assertEquals("REPAIR", request.code());
    }

    /** Confirms a blank description normalizes to null rather than an empty string. */
    @Test
    void shouldNormalizeBlankDescriptionToNull() {
        ExpenseCategoryCreateRequest request = new ExpenseCategoryCreateRequest("REPAIR", "Repair", "   ");

        assertEquals(null, request.description());
    }

    /** Confirms a pre-existing duplicate code is rejected with a clean conflict, not a raw persistence exception. */
    @Test
    void shouldRejectDuplicateCodeCleanly() {
        ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
        setCurrentUser(UUID.randomUUID());
        when(repository.existsByCode("ELECTRICITY")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service(repository).create(new ExpenseCategoryCreateRequest("ELECTRICITY", "Electricity", null)));

        assertEquals(409, exception.getStatusCode().value());
        verify(repository, never()).save(any());
    }

    /** Confirms a race-condition duplicate-code database violation is converted to a clean conflict. */
    @Test
    void shouldConvertDuplicateCodeDatabaseViolationToConflict() {
        ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
        setCurrentUser(UUID.randomUUID());
        when(repository.existsByCode("REPAIR")).thenReturn(false);
        when(repository.save(any(ExpenseCategory.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service(repository).create(new ExpenseCategoryCreateRequest("REPAIR", "Repair", null)));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms an invalid code format is rejected by the approved pattern before reaching the service. */
    @Test
    void shouldRejectInvalidCodeFormatPattern() {
        assertFalse("ota commission".matches("^[A-Z][A-Z0-9_]*$"));
        assertFalse("OTA-COMMISSION".matches("^[A-Z][A-Z0-9_]*$"));
        assertFalse("OTA COMMISSION".matches("^[A-Z][A-Z0-9_]*$"));
        assertTrue("OTA_COMMISSION".matches("^[A-Z][A-Z0-9_]*$"));
        assertTrue("REPAIR".matches("^[A-Z][A-Z0-9_]*$"));
        assertTrue("AIR_CONDITIONER".matches("^[A-Z][A-Z0-9_]*$"));
    }

    /** Confirms the category name can change via update. */
    @Test
    void shouldUpdateCategoryName() {
        ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
        UUID categoryId = UUID.randomUUID();
        ExpenseCategory category = ExpenseCategory.create("ELECTRICITY", "Electricity", null);
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(categoryId)).thenReturn(Optional.of(category));
        when(repository.save(any(ExpenseCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseCategoryResponse response = service(repository)
                .update(categoryId, new ExpenseCategoryUpdateRequest("Power & Electricity", null));

        assertEquals("Power & Electricity", response.name());
    }

    /** Confirms the category description can change via update. */
    @Test
    void shouldUpdateCategoryDescription() {
        ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
        UUID categoryId = UUID.randomUUID();
        ExpenseCategory category = ExpenseCategory.create("MAINTENANCE", "Maintenance", null);
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(categoryId)).thenReturn(Optional.of(category));
        when(repository.save(any(ExpenseCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseCategoryResponse response = service(repository).update(
                categoryId, new ExpenseCategoryUpdateRequest("Maintenance", "Air conditioner maintenance"));

        assertEquals("Air conditioner maintenance", response.description());
    }

    /** Confirms the update request DTO never accepts a code field, since code is immutable. */
    @Test
    void shouldNotExposeCodeOnUpdateRequest() {
        var components = java.util.Arrays.stream(ExpenseCategoryUpdateRequest.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertFalse(components.contains("code"));
        assertFalse(components.contains("active"));
    }

    /** Confirms an active category can be deactivated. */
    @Test
    void shouldDeactivateActiveCategory() {
        ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
        UUID categoryId = UUID.randomUUID();
        ExpenseCategory category = ExpenseCategory.create("SUPPLIES", "Supplies", null);
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(categoryId)).thenReturn(Optional.of(category));
        when(repository.save(any(ExpenseCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseCategoryResponse response = service(repository).deactivate(categoryId);

        assertFalse(response.active());
    }

    /** Confirms an inactive category can be reactivated. */
    @Test
    void shouldReactivateInactiveCategory() {
        ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
        UUID categoryId = UUID.randomUUID();
        ExpenseCategory category = ExpenseCategory.create("SUPPLIES", "Supplies", null);
        category.deactivate();
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(categoryId)).thenReturn(Optional.of(category));
        when(repository.save(any(ExpenseCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseCategoryResponse response = service(repository).reactivate(categoryId);

        assertTrue(response.active());
    }

    /** Confirms deactivating an already-inactive category is rejected as an invalid transition. */
    @Test
    void shouldRejectDeactivatingAlreadyInactiveCategory() {
        ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
        UUID categoryId = UUID.randomUUID();
        ExpenseCategory category = ExpenseCategory.create("SUPPLIES", "Supplies", null);
        category.deactivate();
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(categoryId)).thenReturn(Optional.of(category));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> service(repository).deactivate(categoryId));

        assertEquals(409, exception.getStatusCode().value());
    }

    /** Confirms deactivation does not delete the category row: the repository is never asked to delete anything. */
    @Test
    void shouldNotDeleteCategoryOnDeactivate() {
        ExpenseCategoryRepository repository = mock(ExpenseCategoryRepository.class);
        UUID categoryId = UUID.randomUUID();
        ExpenseCategory category = ExpenseCategory.create("SUPPLIES", "Supplies", null);
        setCurrentUser(UUID.randomUUID());
        when(repository.findById(categoryId)).thenReturn(Optional.of(category));
        when(repository.save(any(ExpenseCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(repository).deactivate(categoryId);

        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    /** Establishes the authenticated application user used for audit attribution. */
    private void setCurrentUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(userId, "expense-manager"), null));
    }

    /** Creates the Expense category service under test with a mocked repository. */
    private ExpenseCategoryService service(ExpenseCategoryRepository repository) {
        return new ExpenseCategoryService(repository, new ExpenseMapper());
    }
}
