# Frontend Coding Rules

## 1. Scope

This document defines frontend coding conventions for the Hotel Management System.

The frontend uses:

- Thymeleaf
- HTML
- CSS
- JavaScript when necessary
- Spring Boot MVC

This document defines coding and structural rules only.

Business requirements must come from the project business/technical specification.

Do not introduce new business behavior from this document.

---

## 2. Source Structure

Frontend files must be placed under:

```text
src/main/resources/

├── templates/
│   ├── layout/
│   ├── dashboard/
│   ├── customer/
│   ├── room/
│   ├── reservation/
│   ├── stay/
│   ├── payment/
│   ├── expense/
│   └── error/
│
└── static/
    ├── css/
    ├── js/
    └── images/
```

Templates must be grouped by business domain.

Do not place HTML files inside Java packages.

---

## 3. Template Naming

Use lowercase file names.

Preferred names:

```text
list.html
detail.html
form.html
index.html
```

Example:

```text
templates/
└── reservation/
    ├── list.html
    ├── detail.html
    └── form.html
```

Do not create inconsistent names such as:

```text
ReservationList.html
reservation_detail_page.html
showReservation.html
```

---

## 4. Reusable Layout

Common UI components must be implemented as reusable Thymeleaf fragments.

Use:

```text
templates/layout/
├── base.html
├── header.html
├── sidebar.html
└── footer.html
```

Do not duplicate header, sidebar, navigation, or footer markup across pages.

---

## 5. Backend Mapping

Every functional frontend action must map to an existing backend operation.

Before implementing a page or action, verify:

```text
Frontend
    ↓
Controller
    ↓
Service
    ↓
Existing business operation
```

Do not create frontend functionality for business operations that do not exist in the backend.

Do not modify backend business behavior merely to support a newly invented UI action.

---

## 6. Business Logic

Business logic must not be implemented in HTML, Thymeleaf, CSS, or JavaScript.

Frontend logic may handle presentation behavior only.

For example, frontend validation may improve user experience, but backend validation remains the source of truth.

---

## 7. State Management

Frontend must respect the backend state machines.

Do not allow direct editing of business status fields.

Do not create generic status controls such as:

```html
<select name="status"></select>
```

for:

```text
Reservation
Room
Payment
AccountingEntry
```

when the status represents a controlled business transition.

Use explicit business actions instead.

Example:

```text
Confirm
Check-in
Check-out
Cancel
Refund
```

These actions must call their corresponding backend operations.

---

## 8. State-Based Actions

Only display an action when that action is valid for the current state.

For Reservation:

```text
DRAFT
→ Confirm

CONFIRMED
→ Check-in
→ Cancel

CHECKED_IN
→ Check-out

CHECKED_OUT
→ no state-changing action

CANCELLED
→ no Check-in

NO_SHOW
→ no Check-in
```

Frontend state checks are for usability only.

Backend state validation remains mandatory.

---

## 9. Authorization

Frontend must respect the current user's permissions.

Actions that the current user is not authorized to perform should not be displayed.

However:

> Hiding a button is not a security mechanism.

Backend authorization must always remain enforced.

Never rely on frontend authorization alone.

---

## 10. Audit Fields

Frontend must never allow users to submit:

```text
createdBy
updatedBy
deletedBy

created_by
updated_by
deleted_by
```

These values must be determined by the backend from the authenticated user.

---

## 11. Sensitive Data

Never render or expose:

```text
password
password_hash
authentication token
secret
payment credentials
```

Do not store sensitive authentication information in frontend JavaScript.

---

## 12. Forms

Forms must have:

- Clear labels
- Appropriate input types
- Validation error display
- Clear submit action
- Clear cancel/back navigation when applicable

Field names must map clearly to the corresponding request DTO.

Do not submit fields that the backend does not accept.

---

## 13. Validation

Use HTML/Thymeleaf validation for user experience where appropriate.

Example:

```html
<input type="date" required />
```

But frontend validation must never replace:

```text
Bean Validation
Business validation
Database constraints
```

---

## 14. Error Messages

Validation and business errors must be displayed clearly to the user.

Do not expose:

```text
Java stack traces
SQL statements
database exception details
internal class names
security internals
```

to end users.

---

## 15. Success Messages

After successful business operations, display an appropriate confirmation message when needed.

Examples:

```text
Reservation created successfully.
Reservation confirmed successfully.
Check-in completed successfully.
Payment recorded successfully.
```

Messages must reflect actual backend results.

Do not display success before the backend operation succeeds.

---

## 16. HTML Formatting

HTML must be consistently formatted.

Use readable indentation.

Example:

```html
<form method="post">
  <div>
    <label for="guestId">Guest</label>
    <input id="guestId" name="guestId" type="text" required />
  </div>

  <button type="submit">Save</button>
</form>
```

Avoid compressed or single-line HTML that reduces readability.

---

## 17. CSS

Application-specific CSS must be placed under:

```text
src/main/resources/static/css/
```

Do not add large amounts of inline CSS to individual HTML pages.

Common styles must be reusable.

Avoid duplicating the same CSS across templates.

---

## 18. JavaScript

JavaScript must be placed under:

```text
src/main/resources/static/js/
```

when the code is reusable or non-trivial.

Do not use JavaScript to reimplement backend business rules.

JavaScript may be used for presentation behavior such as:

```text
confirmation dialogs
UI interactions
form usability
```

when necessary.

---

## 19. JavaScript Scope

Avoid global variables.

Functions must have clear names.

Do not create unnecessary JavaScript when Thymeleaf/HTML can handle the requirement cleanly.

---

## 20. URLs

Do not hard-code application host names.

Do not write URLs such as:

```text
http://localhost:8080/reservations
```

Use application-relative or Thymeleaf-generated URLs.

Example:

```html
th:href="@{/reservations}"
```

---

## 21. Thymeleaf Expressions

Keep Thymeleaf expressions simple and readable.

Do not put complex business calculations inside templates.

Avoid:

```text
large nested conditions
financial calculations
business validation
state-transition logic
```

inside Thymeleaf expressions.

Complex data must be prepared by the backend.

---

## 22. Domain Separation

Templates must remain grouped by domain.

Example:

```text
customer/
room/
reservation/
stay/
payment/
expense/
```

Do not place unrelated pages together simply because they share similar UI.

Reusable presentation components belong under:

```text
layout/
```

or another shared frontend location if explicitly defined by the project architecture.

---

## 23. Controllers

Frontend MVC controllers must follow the project's Java package architecture and Java coding convention.

Controllers must not contain business logic.

Expected flow:

```text
Thymeleaf Page
      ↓
Controller
      ↓
Service
      ↓
Business Logic
```

Not:

```text
Controller
      ↓
Repository
      ↓
Database
```

for business operations.

---

## 24. UI Actions

Button and action names must clearly describe the operation.

Prefer:

```text
Create Reservation
Confirm
Check-in
Check-out
Cancel
Record Payment
```

Avoid ambiguous labels such as:

```text
Execute
Process
Run
Do
```

---

## 25. Destructive or Sensitive Actions

Actions such as cancellation, refund, or other sensitive operations must not be triggered accidentally.

Where confirmation is required by the existing business flow, the UI must clearly communicate the action before submission.

Do not invent additional approval business flows that are not defined in the specification.

---

## 26. Empty States

Lists should handle empty results cleanly.

Example:

```text
No reservations found.
```

Do not render broken or misleading tables when no records exist.

---

## 27. Responsive Layout

Pages should remain usable on common desktop and tablet screen sizes.

Do not introduce a separate mobile application or mobile-specific business flow.

---

## 28. Accessibility Basics

Forms should associate labels with inputs.

Buttons and links should have understandable text.

Images that communicate information should have appropriate alternative text.

Do not rely only on color to communicate important state.

---

## 29. Comments

HTML/JavaScript comments should explain non-obvious intent.

Do not add comments that simply repeat the code.

Do not leave large blocks of commented-out frontend code.

---

## 30. No Dead Frontend Code

Do not keep:

```text
unused CSS
unused JavaScript
unused templates
commented-out old implementation
dead buttons
links to nonexistent pages
```

---

## 31. No Placeholder Features

Do not create fake functional pages for backend functionality that has not been implemented.

For example, if OTA synchronization is not implemented, do not create an OTA synchronization screen that appears operational.

---

## 32. Frontend Definition of Done

Frontend implementation is complete only when:

```text
✓ Templates follow the defined directory structure
✓ HTML is properly formatted
✓ Reusable layout components are not duplicated
✓ Every functional action maps to an existing backend operation
✓ Business state cannot be edited directly
✓ State-dependent actions are displayed correctly
✓ Permission-dependent actions are handled correctly
✓ Audit fields are not client-controlled
✓ Sensitive data is not exposed
✓ Validation errors are displayed
✓ Backend errors are handled safely
✓ No business logic is duplicated in JavaScript
✓ No dead links exist
✓ No placeholder business features were invented
✓ Existing backend tests still pass
```

---

## 33. Codex Rule

Before creating or modifying frontend code, Codex must read:

```text
AGENTS.md
docs/architecture.md
docs/requirements/technical-spec-v1.md
docs/coding-rules/java.md
docs/coding-rules/sql.md
docs/coding-rules/frontend.md
```

The business specification remains the source of truth for business behavior.

This file defines frontend coding conventions only.

If a requested frontend function requires backend behavior that does not exist:

> Do not invent the backend business behavior.

Report the missing mapping instead.

---

## 34. Priority

If rules conflict, use:

```text
Business Specification
        ↓
Security Rules
        ↓
Architecture
        ↓
Java / SQL Rules
        ↓
Frontend Rules
        ↓
Visual Preference
```

A visual or frontend preference must never override business, security, or data-integrity requirements.
