# Hotel Management System

## Technical Specification v1.0

**Purpose:** Implementation specification for Codex  
**Status:** Agreed baseline  
**Scope:** Hotel Management System MVP and the agreed extension points  
**Technology target:** Java / Spring Boot / Thymeleaf / PostgreSQL

---

# 1. System Objective

Xây dựng một hệ thống quản lý khách sạn phục vụ:

- Quản lý khách hàng
- Quản lý loại phòng và phòng
- Quản lý reservation / booking
- Check-in / Check-out
- Quản lý khoản khách phải trả
- Quản lý payment
- Quản lý expense
- Quản lý accounting
- Quản lý user / role / permission
- Audit lịch sử thao tác
- Có khả năng mở rộng để đồng bộ booking từ Agoda, Booking.com và Airbnb thông qua iCal hoặc cơ chế integration phù hợp sau này.

Hệ thống phải được thiết kế sao cho:

1. Business rule được kiểm soát rõ ràng.
2. State transition không được thực hiện tùy ý.
3. Security được kiểm tra trước business operation.
4. Các thao tác quan trọng phải có audit.
5. Accounting entry đã POSTED không được sửa trực tiếp.
6. Không được hard-delete user có liên quan đến dữ liệu lịch sử.
7. Client không được tự gửi `created_by` / `updated_by` để giả mạo người thao tác.

---

# 2. Technology Stack

Target stack:

```text
Backend
- Java
- Spring Boot
- Spring Security
- Spring Data JPA
- Bean Validation
- Scheduler

Frontend
- Thymeleaf

Database
- PostgreSQL

Recommended supporting technologies already agreed:
- Flyway
- Docker
- JUnit
- Mockito
- Testcontainers
- REST API
- OpenAPI
```

---

# 3. High-Level Domain Model

Các domain chính:

```text
Guest
RoomType
Room
Reservation
ReservationRoom
Stay
Charge
Payment
Expense
ExpenseCategory
Account
AccountingEntry
AccountingEntryLine

AppUser
Role
Permission
AuditLog
```

Quan hệ tổng thể:

```text
Guest
  │
  └──< Reservation
          │
          ├──< ReservationRoom >── Room >── RoomType
          │
          └──── Stay
                  │
                  ├──< Charge
                  │
                  └──< Payment
                              │
                              ↓
                     AccountingEntry
                              │
                              ↓
                     AccountingEntryLine
                              │
                              ↓
                           Account


Expense
  │
  ↓
Future Accounting integration
  │
  ↓
AccountingEntry


AppUser ───< Role ───< Permission

AppUser ───────────────> AuditLog
```

---

# 4. Guest Domain

## 4.1 Guest

Mục đích: lưu thông tin khách hàng.

Fields:

```text
id
guest_code
first_name
last_name
email
phone
nationality
date_of_birth
address
created_at
created_by
updated_at
updated_by
```

Constraints:

```text
guest_code UNIQUE
```

Technical ID:

```text
id sử dụng UUID làm internal technical primary key.
guest_code và reservation_number không được sinh từ UUID này.
```

Không sử dụng email làm unique identifier cho Guest.

Một Guest có thể có nhiều Reservation:

```text
Guest 1 ─── N Reservation
```

### Nationality selection (Create / Edit)

Guest Create and Edit select `nationality` from a country dropdown backed by the shared ISO
3166-1 country reference (code, canonical English name, Unicode flag), rather than free text.
The dropdown's first option is a `Select nationality` placeholder. Only the canonical country
name (e.g. `Vietnam`, `Japan`, `South Korea`) is submitted and persisted for new submissions —
never an ISO code or a nationality demonym (e.g. not `VN`, not `Vietnamese`).

Historical Guest data is **not automatically migrated** to canonical country names. Opening
the Edit form never silently modifies stored data by itself:

- If the Guest's stored nationality is already a canonical country name, or is a known legacy
  demonym (e.g. `Vietnamese`, `Japanese`, `American`, `Korean`, `Chinese`, `British`, `French`,
  `German`), the dropdown preselects the matching canonical country.
- If the stored value cannot be safely mapped, it is preserved verbatim as the current
  selection (not discarded or reset to blank) unless the user explicitly changes the field and
  submits the form.

The database schema is unchanged; `nationality` remains a single free-text column.

## 4.2 Guest Management List

Guest Management provides five independent, optional filter fields instead of one generic
search field:

```text
guest_code
first_name
last_name
email
nationality
```

`phone` is not an available Guest list filter.

`guest_code`, `first_name`, `last_name`, and `email` are trimmed independently; a blank field
is ignored (no filter applied for that field). A non-blank value performs case-insensitive
partial (contains) matching against its own column.

`nationality` is a **country selection**, not free text: it is presented as a dropdown sourced
from the same shared country reference used by Create/Edit, with a leading `All nationalities`
option meaning no nationality filter is applied. Selecting a country matches Guests whose
stored nationality is that country's canonical name **or** one of its known legacy demonyms
(e.g. selecting `Japan` matches both `Japan` and `Japanese`), case-insensitively. This is an
exact match against the canonical name and its known legacy equivalents, not a partial
substring match.

Multiple populated filter fields combine with **AND** semantics: a Guest must match every
populated filter to appear in the result. For example, `first_name = Khoa` together with
`nationality = Vietnam` returns only Guests matching both conditions, not either one.

Filtering and pagination are performed at the database level. The fixed page size is 10,
with deterministic default ordering by `guest_code ASC`. Pagination links preserve every
currently active filter without emitting blank query parameters. A zero-result search remains
on the filtered list, preserves the entered filter values (including the selected
nationality), and presents `0 results` with `No guests match the current filters.`

The Guest list presents nationality as an optional Unicode country flag followed by the
unmodified stored nationality text when that text can be safely resolved to an ISO 3166-1
alpha-2 country — either as a country name (e.g. `Vietnam`) or as a known legacy nationality
demonym (e.g. `Vietnamese`) from the same shared, explicitly approved reference. The stored
nationality text remains authoritative Guest data and is never rewritten; the flag is
presentation-only. Blank nationality is displayed as `—`, and an unknown historical
nationality is displayed without a generated flag.

## 4.3 Guest Passport Images (0..N)

A Guest owns **zero to many** `GuestDocument` rows of type `PASSPORT_IMAGE` (previously a
maximum of one). Each is an independently secured stored file representing the booking Guest
or an accompanying traveler:

```text
Guest
    |
    +-- 0..N GuestDocument(PASSPORT_IMAGE)
```

V1 explicitly does **not** model which individual a given passport image belongs to. There is
no Traveler/Occupant/Companion entity, no passport-holder name/number/expiry field on
`GuestDocument`, and no rule tying the passport image count to Reservation guest count or Room
occupancy — the system does not model occupants sufficiently to enforce that, and V1
deliberately does not attempt it.

`GuestDocument` fields, constraints, and storage architecture are otherwise unchanged from the
original one-passport design: server-generated `storage_key`, private filesystem storage under
`hotel.storage.guest-documents.path`, `guest_id`/`document_type` required, `storage_key`
globally unique. Only the constraint that limited a Guest to one document per type
(`guest_document_one_per_type`) was removed (migration V25); existing Guests with zero or one
passport image continue to work without any manual data migration.

Validation is per file, not per request: each selected image must independently be JPEG or
PNG and at most 5 MB. Two files of 4 MB each are both valid in the same submission — the 5 MB
limit is never interpreted as a combined/request-level limit. All selected files are validated
before any file is stored or any metadata is persisted, so one invalid file rejects the entire
selection instead of silently accepting the others.

Create Guest accepts multiple selected images in one submission. Edit Guest additionally lists
every existing passport image and lets Staff **append** new images — uploading never replaces
or removes an existing image. Removing one specific image is a separate, explicit,
CSRF-protected Guest-management mutation (`POST .../documents/{documentId}/remove`) that
identifies the document by its own identifier and never removes any other document owned by
the Guest.

Every "View Passport" action resolves through a secure, per-document endpoint
(`GET /guests/{guestId}/documents/{documentId}/passport`) that independently verifies the
requested document exists, belongs to the requested Guest, and is a `PASSPORT_IMAGE` — a Guest
A URL combined with a Guest B document identifier never resolves. Guest Detail and Edit render
documents in deterministic order (`created_at ASC`, then `id ASC`) as `Passport 1`,
`Passport 2`, … — a display label only, not an identity or ownership claim. Neither storage
key nor filesystem path is ever exposed to the client.

Authorization is unchanged: `MANAGE_GUEST` continues to own every Guest-management mutation
(upload, append, remove); `CHECK_IN` continues to be separately granted read-only access to
the secure passport view endpoint for Check-in identity verification, without gaining any
Guest management/edit capability. No new permission was introduced.

The multipart `max-request-size` was widened from 7 MB to 40 MB (`max-file-size` stays 6 MB,
above the 5 MB business limit) so that several individually valid images can be selected in
one Create/Edit submission without hitting the servlet-level request-size ceiling before the
per-file business validation ever runs. This remains a bounded limit, not an unlimited one.

---

# 5. Room Domain

## 5.1 RoomType

Fields:

```text
id
code
name
description
capacity
base_price
active
created_at
created_by
updated_at
updated_by
```

Constraint:

```text
code UNIQUE
```

Các RoomType đã thống nhất:

```text
SINGLE: Single Room, capacity 1
DOUBLE: Double Room, capacity 2
TWIN: Twin Room, capacity 2
TRIPLE: Triple Room, capacity 3
FAMILY: Family Room, capacity 4
```

RoomType không định nghĩa permanent fixed selling price.

Giá Room có thể thay đổi theo tourism season, month, date range, hoặc future pricing rules.

Không dùng `RoomType.base_price` làm authoritative long-term reservation price.

Detailed pricing / rate-plan behavior không thuộc current Room Management scope.

Không implement RoomRate hoặc RatePlan trong scope hiện tại.

Năm RoomType đã thống nhất phải tồn tại dưới dạng read-only reference data.

Room Management có thể đọc và chọn một trong các RoomType này khi create/update Room.

Không implement RoomType create/update/delete trong current scope.

---

## 5.2 Room

Fields:

```text
id
room_number
room_type_id
floor
status
active
created_at
created_by
updated_at
updated_by
```

Constraint:

```text
room_number UNIQUE
```

Technical ID:

```text
id sử dụng UUID làm internal technical primary key.
room_number và bất kỳ business identifier nào không được sinh từ UUID này.
```

Relationship:

```text
RoomType 1 ─── N Room
```

Mỗi Room phải thuộc chính xác một RoomType.

`room_type` là mandatory cho Room.

Room profile:

```text
Create Room: room_number, room_type, floor
Update Room profile: room_number, room_type, floor
```

Generic Room create/update không được điều khiển:

```text
id
status
active
created_by
updated_by
deleted_by
```

Initial Room status:

```text
AVAILABLE
```

Client không được cung cấp initial status.

`active` biểu thị Room còn thuộc active hotel inventory hay không và khác với operational status.

Ví dụ:

```text
active=true + AVAILABLE: active room currently ready
active=true + OCCUPIED: active room occupied
active=true + OUT_OF_ORDER: active inventory room temporarily unavailable
active=true + MAINTENANCE: active inventory room under maintenance
active=false: room is no longer part of active hotel inventory
```

Không dùng `active=false` thay cho `OUT_OF_ORDER` hoặc `MAINTENANCE`.

Deactivate/reactivate Room không thuộc current Room Management slice.

Authorization:

```text
MANAGE_ROOM bảo vệ Room Management operations.
Reservation room lookup tiếp tục dùng MANAGE_BOOKING.
```

Operational status và booking availability là hai khái niệm khác nhau.

`Room.status == AVAILABLE` không tự nó xác định Room available cho requested booking period.

Booking availability phải xét ít nhất:

```text
requested check-in/check-out period
existing relevant Reservations for that Room
```

Current Room Management implementation slice chỉ gồm:

```text
RoomType read-only support
Room list
Room detail
Create Room
Update Room profile
```

Explicit operational status actions, deactivate/reactivate Room, và RoomType management không thuộc slice này.

Room status:

```text
AVAILABLE
OCCUPIED
DIRTY
CLEANING
MAINTENANCE
OUT_OF_ORDER
```

## 5.3 Room Management List

Room Management provides four independent, optional filter fields:

```text
room_number
room_type
floor
status
```

`room_number` is trimmed; a blank value is ignored (no filter applied). A non-blank value
performs case-insensitive partial (contains) matching against `room_number`.

`room_type` is selected from the existing read-only RoomType reference data (§5.1) and matched
by its stable RoomType identifier, not by rendered display text. The first dropdown option
means no RoomType filter is applied.

`floor` is matched exactly against the stored `floor` value; a blank value is ignored. No floor
range or additional business rule is introduced.

