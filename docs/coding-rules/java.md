# Java Coding Rules

## 1. Scope

This document defines Java coding rules for the Hotel Management System.

These rules apply to:

- Java source code
- Spring Boot components
- Controllers
- Services
- Repositories
- Entities
- DTOs
- Exceptions
- Configuration classes
- Utility classes
- Tests

This document defines coding conventions only.

Business requirements must come from:

```text
docs/requirements/technical-spec-v1.md
```

Do not introduce new business behavior from this document.

---

# 2. Java Version

Use:

```text
Java 21
```

Code must compile with the Java version configured by the project.

Do not use deprecated APIs when a supported alternative already exists.

Use modern Java features when they improve readability and maintainability.

Examples:

```text
record
switch expression
pattern matching
sealed class
Optional
```

Do not use new language features only to make code shorter if readability becomes worse.

---

# 3. Code Formatting

All Java code must follow standard Java formatting.

Use:

```text
4 spaces indentation
```

Do not use TAB characters for indentation.

Correct:

```java
public class ReservationService {

    public void confirmReservation(Long reservationId) {
        // implementation
    }
}
```

Incorrect:

```java
public class ReservationService
{
public void confirmReservation(Long reservationId)
{
// implementation
}
}
```

---

# 4. Braces

Always use braces for control statements.

Correct:

```java
if (reservation.isConfirmed()) {
    processReservation();
}
```

Incorrect:

```java
if (reservation.isConfirmed())
    processReservation();
```

This rule applies to:

```text
if
else
for
while
do-while
```

---

# 5. Line Length

Keep lines reasonably short.

Preferred maximum:

```text
120 characters
```

Break long statements into multiple readable lines.

Example:

```java
Reservation reservation =
        reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
```

---

# 6. Naming Convention

## 6.1 Classes

Use PascalCase.

Correct:

```text
ReservationService
ReservationController
PaymentService
AccountingEntry
RoomRepository
```

Incorrect:

```text
reservationService
reservation_service
Reservation_Service
```

---

## 6.2 Methods

Use camelCase.

Method names must describe the action being performed.

Correct:

```text
createReservation()
confirmReservation()
findReservationById()
calculateOutstandingBalance()
checkIn()
checkOut()
```

Avoid vague names such as:

```text
process()
handle()
execute()
doSomething()
```

unless the surrounding abstraction makes the meaning explicit.

---

## 6.3 Variables

Use camelCase.

Correct:

```text
reservationId
checkInDate
totalAmount
outstandingBalance
```

Incorrect:

```text
reservation_id
ReservationID
TOTAL_AMOUNT
```

---

## 6.4 Constants

Use UPPER_SNAKE_CASE.

Example:

```java
private static final int MAX_RETRY_COUNT = 3;
```

---

## 6.5 Boolean Names

Boolean variables and methods should preferably use prefixes such as:

```text
is
has
can
should
```

Examples:

```java
isAvailable()
hasOutstandingBalance()
canCheckIn()
isActive()
```

---

# 7. Package Naming

Package names must:

- Use lowercase.
- Follow the project architecture defined in `docs/architecture.md`.
- Never use uppercase letters.
- Never use underscores unless explicitly required.

Example:

```text
com.example.hotel.controller.reservation
com.example.hotel.service.reservation
com.example.hotel.repository.reservation
com.example.hotel.entity.reservation
```

Do not create alternative package structures that conflict with `architecture.md`.

---

# 8. Class Responsibility

Each class should have one clear responsibility.

Avoid classes that contain unrelated behavior.

Incorrect:

```text
ReservationService
├── Reservation logic
├── Payment logic
├── Expense logic
├── Accounting logic
└── User management
```

Prefer:

```text
ReservationService
PaymentService
ExpenseService
AccountingService
UserService
```

---

# 9. Class Member Order

Use the following order when practical:

```text
1. Constants
2. Static fields
3. Instance fields
4. Constructors
5. Public methods
6. Protected methods
7. Package-private methods
8. Private methods
```

Keep related methods close together.

---

# 10. Javadoc for Classes

Every Java class and interface must have Javadoc.

This includes:

```text
Controller
Service
Repository
Entity
DTO
Configuration
Exception
Utility
Enum
Test helper
```

Example:

```java
/**
 * Provides application operations for hotel reservations.
 *
 * <p>This service coordinates reservation-related business operations
 * and delegates persistence to the reservation repository.</p>
 */
@Service
public class ReservationService {
}
```

Javadoc must explain the class purpose.

