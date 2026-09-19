package com.example.hotel.service.common;

import com.example.hotel.common.TableSorts;
import com.example.hotel.dto.common.request.AdditionalRevenueCreateRequest;
import com.example.hotel.dto.common.request.AdditionalRevenueSearchCriteria;
import com.example.hotel.dto.common.request.AdditionalRevenueUpdateRequest;
import com.example.hotel.dto.common.response.AdditionalRevenueCategoryResponse;
import com.example.hotel.dto.common.response.AdditionalRevenueResponse;
import com.example.hotel.entity.common.AdditionalRevenue;
import com.example.hotel.entity.common.AdditionalRevenueCategory;
import com.example.hotel.entity.common.AdditionalRevenuePaymentMethod;
import com.example.hotel.mapper.common.AdditionalRevenueMapper;
import com.example.hotel.repository.common.AdditionalRevenueCategoryRepository;
import com.example.hotel.repository.common.AdditionalRevenueRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Manages recorded Additional Revenue and its sole terminal VOIDED transition. */
@Service
public class AdditionalRevenueService {

    private static final int ADDITIONAL_REVENUE_PAGE_SIZE = 20;


    private final AdditionalRevenueRepository revenueRepository;
    private final AdditionalRevenueCategoryRepository categoryRepository;
    private final AdditionalRevenueMapper revenueMapper;
    private final Clock dashboardClock;

    public AdditionalRevenueService(
            AdditionalRevenueRepository revenueRepository,
            AdditionalRevenueCategoryRepository categoryRepository,
            AdditionalRevenueMapper revenueMapper,
            Clock dashboardClock) {
        this.revenueRepository = revenueRepository;
        this.categoryRepository = categoryRepository;
        this.revenueMapper = revenueMapper;
        this.dashboardClock = dashboardClock;
    }

    @Transactional(readOnly = true)
    public List<AdditionalRevenueResponse> findAll() {
        return revenueRepository.findAllByOrderByRevenueDateDescIdDesc().stream()
                .map(revenueMapper::toResponse)
                .toList();
    }

