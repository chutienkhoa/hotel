package com.example.hotel.service.customer;

import com.example.hotel.dto.customer.request.GuestSearchCriteria;
import com.example.hotel.dto.customer.response.CountryCatalog;
import com.example.hotel.dto.customer.response.GuestLookupResponse;
import com.example.hotel.dto.customer.response.GuestListResponse;
import com.example.hotel.entity.customer.Guest;
import com.example.hotel.mapper.customer.GuestMapper;
import com.example.hotel.repository.customer.GuestRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
 * Provides read-only Guest lookup and database-backed list data for MVC presentation.
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
     * Retrieves one database-backed page of Guests matching every supplied optional filter.
     *
     * @param criteria normalized optional Guest list filters
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
     * Builds the case-insensitive database predicate combining every supplied Guest filter.
     *
     * <p>Each populated filter field contributes its own partial-match predicate on its own
     * database column; populated filters are combined with AND semantics, so a Guest must
     * match every supplied filter to appear in the result.</p>
     *
     * @param criteria normalized optional Guest list filters
     * @return the database specification for matching Guest list rows
     */
    Specification<Guest> specificationFor(GuestSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            var predicates = new ArrayList<Predicate>();
            addPartialMatch(predicates, criteriaBuilder, root, "guestCode", criteria.getGuestCode());
            addPartialMatch(predicates, criteriaBuilder, root, "firstName", criteria.getFirstName());
            addPartialMatch(predicates, criteriaBuilder, root, "lastName", criteria.getLastName());
            addPartialMatch(predicates, criteriaBuilder, root, "email", criteria.getEmail());
            addNationalityMatch(predicates, criteriaBuilder, root, criteria.getNationality());
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Adds a case-insensitive partial-match predicate for one field when a filter was supplied.
     *
     * @param predicates predicate list accumulating the AND-combined Guest filters
     * @param criteriaBuilder criteria builder used to construct the predicate
     * @param root Guest query root
     * @param fieldName entity field name to filter on
     * @param filterValue normalized optional filter fragment for the field
     */
    private void addPartialMatch(
            List<Predicate> predicates,
            CriteriaBuilder criteriaBuilder,
            Root<Guest> root,
            String fieldName,
            String filterValue) {
        if (filterValue == null) {
            return;
        }
        String pattern = "%" + filterValue.toLowerCase(Locale.ROOT) + "%";
        predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get(fieldName)), pattern));
    }

    /**
     * Adds a country-selection predicate for the Guest nationality filter when a country was
     * selected.
     *
     * <p>Nationality filtering is a country selection, not a free-text partial match: the
     * selected canonical country name is matched case-insensitively against the stored
     * nationality, along with every known legacy demonym for that country (e.g. selecting
     * "Japan" also matches a stored value of "Japanese"), so historical free-text Guest data
     * continues to match the country the user actually selected.</p>
     *
     * @param predicates predicate list accumulating the AND-combined Guest filters
     * @param criteriaBuilder criteria builder used to construct the predicate
     * @param root Guest query root
     * @param canonicalCountryName normalized optional canonical country name selected by the user
     */
    private void addNationalityMatch(
            List<Predicate> predicates,
            CriteriaBuilder criteriaBuilder,
            Root<Guest> root,
            String canonicalCountryName) {
        if (canonicalCountryName == null) {
            return;
        }
        var lowerNationality = criteriaBuilder.lower(root.get("nationality"));
        List<Predicate> acceptableValueMatches = CountryCatalog.acceptableStoredValues(canonicalCountryName).stream()
                .map(value -> criteriaBuilder.equal(lowerNationality, value.toLowerCase(Locale.ROOT)))
                .toList();
        predicates.add(criteriaBuilder.or(acceptableValueMatches.toArray(new Predicate[0])));
    }
}