Do not write meaningless documentation such as:

```java
/**
 * Reservation service.
 */
```

when the class responsibility can be described more clearly.

---

# 11. Javadoc for Methods

Every method must have Javadoc.

This project requires Javadoc for:

```text
public
protected
package-private
private
```

methods.

Example:

```java
/**
 * Finds a reservation by its identifier.
 *
 * @param reservationId the reservation identifier
 * @return the matching reservation
 * @throws ReservationNotFoundException if no reservation exists for the identifier
 */
public Reservation findById(Long reservationId) {
    // implementation
}
```

---

# 12. Javadoc Parameters

Use `@param` for every method parameter.

Example:

```java
/**
 * Calculates the outstanding balance for a stay.
 *
 * @param stayId the stay identifier
 * @return the outstanding balance
 */
public BigDecimal calculateOutstandingBalance(Long stayId) {
    // implementation
}
```

Parameter descriptions must describe meaning, not only repeat the parameter name.

---

# 13. Javadoc Return Values

If a method returns a value, document it with:

```text
@return
```

Example:

```java
/**
 * Determines whether the room can currently be used.
 *
 * @return {@code true} when the room is available for use
 */
public boolean isAvailable() {
    // implementation
}
```

---

# 14. Javadoc Exceptions

Document business or meaningful technical exceptions using:

```text
@throws
```

Example:

```java
/**
 * Confirms the specified reservation.
 *
 * @param reservationId the reservation identifier
 * @throws ReservationNotFoundException if the reservation does not exist
 * @throws InvalidReservationStateException if confirmation is not allowed
 */
public void confirmReservation(Long reservationId) {
    // implementation
}
```

---

# 15. Javadoc Quality

Javadoc must describe intent and important behavior.

Avoid documentation that only repeats the method name.

Incorrect:

```java
/**
 * Confirms reservation.
 */
public void confirmReservation(Long id) {
}
```

Better:

```java
/**
 * Confirms a reservation after the required reservation validations succeed.
 *
 * @param reservationId the reservation identifier
 * @throws InvalidReservationStateException if the reservation cannot be confirmed
 */
public void confirmReservation(Long reservationId) {
}
```

---

# 16. Inline Comments

Comments should explain:

```text
WHY
```

rather than simply:

```text
WHAT
```

Incorrect:

```java
// Set status
status = ReservationStatus.CONFIRMED;
```

Better:

```java
// Status transitions are performed through the domain method
// so invalid state changes cannot bypass validation.
reservation.confirm();
```

Do not add comments for obvious code.

---

# 17. No Commented-Out Code

Do not keep old implementation as comments.

Incorrect:

```java
// old implementation
// reservation.setStatus(...);
// repository.save(...);
```

Delete unused code.

Git is responsible for source history.

---

# 18. Imports

Do not use wildcard imports.

Incorrect:

```java
import java.util.*;
```

Correct:

```java
import java.util.List;
import java.util.Optional;
```

Remove unused imports.

---

# 19. Dependency Injection

Prefer constructor injection.

Correct:

```java
@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;

    /**
     * Creates a reservation service.
     *
     * @param reservationRepository repository used to access reservations
     */
    public ReservationService(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }
}
```

Do not use field injection.

Avoid:

```java
@Autowired
private ReservationRepository reservationRepository;
```

---

# 20. Controller Rules

Controllers are responsible for:

```text
HTTP request handling
Request DTO validation
Calling application services
Returning responses/views
```

Controllers must not contain business logic.

Incorrect:

```java
@PostMapping("/{id}/confirm")
public void confirm(@PathVariable Long id) {
    Reservation reservation = repository.findById(id).orElseThrow();

    if (reservation.getStatus() == ReservationStatus.DRAFT) {
        reservation.setStatus(ReservationStatus.CONFIRMED);
    }

    repository.save(reservation);
}
```

Correct:

```java
@PostMapping("/{id}/confirm")
public ResponseEntity<Void> confirm(@PathVariable Long id) {
    reservationService.confirmReservation(id);

    return ResponseEntity.noContent().build();
}
```

---

# 21. Service Rules

Services are responsible for application/business orchestration.

Typical flow:

```text
Controller
    ↓
Service
    ↓
Domain / Entity
    ↓
Repository
```

Service methods should clearly represent use cases.

Examples:

```text
createReservation()
confirmReservation()
cancelReservation()
checkIn()
checkOut()
recordPayment()
```

Do not create generic business methods such as:

```text
updateStatus()
processData()
handleRequest()
```

when a more explicit name is possible.

---

# 22. Repository Rules

Repositories are responsible for data access only.

Examples:

```text
findById()
findByGuestId()
findOverlappingReservations()
save()
```

Do not place business state transitions inside repository implementations.

Repository code must not decide whether a reservation can:

```text
confirm
check-in
check-out
cancel
```

That responsibility belongs to the business/application layer.

---

# 23. Entity Rules

Entities must protect important domain state where appropriate.

Avoid treating an entity as only a collection of public setters.

Incorrect:

```java
reservation.setStatus(ReservationStatus.CHECKED_IN);
```

Prefer explicit domain operations:

```java
reservation.checkIn();
```

The domain method must enforce the state transition rules defined by the business specification.

---

# 24. Business State Setters

Do not expose unrestricted public setters for controlled business state.

This applies especially to:

```text
ReservationStatus
RoomStatus
PaymentStatus
AccountingEntryStatus
```

Avoid:

```java
public void setStatus(ReservationStatus status) {
    this.status = status;
}
```

if doing so allows business rules to be bypassed.

Use explicit transition methods instead.

---

# 25. Enum Rules

Use enums for predefined business states and types.

Correct:

```java
public enum ReservationStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED,
    NO_SHOW,
    CHECKED_IN,
    CHECKED_OUT
}
```

Do not compare business states using strings.

Incorrect:

```java
if ("CONFIRMED".equals(reservation.getStatus())) {
}
```

Correct:

```java
if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
}
```

---

# 26. DTO Rules

Do not expose JPA entities directly as REST request or response contracts.

Use DTOs.

Examples:

```text
ReservationCreateRequest
ReservationResponse
PaymentCreateRequest
PaymentResponse
```

Java records may be used for immutable DTOs when appropriate.

Example:

```java
public record ReservationCreateRequest(
        Long guestId,
        LocalDate checkInDate,
        LocalDate checkOutDate
) {
}
```

DTOs must contain only fields needed by the operation.

---

# 27. Request Validation

Use Bean Validation for structural request validation.

Example:

```java
public record ReservationCreateRequest(

        @NotNull
        Long guestId,

        @NotNull
        LocalDate checkInDate,

        @NotNull
        LocalDate checkOutDate
) {
}
```

Bean Validation does not replace business validation.

Business validation belongs in service/domain logic.

---

# 28. Audit Fields

Java audit field names must use:

```text
createdAt
createdBy
updatedAt
updatedBy
```

Database columns use:

```text
created_at
created_by
updated_at
updated_by
```

Do not use names such as:

```text
createId
createdId
updateId
```

unless explicitly required by an external interface.

---

# 29. Audit Field Security

Audit user fields must never be trusted from client input.

Do not accept:

```text
createdBy
updatedBy
deletedBy
```

from API or Thymeleaf form requests.

The backend must determine the current authenticated user.

Conceptually:

```text
Request
  ↓
Authentication
  ↓
Current User
  ↓
Service / Auditing
  ↓
createdBy / updatedBy
```

---

# 30. Base Auditable Entity

Shared audit fields should be centralized when appropriate.

Example structure:

```java
@MappedSuperclass
public abstract class BaseAuditableEntity {

    private LocalDateTime createdAt;

    private Long createdBy;

    private LocalDateTime updatedAt;

    private Long updatedBy;
}
```

The actual implementation must remain consistent with the project's persistence architecture.

Do not expose audit setters to client-controlled DTOs.

---

# 31. Date and Time Types

Prefer Java Time API.

Use types such as:

```text
LocalDate
LocalDateTime
Instant
```

according to the domain meaning.

Do not use legacy:

```text
java.util.Date
java.sql.Date
```

unless required by an external API or existing project integration.

---

# 32. Monetary Values

Use:

```java
BigDecimal
```

for monetary values.

Never use:

```text
float
double
```

for hotel prices, charges, payments, expenses, accounting amounts, or other monetary values.

Incorrect:

```java
double amount;
```

Correct:

```java
BigDecimal amount;
```

---

# 33. BigDecimal Comparison

Do not compare `BigDecimal` using `==`.

Do not rely on `equals()` when scale differences should not affect numeric comparison.

Prefer:

```java
amount.compareTo(BigDecimal.ZERO) > 0
```

instead of:

```java
amount.equals(BigDecimal.ZERO)
```

where numeric comparison is intended.

---

# 34. Monetary Rounding

Do not invent rounding rules.

