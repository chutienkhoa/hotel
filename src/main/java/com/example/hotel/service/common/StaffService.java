package com.example.hotel.service.common;

import com.example.hotel.dto.common.request.StaffCreateRequest;
import com.example.hotel.dto.common.request.StaffSearchCriteria;
import com.example.hotel.dto.common.request.StaffUpdateRequest;
import com.example.hotel.dto.common.response.StaffResponse;
import com.example.hotel.entity.common.Staff;
import com.example.hotel.mapper.common.StaffMapper;
import com.example.hotel.repository.common.StaffRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Manages Staff Management profile data: create, edit, deactivate, reactivate, and search. */
@Service
public class StaffService {

    private static final String STAFF_CODE_FORMAT = "STF-%06d";

    private final StaffRepository staffRepository;
    private final StaffMapper staffMapper;

    /**
     * Creates the Staff service with its persistence and mapping collaborators.
     *
     * @param staffRepository repository used to persist Staff members
     * @param staffMapper mapper used to produce client-safe Staff responses
     */
    public StaffService(StaffRepository staffRepository, StaffMapper staffMapper) {
        this.staffRepository = staffRepository;
        this.staffMapper = staffMapper;
    }

    /**
     * Searches Staff members matching the supplied optional free-text and status filters, in
     * stable Staff Code order.
     *
     * @param criteria normalized optional Staff list filters
     * @return matching Staff responses
     */
    @Transactional(readOnly = true)
    public List<StaffResponse> search(StaffSearchCriteria criteria) {
        return staffRepository.findAll(specificationFor(criteria), Sort.by(Sort.Order.asc("staffCode"))).stream()
                .map(staffMapper::toResponse)
                .toList();
    }

    /**
     * Finds one Staff member by its technical identifier.
     *
     * @param id Staff identifier
     * @return client-safe Staff response
     * @throws ResponseStatusException if no Staff member exists for the identifier
     */
    @Transactional(readOnly = true)
    public StaffResponse findById(UUID id) {
        return staffMapper.toResponse(findStaff(id));
    }

    /**
     * Creates a new active Staff member with a sequence-backed, immutable Staff Code.
     *
     * @param request client-supplied mutable Staff profile data
     * @return the persisted Staff response
     */
    @Transactional
    public StaffResponse create(StaffCreateRequest request) {
        Staff staff = Staff.create(
                UUID.randomUUID(),
                nextAvailableStaffCode(),
                request.firstName().trim(),
                request.lastName().trim(),
                blankToNull(request.phone()),
                blankToNull(request.email()),
                blankToNull(request.position()),
                request.startDate(),
                blankToNull(request.notes()));
        staff.audit(currentUser().id());
        return staffMapper.toResponse(saveGuardingDuplicateCode(staff));
    }

    /**
     * Updates the editable profile fields of an existing Staff member. The Staff Code and active
     * state are never changed here.
     *
     * @param id Staff identifier
     * @param request client-supplied replacement profile fields
     * @return the updated Staff response
     * @throws ResponseStatusException if no Staff member exists for the identifier
     */
    @Transactional
    public StaffResponse update(UUID id, StaffUpdateRequest request) {
        Staff staff = findStaff(id);
        staff.updateProfile(
                request.firstName().trim(),
                request.lastName().trim(),
                blankToNull(request.phone()),
                blankToNull(request.email()),
                blankToNull(request.position()),
                request.startDate(),
                blankToNull(request.notes()));
        staff.audit(currentUser().id());
        return staffMapper.toResponse(staffRepository.save(staff));
    }

    /**
     * Deactivates an active Staff member so they no longer receive new Daily Work Record entries.
     *
     * @param id Staff identifier
     * @return the deactivated Staff response
     * @throws ResponseStatusException if no Staff member exists, or is already inactive
     */
    @Transactional
    public StaffResponse deactivate(UUID id) {
        return transition(id, Staff::deactivate);
    }

    /**
     * Reactivates an inactive Staff member so they become eligible for Daily Work Record entry
     * again.
     *
     * @param id Staff identifier
     * @return the reactivated Staff response
     * @throws ResponseStatusException if no Staff member exists, or is already active
     */
    @Transactional
    public StaffResponse reactivate(UUID id) {
        return transition(id, Staff::reactivate);
    }

