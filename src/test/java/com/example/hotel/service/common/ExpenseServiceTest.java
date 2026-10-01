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
import com.example.hotel.dto.common.request.ExpenseSearchCriteria;
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
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    /**
     * Confirms an Expense amount carrying fractional dong is rejected rather than silently rounded.
     * Expense is VND-only, and VND has no fraction digits.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1000.5", "0.5", "100.123456"})
    void shouldRejectAmountExceedingVndPrecision(String amount) {
        Fixture fixture = fixture();
        setCurrentUser(UUID.randomUUID());

        assertBadRequest(() -> fixture.service().create(new ExpenseCreateRequest(
                fixture.categoryId(), new BigDecimal(amount), LocalDate.now(), ExpensePaymentMethod.CASH, null)));
    }

    /** Confirms a whole-dong amount, including one written with trailing zeros, is accepted. */
    @ParameterizedTest
    @ValueSource(strings = {"1000", "1000.00", "2550000"})
    void shouldAcceptWholeDongAmount(String amount) {
        Fixture fixture = fixture();
        setCurrentUser(UUID.randomUUID());
        when(fixture.categoryRepository().findById(fixture.categoryId()))
                .thenReturn(Optional.of(fixture.category()));
        when(fixture.expenseRepository().save(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseResponse response = fixture.service().create(new ExpenseCreateRequest(
                fixture.categoryId(), new BigDecimal(amount), LocalDate.now(), ExpensePaymentMethod.CASH, null));

        assertEquals(0, new BigDecimal(amount).compareTo(response.amount()));
    }

    /** Confirms a new Expense can be created with an active category. */
    @Test
    void shouldAcceptActiveCategoryOnCreate() {
        Fixture fixture = fixture();
        setCurrentUser(UUID.randomUUID());
        when(fixture.categoryRepository().findById(fixture.categoryId()))
                .thenReturn(Optional.of(fixture.category()));
        when(fixture.expenseRepository().save(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseResponse response = fixture.service().create(createRequest(fixture.categoryId(), ExpensePaymentMethod.CASH));

        assertNotNull(response.id());
    }

    /** Confirms a new Expense cannot be created with an inactive category. */
    @Test
    void shouldRejectInactiveCategoryOnCreate() {
        Fixture fixture = fixture();
        UUID inactiveCategoryId = UUID.randomUUID();
        ExpenseCategory inactiveCategory = category(inactiveCategoryId, "OLD_CATEGORY");
        when(inactiveCategory.isActive()).thenReturn(false);
        setCurrentUser(UUID.randomUUID());
        when(fixture.categoryRepository().findById(inactiveCategoryId)).thenReturn(Optional.of(inactiveCategory));

        assertBadRequest(() -> fixture.service().create(createRequest(inactiveCategoryId, ExpensePaymentMethod.CASH)));
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
                fixture.categoryId(), new BigDecimal("2550000"), LocalDate.of(2026, 9, 11),
                ExpensePaymentMethod.BANK_TRANSFER, "Updated"));

        assertEquals(ExpenseStatus.DRAFT, expense.getStatus());
        assertEquals(0, new BigDecimal("2550000").compareTo(expense.getAmount()));
        assertEquals(ExpensePaymentMethod.BANK_TRANSFER, expense.getPaymentMethod());
        assertEquals(creatorId, expense.getCreatedBy());
        assertEquals(updaterId, expense.getUpdatedBy());
        assertEquals("Updated", response.description());
    }

    /** Confirms a draft Expense may keep its current category even after that category becomes inactive. */
    @Test
    void shouldAllowKeepingCurrentInactiveCategoryOnDraftEdit() {
        Fixture fixture = fixture();
        ExpenseCategory inactiveCategory = category(fixture.categoryId(), "OLD_CATEGORY");
        when(inactiveCategory.isActive()).thenReturn(false);
        Expense expense = Expense.create(
                inactiveCategory, BigDecimal.TEN, LocalDate.of(2026, 9, 10), ExpensePaymentMethod.CASH, null);
        setCurrentUser(UUID.randomUUID());
        when(fixture.expenseRepository().findByIdForUpdate(expense.getId())).thenReturn(Optional.of(expense));
        when(fixture.categoryRepository().findById(fixture.categoryId())).thenReturn(Optional.of(inactiveCategory));
        when(fixture.expenseRepository().save(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExpenseResponse response = fixture.service().update(expense.getId(), updateRequest(fixture.categoryId()));

        assertEquals(fixture.categoryId(), expense.getCategory().getId());
        assertNotNull(response.id());
    }

    /** Confirms a draft Expense may switch from its current inactive category to an active category. */
    @Test
    void shouldAllowChangingFromInactiveToActiveCategoryOnDraftEdit() {
        Fixture fixture = fixture();
        UUID inactiveCategoryId = UUID.randomUUID();
        ExpenseCategory inactiveCategory = category(inactiveCategoryId, "OLD_CATEGORY");
        when(inactiveCategory.isActive()).thenReturn(false);
        Expense expense = Expense.create(
                inactiveCategory, BigDecimal.TEN, LocalDate.of(2026, 9, 10), ExpensePaymentMethod.CASH, null);
        setCurrentUser(UUID.randomUUID());
        when(fixture.expenseRepository().findByIdForUpdate(expense.getId())).thenReturn(Optional.of(expense));
        when(fixture.categoryRepository().findById(fixture.categoryId()))
                .thenReturn(Optional.of(fixture.category()));
        when(fixture.expenseRepository().save(any(Expense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service().update(expense.getId(), updateRequest(fixture.categoryId()));

        assertEquals(fixture.categoryId(), expense.getCategory().getId());
    }

    /** Confirms a draft Expense cannot switch from its current inactive category to a different inactive category. */
    @Test
    void shouldRejectChangingToAnotherInactiveCategoryOnDraftEdit() {
        Fixture fixture = fixture();
        UUID currentInactiveId = UUID.randomUUID();
        ExpenseCategory currentInactive = category(currentInactiveId, "OLD_CATEGORY");
        when(currentInactive.isActive()).thenReturn(false);
        UUID otherInactiveId = UUID.randomUUID();
        ExpenseCategory otherInactive = category(otherInactiveId, "OTHER_OLD_CATEGORY");
        when(otherInactive.isActive()).thenReturn(false);
        Expense expense = Expense.create(
                currentInactive, BigDecimal.TEN, LocalDate.of(2026, 9, 10), ExpensePaymentMethod.CASH, null);
        setCurrentUser(UUID.randomUUID());
        when(fixture.expenseRepository().findByIdForUpdate(expense.getId())).thenReturn(Optional.of(expense));
        when(fixture.categoryRepository().findById(otherInactiveId)).thenReturn(Optional.of(otherInactive));

        assertBadRequest(() -> fixture.service().update(expense.getId(), updateRequest(otherInactiveId)));
        assertEquals(currentInactiveId, expense.getCategory().getId());
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

    /** Confirms the Expense list query uses the approved fixed page size and default ordering. */
    @Test
    void shouldQueryExpensesWithApprovedPageSizeAndOrdering() {
        Fixture fixture = fixture();
        Expense expense = draftExpense(fixture);
        when(fixture.expenseRepository().findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(expense), PageRequest.of(1, 20), 21));

        Page<ExpenseResponse> result = fixture.service().findPage(new ExpenseSearchCriteria(), 1);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(fixture.expenseRepository()).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("expenseDate").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
        assertEquals(21, result.getTotalElements());
        assertEquals(2, result.getTotalPages());
    }

    /** Confirms an unfiltered criteria object adds no database predicate for any field. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldIgnoreAbsentExpenseFilters() {
        Fixture fixture = fixture();
        ExpenseSearchCriteria criteria = new ExpenseSearchCriteria();

        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Predicate conjunction = mock(Predicate.class);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = fixture.service().specificationFor(criteria)
                .toPredicate(mock(Root.class), mock(CriteriaQuery.class), criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(0, predicatesCaptor.getValue().length);
    }

    /** Confirms From Date builds an inclusive greater-than-or-equal predicate. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildInclusiveFromDatePredicate() {
        Fixture fixture = fixture();
        ExpenseSearchCriteria criteria = new ExpenseSearchCriteria();
        LocalDate fromDate = LocalDate.of(2026, 9, 1);
        criteria.setFromDate(fromDate);

        Root<Expense> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<LocalDate> dateField = mock(Path.class);
        Predicate predicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<LocalDate>get("expenseDate")).thenReturn(dateField);
        when(criteriaBuilder.greaterThanOrEqualTo(dateField, fromDate)).thenReturn(predicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = fixture.service().specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(1, predicatesCaptor.getValue().length);
        verify(criteriaBuilder).greaterThanOrEqualTo(dateField, fromDate);
    }

    /** Confirms To Date builds an inclusive less-than-or-equal predicate. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildInclusiveToDatePredicate() {
        Fixture fixture = fixture();
        ExpenseSearchCriteria criteria = new ExpenseSearchCriteria();
        LocalDate toDate = LocalDate.of(2026, 9, 30);
        criteria.setToDate(toDate);

        Root<Expense> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<LocalDate> dateField = mock(Path.class);
        Predicate predicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<LocalDate>get("expenseDate")).thenReturn(dateField);
        when(criteriaBuilder.lessThanOrEqualTo(dateField, toDate)).thenReturn(predicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = fixture.service().specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(1, predicatesCaptor.getValue().length);
        verify(criteriaBuilder).lessThanOrEqualTo(dateField, toDate);
    }

    /** Confirms a combined From/To range contributes both inclusive date predicates together. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldCombineFromAndToDatePredicates() {
        Fixture fixture = fixture();
        ExpenseSearchCriteria criteria = new ExpenseSearchCriteria();
        criteria.setFromDate(LocalDate.of(2026, 9, 1));
        criteria.setToDate(LocalDate.of(2026, 9, 30));

        Root<Expense> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<LocalDate> dateField = mock(Path.class);
        Predicate fromPredicate = mock(Predicate.class);
        Predicate toPredicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<LocalDate>get("expenseDate")).thenReturn(dateField);
        when(criteriaBuilder.greaterThanOrEqualTo(dateField, criteria.getFromDate())).thenReturn(fromPredicate);
        when(criteriaBuilder.lessThanOrEqualTo(dateField, criteria.getToDate())).thenReturn(toPredicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = fixture.service().specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(2, predicatesCaptor.getValue().length);
        assertEquals(List.of(fromPredicate, toPredicate), List.of(predicatesCaptor.getValue()));
    }

    /** Confirms a Category filter builds an exact-match predicate against the category identifier. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildExactMatchPredicateForCategoryId() {
        Fixture fixture = fixture();
        ExpenseSearchCriteria criteria = new ExpenseSearchCriteria();
        UUID categoryId = UUID.randomUUID();
        criteria.setCategoryId(categoryId);

        Root<Expense> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<Object> categoryPath = mock(Path.class);
        Path<UUID> categoryIdPath = mock(Path.class);
        Predicate predicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.get("category")).thenReturn(categoryPath);
        when(categoryPath.<UUID>get("id")).thenReturn(categoryIdPath);
        when(criteriaBuilder.equal(categoryIdPath, categoryId)).thenReturn(predicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = fixture.service().specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(1, predicatesCaptor.getValue().length);
    }

    /** Confirms a Status filter builds an exact-match predicate against the Expense status enum. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldBuildExactMatchPredicateForExpenseStatus() {
        Fixture fixture = fixture();
        ExpenseSearchCriteria criteria = new ExpenseSearchCriteria();
        criteria.setStatus(ExpenseStatus.POSTED);

        Root<Expense> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<ExpenseStatus> statusField = mock(Path.class);
        Predicate predicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.<ExpenseStatus>get("status")).thenReturn(statusField);
        when(criteriaBuilder.equal(statusField, ExpenseStatus.POSTED)).thenReturn(predicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = fixture.service().specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(1, predicatesCaptor.getValue().length);
    }

    /** Confirms Category and Status filters combine together with AND semantics. */
    @Test
    @SuppressWarnings("unchecked")
    void shouldCombineCategoryAndStatusFilters() {
        Fixture fixture = fixture();
        ExpenseSearchCriteria criteria = new ExpenseSearchCriteria();
        UUID categoryId = UUID.randomUUID();
        criteria.setCategoryId(categoryId);
        criteria.setStatus(ExpenseStatus.SUBMITTED);

        Root<Expense> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path<Object> categoryPath = mock(Path.class);
        Path<UUID> categoryIdPath = mock(Path.class);
        Path<ExpenseStatus> statusField = mock(Path.class);
        Predicate categoryPredicate = mock(Predicate.class);
        Predicate statusPredicate = mock(Predicate.class);
        Predicate conjunction = mock(Predicate.class);
        when(root.get("category")).thenReturn(categoryPath);
        when(categoryPath.<UUID>get("id")).thenReturn(categoryIdPath);
        when(criteriaBuilder.equal(categoryIdPath, categoryId)).thenReturn(categoryPredicate);
        when(root.<ExpenseStatus>get("status")).thenReturn(statusField);
        when(criteriaBuilder.equal(statusField, ExpenseStatus.SUBMITTED)).thenReturn(statusPredicate);
        ArgumentCaptor<Predicate[]> predicatesCaptor = ArgumentCaptor.forClass(Predicate[].class);
        when(criteriaBuilder.and(predicatesCaptor.capture())).thenReturn(conjunction);

        Predicate result = fixture.service().specificationFor(criteria).toPredicate(root, query, criteriaBuilder);

        assertEquals(conjunction, result);
        assertEquals(2, predicatesCaptor.getValue().length);
        assertEquals(List.of(categoryPredicate, statusPredicate), List.of(predicatesCaptor.getValue()));
    }

    /** Confirms an inverted date range (From Date after To Date) is detected as invalid. */
    @Test
    void shouldDetectInvalidDateRangeWhenFromDateAfterToDate() {
        ExpenseSearchCriteria criteria = new ExpenseSearchCriteria();
        criteria.setFromDate(LocalDate.of(2026, 9, 30));
        criteria.setToDate(LocalDate.of(2026, 9, 1));

        assertEquals(true, criteria.isDateRangeInvalid());
    }

    /** Confirms an equal or ascending date range is not treated as invalid. */
    @Test
    void shouldNotFlagEqualOrAscendingDateRangeAsInvalid() {
        ExpenseSearchCriteria equalRange = new ExpenseSearchCriteria();
        equalRange.setFromDate(LocalDate.of(2026, 9, 1));
        equalRange.setToDate(LocalDate.of(2026, 9, 1));
        assertFalse(equalRange.isDateRangeInvalid());

        ExpenseSearchCriteria ascendingRange = new ExpenseSearchCriteria();
        ascendingRange.setFromDate(LocalDate.of(2026, 9, 1));
        ascendingRange.setToDate(LocalDate.of(2026, 9, 30));
        assertFalse(ascendingRange.isDateRangeInvalid());
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
        when(category.isActive()).thenReturn(true);
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