If rounding behavior is required but not defined by the business specification, do not create new business behavior.

Use explicit scale and rounding only where the requirement already determines it.

---

# 35. Null Handling

Avoid returning `null` when absence is part of the method contract.

Repository lookup methods may return:

```java
Optional<T>
```

Example:

```java
Optional<Reservation> findById(Long id);
```

Application services may convert missing data into a meaningful exception.

Example:

```java
return reservationRepository.findById(reservationId)
        .orElseThrow(() -> new ReservationNotFoundException(reservationId));
```

---

# 36. Optional Rules

Do not use `Optional` as a JPA entity field.

Avoid:

```java
private Optional<String> email;
```

Use `Optional` mainly for method return values representing possible absence.

Do not use `Optional.get()` without a prior safe check.

---

# 37. Exception Rules

Do not use generic exceptions for business errors.

Avoid:

```java
throw new RuntimeException("Error");
```

Use meaningful exception types.

Examples:

```text
ReservationNotFoundException
InvalidReservationStateException
RoomUnavailableException
OutstandingBalanceException
```

Only create new exception types when they are required by existing behavior.

Do not invent new business scenarios just to create more exception classes.

---

# 38. Exception Messages

Exception messages must contain useful diagnostic context without exposing sensitive information.

Incorrect:

```java
throw new RuntimeException("Invalid");
```

Better:

```java
throw new InvalidReservationStateException(
        "Reservation " + reservationId
                + " cannot be confirmed from status "
                + reservation.getStatus());
```

Do not expose:

```text
password
tokens
secrets
database credentials
```

in exception messages.

---

# 39. Global Exception Handling

Where the project uses centralized error handling, use the existing exception handler pattern.

Do not create inconsistent error-handling mechanisms in individual controllers.

Controllers should not repeatedly contain:

```java
try {
    ...
} catch (Exception ex) {
    ...
}
```

when centralized handling already applies.

---

# 40. Transaction Rules

Business operations affecting multiple persistent entities must use an appropriate transaction boundary.

Place transaction boundaries in the service/application layer.

Example:

```java
@Transactional
public void checkIn(Long reservationId) {
    // business operation
}
```

Do not add `@Transactional` randomly to controllers or every method.

Read-only queries may use read-only transactions where consistent with the project's architecture.

---

# 41. Transaction Atomicity

A business operation that must succeed or fail as a unit must be atomic.

Example:

```text
Check-in
├── Reservation state change
├── Stay creation
├── Room state change
└── Audit update
```

If the operation fails, partial state must not remain committed.

---

# 42. State Machine Rules

Business state transitions must follow:

```text
docs/requirements/technical-spec-v1.md
```

Do not create additional transitions.

Do not bypass state validation.

Incorrect:

```java
reservation.setStatus(ReservationStatus.CHECKED_OUT);
```

Correct conceptually:

```java
reservation.checkOut();
```

or through the appropriate application service.

---

# 43. Security Rules

For protected operations, maintain the logical order:

```text
Authentication
    ↓
Authorization
    ↓
State validation
    ↓
Business validation
    ↓
Transaction
```

Do not bypass authorization for convenience.

Do not weaken security annotations or checks merely to make tests pass.

---

# 44. Logging

Do not use:

```java
System.out.println();
System.err.println();
```

Use the project's logging framework.

Example:

```java
private static final Logger log =
        LoggerFactory.getLogger(ReservationService.class);
```

---

# 45. Logging Levels

Use appropriate logging levels.

```text
ERROR
WARN
INFO
DEBUG
TRACE
```

Do not log normal business rejection as `ERROR` unless it represents an actual unexpected system failure.

---

# 46. Sensitive Logging

Never log sensitive values such as:

```text
password
password hash
access token
refresh token
API secret
database password
payment credentials
```

Do not log complete authentication objects unnecessarily.

---

# 47. Magic Numbers

Do not use unexplained magic numbers.

Incorrect:

```java
if (retryCount > 3) {
}
```

Correct:

```java
private static final int MAX_RETRY_COUNT = 3;
```

Do not create constants for values that are business configuration unless the existing requirement explicitly defines them.

---

# 48. Magic Strings

Do not use repeated business strings where a defined enum or constant exists.

Incorrect:

```java
if ("AGODA".equals(source)) {
}
```

Correct:

```java
if (BookingSource.AGODA == source) {
}
```

---

# 49. Method Size

Methods should remain focused and readable.

Avoid large methods containing multiple unrelated responsibilities.

