package com.example.hotel.service.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.customer.request.GuestSearchCriteria;
import com.example.hotel.dto.customer.response.GuestListResponse;
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

    /** Confirms one normalized query creates OR predicates for every approved Guest search field. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldSearchAllApprovedGuestFieldsCaseInsensitively() {
        GuestRepository repository = mock(GuestRepository.class);
        GuestQueryService service = new GuestQueryService(repository, mock(GuestMapper.class));
        GuestSearchCriteria criteria = new GuestSearchCriteria();
        criteria.setQuery("  KhOa  ");
        criteria.normalizeQuery();

        Root<Guest> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<String> field = mock(Path.class);
        Expression<String> lowerCaseField = mock(Expression.class);
        Predicate predicate = mock(Predicate.class);
        when(root.<String>get(any(String.class))).thenReturn(field);
        when(criteriaBuilder.lower(any(Expression.class))).thenReturn(lowerCaseField);
        when(criteriaBuilder.like(lowerCaseField, "%khoa%")).thenReturn(predicate);
        when(criteriaBuilder.or(any(Predicate[].class))).thenReturn(predicate);

        service.specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        verify(root).get("guestCode");
        verify(root).get("firstName");
        verify(root).get("lastName");
        verify(root).get("email");
        verify(root).get("phone");
        verify(root).get("nationality");
        verify(criteriaBuilder).or(any(Predicate[].class));
    }

    /** Confirms blank input does not add a database search predicate. */
    @Test
    void shouldIgnoreBlankGuestSearchQuery() {
        GuestRepository repository = mock(GuestRepository.class);
        GuestQueryService service = new GuestQueryService(repository, mock(GuestMapper.class));
        GuestSearchCriteria criteria = new GuestSearchCriteria();
        criteria.setQuery("   ");
        criteria.normalizeQuery();
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Predicate conjunction = mock(Predicate.class);
        when(criteriaBuilder.conjunction()).thenReturn(conjunction);

        Predicate result = service.specificationFor(criteria)
                .toPredicate(mock(Root.class), mock(CriteriaQuery.class), criteriaBuilder);

        assertEquals(conjunction, result);
        verify(criteriaBuilder).conjunction();
    }
}