`status` is matched exactly against one Room status value (§5.2); the first dropdown option
means no status filter is applied.

Multiple populated filter fields combine with **AND** semantics: a Room must match every
populated filter to appear in the result. For example, `room_type = DOUBLE` together with
`status = AVAILABLE` returns only Rooms matching both conditions, not either one.

Filtering and pagination are performed at the database level. The fixed page size is 10, with
deterministic default ordering by `room_number ASC`. Pagination links preserve every currently
active filter without emitting blank query parameters. A zero-result search remains on the
filtered list, preserves the entered filter values, and presents `0 results` with
`No rooms match the current filters.`

This list/filter behavior does not change the Room Management implementation slice described
above, the Room state machine, or any operational status transition.

---

# 6. Reservation Domain

Reservation là domain trung tâm của hệ thống.

## 6.1 Reservation

Fields:

```text
id
reservation_number
guest_id
source
external_booking_id
status
reserved_at
check_in_date
check_out_date
currency
total_amount
notes
created_at
created_by
updated_at
updated_by
```

Reservation number:

```text
reservation_number được backend tự động sinh khi tạo Reservation.
Format: R + yyyyMMdd + "-" + daily sequence sáu chữ số, zero-padded.
Ví dụ: R20260911-000001
reservation_number là immutable sau khi tạo và client không được cung cấp giá trị này.
reservation_number không được sinh từ technical ID.
```

Reservation source:

```text
DIRECT
AGODA
BOOKING_COM
AIRBNB
```

Reservation status:

```text
DRAFT
CONFIRMED
CANCELLED
NO_SHOW
CHECKED_IN
CHECKED_OUT
```

Constraint:

```text
check_out_date > check_in_date
```

---

## 6.2 External Booking

Đối với OTA booking:

```text
source
external_booking_id
```

Dùng để nhận diện booking từ hệ thống bên ngoài và chống duplicate khi synchronization.

Constraint:

```text
UNIQUE(source, external_booking_id)
```

`external_booking_id` có thể NULL đối với booking DIRECT.

---

# 7. ReservationRoom

Một Reservation có thể chứa nhiều Room.

Không thiết kế Reservation chỉ có một `room_id`.

## 7.1 ReservationRoom

Fields:

```text
id
reservation_id
room_id
check_in_date
check_out_date
nightly_rate
total_amount
created_at
created_by
updated_at
updated_by
```

Relationship:

```text
Reservation 1 ─── N ReservationRoom
ReservationRoom N ─── 1 Room
```

---

## 7.2 Price Snapshot

`nightly_rate` phải lưu giá tại thời điểm reservation.

Không được dùng `RoomType.base_price` để tính lại giá của reservation cũ.

Ví dụ:

```text
Reservation created:
nightly_rate = ¥10,000
```

Sau đó:

```text
RoomType.base_price = ¥15,000
```

Reservation cũ vẫn phải giữ:

```text
¥10,000
```

---

# 8. Stay Domain

Stay đại diện cho quá trình khách thực tế lưu trú.

## 8.1 Stay

Fields:

```text
id
reservation_id
status
actual_check_in_at
actual_check_out_at
notes
created_at
created_by
updated_at
updated_by
```

Relationship:

```text
Reservation 1 ─── 1 Stay
```

Stay được tạo từ Reservation khi khách thực hiện check-in.

Stay statuses:

```text
CHECKED_IN
CHECKED_OUT
```

Allowed transition:

```text
CHECKED_IN -> CHECKED_OUT
Operation: check-out
```

Khi check-in:

```text
Stay.status = CHECKED_IN
actual_check_in_at được backend ghi nhận
actual_check_out_at = null
```

Khi check-out:

```text
Stay.status = CHECKED_OUT
actual_check_out_at được backend ghi nhận
```

Client không được set trực tiếp Stay.status.

Không thêm Stay state khác trong current scope.

## 8.2 Folio v1

Folio là financial view của một Stay và được truy cập theo Reservation:

```text
GET /reservations/{reservationId}/folio
```

Detailed Folio financial information yêu cầu permission:

```text
MANAGE_PAYMENT
```

`MANAGE_PAYMENT` authorizes:

```text
- viewing Charges
- viewing Payments
- viewing Total Charges
- viewing Total Paid Payments
- viewing Outstanding
- creating Charges
- creating Payments
- Payment state transitions
```

`CHECK_OUT` không cấp detailed financial read access. User chỉ có `CHECK_OUT` không được truy cập Charge, Payment, hoặc detailed Folio financial information chỉ vì có thể thực hiện check-out.

Folio có thể hiển thị Reservation context, Stay context, Charges, Payments, Total Charges, Total Paid Payments, và Outstanding.

Folio financially mutable chỉ khi:

```text
Stay.status = CHECKED_IN
```

Khi:

```text
Stay.status = CHECKED_OUT
```

Folio closed và read-only trong v1.

## 8.3 StayRoomAssignment — Room Change (V1)

`StayRoomAssignment` đại diện cho ACTUAL PHYSICAL OCCUPANCY của một Stay, khác với `ReservationRoom`
là BOOKING / PRICING snapshot bất biến.

```text
ReservationRoom
  → "What room/rate/date range was originally booked?"
  → immutable, không bị Room Change thay đổi

StayRoomAssignment
  → "What physical room was the guest actually occupying, and when?"
  → một interval mở (đang là phòng hiện tại) hoặc đã đóng (lịch sử)
```

Fields:

```text
id
stay_id
room_id
original_reservation_room_id
assigned_from
assigned_to
reason
notes
created_at
created_by
updated_at
updated_by
```

