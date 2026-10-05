# Task 33 — UI/UX Specification

## 1. Document control

| Item | Value |
| --- | --- |
| Project | Hotel System |
| Task | Task 33 — Final UI / UX Polish |
| Scope | Full-system UI/UX redesign, standardization, interaction completion, and regression |
| Status | Approved / specification baseline |
| Last updated | 2026-10-05 |
| Target release | V1 unless an item is explicitly marked V2/deferred |

## 2. Purpose

Task 33 is the final UI/UX release gate for Hotel System. It standardizes the application shell, navigation, page structure, reusable components, interaction behavior, feedback states, responsive behavior, and end-to-end screen flows across the system.

Task 33 changes presentation and usability only. It must not silently create or change business rules, state transitions, permissions, database behavior, report formulas, or API contracts.

## 3. Sources of truth and precedence

When two references conflict, use this order:

1. Current backend/domain/business contracts (`docs/requirements/technical-spec-v1.md` and the approved domain/state-machine rules it defines).
2. Explicit later-approved Task 33 product decisions (including decisions recorded during Batch audits, such as this reconciliation).
3. This document, `Task33_UI_UX_Specification.md`.
4. The latest approved one-screen-at-a-time Task 33 mockups and interaction specifications.
5. Original images in `UI Update.zip` and `Hotel_System_UI_Flow_Complete(1).xlsx` as supporting visual/coverage references.

Later explicitly approved Task 33 decisions supersede older decisions recorded earlier in this document. When a Batch audit finds that an older section of this document conflicts with a later-approved decision, the older section is corrected in place so the document stays internally consistent — contradictory versions of the same decision must not be left standing in different sections.

Mockup-generated fields, actions, and capabilities are **not** requirements by themselves. A mockup may illustrate intent, but it only becomes an implementation requirement when the capability is backed by existing backend behavior or has been explicitly approved for development. Do not implement a control, route, or field solely because it appears in a mockup or in the UI-flow workbook.

The UI-flow workbook records 84 screen states and 241 mapped clickable elements. It remains a coverage reference, but its older information architecture must not override the newer Task 33 decisions in this document.

Representative names, dates, identifiers, amounts, and room numbers in mockups are sample data only.

Approved, final, per-screen visual evidence is tracked at `docs/specs/evidence/task33/<module>/<screen>-final.png` (for example, `docs/specs/evidence/task33/dashboard/dashboard-final.png` for Dashboard, §9.1). Where a module section below references its own evidence file by this convention, that file is the current canonical visual reference for that screen and supersedes any earlier mockup for the same screen, subject to the same sample-data and backend-capability caveats as the rest of this section.

## 4. Scope boundaries

### 4.1 In scope

- Full UI audit and consistent visual treatment.
- Application shell: fixed sidebar, fixed header, and independently scrolling main content.
- Final information architecture and permission-aware navigation.
- Dashboard and operational workspaces.
- Reservation, guest, room, finance, reports, staff, user, and permission screens.
- Complete behavior for every visible button, tab, link, menu, row action, dialog, and form.
- State-aware actions and terminal-state presentation.
- Loading, empty, no-results, validation, warning, success, business-error, system-error, and permission-denied states.
- Multi-step form state preservation.
- Responsive/mobile-friendly presentation and full UI regression.

### 4.2 Out of scope

- New business features or new domain rules introduced only to support a visual idea.
- Changes to reservation/stay lifecycle semantics.
- Changes to report definitions, formulas, or the approved PDF/Excel data model.
- New role types or role CRUD. `ADMIN`, `MANAGER`, and `STAFF` remain the fixed built-in roles.
- Automatic persistence of unfinished wizard data to the database, HTTP session, or browser local storage.
- A functional global-search control. No backend capability exists for cross-domain search; see §6.2 and §13.
- A functional notification-bell control. No backend capability exists for notifications; see §6.2 and §13.

Any genuine backend or business gap discovered during implementation must be documented and handled as a separate task.

## 5. UX principles

1. **Operations first.** The most common hotel workflows must be reachable from Dashboard or Front Desk with minimal navigation.
2. **Context is preserved.** Errors must not erase input, close the current workflow, or redirect the user unnecessarily.
3. **Actions follow state.** Only valid actions for the current reservation/stay/folio state are visible and executable.
4. **Permissions are explicit.** Hidden navigation is not authorization; all protected actions remain server-authorized.
5. **Destructive actions require intent.** Cancel, void, refund, delete, deactivate, and discard actions require confirmation.
6. **History is immutable.** Corrected financial records and operational changes remain visible in history/audit views.
7. **No dead controls.** Every visible button, tab, link, row, icon, and menu item must have a defined destination or system output.
8. **One visual language.** Reuse the approved shell, components, spacing rhythm, action hierarchy, badges, and feedback patterns.

## 6. Visual direction

### 6.1 Application shell

- Dark navy persistent left sidebar.
- White/light-gray workspace.
- Fixed top header and fixed sidebar on desktop.
- Only the main content region scrolls.
- Do not leave an empty footer/navigation gap below the sidebar.
- Primary actions use the approved blue treatment.
- White cards use light borders/shadows and clear spacing.
- Status colors must convey meaning consistently; green is used for positive/ready/active states.
- The approved desktop mockup canvas is 1536 × 1024, but implementation must remain responsive rather than fixed-size.

### 6.2 Header

The shared header contains only the approved shell/account/language/navigation controls currently supported by the application:

- Mobile navigation toggle for the sidebar.
- Brand/home link.
- Logged-in user area: avatar/initials, name, and dropdown.
- Language switcher and logout action in the user dropdown.

Global search and a notification bell are **not** V1 header controls. No backend capability exists for cross-domain search or for notifications (confirmed absent from the service/repository layer). Task 33 follows the "no dead controls" rule (§5, principle 7): a control with no supported backend behavior must not be rendered just because it appeared in an earlier mockup. Global search and notifications remain deferred/V2 references (§13) and only become in-scope once a corresponding backend capability is approved and built.

### 6.3 Component consistency

- Page title, subtitle, breadcrumbs, and primary page action use one shared hierarchy.
- Forms use persistent labels; required fields and inline errors appear consistently.
- Tables use consistent headers, row hover/click behavior, pagination, filters, status badges, and action menus.
- Tabs retain the active state and never appear interactive without a defined target.
- Primary, secondary, neutral, and destructive buttons keep the same semantic hierarchy across modules.
- Dialogs have a clear title, concise consequence text, cancel action, and explicit confirmation action.
- Long pages may scroll; important actions must remain discoverable without hiding core content in unrelated tabs.

No new color, typography, spacing, or component system may be invented during implementation when an approved mockup/component already exists.

## 7. Final information architecture

### 7.1 Sidebar — final locked navigation

This is the final Task 33 navigation contract. It supersedes every older navigation example elsewhere in this document and in earlier mockups; no other section may describe a different sidebar structure.

| Group | Items |
| --- | --- |
| — | Dashboard |
| OPERATIONS | Front Desk, Reservations, Guests |
| HOTEL | Rooms → Room List, Housekeeping |
| FINANCE | Additional Revenue, Expenses, Reports |
| ADMINISTRATION | Staff, Work Records, Users, Roles & Permissions |

Rules:

- Check-in and Check-out are contextual Front Desk actions, not top-level sidebar items. See §9.2.1a for the transitional rule that keeps legacy Check-in/Check-out sidebar links in place until Batch 3 delivers their replacement entry points.
- Stays are accessed through Front Desk, Reservation Detail, Guest Detail, or linked records; they are not a top-level V1 item.
- Housekeeping is a current V1 operational destination, not a V2 deferral (§7.1a).
- `Rooms` is a navigation parent/group only. It does not represent a new "Rooms Overview" feature or route; it does not require any backend page that does not already exist. Its children are the existing `Room List` destination and the existing `Housekeeping` destination (§7.1a). Where the sidebar uses expandable groups, `Rooms` may toggle `Room List`/`Housekeeping` open and closed, but the group itself must not need an invented backend page.
- `Reports` is one sidebar entry under FINANCE (§7.1b). It does not have separate sidebar entries for Overview, Financial, Occupancy, or Revenue.
- There is no separate `MANAGEMENT` sidebar section. `Staff`, `Work Records`, `Users`, and `Roles & Permissions` all live under `ADMINISTRATION` (§7.1c). There is no generic `Categories` sidebar item; category management stays reachable from within the Additional Revenue/Expenses flows it belongs to (§9.6.3), not as its own Administration entry.
- Sidebar items are permission-aware.
- The active module/item uses the approved blue active background.
- The V1 desktop sidebar is persistent and not collapsible.

#### 7.1a Rooms and Housekeeping

`Room List` and `Housekeeping` are both existing destinations; `Rooms` only groups them in the sidebar. The current room lifecycle already includes housekeeping as a V1 operation:

```text
CHECKED_OUT
    ↓
Room becomes DIRTY
    ↓
Housekeeping
DIRTY → CLEANING → AVAILABLE
```

Housekeeping is therefore part of the current V1 operational lifecycle, governed by the existing housekeeping permission, and belongs under `HOTEL → Rooms → Housekeeping`. This supersedes any earlier statement in this document (or in superseded mockups) that housekeeping is V2 or should be excluded from the V1 sidebar. This reconciliation does not change the underlying housekeeping business rules or transitions — only where the existing destination is surfaced in navigation.

#### 7.1b Reports as a single entry

`Reports` is one sidebar entry under `FINANCE`. Do not add sidebar entries for `Overview`, `Financial`, `Occupancy`, or `Revenue` — those remain internal views/tabs/pages inside the Reports destination where supported by the approved Reports design and existing backend (§9.7), not independent navigation items. A report view that an older version of this document listed as its own route (for example a standalone "Revenue" route) is not reinstated by that older listing; it stays a tab/view inside Reports unless the backend already exposes it as such. PDF and Excel exports are unaffected by this reconciliation (§9.7.2); Task 33 redesigns only the web Reports UI, and only when Batch 7 is reached.

#### 7.1c Administration, Staff, and Work Records

`ADMINISTRATION` contains `Staff`, `Work Records`, `Users`, and `Roles & Permissions`. `Staff` and `Work Records` are independent navigation items aligned with the existing, already-separate backend permissions:

- `Staff` is governed by the `MANAGE_STAFF` permission (staff list/create/edit/deactivate/reactivate).
- `Work Records` is governed by the attendance/work-record permission (`MANAGE_ATTENDANCE`) that protects the Daily Work Record screens.

Do not gate the `Staff` link on `MANAGE_STAFF` **OR** `MANAGE_ATTENDANCE`. An attendance-only user must not see a `Staff` link that leads to a 403. Each navigation item enforces only its own permission; this is alignment with the existing permission model, not a new business capability.

### 7.2 Responsive behavior

- Desktop keeps the persistent sidebar and header.
- Smaller viewports must remain usable without breaking content hierarchy.
- Wide tables may use contained horizontal scrolling; columns must not overlap.
- Dialogs and forms must fit the viewport and keep actions reachable.
- Responsive changes must not remove information or bypass permission/state rules.

## 8. Shared page and interaction patterns

### 8.1 List pages

List pages use the following order when applicable:

1. Breadcrumb/title/subtitle and primary create action.
2. Optional summary/KPI cards.
3. Search, filters, date controls, and `Clear Filters`.
4. Data table/list with status and row actions.
5. Pagination and page-size controls.

Clicking a record identifier or row opens its detail page. The row action menu exposes only valid actions for that record and the current user.

### 8.2 Detail pages

- Show record identity, status, key metadata, contextual actions, and tabs/sections.
- Related identifiers are links to their corresponding detail pages.
- Terminal states are visibly identified and read-only: for Reservation Detail this is the closed-state subtitle, the state-toned badge, and the Cancellation/No-show Information card with no edit controls (§9.3.4.9), which replaces an earlier separate-banner wording.
- A generic `Edit` action is available only when the approved business state permits it.
- Post-confirmation lifecycle changes use dedicated operations rather than generic editing.

### 8.3 Forms

- Preserve entered data when validation or business/system errors occur.
- Field validation is inline, next to the relevant field.
- On submit, focus/scroll to the first invalid field.
- Long or multi-step forms also show an error summary at the top.
- Numeric money inputs may display thousands separators while the backend receives a numeric value.
- Submit actions are disabled while processing and show a loading indicator.
- Duplicate requests must be prevented.

## 9. Module specifications

### 9.1 Dashboard

