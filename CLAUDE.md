# CLAUDE.md

Entry point for Claude when working on this repository. This file does not restate the
business specification or the coding rules — it tells you which documents govern the work
and how to apply them.

## 1. Project

Hotel Management System: Java / Spring Boot / Spring Security / Spring Data JPA /
Thymeleaf / PostgreSQL, built with Maven (`pom.xml` at the repository root).

Backend follows **package-by-layer** under `com.example.hotel`
(`controller`, `service`, `repository`, `entity`, `dto`, `mapper`, `exception`,
`security`, `config`, `common`). Frontend is Thymeleaf templates plus static CSS/JS
organised **by domain**.

## 2. Required Reading

Read in this order before planning or writing any change:

1. [AGENTS.md](AGENTS.md)
2. [docs/requirements/technical-spec-v1.md](docs/requirements/technical-spec-v1.md)
3. [docs/coding-rules/architecture.md](docs/coding-rules/architecture.md)
4. [docs/coding-rules/java.md](docs/coding-rules/java.md)
5. [docs/coding-rules/sql.md](docs/coding-rules/sql.md)
6. [docs/coding-rules/frontend.md](docs/coding-rules/frontend.md)

Always read 1–3. Read 4–6 for the layers the task actually touches (Java, SQL/database,
frontend). For cross-layer tasks, read every rule document relevant to those layers.

Follow those documents as written. Do not copy their content into this file or into code
comments, and do not restate their rules back to the user in place of applying them.

## 3. Source of Truth

- `docs/requirements/technical-spec-v1.md` is the **business source of truth**.
- **Existing code is not automatically the business source of truth.** It may be
  incomplete, in progress, or wrong.
- If the implementation conflicts with the approved specification, **report the conflict**
  — state both sides and ask. Do not silently pick one and do not "fix" code to match your
  reading of the spec without saying so.
- If a requirement is missing or unclear, **do not invent business behavior**. Do not infer
  hotel business rules from field names, table columns, UI affordances, or common industry
  practice. Report the gap.

Instruction priority when guidance conflicts:

```text
1. Business requirements (technical-spec-v1.md)
2. Security rules and business invariants
3. Architecture (architecture.md)
4. Java / SQL coding rules
5. Frontend coding rules
6. Existing implementation / stylistic preference
```

## 4. Working Rules

- **Inspect before changing.** Read the relevant implementation and its existing tests
  before editing anything.
- Make the **smallest coherent change** needed for the requested task.
- **No unrelated refactoring.** Do not rename, move, or restructure code that the task does
  not require.
- Preserve existing behavior unless the requirement explicitly changes it.
- Do not add dependencies, frameworks, or architectural patterns without explicit
  instruction.
- Do not modify production logic just to make tests pass, and do not delete failing tests.

## 5. Architecture

Follow `docs/coding-rules/architecture.md` exactly.

- Layer direction: Controller → Service → Repository → Entity/DB. No shortcuts.
- **MVC controllers must call services. They must not call the application's own REST
  APIs**, and must not call repositories for business operations.
- Controllers hold no business logic; business operations live in services / domain logic
  with explicit use-case method names.
- Do not create a new layer, business domain, or package, and do not move or rename
  existing packages, without explicit approval.
- Frontend resources mirror backend domains (`templates/{domain}/`,
  `static/css/{domain}/`, `static/js/{domain}/`).

## 6. Security

- Order for every protected operation: authentication → authorization → state validation →
  business validation → transaction → audit.
- **Do not weaken authorization to make a feature work**, to simplify a flow, or to make a
  test pass. Hiding a UI control is not authorization.
- Audit fields (`created_by` / `updated_by`) are server-controlled and must never be
  accepted from the client.
- Parameterize all queries; never concatenate user input into SQL.
- Never log or expose sensitive data; no secrets in source or committed configuration.

## 7. Business State

- **Code must implement the approved state machines, not redefine them.** Reservation,
  Room, Payment and AccountingEntry transitions are defined in the specification.
- Do not add transitions, do not bypass state validation, and do not set business status
  fields directly — go through the corresponding business operation
  (`confirm`, `checkIn`, `checkOut`, `cancel`, …).
- Having permission never substitutes for a valid state transition.
- Respect the critical invariants in the specification (no double-booking, POSTED
  accounting entries immutable, balanced debit/credit, no hard-delete of historical
  business data, price snapshots, etc.).

## 8. Frontend

- Every functional frontend action must map to an **existing** backend operation. If the
  backend behavior does not exist, report the missing mapping instead of inventing it.
- **Reuse existing templates, layout fragments, CSS and JS patterns** rather than adding
  parallel ones.
- No business logic in Thymeleaf, HTML, CSS or JavaScript; backend validation stays
  authoritative.
- Show state-dependent and permission-dependent actions only when valid — for usability,
  never as a security control.
- No placeholder screens for unimplemented backend functionality.

## 9. Scope Control

- Implement exactly the requested task — nothing adjacent, nothing speculative.
- Do not add business features listed as non-goals in the specification.
- If the task cannot be completed within the documented architecture and rules, **stop and
  ask** rather than inventing a structure or a rule.

## 10. Database

- Follow `docs/coding-rules/sql.md`: SQL keywords uppercase, explicit column lists, no
  `SELECT *`, parameterized values, WHERE clauses on UPDATE/DELETE.
- **Do not introduce schema migrations unless an approved requirement requires them.**
- Preserve existing query behavior — do not change conditions, joins, ordering, filtering
  or selected columns unless the task explicitly asks for it.
- Application validation does not replace database constraints; keep both.

## 11. Testing

- Inspect existing tests for the affected area first, and follow the project's existing
  test infrastructure and naming conventions.
- Cover, where applicable: happy path, invalid state transition, business rule violation,
  authorization failure.
- **Run the relevant tests after implementing.** Prefer a targeted run
  (`mvn test -Dtest=<TestClass>`) and widen to the affected module when appropriate.
- Report failures honestly with the actual output. Never claim tests passed without
  running them.

## 12. Before Finishing

Report, concisely:

1. Files created and files modified.
2. Tests run (commands) and their real results.
3. Specification sections or business rules the change relies on.
4. Unresolved issues: spec/implementation conflicts, missing or ambiguous requirements,
   skipped scope, assumptions made.

Do not report a task complete while any of the above is unverified.
