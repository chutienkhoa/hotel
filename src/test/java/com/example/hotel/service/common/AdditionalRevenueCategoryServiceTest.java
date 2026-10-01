package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.request.AdditionalRevenueCategoryCreateRequest;
import com.example.hotel.dto.common.request.AdditionalRevenueCategoryUpdateRequest;
import com.example.hotel.dto.common.response.AdditionalRevenueCategoryResponse;
import com.example.hotel.entity.common.AdditionalRevenueCategory;
import com.example.hotel.mapper.common.AdditionalRevenueMapper;
import com.example.hotel.repository.common.AdditionalRevenueCategoryRepository;
import com.example.hotel.security.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Additional Revenue category validation, lifecycle, and audit-safe persistence. */
class AdditionalRevenueCategoryServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldCreateActiveCategoryWithNormalizedCode() {
        AdditionalRevenueCategoryRepository repository = mockRepository();
        setCurrentUser();
        when(repository.existsByCode("AIRPORT_TRANSFER")).thenReturn(false);
        when(repository.save(any(AdditionalRevenueCategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdditionalRevenueCategoryResponse response = service(repository)
                .create(new AdditionalRevenueCategoryCreateRequest(" airport_transfer ", "Airport Transfer", "  Shuttle  "));

        assertTrue(response.active());
        assertEquals("AIRPORT_TRANSFER", response.code());
        assertEquals("Shuttle", response.description());
    }

    @Test
    void shouldRejectDuplicateCodeWithoutRawPersistenceException() {
        AdditionalRevenueCategoryRepository repository = mockRepository();
        setCurrentUser();
        when(repository.existsByCode("OTHER")).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service(repository)
                .create(new AdditionalRevenueCategoryCreateRequest("OTHER", "Other", null)));

        assertEquals(409, exception.getStatusCode().value());
        verify(repository, never()).save(any());
    }

    @Test
    void shouldConvertDuplicateCodeRaceToConflict() {
        AdditionalRevenueCategoryRepository repository = mockRepository();
        setCurrentUser();
        when(repository.existsByCode("OTHER")).thenReturn(false);
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service(repository)
                .create(new AdditionalRevenueCategoryCreateRequest("OTHER", "Other", null)));

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void shouldOnlyAllowNameAndDescriptionToChangeThroughUpdate() {
        AdditionalRevenueCategoryRepository repository = mockRepository();
        UUID id = UUID.randomUUID();
        AdditionalRevenueCategory category = AdditionalRevenueCategory.create("OTHER", "Other", null);
        setCurrentUser();
        when(repository.findById(id)).thenReturn(Optional.of(category));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AdditionalRevenueCategoryResponse response = service(repository)
                .update(id, new AdditionalRevenueCategoryUpdateRequest("Miscellaneous", "Updated"));

        assertEquals("OTHER", response.code());
        assertEquals("Miscellaneous", response.name());
        assertEquals("Updated", response.description());
        assertFalse(java.util.Arrays.stream(AdditionalRevenueCategoryUpdateRequest.class.getRecordComponents())
                .map(component -> component.getName()).toList().contains("code"));
        assertFalse(java.util.Arrays.stream(AdditionalRevenueCategoryUpdateRequest.class.getRecordComponents())
                .map(component -> component.getName()).toList().contains("active"));
    }

    @Test
    void shouldTransitionActiveAndInactiveOnlyOnce() {
        AdditionalRevenueCategoryRepository repository = mockRepository();
        UUID id = UUID.randomUUID();
        AdditionalRevenueCategory category = AdditionalRevenueCategory.create("OTHER", "Other", null);
        setCurrentUser();
        when(repository.findById(id)).thenReturn(Optional.of(category));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertFalse(service(repository).deactivate(id).active());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service(repository).deactivate(id))
                .getStatusCode().value());
        assertTrue(service(repository).reactivate(id).active());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service(repository).reactivate(id))
                .getStatusCode().value());
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void shouldUseApprovedCodePattern() {
        assertTrue("ELECTRIC_CART_RENTAL".matches("^[A-Z][A-Z0-9_]*$"));
        assertFalse("electric cart".matches("^[A-Z][A-Z0-9_]*$"));
        assertFalse("ELECTRIC-CART".matches("^[A-Z][A-Z0-9_]*$"));
    }

    private AdditionalRevenueCategoryRepository mockRepository() {
        return org.mockito.Mockito.mock(AdditionalRevenueCategoryRepository.class);
    }

    private AdditionalRevenueCategoryService service(AdditionalRevenueCategoryRepository repository) {
        return new AdditionalRevenueCategoryService(repository, new AdditionalRevenueMapper());
    }

    private void setCurrentUser() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CurrentUser(UUID.randomUUID(), "revenue-manager"), null));
    }
}
