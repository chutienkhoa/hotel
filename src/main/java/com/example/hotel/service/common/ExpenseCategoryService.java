package com.example.hotel.service.common;

import com.example.hotel.dto.common.request.ExpenseCategoryCreateRequest;
import com.example.hotel.dto.common.request.ExpenseCategoryUpdateRequest;
import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.entity.common.ExpenseCategory;
import com.example.hotel.mapper.common.ExpenseMapper;
import com.example.hotel.repository.common.ExpenseCategoryRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Manages configurable Expense v1 category reference data: create, edit, deactivate, reactivate. */
@Service
public class ExpenseCategoryService {

    private final ExpenseCategoryRepository categoryRepository;
    private final ExpenseMapper expenseMapper;

    /**
     * Creates the Expense category service with its persistence and mapping collaborators.
     *
     * @param categoryRepository repository used to persist Expense categories
     * @param expenseMapper mapper used to produce client-safe category responses
     */
    public ExpenseCategoryService(ExpenseCategoryRepository categoryRepository, ExpenseMapper expenseMapper) {
        this.categoryRepository = categoryRepository;
        this.expenseMapper = expenseMapper;
    }

    /**
     * Lists every Expense category, active or inactive, in stable code order.
     *
     * @return ordered Expense category responses
     */
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> findAll() {
        return categoryRepository.findAllByOrderByCodeAsc().stream()
                .map(expenseMapper::toCategoryResponse)
                .toList();
    }

    /**
     * Finds one Expense category by its technical identifier.
     *
     * @param id ExpenseCategory identifier
     * @return client-safe category response
     * @throws ResponseStatusException if no category exists for the identifier
     */
    @Transactional(readOnly = true)
    public ExpenseCategoryResponse findById(UUID id) {
        return expenseMapper.toCategoryResponse(findCategory(id));
    }

    /**
     * Creates a new active Expense category with an immutable, unique, normalized code.
     *
     * @param request client-controlled category data
     * @return created category response
     * @throws ResponseStatusException if the normalized code is already in use
     */
    @Transactional
    public ExpenseCategoryResponse create(ExpenseCategoryCreateRequest request) {
        if (categoryRepository.existsByCode(request.code())) {
            throw conflict("Expense category code already exists");
        }
        ExpenseCategory category = ExpenseCategory.create(request.code(), request.name(), request.description());
        category.audit(currentUser().id());
        return expenseMapper.toCategoryResponse(saveGuardingDuplicateCode(category));
    }

    /**
     * Updates the editable display fields of an existing Expense category. The code never changes.
     *
     * @param id ExpenseCategory identifier
     * @param request client-controlled replacement display fields
     * @return updated category response
     * @throws ResponseStatusException if no category exists for the identifier
     */
    @Transactional
    public ExpenseCategoryResponse update(UUID id, ExpenseCategoryUpdateRequest request) {
        ExpenseCategory category = findCategory(id);
        category.updateProfile(request.name(), request.description());
        category.audit(currentUser().id());
        return expenseMapper.toCategoryResponse(categoryRepository.save(category));
    }

    /**
     * Deactivates an active Expense category so it can no longer be selected for a new Expense.
     *
     * @param id ExpenseCategory identifier
     * @return deactivated category response
     * @throws ResponseStatusException if no category exists, or it is already inactive
     */
    @Transactional
    public ExpenseCategoryResponse deactivate(UUID id) {
        return transition(id, ExpenseCategory::deactivate);
    }

    /**
     * Reactivates an inactive Expense category so it becomes selectable again.
     *
     * @param id ExpenseCategory identifier
     * @return reactivated category response
     * @throws ResponseStatusException if no category exists, or it is already active
     */
    @Transactional
    public ExpenseCategoryResponse reactivate(UUID id) {
        return transition(id, ExpenseCategory::reactivate);
    }

    /**
     * Applies one approved active-state transition.
     *
     * @param id ExpenseCategory identifier
     * @param operation approved explicit domain operation
     * @return transitioned category response
     */
    private ExpenseCategoryResponse transition(UUID id, java.util.function.Consumer<ExpenseCategory> operation) {
        ExpenseCategory category = findCategory(id);
        try {
            operation.accept(category);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        category.audit(currentUser().id());
        return expenseMapper.toCategoryResponse(categoryRepository.save(category));
    }

    /**
     * Saves a new category, converting a race-condition duplicate-code database violation into a
     * user-safe conflict rather than letting a raw persistence exception surface to the client.
     *
     * @param category new category to persist
     * @return the persisted category
     * @throws ResponseStatusException if the database rejects the row as a duplicate code
     */
    private ExpenseCategory saveGuardingDuplicateCode(ExpenseCategory category) {
        try {
            return categoryRepository.save(category);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Expense category code already exists");
        }
    }

    /**
     * Finds an Expense category by its technical identifier.
     *
     * @param id ExpenseCategory identifier
     * @return existing category
     * @throws ResponseStatusException if no category exists for the identifier
     */
    private ExpenseCategory findCategory(UUID id) {
        return categoryRepository.findById(id).orElseThrow(() -> notFound("Expense category"));
    }

    /**
     * Resolves the authenticated JWT or session principal for audit attribution.
     *
     * @return authenticated application user
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