Purpose: an operational-first "what is happening at the hotel now/today" screen. Dashboard is not an analytics surface; Reports remains the primary location for reporting/analytics (§9.7).

Canonical visual evidence: `docs/specs/evidence/task33/dashboard/dashboard-final.png`. This image is authoritative for layout, visual hierarchy, card composition, spacing, density, the two-column operational layout, KPI presentation, table styling, the Room Status visualization, and the Quick Actions layout. It is **not** authoritative for sample names/numbers, invented statuses, or any control that does not map to approved backend capability (§3). Where the image's sample content conflicts with this section or with real backend semantics, this section and real backend semantics win — see the explicit call-outs in §9.1.3, §9.1.6, and §9.1.14.

§9.1.1–§9.1.9 are the final, approved Dashboard V1 product decisions, superseding every earlier Dashboard reconciliation recorded previously in this document, including the prior analytics-first Batch 2B hierarchy and the prior restriction against a Recent Reservations block or a date-scoped Arrivals/Departures KPI. Where any other part of this document could be read to require the earlier hierarchy or restriction, this section controls.

**Explicit supersession note**: technical-spec-v1 §41 states Dashboard v1 "intentionally" excludes date-scoped arrival/departure metrics. §9.1.2.C/D and §9.1.3/§9.1.6 below introduce exactly such metrics (a strict today-scoped Arrivals/Departures KPI and worklist), as an explicit, later-approved Task 33 product decision under this document's own precedence rule (§3, item 2). This is recorded here for traceability: `technical-spec-v1.md` itself is not modified by this document, so a reader comparing the two should treat this section as the controlling, superseding decision for Dashboard date-scoped arrival/departure summaries specifically — a documentary gap between the two documents, not an unresolved contradiction. No other technical-spec-v1 §41 exclusion is affected by this note; Revenue, Profit, Occupancy Rate, ADR, RevPAR, and global Charge/Payment/Outstanding totals remain fully excluded (§9.1.14).

#### 9.1.1 Backing read models

The final Dashboard reuses, and must not duplicate or diverge from the business rules of, these existing read models:

- `DashboardService` — existing aggregate counts (Active Rooms, Available Rooms, Room-status counts).
- `FrontDeskQueryService.arrivals()` / `.departures(includeAmounts)` / `.inHouse()` — the exact same Front Desk read models and readiness/eligibility/overdue rules; the Dashboard must reuse, never reimplement, this logic.
- `ReservationQueryService` — for Recent Reservations (§9.1.7), via one new, narrowly-scoped read query, not by changing Reservation List's own `findPage` default sort or sort-key whitelist.

New backend work approved by this redesign is limited to: (a) a guest-headcount aggregate query (§9.1.2.A), (b) a point-in-time "in-house as of an instant" query for the Currently Staying trend (§9.1.2.A), (c) a zero-fill completion of Room Status across all 6 `RoomStatus` values (§9.1.4), and (d) one new Recent Reservations read query (§9.1.7). No schema migration and no write-side/snapshot mechanism is approved or required for any Dashboard metric.

#### 9.1.2 Final KPI row

Four KPI cards, each independently permission-gated (§9.1.9):

**A. Currently Staying**

- Headline: current physical guest headcount = `SUM(Reservation.adultCount + Reservation.childCount)` over every `Stay` in-house at the evaluation instant (`actualCheckInAt ≤ instant AND (actualCheckOutAt IS NULL OR actualCheckOutAt > instant)`).
- Secondary: current occupied/current room count — reuse the existing OCCUPIED count already produced for Room Status (§9.1.4); do not run a second, separate query for the same number.
- Trend: a "vs. yesterday" guest-headcount delta, evaluated at the hotel-local date boundary (start of today / end of yesterday), using the same in-house interval query at a different instant. This is accurately derivable from existing `Stay.actualCheckInAt`/`actualCheckOutAt` data with no snapshot table, because guest composition (`adultCount`/`childCount`) is frozen for the life of a Stay once checked in (composition edits are permitted only while `Reservation.status = CONFIRMED`, i.e., before any Stay exists).

**B. Available Rooms**

- Headline: current available-room count only (reuse the existing `availableRooms` field).
- **Must not** display a vs.-yesterday trend, arrow, or delta of any kind. Historical `Room.status` is a bare mutable field with no version history anywhere in the system (no audit trail records operational-status transitions, and `RoomInventoryPeriod` tracks sellable-inventory periods, not operational status) and cannot be reconstructed. This must not be implemented even if a future evidence image shows it.

**C. Today's Arrivals**

- Headline count means strictly `Reservation.checkInDate == hotelToday`. Overdue (pre-today) arrivals are excluded from this headline count, even though they remain visible in the Today's Arrivals worklist below (§9.1.3) and in Front Desk itself — the KPI number and the worklist are allowed to differ in scope.
- Secondary "needs attention" count: derived from the existing readiness data (`needsAttention`) for today's rows only.

**D. Today's Departures**

- Headline count means strictly `Reservation.checkOutDate == hotelToday` (planned checkout date). Overdue departures are excluded from this headline count, for the same reason as C.
- Secondary "needs attention"/payment-required count: derived from existing departure readiness data (`paymentRequired`/`needsAttention`) for today's rows only.

#### 9.1.3 Today's Arrivals table

Reuses `FrontDeskQueryService.arrivals()` and its existing readiness model. Fields: Reservation #, Guest Name, Check-in Date, Room(s), Source, operational readiness/status, Action.

- **No ETA column.** `Reservation` has no planned-arrival-time field; the evidence image's "ETA" column is sample content and must not be implemented.
- Status must use the real operational concepts already defined by Front Desk readiness (`Ready` / `Needs Attention`, plus the existing `Overdue Arrival` badge where applicable) — never the evidence image's illustrative "Expected" label, which is not a domain status.
- Action navigates to the canonical Check-in Review route (`/check-in/reservations/{id}`), with the same label behavior Front Desk already uses (Check-in when ready, Review when needing attention). The Dashboard must never perform a direct, one-click check-in.
- Gated by `PERM_CHECK_IN` (§9.1.9).

#### 9.1.4 Room Status