    /**
     * Applies one approved active-state transition.
     *
     * @param id Staff identifier
     * @param operation approved explicit domain operation
     * @return the transitioned Staff response
     */
    private StaffResponse transition(UUID id, Consumer<Staff> operation) {
        Staff staff = findStaff(id);
        try {
            operation.accept(staff);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        staff.audit(currentUser().id());
        return staffMapper.toResponse(staffRepository.save(staff));
    }

    /**
     * Builds the case-insensitive database predicate combining the Staff free-text search and
     * status filters.
     *
     * @param criteria normalized optional Staff list filters
     * @return the database specification for matching Staff list rows
     */
    private Specification<Staff> specificationFor(StaffSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            var predicates = new ArrayList<Predicate>();
            addQueryMatch(predicates, criteriaBuilder, root, criteria.getQuery());
            if (criteria.hasStatusFilter()) {
                predicates.add(criteriaBuilder.equal(root.get("active"), criteria.isActiveFilter()));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Adds an OR-combined case-insensitive partial-match predicate across every searchable Staff
     * field, when a free-text query was supplied.
     *
     * @param predicates predicate list accumulating the AND-combined Staff filters
     * @param criteriaBuilder criteria builder used to construct the predicate
     * @param root Staff query root
     * @param query normalized optional free-text search fragment
     */
    private void addQueryMatch(
            List<Predicate> predicates, CriteriaBuilder criteriaBuilder, Root<Staff> root, String query) {
        if (query == null) {
            return;
        }
        String pattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
        predicates.add(criteriaBuilder.or(
                criteriaBuilder.like(criteriaBuilder.lower(root.get("staffCode")), pattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("firstName")), pattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("lastName")), pattern),
                criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.concat(
                        criteriaBuilder.concat(criteriaBuilder.coalesce(root.get("firstName"), ""), " "),
                        criteriaBuilder.coalesce(root.get("lastName"), ""))), pattern),
                criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("phone"), "")), pattern),
                criteriaBuilder.like(criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("email"), "")), pattern),
                criteriaBuilder.like(
                        criteriaBuilder.lower(criteriaBuilder.coalesce(root.get("position"), "")), pattern)));
    }

    /**
     * Allocates a unique Staff Code from the database sequence without using a maximum-value
     * query.
     *
     * @return an unused Staff Code with the approved {@code STF-000001} format
     */
    private String nextAvailableStaffCode() {
        String staffCode;
        do {
            staffCode = STAFF_CODE_FORMAT.formatted(staffRepository.nextStaffCodeSequence());
        } while (staffRepository.existsByStaffCode(staffCode));
        return staffCode;
    }

    /**
     * Saves a new Staff member, converting a race-condition duplicate-code database violation
     * into a user-safe conflict rather than letting a raw persistence exception surface.
     *
     * @param staff new Staff member to persist
     * @return the persisted Staff member
     * @throws ResponseStatusException if the database rejects the row as a duplicate code
     */
    private Staff saveGuardingDuplicateCode(Staff staff) {
        try {
            return staffRepository.save(staff);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Staff Code already exists");
        }
    }

    /**
     * Loads an existing Staff member or produces the standard not-found response.
     *
     * @param id Staff identifier
     * @return the persisted Staff entity
     * @throws ResponseStatusException if no Staff member exists for the identifier
     */
    private Staff findStaff(UUID id) {
        return staffRepository.findById(id).orElseThrow(() -> notFound("Staff member"));
    }

    /**
     * Converts a blank optional form value to {@code null} so it is stored consistently.
     *
     * @param value raw optional submitted value
     * @return the trimmed value, or {@code null} when absent or blank
     */
    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Resolves the current JWT or session principal to the audit identity used by persisted
     * entities.
     *
     * @return the authenticated application user
     * @throws ResponseStatusException if the active principal is not an application user
     */
    private CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser;
        }
        if (principal instanceof SessionUserPrincipal sessionUserPrincipal) {
            return new CurrentUser(sessionUserPrincipal.id(), sessionUserPrincipal.getUsername());
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthenticated user");
    }

    /**
     * Creates a standard not-found response.
     *
     * @param resourceName missing resource name
     * @return not-found response exception
     */
    private ResponseStatusException notFound(String resourceName) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, resourceName + " not found");
    }

    /**
     * Creates a standard conflict response.
     *
     * @param message state-conflict message
     * @return conflict response exception
     */
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
