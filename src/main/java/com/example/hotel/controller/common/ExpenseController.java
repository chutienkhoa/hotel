package com.example.hotel.controller.common;

import com.example.hotel.dto.common.request.ExpenseCreateRequest;
import com.example.hotel.dto.common.request.ExpenseUpdateRequest;
import com.example.hotel.dto.common.response.ExpenseResponse;
import com.example.hotel.service.common.ExpenseService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the approved Expense v1 read, draft-edit, and explicit lifecycle operations. */
@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;

    /**
     * Creates the Expense REST controller.
     *
     * @param expenseService service that owns Expense v1 behavior
     */
    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    /**
     * Lists all Expenses.
     *
     * @return ordered Expense responses
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    List<ExpenseResponse> findAll() {
        return expenseService.findAll();
    }

    /**
     * Retrieves one Expense.
     *
     * @param id Expense identifier
     * @return Expense response
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    ExpenseResponse findById(@PathVariable UUID id) {
        return expenseService.findById(id);
    }

    /**
     * Creates a draft Expense.
     *
     * @param request validated client-controlled Expense fields
     * @return created draft Expense
     */
    @PostMapping
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    ExpenseResponse create(@Valid @RequestBody ExpenseCreateRequest request) {
        return expenseService.create(request);
    }

    /**
     * Updates an Expense while it is a draft.
     *
     * @param id Expense identifier
     * @param request validated replacement business fields
     * @return updated Expense
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    ExpenseResponse update(@PathVariable UUID id, @Valid @RequestBody ExpenseUpdateRequest request) {
        return expenseService.update(id, request);
    }

    /**
     * Submits one draft Expense.
     *
     * @param id Expense identifier
     * @return submitted Expense
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    ExpenseResponse submit(@PathVariable UUID id) {
        return expenseService.submit(id);
    }

    /**
     * Approves one submitted Expense.
     *
     * @param id Expense identifier
     * @return approved Expense
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    ExpenseResponse approve(@PathVariable UUID id) {
        return expenseService.approve(id);
    }

    /**
     * Rejects one submitted Expense.
     *
     * @param id Expense identifier
     * @return rejected Expense
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    ExpenseResponse reject(@PathVariable UUID id) {
        return expenseService.reject(id);
    }

    /**
     * Posts one approved Expense without accounting integration.
     *
     * @param id Expense identifier
     * @return posted Expense
     */
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('PERM_MANAGE_EXPENSE')")
    ExpenseResponse post(@PathVariable UUID id) {
        return expenseService.post(id);
    }
}
