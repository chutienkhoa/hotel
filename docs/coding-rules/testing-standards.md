# Testing Standards

- Add or update tests for changed behavior.
- Cover valid, invalid, and authorization-relevant paths where applicable.
- Keep tests deterministic and isolated.
- Use integration tests when persistence, framework wiring, or external boundaries require verification.
- Never construct a fixture's Instant/timestamp from a fixed wall-clock hour on a calendar date derived from
  `LocalDate.now(clock)` (e.g. `today.atTime(14, 0)`); a later production write using the real clock can occur
  before that hour on the same day. Anchor same-day fixtures at `atStartOfDay(zone)` or an explicit historical
  instant instead.
- Run the relevant test suite before reporting completion; see README.md for the fast-mode vs. FULL-verification
  (`mvn verify -Pintegration`) commands.