Shows current operational Room status — never booking-period availability (technical-spec-v1 §41's room-status/availability distinction is unaffected by this section). All 6 `RoomStatus` values are shown — `AVAILABLE`, `OCCUPIED`, `DIRTY`, `CLEANING`, `MAINTENANCE`, `OUT_OF_ORDER` — including any that are currently zero-count; a zero-count status must still appear (zero-filled), the same convention already used elsewhere on this Dashboard for monthly/source series. Percentages are derived at render time from the current counts; they are presentation arithmetic, not a stored or separately queried figure. "View all" navigates to the existing Room List (`/rooms`). Gated by the Dashboard's own existing permission only (no additional operational permission needed — room-status counts carry no guest-identifying data).

#### 9.1.5 Currently Staying table

Reuses `FrontDeskQueryService.inHouse()`. Fields: Room(s), Guest Name, Actual Check-in Date, Nights, Status, Action.

- Nights is derived for presentation (from `actualCheckInAt` to the hotel's current date) and is explicitly not a stored domain field; it must not be mistaken for existing data when implemented.
- Action navigates to the canonical Reservation Detail (`/reservations/{id}`). Do not create a Dashboard-specific stay detail page.
- Gated by `PERM_CHECK_OUT` (the same permission `inHouse()`'s own canonical page already requires).

#### 9.1.6 Today's Departures table

Reuses `FrontDeskQueryService.departures(includeAmounts)`. Fields: Room(s), Guest Name, Planned Check-out Date, Nights, operational readiness/status, Action.

- Status must use the real operational concepts already defined by Front Desk readiness (`Ready for Checkout` / `Payment Required`, plus the existing `Overdue Departure` badge where applicable). The evidence image's illustrative "Due today" and "Pending" labels are **not** domain statuses and must not be implemented, even though they still appear in the current evidence image — per §3/§9.1, explicit specification wording wins over mockup sample text.
- Action navigates to the canonical Checkout Review route (`/check-out/{id}`), with the same label behavior Front Desk already uses. The Dashboard must never bypass outstanding-balance checks, stay-extension requirements, or any other existing checkout guard.
- Financial amount (outstanding balance) visibility remains gated by `PERM_MANAGE_PAYMENT`, exactly as in `departures(includeAmounts)` today — never shown by default.
- Gated by `PERM_CHECK_OUT`.

#### 9.1.7 Recent Reservations

Approved for V1. Definition: the 5 most recently **created** reservations, using `Reservation.reservedAt` (the existing, immutable, `NOT NULL` creation timestamp set once by the single production reservation-creation path, covering every source including walk-in and OTA) as the canonical creation time.

Deterministic ordering:

```text
ORDER BY reservedAt DESC, reservationNumber DESC
LIMIT 5
```

Implemented as one new, dedicated, small Dashboard read query (reusing the existing `ReservationSummaryResponse` shape and the existing batch room-number lookup pattern already used by `ReservationQueryService.findAll()`), not by changing Reservation List's own `findPage` default sort or its sort-key whitelist.

Fields: Reservation #, Guest Name, Check-in Date, Check-out Date, Room(s), Source, Status. **No financial amount** is shown in this block, consistent with the Dashboard's existing no-financial-metrics boundary (§9.1.14).

Gated by `PERM_VIEW_BOOKING`.

#### 9.1.8 Quick Actions

Four tiles, matching the approved evidence: New Reservation, Guest Management, Room Management, View Reports — each linking to its existing route (`/reservations/new`, `/guests`, `/rooms`, `/reports`) under its own existing permission (`MANAGE_BOOKING`, `MANAGE_GUEST`, `MANAGE_ROOM`, `VIEW_REPORT` respectively). No new route is created. These four actions are **not** also duplicated as page-header buttons; Quick Actions is their one and only placement on the Dashboard.

#### 9.1.9 Permissions

Dashboard page-level authorization is unchanged (`PERM_VIEW_REPORT`). `VIEW_REPORT` is never assumed to imply any operational permission. Each embedded block/action is independently gated by its own canonical permission, and is **omitted** (not disabled, not blanked) for a viewer who lacks it:

| Block/action | Required permission |
| --- | --- |
| KPI row aggregate counts, Room Status | `PERM_VIEW_REPORT` only (no guest-identifying data) |
| Today's Arrivals KPI + table | `PERM_CHECK_IN` |
| Currently Staying KPI (headcount/room count) + table | `PERM_CHECK_OUT` |
| Today's Departures KPI + table | `PERM_CHECK_OUT` |
| Outstanding/financial amount within Departures | `PERM_MANAGE_PAYMENT` (in addition to `PERM_CHECK_OUT`) |
| Recent Reservations | `PERM_VIEW_BOOKING` |
| Quick Actions tiles | each tile's own existing destination permission |

Hiding a block or link is a usability courtesy, never the authorization boundary; every destination route keeps enforcing its own permission independently of what the Dashboard shows or hides. No permission is loosened by this section.

#### 9.1.10 Disposition of prior (Batch 2B) analytics blocks

The following remain fully supported backend capability; removing them from the Dashboard here is a **presentation-only** change, not a requirement to delete their query/service code:

- Reservations by Check-in Month, Booked Rooms by RoomType, Reservations by Source, Reservations by Status, Expenses by Status — no longer part of the Dashboard UI. Reports remains the primary location for this kind of reporting/analytics.
- Active Rooms by Operational Status — visually superseded by Room Status (§9.1.4); do not render both.
- Operational Room Alerts — may remain only if it fits the approved evidence hierarchy without becoming a large analytics section; it must not be restored to its previous full-width prominence.

#### 9.1.11 Foundation reuse

Reuse the Task 33 Foundation shell, cards, buttons/links, badges, standard tables, and the shared enum/i18n rendering convention. Breadcrumb is not added (Dashboard remains top-level). Toast/confirmation/error-dialog infrastructure is added only where a real Dashboard action needs it; the Check-in Review/Checkout Review/Reservation Detail/Room List/Reports links are navigation, not in-page actions, so none is required merely for them.

#### 9.1.12 i18n

All Dashboard user-visible text, including the operational blocks' labels, statuses, and Quick Actions tiles, must use the existing EN/VI message-key infrastructure; no new hardcoded English text.

#### 9.1.13 Responsive

Desktop visual target is the approved evidence. On mobile: KPI cards reflow cleanly; operational tables use the existing `.table-wrap` safe-overflow pattern already used by Front Desk and Reservation List; Quick Actions stack; Room Status remains readable; no page-level horizontal overflow; critical operational content is never removed merely to shorten the page; the existing Task 33 mobile drawer remains authoritative. The known hamburger/close mobile-icon-state cosmetic item remains a separate Foundation polish item, not part of this reconciliation.

#### 9.1.14 Non-goals (V1)

The following must not be introduced on the strength of the evidence image, an older mockup, or the UI-flow workbook, unless separately approved in a future product task:

- Revenue, Profit, Occupancy Rate, ADR, RevPAR.
- Cash received, payment totals, charge totals, or outstanding-balance totals shown by default (outstanding remains gated by `PERM_MANAGE_PAYMENT`, §9.1.9).
- A vs.-yesterday trend, arrow, or delta on Available Rooms (§9.1.2.B).
- An ETA/planned-arrival-time column (§9.1.3).
- Invented statuses not backed by the real readiness model — "Expected," "Due today," "Pending," or any other illustrative label from the evidence image that does not correspond to an actual Front Desk readiness/overdue concept.
- A direct, one-click Check-in or Check-out action that bypasses Check-in Review or Checkout Review.
- A Dashboard-specific stay/reservation detail page.
- Reservation Planning Board and Room Availability Dashboard (§13).
- Forecasting, RMS recommendations, AI insights, notifications (including a header notification bell — §6.2 remains controlling: no backend capability for notifications exists, even though the current evidence image shows a bell icon), global search, or OTA sync status.
- A new route created solely for Dashboard navigation or Quick Actions.
- Duplicating New Reservation / View Reports (or any other Quick Actions tile) as a separate page-header button.

### 9.2 Front Desk

Front Desk is the operational workspace with the following views:

- **Arrivals** — expected arrivals, reservation search, review, and check-in.
- **In-house** — current stays and contextual stay operations.
- **Departures** — planned departures and checkout readiness.

Common behavior:

- Search/filter controls update the visible queue.
- Clicking a row opens the appropriate Review or Detail page.
- Back navigation returns to the originating Front Desk view with filters/context preserved where possible.
- Arrivals, In-house, and Departures are views/tabs inside Front Desk. They are not global sidebar items.

#### 9.2.1 Check-in

Flow:

`Front Desk → Arrivals/Find Reservation → Check-in Review → Confirm Check-in → Check-in Complete → Stay/Reservation Detail`

- If no reservation exists, `Start New Booking` opens Create Reservation. The new reservation must be confirmed before check-in.
- Review shows the approved guest/reservation/room/payment context only.
- Guest identity is shown by guest code/link where required; clicking it opens Guest Detail.
- Payment action opens the approved payment/folio flow.
- Confirm Check-in is permission- and state-controlled.
- Completion may expose approved outputs such as registration card/key-card preview and links to the created stay/reservation.

#### 9.2.1a Transitional Check-in/Check-out navigation

The final Task 33 sidebar (§7.1) does not contain standalone `Check-in` or `Check-out` items; those operations are reached from inside Front Desk. The target, final flow is:

```text
Front Desk
  ↓
Arrivals
  ↓
Check-in Guest
  ├── Existing Reservation
  ├── Walk-in
  └── OTA Booking Not Entered

Departures
  ↓
Checkout
```

However, during incremental Task 33 implementation, the legacy `Check-in` / `Check-out` sidebar links may remain temporarily. The existing Walk-in and "OTA Booking Not Entered" flows currently depend on the legacy Check-in entry point; removing that link before its replacement exists would make those operational capabilities unreachable. Batch 3 (Front Desk) must establish the approved replacement entry points (`Check-in Guest` with its `Existing Reservation` / `Walk-in` / `OTA Booking Not Entered` paths, and `Checkout` under Departures) before the temporary `Check-in` / `Check-out` sidebar links are removed. Migration safety takes precedence over making the intermediate sidebar visually identical to the final navigation in §7.1.

#### 9.2.2 Checkout Review — GAP-01 closed

Flow:

`Front Desk → Departures → Checkout Review`

- Only a checked-in stay/reservation that is eligible under the existing checkout rules may enter the confirmation path.
- Show planned checkout context and folio readiness.
- If `Outstanding > 0`, checkout is blocked. Offer `Open Folio` / `Record Payment`, then return to Checkout Review.
- If `Outstanding = 0`, enable `Confirm Checkout`.
- `Confirm Checkout` opens a confirmation dialog.
- On success: reservation/stay becomes `CHECKED_OUT`; the current occupied room becomes `DIRTY`; show success feedback; return to Departures or the approved completion view.
- A future reservation for that room does not block checkout and does not prevent the room from becoming `DIRTY`.
- Do not add Room Condition, checkout staff, extra checklist, or any new checkout capability in Task 33.
- **Checkout Complete** (the "approved completion view" above) is a presentation/success state only, not a new domain state; the underlying lifecycle remains Reservation `CHECKED_OUT` / Stay `CHECKED_OUT` as already set by `ReservationService.checkOut`. It may link to Reservation Detail, Front Desk, or the next Departures row, consistent with the approved `checkout-complete-final.png` evidence.
- **Evidence override**: neither Checkout Review nor Checkout Complete renders a generic "Edit Guest" action or any other control without a backing route; every visible action must map to an existing route (Open Folio, Record Payment, Confirm Checkout, navigation), per §5 principle 7 and §12.

#### 9.2.3 Front Desk table UX and read-model enhancements (Batch 3A)

Arrivals, In-house, and Departures currently have no pagination, column-header sorting, or search/filter parameters in the backend read model (`FrontDeskQueryService`/`FrontDeskPageController`) at all. Task 33 approves bounded, read-model-only enhancements to add them, without changing what each view means:

- Pagination, sortable column headers, and practical search/filter are approved additions, implemented as read-model query parameters analogous to the pattern already used by Reservation List/Guest List/Check-in search.
- Filtering only narrows the already-valid, date/state-scoped result set each view already defines (technical-spec-v1 §65) — it must never bypass hotel-date scoping, change which Reservations/Stays qualify for a view, or alter existing ordering rules (needs-attention/overdue-first for Arrivals and Departures, room-number order for In-house) beyond an explicitly approved sortable column.
- Approved layout: the pagination control sits above the table, aligned upper-right; sortable columns use clickable column headers; each row exposes exactly one primary Action button (Check-in/Review, Checkout, or the equivalent); critical row content (Guest Name, Room(s), Reservation #) stays single-line where practical; on narrow screens, prefer horizontal scrolling over wrapping that would truncate or stack critical content destructively.
- This is new backend read-model work (new query parameters/filters), not a new business domain; it does not change the Arrivals/In-house/Departures business definitions in technical-spec-v1 §65.

#### 9.2.4 Front Desk financial display — In-house and Departures are not symmetric

- **In-house never shows a Balance/Outstanding column.** `FrontDeskQueryService.inHouse()` does not compute or read Charge/Payment totals for In-house rows at all — it has no `includeAmounts` parameter and calls `toStayRows` with `withBalance = false`. Adding an In-house Balance column would require new financial querying added solely to reproduce a mockup, which is out of scope; do not add it.
- **Departures may legitimately show an outstanding amount.** `FrontDeskQueryService.departures(includeAmounts)` already computes Outstanding for readiness purposes and exposes it only to a viewer who holds `PERM_MANAGE_PAYMENT`, exactly as the Dashboard's Today's Departures block already does (§9.1.6, §9.1.9); a viewer with only `PERM_CHECK_OUT` sees the Ready-for-Checkout/Payment-Required label without the amount. This is existing, already-approved behavior, not new scope, and it does not extend to In-house.
- Financial information otherwise stays contextual through Reservation Detail/Folio, consistent with §9.1.14 and §9.6.

#### 9.2.5 Walk-in and OTA Booking Not Entered — Guest creation orchestration (Batch 3B)

Both the Walk-in and OTA Booking Not Entered flows (§9.2.1a) require selecting a Guest. There is no separate aggregate "Guest + Passport + Reservation" creation request, and none is approved — Walk-in/OTA Guest selection and creation reuse the existing, already-built operational Guest creation capability (`GuestPageController`), not a new cross-domain transaction:

- The wizard offers **Existing Guest** (search/select an existing `guestId`, reusing the existing eligible-guest lookup) or **New Guest**.
- **New Guest** navigates to the existing Guest creation form (`GET /guests/new?returnTo=...`), which already accepts `returnTo` restricted to `/reservations/new`, `/check-in/walk-in`, and `/check-in/ota-entry`, and already sets a `createdGuestId` flash attribute and redirects back to the `returnTo` target on success (`GuestPageController.create`).
- **Remaining implementation work, scoped to Batch 3B**: `CheckInPageController`'s Walk-in (`GET /check-in/walk-in`) and OTA Booking Not Entered (`GET /check-in/ota-entry`) handlers do not currently read the `createdGuestId` flash attribute back. Batch 3B must consume it, pre-select the newly created Guest in the wizard's Guest field, and preserve the rest of the in-progress wizard state across the round trip (folded in with the existing §9.3.3a Walk-in Back/state-loss fix). This is UI/controller wiring on top of an already-built capability, not a new backend aggregate or a new write path.
- **No cross-domain transaction**: Guest creation and Reservation/Stay creation remain two separate, already-existing operations performed in sequence (create the Guest, then continue Walk-in/OTA using the resulting `guestId`); Task 33 does not introduce a single transaction spanning Guest + Passport + Reservation.
- **Passport stays a Guest-management boundary** (§9.4.2): an operational user (holding only `CHECK_IN`/`MANAGE_BOOKING`, not `MANAGE_GUEST`) may create the Guest record itself through this flow, but the passport-upload control on that form is hidden for them and any submitted passport file is dropped server-side (`GuestPageController.create`); passport upload/replace/delete remain `MANAGE_GUEST`-only. Do not broaden this boundary to make the wizard's passport step more convenient.
- **No structured passport/ID fields anywhere.** Passport remains an image document only. Do not add a Passport Number, ID Number, OCR, or any other structured passport metadata field to the Walk-in/OTA wizard, the Guest creation form, or Check-in Review, even if a mockup/evidence image (`walk-in-reservation-final.png`, `ota-booking-not-entered-final.png`) shows one.
- **Reservation Summary evidence note**: the `reservation-summary-final.png` evidence corresponds to the existing read-only Review step already described for Walk-in (`check-in/walk-in-review`) and for OTA Booking Not Entered/Existing Reservation (Check-in Review, §9.2.6) — it is not a separate, additional screen beyond those approved Review steps.

#### 9.2.6 Check-in Review — approved controls (evidence override)

Check-in Review (§9.2.1) shows only the approved read-only guest/reservation/room/payment context and the approved actions already backed by a real route. The following controls, even where shown in the `check-in-review-final.png` evidence image or an older mockup, are not supported and must not be implemented as written there:

- **No generic "Edit Guest" action** without a backing route. Guest profile editing remains the existing `MANAGE_GUEST`-only Guest Edit flow (§9.4.1), reached from Guest Detail, not from Check-in Review.
- **No direct "Upload Passport" action** without a backing route from Check-in Review. Passport upload/replace/delete remain the existing `MANAGE_GUEST`-only Documents flow (§9.4.2) on Guest Detail; Check-in Review may link to secure View Passport (read-only) but does not add its own upload control.
- **No generic "Change Room" action.** Before check-in, the only backed room-reassignment capability is the existing per-blocker **Reassign Room** action, surfaced next to the specific room that is actually blocking Arrival Readiness (not as a standalone, always-available button), restricted to the real eligibility rules already enforced server-side for pre-check-in room reassignment. This restriction describes the Check-in Review surface only: Reservation Detail for a `CONFIRMED` Reservation with no Stay has its own, separately approved `Reassign Room` entry in the `More` menu (§9.3.4.4), which invokes the same backend operation under the same `CHECK_IN` permission and eligibility and is not a generic edit. "Room Change" as a named operation applies only after check-in, to a `CHECKED_IN` Stay (§9.5), and is a different capability from this pre-check-in Reassign Room.

#### 9.2.7 Front Desk / Check-in implementation boundary (Batch 3)

Batch 3 MAY add: Front Desk read-model pagination/search/sort (§9.2.3); UI/controller wiring for `createdGuestId` return/pre-selection and wizard state preservation (§9.2.5, §9.3.3a); the redesigned Thymeleaf UI, responsive behavior, and accessibility for Front Desk/Check-in/Checkout screens; permission-aware action rendering consistent with existing permissions.

Batch 3 MUST NOT add: schema fields added only for mockup cosmetics (for example `Room.floor`-based filtering, a stored ETA, or a stored Balance snapshot); partial refunds or any `refundAmount` field; a financial adjustment/`ADJUSTMENT` Charge model; editable financial records (Edit Charge/Edit Payment); manually created `ROOM` Charges; a Passport Number/OCR/structured-ID field; a new `Room.floor` filtering model; a cross-domain Guest+Reservation aggregate transaction; or any other V2/deferred functionality listed in §13.

### 9.3 Reservations

#### 9.3.1 Reservation List

- Search/filter reservations and open a reservation by its reservation number.
- `Create Reservation` starts the wizard.
- Row actions are state-aware and permission-aware.
- More Filters must have a defined open/close/apply/clear behavior.

#### 9.3.2 Create Reservation wizard

Canonical steps:

1. Guest Information.
2. Stay Details.
3. Select Room(s).
4. Additional Services.
5. Review & Confirm.
6. Reservation Created.

Guest Information:

- Search/select an eligible existing guest or create a new guest.
- The selection control excludes guests whose relevant stay/reservation is `CHECKED_OUT`; creating a new guest code for a returning customer remains allowed.
- Selected guest details may expand/collapse and show Name, Email, Phone, and Nationality, one item per line.
- `View Profile` opens Guest Detail.

Stay Details:

- Source is required: `DIRECT`, `AGODA`, `BOOKING_COM`, or `AIRBNB`.
- OTA information follows the approved reservation OTA specification.
- Stay dates and guest counts use existing validation rules.

Room selection:

- Each room row contains Room, Nightly Rate, and Currency.
- Availability and overlap rules remain unchanged.

Review & Confirm:

- Each section has an Edit link back to its source step.
- `Save as Draft` creates/updates a `DRAFT` reservation using the approved backend behavior.
- `Confirm Reservation` performs the final submit and opens Reservation Created on success.
- Reservation Created links to Reservation Detail, check-in when eligible, and Create Another Reservation.

#### 9.3.3 Multi-step state preservation — GAP-06 closed

This contract applies to Create Reservation and every other multi-step wizard:

- `Next` validates the current step and never resets previously entered values.
- `Back` returns to the previous step with all entered and selected values intact.
- Returning through an Edit link from Review preserves all other steps.
- Field validation failure keeps the user on the current step and preserves all input.
- Business/system failure uses the global error dialog and preserves the current step, all input, and all prior selections.
- Long-step errors show a top summary plus inline field errors.
- `Cancel`/Exit with unsaved changes opens `Discard changes?`.
- Only explicit discard abandons the in-progress client-side wizard state.
- Do not auto-save incomplete wizard data to the database, HTTP session, or local storage.
- Recovery after page refresh, browser close, or navigation outside the confirmed wizard flow is not required by this contract.
- Final submit disables the action, displays loading, and prevents double submission.

#### 9.3.3a Known deferred issue — Walk-in Review → Back

Confirmed current issue: in the Walk-in flow, using `Back` from the Review step currently loses previously entered wizard state, which does not yet meet the contract in §9.3.3. This reconciliation records the gap but does not fix it here. It is assigned to Batch 3B (Front Desk, §14), where the Walk-in flow's final implementation must follow the §9.3.3 state-preservation contract in full: `Back`/`Next` preserve entered state, validation failure preserves entered state, and business/system errors preserve entered state. `Cancel` still explicitly discards only after confirmation. Batch 3B also covers the related New-Guest round trip: returning from Guest creation must preserve the rest of the wizard's entered state and auto-select the newly created Guest via `createdGuestId` (§9.2.5), not only the `Back`-button case. As with the rest of §9.3.3, V1 does not require state recovery across a browser refresh, a closed tab, or reopening the URL.

#### 9.3.4 Reservation Detail — one state-aware system (GAP-02 closed; reconciled 2026-10-05)

Reservation Detail is **one shared, state-aware page system**, not six unrelated screens. `DRAFT`, `CONFIRMED`, `CHECKED_IN`, `CHECKED_OUT`, `CANCELLED`, and `NO_SHOW` are configurations of the same shell (§9.3.4.1): the same header, summary strip, tabs, Overview card grid, right-hand support column, Recent Activity, and Audit Log. The state changes **which actions, tabs, cards, and room semantics** are shown — never the visual language.

**Approved visual evidence** (canonical per §3; `docs/specs/evidence/task33/reservations/`):

| State | Evidence file |
| --- | --- |
| `DRAFT` | `reservation-detail-draft-final.png` |
| `CONFIRMED` | `reservation-detail-confirmed-final.png` |
| `CHECKED_IN` | `reservation-detail-checked-in-final.png` |
| `CHECKED_OUT` | `reservation-detail-checked-out-final.png` |
| `CANCELLED` | `reservation-detail-cancelled-final.png` |
| `NO_SHOW` | `reservation-detail-no-show-final.png` |

The earlier Front Desk evidence `docs/specs/evidence/task33/front-desk/reservation-detail-checked-in-final.png` (Slice 3D) is **superseded** for Reservation Detail by `reservations/reservation-detail-checked-in-final.png`, which extends the same visual language (summary strip, tabs, three-card row, two-column body) into the shared shell. The older file stays in the archive as history; it must not be used as a second CHECKED_IN design. Controls that appeared only in the older image — "Special Requests", "ID / Passport Number" (also barred by §9.2.5), "Add Note" (§9.3.4a), "Add Adjustment", "Add Additional Revenue", "Add Payment" on the cards (§9.6.1), a "Direct (Walk-in)" source value (the source set is `DIRECT`, `AGODA`, `BOOKING_COM`, `AIRBNB`), and the Created By strip item — are not part of the shell.

**Precedence for this section.** Business and capability: current backend/domain rules → later explicit Task 33 product decisions → this document → mockup evidence. Visual: the approved evidence is the visual contract, except where it depicts a capability that is unsupported by the backend or explicitly rejected here. Names, dates, amounts, rooms, reasons, and actors in the evidence are sample data. A sample value in an image (for example "Confirmed At", "Remaining", "Nights (Current)") becomes a requirement only where §9.3.4.11 classifies it as supported.

##### 9.3.4.1 Shared visual shell

1. **Breadcrumb** — the standard Task 33 breadcrumb: `Reservations / Reservation #<number>`, or the Front Desk workflow trail when opened from Front Desk or Find Reservation (existing `Breadcrumbs.reservationDetail`). The current entry is not a link.
2. **Title row** — `Reservation #<number>` plus the status badge (§9.3.4.2).
3. **Lifecycle/state subtitle** — one or two short lines under the title describing the state (§9.3.4.2). Terminal states end with "This reservation is closed."
4. **Contextual header actions** — right-aligned: at most one primary action (or, for `CHECKED_IN`, the operational action set) plus a `More` menu for secondary narrow operations. The menu lists only actions that are valid for the state, the user's permission, and business eligibility; destructive actions sit last, after a divider, in the destructive treatment. The menu closes on selection, Escape, or outside click and is keyboard accessible (§12). A menu with no visible item is not rendered.
5. **Summary strip** — Primary Guest (name, guest code beneath), Room (room code, room type beneath), Source, Check-in (date, weekday beneath), Check-out, Nights, Guests (adults, children). Nights is one value computed from the Reservation's current planned check-in and check-out dates (Stay Extension already moves the planned check-out). With one room the Room item shows that room's code and room type; with two or more it lists the room codes (each a link where permitted) and shows the room count beneath instead of a single type.
6. **State-aware tabs** — §9.3.4.3. Every rendered tab has a real target; a tab the user cannot open is not rendered (§6.3, §5 principle 7).
7. **Overview** — a three-card row (Guest Information, Stay or Booking Information, Reservation Information), then a two-column body: main column (state information card, room section, financial/prepayment card) and a right-hand support column (Booking Contact, Notes, Recent Activity). Cards size to their content: the card grid aligns to the top and must not stretch shorter cards to match taller neighbours or leave empty vertical space (this applies in particular to the `DRAFT` Guest Information and Stay Information cards). Guest Information carries **no Edit control**: the Guest Code link is the navigation path to Guest Detail, and editing the Guest profile belongs to the Guests module (§9.4.1).
8. **Recent Activity** — compact preview (§9.3.4.8). **Audit Log** — the full history, reached from the tab or from `View All`.

**Links.** Guest Code and Room Code are navigation links to Guest Detail and Room Detail only when the user holds the existing `MANAGE_GUEST` / `MANAGE_ROOM` permission; otherwise they render as plain text. Link text uses the blue/light-blue link colour with **no underline by default**; hover and keyboard focus must make the interactivity apparent (colour change and underline, plus the standard focus ring). These are ordinary in-app navigation — they do not open a new browser tab. This convention supersedes the underlined links drawn in the evidence images.

**Buttons.** `Edit` on a card (Booking Contact and Notes only; Guest Information has none) is a secondary button: white background, light-blue text and border, edit icon on the **left**. `Open Folio` is a primary button: blue background, white text, icon on the left. `View All` and `View Prepayments` are secondary buttons: white background, light-blue border and text, icon on the left (the trailing "external link" glyph drawn on `View Prepayments` is not used — it suggests a new tab, and the navigation is in-app).

##### 9.3.4.2 Status badges and lifecycle subtitle

Badges use the localized display label (the `enum.reservationStatus.<VALUE>` convention through the shared enum/badge fragment, technical-spec-v1 §60.3), never the raw enum string.

| State | Badge tone | Subtitle |
| --- | --- | --- |
| `DRAFT` | neutral grey | "Created on {date time} by {user}" and "This is a draft reservation. You can edit the details or confirm it later." |
| `CONFIRMED` | blue | "Confirmed reservation. Ready for pre-arrival management." and "Confirmed on {date time} by {user}" |
| `CHECKED_IN` | green | "Checked in on {date} at {time} by {user}." |
| `CHECKED_OUT` | neutral/slate | "Stay completed on {date} at {time} by {user}." and "This reservation is closed." |
| `CANCELLED` | soft red | "Reservation cancelled on {date} at {time} by {user}." and "This reservation is closed." |
| `NO_SHOW` | amber/orange (not the cancellation red) | "Reservation marked as no-show on {date} at {time} by {user}." and "This reservation is closed." |

Subtitle times and actors come from the sources in §9.3.4.6. When a source is absent (for example a legacy Reservation with no audit row), the "on … by …" clause is omitted rather than invented. The `CONFIRMED` evidence renders the badge in a green tint that reads close to `CHECKED_IN`; the written decision (blue for `CONFIRMED`, green for `CHECKED_IN`) governs, and matches the existing `status-badge--confirmed` treatment.

##### 9.3.4.3 State configuration matrix

| | `DRAFT` | `CONFIRMED` | `CHECKED_IN` | `CHECKED_OUT` | `CANCELLED` | `NO_SHOW` |
| --- | --- | --- | --- | --- | --- | --- |
| Tabs | Overview · Notes · Audit Log | Overview · Notes · Audit Log | Overview · Folio¹ · Payments¹ · Room History · Notes · Audit Log | Overview · Folio¹ · Payments¹ · Room History · Notes · Audit Log | Overview · Notes · Audit Log | Overview · Notes · Audit Log |
| Second card title | Stay Information | Stay Information | Stay Information | Stay Information | Booking Information | Booking Information |
| State information card | — | — | — | — | Cancellation Information (soft red) | No-show Information (amber) |
| Room section | Booked Rooms | Booked Rooms | Current Room Assignment | Stay / Room History | Booked Rooms | Booked Rooms |
| Room semantics | `ReservationRoom` booking snapshot | `ReservationRoom` booking snapshot | current/open `StayRoomAssignment` | actual/final `StayRoomAssignment` history | `ReservationRoom` booking snapshot | `ReservationRoom` booking snapshot |
| Room row action (two or more rooms)⁴ | — | `Reassign` on each Booked Rooms row | `Change Room` on each Current Room Assignment row | none | none | none |
| Money card | — | Prepayment Summary² | Financial Summary¹ | Financial Summary¹ | — | — |
| Header primary | Confirm Reservation | Check In | Check Out · Change Room⁴ · Extend Stay | Open Folio¹ | none | none |
| `More` menu | Edit Reservation · Edit Booking Contact · Edit Notes · — · Cancel Reservation | Change Dates · Reassign Room⁴ · Edit Booking Contact · Edit Guest Composition · Edit Notes · Correct OTA Reference · — · Mark as No-show · Cancel Reservation | Edit Booking Contact · Edit Notes³ | none | none | none |
| Editable cards | Booking Contact, Notes | Booking Contact, Notes | none (operations above) | none | none | none |

¹ Rendered only for a user with `MANAGE_PAYMENT`; `Folio` and `Payments` also require a Stay. A user without it sees neither the tabs nor the card nor the button — never a tab that leads to 403 (§9.3.4.7).
² Rendered only for a user with `MANAGE_PAYMENT`.
³ Booking Contact and Notes remain editable through `CHECKED_IN` (technical-spec-v1 §74; §9.3.4a). The `CHECKED_IN` evidence draws no Edit on those cards; the capability is kept in the `More` menu rather than dropped (see §9.3.4.11, "Edit in `CHECKED_IN`").
⁴ Exactly one booked room (`CONFIRMED`) or exactly one open room assignment (`CHECKED_IN`): the `More` entry / header action targets that room. Two or more: the generic action is omitted and each room row carries its own action (§9.3.4.5). No room-selection dialog is used.

There is no tab or card for `Prepayments`, `Room History` before check-in, `Folio`/`Payments` before check-in or after cancellation/no-show.

##### 9.3.4.4 Action matrix — permission and eligibility

Action visibility must respect **both** the user's permission **and** business eligibility. An action that the backend would reject in every case for the current state is not offered. Hiding an action is usability only; every operation stays server-authorized and state-validated (§5 principle 4, §11), and direct URLs are rejected server-side.

| Action | States | Permission | Eligibility (UI offers only when true) | Existing operation |
| --- | --- | --- | --- | --- |
| Confirm Reservation | `DRAFT` | `MANAGE_BOOKING` | state is `DRAFT` | `POST /reservations/{id}/confirm`, confirmation dialog. Room-overlap and adult-capacity failures return as a business-error dialog. |
| Edit Reservation | `DRAFT` | `MANAGE_BOOKING` | state is `DRAFT` | `GET/POST /reservations/{id}/edit` — the only generic edit; full replacement of draft data. |
| Cancel Reservation | `DRAFT`, `CONFIRMED` | `MANAGE_BOOKING` | state is `DRAFT` or `CONFIRMED`. It is **not** hidden or disabled because an active paid prepayment exists; the backend stays authoritative. | `POST /reservations/{id}/cancel`; reason code required, detail required for `OTHER`. If the backend rejects it because of an active prepayment, the shared error dialog explains that the prepayment must be refunded or voided first and offers `View Prepayments` (to a user holding `MANAGE_PAYMENT`); closing the dialog returns focus to the workflow (§9.3.4.10). |
| Edit Booking Contact | `DRAFT`, `CONFIRMED`, `CHECKED_IN` | `MANAGE_BOOKING` | state is not terminal | `GET/POST /reservations/{id}/booking-contact` |
| Edit Notes | `DRAFT`, `CONFIRMED`, `CHECKED_IN` | `MANAGE_BOOKING` | state is not terminal | `GET/POST /reservations/{id}/notes` (§9.3.4a) |
| Change Dates | `CONFIRMED` | `MANAGE_BOOKING` | no Stay exists | `GET/POST /reservations/{id}/change-dates` |
| Edit Guest Composition | `CONFIRMED` | `MANAGE_BOOKING` | no Stay exists | `GET/POST /reservations/{id}/guest-composition` |
| Correct OTA Reference | `CONFIRMED` | `MANAGE_BOOKING` | no Stay exists and source is not `DIRECT` | `GET/POST /reservations/{id}/correct-ota-reference` |
| Reassign Room | `CONFIRMED` | `CHECK_IN` (not `MANAGE_BOOKING`) | no Stay exists. **One booked room:** `More → Reassign Room` targets that room directly. **Two or more booked rooms:** the `More` entry is omitted and each Booked Rooms row carries its own `Reassign` action. | `GET/POST /check-in/reservations/{id}/rooms/{roomId}/reassign` — the existing `(reservationId, roomId)` route; pre-check-in reassignment, not Room Change. No room-selection dialog and no new workflow. Entered from Reservation Detail it **returns to Reservation Detail** (and carries the Detail breadcrumb) using a fixed, whitelisted return context, never an arbitrary return URL; entered from Check-in Review it still returns to the Review. |
| Mark as No-show | `CONFIRMED` | `MANAGE_BOOKING` | **check-in date is before the hotel business date** | `POST /reservations/{id}/no-show`; reason required. The rule lives in `ReservationService.noShow`; the page must not carry a second copy of it (§9.3.4.12). A rejection caused by an active prepayment uses the same prepayment error dialog as Cancel. |
| Check In | `CONFIRMED` | `CHECK_IN` | no Stay exists and the hotel business date is on or after the check-in date | opens Check-in Review `GET /check-in/reservations/{id}`; confirmation is `POST /check-in/reservations/{id}/confirm`. Room readiness blockers are shown on Check-in Review (§9.2.6), not hidden here. |
| Check Out | `CHECKED_IN` | `CHECK_OUT` | state is `CHECKED_IN` | opens Checkout Review `GET /check-out/{id}`; outstanding and overdue rules are enforced and explained there (§9.2.2). |
| Change Room | `CHECKED_IN` | `CHANGE_ROOM` | the Stay has a current/open room assignment. **One open assignment:** the header `Change Room` targets it. **Two or more:** the header action is omitted and each Current Room Assignment row carries its own `Change Room`. | `GET /reservations/{id}/rooms/{roomId}/change` — the existing `(reservationId, roomId)` route; the source room is fixed by the route and the form offers no source selector. Adult-capacity validation for Change Room is unchanged by Task 33 (§9.3.4.12, backlog). |
| Extend Stay | `CHECKED_IN` | `EXTEND_STAY` | state is `CHECKED_IN` | `GET /reservations/{id}/stay-extension` (§9.3.4b) |
| Open Folio | `CHECKED_IN`, `CHECKED_OUT` | `MANAGE_PAYMENT` | a Stay exists | `GET /reservations/{id}/folio`. Exactly **one** Open Folio call-to-action per Overview: inside Financial Summary for `CHECKED_IN`, in the header for `CHECKED_OUT`. |
| View Prepayments | `CONFIRMED` | `MANAGE_PAYMENT` | state is `CONFIRMED` | `GET /reservations/{id}/prepayments` — the Prepayments page lists the Reservation's prepayments and hosts Record, Refund, and Void (existing operations, rules, and permissions). |

Not offered in any state: cancel or no-show after check-in, editing a terminal Reservation, reopening or reinstating a cancelled/no-show Reservation, hard delete, and any Reservation-level financial mutation. `Reassign Room` before check-in and `Change Room` after check-in are different operations (§9.2.6, §9.5).

##### 9.3.4.5 Room semantics

The Reservation's booking snapshot and the Stay's operational room state are different facts and are never mixed in one table or labelled as one another.

- `DRAFT`, `CONFIRMED`, `CANCELLED`, `NO_SHOW` → **Booked Rooms**, from the immutable `ReservationRoom` snapshot (room, room type, adult capacity, nightly rate, nights, total, plus the Reservation Total). For `CANCELLED` and `NO_SHOW` the snapshot is the historical record of what was booked; the page uses "Booking Information" and never implies that a Stay occurred. Pre-check-in Reassign Room changes the snapshot's room only (rate, total, and dates unchanged).
- `CHECKED_IN` → **Current Room Assignment**, from the current/open `StayRoomAssignment` rows: room, room type, adult capacity, nightly rate, assigned-from, expected check-out (the Reservation's current planned check-out, which Stay Extension moves), and a `Current` badge. The nightly rate is the rate of the originally booked `ReservationRoom` line that the assignment descends from (the lineage the backend keeps through Room Change and Stay Extension). It must **not** be looked up by matching the current Room ID against the booked rooms: after a Room Change that match fails and would show no rate. The assigned-from value is the start of the current assignment, which after a Room Change is the change instant, so the column is labelled "Assigned From" rather than "Check-in" (§9.3.4.11).
- `CHECKED_OUT` → **Stay / Room History**, from the actual/final `StayRoomAssignment` history (**Room, Room Type, Assigned From, Assigned To**; no calculated per-segment Nights column, which would invent room-night semantics for mid-stay room changes). The Room History tab carries the full history including reason and changed-by. Final rooms come from the existing final-rooms read model, not from the (now closed) current-room query.
- The original booking snapshot stays immutable and retrievable in the backend after Room Change. After check-in the Detail does not render a second "Booked Rooms" table (the evidence shows none); where a booking figure matters it appears as a labelled booking value (for example Original Booking Total once a Stay has been extended).
- Room Code links to Room Detail where the user holds `MANAGE_ROOM`.
- **Multi-room contextual actions.** The room being replaced is always identified by the existing `(reservationId, roomId)` route: a room appears at most once per Reservation, and at most one open assignment exists per room. Pre-check-in `Reassign` (Booked Rooms rows) and post-check-in `Change Room` (Current Room Assignment rows) therefore act on exactly the row they sit in. A generic action that is not tied to a row exists only when there is exactly one room. The row-action column scrolls with the table on small screens and is never frozen or sticky. No room-selection dialog or new backend workflow is introduced.

##### 9.3.4.6 Information cards and data sources

All values below come from existing backend data. **No persistence field is added** to reproduce a timestamp or actor drawn in the evidence.

| Card / row | Source |
| --- | --- |
| Guest Information — Guest Code, Full Name, Phone, Email, Nationality | Primary Guest profile via the existing guest lookup, loaded for every state (not only `CHECKED_IN`). Date of birth and any ID/passport number are not shown (§9.2.5). Guest Code is the navigation link to Guest Detail; the card has no Edit control. |
| Stay / Booking Information — dates, Nights, Adults, Children | Reservation planned dates and guest counts. Nights is a single value from the current planned dates (Stay Extension already moves the planned check-out); there is no separate "planned" and "current" Nights. `CHECKED_IN` and `CHECKED_OUT` add the Stay's actual check-in and actual check-out (Planned vs Actual) from the Stay; Actual Check-out shows "—" while `CHECKED_IN`. After a Stay Extension the extension totals (Original Booking Total, Extension Amount, Current Accommodation Total) remain shown, as today. |
| Reservation Information — Status, Source, OTA Booking Reference, Reserved At, Created By, Last Updated At | Reservation fields (`reservedAt`, created-by username, `updatedAt`). OTA reference is "—" for `DIRECT`. |
| Reservation Information — Last Updated By | The existing audit `updatedBy`, resolved to a username like Created By (bounded read-model addition, §9.3.4.12). |
| Reservation Information — Confirmed At / Confirmed By; Checked In By; Checked Out By; Cancelled At/By; Marked No-show At/By | Time and actor of the corresponding lifecycle event read from the existing Reservation audit history (action code, time, actor only — never old/new values). Check-in At and Checked Out At come from the Stay's actual timestamps. Absent for legacy Reservations with no audit row: show "—". |
| Cancellation Information — Cancelled At, Cancelled By, Reason | Time/actor as above; Reason is the stored cancellation reason code label plus the optional detail. A legacy Reservation shows the neutral "No reason recorded". |
| No-show Information — Marked No-show At, Marked No-show By, Reason | Time/actor as above; Reason is the stored free-text no-show reason, or "No reason recorded" for legacy rows. |
| Booking Contact — Name, Phone, Email | The effective Booking Contact. When none is stored and the Primary Guest is used as the read fallback, a visible fallback notice stays (technical-spec-v1 §74). |
| Notes | The single `Reservation.notes` value, read-only text. `DRAFT` and `CONFIRMED` show the existing `used / 5000` length as a non-interactive caption. |

##### 9.3.4.7 Financial boundary

Reservation Detail is **not** a replacement for Folio. It shows a state-appropriate summary and navigation; detailed Charge and Payment lists and every Charge/Payment operation stay in the existing Folio workflow (§9.6) under the existing permissions.

- Money cards, the Folio and Payments tabs, `Open Folio`, and prepayment figures require `MANAGE_PAYMENT`. A user without it sees none of them (no dead or 403-bound navigation). `CHECK_OUT` alone never grants detailed financial data.
- Reservation Total and the booking-snapshot room figures remain visible to any user who can view the Reservation (existing behaviour); the Activity timeline shows that a financial event happened but never its amount.
- `CONFIRMED` — **Prepayment Summary**: **Prepaid** (the existing active-prepayment total) and **Reservation Total**, plus `View Prepayments`. No "Remaining" or any other derived amount is shown; Task 33 introduces no new financial calculation.
- `CHECKED_IN` — **Financial Summary**: Room Charges, Additional Charges (non-`ROOM` Charges), Payments, Outstanding, the note "For detailed charges and payments, please open Folio.", and the single `Open Folio` button. Outstanding follows the existing canonical balance calculation.
- `CHECKED_OUT` — **Financial Summary**: Room Charges, Additional Charges, Total Charges, Payments, Outstanding, and the staff-friendly folio indicator below. No `Open Folio` inside the card (the header carries it). No post-checkout Charge/Payment mutation is offered: the V1 folio is closed (§9.6.1, §9.6.2).
- `Folio` and `Payments` tabs are **navigation tabs** to the matching sections of the existing Folio page (`/reservations/{id}/folio?tab=charges` and `?tab=payments`); they leave Detail and do not duplicate the Folio lists on Detail.
- **Prepayment Refund and Void are not on Reservation Detail.** Detail holds only the prepayment summary and `View Prepayments`. Refund and Void are performed on the Prepayments page (`/reservations/{id}/prepayments`), which Task 33 extends to list the Reservation's prepayments and offer the existing Refund and Void operations (an approved, bounded enhancement). All existing prepayment business rules and permissions are preserved; the financial domain is not redesigned.
- **Folio indicator** (replaces the technical "Financial Integrity" presentation). Inside Financial Summary, for `MANAGE_PAYMENT` users, a small plain-language indicator is shown only where it is meaningful: "✓ Folio settled" when Outstanding is zero and the existing reconciliation reports no problem (the normal `CHECKED_OUT` state, and `CHECKED_IN` when the balance is zero); "Folio needs review" when the existing reconciliation reports a problem, with the folio reached through `Open Folio`. Nothing else is shown: no "integrity" terminology, issue types, or implementation detail. During a stay with an Outstanding balance no indicator is shown (the Outstanding tile already says it). It is diagnostic only and never gates check-out.

##### 9.3.4.8 Recent Activity and Audit Log

- **Recent Activity** is a compact preview in the right-hand column: the most recent entries (limit 5, matching the Folio preview), **newest first**, each with the localized action label, date-time, and actor. The newest entry's marker uses the state tone (green, blue, red, amber); older entries use a neutral marker. `View All` (secondary button, icon left) opens the Audit Log tab.
- **Audit Log** is a tab of the same page listing the complete history (Time / By / Activity) from the same Reservation audit source, **newest first**, like the preview. Newest-first is a presentation order only: the audit source query and its stored order are unchanged (technical-spec-v1 §79) and the page reverses that order deterministically. Recent Activity is shown in every state, including `DRAFT`, so the Created/Updated history is always visible.
- Both show only the time, the actor's username, and the localized action label from the `reservation.activity.action.*` keys, with the generic fallback label for an unknown action. **Raw audit `oldValue`/`newValue` payloads, financial amounts, notes content, contact content, and reasons are never shown or parsed** (technical-spec-v1 §79). Sub-lines such as "Rooms: 101" drawn in the older evidence are not supported. Sample wording in the evidence ("Guest checked in", "Payment received") is not a requirement; the existing localized labels apply.
- Permission is `VIEW_BOOKING`, the same as the page. The tab is rendered in every state.

##### 9.3.4.9 Terminal-state immutability

`CHECKED_OUT`, `CANCELLED`, and `NO_SHOW` visibly communicate read-only history: the closed subtitle line (§9.3.4.2), the state-toned status badge, the Cancellation/No-show Information card, no `More` menu, and no Edit buttons on any card. Edit controls are never rendered "for visual consistency". The only header action is `Open Folio` for `CHECKED_OUT` with `MANAGE_PAYMENT`. This is the approved terminal-state presentation and replaces the earlier requirement for a separate "prominent banner" element: the subtitle plus the state card are the banner.

##### 9.3.4.10 Validation, dialogs, and responsive behaviour

- Actions that collect input (Cancel Reservation: reason code and detail; Mark as No-show: reason) open a dialog from the `More` menu. Destructive actions confirm before executing (§5 principle 5).
- Form validation failure follows the Task 33 convention: **shared error-dialog summary → inline field errors → close the dialog → focus and scroll to the first invalid field**, with all entered data preserved. The legacy full-width validation-error banner above the page content is not used. Business-rule failures (for example an active prepayment blocking cancellation or no-show, or a no-show attempted too early) use the global business-error dialog (§10). Server-side validation remains authoritative.
- **Cancel rejected because of an active prepayment:** the global business-error dialog states, in plain language, that the prepayment must be refunded or voided first, offers `View Prepayments` to a user holding `MANAGE_PAYMENT`, and on close returns focus to the workflow (the Cancel dialog or the `More` control). Cancel Reservation is never hidden in anticipation of this rejection.
- Tables (Booked Rooms, Current Room Assignment, Stay / Room History, Audit Log) scroll horizontally inside their container when the viewport is narrow. Row-action columns (`Reassign`, `Change Room`) scroll with the table like any other column on narrow screens; the final Action column is **not** frozen, sticky, or fixed; columns never overlap; identifiers and monetary values stay readable (§7.2).
- The summary strip wraps; the three-card row and the two-column body collapse to one column on narrow viewports; header actions wrap beneath the title without dropping any action.

##### 9.3.4.11 Capability reconciliation of the evidence

Classes: `SUPPORTED`, `SUPPORTED_WITH_UI_WORK`, `BACKEND_GAP` (a bounded read-model addition, §9.3.4.12), `SPEC_CONFLICT` (resolved as stated), `REMOVE_FROM_UI`, `NEEDS_PRODUCT_DECISION` (none remain open for Reservation Detail; §9.3.4.12).

| Element in the evidence | Class | Resolution |
| --- | --- | --- |
| Header actions per state (§9.3.4.4) | SUPPORTED / SUPPORTED_WITH_UI_WORK | Each maps to an existing operation. Eligibility-gated visibility needs the read-model flags in §9.3.4.12. |
| Primary Guest name/phone/email/nationality in every state | SUPPORTED_WITH_UI_WORK | Guest lookup is currently loaded for `CHECKED_IN` only. |
| Guest Information "Edit" (`DRAFT`, `CONFIRMED`) | REMOVE_FROM_UI | Decision 1. No reservation operation changes the Primary Guest after Confirm, and Guest profile editing belongs to the Guests module. Guest Code is the navigation link to Guest Detail. |
| Room Type under the room code, Room Type column | SUPPORTED_WITH_UI_WORK | Booked-room DTO has no room type; enrich through the existing room lookup, as already done for current rooms. |
| Capacity column ("2 adults") | BACKEND_GAP | The room-type adult capacity exists (and may be unconfigured, shown as "—") but is not on the booked-room read model. |
| Confirmed At/By, Checked In By, Checked Out By, Cancelled At/By, No-show At/By | SUPPORTED_WITH_UI_WORK | Derived from the existing audit history (§9.3.4.6). No new column. |
| Last Updated By | BACKEND_GAP | `updatedBy` exists on the audited entity but is not in the detail read model. |
| Check-in At / Check-out At / Planned vs Actual | SUPPORTED_WITH_UI_WORK | `Stay.actualCheckInAt/actualCheckOutAt` are already in the page model and unused. |
| "Nights (Planned)" and "Nights (Current)" (`CHECKED_IN`) | REMOVE_FROM_UI | Decision 2. One "Nights" value from the current planned stay dates (Stay Extension already moves the planned check-out). |
| Per-segment "Nights" in Stay / Room History | REMOVE_FROM_UI | Decision 7. The table is Room, Room Type, Assigned From, Assigned To; no calculated per-segment Nights. |
| Stay / Room History Assigned From / Assigned To | SUPPORTED | Assignment `assignedFrom` / `assignedTo`. Reason and Changed-by stay in the Room History tab. |
| Current Room Assignment "Check-in" column | SPEC_CONFLICT (resolved) | Decision 10. The value is the current assignment's start, which differs from the stay's check-in after a Room Change, so the column is "Assigned From". |
| Rate in Current Room Assignment | SUPPORTED_WITH_UI_WORK | Use the lineage rate (§9.3.4.5), not a Room-ID match. |
| Prepayment Summary: Prepaid, Reservation Total | SUPPORTED_WITH_UI_WORK | From the existing prepayment summary ("Prepaid" is the existing active-prepayment total). |
| Prepayment Summary: "Remaining" | REMOVE_FROM_UI | Decision 3. Not a defined business figure; no new calculation is introduced. |
| `View Prepayments` destination and prepayment Refund/Void | SUPPORTED_WITH_UI_WORK | Decision 11. The Prepayments page is extended to list prepayments and host the existing Refund and Void operations; Detail keeps only the summary. |
| Financial Summary: Room Charges vs Additional Charges | BACKEND_GAP | Total Charges/Payments/Outstanding exist; the ROOM vs non-ROOM split does not. "Additional Charges" means non-`ROOM` Charges and is unrelated to the Additional Revenue module. |
| Financial Summary: Payments, Outstanding, "✓ Folio settled" | SUPPORTED | Existing balance calculation (§9.3.4.7). |
| Financial Integrity (technical) | SPEC_CONFLICT (resolved) | Decision 8. technical-spec-v1 requires the diagnostic on Detail; it is shown only as the small staff-friendly folio indicator ("✓ Folio settled" / "Folio needs review"), SUPPORTED_WITH_UI_WORK from the existing reconciliation and balance. No technical terminology. |
| Folio / Payments tabs | SUPPORTED_WITH_UI_WORK | Navigation to Folio page sections; `MANAGE_PAYMENT` only (§9.3.4.7). |
| Recent Activity newest-first, limited, `View All`; Audit Log newest-first | SUPPORTED_WITH_UI_WORK | Decision 6. Presentation reversal and limit of the existing chronological audit source. |
| Recent Activity absent from the `DRAFT` evidence | SPEC_CONFLICT (resolved) | Decision 9. Shown in every state, including `DRAFT`, for shell consistency and the Created/Updated history. |
| Activity sub-lines ("Rooms: 101", "Source: Direct") | REMOVE_FROM_UI | Would require reading raw audit values (technical-spec-v1 §79). |
| Reassign Room (`CONFIRMED`) | SPEC_CONFLICT (resolved) / SUPPORTED_WITH_UI_WORK | Decision 5. One booked room: `More → Reassign Room`. Two or more: no `More` entry; a `Reassign` action on each Booked Rooms row. §9.2.6 limits the Check-in Review surface only. Uses the existing `(reservationId, roomId)` route; returns to Detail through a whitelisted fixed return context (bounded controller work, no service change). |
| Mark as No-show only when date-eligible; Check In only when eligible | SUPPORTED_WITH_UI_WORK / BACKEND_GAP | Eligibility is evaluated by the backend rules, not re-implemented in the page (§9.3.4.12). |
| Edit on Booking Contact / Notes cards in `CHECKED_IN` | SPEC_CONFLICT | Evidence omits it; backend and §9.3.4a allow editing through `CHECKED_IN`. Kept in the `More` menu (§9.3.4.3 note 3). |
| Terminal "prominent banner" (earlier §8.2 / §9.3.4 wording) | SPEC_CONFLICT | Superseded by the subtitle plus state card (§9.3.4.9); §8.2 corrected in place. |
| Underlined Guest/Room links; trailing external-link glyph on `View Prepayments` | SPEC_CONFLICT | Superseded by the link and button conventions in §9.3.4.1. |
| "Special Requests", "ID / Passport Number", "Add Note", "Add Adjustment", "Add Additional Revenue", "Add Payment", "Direct (Walk-in)" | REMOVE_FROM_UI | No backing capability or explicitly rejected (§9.2.5, §9.3.4a, §9.6.1). |
| Notes length caption "n / 5000" | SUPPORTED_WITH_UI_WORK | Display only, from the existing limit; the Notes card is never inline-editable. |
| Change Room (`CHECKED_IN`) with several rooms | SUPPORTED | Decision 5. One open assignment: header `Change Room`. Two or more: no header action; a `Change Room` action on each Current Room Assignment row (existing per-room behaviour). Existing route; no selection dialog. |
| Cancel Reservation when an active paid prepayment exists | SUPPORTED_WITH_UI_WORK | Decision 4. Stays visible; the backend rejection opens the shared error dialog with `View Prepayments` (§9.3.4.10). |
| Summary-strip Room item with several rooms | SUPPORTED_WITH_UI_WORK | Room codes listed as links (where permitted) with the room count beneath; follows the per-room principle of decision 5. |
| Adult-capacity validation on post-check-in Change Room | not changed by Task 33 | Decision 12. technical-spec-v1 §66.2 deliberately defers it; it remains separate product/domain backlog. |
| Language flag in the top bar | not a Detail element | Global header (§6.2); unchanged by this section. |

##### 9.3.4.12 Bounded backend work and locked product decisions

**Bounded backend / read-model work implied by this section.** No schema migration, no new persisted field, no new permission, no new lifecycle transition, no change to any business rule.

1. Add `updatedBy` (username) and per-booked-room room type and adult capacity to the Reservation Detail read model. (BACKEND_GAP)
2. Expose the latest time/actor per lifecycle action (confirm, check-in, check-out, cancel, no-show) from the existing audit history through the existing activity query service, without exposing old/new values. (SUPPORTED_WITH_UI_WORK)
3. Expose No-show eligibility and Check-in eligibility to the page by reusing the **same rule** the mutating operation enforces (hotel business clock), so each rule has one source. (BACKEND_GAP)
4. Provide the ROOM vs non-ROOM Charge split inside the canonical stay-balance calculation. (BACKEND_GAP)
5. Add room type to the Room History read model; use the existing lineage-rate and final-rooms read models for Current Room Assignment and Stay / Room History. (BACKEND_GAP for room type; the others SUPPORTED_WITH_UI_WORK)
6. Make Reassign Room return to Reservation Detail, with the Detail breadcrumb, when launched from Detail, using a fixed whitelisted return context (never an arbitrary URL); behaviour from Check-in Review is unchanged. (SUPPORTED_WITH_UI_WORK; controller only)
7. Extend the existing Prepayments page to list a Reservation's prepayments and host the existing Refund and Void operations, so Detail shows a summary only. (SUPPORTED_WITH_UI_WORK; existing endpoints, rules, and permissions)
8. Support the dialog → inline-errors → focus validation flow for Cancel and No-show using the existing server-side validation, and the prepayment-rejection dialog with `View Prepayments` (§9.3.4.10). (SUPPORTED_WITH_UI_WORK)
9. Drive the folio indicator from the existing reconciliation and balance results (§9.3.4.7). (SUPPORTED_WITH_UI_WORK)

**Locked product decisions.** All Reservation Detail product decisions are resolved; none is open.

| # | Decision | Resolution |
| --- | --- | --- |
| 1 | Guest Information Edit | Removed. Guest Code links to Guest Detail; Guest profile editing belongs to the Guests module. |
| 2 | Nights | One value from the Reservation's current planned stay dates. No "Planned"/"Current" pair. |
| 3 | Pre-stay Remaining | Not shown. Only Reservation Total and Prepaid; no new financial calculation. |
| 4 | Cancel with active prepayment | Cancel stays visible when lifecycle and permission allow. On backend rejection: shared error dialog, "refund or void the prepayment first", `View Prepayments` where appropriate, focus returns to the workflow. |
| 5 | Multi-room Reassign / Change Room | One room: `More → Reassign Room` (CONFIRMED) / header `Change Room` (CHECKED_IN) targets it. Two or more: generic action omitted; per-row `Reassign` on Booked Rooms and per-row `Change Room` on Current Room Assignment. Existing `(reservationId, roomId)` routes; no selection dialog; row-action column scrolls, never frozen. CONFIRMED Reassign returns to Detail through a whitelisted fixed return context. |
| 6 | Ordering | Audit Log and Recent Activity are newest first. |
| 7 | Room History segment Nights | No per-segment Nights. Columns: Room, Room Type, Assigned From, Assigned To. |
| 8 | Financial integrity | A small plain-language indicator ("✓ Folio settled" / "Folio needs review") only where meaningful; no technical terminology. |
| 9 | Recent Activity on DRAFT | Shown. |
| 10 | Assignment start label | "Assigned From", not "Check-in". |
| 11 | Prepayment Refund / Void | On the Prepayments page only; Detail keeps the summary and navigation. Existing prepayment rules and permissions preserved. |
| 12 | Change Room adult capacity | Not changed by Task 33; deliberately deferred by technical-spec-v1 §66.2 and kept as separate product/domain backlog. |
| 13 | Summary-strip Room item with several rooms | Room codes listed as links where permitted, room count beneath (follows decision 5's per-room principle). |

**Backlog outside Task 33 (not blockers):** adult-capacity validation for post-check-in Room Change (decision 12).

#### 9.3.4a Reservation Notes (evidence override)

`Reservation.notes` is a single mutable free-text field (existing backend field, edited through the existing dedicated `GET/POST /reservations/{id}/notes` controlled operation) — it is not a comment thread, not a set of multiple timestamped entries, and not a staff-messaging feature. Reservation Detail's Notes tab/section shows the current value of this one field and, when the Reservation is `DRAFT`, `CONFIRMED`, or `CHECKED_IN` and the user holds the existing notes-edit permission, an Edit action that replaces the whole field value in one controlled update. It is read-only once the Reservation is `CHECKED_OUT`, `CANCELLED`, or `NO_SHOW`, consistent with the rest of this matrix. In the shared Detail shell (§9.3.4) the Edit action appears on the Notes card for `DRAFT` and `CONFIRMED` and in the `More` menu for `DRAFT`, `CONFIRMED`, and `CHECKED_IN`.

**Evidence override**: where a mockup/evidence image shows Notes as a multi-entry thread, a list of separately timestamped notes, or an "add note" affordance that appends rather than replaces, that illustration is incorrect — this section's single-field, replace-in-place behavior is authoritative. The separate Activity/History tab (audit timeline) remains the correct place for a chronological record of actions; it is not merged with Notes.

#### 9.3.4b Stay Extension (evidence override)

`Extend Stay` (`CHECKED_IN` only, see the action matrix above) changes only the Stay's planned check-out date. The approved form accepts the new check-out date (and the currently-displayed check-out date, used only for stale-request detection) and nothing else.

- **Evidence override**: a mockup/evidence image (`extend-stay-final.png`) that shows an editable Room Type or Rate Type dropdown on the Extend Stay form is incorrect and must not be implemented. V1 Stay Extension has no room or rate selection control.
- Pricing for the extended nights is always based on the existing/original `ReservationRoom` nightly-rate lineage already attached to the room(s) currently assigned to the Stay; Stay Extension never accepts a different rate or room type from the user and never invents a new price.
- Changing to a different physical room during an active Stay is Room Change (§9.5), a separate operation; Stay Extension does not combine with or substitute for it.
- The Review/confirmation step (`extend-stay-review-charges-final.png`) may display Original Booking Total, Extension Amount, and Current Accommodation Total (all derived, read-only) consistent with the approved evidence, but these remain presentation of backend-calculated values, not editable inputs.

### 9.4 Guests

#### 9.4.1 Guest List and Detail

- List supports approved search/filter controls and Add Guest.
- Clicking a guest opens Guest Detail.
- Guest Detail uses `Overview`, `Stays`, `Documents`, and `Notes` tabs.
- `Stays` links to the selected stay/reservation and supports Create Reservation.
- Edit is permission-controlled and preserves the existing guest business rules.

#### 9.4.2 Passport Documents — GAP-04 closed

Flow:

`Guest Detail → Documents → Upload / View / Replace / Delete`

- Support multiple passport images.
- Accepted types: JPG and PNG only.
- Maximum file size: 5 MB per image.
- Documents are private and may be viewed only through an authenticated, authorized preview.
- Do not show a PDF/download toolbar and do not expose a direct public download flow.
- Passport images must never be used as the guest avatar.
- `Replace` and `Delete` are separate permission-controlled actions.
- Replace requires confirmation, then updates the document list/preview.
- Delete requires confirmation, then updates the document list.
- Required states: invalid file type, file too large, upload failure, permission denied, loading, and successful upload/replace/delete.
- Retention and storage behavior remain governed by the approved passport-document security/business specification; Task 33 does not redefine them.

### 9.5 Rooms

`Rooms` is the sidebar parent/group described in §7.1a; `Room List` is its existing rooms-list destination and `Housekeeping` is its existing housekeeping destination. This section describes `Room List`; housekeeping business rules are unchanged by Task 33.

- Room List supports approved search/filter/status presentation and Add Room where permitted.
- Clicking a room opens Room Detail.
- Room Detail shows information, current stay, maintenance/history, and occupancy context as applicable.
- Linked guest, stay, and reservation identifiers open their respective details.
- `Edit`, `Mark as Out of Order`, occupancy calendar, history, and reservation actions are permission- and state-aware.
- Room Change uses the approved dedicated workflow, not generic reservation editing.

Room Change rules preserved by the UI:

- Reason is required: `GUEST_REQUEST`, `ROOM_ISSUE`, `UPGRADE`, `DOWNGRADE`, `OPERATIONAL`, or `OTHER`.
- Notes are optional except required for `OTHER`.
- The new room cannot be the current room.
- The new room must be usable and available for the remaining stay; availability is revalidated under lock at confirmation.
- Multiple sequential changes and returning to a previously used room are allowed when currently valid.
- History is immutable (`StayRoomAssignment` is append-only except for closing the open interval).
- **Room Change state transition (corrected)**: on successful confirmation, the old (current) room transitions `OCCUPIED` → `DIRTY` immediately, in the same transaction as the change — it does not become `AVAILABLE`, and the `DIRTY` transition is not deferred to checkout. The new room transitions `AVAILABLE` → `OCCUPIED`. The vacated room then follows the existing Housekeeping lifecycle (`DIRTY` → `CLEANING` → `AVAILABLE`, §7.1a) before it is check-in-ready again. **Evidence override**: an earlier version of this document, and the `change-room-final.png` evidence image/flow description, stated or implied that the old room becomes `AVAILABLE` directly or that the `DIRTY` transition happens at checkout; both are incorrect. This corrected rule is authoritative, not that earlier text or the evidence image — `RoomChangeService.changeRoom` (via `Room.releaseForRoomChange()`) already implements it correctly today; only this document's wording was wrong.
- Room change does not automatically change reservation pricing unless an existing approved rule/action explicitly does so.
- **Not supported in V1 (evidence override)**: a mockup/evidence image may show a "High floor preferred" toggle, a floor filter, or a capacity filter on the replacement-room candidate list. None of these exist in the backend candidate-room query (`RoomChangeService.candidateRooms`) and none is approved for V1; do not implement them. `Room.floor` is not exposed as a filter and no new `Room.floor`-based filtering capability is approved. `RoomType.capacity` is existing backend data and may be shown as a read-only display value on a candidate room row where useful, but it is not an eligibility filter — eligibility/availability stays fully backend-rule-determined (`RoomChangeService.candidateRooms`/`changeRoom`), unaffected by what is or is not displayed.

### 9.6 Finance

#### 9.6.1 Folio

- Folio provides stay/reservation context, balance summary, Charges, Payments, Outstanding, and checkout readiness.
- Financial amounts and mutation actions require the existing financial permissions.
- Users with checkout-only access see checkout readiness but not protected financial detail.
- `CHECKED_OUT` folio is read-only (§9.6.2 restates this for the correction actions specifically). This is a usability mirror of the server-side `StayStatus.CHECKED_IN` gate already enforced in `ChargeService`/`PaymentService`, not a UI-invented authorization boundary — hiding a mutation control here never substitutes for that server-side check (§5, principle 4).
- **Approved mutation actions only**: Add Charge (a supported non-`ROOM` Charge type — `BREAKFAST`, `EXTRA_BED`, `LAUNDRY`, `MINIBAR`, `SERVICE`, or `OTHER`), Void Charge (eligible manual non-`ROOM` Charge only, §9.6.2), Record Payment, Refund Payment (whole payment only, §9.6.2), and Void Payment (§9.6.2).
- **Not supported in V1 — do not implement even where a mockup/evidence image shows it**: "Add Room Charge" (`ROOM` Charges are created only by the system, at check-in and by Stay Extension, never manually — `ChargeService.create` rejects a manual `ROOM` type outright), "Add Adjustment" (there is no `ADJUSTMENT` Charge type and none is approved), "Edit Charge" (a posted Charge is corrected only through Void, never edited in place), and "Edit Payment" (a posted Payment is corrected only through Void or Refund, never edited in place). Any `folio-charges-final.png`/`folio-payments-final.png`/`folio-overview-final.png` evidence control matching these names is superseded by this list.

#### 9.6.2 Folio correction flow — GAP-03 closed

Charges tab:

- `Void Charge` is available only for a manual, non-`ROOM` charge.
- Collect a required reason, show confirmation, then mark the charge `VOIDED`.
- Room charges cannot be voided through this action.

Payments tab:

- `Refund Payment` shows Payment Amount as read-only and supports whole refund only.
- Partial refund is not supported.
- **Evidence override**: a mockup/evidence image showing an editable Refund Amount input is incorrect; `PaymentRefundRequest` has no amount field at all — Refund Payment is reason-only and always refunds the full Payment amount. Do not add an editable amount field to this flow.
- Confirming the refund marks the payment `REFUNDED`.
- `Void Payment` requires a reason and confirmation, then marks the payment `VOIDED`.

Audit behavior:

- `VOIDED` and `REFUNDED` records remain visible in their tables and audit history.
- A corrected transaction cannot be corrected again.
- All correction actions are hidden in a `CHECKED_OUT` folio.

#### 9.6.3 Additional Revenue and Expenses

- Separate list and create/edit flows follow the approved mockups.
- Search, date/category filters, pagination, amount formatting, attachment/receipt access, and row actions use shared patterns.
- Categories are read-only reference data for authorized users; the final navigation (§7.1) has no generic `Categories` sidebar item, so category reference data stays reachable from within the Additional Revenue/Expenses flows it supports, not as its own Administration destination.
- Validation is inline; failed save/delete/business actions follow the global feedback contract.
- Task 33 does not change expense/additional-revenue accounting semantics.

### 9.7 Reports

`Reports` is one sidebar entry under FINANCE (§7.1b). Inside it, V1 contains exactly these views/destinations, reached from within Reports rather than from separate sidebar items:

- Overview / Monthly Hotel Performance (§9.7.1), including its own Revenue Trend chart/section.
- Monthly Financial Report.
- Monthly Occupancy Report.

There is no separate "Revenue" report destination. An earlier version of this document listed `Revenue` as its own report destination; that was stale — the existing backend defines Monthly Financial Report and Monthly Occupancy Report as the two drill-in reports, with revenue trend data presented as a section of Overview, not a standalone report route. Do not invent a standalone Revenue report route on the strength of the older listing.

Reports are available only to users with the existing report permission.

#### 9.7.1 Overview / Monthly Hotel Performance

The approved page is one vertically scrollable report with:

- Month selector with previous/next controls.
- `Export PDF` and `Export Excel`.
- KPI cards: Total Revenue, Total Expenses, Net Profit, Occupancy Rate.
- Revenue Trend and Reservation Source.
- Occupancy Rate and Room Type Performance.
- Financial Summary and Top Additional Revenue.
- Approved click-tabs/drill-ins for Room Type Performance, Financial Summary, and Additional Revenue.

Dashboard remains an operational page; Reports Overview is an owner/manager analytical page.

#### 9.7.2 Export contract

- PDF uses the approved one-page A4 Monthly Hotel Performance layout.
- Excel uses the approved final editable workbook template.
- Web, PDF, and Excel share the same approved report data semantics.
- Task 33 may align presentation with the newer report visual style but must not alter calculations, definitions, or data scope.
- Export actions show loading and success/failure feedback; they are file outputs, not application screens.

### 9.8 Staff Management

Staff and application users are separate concepts. A staff member may optionally link to one application user.

Required screens/flows:

- Staff List with search and status filtering.
- Staff Detail.
- Create Staff.
- Edit Staff.
- Deactivate and Reactivate with confirmation.
- Work Records — By Date.
- Work Records — By Staff.

Staff fields include staff code, name, phone, email, position, status, and optional linked user according to the approved domain specification. Position is not a security role.

Work Records:

- By Date supports bulk entry of Start, End, and Notes.
- Working Time is calculated automatically.
- By Staff shows history filtered by From/To dates.
- Save uses validation, loading, success feedback, and business-error preservation rules.

### 9.9 Users

Required screens/flows:

- User List with search, role filter, status filter, pagination, and Add User.
- User Detail with Information and Permissions views.
- Create User.
- Edit User.
- Reset Password.
- Activate/Deactivate with confirmation.

Rules:

- User is distinct from Staff and may have an optional one-to-one staff link.
- Username is immutable after creation.
- Each user has one built-in role.
- The last active `ADMIN` and protected self-actions must obey the approved safeguards.
- Deactivating a linked staff member locks/deactivates the linked user according to the approved business rule.
- User management is `ADMIN`-only through the existing `MANAGE_USER` permission.

### 9.10 Roles & Permissions

- Fixed roles: `ADMIN`, `MANAGER`, `STAFF`.
- No role create, rename, or delete flow.
- One permission-matrix screen groups permissions by Dashboard, Reservations, Guests, Rooms, Finance, and Administration.
- The matrix reflects database state, not hardcoded display-only assumptions.
- `MANAGE_USER` is locked `ON` for `ADMIN` and locked `OFF` for `MANAGER` and `STAFF`.
- Only the checkbox/control itself toggles a permission; clicking the entire row must not accidentally change it.
- Save is transactional and creates the approved `ROLE_PERMISSION_CHANGE` audit record.
- Updated authority is effective on the next authorized request according to the approved security behavior.
- On narrow screens, the matrix uses contained horizontal overflow without losing role/permission labels.

Navigation visibility follows permission assignments, but backend authorization remains mandatory.

## 10. Global feedback contract — GAP-05 closed

| Situation | Required UX |
| --- | --- |
| Successful action | Toast; keep wording concise and name the completed action |
| Non-blocking warning | Toast or inline warning; do not use a blocking popup if the user may continue |
| Field validation | Red/invalid field treatment plus inline message; focus/scroll to the first error |
| Long or multi-step validation | Error summary at the top plus field-level errors |
| Business-rule failure | Global error dialog/popup; preserve page/form context and all entered data |
| System failure | Global error dialog/popup with safe wording; preserve context when possible |
| Destructive action | Confirmation dialog before execution |
| Loading/submission | Disable the initiating action, show progress, and prevent double submit |
| Empty dataset | Contextual empty state and permitted primary action |
| No filter results | No-results state with `Clear Filters` |
| Permission denied | Explicit permission-denied state; no silent failure |

Additional rules:

- Business/system error dialogs must not expose stack traces, SQL, internal exception names, server paths, or sensitive values.
- The dialog should explain what failed and provide a clear close/retry/recovery action when available.
- Closing an error dialog returns the user to the unchanged page/form state.
- Important warnings remain visible long enough to read.
- A success toast must not replace navigation to a required completion/detail page.

## 11. Permissions and security UX

- UI visibility follows existing permissions and role mappings.
- Server-side authorization is required for every protected route and mutation, including direct URL access.
- Read and mutation capabilities may differ; the UI must not infer write access from read access.
- Sensitive documents use private authenticated access.
- Permission-denied responses use the global permission state/dialog and never expose implementation details.
- Task 33 does not redefine the permission model. Any mismatch discovered during implementation is reported as a separate security/business gap.

## 12. Output and navigation behavior

- PDF, Excel, print preview, email, receipt, registration card, and key-card actions are explicit outputs, not undefined application screens.
- External/file output actions show loading and clear success/failure feedback.
- Browser Back and in-app Back must not unexpectedly lose in-progress state inside an approved wizard.
- Links to guest, room, reservation, stay, payment, or staff records open the correct detail context.
- Every action menu closes after selection, Escape, or outside click and remains keyboard accessible.

## 13. Deferred/V2 references

The following may have approved mockups/direction but are not V1 implementation requirements of Task 33 unless separately scheduled:

- Notification workflows (header bell or otherwise). No approved backend capability exists for V1; see §6.2.
- Global search (header or otherwise). No approved backend capability exists for V1; see §6.2.
- Mobile-native/PWA behavior.
- Passport OCR.
- Reservation Planning Board and Room Availability Dashboard.
- Hotel Settings sections not included in the final V1 information architecture.

These references must not be deleted from the design archive, but they must not expand V1 implementation scope.

## 14. Implementation sequence

Task 33 is implemented in the following batches. Status reflects the current checkpoint as of this reconciliation and must be kept current as batches complete.

| Batch | Scope | Status |
| --- | --- | --- |
| 1A | Design System + Global App Shell Foundation | COMPLETED |
| 1B | Global Feedback Components | NEXT |
| 1C | Standard Components + Accessibility | Not started |
| 2 | Dashboard | Not started |
| 3 | Front Desk | Not started |
| 4 | Reservations + Folio | Not started |
| 5 | Guests | Not started |
| 6 | Rooms + Housekeeping | Not started |
| 7 | Finance + Reports | Not started |
| 8 | Administration | Not started |
| Final | UX / Permission / Responsive / Dead-link / E2E Audit | Not started |

Batch 3 (Front Desk) is where the transitional Check-in/Check-out navigation rule (§9.2.1a) resolves: it must establish the approved `Check-in Guest` / `Checkout` entry points before the legacy `Check-in` / `Check-out` sidebar links are removed. Batch 3 is also where the known deferred issue in §9.3.3a (Walk-in Review → Back losing wizard state) is to be fixed. Batch 7 (Finance + Reports) is where the web Reports UI is redesigned per §7.1b/§9.7; PDF/Excel exports are unaffected.

Batch 3 (Front Desk) is implemented in the following sequence of slices, per the implementation boundary in §9.2.7:

| Slice | Scope |
| --- | --- |
| 3A | Front Desk query/read-model enhancements — pagination, sort, filter (§9.2.3) |
| 3B | Check-in Guest + Walk-in/OTA orchestration + wizard state (§9.2.5, §9.3.3a) |
| 3C | Check-in Review (§9.2.6) |
| 3D | Reservation Detail / active Stay (§9.3.4, §9.3.4a) — `CHECKED_IN` configuration only; the shared state-aware Detail for all six states (§9.3.4.1–§9.3.4.12) is a Batch 4 (Reservations) item and extends this slice rather than replacing it |
| 3E | Room Change (§9.5) |
| 3F | Stay Extension (§9.3.4b) |
| 3G | Folio / Charges / Payments (§9.6) |
| 3H | Checkout (§9.2.2) |
| 3I | Front Desk integration / responsive / permissions / E2E audit |

Implementation must remain pixel-close to the approved mockups. Do not redesign screens during coding.

## 15. Acceptance criteria

Task 33 is complete only when all of the following are true:

- The final sidebar/header shell is consistent across all in-scope pages.
- Check-in and Check-out are contextual Front Desk actions rather than top-level navigation.
- Every visible button, tab, link, row action, icon, and menu item has a defined result.
- No V1 screen contains a dead control or an unmapped destination.
- State-based Reservation actions match the approved matrix.
- Checkout enforces Outstanding = 0 and transitions the occupied room to `DIRTY` without the removed future-reservation blocker.
- Folio correction behavior matches the approved whole-refund/void rules and retains audit history.
- Passport Documents accept only JPG/PNG up to 5 MB and remain private/authenticated.
- Multi-step forms preserve state across Next, Back, validation errors, and business/system errors.
- Cancel/Exit with unsaved wizard changes requires explicit discard confirmation.
- Field errors are inline; business/system errors use the global popup; success uses toast.
- Destructive operations require confirmation and prevent double submission.
- Terminal states are clearly read-only and visibly identified.
- Permission-aware visibility and direct-route server authorization both pass.
- Reports preserve approved semantics and use the approved PDF/Excel templates.
- Fixed roles and Roles & Permissions matrix behavior remain unchanged.
- V1 does not gain deferred V2 features through UI implementation.
- Desktop, smaller viewport, long-content, wide-table, empty, loading, error, and permission-denied states pass visual regression.
- Existing backend/domain tests remain green; new UI tests cover the changed flows.

## 16. Definition of done checklist

- [ ] Approved mockup mapped to each implemented screen/state.
- [ ] All click targets reviewed against this specification.
- [ ] No unapproved business rule or field introduced.
- [ ] All forms tested for validation and state preservation.
- [ ] All mutations tested for loading and duplicate-submit prevention.
- [ ] All destructive actions tested for confirmation and cancellation.
- [ ] All permission combinations tested for visibility and direct access.
- [ ] All terminal states tested as read-only.
- [ ] Reports compared with approved web/PDF/Excel baselines.
- [ ] Responsive and keyboard interaction regression completed.
- [ ] Full application UI regression completed.

---

This specification closes the Task 33 coverage gaps GAP-01 through GAP-06. Any later request that changes business semantics, permissions, lifecycle transitions, reporting calculations, or release scope must be documented as a separate approved change rather than folded silently into Task 33.
