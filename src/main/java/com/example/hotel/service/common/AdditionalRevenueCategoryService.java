package com.example.hotel.service.common;

import com.example.hotel.dto.common.request.AdditionalRevenueCategoryCreateRequest;
import com.example.hotel.dto.common.request.AdditionalRevenueCategoryUpdateRequest;
import com.example.hotel.dto.common.response.AdditionalRevenueCategoryResponse;
import com.example.hotel.entity.common.AdditionalRevenueCategory;
import com.example.hotel.mapper.common.AdditionalRevenueMapper;
import com.example.hotel.repository.common.AdditionalRevenueCategoryRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Manages configurable Additional Revenue category reference data. */
@Service
public class AdditionalRevenueCategoryService {

    private final AdditionalRevenueCategoryRepository categoryRepository;
    private final AdditionalRevenueMapper revenueMapper;

    public AdditionalRevenueCategoryService(
            AdditionalRevenueCategoryRepository categoryRepository, AdditionalRevenueMapper revenueMapper) {
        this.categoryRepository = categoryRepository;
        this.revenueMapper = revenueMapper;
    }

    /** Lists active and inactive categories in stable technical-code order. */
    @Transactional(readOnly = true)
    public List<AdditionalRevenueCategoryResponse> findAll() {
        return categoryRepository.findAllByOrderByCodeAsc().stream()
                .map(revenueMapper::toCategoryResponse)
                .toList();
    }

    /** Finds a category by ID. */
    @Transactional(readOnly = true)
    public AdditionalRevenueCategoryResponse findById(UUID id) {
        return revenueMapper.toCategoryResponse(findCategory(id));
    }

    /** Creates a category in the active state with a unique immutable code. */
    @Transactional
    public AdditionalRevenueCategoryResponse create(AdditionalRevenueCategoryCreateRequest request) {
        if (categoryRepository.existsByCode(request.code())) {
            throw conflict("Additional Revenue category code already exists");
        }
        AdditionalRevenueCategory category =
                AdditionalRevenueCategory.create(request.code(), request.name(), request.description());
        category.audit(currentUser().id());
        return revenueMapper.toCategoryResponse(saveGuardingDuplicateCode(category));
    }

    /** Updates category display information only; code and active state remain immutable here. */
    @Transactional
    public AdditionalRevenueCategoryResponse update(UUID id, AdditionalRevenueCategoryUpdateRequest request) {
        AdditionalRevenueCategory category = findCategory(id);
        category.updateProfile(request.name(), request.description());
        category.audit(currentUser().id());
        return revenueMapper.toCategoryResponse(categoryRepository.save(category));
    }

    /** Applies the approved ACTIVE to INACTIVE transition. */
    @Transactional
    public AdditionalRevenueCategoryResponse deactivate(UUID id) {
        return transition(id, AdditionalRevenueCategory::deactivate);
    }

    /** Applies the approved INACTIVE to ACTIVE transition. */
    @Transactional
    public AdditionalRevenueCategoryResponse reactivate(UUID id) {
        return transition(id, AdditionalRevenueCategory::reactivate);
    }

    private AdditionalRevenueCategoryResponse transition(UUID id, Consumer<AdditionalRevenueCategory> operation) {
        AdditionalRevenueCategory category = findCategory(id);
        try {
            operation.accept(category);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        category.audit(currentUser().id());
        return revenueMapper.toCategoryResponse(categoryRepository.save(category));
    }

    private AdditionalRevenueCategory saveGuardingDuplicateCode(AdditionalRevenueCategory category) {
        try {
            return categoryRepository.save(category);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Additional Revenue category code already exists");
        }
    }

    private AdditionalRevenueCategory findCategory(UUID id) {
        return categoryRepository.findById(id).orElseThrow(() -> notFound("Additional Revenue category"));
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

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
