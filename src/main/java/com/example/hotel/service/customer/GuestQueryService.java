package com.example.hotel.service.customer;

import com.example.hotel.dto.customer.request.GuestSearchCriteria;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.customer.response.GuestListResponse;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.customer.GuestRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides read-only guest lookup data for reservation creation.
 */
@Service
public class GuestQueryService {

    private static final int GUEST_PAGE_SIZE = 10;

    private final GuestRepository guestRepository;
    private final GuestMapper guestMapper;

    /**
     * Creates the query service with the repository used to load guests.
     *
     * @param guestRepository repository used to load guests
     * @param guestMapper mapper used to prepare compact Guest list data
     */
    public GuestQueryService(GuestRepository guestRepository, GuestMapper guestMapper) {
        this.guestRepository = guestRepository;
        this.guestMapper = guestMapper;
    }

    /**
     * Retrieves the guest identifiers and codes required by the reservation form.
     *
     * @return the guest lookup entries
     */
    @Transactional(readOnly = true)
    public List<GuestLookupResponse> findAllForReservationCreation() {
        return guestRepository.findAll().stream()
                .map(guest -> new GuestLookupResponse(guest.getId(), guest.getGuestCode()))
                .toList();
    }

    /**
     * Retrieves one database-backed page of Guests matching the supplied optional search query.
     *
     * @param criteria normalized optional Guest search criteria
     * @param page zero-based requested page number
     * @return a page of compact Guest list representations
     */
    @Transactional(readOnly = true)
    public Page<GuestListResponse> findPage(GuestSearchCriteria criteria, int page) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                GUEST_PAGE_SIZE,
                Sort.by(Sort.Order.asc("guestCode")));
        return guestRepository.findAll(specificationFor(criteria), pageable)
                .map(guestMapper::toListResponse);
    }

    /**
     * Builds the case-insensitive database predicate for the single Guest search field.
     *
     * @param criteria normalized optional Guest search criteria
     * @return the database specification for matching Guest list rows
     */
    Specification<Guest> specificationFor(GuestSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            if (criteria.getQuery() == null) {
                return criteriaBuilder.conjunction();
            }

            String pattern = "%" + criteria.getQuery().toLowerCase(Locale.ROOT) + "%";
            var predicates = new ArrayList<Predicate>();
            predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("guestCode")), pattern));
            predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("firstName")), pattern));
            predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("lastName")), pattern));
            predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), pattern));
            predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("phone")), pattern));
            predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("nationality")), pattern));
            return criteriaBuilder.or(predicates.toArray(new Predicate[0]));
        };
    }
}
