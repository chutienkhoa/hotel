# SQL Coding Rules

## 1. Purpose

This document defines the mandatory SQL coding conventions for the Hotel Management System.

Codex and other coding agents MUST follow these rules when creating, modifying, or reviewing SQL statements.

Do NOT introduce additional SQL conventions that are not defined in this document without explicit approval.

---

# 2. SQL Keyword Case

All SQL keywords MUST be written in **UPPERCASE**.

Examples of SQL keywords include:

```text
SELECT
FROM
WHERE
INSERT
INTO
VALUES
UPDATE
SET
DELETE
JOIN
INNER JOIN
LEFT JOIN
RIGHT JOIN
FULL JOIN
ON
GROUP BY
ORDER BY
HAVING
LIMIT
OFFSET
DISTINCT
AS
AND
OR
NOT
IN
EXISTS
BETWEEN
LIKE
IS NULL
IS NOT NULL
UNION
UNION ALL
CASE
WHEN
THEN
ELSE
END
CREATE
ALTER
DROP
TABLE
INDEX
PRIMARY KEY
FOREIGN KEY
REFERENCES
```

Correct:

```sql
SELECT customer_id,
       customer_name
FROM customer
WHERE customer_id = 100;
```

Incorrect:

```sql
select customer_id,
       customer_name
from customer
where customer_id = 100;
```

---

# 3. SQL Keywords and Identifiers

SQL keywords MUST be uppercase.

Table names, column names, aliases, and other identifiers MUST follow the project's database naming convention.

Example:

```sql
SELECT c.customer_id,
       c.customer_name
FROM customer c
WHERE c.customer_id = 100;
```

Do NOT uppercase identifiers simply because SQL keywords must be uppercase.

Correct:

```sql
SELECT customer_id
FROM customer;
```

Not required:

```sql
SELECT CUSTOMER_ID
FROM CUSTOMER;
```

---

# 4. SELECT Formatting

Each selected column SHOULD be placed on a separate line when the query contains multiple columns.

Preferred:

```sql
SELECT c.customer_id,
       c.customer_name,
       c.email,
       c.phone
FROM customer c;
```

Avoid:

```sql
SELECT c.customer_id, c.customer_name, c.email, c.phone FROM customer c;
```

For a simple single-column query, a single line is acceptable:

```sql
SELECT customer_id
FROM customer;
```

---

# 5. FROM Formatting

`FROM` MUST be written in uppercase.

For readability, the table should normally be placed on the line after `FROM`.

Preferred:

```sql
SELECT c.customer_id,
       c.customer_name
FROM customer c;
```

---

# 6. WHERE Formatting

`WHERE` MUST be written in uppercase.

Conditions SHOULD be separated into individual lines when there are multiple conditions.

Preferred:

```sql
SELECT c.customer_id,
       c.customer_name
FROM customer c
WHERE c.status = 'ACTIVE'
  AND c.deleted_flag = FALSE;
```

Avoid:

```sql
SELECT c.customer_id, c.customer_name
FROM customer c
WHERE c.status = 'ACTIVE' AND c.deleted_flag = FALSE;
```

---

# 7. AND / OR Formatting

`AND` and `OR` MUST be written in uppercase.

For multiple conditions, place the operator at the beginning of the continuation line.

Preferred:

```sql
WHERE c.status = 'ACTIVE'
  AND c.deleted_flag = FALSE
  AND c.customer_type = 'REGULAR';
```

For `OR`:

```sql
WHERE c.status = 'ACTIVE'
   OR c.status = 'PENDING';
```

---

# 8. JOIN Formatting

All JOIN-related keywords MUST be uppercase.

Preferred:

```sql
SELECT c.customer_id,
       c.customer_name,
       b.booking_id
FROM customer c
INNER JOIN booking b
        ON b.customer_id = c.customer_id
WHERE c.status = 'ACTIVE';
```

For `LEFT JOIN`:

```sql
SELECT c.customer_id,
       c.customer_name,
       b.booking_id
FROM customer c
LEFT JOIN booking b
       ON b.customer_id = c.customer_id;
```

---

# 9. INSERT Formatting

`INSERT`, `INTO`, and `VALUES` MUST be uppercase.

Preferred:

```sql
INSERT INTO customer (
    customer_name,
    email,
    phone
) VALUES (
    'John',
    'john@example.com',
    '0900000000'
);
```

---

# 10. UPDATE Formatting

`UPDATE` and `SET` MUST be uppercase.

Preferred:

```sql
UPDATE customer
SET customer_name = 'John',
    email = 'john@example.com',
    update_id = 'SYSTEM'
WHERE customer_id = 100;
```

IMPORTANT:

An `UPDATE` statement MUST normally contain an appropriate `WHERE` condition.

Codex MUST NOT remove or weaken an existing `WHERE` condition without explicit approval.

---

# 11. DELETE Formatting

`DELETE` and `FROM` MUST be uppercase.

Preferred:

```sql
DELETE
FROM customer
WHERE customer_id = 100;
```

IMPORTANT:

A `DELETE` statement MUST normally contain an appropriate `WHERE` condition.

Codex MUST NOT create or modify a DELETE statement that can unintentionally delete all records.

---

# 12. ORDER BY

`ORDER BY` MUST be uppercase.

Preferred:

```sql
SELECT customer_id,
       customer_name
FROM customer
WHERE status = 'ACTIVE'
ORDER BY customer_name ASC;
```

Multiple sort columns:

```sql
ORDER BY created_at DESC,
         customer_name ASC;
```

---

# 13. GROUP BY

`GROUP BY` MUST be uppercase.

Preferred:

```sql
SELECT status,
       COUNT(*) AS customer_count
FROM customer
GROUP BY status;
```

---

# 14. HAVING

`HAVING` MUST be uppercase.

Preferred:

```sql
SELECT status,
       COUNT(*) AS customer_count
FROM customer
GROUP BY status
HAVING COUNT(*) > 10;
```

---

# 15. CASE Expression

`CASE`, `WHEN`, `THEN`, `ELSE`, and `END` MUST be uppercase.

Preferred:

```sql
SELECT customer_id,
       CASE
           WHEN status = 'ACTIVE' THEN 'Active'
           WHEN status = 'INACTIVE' THEN 'Inactive'
           ELSE 'Unknown'
       END AS status_name
FROM customer;
```

---

# 16. NULL Handling

Use uppercase SQL keywords for NULL-related expressions.

Preferred:

```sql
WHERE deleted_at IS NULL;
```

```sql
WHERE deleted_at IS NOT NULL;
```

---

# 17. IN / EXISTS / BETWEEN / LIKE

SQL operators and keywords MUST be uppercase.

Preferred:

```sql
WHERE status IN ('ACTIVE', 'PENDING');
```

```sql
WHERE customer_id BETWEEN 100 AND 200;
```

```sql
WHERE customer_name LIKE '%John%';
```

```sql
WHERE EXISTS (
    SELECT 1
    FROM booking b
    WHERE b.customer_id = c.customer_id
);
```

---

# 18. Subqueries

Subqueries SHOULD be formatted with indentation.

Preferred:

```sql
SELECT c.customer_id,
       c.customer_name
FROM customer c
WHERE EXISTS (
    SELECT 1
    FROM booking b
    WHERE b.customer_id = c.customer_id
);
```

---

# 19. CTE

If Common Table Expressions are used, `WITH` and `AS` MUST be uppercase.

Preferred:

```sql
WITH active_customer AS (
    SELECT customer_id,
           customer_name
    FROM customer
    WHERE status = 'ACTIVE'
)
SELECT customer_id,
       customer_name
FROM active_customer;
```

---

# 20. SQL Comments

SQL comments SHOULD be used only when the reason or logic cannot be understood from the SQL itself.

Single-line comment:

```sql
-- Get active customers
SELECT customer_id,
       customer_name
FROM customer
WHERE status = 'ACTIVE';
```

Multi-line comment:

```sql
/*
 * Retrieve customers who have active bookings.
 */
SELECT c.customer_id,
       c.customer_name
FROM customer c
INNER JOIN booking b
        ON b.customer_id = c.customer_id
WHERE b.status = 'ACTIVE';
```

Do NOT add unnecessary comments that simply repeat what the SQL already expresses.

---

# 21. Table and Column Aliases

Aliases SHOULD be short but meaningful.

Preferred:

```sql
SELECT c.customer_id,
       b.booking_id
FROM customer c
INNER JOIN booking b
        ON b.customer_id = c.customer_id;
```

Avoid meaningless aliases when they reduce readability:

```sql
SELECT a.customer_id,
       x.booking_id
FROM customer a
INNER JOIN booking x
        ON x.customer_id = a.customer_id;
```

---

# 22. Avoid SELECT \*

Do NOT use `SELECT *` unless there is a specific and approved reason.

Preferred:

```sql
SELECT customer_id,
       customer_name,
       email
FROM customer;
```

Avoid:

```sql
SELECT *
FROM customer;
```

This rule applies especially to application queries and repository queries.

---

# 23. Explicit Column Names

For `INSERT`, explicitly specify the column names.

Preferred:

```sql
INSERT INTO customer (
    customer_name,
    email,
    phone
) VALUES (
    'John',
    'john@example.com',
    '0900000000'
);
```

Do NOT rely on implicit column ordering:

```sql
INSERT INTO customer
VALUES (
    'John',
    'john@example.com',
    '0900000000'
);
```

---

# 24. Query Readability

SQL MUST prioritize readability.

For complex queries:

- Put major SQL clauses on separate lines.
- Put selected columns on separate lines.
- Put multiple conditions on separate lines.
- Indent nested queries.
- Indent JOIN conditions consistently.

Example:

```sql
SELECT c.customer_id,
       c.customer_name,
       COUNT(b.booking_id) AS booking_count
FROM customer c
LEFT JOIN booking b
       ON b.customer_id = c.customer_id
      AND b.status = 'CONFIRMED'
WHERE c.status = 'ACTIVE'
GROUP BY c.customer_id,
         c.customer_name
HAVING COUNT(b.booking_id) > 0
ORDER BY booking_count DESC;
```

---

# 25. SQL Injection Prevention

Application code MUST NOT construct SQL by directly concatenating user-provided values.

Avoid:

```text
"SELECT ... WHERE customer_name = '" + customerName + "'"
```

Use parameterized queries or the appropriate framework mechanism.

For Spring Data / JDBC / ORM implementations, follow the project's existing data-access approach.

---

# 26. Existing SQL Behavior

When modifying existing SQL, Codex MUST preserve existing behavior unless the task explicitly requires a behavior change.

Codex MUST NOT:

- Change business conditions.
- Remove existing WHERE conditions.
- Change JOIN types.
- Change sorting behavior.
- Change filtering behavior.
- Change selected columns.
- Change transaction-related behavior.

unless explicitly requested.

---

# 27. SQL Formatting Only

If the task is only to format SQL, Codex MUST NOT change the SQL logic.

For example, changing:

```sql
select customer_id from customer where status = 'ACTIVE'
```

to:

```sql
SELECT customer_id
FROM customer
WHERE status = 'ACTIVE';
```

is a formatting change.

Codex MUST NOT simultaneously change it to:

```sql
SELECT customer_id,
       customer_name
FROM customer
WHERE status = 'ACTIVE'
  AND deleted_flag = FALSE;
```

because that changes query behavior.

---

# 28. Mandatory Rule for SQL Keywords

The following rule is mandatory:

> ALL SQL keywords MUST be written in UPPERCASE.

Examples:

```text
SELECT
FROM
WHERE
INSERT
INTO
VALUES
UPDATE
SET
DELETE
JOIN
INNER JOIN
LEFT JOIN
RIGHT JOIN
FULL JOIN
ON
GROUP BY
ORDER BY
HAVING
AND
OR
NOT
IN
EXISTS
BETWEEN
LIKE
IS NULL
IS NOT NULL
CASE
WHEN
THEN
ELSE
END
WITH
AS
UNION
UNION ALL
DISTINCT
LIMIT
OFFSET
```

Lowercase SQL keywords are NOT allowed.

Incorrect:

```sql
select
from
where
and
or
order by
group by
```

Correct:

```sql
SELECT
FROM
WHERE
AND
OR
ORDER BY
GROUP BY
```

---

# 29. Completion Checklist

Before completing a SQL-related task, Codex MUST verify:

- [ ] All SQL keywords are uppercase.
- [ ] SELECT is uppercase.
- [ ] FROM is uppercase.
- [ ] WHERE is uppercase.
- [ ] JOIN keywords are uppercase.
- [ ] AND / OR are uppercase.
- [ ] GROUP BY is uppercase.
- [ ] ORDER BY is uppercase.
- [ ] INSERT / INTO / VALUES are uppercase.
- [ ] UPDATE / SET are uppercase.
- [ ] DELETE / FROM are uppercase.
- [ ] Existing SQL behavior is preserved unless explicitly requested otherwise.
- [ ] No unnecessary `SELECT *`.
- [ ] INSERT statements explicitly specify columns.
- [ ] User-provided values are parameterized.
- [ ] UPDATE and DELETE statements have appropriate WHERE conditions.
- [ ] SQL formatting is readable and consistent.
