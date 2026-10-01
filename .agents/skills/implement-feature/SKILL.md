name: implement-feature
description: >
Standard workflow for implementing a new feature or extending an existing
feature in the Hotel Management System.

---

# Implement Feature

## Purpose

Use this skill when implementing a new feature or extending an existing
feature in the Hotel Management System.

Typical examples:

- Guest Management
- Reservation Management
- Room Management
- Payment Management
- Check-in / Check-out
- Search and filtering
- CRUD functionality
- Permission-controlled functionality
- Dashboard functionality

This skill defines HOW a feature must be implemented.

Business requirements must come from:

- the user's request
- existing project documentation
- existing approved behavior in the codebase

Never invent new business requirements.

---

# 1. Read Project Instructions

Before analyzing or modifying code:

1. Read `AGENTS.md`.
2. Read `docs/coding-rules/architecture.md`.
3. Read `docs/coding-rules/sql.md`.
4. Read any additional coding rules referenced by `AGENTS.md`.

All applicable project rules are mandatory.

If this skill conflicts with a higher-level project instruction,
follow the higher-level instruction.

---

# 2. Understand the Requested Feature

Identify exactly what the requested feature must do.

Determine:

- feature name
- business goal
- affected domain
- expected behavior
- permissions
- validation requirements
- UI requirements
- database requirements
- test requirements

Do not add functionality that was not requested.

Do not change existing business rules based on assumptions.

Preserve existing behavior unless the task explicitly requests a change.

---

# 3. Inspect Existing Implementation

Before writing code, inspect the existing codebase.

Look for:

- similar features
- controllers
- services
- repositories
- entities
- DTOs/forms
- mappers
- validators
- Thymeleaf templates
- security configuration
- permission checks
- exception handling
- tests
- database schema and migrations

Prefer existing project patterns over introducing new patterns.

Do not create duplicate abstractions.

---

# 4. Determine Impacted Areas

Identify which areas are actually affected.

Possible backend areas:

- controller
- service
- repository
- entity
- dto
- form
- mapper
- validator
- security
- configuration

Possible frontend areas:

- Thymeleaf templates
- fragments
- JavaScript
- CSS

Possible database areas:

- tables
- columns
- indexes
- constraints
- foreign keys
- migrations
- queries

Only modify areas required by the requested feature.

---

# 5. Create an Implementation Plan

Before modifying files, create a concise implementation plan.

The plan must include:

## Feature

Name and scope of the feature.

## Existing behavior

Relevant current behavior discovered from the codebase.

## Required changes

Exactly what must change.

## Files to create

List each new file and explain why it is required.

## Files to modify

List each existing file and explain the required modification.

## Database impact

State whether database changes are required.

## Security impact

State whether authentication, authorization, or permissions are affected.

## Tests

Describe tests that must be added or updated.

Do not start implementation before understanding the impact.

---

# 6. Implement Only the Approved Scope

Implement only the requested feature.

Follow:

- `AGENTS.md`
- `docs/coding-rules/architecture.md`
- `docs/coding-rules/sql.md`
- existing project conventions

Do not:

- refactor unrelated code
- rename unrelated classes
- reformat unrelated files
- change unrelated business logic
- introduce unnecessary libraries
- introduce unnecessary abstractions
- change architecture without explicit instruction
- modify unrelated database behavior

Keep the change set minimal and focused.

---

# 7. Backend Responsibilities

Follow the architecture defined in:

`docs/coding-rules/architecture.md`

General responsibility boundaries:

## Controller

Responsible for:

- HTTP request handling
- request validation
- response/view selection
- delegation to services

Do not place business logic in controllers.

## Service

Responsible for:

- business logic
- orchestration
- transaction boundaries

## Repository

Responsible for:

- persistence
- database access

## Entity

Responsible for:

- persistence model

## DTO / Form

Responsible for:

- data transfer
- user input

---

# 8. Database Rules

If SQL or database changes are required:

Follow:

`docs/coding-rules/sql.md`

Check:

- naming conventions
- nullability
- indexes
- constraints
- foreign keys
- uniqueness
- migration compatibility

SQL keywords must follow the project's SQL rules.

Example:

```sql
SELECT
    id,
    guest_code
FROM
    guest
WHERE
    id = :id;
Do not silently change existing database semantics.

9. Security Review
For every feature, check whether it affects:
	•	authentication
	•	authorization
	•	permissions
	•	object-level access control
	•	input validation
	•	CSRF
	•	SQL injection
	•	XSS
	•	sensitive data exposure
	•	insecure direct object references
Permission-controlled functionality must always be protected on the backend.
Do not rely only on hiding UI elements.
For example:
Bad:
	•	Hide the Delete button.
	•	Endpoint remains callable by unauthorized users.
Good:
	•	Hide the Delete button when appropriate.
	•	Verify permission again on the backend.

10. Validation
Validate all user-controlled input at the appropriate backend boundary.
Consider:
	•	required fields
	•	maximum length
	•	minimum length
	•	allowed values
	•	numeric ranges
	•	date ranges
	•	duplicate values
	•	foreign-key references
	•	malformed IDs
	•	invalid requests
Frontend validation may improve UX but must not replace backend validation.

11. Transaction Review
If an operation performs multiple related database changes,determine whether they must be atomic.
Use a transaction when partial completion could leave the systemin an invalid or inconsistent state.
Do not add transactions without understanding the business operation.

12. Concurrency and Idempotency
If the feature may receive duplicate or concurrent requests,evaluate risks such as:
	•	double submit
	•	duplicate insert
	•	race conditions
	•	repeated booking
	•	repeated payment
	•	repeated webhook processing
Use concurrency protection or idempotency only when appropriatefor the business operation.

13. Testing
Add or update only relevant tests.
Consider:
	•	successful execution
	•	validation failure
	•	unauthorized access
	•	forbidden access
	•	not-found behavior
	•	duplicate operations
	•	repository/database behavior
	•	affected business rules
Do not rewrite unrelated tests.

14. Build Verification
Before considering the feature complete:
	1	Run relevant tests.
	2	Run the project build when practical.
	3	Check compilation errors.
	4	Check test failures.
	5	Review modified files.
Do not treat the feature as complete if the project does not compile.
If a failure is unrelated to the requested feature,report it instead of modifying unrelated code.

15. Final Diff Review
Review the final changes.
Verify that:
	•	only intended source files changed
	•	unrelated files were not reformatted
	•	generated files are not included
	•	build artifacts are not included
	•	target/ is not included
	•	temporary IDE files are not included
	•	no unrelated business behavior changed

16. Final Report
After implementation, report:
Implemented
Summarize the behavior added or changed.
Files changed
List created and modified files.
Database
Describe database changes.
If none:
No database changes.
Security
Describe relevant permission and security checks.
Tests
List tests/build commands executed and their results.
Remaining issues
List unresolved issues.
If none:
No known remaining issues.

Core Rules
Always:
	•	understand before modifying
	•	inspect existing implementation
	•	follow project architecture
	•	follow coding rules
	•	keep changes minimal
	•	preserve existing business behavior
	•	validate backend input
	•	enforce permissions on the backend
	•	run relevant tests
	•	review the final diff
Never:
	•	invent business requirements
	•	perform unrelated refactoring
	•	modify unrelated code
	•	change architecture without explicit instruction
	•	silently change database behavior
	•	bypass permission checks
	•	include generated build files

After creating the file:
	1	Verify that the file exists at:.agents/skills/implement-feature/SKILL.md
	2	Show me the created file path.
	3	Show me the Git diff for this change only.
Do not make any additional changes.
```
