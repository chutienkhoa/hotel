package com.example.hotel.service.common;

import com.example.hotel.common.TableSorts;
import com.example.hotel.dto.common.request.ExpenseCreateRequest;
import com.example.hotel.dto.common.request.ExpenseUpdateRequest;
import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.dto.common.response.ExpenseResponse;
import com.example.hotel.entity.common.Expense;
import com.example.hotel.entity.common.ExpenseCategory;
import com.example.hotel.entity.common.ExpensePaymentMethod;
import com.example.hotel.mapper.common.ExpenseMapper;
import com.example.hotel.dto.common.request.ExpenseSearchCriteria;
import com.example.hotel.repository.common.ExpenseCategoryRepository;
import com.example.hotel.repository.common.ExpenseRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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

/** Manages Expense v1 drafts, approved lifecycle transitions, and read-only category reference data. */
@Service
public class ExpenseService {

    private static final int EXPENSE_PAGE_SIZE = 20;


    private final ExpenseRepository expenseRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseMapper expenseMapper;

    /**
     * Creates the Expense service with persistence and mapping collaborators.
     *
     * @param expenseRepository repository used to persist and lock Expenses
     * @param expenseCategoryRepository repository used to resolve read-only categories
     * @param expenseMapper mapper used to produce client-safe responses
     */
    public ExpenseService(
            ExpenseRepository expenseRepository,
            ExpenseCategoryRepository expenseCategoryRepository,
            ExpenseMapper expenseMapper) {
        this.expenseRepository = expenseRepository;
        this.expenseCategoryRepository = expenseCategoryRepository;
        this.expenseMapper = expenseMapper;
    }

    /**
     * Lists all Expenses in stable descending business-date order.
     *
     * @return ordered Expense responses
     */
    @Transactional(readOnly = true)
    public List<ExpenseResponse> findAll() {
        return expenseRepository.findAllByOrderByExpenseDateDescIdDesc().stream()
                .map(expenseMapper::toResponse)
                .toList();
    }

