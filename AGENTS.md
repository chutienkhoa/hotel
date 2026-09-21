## Project Documentation

Before making changes, read the project documentation relevant to the task.

### Business Requirements

Business behavior is defined in:

- `docs/requirements/technical-spec-v1.md`

This document is the source of truth for business requirements.

Do not introduce, modify, or infer business behavior that is not defined in the specification.

### Architecture

Project architecture and package structure are defined in:

- `docs/coding-rules/architecture.md`

All new files must follow the defined project structure.

Do not introduce a different architecture or package organization without explicit instruction.

### Java

When creating or modifying Java code, read and follow:

- `docs/coding-rules/java.md`

### SQL / Database

When creating or modifying SQL, database migrations, queries, or database-related code, read and follow:

- `docs/coding-rules/sql.md`

### Frontend

When creating or modifying frontend code, including Thymeleaf, HTML, CSS, or JavaScript, read and follow:

- `docs/coding-rules/frontend.md`

Frontend implementation must map to existing backend functionality.

Do not create new backend business behavior solely to support a frontend feature.

### Cross-Layer Changes

If a task affects multiple layers, read all rules relevant to those layers.

For example:

```text
Frontend only
→ frontend.md

Java backend only
→ java.md

Database only
→ sql.md

Frontend + Backend
→ frontend.md + java.md

Backend + Database
→ java.md + sql.md

Full-stack
→ frontend.md + java.md + sql.md
```

The business specification and architecture rules apply whenever the requested change affects business behavior or project structure.

## Rule Priority

If project documents conflict, use the following priority:

```text
Business Requirements
        ↓
Security / Business Invariants
        ↓
Architecture
        ↓
Java / SQL Coding Rules
        ↓
Frontend Coding Rules
        ↓
Implementation Preference
```

If a requirement is unclear or missing, do not invent new business behavior.