`original_reservation_room_id` là lineage anchor: mọi assignment sinh ra từ cùng một phòng đặt
ban đầu (assignment khởi tạo lúc check-in và mọi assignment thay thế sau đó qua Room Change) đều
tham chiếu cùng một `ReservationRoom`. Do đó ranh giới thời gian lưu trú còn lại ("planned
check-out boundary") của lineage đó luôn được đọc từ `ReservationRoom.check_out_date` bất biến,
không lưu trùng lặp.

Khi check-in, backend tạo một `StayRoomAssignment` mở (`assigned_to = null`, `reason = null`) cho
mỗi `ReservationRoom` của Reservation, với `assigned_from = Stay.actual_check_in_at`.

### Room Change (sau check-in)

Room Change chỉ khả dụng cho một Stay đang `CHECKED_IN`, và thao tác trên đúng một phòng hiện tại
tại một thời điểm.

Quy tắc:

```text
- Reason là bắt buộc: GUEST_REQUEST, ROOM_ISSUE, UPGRADE, DOWNGRADE, OPERATIONAL, OTHER
- Notes là tùy chọn, ngoại trừ reason == OTHER thì Notes bắt buộc và không được rỗng
- Phòng thay thế phải khác phòng hiện tại
- Phòng thay thế phải AVAILABLE và active, và không có overlap với Reservation khác
  (CONFIRMED/CHECKED_IN) trong khoảng [ngày hiện tại theo Clock, planned check-out boundary
  của lineage đang đổi)
- Room Change bị từ chối khi ngày hiện tại (theo Clock) >= planned check-out boundary của lineage
- Room Change KHÔNG BAO GIỜ tự động thay đổi ReservationRoom.nightly_rate, ReservationRoom.total_amount,
  Reservation.total_amount, hoặc các Charge ROOM đã tạo khi check-in
- Nếu khách sạn cần thu thêm phí (ví dụ nâng hạng phòng), Staff tạo Charge riêng qua Charge/Folio
  hiện có, không qua Room Change
```

Khi Room Change thành công, trong cùng một transaction:

```text
1. Đóng assignment hiện tại: assigned_to = thời điểm hiện tại theo Clock
2. Tạo assignment mới, cùng original_reservation_room_id (cùng lineage), assigned_to = null
3. Phòng cũ: OCCUPIED -> AVAILABLE qua Room Change release (xem mục 21)
4. Phòng mới: AVAILABLE -> OCCUPIED (qua toán tử occupy hiện có)
5. Ghi AuditLog: entity_type = RESERVATION, action = CHANGE_ROOM, entity_id = Reservation ID,
   old_value = "Room <old>", new_value = "Room <new>"
```

Nếu bất kỳ bước nào thất bại (bao gồm mất race điều kiện dưới khóa phòng), toàn bộ transaction
rollback: không có thay đổi trạng thái phòng, không có thay đổi assignment, không có trạng thái
một phần nào còn lại.

Lịch sử assignment đã đóng không bao giờ bị sửa hoặc xóa để phản ánh một lần đổi phòng sau đó;
mỗi lần đổi phòng chỉ đóng interval hiện tại và thêm interval mới (append-only ngoại trừ việc đóng
interval đang mở).

Quay lại một phòng đã từng ở trước đó (ví dụ 201 → 305 → 201) là hợp lệ nếu phòng đó hiện đang
usable và available cho phần còn lại của lineage.

### Authorization

Room Change yêu cầu permission `CHANGE_ROOM` (xem mục 28). `CHECK_IN`, `CHECK_OUT`, và
`MANAGE_BOOKING` không cấp quyền thực hiện Room Change.

---

# 9. Charge Domain

Charge đại diện cho khoản khách phải trả.

## 9.1 Charge

Fields:

```text
id
stayId
type
description
quantity
unitPrice
amount
chargedAt
createdAt
createdBy
updatedAt
updatedBy
```

Rules:

```text
id is a backend-generated UUID
stayId is required
type is required
description is optional
amount is the persisted authoritative financial value
chargedAt is generated by the backend at Charge creation
audit fields are backend-controlled
```

Client không được provide:

```text
id
chargedAt
createdAt
createdBy
updatedAt
updatedBy
```

Relationship:

```text
Stay 1:N Charge
```

Mỗi Charge thuộc chính xác một Stay. `charge.stay_id` là mandatory. Reverse `Stay.charges` entity collection không bắt buộc trong Charge v1.

Authorization:

```text
MANAGE_PAYMENT
```

Charge v1 chỉ hỗ trợ:

```text
POST /api/stays/{stayId}/charges
GET  /api/stays/{stayId}/charges
```

Charge creation chỉ được phép khi:

```text
Stay.status = CHECKED_IN
```

Charge creation phải reject khi `Stay.status = CHECKED_OUT`.

Không định nghĩa update, delete, void, correction, hoặc reversal behavior trong Charge v1.

Charge types:

```text
ROOM
BREAKFAST
EXTRA_BED
LAUNDRY
MINIBAR
SERVICE
TAX
DISCOUNT
OTHER
```

Charge v1 chỉ được tạo các type:

```text
ROOM
BREAKFAST
EXTRA_BED
LAUNDRY
MINIBAR
SERVICE
OTHER
```

`TAX` và `DISCOUNT` được giữ lại làm enum/reference values cho future use, nhưng Charge v1 phải reject việc tạo Charge với hai type này.

Không implement automatic tax calculation, discount calculation, negative Charge behavior, hoặc currency-conversion trong current scope. Detailed behavior sẽ được định nghĩa trong future specification change.

### Charge pricing modes

Mỗi Charge phải dùng chính xác một trong hai pricing modes sau.

#### FIXED AMOUNT

FIXED AMOUNT được dùng khi `quantity` và `unitPrice` không được cung cấp.

```text
amount > 0
quantity = absent
unitPrice = absent
```

Với FIXED AMOUNT, user cung cấp `amount`; `amount` được persisted và authoritative. Mode này hỗ trợ fixed Charges như `SERVICE`, `OTHER`, và `ROOM` khi applicable.

#### ITEMIZED

ITEMIZED được dùng khi `quantity` và `unitPrice` được cung cấp. Hai field này phải cùng present.

```text
quantity > 0
unitPrice >= 0
rawAmount = quantity * unitPrice
amount = rawAmount normalized to scale 6 using HALF_UP
amount > 0
```

Với ITEMIZED Charge mới được tạo, backend phải authoritative khi tính `rawAmount = quantity * unitPrice`, sau đó normalize calculated `amount` trước persistence theo existing Charge amount scale:

```text
scale = 6
rounding mode = HALF_UP
quantity.multiply(unitPrice).setScale(6, RoundingMode.HALF_UP)
```

Không được dựa vào database hoặc JPA implicit rounding. Client chỉ cung cấp `quantity` và `unitPrice`; client không được independently determine authoritative calculated `amount`.

Không được dựa vào Thymeleaf, browser validation, JavaScript, hoặc client-supplied calculated values để enforce financial correctness của ITEMIZED Charge.

Không tạo Charge-type-specific pricing rule trong Charge v1. `quantity` tiếp tục dùng `BigDecimal`; chưa định nghĩa type-specific integer/fractional quantity rule. Ví dụ hợp lệ về presentation gồm `2` breakfasts, `3` laundry units, hoặc `1.5` service units nếu operationally needed. Presentation không nên hiển thị trailing zeros không cần thiết, ví dụ `1.000000 -> 1` và `1.500000 -> 1.5`.

Charge và Payment là hai khái niệm khác nhau.

Charge đại diện cho financial amount đã được ghi nhận trên Stay/Folio.

Không thêm Charge state `PENDING` hoặc `FINALIZED` trong current scope.

`Charge.amount` đã được persisted là authoritative monetary value dùng cho balance calculation. Check-out và accounting calculation phải dùng:

```text
SUM(charge.amount)
```

Không tính lại Stay balance hoặc historical `Charge.amount` tại check-out từ `quantity * unitPrice`.

Historical Charge records không được recalculate hoặc modify. Historical Charge giữ stored `amount` là authoritative, kể cả khi `quantity` và `unitPrice` không khớp với stored `amount`. ITEMIZED calculated-amount rule chỉ áp dụng cho Charge mới được tạo sau khi behavior này được implement; không cần data migration hoặc historical normalization.

UI entry phải làm rõ hai mode: ITEMIZED entry nhận `quantity` và `unitPrice` rồi application calculates `amount`; FIXED AMOUNT entry nhận `amount` và không nhận `quantity` hoặc `unitPrice`. UI không được encourage user nhập ba independent monetary/calculation values. Không quy định JavaScript behavior trong specification này. VND display formatting là presentation concern và có thể hide fractional digits, nhưng không thay đổi stored BigDecimal semantics.

Ví dụ:

```text
Charges:
Room       ¥30,000
Breakfast   ¥3,000

Total = ¥33,000
```

---

# 10. Payment Domain

Payment đại diện cho tiền khách đã thanh toán.

## 10.1 Payment

Payment hỗ trợ multi-currency: tender amount và Folio-applied amount là hai giá trị tách biệt,
được snapshot bất biến ngay khi Payment được ghi nhận.

Fields:

```text
id
stayId
amount            -- tender amount, theo currency
currency          -- PaymentCurrency: VND hoặc USD, currency khách thực trả
exchangeRate      -- "1 USD = exchangeRate VND"; null cho same-currency Payment
appliedAmount     -- amount đã quy đổi sang Folio (Reservation) currency; luôn > 0
method
status
paidAt
reference
refundReason      -- required khi status = REFUNDED; null trước đó
createdAt
createdBy
updatedAt
updatedBy
```

Rules:

```text
id is a backend-generated UUID
stayId is required and comes from the URL path
amount is required, amount > 0, and immutable after Payment creation
currency is required (VND hoặc USD)
method is required
reference is optional, TRỪ method = OTA thì reference bắt buộc và không được rỗng
reference has no method-specific format or uniqueness rule beyond the OTA-required rule
exchangeRate, appliedAmount, currency, amount là immutable financial snapshot sau khi tạo
audit fields are backend-controlled
```

### Exchange rate / currency conversion

`exchangeRate` luôn mang nghĩa cố định "1 USD = exchangeRate VND", bất kể Payment currency hay
Reservation/Folio currency bên nào là USD. Backend là nơi duy nhất tính `appliedAmount`; client
không bao giờ tự tính hay cung cấp `appliedAmount`.

```text
Same currency (Payment currency == Folio currency):
    appliedAmount = amount
    exchangeRate  = null (bắt buộc null, không được cung cấp)

Cross currency, USD tender -> VND Folio:
    appliedAmount = amount * exchangeRate

Cross currency, VND tender -> USD Folio:
    appliedAmount = amount / exchangeRate

scale = 6
rounding = HALF_UP
```

Một khi đã ghi nhận, `amount`/`currency`/`exchangeRate`/`appliedAmount` là snapshot tài chính bất
biến. Thay đổi tỷ giá sau này không được tính lại các Payment lịch sử.

Client chỉ được provide:

```text
amount
currency
exchangeRate      -- chỉ khi cross-currency
method
reference
```

Client không được provide:

```text
id
stayId in request body
appliedAmount
status
paidAt
refundReason (ngoại trừ qua refund operation riêng, xem bên dưới)
createdAt
createdBy
updatedAt
updatedBy
```

Relationship:

```text
Stay 1:N Payment
```

Mỗi Payment thuộc chính xác một Stay. `payment.stay_id` là NOT NULL.

Payment creation chỉ được phép khi `Stay.status = CHECKED_IN`. Không hỗ trợ pre-check-in deposits và không tạo Payment mới sau khi Stay `CHECKED_OUT`.

Authorization:

```text
MANAGE_PAYMENT
```

`MANAGE_PAYMENT` bảo vệ đồng nhất mọi operation Payment v1: pending creation, direct record-paid,
mark-paid, mark-failed, và refund. Không có permission Payment riêng biệt nào khác.

Payment v1 hỗ trợ:

```text
POST /api/stays/{stayId}/payments               -- tạo Payment PENDING
POST /api/stays/{stayId}/payments/record-paid    -- tạo và xác nhận PAID atomically
GET  /api/stays/{stayId}/payments

POST /api/payments/{id}/mark-paid
POST /api/payments/{id}/mark-failed
POST /api/payments/{id}/refund                   -- body: { reason }
```

Không implement generic Payment update hoặc delete.

Payment methods:

```text
CASH
CREDIT_CARD
BANK_TRANSFER
OTA
OTHER
```

Payment statuses:

```text
PENDING
PAID
FAILED
REFUNDED
```

### Tạo Payment PENDING (existing flow)

Mỗi Payment tạo qua `POST /api/stays/{stayId}/payments` bắt đầu với:

```text
status = PENDING
paidAt = null
```

Client không được chọn initial status. Direct creation với `PAID`, `FAILED`, hoặc `REFUNDED` không được phép qua endpoint này.

### Ghi nhận Payment đã thanh toán trực tiếp (direct record-paid)

`POST /api/stays/{stayId}/payments/record-paid` là thao tác được duyệt cho Payment thủ công mà
Staff đã thực nhận tiền (ví dụ CASH tại quầy): tạo Payment và chuyển `PAID` trong cùng một
transaction, không tồn tại trạng thái `PENDING` trung gian nào được persist. Endpoint này tái sử
dụng chính xác cùng validation, tính `appliedAmount`, và overpayment-prevention logic (khóa Stay
`PESSIMISTIC_WRITE`, tính lại tổng sau khi khóa) như flow PENDING + mark-paid hiện có. Flow
PENDING + mark-paid vẫn được giữ nguyên và khả dụng đầy đủ, dành cho Payment cần chờ xác nhận từ
gateway/ngân hàng/OTA trong tương lai.

Khi chuyển `PENDING -> PAID` (qua mark-paid hoặc trực tiếp qua record-paid), backend ghi `paidAt`
bằng authoritative hotel Clock (cùng Clock bean dùng cho Check-in/Check-out/Room Change). Client
không được provide hoặc modify `paidAt`.

Payment `FAILED` giữ `paidAt = null`.

Chỉ Payment có status `PAID` đóng góp vào Total Payments.

```text
PENDING: không đóng góp
FAILED: không đóng góp
PAID: đóng góp appliedAmount
REFUNDED: không đóng góp (loại khỏi Total Payments ngay khi refund thành công)
```

Overpayment không được hỗ trợ.

Trước khi chuyển `PENDING -> PAID` (bao gồm cả record-paid), backend phải bảo đảm việc công nhận
Payment là `PAID` không làm:

```text
Total PAID Payments (appliedAmount) > Total Charges
```

Trong đó:

```text
Total Charges = SUM(charge.amount)
Total PAID Payments = SUM(payment.appliedAmount WHERE status = PAID)
```

Nếu transition gây overpayment, phải reject. Không implement general Outstanding service trong Payment v1 — `StayBalanceService` là nơi duy nhất tính Outstanding cho Folio/Check-out.

---

# 11. Payment State Machine

Allowed transitions:

```text
PENDING -> PAID
Operation: mark-paid, hoặc trực tiếp qua record-paid (tạo + PAID atomically)

PENDING -> FAILED
Operation: mark-failed

PAID -> REFUNDED
Operation: refund
```

Không được thêm Payment transition khác.

Các Payment transitions chỉ được phép khi owning Stay có:

```text
Stay.status = CHECKED_IN
```

Khi owning Stay có `Stay.status = CHECKED_OUT`, các operation `mark-paid`, `mark-failed`, và `refund` phải reject. Rule này không thay đổi Payment state machine.

Refund dùng cùng Payment record theo transition `PAID -> REFUNDED`. Không tạo separate negative Payment record; Payment amount giữ nguyên; partial refunds không được hỗ trợ trong Payment v1.

Refund yêu cầu `refundReason` — free text, bắt buộc, không được rỗng sau khi trim. Backend từ
chối refund nếu thiếu hoặc rỗng, kể cả khi UI bị bỏ qua. `refundReason` được lưu trên chính
Payment row đó và không thể sửa sau khi refund. Không thêm `refundedAt`/`refundedBy`:
`updatedAt`/`updatedBy` (từ audit fields hiện có) là authoritative timestamp/user cho refund, vì
`REFUNDED` là trạng thái cuối (terminal).

Refund thành công ghi một AuditLog entry theo convention hiện có (cùng style với
`CHANGE_ROOM`/`CHECK_OUT`):

```text
action     = REFUND_PAYMENT
entityType = RESERVATION
entityId   = Reservation ID (qua Stay -> Reservation)
oldValue   = "Payment <id> status=PAID"
newValue   = "Payment <id> status=REFUNDED, reason=<refundReason>"
```

---

# 12. Outstanding Balance

Total Charges:

```text
SUM(charge.amount)
```

Total Payments:

```text
SUM(payment.appliedAmount WHERE payment.status = PAID)
```

`appliedAmount` đã ở Folio (Reservation) currency; multi-currency Payment không cần quy đổi lại ở
bước này (xem mục 10.1). Với Payment cùng currency với Folio, `appliedAmount = amount` nên công
thức không đổi so với trường hợp single-currency.

Outstanding:

```text
Total Charges - Total Payments
```

Ví dụ:

```text
Total Charge  = ¥36,300
Total Payment = ¥36,300

Outstanding = ¥0
```

Nếu:

```text
Total Charge  = ¥50,000
Total Payment = ¥40,000

Outstanding = ¥10,000
```

Check-out yêu cầu `Outstanding = 0`.

Không có outstanding-balance override hoặc override permission trong current scope.

Reservation Detail có thể hiển thị non-financial checkout-readiness indicator cho user có `CHECK_OUT`:

```text
READY
- current Stay exists
- Outstanding = 0

PAYMENT_REQUIRED
- current Stay exists
- Outstanding != 0
```

Detailed Outstanding monetary amount không cần expose cho user chỉ có `CHECK_OUT`. Backend check-out validation remains authoritative.

Folio v1 không hỗ trợ post-checkout refund, Payment, Charge, folio reopening, late Charge, financial adjustment, correction/reversal workflow, hoặc accounting reversal. Các behavior này yêu cầu future explicit financial/accounting specification.

---

# 13. Expense Domain

## 13.1 ExpenseCategory

Fields:

```text
id
code
name
description
active
created_at
created_by
updated_at
updated_by
```

Các category đã thống nhất:

```text
ELECTRICITY
WATER
INTERNET
SALARY
LAUNDRY
CLEANING
SUPPLIES
MAINTENANCE
OTHER
```

ExpenseCategory là read-only reference data trong Expense v1.

Không implement ExpenseCategory create, update, hoặc delete.

---

## 13.2 Expense

Fields:

```text
id
category_id
amount
currency
expense_date
payment_method
description
status
created_by
approved_by
created_at
updated_at
updated_by
```

Expense v1 sử dụng fixed currency:

```text
VND
```

Currency do backend kiểm soát. Client không được provide hoặc modify currency. Không implement multi-currency behavior trong Expense v1.

`Expense.amount` là required, phải lớn hơn `0`, và sử dụng `BigDecimal`. Không dùng `float` hoặc `double`.

`expenseDate` do client cung cấp, đại diện cho business date của Expense và khác `createdAt`. Không áp dụng future-date restriction trong Expense v1.

Expense payment methods là Expense concept riêng, không reuse `PaymentMethod` của Payment domain theo assumption:

```text
CASH
BANK_TRANSFER
CREDIT_CARD
OTHER
```

Mỗi Expense mới bắt đầu với:

```text
status = DRAFT
```

Client không được chọn initial Expense status.

Expense status:

```text
DRAFT
SUBMITTED
APPROVED
REJECTED
POSTED
```

Allowed transitions:

```text
DRAFT -> SUBMITTED
Operation: submit

SUBMITTED -> APPROVED
Operation: approve

SUBMITTED -> REJECTED
Operation: reject

APPROVED -> POSTED
Operation: post
```

Không được thêm Expense transition khác.

Khi `Expense.status = DRAFT`, chỉ các field sau có thể được update:

```text
category
amount
expenseDate
paymentMethod
description
```

Sau khi rời khỏi `DRAFT`, các business field này immutable. Không implement generic editing cho Expense có status `SUBMITTED`, `APPROVED`, `REJECTED`, hoặc `POSTED`.

Client chỉ được provide:

```text
category
amount
expenseDate
paymentMethod
description
```

Client không được provide:

```text
id
currency
status
createdBy
approvedBy
createdAt
updatedAt
updatedBy
```

`approvedBy` do backend kiểm soát và được set khi transition `SUBMITTED -> APPROVED`.

Mọi Expense v1 operation yêu cầu permission:

```text
MANAGE_EXPENSE
```

Không thêm Expense permission mới. Current role-permission mapping giữ nguyên.

Expense v1 hỗ trợ:

```text
List Expenses
View Expense Detail
Create Expense
Update DRAFT Expense
Submit Expense
Approve Expense
Reject Expense
Post Expense
```

Không hỗ trợ delete, correction, reversal, void, receipt/file upload, hoặc recurring Expense behavior.

Expense v1 không tạo `AccountingEntry`. `POSTED` chỉ là Expense domain state trong version này. Không định nghĩa debit/credit behavior, chart-of-accounts mapping, accounting posting rules, hoặc automatic AccountingEntry creation; Accounting integration sẽ được specified separately sau này.

---

# 14. Accounting Domain

Accounting được thiết kế theo double-entry accounting.

## 14.1 Account

Fields:

```text
id
code
name
type
active
created_at
created_by
updated_at
updated_by
```

Account types:

```text
ASSET
LIABILITY
EQUITY
REVENUE
EXPENSE
```

Các account examples đã thống nhất:

```text
1000 Cash
1100 Bank
1200 Accounts Receivable

4000 Room Revenue
4100 Service Revenue

5000 Electricity Expense
5100 Salary Expense
5200 Maintenance Expense
```

---

# 15. AccountingEntry

## 15.1 AccountingEntry

Fields:

```text
id
entry_number
entry_date
status
description
reference_type
reference_id
created_at
created_by
posted_at
```

Accounting status:

```text
DRAFT
SUBMITTED
APPROVED
POSTED
REVERSED
REJECTED
```

---

# 16. AccountingEntryLine

Một AccountingEntry có nhiều AccountingEntryLine.

```text
AccountingEntry 1 ─── N AccountingEntryLine
```

Fields:

```text
id
accounting_entry_id
account_id
debit
credit
```

Accounting invariant:

```text
SUM(debit) = SUM(credit)
```

Một accounting entry không được POST nếu debit và credit không cân bằng.

---

# 17. Accounting State Machine

Allowed flow:

```text
DRAFT
  ↓
SUBMITTED
  ↓
APPROVED
  ↓
POSTED
  ↓
REVERSED
```

Ngoài ra:

```text
SUBMITTED → REJECTED
```

Các state terminal:

```text
REJECTED
REVERSED
```

---

# 18. Immutable Posted Accounting Entry

Accounting Entry sau khi POSTED không được sửa trực tiếp.

Không được:

```text
UPDATE posted accounting entry
```

Nếu phát hiện sai:

```text
Original Entry
      ↓
Reversal Entry
      ↓
Correct Entry
```

Ví dụ:

```text
Original:
Revenue +¥50,000

Reversal:
Revenue -¥50,000

Correct:
Revenue +¥40,000
```

---

# 19. Reservation State Machine

Allowed transitions:

```text
DRAFT
  ↓ Confirm
CONFIRMED
```

Từ CONFIRMED:

```text
CONFIRMED
 ├── Check-in → CHECKED_IN
 ├── Cancel   → CANCELLED
 └── No-show  → NO_SHOW
```

Từ CHECKED_IN:

```text
CHECKED_IN
 └── Check-out → CHECKED_OUT
```

Terminal states:

```text
CHECKED_OUT
CANCELLED
NO_SHOW
```

Không được phép:

```text
DRAFT → CHECKED_IN
CANCELLED → CHECKED_IN
CANCELLED → CONFIRMED
CHECKED_OUT → CHECKED_IN
NO_SHOW → CHECKED_IN
```

Reservation không được hard-delete trong business flow.

---

# 20. Reservation Business Rules

## Confirm

Phải kiểm tra:

```text
Guest tồn tại
Room tồn tại
Check-in hợp lệ
Check-out > Check-in
Room không bị booking overlap
Price hợp lệ
User có permission
```

## Check-in

Phải kiểm tra:

```text
Reservation phù hợp để check-in
Room available/ready
Guest information hợp lệ
Reservation không bị cancellation
User có CHECK_IN permission
```

Không thêm deposit/payment requirement cho check-in trong current scope.

## Check-out

Phải kiểm tra:

```text
Final bill đã được calculate
Outstanding balance = 0
User có CHECK_OUT permission
```

Không dùng pending charges làm check-out validation rule trong current version.

---

# 21. Room State Machine

Approved transitions:

```text
AVAILABLE -> OCCUPIED
Operation: check-in
Permission: CHECK_IN

OCCUPIED -> DIRTY
Operation: check-out
Permission: CHECK_OUT

DIRTY -> CLEANING
Operation: start-cleaning
Permission: MANAGE_ROOM

CLEANING -> AVAILABLE
Operation: finish-cleaning
Permission: MANAGE_ROOM

AVAILABLE -> MAINTENANCE
Operation: start-maintenance
Permission: MANAGE_ROOM

MAINTENANCE -> AVAILABLE
Operation: finish-maintenance
Permission: MANAGE_ROOM

AVAILABLE -> OUT_OF_ORDER
Operation: mark-out-of-order
Permission: MANAGE_ROOM

OUT_OF_ORDER -> AVAILABLE
Operation: restore-to-service
Permission: MANAGE_ROOM

OCCUPIED -> AVAILABLE
Operation: room-change-release
Permission: CHANGE_ROOM
```

Transition `OCCUPIED -> AVAILABLE` (room-change-release) chỉ được sử dụng bởi Room Change (xem
mục 8.3) để giải phóng phòng cũ. Transition này không thay thế, không thay đổi, và không được
dùng cho check-out (`OCCUPIED -> DIRTY`, xem mục 24). Room Change không tự động chuyển phòng cũ
sang `OUT_OF_ORDER`; Maintenance/Room Management chịu trách nhiệm riêng cho việc đó.

Không được thêm Room status transition khác.

---

# 22. Reservation Status ≠ Room Status

Hai state machine độc lập.

Ví dụ hợp lệ:

```text
Reservation = CONFIRMED
Room        = MAINTENANCE
```

Reservation tồn tại nhưng Room không thể được assign/check-in.

Không được coi:

```text
CONFIRMED == OCCUPIED
```

hoặc tự động thay đổi Room thành OCCUPIED chỉ vì Reservation được CONFIRMED.

---

# 23. Check-in Flow

```text
Reservation
      ↓
Verify Guest
      ↓
Verify Room
      ↓
Check-in
      ↓
Reservation = CHECKED_IN
      ↓
Stay created
      ↓
Room = OCCUPIED
```

Các bước phải nằm trong business transaction phù hợp.

---

## 23.1 Check-in Flow — V1 Operational Detail

`/check-in` là route vận hành thực tế, protected bởi `CHECK_IN`.

Cung cấp ba entry flow:

```text
A. Existing Reservation
B. OTA Booking Not Entered
C. Walk-in
```

### A. Existing Reservation

Tìm kiếm CHỈ trong database nội bộ (không tích hợp Agoda/Booking.com/Airbnb API).

Hỗ trợ tra cứu theo:

```text
Reservation Number
Guest name
OTA Booking Reference
```

Chỉ Reservation ở trạng thái `CONFIRMED` mới actionable cho Check-in. Chọn một kết quả
sẽ chuyển sang trang Check-in Review (read-only).

### Check-in Review (read-only)

Hiển thị:

```text
Guest: tên, guest code, nationality (nếu có), passport availability, secure View Passport
Booking: Reservation Number, Source, OTA Booking Reference (chỉ khi source là OTA),
         planned check-in date, planned check-out date, actual check-in date/time preview
Room/financial: assigned room(s), room type (nếu có), nightly rate, số đêm, room total,
                reservation total, currency
```

Không cho phép thay đổi Guest, Room, Source, OTA reference, booking dates, nightly rate,
currency, totals từ trang này.

### Early / Normal / Late Check-in

So sánh hotel current `LocalDate` (từ Clock nghiệp vụ hiện có, KHÔNG dùng giờ trình duyệt)
với `Reservation.checkInDate`:

```text
today < checkInDate  → EARLY  → check-in bị chặn, không hiển thị nút Confirm Check-in.
                                 Staff phải tạo một DIRECT reservation riêng cho khoảng
                                 lưu trú sớm hơn.
today == checkInDate → NORMAL → cho phép check-in bình thường.
today > checkInDate  → LATE   → cho phép check-in, Review page hiển thị cảnh báo rõ ràng;
                                 nút "Confirm Check-in" trên trang cảnh báo này CHÍNH LÀ
                                 xác nhận của con người — không có popup xác nhận thứ hai
                                 riêng cho late check-in.
```

Rule EARLY được backend (`ReservationService.checkIn`) enforce độc lập với UI. Gọi POST/API
trực tiếp không thể bypass rule này.

Late check-in KHÔNG được thay đổi: `Reservation.checkInDate`, `checkOutDate`,
`ReservationRoom` snapshots, số đêm, nightly rate, totals, ROOM Charge. ROOM Charge tiếp tục
được tính từ `ReservationRoom` snapshot y như hiện tại (không dùng actual check-in time).

### B. OTA Booking Not Entered

KHÔNG phải Walk-in. Đại diện cho: khách đã có booking Agoda/Booking.com/Airbnb nhưng Staff
chưa/quên nhập vào Hotel System trước khi khách đến.

Tái sử dụng nghiệp vụ tạo Reservation hiện có (`CreateRequest`, `ReservationService.create`).
Staff chọn Guest hiện có hoặc tạo Guest mới (theo rule Guest hiện có), chọn OTA Source
(`AGODA`/`BOOKING_COM`/`AIRBNB` — không chấp nhận `DIRECT` ở flow này), nhập OTA Booking
Reference, ngày theo đúng booking OTA, chọn Room, nhập nightly rate/currency, rồi tạo
Reservation thật (`source` = OTA source đã chọn, `otaBookingReference` = giá trị Staff nhập).
Sau khi tạo + confirm thành công, Staff được chuyển thẳng vào Check-in Review flow ở trên
(không phải quay lại Reservation List để tìm kiếm). Toàn bộ rule Early/Normal/Late vẫn áp
dụng — nhập OTA reservation trễ tại quầy không cho phép check-in sớm hơn ngày OTA đã đặt.
Hai reservation (DIRECT cho đêm phát sinh sớm hơn, và OTA cho phần còn lại) KHÔNG được tự
động liên kết.

### C. Walk-in

Khách không có reservation, đến trong ngày:

```text
source        = DIRECT (không cho chọn source khác)
checkInDate   = hotel current date (authoritative, từ Clock)
checkOutDate  = Staff chọn
```

Staff chọn Guest hiện có hoặc tạo Guest mới (theo rule Guest hiện có), chọn checkout date,
chọn (các) room còn trống cho TOÀN BỘ khoảng ngày yêu cầu — danh sách room phải date-range
aware, KHÔNG chỉ lọc theo `Room.status` hiện tại (tái sử dụng overlap semantics của
`ReservationRepository.hasOverlap`, cộng với loại trừ room đang `OUT_OF_ORDER`). Danh sách
này chỉ phục vụ UX; validation cuối cùng tại bước confirm mới có tính authoritative (lock
room bằng pessimistic lock hiện có, re-check overlap).

Walk-in luôn tạo một DIRECT Reservation thật, đi qua đúng lifecycle hiện có
(`create → confirm → checkIn`), KHÔNG có đường tạo Stay bỏ qua Reservation.

Trước khi xác nhận, hiển thị Review read-only (Guest + Passport, Source = Direct,
check-in/check-out date, actual check-in time preview, room(s), nightly rate, số đêm,
currency, total) — trang Review không được âm thầm thay đổi dữ liệu.

"Confirm & Check-in" là MỘT operation atomic duy nhất đối với Staff:

```text
validate
  → lock/revalidate Room availability (RoomRepository.lockAllByIdIn + hasOverlap, y như
    confirm() hiện có)
  → create DIRECT Reservation
  → confirm Reservation
  → check in Reservation (tạo Stay, tạo ROOM Charge, Room → OCCUPIED)
  → commit
```

Toàn bộ nằm trong một transaction boundary duy nhất (orchestration method mới, gọi lại
nguyên vẹn `ReservationService.create/confirm/checkIn` — không tạo alternate Stay path,
không nhân bản locking logic). Nếu bất kỳ bước nào fail, toàn bộ operation phải rollback:
không được để lại orphan Reservation, Reservation nửa vời, Stay không đi kèm lifecycle, ROOM
Charge một phần, hoặc Room status sai.

### Direct + OTA Consecutive Stays (V1)

V1 cố tình giữ các reservation liên tiếp (ví dụ DIRECT 19/09→20/09 rồi BOOKING_COM
20/09→23/09 cùng một Room) tách biệt. Vận hành là check-out DIRECT rồi check-in OTA. KHÔNG
implement Continue Stay, linked reservations, automatic Stay transfer, hay automatic room
continuation trong V1.

### Passport Authorization (V1 adjustment)

`MANAGE_GUEST` tiếp tục là permission quản lý Guest. `CHECK_IN` được cấp thêm quyền ĐỌC ảnh
passport Guest (secure per-document passport-image endpoint, xem mục 4.3) khi cần cho Check-in
— không cấp thêm bất kỳ Guest management/edit capability nào cho Staff chỉ có `CHECK_IN`.
Passport image vẫn là optional; Guest có 0, 1, hay nhiều ảnh passport đều KHÔNG chặn check-in
— "đủ điều kiện passport" (khi được hiển thị) nghĩa là `passportImages.size() >= 1`, không phải
`== 1`, và Check-in không yêu cầu số ảnh passport khớp số người lưu trú.

### Sidebar

Sau khi `/check-in` tồn tại như route thật, "Check-in" xuất hiện trong nhóm OPERATIONS của
sidebar hiện có theo đúng permission-aware rule sẵn có (hiển thị khi user có `CHECK_IN`).

---

# 24. Check-out Flow

Check-out operates atomically on the entire Reservation.

Check-out releases the Stay's CURRENT rooms — the open `StayRoomAssignment` rows (xem mục 8.3) —
không phải danh sách `ReservationRoom` gốc. Với một Stay chưa từng Room Change, hai tập hợp này
trùng nhau nên hành vi check-out không đổi. Với một Stay đã Room Change, chỉ phòng đang thực sự
bị chiếm mới được release; phòng gốc đã được release trước đó bởi Room Change không bị đụng đến
lần nữa.

Ví dụ: Reservation đặt phòng 201 + 202. Sau Room Change 201 → 305, check-out phải chuyển 305 và
202 sang DIRTY; 201 không được đụng đến vì assignment của nó đã bị đóng bởi Room Change.

Nếu Stay chiếm nhiều Room hiện tại:

```text
every current Room phải OCCUPIED trước check-out
every current Room chuyển OCCUPIED -> DIRTY
Reservation chuyển CHECKED_IN -> CHECKED_OUT
Stay chuyển CHECKED_IN -> CHECKED_OUT
actual_check_out_at được ghi nhận
mọi open StayRoomAssignment của Stay được đóng tại đúng actual_check_out_at
```

Không hỗ trợ partial-room check-out trong current version.

Nếu bất kỳ current Room không thể chuyển `OCCUPIED -> DIRTY`, toàn bộ check-out thất bại và không có partial check-out.

Check-out transaction phải atomically:

```text
1. Load CHECKED_IN Reservation
2. Load unique Stay
3. Calculate Total Charges
4. Calculate Total PAID Payments
5. Calculate Outstanding
6. Reject nếu Outstanding != 0
7. Load open StayRoomAssignment rows (current Rooms) của Stay
8. Lock all current Rooms
9. Require every current Room = OCCUPIED
10. Transition every current Room OCCUPIED -> DIRTY
11. Transition Reservation CHECKED_IN -> CHECKED_OUT
12. Transition Stay CHECKED_IN -> CHECKED_OUT
13. Record actual_check_out_at
14. Close every open StayRoomAssignment tại actual_check_out_at, giữ nguyên lịch sử assignment
    đã đóng trước đó
15. Apply authenticated-user audit updates
16. Write approved CHECK_OUT audit log
```

Nếu bất kỳ step nào fail, transaction phải roll back.

Check-out yêu cầu `CHECK_OUT`.

Không có check-out override permission.

Existing `CHECK_IN` authorization remains unchanged.

`actual_check_out_at` phải lấy từ authoritative hotel Clock (`Instant.now(clock)`), không phải
`Instant.now()` mặc định và không được client/browser cung cấp. Backend tính đúng một `Instant`
cho toàn bộ transaction và dùng lại chính giá trị đó cho cả `Stay.actual_check_out_at` lẫn
`assigned_to` của mọi `StayRoomAssignment` đang mở được đóng trong transaction đó — hai giá trị
này luôn bằng nhau.

Check-out không phân biệt Early / Normal / Late so với `Reservation.check_out_date`. Cả ba
trường hợp (`hotelToday < check_out_date`, `== check_out_date`, `> check_out_date`) đều được
phép check-out như nhau khi các điều kiện hiện có (Reservation/Stay CHECKED_IN, Outstanding = 0,
current Room = OCCUPIED) đã thỏa mãn. V1 không có cảnh báo, phí, hoàn tiền, thay đổi giá, hoặc
gia hạn Reservation dựa trên thời điểm check-out thực tế so với ngày dự kiến.

### Check-out Operational Screen

`/check-out` là màn hình vận hành dạng queue/search, KHÔNG phải Reservation List thay thế: chỉ
hiển thị Reservation có `status = CHECKED_IN` và Stay `status = CHECKED_IN`. Tìm kiếm theo
Reservation Number, Guest, Room, tái dùng infrastructure search/pagination hiện có của
Reservation (`ReservationSearchCriteria`/`ReservationQueryService`), buộc `status=CHECKED_IN`
giống cách Check-in search buộc `status=CONFIRMED`.

Reservation Number và Guest được ủy quyền trực tiếp cho `ReservationQueryService.findPage()`
không thay đổi. Riêng filter Room KHÔNG được ủy quyền nguyên trạng: query Room có sẵn của
`ReservationQueryService` join trên `ReservationRoom` (snapshot đặt phòng gốc, bất biến), nên sau
Room Change nó không còn phản ánh đúng phòng vật lý hiện tại của Stay. Vì vậy
`CheckOutQueryService.search()` tạm xóa filter Room trước khi gọi `findPage()` (khôi phục lại
ngay sau đó để form tìm kiếm không mất giá trị đã nhập), rồi tự so khớp filter Room với danh sách
current Room (từ open `StayRoomAssignment`) của từng dòng kết quả đã trả về. Giới hạn được ghi
nhận rõ ràng: việc so khớp Room chỉ áp dụng trong phạm vi trang kết quả CHECKED_IN hiện tại (kích
thước trang cố định, xem `ReservationQueryService`), không phải tìm kiếm Room xuyên trang — chấp
nhận được cho quy mô hàng đợi vận hành V1 (số lượt lưu trú đang CHECKED_IN đồng thời tại một thời
điểm), không phải chuẩn hóa Data Table UX tổng quát (đó là phạm vi Task 26).

Cột "Current Room(s)" trên danh sách và trên Check-out Review luôn đọc từ open
`StayRoomAssignment` (current Rooms), không phải `ReservationRoom` gốc — cùng nguyên tắc current-
room đã mô tả ở đầu mục 24. Ví dụ: Reservation đặt phòng 201 + 202, sau Room Change 201 → 305,
danh sách và Review phải hiển thị "305, 202", không hiển thị "201, 202". Tìm theo Room "305" (phòng
hiện tại) phải tìm thấy Stay này; tìm theo Room "201" (phòng gốc đã được giải phóng bởi Room
Change) không được tìm thấy nữa.

Guest hiển thị dưới dạng Guest Code (ví dụ "DEMO-G013"), không hiển thị mini profile
(passport/nationality/address/phone/email/DOB). Guest Code chỉ là link khi user có
`MANAGE_GUEST`; nếu không, hiển thị plain text.

Readiness (`READY` / `PAYMENT_REQUIRED`) tái dùng `StayBalanceService` làm nguồn Outstanding duy
nhất, không tính lại. User chỉ có `CHECK_OUT` (không có `MANAGE_PAYMENT`) chỉ thấy nhãn
READY/PAYMENT REQUIRED, không thấy Total Charges/Total Paid/Outstanding amount — đúng nguyên tắc
đã nêu ở mục 12 (`CHECK_OUT` không cấp detailed financial read access).

Check-out Review (`GET /check-out/{id}`) là màn hình read-only trước khi xác nhận, hiển thị
Guest, Current Rooms, planned check-in/check-out date, actual check-in, và Readiness. Review
không tự làm authoritative cho Outstanding: nút "Confirm Check-out" chỉ hiển thị khi Reservation
đang CHECKED_IN và Readiness = READY, nhưng hành động Confirm (`POST /check-out/{id}/confirm`)
vẫn gọi lại đúng `ReservationService.checkOut(reservationId)` hiện có — service này tự revalidate
toàn bộ điều kiện (Outstanding, trạng thái Reservation/Stay/Room) độc lập với dữ liệu Review có
thể đã cũ.

`/check-out` là một entry point BỔ SUNG cho check-out, không thay thế các entry point sẵn có
(nút Check-out trên Reservation Detail và trên Folio) — tất cả entry point đều hội tụ vào cùng
một `ReservationService.checkOut()`.

### Sidebar

Sau khi `/check-out` tồn tại như route thật, "Check-out" xuất hiện trong nhóm OPERATIONS của
sidebar hiện có, ngay dưới "Check-in", theo đúng permission-aware rule sẵn có (hiển thị khi user
có `CHECK_OUT`). Thứ tự nhóm OPERATIONS: Check-in, Check-out, Reservations, Guests.

---

# 25. Cancellation Flow

```text
CONFIRMED
    ↓
Cancellation rule
    ↓
Cancellation fee nếu có
    ↓
Refund nếu cần
    ↓
CANCELLED
```

Không xóa Reservation để thực hiện cancellation.

---

# 26. Security Domain

## 26.1 AppUser

Table:

```text
app_user
```

Fields:

```text
id
username
password_hash
email
active
created_at
created_by
updated_at
updated_by
```

User không được hard-delete nếu còn được reference bởi dữ liệu lịch sử.

Khi user nghỉ:

```text
active = false
```

Dữ liệu cũ vẫn giữ `created_by` / `updated_by`.

---

# 27. Role

Fields:

```text
id
code
name
created_at
created_by
updated_at
updated_by
```

Roles đã thống nhất:

```text
ADMIN
MANAGER
STAFF
```

---

# 28. Permission

Fields:

```text
id
code
name
created_at
created_by
updated_at
updated_by
```

Permissions đã thống nhất:

```text
MANAGE_USER
MANAGE_ROOM
MANAGE_BOOKING
MANAGE_PAYMENT
MANAGE_EXPENSE
MANAGE_GUEST
VIEW_REPORT
VIEW_BOOKING
CHECK_IN
CHECK_OUT
DELETE_RESERVATION
CHANGE_ROOM
```

Permission mapping đã thống nhất:

```text
ADMIN
 ├── MANAGE_USER
 ├── MANAGE_ROOM
 ├── MANAGE_BOOKING
 ├── MANAGE_PAYMENT
 ├── MANAGE_EXPENSE
 ├── MANAGE_GUEST
 ├── VIEW_REPORT
 ├── VIEW_BOOKING
 ├── CHECK_OUT
 └── CHANGE_ROOM
```

```text
MANAGER
 ├── MANAGE_ROOM
 ├── MANAGE_BOOKING
 ├── MANAGE_PAYMENT
 ├── VIEW_REPORT
 ├── VIEW_BOOKING
 ├── MANAGE_GUEST
 ├── CHECK_IN
 ├── CHECK_OUT
 └── CHANGE_ROOM
```

```text
STAFF
 ├── VIEW_BOOKING
 ├── CHECK_IN
 ├── CHECK_OUT
 ├── MANAGE_PAYMENT
 └── CHANGE_ROOM
```

`DELETE_RESERVATION` được xác định là permission có thể tồn tại trong hệ thống, nhưng business flow reservation không sử dụng hard delete.

`MANAGE_PAYMENT` được cấp cho STAFF để hỗ trợ thao tác Payment vận hành khi check-out (xem
Operational Sidebar Restructure). `CHECK_IN` được cấp cho MANAGER để đảm bảo cả ba role
(ADMIN/MANAGER/STAFF) đều có thể thực hiện Check-in (xem 23.1). `CHECK_IN` còn cấp quyền ĐỌC
ảnh passport Guest qua secure passport-image endpoint, không cấp Guest management.

`CHANGE_ROOM` được cấp cho cả ba role (ADMIN/MANAGER/STAFF) vì Room Change là một thao tác vận
hành tại quầy lễ tân trong một Stay đang hoạt động (xem 8.3); STAFF cần thực hiện được mà không
cần được cấp `MANAGE_BOOKING`. `CHANGE_ROOM` là permission riêng biệt, không được suy ra từ
`CHECK_IN`, `CHECK_OUT`, hay `MANAGE_BOOKING`.

---

# 29. User-Role Relationship

```text
AppUser N ─── N Role
```

Through:

```text
user_role
-----------
user_id
role_id
```

---

# 30. Role-Permission Relationship

```text
Role N ─── N Permission
```

Through:

```text
role_permission
---------------
role_id
permission_id
```

---

# 31. Audit Standard

Các entity nghiệp vụ sử dụng:

```text
created_at
created_by
updated_at
updated_by
```

Trong đó:

```text
created_by → app_user.id
updated_by → app_user.id
```

Naming convention:

```text
created_by
updated_by
```

Không dùng:

```text
created_id
update_id
```

---

# 32. Audit Log

`created_by` / `updated_by` không thay thế Audit Log.

Audit Log dùng để lưu lịch sử thao tác.

## 32.1 AuditLog

Fields:

```text
id
user_id
action
entity_type
entity_id
old_value
new_value
ip_address
created_at
```

Ví dụ:

```text
user: manager01
action: CHANGE_ROOM_PRICE
entity_type: ROOM
entity_id: 101

old_value: ¥10,000
new_value: ¥15,000
```

Các thao tác quan trọng phải có audit.

Ví dụ:

```text
DELETE_RESERVATION
CHANGE_ROOM
REFUND_PAYMENT
CHANGE_PRICE
CHANGE_EXPENSE
CHANGE_USER_ROLE
CHECK_OUT
```

---

# 33. Audit Fields Must Not Come From Client

Client không được phép tự gửi:

```json
{
  "createdBy": 123,
  "updatedBy": 456
}
```

Application phải lấy current authenticated user.

Flow:

```text
HTTP Request
      ↓
Authentication
      ↓
Spring Security
      ↓
Current User
      ↓
Service
      ↓
Entity
      ├── created_by = currentUser.id
      └── updated_by = currentUser.id
```

Đây là security requirement.

---

# 34. System User

Các operation được thực hiện tự động bởi hệ thống có thể sử dụng một System User.

Ví dụ:

```text
SYSTEM
```

User này dùng cho các operation như OTA synchronization.

Khi hệ thống tự tạo record:

```text
created_by = SYSTEM_USER
```

---

# 35. Soft Delete

Soft delete được sử dụng cho những entity cần giữ lịch sử.

Fields:

```text
deleted_at
deleted_by
```

Khi active:

```text
deleted_at = NULL
```

Khi soft-delete:

```text
deleted_at = timestamp
deleted_by = user id
```

Không áp dụng hard-delete cho những dữ liệu cần giữ lịch sử nghiệp vụ.

---

# 36. Double Booking Protection

Room không được có hai Reservation overlap trong cùng khoảng thời gian.

Ví dụ:

```text
Room 101

Booking A:
Sep 10 → Sep 12

Booking B:
Sep 11 → Sep 13
```

Không được phép.

Chỉ kiểm tra ở Java là không đủ vì có thể xảy ra concurrent requests:

```text
Request A → check available
Request B → check available

Request A → insert
Request B → insert
```

Implementation phải kết hợp:

```text
Application validation
+
Database protection / locking
+
Transaction
```

Mục tiêu:

```text
No double booking
```

---

# 37. Database Constraints

Các constraint đã thống nhất:

## Guest

```text
guest_code UNIQUE
```

## RoomType

```text
code UNIQUE
```

## Room

```text
room_number UNIQUE
```

## Reservation

```text
check_out_date > check_in_date
```

## External booking

```text
UNIQUE(source, external_booking_id)
```

## Payment

```text
amount > 0
```

## Charge

```text
quantity is absent or quantity > 0
```

## AccountingEntryLine

```text
debit >= 0
credit >= 0
```

## Accounting

```text
SUM(debit) = SUM(credit)
```

---

# 38. Recommended Initial Indexes

Các index đã xác định:

```text
reservation(guest_id)

reservation(check_in_date, check_out_date)

reservation(source, external_booking_id)

reservation_room(room_id, check_in_date, check_out_date)

payment(stay_id)

charge(stay_id)

expense(expense_date)

accounting_entry(entry_date)

audit_log(entity_type, entity_id)

audit_log(user_id, created_at)
```

---

# 39. OTA Integration

Hệ thống có khả năng hỗ trợ:

```text
Agoda
Booking.com
Airbnb
```

Booking source:

```text
DIRECT
AGODA
BOOKING_COM
AIRBNB
```

Kiến trúc mong muốn:

```text
Agoda
Booking.com
Airbnb
     │
     ↓
External Booking
     │
     ↓
Booking Adapter
     │
     ↓
Reservation
     │
     ↓
Room Availability
```

---

# 40. iCal

Giai đoạn đầu có thể sử dụng iCal để đồng bộ lịch.

Flow:

```text
Agoda ─────┐
Booking ───┼── iCal ──> Hotel System
Airbnb ────┘
```

Scheduler:

```text
Scheduler
    ↓
Fetch iCal
    ↓
Parse events
    ↓
Compare reservation
    ↓
Update room availability
```

iCal được xác định là cơ chế đồng bộ lịch phòng, không được giả định rằng iCal chứa toàn bộ thông tin booking.

Các dữ liệu như:

```text
price
guest information
payment
full booking detail
```

không được tự động coi là có trong iCal.

---

# 41. Dashboard

Dashboard v1 là read-only và yêu cầu permission:

```text
VIEW_REPORT
```

MVC route:

```text
GET /dashboard
```

Dashboard v1 không yêu cầu REST endpoint.

Dashboard v1 chỉ hiển thị:

```text
Reservation summary
- total Reservation count
- Reservation count grouped by status

Room summary
- total active Rooms
- active Room count grouped by operational status

Stay summary
- count of currently CHECKED_IN Stays

Operational Room alerts for active Rooms
- DIRTY
- CLEANING
- MAINTENANCE
- OUT_OF_ORDER

Expense summary
- Expense count grouped by status
```

Dashboard Room summary và operational alerts chỉ bao gồm Room có `active = true`.
Dashboard Room metrics chỉ đại diện cho operational Room status; không suy ra booking availability từ `Room.status = AVAILABLE`.

Dashboard v1 không bao gồm:

```text
today's arrivals
today's departures
upcoming reservations
Revenue
Profit
global Charge total
global Payment total
global Outstanding
Pending Payment summary
occupancy rate
ADR
RevPAR
cancellation rate
average stay
any other hotel KPI not explicitly approved
```

Các reference Revenue, Expense, và Profit như Dashboard KPI là future reporting goals. Dashboard v1 không tính hoặc hiển thị Revenue hoặc Profit; PAID Payment không được gắn nhãn Revenue. Accounting-based financial KPI sẽ được định nghĩa trong future Accounting/Reporting specification.

Dashboard v1 cố ý không có date-scoped arrival/departure metrics. Scheduled-vs-actual semantics và Dashboard business timezone sẽ được specified separately khi thêm date-scoped Dashboard metric.

Dashboard template có thể đặt tại:

```text
src/main/resources/templates/dashboard/
```

## 41.1 Dashboard Analytics v2

Dashboard Analytics v2 vẫn là read-only, dùng cùng MVC route Dashboard hiện có, và tiếp tục yêu cầu permission:

```text
VIEW_REPORT
```

Không thêm user-selectable date filter trong Analytics v2.

### Reporting period

Analytics v2 dùng current calendar year làm reporting period mặc định. Với current year `Y`:

```text
startDate = Y-01-01
endDateExclusive = (Y + 1)-01-01
```

Mọi Analytics v2 dataset phải dùng `Reservation.checkInDate` để xác định Reservation có thuộc reporting period hay không.

Không dùng:

```text
Reservation.createdAt
Reservation.reservedAt
Stay.actualCheckInAt
```

### Reservations by Check-in Month

Definition:

```text
COUNT(Reservation)
GROUP BY year/month of Reservation.checkInDate
within the current calendar year
```

Metric này hiển thị planned booking volume, không phải completed stays.

Hiển thị đủ mười hai tháng January đến December. Month không có Reservation phải có count = `0`.

Không filter theo `Reservation.status`.

### Booked Rooms by RoomType

Definition:

```text
COUNT(ReservationRoom)
GROUP BY RoomType
WHERE owning Reservation.checkInDate is within the current calendar year
```

Count `ReservationRoom` records, không count distinct `Reservation`.

Không filter theo `Reservation.status`.

`RoomType.code` là stable grouping identity. `RoomType.name` có thể dùng làm presentation text.

Analytics v2 có thể resolve RoomType qua:

```text
ReservationRoom -> Room -> RoomType
```

Vì vậy Analytics v2 dùng current RoomType assignment của Room. Không thêm historical RoomType snapshot trong version này. Nếu cần strict historical RoomType-at-booking reporting sau này, requirement đó phải được specified riêng.

### Reservations by Source

Definition:

```text
COUNT(Reservation)
GROUP BY Reservation.source
WHERE Reservation.checkInDate is within the current calendar year
```

Không filter theo `Reservation.status`.

Approved sources giữ nguyên:

```text
DIRECT
AGODA
BOOKING_COM
AIRBNB
```

Không thêm source mới.

### Consistent reporting period

Cả ba Analytics v2 charts phải dùng cùng current-calendar-year `Reservation.checkInDate` reporting period. Không dùng all-history aggregation cho RoomType hoặc Source trong khi monthly chart dùng year range.

### Chart presentation

Approved visualizations:

```text
Reservations by Check-in Month: bar or line chart
Booked Rooms by RoomType: bar chart
Reservations by Source: doughnut or pie chart
```

Chart.js được approved làm rendering library. Dùng pinned/local application asset, không dùng unpinned runtime CDN.

Backend phải cung cấp already aggregated data. Frontend JavaScript chỉ được transform data đó thành Chart.js datasets và render charts.

Frontend JavaScript không được:

```text
calculate business metrics
query the database
determine permissions
infer Reservation state
infer Room availability
```

### Zero-count handling

Dashboard backend layer phải cung cấp deterministic complete datasets. Với monthly analytics:

```text
all 12 months must be represented
missing query results are zero-filled by backend/service logic
```

Charts không được tự determine missing business buckets.

### Analytics v2 boundaries

Analytics v2 không thêm:

```text
Revenue
Profit
ADR
RevPAR
Occupancy Rate
Payment totals
Outstanding totals
financial/accounting metrics
```

Dashboard authorization không thay đổi.

---

# 42. Accounting Calculation

Lợi nhuận không được tính đơn giản chỉ từ Reservation.

Logic tổng quát:

```text
Revenue
  -
Expense
  =
Operating Profit
```

Expense có thể cần accounting representation phù hợp trong future Accounting implementation. Behavior này nằm ngoài Expense v1: Expense `POSTED` hiện không tạo `AccountingEntry`. AccountingEntry creation rules, debit/credit rules, account mapping, và posting behavior sẽ được specified separately trong Accounting scope.

---

# 43. Security Processing Pipeline

Mọi operation quan trọng phải tuân theo flow:

```text
Request
   ↓
Authentication
   ↓
Authorization
   ↓
State Machine Validation
   ↓
Business Rule Validation
   ↓
Transaction
   ↓
Audit Log
```

Không được bỏ qua state validation chỉ vì user có permission.

Ví dụ:

User có:

```text
CHECK_IN
```

nhưng Reservation:

```text
CANCELLED
```

thì vẫn phải reject.

---

# 44. Core API Use Cases

Các API/use cases đã xác định:

```text
POST /api/reservations
POST /api/reservations/{id}/confirm
POST /api/reservations/{id}/check-in
POST /api/stays/{id}/check-out
POST /api/payments
POST /api/expenses
```

API implementation phải gọi business service tương ứng.

Không expose trực tiếp việc thay đổi state thông qua generic CRUD.

Không cho phép client tự do:

```text
reservation.status = ...
room.status = ...
payment.status = ...
accountingEntry.status = ...
```

Business transition phải được thực hiện thông qua operation tương ứng.

---

# 45. Generic CRUD Restriction

Không được thiết kế business logic theo kiểu:

```text
PUT /reservation/{id}
{
    "status": "CHECKED_OUT"
}
```

cho các state transition quan trọng.

Thay vào đó:

```text
POST /reservations/{id}/confirm
POST /reservations/{id}/check-in
POST /stays/{id}/check-out
```

Business operation phải kiểm tra:

```text
Current State
      ↓
Allowed Transition?
      ↓
Business Rules
      ↓
Execute
```

---

# 46. Base Audit Model

Các entity nghiệp vụ nên dùng một base audit model để tránh duplicate field.

Concept:

```text
BaseEntity
├── created_at
├── created_by
├── updated_at
└── updated_by
```

Các entity phù hợp kế thừa/áp dụng audit model:

```text
Guest
RoomType
Room
Reservation
ReservationRoom
Stay
Charge
Payment
Expense
ExpenseCategory
Account
AppUser
Role
Permission
```

Accounting Entry có:

```text
created_at
created_by
posted_at
```

và không được sửa trực tiếp sau POSTED.

---

# 47. Critical Business Invariants

Đây là danh sách invariant bắt buộc phải được bảo vệ bằng code và database khi có thể.

```text
1. check_out_date > check_in_date

2. Room không được double-booked.

3. Reservation chỉ được chuyển qua allowed state transition.

4. CANCELLED reservation không được check-in.

5. CHECKED_OUT reservation không được check-in lại.

6. NO_SHOW reservation không được check-in.

7. Check-out yêu cầu outstanding balance = 0.
   Không có override permission trong current scope.

8. Payment amount > 0.

9. Charge uses a valid FIXED AMOUNT or ITEMIZED pricing mode.

10. Accounting debit/credit phải cân bằng.

11. POSTED accounting entry không được update trực tiếp.

12. Client không được tự set created_by / updated_by.

13. User history không được mất khi user bị deactivate.

14. Reservation cancellation không dùng hard delete.

15. Reservation price phải giữ snapshot tại thời điểm booking.

16. Reservation status và Room status là hai state machine độc lập.
```

---

# 48. Implementation Architecture

Kiến trúc logic:

```text
Controller
    ↓
Application / Service Layer
    ↓
Domain Business Rules
    ↓
Repository
    ↓
PostgreSQL
```

Security:

```text
Spring Security
      ↓
Authentication
      ↓
Authorization
```

Audit:

```text
Current User
      ↓
Audit fields
      +
Audit Log
```

Transaction:

```text
Service operation
      ↓
@Transactional
      ↓
State change
      ↓
Related entity changes
      ↓
Accounting / Audit
```

---

# 49. Domain Service Principle

Business operation phải được đặt trong service/domain logic phù hợp.

Không để Controller trực tiếp:

```text
repository.save(...)
```

để thực hiện business operation.

Ví dụ:

```text
ReservationController
        ↓
ReservationService
        ↓
confirm()
checkIn()
cancel()
noShow()
```

Không:

```text
ReservationController
        ↓
reservation.setStatus(...)
        ↓
repository.save(...)
```

---

# 50. Implementation Priority

Thứ tự triển khai đã thống nhất:

## Phase 1 — PMS Core

```text
Room
RoomType
Guest
Reservation
ReservationRoom
Stay
Charge
Payment
Dashboard
Check-in
Check-out
```

## Phase 2 — Accounting

```text
Expense
ExpenseCategory
Account
AccountingEntry
AccountingEntryLine
Revenue
Expense
Profit/Loss
Monthly Report
```

## Phase 3 — OTA Integration

```text
Agoda
Booking.com
Airbnb
iCal
Synchronization
```

## Phase 4 — Security / Enterprise

```text
RBAC
Permission
Audit Log
Approval
Notification
Backup
Monitoring
```

Các phần Phase 2–4 không được tự động coi là đã implement chỉ vì database có thể hỗ trợ chúng.

---

# 51. Explicit Non-Goals for Current Implementation

Codex **không được tự thêm business requirement** ngoài tài liệu này.

Không tự thêm:

```text
loyalty program
room service
restaurant POS
inventory management
payroll
tax filing
multi-property management
currency exchange
dynamic pricing
AI chatbot
automatic pricing
channel manager implementation
mobile application
```

nếu chưa có requirement riêng.

Đặc biệt:

> Không tự suy diễn business rule mới từ các tên field hoặc domain.

Nếu implementation gặp requirement chưa được định nghĩa, phải giữ thiết kế mở và không tự quyết định business behavior.

---

# 52. Codex Implementation Rules

Codex phải tuân thủ các nguyên tắc:

### Rule 1 — Không bypass state machine

Không được trực tiếp set:

```text
reservation.status
room.status
payment.status
accounting_entry.status
```

trong business code nếu transition không đi qua operation tương ứng.

### Rule 2 — Không trust client audit fields

Không nhận:

```text
created_by
updated_by
```

từ client.

### Rule 3 — Không hard-delete historical business data

Đặc biệt:

```text
Reservation
Payment
Accounting Entry
Audit Log
User referenced by history
```

### Rule 4 — Accounting POSTED immutable

Không update/delete POSTED accounting entry.

### Rule 5 — Authorization trước business operation

Flow:

```text
Authentication
→ Authorization
→ State validation
→ Business validation
→ Transaction
```

### Rule 6 — Database constraints không được bỏ qua

Application validation không thay thế database constraints.

### Rule 7 — Concurrent booking phải an toàn

Phải chống double booking trong concurrent requests.

### Rule 8 — Audit các thao tác quan trọng

Các thay đổi quan trọng phải ghi AuditLog.

---

# 53. Definition of Done

Một business operation chỉ được coi là hoàn thành khi:

```text
✓ Authentication được kiểm tra
✓ Authorization được kiểm tra
✓ State transition hợp lệ
✓ Business invariant được kiểm tra
✓ Transaction được đảm bảo
✓ Database constraint được áp dụng
✓ Audit field được cập nhật
✓ Audit Log được ghi nếu operation thuộc nhóm cần audit
✓ Có test cho happy path
✓ Có test cho invalid state
✓ Có test cho authorization failure
✓ Có test cho business rule violation
```

---

# 54. Source of Truth

Tài liệu này là **baseline business/technical specification v1.0**.

Khi Codex implement:

```text
Requirement
    ↓
This Specification
    ↓
Design
    ↓
Implementation
    ↓
Tests
```

Không được đảo ngược thành:

```text
Generated Code
    ↓
AI tự suy đoán Requirement
```

Nếu code và specification mâu thuẫn:

```text
Specification wins.
```

Nếu requirement chưa được định nghĩa trong specification:

```text
Do not invent business behavior.
```

---

# 55. Final System Model

Hệ thống cuối cùng theo baseline:

```text
                         HOTEL MANAGEMENT SYSTEM
                                   │
       ┌───────────────────────────┼───────────────────────────┐
       │                           │                           │
     GUEST                       ROOM                     RESERVATION
       │                           │                           │
       │                    ┌──────┴──────┐             ┌──────┴──────┐
       │                    │             │             │             │
       │                ROOM_TYPE       STATUS    RESERVATION_ROOM   STAY
       │                                                │             │
       │                                                │        ┌────┴────┐
       │                                                │        │         │
       │                                                │      CHARGE   PAYMENT
       │                                                │                  │
       └────────────────────────────────────────────────┴──────────────────┘
                                                                          │
                                                                          ↓
                                                                 ACCOUNTING ENTRY
                                                                          │
                                                                          ↓
                                                               ACCOUNTING ENTRY LINE
                                                                          │
                                                                          ↓
                                                                       ACCOUNT


EXPENSE ────────────────────────────────────────────────────────> ACCOUNTING


                         SECURITY
                            │
              ┌─────────────┼─────────────┐
              ↓             ↓             ↓
            USER           ROLE       PERMISSION
              │             │
              └─────────────┴───────────────
                            │
                            ↓
                       AUDIT LOG
```

---

# 56. Staff Management + Daily Work Record

V1 bổ sung đúng hai khả năng nhỏ, tách biệt hoàn toàn khỏi User Management (Task 24) và Role/
Permission Management (Task 25):

```text
A. Staff Management
B. Daily Work Record
```

Đây KHÔNG phải hệ thống chấm công/lịch làm việc/payroll.

## 56.1 Staff ≠ AppUser

`Staff` đại diện cho một NHÂN VIÊN/CON NGƯỜI của khách sạn. `AppUser` đại diện cho một TÀI
KHOẢN đăng nhập PMS. Hai khái niệm này hoàn toàn tách biệt trong V1:

```text
Staff
   │
   └── (tùy chọn) staff.app_user_id → AppUser — xem mục 57
```

Một nhân viên (ví dụ Housekeeping) có thể tồn tại trong `Staff` mà KHÔNG cần bất kỳ tài khoản
PMS nào. Liên kết tùy chọn `Staff 0..1 AppUser` được định nghĩa ở mục 57 (Task 24, User
Management).

## 56.2 Staff

Fields:

```text
id
staff_code
first_name
last_name
phone
email
position
start_date
active
notes
created_at / created_by / updated_at / updated_by
```

Bắt buộc: `staff_code` (backend-generated), `first_name`, `last_name`, `start_date`. Còn lại là
tùy chọn. `position` là free text V1 (ví dụ Manager, Receptionist, Housekeeping, Security,
Maintenance, Night Reception, Accountant, Part-time) — KHÔNG có `StaffPosition` enum, KHÔNG có
Position Management riêng.

`staff_code` được sinh bởi backend qua một PostgreSQL sequence riêng (`staff_code_sequence`,
tách biệt hoàn toàn khỏi `guest_code_sequence`), theo đúng kiến trúc sinh mã đã duyệt của Guest
(`GuestService.nextAvailableGuestCode` → tương đương `StaffService`), định dạng `STF-000001`,
`STF-000002`, ... Mã này bất biến sau khi tạo và không bao giờ do người dùng nhập tay.

Vòng đời Staff KHÔNG có physical delete. Yêu cầu "Delete Staff" ban đầu của người dùng được hiện
thực hóa bằng `active = true/false` với hai thao tác tường minh: Deactivate / Reactivate, theo
đúng pattern đã duyệt của ExpenseCategory/AdditionalRevenueCategory (transition có kiểm tra
trạng thái hiện tại, từ chối an toàn nếu chuyển đổi không hợp lệ). Deactivate/Reactivate KHÔNG
bao giờ xóa hay cascade-xóa lịch sử `DailyWorkRecord` của Staff đó.

## 56.3 Daily Work Record

Fields:

```text
id
staff_id (FK → staff)
work_date    (LocalDate)
start_time   (LocalTime)
end_time     (LocalTime)
notes
created_at / created_by / updated_at / updated_by
```

Bất biến nghiệp vụ: một Staff có TỐI ĐA MỘT `DailyWorkRecord` cho một `work_date`, được enforce
ở cả tầng ứng dụng (tìm-rồi-cập nhật-hoặc-tạo-mới, không bao giờ tạo dòng thứ hai) lẫn tầng
database (`UNIQUE (staff_id, work_date)`, index `ux_daily_work_record_staff_date`).

V1 chỉ hỗ trợ ca làm việc TRONG NGÀY (same-day). Khi một dòng được lưu, bắt buộc
`start_time < end_time` (enforced bằng CHECK constraint `daily_work_record_start_before_end`
và tại entity). V1 KHÔNG suy luận ca qua đêm (overnight), KHÔNG có cờ overnight, KHÔNG có khái
niệm Shift.

Working Time (ví dụ "9h00", "8h55") là giá trị DẪN XUẤT, tính từ `end_time - start_time`, KHÔNG
được lưu trữ trong database và luôn được tính lại phía server trước khi hiển thị — never trusted
from the client.

### Nhập liệu hàng loạt (bulk entry)

Màn hình Daily Work Record cho một `work_date` được chọn: tải toàn bộ Staff đang `active`, mỗi
Staff một dòng, cho phép nhập Start/End/Notes cho nhiều Staff cùng lúc, lưu trong MỘT thao tác
transactional duy nhất (`DailyWorkRecordService.saveBulk`).

Quy tắc theo từng dòng:

```text
Cả Start và End đều trống
    → không tạo/không có DailyWorkRecord cho Staff/ngày đó
    → nếu đã tồn tại record cho Staff/ngày đó, record đó bị XÓA (ngoại lệ hard-delete
      được duyệt cho DailyWorkRecord — vì đây là dòng nhập liệu hàng ngày có thể chỉnh sửa,
      không phải Staff master/business identity)

Cả hai đều có giá trị, Start < End
    → tạo mới (nếu chưa có record) hoặc CẬP NHẬT record hiện có (không bao giờ tạo dòng thứ hai)

Chỉ Start có giá trị
    → lỗi validation

Chỉ End có giá trị
    → lỗi validation

Start >= End
    → lỗi validation
```

Toàn bộ request được validate TRƯỚC khi thực hiện bất kỳ thay đổi nào (all-or-nothing): một dòng
không hợp lệ trong nhiều dòng gửi lên sẽ từ chối TOÀN BỘ thao tác lưu, không có dòng nào được lưu
một phần. Ràng buộc UNIQUE ở database vẫn là lớp bảo vệ cuối cùng chống race-condition khi hai
request ghi đồng thời cho cùng một Staff/ngày.

### Staff không còn active

Màn hình nhập liệu MỚI chỉ tải Staff đang `active`; Staff không active sẽ không xuất hiện để
nhận dòng nhập mới, và server từ chối bất kỳ dòng gửi lên cho một Staff không còn active (ví dụ
do trạng thái đổi ngay trong lúc chỉnh sửa). Lịch sử `DailyWorkRecord` đã có của Staff không active
KHÔNG bị xóa và vẫn được truy vấn được — dữ liệu lịch sử luôn được bảo toàn.

## 56.4 Permissions

```text
MANAGE_STAFF        — bảo vệ Staff list/create/edit/deactivate/reactivate
MANAGE_ATTENDANCE   — bảo vệ màn hình Daily Work Record (tải ngày, lưu hàng loạt)
```

Cấp cho `ADMIN` và `MANAGER`. KHÔNG cấp cho role `STAFF` (role đăng nhập hiện có — không nhầm
với domain `Staff`/nhân viên mới ở trên). Hai permission này tách biệt hoàn toàn với
`MANAGE_USER` (permission đã seed từ trước, dành riêng cho Task 24 User Management, không được
tái sử dụng ở đây) và không có `VIEW_STAFF`/`VIEW_ATTENDANCE` trong V1.

## 56.5 Navigation

Sidebar có thêm nhóm mới **ADMINISTRATION** chứa mục **Staff**, hiển thị khi user có
`MANAGE_STAFF` HOẶC `MANAGE_ATTENDANCE` (qua `NavigationModelAdvice`). Trong module Staff, hai
khả năng (Staff Management, Daily Work Record) được liên kết chéo với nhau nhưng mỗi khả năng
tự enforce permission riêng — có `MANAGE_STAFF` không tự động cho phép truy cập Daily Work
Record và ngược lại, dù ADMIN/MANAGER hiện có cả hai.

## 56.6 Staff Detail + Work History (by Staff)

Hai màn hình vận hành bổ sung cho nhau, cùng dùng chung dữ liệu `Staff`/`DailyWorkRecord`, không
tạo bảng hay entity mới:

```text
BY DATE  — Daily Work Record (mục 56.3): "Ngày này, mỗi Staff làm việc mấy giờ?"
BY STAFF — Staff Detail → Work History (mục này): "Staff này, lịch sử làm việc thế nào?"
```

`GET /staff/{id}` (bảo vệ bởi `MANAGE_STAFF`, không tạo permission mới) hiển thị hồ sơ Staff
(Staff Information) cùng Work History — danh sách `DailyWorkRecord` của riêng Staff đó, lọc theo
khoảng ngày `fromDate`/`toDate` (cả hai đều inclusive, `LocalDate`, định dạng hiển thị
`dd/MM/yyyy`), sắp xếp `work_date` giảm dần (mới nhất trước). Truy vấn qua
`DailyWorkRecordRepository.findByStaffIdAndWorkDateBetweenOrderByWorkDateDesc`, không có bảng
hay tầng lưu trữ lịch sử riêng.

Khoảng ngày mặc định khi không truyền tham số: từ ngày đầu tháng hiện tại (theo hotel `Clock`)
đến ngày hiện tại (theo hotel `Clock`) — không dùng `LocalDate.now()` trần. Nếu `fromDate` sau
`toDate`, trả về thông báo validation an toàn ("From date must not be after To date."), KHÔNG lỗi
trang chung.

Work History CHỈ hiển thị những ngày thực sự có `DailyWorkRecord` được lưu — KHÔNG tự sinh dòng
cho những ngày không có record. Một ngày không có record nghĩa là "không có dữ liệu", KHÔNG ngầm
định là nghỉ phép/vắng mặt/ngày lễ. Working Time trong Work History dùng lại đúng công thức dẫn
xuất ở mục 56.3 (`DailyWorkRecordService`), không tạo công thức thứ hai, không lưu trữ.

Staff Detail vẫn truy cập được và hiển thị đầy đủ lịch sử cho Staff đã `INACTIVE` — deactivate
không ẩn hay xóa `DailyWorkRecord`. Màn hình này không cho tạo mới `DailyWorkRecord`; nhập liệu
mới vẫn chỉ qua màn hình Daily Work Record (chỉ Staff `active`, mục 56.3).

Trên UI, nhãn hiển thị cho người dùng dùng **Work Check-in** / **Work Check-out** thay cho
Start/End (ở cả màn hình Daily Work Record lẫn Work History), ánh xạ trực tiếp tới
`startTime`/`endTime`. Đây CHỈ là thuật ngữ hiển thị — field/DB column nội bộ vẫn giữ nguyên
`startTime`/`endTime`; KHÔNG có nghĩa là chấm công tự động, thời gian vẫn do Manager/Admin nhập
tay.

## 56.7 Daily Work Record — By Date / By Staff

Màn hình Daily Work Record có hai chế độ xem, chuyển đổi qua lại bằng tab đơn giản, đều bảo vệ
bởi `MANAGE_ATTENDANCE` (không có permission mới):

```text
Daily Work Record
├── By Date  (GET/POST /staff/daily-work-record)
│   └── bulk manual entry — không thay đổi hành vi nghiệp vụ ở mục 56.3
│
└── By Staff (GET /staff/daily-work-record/by-staff)
    └── tìm kiếm lịch sử theo Staff + khoảng ngày, chỉ đọc (read-only)
```

**By Date** giữ nguyên hành vi hiện có (mục 56.3): chọn một `work_date`, tải toàn bộ Staff
`active`, nhập/lưu hàng loạt.

**By Staff** là một màn hình tìm kiếm trực tiếp, không cần đi vòng qua Staff List → View → Staff
Detail: chọn một Staff (hiển thị `Staff Code - Họ tên`, danh sách chọn bao gồm CẢ Staff `active`
lẫn `inactive` vì lịch sử của Staff không active vẫn phải tìm được), nhập khoảng ngày
`fromDate`/`toDate` (mặc định: từ ngày đầu tháng hiện tại đến ngày hiện tại theo hotel `Clock`),
và xem kết quả. Đây là read-only — màn hình này KHÔNG tạo hay sửa bất kỳ `DailyWorkRecord` nào,
kể cả cho Staff `active`.

By Staff tái sử dụng đúng `DailyWorkRecordService.history(...)` và
`DailyWorkRecordRepository.findByStaffIdAndWorkDateBetweenOrderByWorkDateDesc(...)` đã có ở mục
56.6 (Staff Detail → Work History) — không có truy vấn hay implementation lịch sử thứ hai. Quy
tắc hiển thị giống hệt mục 56.6: chỉ hiện ngày có record thực sự (không tự sinh ngày trống), sắp
xếp mới nhất trước, Working Time luôn dẫn xuất và không lưu trữ, `fromDate > toDate` trả về thông
báo validation an toàn thay vì lỗi trang chung.

Staff Detail → Work History (mục 56.6) vẫn được giữ nguyên như một lối vào khác, hữu ích khi đang
xem hồ sơ một nhân viên cụ thể; cả hai lối vào dùng chung một service/query/business logic.

## 56.8 V1 Exclusions

Không triển khai trong V1 của Task 23: Role/Permission Management
UI, Shift, Shift Scheduling, chấm công tự động, nhân viên tự check-in/check-out, nhiều session
làm việc/ngày, break/lunch tracking, ca qua đêm, tính đi trễ/về sớm, overtime, payroll, salary,
leave/holiday management, GPS, fingerprint, face recognition, biometrics, attendance reports,
Excel/PDF export.

# 57. PMS User Account Management

Task 24 bổ sung quản lý tài khoản đăng nhập PMS (`AppUser`). `Staff` (nhân viên/con người) và
`AppUser` (tài khoản xác thực/bảo mật) vẫn là hai khái niệm tách biệt.

## 57.1 Quan hệ Staff 0..1 AppUser

```text
staff.app_user_id   NULLABLE, UNIQUE (uq_staff_app_user), FK → app_user(id) (fk_staff_app_user)
```

Staff có thể tồn tại không có tài khoản; AppUser có thể tồn tại không có Staff (ví dụ tài khoản
bootstrap/hệ thống); một AppUser liên kết tối đa một Staff và một Staff liên kết tối đa một
AppUser (UNIQUE ở database, cùng validation ở service với thông báo thân thiện). `AppUser` KHÔNG
phụ thuộc `Staff`. Không dùng bảng liên kết.

## 57.2 Quyền và điều hướng

Mọi thao tác User Management (MVC) yêu cầu `PERM_MANAGE_USER` — permission đã seed từ V1 và chỉ
cấp cho `ADMIN`; KHÔNG cấp cho MANAGER/STAFF, KHÔNG thêm permission mới. Độc lập với
`MANAGE_STAFF`/`MANAGE_ATTENDANCE`. Mọi mutation dùng POST + CSRF. Sidebar nhóm ADMINISTRATION có
mục **Users** (cờ `canManageUser` trong `NavigationModelAdvice`). Routes: `GET /users`,
`GET /users/new`, `POST /users`, `GET /users/{id}`, `GET|POST /users/{id}/edit`-`/users/{id}`,
`GET|POST /users/{id}/reset-password`, `POST /users/{id}/activate`, `POST /users/{id}/deactivate`.
Không có hard delete.

## 57.3 Username, mật khẩu, email, role

- Username bất biến sau khi tạo; chuẩn hóa chữ thường; 3..50 ký tự; chỉ `a-z 0-9 . _ -`; duy nhất
  KHÔNG phân biệt hoa/thường (ứng dụng + unique index `ux_app_user_username_lower` trên
  `LOWER(username)`); đăng nhập (MVC và API) cũng không phân biệt hoa/thường. Migration V27 dừng
  với lỗi rõ ràng nếu dữ liệu hiện có xung đột không phân biệt hoa/thường và KHÔNG tự đổi username.
- Mật khẩu: 8..72 ký tự, phải khớp xác nhận, mã hóa bằng BCrypt hiện có, không lưu/log/trả về
  plaintext hay hash. Không có expiry, history, complexity, forgot-password, reset token hay
  buộc đổi mật khẩu. Admin reset mật khẩu qua luồng riêng (`/users/{id}/reset-password`).
- `AppUser.email` không hiển thị/không chỉnh sửa trong V1 và không đồng bộ với `Staff.email`.
- V1 quản lý ĐÚNG MỘT role cho mỗi user (ADMIN, MANAGER, STAFF); đổi role thay thế toàn bộ tập
  role trong một transaction. Schema N:M `user_role` giữ nguyên. Không có custom role, role CRUD,
  chỉnh permission (thuộc Task 25).

## 57.4 Vòng đời và ràng buộc an toàn

- Deactivate user: `active=false`, giữ role, giữ liên kết Staff, không xóa.
- Activate user: bị từ chối nếu user liên kết Staff INACTIVE. Tạo user hoặc gắn Staff cho user
  ACTIVE bị từ chối nếu Staff INACTIVE.
- Deactivate Staff → tự động deactivate AppUser liên kết trong CÙNG transaction (có audit).
  Reactivate Staff KHÔNG reactivate AppUser. Deactivate AppUser KHÔNG deactivate Staff.
- Admin KHÔNG được tự deactivate và KHÔNG được tự đổi role; được xem tài khoản của mình và reset
  mật khẩu của mình. Chặn ở server; UI cũng ẩn/vô hiệu hóa.
- Luôn giữ ít nhất một AppUser ADMIN đang ACTIVE: chặn deactivate hoặc hạ role ADMIN cuối cùng
  (kể cả khi bị deactivate qua Staff). Cài đặt bằng khóa dòng
  `SELECT ... FOR UPDATE` trên toàn bộ ADMIN active (`AppUserRepository.lockActiveAdminIds`) trong
  transaction, nên các thay đổi đồng thời được tuần tự hóa.

## 57.5 Vô hiệu hóa ngay lập tức và phân quyền hiện hành

- MVC: `ActiveUserSessionFilter` kiểm tra tài khoản trong DB ở MỌI request đã xác thực; nếu
  không còn tồn tại/không active thì xóa security context, invalidate session và chuyển về
  `/login`.
- JWT: `JwtFilter` chỉ dùng token để định danh; mỗi request đọc lại AppUser từ DB, không active/
  không tồn tại thì không xác thực (401).
- Đổi role có hiệu lực từ request kế tiếp: cả hai đường đều dựng authorities từ role/permission
  HIỆN TẠI trong DB, không tin snapshot permission trong session/JWT. Không có Redis, blacklist,
  refresh token.

## 57.6 Audit

Thao tác nhạy cảm ghi `AuditLog` với `entity_type=APP_USER`, `entity_id`=AppUser bị tác động:
`USER_ACTIVATE`, `USER_DEACTIVATE` (gồm deactivate tự động do Staff bị deactivate),
`USER_ROLE_CHANGE` (old/new là mã role), `USER_PASSWORD_RESET` (không có dữ liệu mật khẩu).
Đọc/liệt kê user không ghi audit.

**End of Specification v1.0**
