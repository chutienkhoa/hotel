# Database Standards

- Use versioned migrations for schema changes.
- Keep database constraints aligned with application validation.
- Preserve historical data unless deletion is explicitly required.
- Add indexes only when justified by access patterns.
- Review migration rollback and deployment impact before applying changes.