Instead of:

```text
checkOut() → 200 lines
```

prefer logical extraction such as:

```text
findStay()
validateCheckOut()
calculateOutstandingBalance()
completeCheckOut()
```

Do not split code into tiny methods solely to reduce line count when readability becomes worse.

---

# 50. Method Parameters

Avoid methods with excessive unrelated parameters.

If a method requires many related inputs, consider an appropriate request/value object when consistent with the architecture.

Do not introduce unnecessary wrapper classes solely to satisfy this rule.

---

# 51. Method Return Values

Methods that perform commands should not return unnecessary data.

Queries should return the data required by the caller.

Avoid using return values as hidden side-channel status codes when exceptions or explicit types already represent the result clearly.

---

# 52. Streams

Use Stream API when it improves readability.

Good:

```java
List<Long> ids = reservations.stream()
        .map(Reservation::getId)
        .toList();
```

Do not create deeply nested stream chains that are difficult to understand.

Prefer a normal loop when it is clearer.

---

# 53. Loops

Do not replace a simple readable loop with complex functional code solely for style.

Readable code has priority over fewer lines.

---

# 54. Lombok

If Lombok is already used by the project, use it carefully.

Do not use Lombok annotations that expose unrestricted setters for controlled domain state.

Avoid:

```java
@Data
public class Reservation {
}
```

for rich domain entities when it exposes inappropriate setters.

Prefer only the Lombok annotations actually needed.

Do not introduce Lombok if the project does not already use it unless explicitly requested.

---

# 55. Equality

Implement `equals()` and `hashCode()` carefully for entities and value objects.

Do not automatically include mutable business fields in entity equality.

Follow the existing persistence design.

Do not generate entity equality methods blindly.

---

# 56. toString()

Do not include sensitive fields in `toString()`.

Be careful when generating `toString()` for entities with bidirectional relationships because it may cause:

```text
recursive calls
large logs
lazy-loading problems
```

---

# 57. Collections

Prefer interface types.

Correct:

```java
List<Reservation> reservations;
Set<Role> roles;
```

Avoid unnecessarily declaring:

```java
ArrayList<Reservation> reservations;
```

unless implementation-specific behavior is needed.

---

# 58. Mutable Collections

Do not expose mutable internal collections unnecessarily.

Business collections should be modified through controlled methods when invariants must be protected.

Example concept:

```java
reservation.addRoom(room);
```

rather than external code manipulating the internal collection directly.

---

# 59. Repository Queries

Repository query method names must be readable.

Avoid extremely long derived query method names when a clearer explicit query or repository method improves maintainability.

SQL-specific rules must follow:

```text
docs/coding-rules/sql.md
```

---

# 60. No Business Logic in Mapping Code

DTO/entity mapper code must only map data.

Do not hide business rules inside:

```text
Mapper
Converter
Assembler
```

Business decisions belong to service/domain code.

---

# 61. Utility Classes

Do not create generic utility classes as a dumping ground.

Avoid classes such as:

```text
CommonUtils
Helper
Utils
GeneralService
```

unless there is a clear, cohesive responsibility.

Utility classes must have a specific purpose.

---

# 62. Static Methods

Do not use static methods as a replacement for proper dependency-managed services.

Static utility methods are acceptable for pure stateless transformations where appropriate.

---

# 63. Configuration

Configuration values must not be hard-coded when they are environment-specific.

Use the project's Spring configuration mechanism.

Do not hard-code:

```text
database URLs
credentials
external service secrets
environment-specific hosts
```

in Java source files.

---

# 64. Secrets

Never commit secrets into Java code.

Examples:

```text
password
API key
secret key
token
private key
```

Use the project's approved configuration mechanism.

---

# 65. Dead Code

Do not leave:

```text
unused methods
unused fields
unused imports
unused classes
unreachable branches
commented-out implementation
```

Remove dead code before considering the task complete.

---

# 66. TODO and FIXME

Do not leave unresolved:

```text
TODO
FIXME
HACK
```

in completed implementation unless explicitly requested.

If a business requirement is missing:

```text
Do not invent the requirement.
```

Report the missing requirement instead.

---

# 67. Tests

Java business logic must have appropriate tests.

For important business operations, cover at least:

```text
happy path
invalid state
business rule violation
authorization failure where applicable
```

Do not remove existing tests solely because generated implementation fails them.

---

# 68. Test Naming

Test names must describe behavior clearly.

Preferred format:

