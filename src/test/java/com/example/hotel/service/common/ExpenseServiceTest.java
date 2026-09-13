package com.example.hotel.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotel.dto.common.request.ExpenseCreateRequest;
import com.example.hotel.dto.common.request.ExpenseUpdateRequest;
import com.example.hotel.dto.common.response.ExpenseResponse;
import com.example.hotel.entity.common.Expense;
import com.example.hotel.entity.common.ExpenseCategory;
import com.example.hotel.entity.common.ExpensePaymentMethod;
import com.example.hotel.entity.common.ExpenseStatus;
import com.example.hotel.mapper.common.ExpenseMapper;
import com.example.hotel.repository.common.ExpenseCategoryRepository;
import com.example.hotel.repository.common.ExpenseRepository;
import com.example.hotel.security.CurrentUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Verifies Expense v1 validation, audit attribution, controlled lifecycle, and reference-data use. */
class ExpenseServiceTest {

    /** Clears the authenticated user established by each test. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Confirms creation owns identity, VND currency, DRAFT status, and audit attribution on the backend. */
    @Test
    void shouldCreateDraftExpenseWithBackendControlledFields() {
        Fixture fixture = fixture();
        UUID userId = UUID.randomUUID();
        setCurrentUser(userId);
        when(fixture.categoryRepository().findById(fixture.categoryId()))
                .thenReturn(Optional.of(fixture.category()));
        when(fixture.expenseRepository().save(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseResponse response = fixture.service().create(createRequest(fixture.categoryId(), ExpensePaymentMethod.CASH));

        ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
        verify(fixture.expenseRepository()).save(captor.capture());
        Expense saved = captor.getValue();
        assertNotNull(saved.getId());
        assertEquals(ExpenseStatus.DRAFT, saved.getStatus());
        assertEquals(Expense.CURRENCY_VND, saved.getCurrency());
        assertNull(saved.getApprovedBy());
        assertEquals(userId, saved.getCreatedBy());
        assertEquals(userId, saved.getUpdatedBy());
        assertEquals("DRAFT", response.status());
        assertEquals("VND", response.currency());
    }

    /** Confirms every approved reference category can be selected for Expense creation. */
    @ParameterizedTest
    @MethodSource("approvedCategories")
    void shouldAcceptApprovedExpenseCategories(String categoryCode) {
        Fixture fixture = fixture();
        ExpenseCategory category = category(fixture.categoryId(), categoryCode);
        setCurrentUser(UUID.randomUUID());
        when(fixture.categoryRepository().findById(fixture.categoryId())).thenReturn(Optional.of(category));
        when(fixture.expenseRepository().save(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseResponse response = fixture.service().create(createRequest(fixture.categoryId(), ExpensePaymentMethod.CASH));

        assertEquals(categoryCode, response.category().code());
    }

    /** Confirms every approved Expense-specific payment method can be selected. */
    @ParameterizedTest
    @MethodSource("expensePaymentMethods")
    void shouldAcceptApprovedExpensePaymentMethods(ExpensePaymentMethod paymentMethod) {
        Fixture fixture = fixture();
        setCurrentUser(UUID.randomUUID());
        when(fixture.categoryRepository().findById(fixture.categoryId()))
                .thenReturn(Optional.of(fixture.category()));
        when(fixture.expenseRepository().save(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseResponse response = fixture.service().create(createRequest(fixture.categoryId(), paymentMethod));

        assertEquals(paymentMethod.name(), response.paymentMethod());
    }

    /** Confirms non-positive Expense amounts are rejected at the service boundary. */
    @Test
    void shouldRejectNonPositiveAmount() {
        Fixture fixture = fixture();
        setCurrentUser(UUID.randomUUID());

        assertBadRequest(() -> fixture.service().create(new ExpenseCreateRequest(
                fixture.categoryId(), BigDecimal.ZERO, LocalDate.now(), ExpensePaymentMethod.CASH, null)));
    }

    /** Confirms create and update DTOs exclude all server-controlled Expense fields. */
    @Test
    void shouldNotExposeServerControlledFieldsInRequests() {
        List<String> createComponents = componentNames(ExpenseCreateRequest.class);
        List<String> updateComponents = componentNames(ExpenseUpdateRequest.class);

        for (String component : List.of(
                "id", "currency", "status", "approvedBy", "createdBy", "createdAt", "updatedBy", "updatedAt")) {
            assertFalse(createComponents.contains(component));
            assertFalse(updateComponents.contains(component));
        }
    }

    /** Confirms a draft Expense may replace all approved business fields. */
    @Test
    void shouldUpdateDraftExpenseOnly() {
        Fixture fixture = fixture();
        UUID creatorId = UUID.randomUUID();
        UUID updaterId = UUID.randomUUID();
        Expense expense = Expense.create(
                fixture.category(), BigDecimal.TEN, LocalDate.of(2026, 9, 10), ExpensePaymentMethod.CASH, "Old");
        expense.audit(creatorId);
        setCurrentUser(updaterId);
        when(fixture.expenseRepository().findByIdForUpdate(expense.getId())).thenReturn(Optional.of(expense));
        when(fixture.categoryRepository().findById(fixture.categoryId()))
                .thenReturn(Optional.of(fixture.category()));
        when(fixture.expenseRepository().save(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseResponse response = fixture.service().update(expense.getId(), new ExpenseUpdateRequest(
                fixture.categoryId(), new BigDecimal("25.50"), LocalDate.of(2026, 9, 11),
                ExpensePaymentMethod.BANK_TRANSFER, "Updated"));

        assertEquals(ExpenseStatus.DRAFT, expense.getStatus());
        assertEquals(0, new BigDecimal("25.50").compareTo(expense.getAmount()));
        assertEquals(ExpensePaymentMethod.BANK_TRANSFER, expense.getPaymentMethod());
        assertEquals(creatorId, expense.getCreatedBy());
        assertEquals(updaterId, expense.getUpdatedBy());
        assertEquals("Updated", response.description());
    }

    /** Confirms an Expense can no longer be updated after leaving DRAFT. */
    @Test
    void shouldRejectUpdateAfterExpenseLeavesDraft() {
        Fixture fixture = fixture();
        Expense expense = draftExpense(fixture);
        expense.submit();
        setCurrentUser(UUID.randomUUID());
        when(fixture.expenseRepository().findByIdForUpdate(expense.getId())).thenReturn(Optional.of(expense));
        when(fixture.categoryRepository().findById(fixture.categoryId()))
                .thenReturn(Optional.of(fixture.category()));

        assertConflict(() -> fixture.service().update(expense.getId(), updateRequest(fixture.categoryId())));
        assertEquals(ExpenseStatus.SUBMITTED, expense.getStatus());
    }

    /** Confirms the DRAFT-to-SUBMITTED transition succeeds and updates audit attribution. */
    @Test
    void shouldSubmitDraftExpense() {
        Fixture fixture = fixture();
        Expense expense = draftExpense(fixture);
        UUID userId = UUID.randomUUID();
        setCurrentUser(userId);
        prepareTransition(fixture, expense);

        fixture.service().submit(expense.getId());

        assertEquals(ExpenseStatus.SUBMITTED, expense.getStatus());
        assertEquals(userId, expense.getUpdatedBy());
    }

    /** Confirms the submitted-to-approved transition assigns the authenticated approver. */
    @Test
    void shouldApproveSubmittedExpenseAndSetApprovedBy() {
        Fixture fixture = fixture();
        Expense expense = draftExpense(fixture);
        expense.submit();
        UUID approverId = UUID.randomUUID();
        setCurrentUser(approverId);
        prepareTransition(fixture, expense);

        fixture.service().approve(expense.getId());

        assertEquals(ExpenseStatus.APPROVED, expense.getStatus());
        assertEquals(approverId, expense.getApprovedBy());
    }

    /** Confirms submitted Expenses may be rejected through their explicit operation. */
    @Test
    void shouldRejectSubmittedExpense() {
        Fixture fixture = fixture();
        Expense expense = draftExpense(fixture);
        expense.submit();
        setCurrentUser(UUID.randomUUID());
        prepareTransition(fixture, expense);

        fixture.service().reject(expense.getId());

        assertEquals(ExpenseStatus.REJECTED, expense.getStatus());
    }

    /** Confirms approved Expenses may be posted without accounting dependencies. */
    @Test
    void shouldPostApprovedExpenseWithoutAccountingEntryCreation() {
        Fixture fixture = fixture();
        Expense expense = draftExpense(fixture);
        expense.submit();
        expense.approve(UUID.randomUUID());
        setCurrentUser(UUID.randomUUID());
        prepareTransition(fixture, expense);

        fixture.service().post(expense.getId());

        assertEquals(ExpenseStatus.POSTED, expense.getStatus());
    }

    /** Confirms each disallowed source status is rejected by the corresponding lifecycle operation. */
    @ParameterizedTest
    @MethodSource("invalidTransitions")
    void shouldRejectInvalidTransitions(
            Expense expense, java.util.function.Consumer<ExpenseService> operation, Fixture fixture) {
        setCurrentUser(UUID.randomUUID());
        when(fixture.expenseRepository().findByIdForUpdate(expense.getId())).thenReturn(Optional.of(expense));

        assertConflict(() -> operation.accept(fixture.service()));
    }

    /** Supplies all approved Expense category codes. */
    private static Stream<String> approvedCategories() {
        return Stream.of(
                "ELECTRICITY", "WATER", "INTERNET", "SALARY", "LAUNDRY", "CLEANING", "SUPPLIES",
                "MAINTENANCE", "OTHER");
    }

    /** Supplies all Expense-specific payment methods. */
    private static Stream<ExpensePaymentMethod> expensePaymentMethods() {
        return Stream.of(ExpensePaymentMethod.CASH, ExpensePaymentMethod.BANK_TRANSFER,
                ExpensePaymentMethod.CREDIT_CARD, ExpensePaymentMethod.OTHER);
    }

    /** Supplies invalid lifecycle-operation scenarios. */
    private static Stream<Arguments> invalidTransitions() {
        Fixture fixture = fixture();
        Expense draft = draftExpense(fixture);
        Expense submitted = draftExpense(fixture);
        submitted.submit();
        Expense approved = draftExpense(fixture);
        approved.submit();
        approved.approve(UUID.randomUUID());
        return Stream.of(
                Arguments.of(draft, (java.util.function.Consumer<ExpenseService>) service -> service.approve(draft.getId()), fixture),
                Arguments.of(draft, (java.util.function.Consumer<ExpenseService>) service -> service.reject(draft.getId()), fixture),
                Arguments.of(submitted, (java.util.function.Consumer<ExpenseService>) service -> service.post(submitted.getId()), fixture),
                Arguments.of(approved, (java.util.function.Consumer<ExpenseService>) service -> service.submit(approved.getId()), fixture));
    }

    /** Returns record component names for a request type. */
    private List<String> componentNames(Class<?> requestType) {
        return Arrays.stream(requestType.getRecordComponents()).map(component -> component.getName()).toList();
    }

    /** Creates a default valid Expense creation request. */
    private ExpenseCreateRequest createRequest(UUID categoryId, ExpensePaymentMethod paymentMethod) {
        return new ExpenseCreateRequest(categoryId, BigDecimal.TEN, LocalDate.of(2026, 9, 11), paymentMethod, null);
    }

    /** Creates a default valid Expense update request. */
    private ExpenseUpdateRequest updateRequest(UUID categoryId) {
        return new ExpenseUpdateRequest(categoryId, BigDecimal.TEN, LocalDate.of(2026, 9, 11), ExpensePaymentMethod.CASH, null);
    }

    /** Creates a draft Expense fixture with the fixture category. */
    private static Expense draftExpense(Fixture fixture) {
        return Expense.create(fixture.category(), BigDecimal.TEN, LocalDate.of(2026, 9, 11), ExpensePaymentMethod.CASH, null);
    }

    /** Prepares the repository behavior required by one explicit lifecycle transition. */
    private void prepareTransition(Fixture fixture, Expense expense) {
        when(fixture.expenseRepository().findByIdForUpdate(expense.getId())).thenReturn(Optional.of(expense));
        when(fixture.expenseRepository().save(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** Creates an Expense test fixture with mocked persistence collaborators. */
    private static Fixture fixture() {
        ExpenseRepository expenseRepository = mock(ExpenseRepository.class);
        ExpenseCategoryRepository categoryRepository = mock(ExpenseCategoryRepository.class);
        UUID categoryId = UUID.randomUUID();
        return new Fixture(
                new ExpenseService(expenseRepository, categoryRepository, new ExpenseMapper()),
                expenseRepository,
                categoryRepository,
                category(categoryId, "ELECTRICITY"),
                categoryId);
    }

    /** Creates a mocked read-only ExpenseCategory fixture. */
    private static ExpenseCategory category(UUID id, String code) {
        ExpenseCategory category = mock(ExpenseCategory.class);
        when(category.getId()).thenReturn(id);
        when(category.getCode()).thenReturn(code);
        return category;
    }

    /** Establishes the authenticated application user used for audit attribution. */
    private void setCurrentUser(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CurrentUser(userId, "expense-manager"), null));
    }

    /** Confirms an operation returns the standard bad-request response. */
    private void assertBadRequest(org.junit.jupiter.api.function.Executable operation) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, operation);
        assertEquals(400, exception.getStatusCode().value());
    }

    /** Confirms an operation returns the standard conflict response. */
    private void assertConflict(org.junit.jupiter.api.function.Executable operation) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, operation);
        assertEquals(409, exception.getStatusCode().value());
    }

    /** Holds Expense service collaborators and reference-data fixtures. */
    private record Fixture(
            ExpenseService service,
            ExpenseRepository expenseRepository,
            ExpenseCategoryRepository categoryRepository,
            ExpenseCategory category,
            UUID categoryId) {}
}