    /** Loads a database-filtered page in deterministic revenue-date order. */
    @Transactional(readOnly = true)
    public Page<AdditionalRevenueResponse> findPage(AdditionalRevenueSearchCriteria criteria, int page) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                ADDITIONAL_REVENUE_PAGE_SIZE,
                TableSorts.ADDITIONAL_REVENUE.resolve(criteria.getSort(), criteria.getDir()));
        return revenueRepository.findAll(specificationFor(criteria), pageable).map(revenueMapper::toResponse);
    }

    /** Builds the AND-combined database predicates for populated list filters. */
    Specification<AdditionalRevenue> specificationFor(AdditionalRevenueSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (criteria.getFromDate() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("revenueDate"), criteria.getFromDate()));
            }
            if (criteria.getToDate() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("revenueDate"), criteria.getToDate()));
            }
            if (criteria.getCategoryId() != null) {
                predicates.add(criteriaBuilder.equal(root.get("category").get("id"), criteria.getCategoryId()));
            }
            if (criteria.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), criteria.getStatus()));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public AdditionalRevenueResponse findById(UUID id) {
        return revenueMapper.toResponse(findRevenue(id));
    }

    @Transactional(readOnly = true)
    public List<AdditionalRevenueCategoryResponse> findActiveCategories() {
        return categoryRepository.findByActiveTrueOrderByCodeAsc().stream()
                .map(revenueMapper::toCategoryResponse)
                .toList();
    }

    /** Lists active and inactive categories for historical list filtering. */
    @Transactional(readOnly = true)
    public List<AdditionalRevenueCategoryResponse> findAllCategories() {
        return categoryRepository.findAllByOrderByCodeAsc().stream()
                .map(revenueMapper::toCategoryResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdditionalRevenueCategoryResponse> findSelectableCategoriesForEdit(UUID revenueId) {
        AdditionalRevenue revenue = findRevenue(revenueId);
        List<AdditionalRevenueCategoryResponse> categories = new ArrayList<>(findActiveCategories());
        AdditionalRevenueCategory currentCategory = revenue.getCategory();
        if (!currentCategory.isActive()
                && categories.stream().noneMatch(category -> category.id().equals(currentCategory.getId()))) {
            categories.add(revenueMapper.toCategoryResponse(currentCategory));
            categories.sort(Comparator.comparing(AdditionalRevenueCategoryResponse::code));
        }
        return categories;
    }

    @Transactional
    public AdditionalRevenueResponse create(AdditionalRevenueCreateRequest request) {
        validate(request.categoryId(), request.amount(), request.revenueDate(), request.paymentMethod());
        AdditionalRevenue revenue = AdditionalRevenue.create(
                resolveCategoryForCreate(request.categoryId()),
                request.amount(),
                request.revenueDate(),
                request.paymentMethod(),
                normalizeOptional(request.description()));
        revenue.audit(currentUser().id());
        return revenueMapper.toResponse(revenueRepository.save(revenue));
    }

    @Transactional
    public AdditionalRevenueResponse update(UUID id, AdditionalRevenueUpdateRequest request) {
        validate(request.categoryId(), request.amount(), request.revenueDate(), request.paymentMethod());
        AdditionalRevenue revenue = findRevenueForUpdate(id);
        try {
            revenue.updateRecorded(
                    resolveCategoryForUpdate(request.categoryId(), revenue),
                    request.amount(),
                    request.revenueDate(),
                    request.paymentMethod(),
                    normalizeOptional(request.description()));
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        revenue.audit(currentUser().id());
        return revenueMapper.toResponse(revenueRepository.save(revenue));
    }

    @Transactional
    public AdditionalRevenueResponse voidRevenue(UUID id, String voidReason) {
        String normalizedReason = normalizeRequired(voidReason, "void reason is required");
        if (normalizedReason.length() > 1000) {
            throw badRequest("void reason must not exceed 1000 characters");
        }
        CurrentUser user = currentUser();
        AdditionalRevenue revenue = findRevenueForUpdate(id);
        try {
            revenue.voidRevenue(normalizedReason, dashboardClock.instant(), user.id());
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        revenue.audit(user.id());
        return revenueMapper.toResponse(revenueRepository.save(revenue));
    }

    private void validate(
            UUID categoryId,
            BigDecimal amount,
            LocalDate revenueDate,
            AdditionalRevenuePaymentMethod paymentMethod) {
        if (categoryId == null) {
            throw badRequest("category is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("amount must be greater than zero");
        }
        if (revenueDate == null) {
            throw badRequest("revenueDate is required");
        }
        if (paymentMethod == null) {
            throw badRequest("paymentMethod is required");
        }
    }

    private AdditionalRevenueCategory resolveCategoryForCreate(UUID categoryId) {
        AdditionalRevenueCategory category = findCategory(categoryId);
        if (!category.isActive()) {
            throw conflict("Additional Revenue category is inactive");
        }
        return category;
    }

    private AdditionalRevenueCategory resolveCategoryForUpdate(UUID categoryId, AdditionalRevenue revenue) {
        AdditionalRevenueCategory category = findCategory(categoryId);
        if (!category.isActive() && !category.getId().equals(revenue.getCategory().getId())) {
            throw conflict("Additional Revenue category is inactive");
        }
        return category;
    }

    private AdditionalRevenue findRevenue(UUID id) {
        return revenueRepository.findById(id).orElseThrow(() -> notFound("Additional Revenue"));
    }

    private AdditionalRevenue findRevenueForUpdate(UUID id) {
        return revenueRepository.findByIdForUpdate(id).orElseThrow(() -> notFound("Additional Revenue"));
    }

    private AdditionalRevenueCategory findCategory(UUID id) {
        return categoryRepository.findById(id).orElseThrow(() -> notFound("Additional Revenue category"));
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeRequired(String value, String message) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw badRequest(message);
        }
        return normalized;
    }

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

    private ResponseStatusException notFound(String resource) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, resource + " not found");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