    /**
     * Loads one database-backed page of Expenses matching every supplied optional filter.
     *
     * @param criteria normalized optional Expense list filters
     * @param page zero-based requested page number
     * @return a page of client-safe Expense responses ordered by expense date, then identifier, descending
     */
    @Transactional(readOnly = true)
    public Page<ExpenseResponse> findPage(ExpenseSearchCriteria criteria, int page) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                EXPENSE_PAGE_SIZE,
                TableSorts.EXPENSE.resolve(criteria.getSort(), criteria.getDir()));
        return expenseRepository.findAll(specificationFor(criteria), pageable).map(expenseMapper::toResponse);
    }

    /**
     * Builds the database predicate combining every supplied Expense filter.
     *
     * <p>Each populated filter field contributes its own predicate on its own database column or
     * association; populated filters are combined with AND semantics, so an Expense must match
     * every supplied filter to appear in the result. From Date and To Date are both inclusive.</p>
     *
     * @param criteria normalized optional Expense list filters
     * @return the database specification for matching Expense list rows
     */
    Specification<Expense> specificationFor(ExpenseSearchCriteria criteria) {
        return (root, query, criteriaBuilder) -> {
            var predicates = new ArrayList<Predicate>();
            if (criteria.getFromDate() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("expenseDate"), criteria.getFromDate()));
            }
            if (criteria.getToDate() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("expenseDate"), criteria.getToDate()));
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

    /**
     * Finds one Expense by its technical identifier.
     *
     * @param id Expense identifier
     * @return client-safe Expense response
     * @throws ResponseStatusException if no Expense exists for the identifier
     */
    @Transactional(readOnly = true)
    public ExpenseResponse findById(UUID id) {
        return expenseMapper.toResponse(findExpense(id));
    }

    /**
     * Lists every Expense category, active or inactive, in stable code order.
     *
     * <p>Used where historical Expense data must remain representable, such as the Expense list
     * category filter, which must still be able to filter by a category that has since been
     * deactivated. Do not use this for a form that lets staff select a category for a
     * <em>new</em> Expense assignment; use {@link #findActiveCategories()} for that.</p>
     *
     * @return categories in stable code order, active and inactive
     */
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> findAllCategories() {
        return expenseCategoryRepository.findAllByOrderByCodeAsc().stream()
                .map(expenseMapper::toCategoryResponse)
                .toList();
    }

    /**
     * Lists only active Expense categories in stable code order.
     *
     * <p>Used for the Create Expense form, where only categories currently selectable for a new
     * assignment should be offered.</p>
     *
     * @return active categories in stable code order
     */
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> findActiveCategories() {
        return expenseCategoryRepository.findByActiveTrueOrderByCodeAsc().stream()
                .map(expenseMapper::toCategoryResponse)
                .toList();
    }

    /**
     * Creates a draft Expense with the fixed VND currency and backend-owned lifecycle fields.
     *
     * @param request client-controlled Expense data
     * @return created draft Expense response
     */
    @Transactional
    public ExpenseResponse create(ExpenseCreateRequest request) {
        validate(request.categoryId(), request.amount(), request.expenseDate(), request.paymentMethod());
        ExpenseCategory category = resolveCategoryForCreate(request.categoryId());
        Expense expense = Expense.create(
                category,
                request.amount(),
                request.expenseDate(),
                request.paymentMethod(),
                request.description());
        expense.audit(currentUser().id());
        return expenseMapper.toResponse(expenseRepository.save(expense));
    }

    /**
     * Updates the business fields of an Expense only while it remains a draft.
     *
     * @param id Expense identifier
     * @param request client-controlled replacement business fields
     * @return updated Expense response
     * @throws ResponseStatusException if the Expense is missing or no longer a draft
     */
    @Transactional
    public ExpenseResponse update(UUID id, ExpenseUpdateRequest request) {
        validate(request.categoryId(), request.amount(), request.expenseDate(), request.paymentMethod());
        Expense expense = findExpenseForUpdate(id);
        ExpenseCategory category = resolveCategoryForUpdate(request.categoryId(), expense);
        try {
            expense.updateDraft(
                    category,
                    request.amount(),
                    request.expenseDate(),
                    request.paymentMethod(),
                    request.description());
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        expense.audit(currentUser().id());
        return expenseMapper.toResponse(expenseRepository.save(expense));
    }

    /**
     * Submits a draft Expense.
     *
     * @param id Expense identifier
     * @return submitted Expense response
     */
    @Transactional
    public ExpenseResponse submit(UUID id) {
        return transition(id, Expense::submit);
    }

    /**
     * Approves a submitted Expense and assigns the authenticated approver.
     *
     * @param id Expense identifier
     * @return approved Expense response
     */
    @Transactional
    public ExpenseResponse approve(UUID id) {
        CurrentUser user = currentUser();
        Expense expense = findExpenseForUpdate(id);
        try {
            expense.approve(user.id());
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        expense.audit(user.id());
        return expenseMapper.toResponse(expenseRepository.save(expense));
    }

    /**
     * Rejects a submitted Expense.
     *
     * @param id Expense identifier
     * @return rejected Expense response
     */
    @Transactional
    public ExpenseResponse reject(UUID id) {
        return transition(id, Expense::reject);
    }

    /**
     * Posts an approved Expense without creating an AccountingEntry in Expense v1.
     *
     * @param id Expense identifier
     * @return posted Expense response
     */
    @Transactional
    public ExpenseResponse post(UUID id) {
        return transition(id, Expense::post);
    }

    /**
     * Applies one non-approval lifecycle operation and records its authenticated updater.
     *
     * @param id Expense identifier
     * @param operation one approved explicit domain operation
     * @return transitioned Expense response
     */
    private ExpenseResponse transition(UUID id, Consumer<Expense> operation) {
        Expense expense = findExpenseForUpdate(id);
        try {
            operation.accept(expense);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        expense.audit(currentUser().id());
        return expenseMapper.toResponse(expenseRepository.save(expense));
    }

    /**
     * Validates client-controlled Expense fields independently of REST Bean Validation.
     *
     * @param categoryId selected ExpenseCategory identifier
     * @param amount positive Expense amount
     * @param expenseDate client-selected business date
     * @param paymentMethod selected Expense payment method
     */
    private void validate(
            UUID categoryId,
            BigDecimal amount,
            LocalDate expenseDate,
            ExpensePaymentMethod paymentMethod) {
        if (categoryId == null) {
            throw badRequest("category is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest("amount must be greater than zero");
        }
        if (expenseDate == null) {
            throw badRequest("expenseDate is required");
        }
        if (paymentMethod == null) {
            throw badRequest("paymentMethod is required");
        }
    }

    /**
     * Finds an Expense without applying a write lock.
     *
     * @param id Expense identifier
     * @return existing Expense
     */
    private Expense findExpense(UUID id) {
        return expenseRepository.findById(id).orElseThrow(() -> notFound("Expense"));
    }

    /**
     * Finds and locks an Expense before mutation.
     *
     * @param id Expense identifier
     * @return locked Expense
     */
    private Expense findExpenseForUpdate(UUID id) {
        return expenseRepository.findByIdForUpdate(id).orElseThrow(() -> notFound("Expense"));
    }

    /**
     * Resolves one selected read-only Expense category.
     *
     * @param id ExpenseCategory identifier
     * @return existing category
     */
    private ExpenseCategory findCategory(UUID id) {
        return expenseCategoryRepository.findById(id).orElseThrow(() -> notFound("Expense category"));
    }

    /**
     * Resolves the category selected for a new Expense, requiring it to be currently active.
     *
     * @param id selected ExpenseCategory identifier
     * @return the resolved active category
     * @throws ResponseStatusException if the category is missing or inactive
     */
    private ExpenseCategory resolveCategoryForCreate(UUID id) {
        ExpenseCategory category = findCategory(id);
        if (!category.isActive()) {
            throw badRequest("category is not active");
        }
        return category;
    }

    /**
     * Resolves the category selected while editing a draft Expense.
     *
     * <p>An active category is always allowed. An inactive category is allowed only when it is
     * the Expense's own current category, so an existing assignment to a now-inactive category
     * can be left unchanged; switching to a <em>different</em> inactive category is rejected.</p>
     *
     * @param id selected ExpenseCategory identifier
     * @param expense the draft Expense being edited
     * @return the resolved allowed category
     * @throws ResponseStatusException if the category is missing, or inactive and not the
     *     Expense's current category
     */
    private ExpenseCategory resolveCategoryForUpdate(UUID id, Expense expense) {
        ExpenseCategory category = findCategory(id);
        boolean keepingCurrentCategory = category.getId().equals(expense.getCategory().getId());
        if (!category.isActive() && !keepingCurrentCategory) {
            throw badRequest("category is not active");
        }
        return category;
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
     * Creates a standard bad-request response.
     *
     * @param message validation message
     * @return bad-request response exception
     */
    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
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