```java
@Test
void shouldCheckInConfirmedReservationWhenRoomIsAvailable() {
}
```

Examples:

```java
shouldRejectCheckInWhenReservationIsCancelled()

shouldRejectCheckOutWhenOutstandingBalanceExists()

shouldCreateReservationWhenInputIsValid()
```

---

# 69. Test Structure

Tests should be readable using:

```text
Given
When
Then
```

conceptually.

Comments for these sections are optional if the test structure is already clear.

Avoid excessive setup inside each test.

---

# 70. Unit Tests

Unit tests should focus on business behavior of the unit being tested.

Mock only external collaborators required by the test.

Do not mock the method under test.

---

# 71. Integration Tests

Use integration tests when behavior depends on:

```text
database constraints
transactions
JPA mappings
repository queries
concurrency
```

Follow the existing project test infrastructure.

---

# 72. No Test-Only Production Behavior

Do not modify production logic solely to make tests easier.

Do not weaken:

```text
authorization
validation
state machine
database constraints
```

for tests.

---

# 73. Security Test Rule

Important protected operations should verify that unauthorized access cannot bypass backend authorization.

Frontend visibility is not considered sufficient authorization protection.

---

# 74. No Unrelated Refactoring

When implementing a task:

```text
modify only what is necessary for the requested change
```

Do not perform broad unrelated refactoring.

Do not rename unrelated classes, packages, methods, or fields unless required.

---

# 75. Preserve Existing Behavior

Refactoring must preserve existing business behavior unless the requirement explicitly changes it.

Do not reinterpret requirements while refactoring code.

---

# 76. No New Business Behavior

Codex must never infer new hotel business rules from:

```text
method names
database columns
UI assumptions
common hotel practices
personal preference
```

If business behavior is not defined in the specification:

```text
DO NOT INVENT IT.
```

---

# 77. Rule Priority

If rules conflict, apply the following priority:

```text
Business Specification
        ↓
Security / Business Invariants
        ↓
Architecture
        ↓
Data Integrity
        ↓
Java Coding Rules
        ↓
Implementation Preference
```

Coding style must never override business, security, or data-integrity requirements.

---

# 78. Code Review Checklist

Before considering Java changes complete, verify:

```text
[ ] Code compiles
[ ] Java formatting is correct
[ ] Package structure follows architecture.md
[ ] Class names follow naming rules
[ ] Method names are meaningful
[ ] Every class has Javadoc
[ ] Every method has Javadoc
[ ] @param is documented where applicable
[ ] @return is documented where applicable
[ ] @throws is documented where applicable
[ ] No wildcard imports
[ ] No unused imports
[ ] No System.out / System.err
[ ] No sensitive information is logged
[ ] Controller contains no business logic
[ ] Repository contains no business logic
[ ] Service transaction boundaries are appropriate
[ ] Controlled business states cannot be freely modified
[ ] DTOs are used instead of exposing entities directly
[ ] Client cannot control audit fields
[ ] Monetary values use BigDecimal
[ ] Enums are used instead of magic business strings
[ ] No generic RuntimeException for defined business failures
[ ] No dead code
[ ] No commented-out old implementation
[ ] No unresolved TODO/FIXME
[ ] Tests cover the relevant behavior
[ ] Existing tests still pass
[ ] No undocumented business behavior was added
```

---

# 79. Codex Instructions

Before creating or modifying Java code, Codex must read:

```text
AGENTS.md
docs/architecture.md
docs/requirements/technical-spec-v1.md
docs/coding-rules/java.md
```

If the task also affects the database, read:

```text
docs/coding-rules/sql.md
```

If the task also affects frontend code, read:

```text
docs/coding-rules/frontend.md
```

Codex must follow the existing backend implementation and specification.

Do not rewrite unrelated code.

Do not add dependencies unless required by the requested implementation.

Do not introduce new frameworks or architectural patterns without explicit instruction.

---

# 80. Definition of Done

Java implementation is complete only when:

```text
✓ Code compiles successfully
✓ Java formatting is correct
✓ Architecture rules are followed
✓ Javadoc exists for every class
✓ Javadoc exists for every method
✓ Business behavior matches the specification
✓ Security rules are preserved
✓ State transitions cannot be bypassed
✓ Audit fields are server-controlled
✓ Transaction boundaries are correct
✓ Monetary values use BigDecimal
✓ Exceptions are meaningful
✓ Logging is safe
✓ Tests pass
✓ No dead code remains
✓ No unrelated changes were introduced
✓ No new business requirements were invented
```
