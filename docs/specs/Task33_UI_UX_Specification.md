# Task 33 — UI/UX Specification

## 1. Document control

| Item | Value |
| --- | --- |
| Project | Hotel System |
| Task | Task 33 — Final UI / UX Polish |
| Scope | Full-system UI/UX redesign, standardization, interaction completion, and regression |
| Status | Approved / specification baseline |
| Last updated | 2026-10-02 |
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
- Terminal states use a prominent banner and read-only presentation.
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

Purpose: daily operational overview and shortcuts, separate from analytical Reports.

Required interactions:

- KPI cards and summary links navigate to the relevant filtered workspace.
- Today's arrivals navigate to Front Desk → Arrivals.
- Currently staying navigates to Front Desk → In-house or the selected stay/reservation detail.
- Departures navigate to Front Desk → Departures.
- Recent reservations navigate to Reservation List or Reservation Detail.
- New Reservation starts the Create Reservation wizard.
- Room status navigates to Rooms.
- View Reports navigates to Reports (Overview view).

The V1 Reservations KPI displays both `This year` and `This month`, based on `Reservation.checkInDate`.

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

Confirmed current issue: in the Walk-in flow, using `Back` from the Review step currently loses previously entered wizard state, which does not yet meet the contract in §9.3.3. This reconciliation records the gap but does not fix it here. It is assigned to Batch 3 (Front Desk, §14), where the Walk-in flow's final implementation must follow the §9.3.3 state-preservation contract in full: `Back`/`Next` preserve entered state, validation failure preserves entered state, and business/system errors preserve entered state. `Cancel` still explicitly discards only after confirmation. As with the rest of §9.3.3, V1 does not require state recovery across a browser refresh, a closed tab, or reopening the URL.

#### 9.3.4 Reservation Detail and action matrix — GAP-02 closed

Use one consistent detail layout with state-aware actions and tabs for overview, stay/rooms, guests, financial/folio, and activity/history as approved by the mockups.

| Reservation state | Available actions |
| --- | --- |
| `DRAFT` | Edit, Confirm, Cancel |
| `CONFIRMED` | Change Dates, Correct OTA Reference (non-`DIRECT` only), Cancel, No-show when date-eligible, Check-in when eligible |
| `CHECKED_IN` | Change Room, Extend Stay, Open Folio, Checkout |
| `CHECKED_OUT` | Open Folio read-only |
| `CANCELLED` | Read-only |
| `NO_SHOW` | Read-only |

Rules:

- Every action also requires its existing permission.
- `Edit` is a generic action for `DRAFT` only.
- Post-confirmation changes use dedicated actions and validations.
- Direct URLs must be rejected server-side when the state or permission does not allow the action.
- `CHECKED_OUT`, `CANCELLED`, and `NO_SHOW` show a clear terminal-state banner.
- Destructive actions use confirmation dialogs.
- No-show appears only when the existing date/business condition is met.
- Correct OTA Reference appears only for non-`DIRECT` reservations.

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
- History is immutable.
- In V1, the old room becomes `AVAILABLE`; the newly vacated room becomes `DIRTY` at checkout.
- Room change does not automatically change reservation pricing unless an existing approved rule/action explicitly does so.

### 9.6 Finance

#### 9.6.1 Folio

- Folio provides stay/reservation context, balance summary, Charges, Payments, Outstanding, and checkout readiness.
- Financial amounts and mutation actions require the existing financial permissions.
- Users with checkout-only access see checkout readiness but not protected financial detail.
- `CHECKED_OUT` folio is read-only.

#### 9.6.2 Folio correction flow — GAP-03 closed

Charges tab:

- `Void Charge` is available only for a manual, non-`ROOM` charge.
- Collect a required reason, show confirmation, then mark the charge `VOIDED`.
- Room charges cannot be voided through this action.

Payments tab:

- `Refund Payment` shows Payment Amount as read-only and supports whole refund only.
- Partial refund is not supported.
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
