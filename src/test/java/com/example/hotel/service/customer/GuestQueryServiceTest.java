package com.example.hotel.service.customer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.customer.request.GuestSearchCriteria;
import com.example.hotel.dto.customer.response.GuestListResponse;
import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.customer.GuestRepository;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Verifies database-backed Guest list pagination and nationality presentation configuration. */
class GuestQueryServiceTest {

    /** Confirms Reservation creation omits Guests with a completed Reservation. */
    @Test
    void shouldLoadOnlyGuestsWithoutCheckedOutReservationsForReservationCreation() {
        GuestRepository repository = mock(GuestRepository.class);
        GuestMapper mapper = mock(GuestMapper.class);
        Guest eligibleGuest = mock(Guest.class);
        when(repository.findAllWithoutReservationStatus(ReservationStatus.CHECKED_OUT))
                .thenReturn(List.of(eligibleGuest));

        new GuestQueryService(repository, mapper).findAllForReservationCreation();

        verify(repository).findAllWithoutReservationStatus(ReservationStatus.CHECKED_OUT);
        verify(repository, never()).findAll();
        verify(mapper).toLookupResponse(eligibleGuest);
    }

    /** Confirms Guest list queries use the approved fixed size and guest-code ordering. */
    @Test
    void shouldQueryTenGuestsWithGuestCodeOrdering() {
        GuestRepository repository = mock(GuestRepository.class);
        GuestMapper mapper = mock(GuestMapper.class);
        Guest guest = mock(Guest.class);
        GuestListResponse summary = new GuestListResponse(
                UUID.randomUUID(), "G000001", "Khoa", "Chu", "khoa@example.com", null);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(guest), Pageable.ofSize(10).withPage(1), 11));
        when(mapper.toListResponse(guest)).thenReturn(summary);

        var result = new GuestQueryService(repository, mapper)
                .findPage(new GuestSearchCriteria(), 1);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("guestCode").getDirection());
        assertEquals(11, result.getTotalElements());
        assertEquals(2, result.getTotalPages());
        assertEquals(List.of(summary), result.getContent());
    }

    /** Confirms an unfiltered criteria object adds no database predicate for any field. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldIgnoreAbsentFilters() {
        GuestRepository repository = mock(GuestRepository.class);
        GuestQueryService service = new GuestQueryService(repository, mock(GuestMapper.class));
        GuestSearchCriteria criteria = new GuestSearchCriteria();
        criteria.normalize();

        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Predicate conjunction = mock(Predicate.class);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria)
                .toPredicate(mock(Root.class), mock(CriteriaQuery.class), criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(0, predicatesCaptor.getValue().length);
    }

    /** Confirms one populated filter field builds exactly one case-insensitive partial-match predicate. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildPartialMatchPredicateForOnePopulatedField() {
        GuestRepository repository = mock(GuestRepository.class);
        GuestQueryService service = new GuestQueryService(repository, mock(GuestMapper.class));
        GuestSearchCriteria criteria = new GuestSearchCriteria();
        criteria.setFirstName("  KhOa  ");
        criteria.normalize();

        Root<Guest> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<String> field = mock(Path.class);
        Expression<String> lowerCaseField = mock(Expression.class);
        Predicate predicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<String>get("firstName")).thenReturn(field);
        when(criteriaBuilder.lower(field)).thenReturn(lowerCaseField);
        when(criteriaBuilder.like(lowerCaseField, "%khoa%")).thenReturn(predicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        verify(root).get("firstName");
        verify(root, never()).get("guestCode");
        verify(root, never()).get("lastName");
        verify(root, never()).get("email");
        verify(root, never()).get("nationality");
        assertArrayEquals(new Predicate[] {predicate}, predicatesCaptor.getValue());
    }

    /**
     * Confirms the nationality filter builds a case-insensitive OR predicate across the
     * canonical country name and its known legacy demonyms, not a free-text partial match.
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildCountrySelectionPredicateForNationalityFilter() {
        GuestRepository repository = mock(GuestRepository.class);
        GuestQueryService service = new GuestQueryService(repository, mock(GuestMapper.class));
        GuestSearchCriteria criteria = new GuestSearchCriteria();
        criteria.setNationality("Japan");
        criteria.normalize();

        Root<Guest> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<String> nationalityField = mock(Path.class);
        Expression<String> lowerNationality = mock(Expression.class);
        Predicate canonicalMatch = mock(Predicate.class);
        Predicate legacyMatch = mock(Predicate.class);
        Predicate orPredicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<String>get("nationality")).thenReturn(nationalityField);
        when(criteriaBuilder.lower(nationalityField)).thenReturn(lowerNationality);
        when(criteriaBuilder.equal(lowerNationality, "japan")).thenReturn(canonicalMatch);
        when(criteriaBuilder.equal(lowerNationality, "japanese")).thenReturn(legacyMatch);
        ArgumentCaptor<Predicate[]> orPredicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.or(orPredicatesCaptor.capture())).thenReturn(orPredicate);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(2, orPredicatesCaptor.getValue().length);
        assertTrue(List.of(orPredicatesCaptor.getValue()).containsAll(List.of(canonicalMatch, legacyMatch)));
        verify(root, never()).get("firstName");
    }

    /** Confirms multiple populated filter fields are AND-combined into one predicate array. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldCombineMultiplePopulatedFiltersWithAndSemantics() {
        GuestRepository repository = mock(GuestRepository.class);
        GuestQueryService service = new GuestQueryService(repository, mock(GuestMapper.class));
        GuestSearchCriteria criteria = new GuestSearchCriteria();
        criteria.setFirstName("Khoa");
        criteria.setNationality("Vietnam");
        criteria.normalize();

        Root<Guest> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<String> firstNameField = mock(Path.class);
        Path<String> nationalityField = mock(Path.class);
        Expression<String> lowerFirstName = mock(Expression.class);
        Expression<String> lowerNationality = mock(Expression.class);
        Predicate firstNamePredicate = mock(Predicate.class);
        Predicate nationalityOrPredicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<String>get("firstName")).thenReturn(firstNameField);
        when(root.<String>get("nationality")).thenReturn(nationalityField);
        when(criteriaBuilder.lower(firstNameField)).thenReturn(lowerFirstName);
        when(criteriaBuilder.lower(nationalityField)).thenReturn(lowerNationality);
        when(criteriaBuilder.like(lowerFirstName, "%khoa%")).thenReturn(firstNamePredicate);
        // Nationality is a country selection: "Vietnam" also matches the known legacy value "Vietnamese".
        when(criteriaBuilder.equal(eq(lowerNationality), any())).thenReturn(mock(Predicate.class));
        when(criteriaBuilder.or(any(Predicate[].class))).thenReturn(nationalityOrPredicate);
        ArgumentCaptor<Predicate[]> andPredicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(andPredicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(2, andPredicatesCaptor.getValue().length);
        assertTrue(List.of(andPredicatesCaptor.getValue()).containsAll(
                List.of(firstNamePredicate, nationalityOrPredicate)));
        verify(criteriaBuilder).equal(lowerNationality, "vietnam");
        verify(criteriaBuilder).equal(lowerNationality, "vietnamese");
    }
}
