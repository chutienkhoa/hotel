package com.example.hotel.service.common;

import com.example.hotel.dto.common.request.ExpenseCreateRequest;
import com.example.hotel.dto.common.request.ExpenseUpdateRequest;
import com.example.hotel.dto.common.response.ExpenseCategoryResponse;
import com.example.hotel.dto.common.response.ExpenseResponse;
import com.example.hotel.entity.common.Expense;
import com.example.hotel.entity.common.ExpenseCategory;
import com.example.hotel.entity.common.ExpensePaymentMethod;
import com.example.hotel.mapper.common.ExpenseMapper;
import com.example.hotel.repository.common.ExpenseCategoryRepository;
import com.example.hotel.repository.common.ExpenseRepository;
import com.example.hotel.security.CurrentUser;
import com.example.hotel.security.SessionUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Manages Expense v1 drafts, approved lifecycle transitions, and read-only category reference data. */
@Service
public class ExpenseService {

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
     * Lists the approved read-only Expense categories used by creation and update forms.
     *
     * @return categories in stable code order
     */
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> findAllCategories() {
        return expenseCategoryRepository.findAllByOrderByCodeAsc().stream()
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
        Expense expense = Expense.create(
                findCategory(request.categoryId()),
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
        try {
            expense.updateDraft(
                    findCategory(request.categoryId()),
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
