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
MANAGE_ROOM bảo vệ Room Management operations (master data, maintenance, out-of-order).
MANAGE_HOUSEKEEPING bảo vệ các transition housekeeping `start-cleaning` / `finish-cleaning` (mục 21).
Reservation room lookup tiếp tục dùng MANAGE_BOOKING.
```

Operational status và booking availability là hai khái niệm khác nhau.

`Room.status == AVAILABLE` không tự nó xác định Room available cho requested booking period.

Trạng thái hiện tại `OCCUPIED`, `DIRTY`, `CLEANING` mô tả Room ở thời điểm hiện tại, KHÔNG phải trong một kỳ
lưu trú tương lai, nên KHÔNG loại một Room khỏi việc đặt phòng cho kỳ không giao nhau (Room đang `OCCUPIED` hôm
nay vẫn đặt được cho 10/10–12/10 nếu không có Reservation `CONFIRMED`/`CHECKED_IN` giao ngày). Danh sách Room của
form đặt phòng và `/api/rooms/lookup` là bookable inventory: Room `active` không ở `MAINTENANCE`/`OUT_OF_ORDER`
(hiện chưa có dữ liệu thời hạn cho hai trạng thái này nên chúng tiếp tục bị loại), độc lập với trạng thái vận hành
khác; `/api/rooms/lookup` nhận tùy chọn `checkInDate`+`checkOutDate` để loại thêm Room có Reservation giao ngày.
Confirm (`ReservationService.confirm`) vẫn khóa Room và từ chối overlap, không xét `Room.status`.

Ngược lại, READINESS cho check-in NGAY (Walk-in, check-in) là khái niệm riêng: Room phải `active` và `AVAILABLE`
(trạng thái check-in-ready duy nhất của V1); `DIRTY`, `CLEANING`, `MAINTENANCE`, `OUT_OF_ORDER`, `OCCUPIED` không
được đưa ra cho Walk-in. Việc validate check-in không thay đổi.

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

## 6.1a Currency and monetary precision (V1)

Một Reservation có ĐÚNG MỘT currency có thẩm quyền. `ReservationRoom`, `Charge`, `StayExtensionRoom`
và `Payment.appliedAmount` đều được định danh theo currency đó; các bảng này KHÔNG có cột currency
riêng.

Currency được hỗ trợ trong V1:

```text
VND
USD
```

Không hỗ trợ bất kỳ mã ISO-4217 nào khác trong V1. Quy tắc này được enforce nhất quán tại UI, request
validation, service/domain boundary và CHECK constraint của PostgreSQL (`reservation_currency_supported`).
Currency là immutable sau khi Reservation rời trạng thái DRAFT, tức là trước khi bất kỳ khoản tiền nào
có thể tồn tại.

Số chữ số thập phân của từng currency:

```text
VND = 0
USD = 2
```

Hai quy tắc khác nhau áp dụng cho hai loại giá trị tiền tệ:

```text
Giá trị do người dùng nhập (nightly rate, Payment amount, Charge amount/unitPrice,
Additional Revenue amount, Expense amount):
    vượt quá số chữ số thập phân của currency -> TỪ CHỐI (400)
    KHÔNG được âm thầm làm tròn giá trị nhân viên đã nhập

Giá trị do hệ thống tính (FX conversion, itemized quantity x unitPrice):
    chuẩn hóa về số chữ số thập phân của currency đích
    rounding = HALF_UP
```

`Payment.amount` được kiểm tra theo `Payment.currency` (currency khách thực trả), KHÔNG theo currency
của Folio. `Payment.appliedAmount` được chuẩn hóa về currency của Reservation, nên Outstanding không
bao giờ còn số dư lẻ dưới đơn vị nhỏ nhất và Check-out vẫn dùng `outstanding.compareTo(ZERO) == 0`
không cần dung sai.

`exchangeRate` KHÔNG phải là một giá trị tiền tệ định danh theo VND hay USD; nó giữ nguyên độ chính
xác phân số và chỉ bắt buộc `exchangeRate > 0`.

Additional Revenue và Expense là VND-only; báo cáo vẫn ở VND và KHÔNG dùng exchange rate của Payment
để quy đổi doanh thu (xem 61.2).

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
check-out boundary") là ngày trả phòng dự kiến HIỆN TẠI `Reservation.check_out_date` (bằng
`ReservationRoom.check_out_date` bất biến cho tới khi có Stay Extension, xem mục 68; sau đó
`ReservationRoom` vẫn giữ ngày đặt gốc), không lưu trùng lặp.

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
- Room Change bị từ chối khi ngày hiện tại (theo Clock) >= planned check-out boundary hiện tại (`Reservation.check_out_date`, mục 68)
- Room Change KHÔNG BAO GIỜ tự động thay đổi ReservationRoom.nightly_rate, ReservationRoom.total_amount,
  Reservation.total_amount, hoặc các Charge ROOM đã tạo khi check-in
- Nếu khách sạn cần thu thêm phí (ví dụ nâng hạng phòng), Staff tạo Charge riêng qua Charge/Folio
  hiện có, không qua Room Change
```

Khi Room Change thành công, trong cùng một transaction:

```text
1. Đóng assignment hiện tại: assigned_to = thời điểm hiện tại theo Clock
2. Tạo assignment mới, cùng original_reservation_room_id (cùng lineage), assigned_to = null
3. Phòng cũ: OCCUPIED -> DIRTY qua Room Change release (xem mục 21); phòng cũ cần housekeeping
   (DIRTY -> CLEANING -> AVAILABLE) trước khi sẵn sàng cho khách khác
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

scale    = số chữ số thập phân của Reservation/Folio currency (VND = 0, USD = 2)
rounding = HALF_UP
```

`appliedAmount` được làm tròn ĐÚNG MỘT LẦN, trực tiếp về currency của Folio: không có scale trung
gian, nên không tồn tại số dư lẻ dưới đơn vị nhỏ nhất (ví dụ 999.999 VND / 25.000 = 39,99996 được ghi
nhận là 40,00 USD) và cũng không có double rounding. `Payment.amount` được kiểm tra độ chính xác theo
`Payment.currency`, không theo currency của Folio (xem 6.1a). Một khoản cross-currency quá nhỏ để ghi
nhận trong currency của Folio bị TỪ CHỐI thay vì lưu thành Payment giá trị 0.

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
Permission: MANAGE_HOUSEKEEPING

CLEANING -> AVAILABLE
Operation: finish-cleaning
Permission: MANAGE_HOUSEKEEPING

AVAILABLE -> MAINTENANCE
Operation: start-maintenance
Permission: MANAGE_ROOM

DIRTY -> MAINTENANCE
Operation: start-maintenance
Permission: MANAGE_ROOM

MAINTENANCE -> AVAILABLE
Operation: finish-maintenance
Permission: MANAGE_ROOM

AVAILABLE -> OUT_OF_ORDER
Operation: mark-out-of-order
Permission: MANAGE_ROOM

DIRTY -> OUT_OF_ORDER
Operation: mark-out-of-order
Permission: MANAGE_ROOM

OUT_OF_ORDER -> AVAILABLE
Operation: restore-to-service
Permission: MANAGE_ROOM

OCCUPIED -> DIRTY
Operation: room-change-release
Permission: CHANGE_ROOM
```

`start-maintenance` và `mark-out-of-order` (V1 Hardening) chấp nhận cả hai trạng thái nguồn `AVAILABLE` và
`DIRTY`: một phòng bị bỏ lại `DIRTY` sau check-out hoặc Room Change có thể vào thẳng `MAINTENANCE`/`OUT_OF_ORDER`
mà không cần một chu trình housekeeping giả (phòng chưa từng dơ vẫn phải qua `start-cleaning`/`finish-cleaning`).
`OCCUPIED` KHÔNG BAO GIỜ được chuyển trực tiếp sang `MAINTENANCE`/`OUT_OF_ORDER`; khách đang ở phải được chuyển đi
qua Room Change (mục 8.3) trước, đúng chuỗi `OCCUPIED -> DIRTY -> MAINTENANCE/OUT_OF_ORDER`. `CLEANING` cũng
không được vào thẳng `MAINTENANCE`/`OUT_OF_ORDER` trong V1.

`start-maintenance` và `mark-out-of-order` yêu cầu một `reason` dạng free-text bắt buộc (trim, không rỗng, tối đa
2000 ký tự, theo đúng quy ước `no_show_reason`/`cancellation_reason_detail` hiện có). `reason` được lưu trên
`RoomInventoryPeriod` được mở cho giai đoạn không sellable đó (cột `reason`, mục 61.7), không lưu trên `Room`, nên
vẫn còn sau khi phòng trở lại `AVAILABLE` — lịch sử không sellable không bị mất lý do. Một period sellable
(`unavailable_reason IS NULL`) không bao giờ mang `reason`. `finish-maintenance` và `restore-to-service` không
yêu cầu `reason`. Dữ liệu `BOOTSTRAP` hiện có hợp lệ không có `reason`.

Khi `MANAGE_ROOM` chuyển một Room sang `MAINTENANCE`/`OUT_OF_ORDER`, hệ thống kiểm tra Reservation `CONFIRMED`
còn hiệu lực (chưa kết thúc theo ngày khách sạn hiện tại) đang dùng phòng đó và hiển thị một cảnh báo thông tin
(số reservation, ngày nhận/trả phòng) trên form MVC trước khi xác nhận; REST giữ nguyên hành vi xác định, không
thêm bước xác nhận. Cảnh báo KHÔNG chặn transition, KHÔNG tự hủy Reservation, KHÔNG tự đổi phòng, và KHÔNG sửa
`Reservation`/`ReservationRoom`. Reservation `CANCELLED`/`NO_SHOW`/`CHECKED_OUT` không tạo cảnh báo.

Transition `OCCUPIED -> DIRTY` (room-change-release) chỉ được sử dụng bởi Room Change (xem mục 8.3)
để giải phóng phòng cũ. Một phòng khách vừa rời đi không tự động sạch, nên vòng đời V1 thống nhất:

```text
Room Change:   OCCUPIED -> DIRTY
Check-out:     OCCUPIED -> DIRTY
Housekeeping:  DIRTY -> CLEANING -> AVAILABLE
```

Housekeeping thuộc phạm vi V1 (không còn hoãn sang V2). `MANAGE_HOUSEKEEPING` chỉ ủy quyền hai
transition `start-cleaning` (DIRTY -> CLEANING) và `finish-cleaning` (CLEANING -> AVAILABLE); nó KHÔNG cấp quyền
tạo/sửa Room, maintenance hay out-of-order (các thao tác đó vẫn thuộc `MANAGE_ROOM`), và không thay cho việc kiểm tra
state transition. V1 không có role Housekeeper riêng và không có trạng thái `READY` được lưu: "sẵn sàng" chỉ là khái
niệm dẫn xuất và tương đương `AVAILABLE`. Worklist/workspace housekeeping chưa thuộc phần đã triển khai.

Phòng bị bỏ lại sau Room Change phải qua housekeeping (`start-cleaning`, `finish-cleaning`) trước khi thành
`AVAILABLE` và mới được coi là sẵn sàng cho khách khác; nó không được check-in hoặc chọn làm phòng thay thế trong
khi còn `DIRTY`/`CLEANING`. `AVAILABLE` vẫn là trạng thái sẵn sàng đón khách duy nhất của V1 (không có `READY`).
Room Change không tự động chuyển phòng cũ sang `OUT_OF_ORDER`; Maintenance/Room Management chịu trách nhiệm
riêng cho việc đó. Danh sách candidate của Room Change (mục 8.3) loại `MAINTENANCE` và `OUT_OF_ORDER` giống nhau,
cùng điều kiện `active` + `AVAILABLE` mà `changeRoom` dùng làm điều kiện phòng đích có thẩm quyền — không dùng một
quy tắc loại trừ khác cho danh sách gợi ý.

**Ý nghĩa V1 của MAINTENANCE và OUT_OF_ORDER**: `MAINTENANCE` = phòng tạm thời ngừng phục vụ để bảo trì, bảo
dưỡng, hoặc sửa chữa theo kế hoạch. `OUT_OF_ORDER` = phòng hiện không vận hành được do hỏng hóc hoặc tình trạng
khiến phòng không dùng được. Hai trạng thái có cùng hiệu ứng vận hành V1 (không sellable, không check-in-ready)
và cùng thuật toán tính availability; đây là hai nhãn/lý do riêng biệt trên cùng một hiệu ứng, không phải hai
thuật toán khác nhau.

**Chưa hỗ trợ trong V1 (hoãn sang V2)**: lên lịch `MAINTENANCE`/`OUT_OF_ORDER` cho một khoảng ngày trong tương
lai trong khi phòng vẫn sellable ở hiện tại (`RoomBlock`/`RoomUnavailability` với ngày bắt đầu/kết thúc), tự động
phục hồi theo lịch, và tự động hủy/đổi phòng Reservation bị ảnh hưởng. `RoomInventoryPeriod` (mục 61.7) tiếp tục
là sổ cái lịch sử của trạng thái tồn kho THỰC TẾ đã xảy ra, không phải nơi lên kế hoạch cho tương lai.

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
`ReservationRepository.hasOverlap`, và chỉ gồm Room check-in-ready: `active` + `AVAILABLE`, nên loại `DIRTY`/
`CLEANING`/`MAINTENANCE`/`OUT_OF_ORDER`/`OCCUPIED` mà check-in sẽ từ chối). Danh sách
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

Check-out sớm (`hotelToday < check_out_date`) và đúng hạn (`== check_out_date`) được phép như nhau khi các điều kiện
hiện có (Reservation/Stay CHECKED_IN, Outstanding = 0, current Room = OCCUPIED) đã thỏa mãn. Check-out QUÁ HẠN
(`check_out_date < hotelToday`) bị TỪ CHỐI — xem mục 71 (Overdue Departure). V1 không có cảnh báo, phí, hoàn tiền, thay đổi giá, hoặc
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
MANAGE_HOUSEKEEPING
EXTEND_STAY
```

Permission mapping đã thống nhất:

```text
ADMIN
 ├── MANAGE_USER
 ├── MANAGE_ROOM
 ├── MANAGE_HOUSEKEEPING
 ├── MANAGE_BOOKING
 ├── MANAGE_PAYMENT
 ├── MANAGE_EXPENSE
 ├── MANAGE_GUEST
 ├── VIEW_REPORT
 ├── VIEW_BOOKING
 ├── CHECK_OUT
 ├── CHANGE_ROOM
 └── EXTEND_STAY
```

```text
MANAGER
 ├── MANAGE_ROOM
 ├── MANAGE_HOUSEKEEPING
 ├── MANAGE_BOOKING
 ├── MANAGE_PAYMENT
 ├── VIEW_REPORT
 ├── VIEW_BOOKING
 ├── MANAGE_GUEST
 ├── CHECK_IN
 ├── CHECK_OUT
 ├── CHANGE_ROOM
 └── EXTEND_STAY
```

```text
STAFF
 ├── VIEW_BOOKING
 ├── CHECK_IN
 ├── CHECK_OUT
 ├── MANAGE_PAYMENT
 ├── CHANGE_ROOM
 └── EXTEND_STAY
```

STAFF mặc định KHÔNG có `MANAGE_HOUSEKEEPING` (repository/spec không chứng minh STAFF là người dọn phòng); ADMIN có thể
cấp nó qua ma trận permission (mục 58.2). Migration V29 cũng cấp cho mọi role đang giữ `MANAGE_ROOM` để không mất khả
năng cleaning trước đây.

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

# 58. Roles & Permissions Management

Task 25 cho phép ADMIN cấu hình ma trận permission của ba role built-in. Không có thay đổi
schema/migration: schema `role`, `permission`, `role_permission` hiện có đã đủ.

## 58.1 Role cố định

V1 chỉ có ba role built-in: `ADMIN`, `MANAGER`, `STAFF`. KHÔNG tạo/xóa/đổi tên role, KHÔNG custom
role, KHÔNG tạo/xóa/đổi tên permission, KHÔNG role hierarchy hay permission inheritance. Quan hệ
`AppUser ↔ Role` (N:M) giữ nguyên; User Management (mục 57) vẫn quản lý đúng một role/user.

## 58.2 Ma trận permission có thể chỉnh

`GET|POST /roles-permissions` (một trang ma trận duy nhất, sidebar ADMINISTRATION → Roles &
Permissions), bảo vệ bằng `PERM_MANAGE_USER` (chỉ ADMIN trong V1, không có permission mới). Các
permission hiển thị, theo nhóm: Dashboard (`VIEW_REPORT`); Reservations (`VIEW_BOOKING`,
`MANAGE_BOOKING`, `CHECK_IN`, `CHECK_OUT`, `CHANGE_ROOM`, `EXTEND_STAY`); Guests (`MANAGE_GUEST`); Rooms
(`MANAGE_ROOM`); Housekeeping (`MANAGE_HOUSEKEEPING`); Finance (`MANAGE_PAYMENT`, `MANAGE_EXPENSE`, `MANAGE_ADDITIONAL_REVENUE`);
Administration (`MANAGE_STAFF`, `MANAGE_ATTENDANCE`, `MANAGE_USER`). Trạng thái được đọc từ
database, không hard-code. `DELETE_RESERVATION` là permission "dormant" (đã seed, không role nào có,
không code nào dùng): KHÔNG hiển thị, KHÔNG cấp, KHÔNG xóa khỏi database; việc lưu ma trận không
bao giờ cấp nó và giữ nguyên mọi permission không hiển thị mà role đang có.

## 58.3 Bất biến MANAGE_USER

`MANAGE_USER` luôn BẬT và KHÓA cho ADMIN, luôn TẮT và KHÓA cho MANAGER và STAFF. Được enforce ở
service (không chỉ HTML): request thiếu `MANAGE_USER` của ADMIN vẫn giữ nguyên; request cấp
`MANAGE_USER` cho MANAGER/STAFF bị từ chối. ADMIN có thể tự sửa các permission cấu hình được của
role ADMIN (không bị khôi phục âm thầm); chỉ `MANAGE_USER` có bất biến đặc biệt.

## 58.4 Lưu, khóa và audit

Lưu trong một transaction: validate toàn bộ submission (đủ đúng ba role, permission thuộc catalogue
hiển thị) trước khi thay đổi; khóa dòng ba role (`SELECT … FOR UPDATE`,
`RoleRepository.lockBuiltInRoleIds`) rồi mới đọc và cập nhật để tuần tự hóa các lần lưu đồng thời;
chỉ cập nhật role có permission thực sự thay đổi. Mỗi role thay đổi ghi một `AuditLog`:
`action=ROLE_PERMISSION_CHANGE`, `entity_type=ROLE`, `entity_id`=Role.id, `old_value`/`new_value` là
danh sách mã permission sắp xếp, ngăn cách bằng dấu phẩy; không audit role không đổi; không có Audit
Log UI.

## 58.5 Hiệu lực ngay lập tức

Thay đổi permission có hiệu lực từ request xác thực kế tiếp cho cả session MVC lẫn JWT nhờ cơ chế
dựng lại authorities từ database (mục 57.5); không cần đăng nhập lại, không cache, không blacklist,
không buộc đăng xuất.

# 59. Data Table Standardization (Task 26)

## 59.1 Phạm vi

Chuẩn hóa hành vi bảng dữ liệu CHỨC NĂNG cho bảy màn hình chính: Reservations, Guests, Rooms,
Check-in Existing, Check-out, Expenses, Additional Revenue. KHÔNG áp dụng cho Staff, Users, các
danh mục (Expense/Additional Revenue Categories), Daily Work Record, Staff Detail, Roles &
Permissions, bảng nhúng (Dashboard, Reservation Detail, Folio, Check-in Review). Thiết kế giao diện
(sticky header, responsive, visual) thuộc Task 33; Task 26 ưu tiên đúng chức năng hơn hình thức.

## 59.2 Phân trang

Phân trang phía server (Spring `Page`), tham số `page` bắt đầu từ 0 trong URL (UI hiển thị từ 1).
Kích thước cố định: Reservations/Guests/Rooms/Check-in/Check-out = 10; Expenses/Additional
Revenue = 20; không có điều khiển chọn page size. Lọc và sắp xếp luôn được thực hiện ở database
TRƯỚC khi phân trang. `page` âm hoặc không phải số được đưa về 0. Nếu `page` vượt quá trang cuối
trong khi kết quả KHÔNG rỗng, server chuyển hướng (302) tới trang cuối hợp lệ, giữ nguyên bộ lọc và
sắp xếp; kết quả thật sự rỗng không chuyển hướng và hiển thị empty state. Tóm tắt kết quả:
"Showing a–b of n" (hoặc "0 results") từ dữ liệu Page đã có, không thêm truy vấn.

## 59.3 Sắp xếp

Tham số `sort=<khóa logic>&dir=asc|desc`. Giá trị từ trình duyệt KHÔNG BAO GIỜ được chuyển thẳng
vào Spring Data/JPA: mỗi màn hình có whitelist (`TableSorts`) ánh xạ khóa công khai sang thuộc tính
an toàn; `sort`/`dir` không hợp lệ quay về thứ tự mặc định của màn hình. Khóa cho phép:
Reservations (`reservationNumber`, `checkInDate`, `checkOutDate`, `status`); Guests (`guestCode`,
`firstName`, `lastName`); Rooms (`roomNumber`, `roomType` theo mã loại phòng, `floor`, `status`);
Check-in Existing (`reservationNumber`, `checkInDate`); Check-out (`reservationNumber`,
`checkOutDate`); Expenses và Additional Revenue (`date`, `amount`, `status`). Thứ tự mặc định giữ
nguyên: Reservations/Check-in/Check-out theo ngày check-in giảm dần rồi số reservation; Guests theo
mã guest tăng dần; Rooms theo số phòng tăng dần; Expenses/Additional Revenue theo ngày giảm dần rồi
id giảm dần. Khi sắp xếp theo người dùng luôn có thứ tự phụ xác định (deterministic).

## 59.4 Trạng thái URL

Bộ lọc, `sort`, `dir`, `page` được giữ trong URL và mã hóa an toàn: đổi trang giữ bộ lọc và sắp xếp;
đổi sắp xếp giữ bộ lọc và đặt lại `page`=0; gửi bộ lọc giữ sắp xếp hiện tại (trường ẩn) và đặt lại
`page`=0; "Reset/Clear" quay về route gốc (sắp xếp và trang mặc định). Tiêu đề cột sắp xếp được là
liên kết (ASC → DESC → ASC), không dùng JavaScript.

## 59.5 Check-out: phòng hiện tại

Bộ lọc Room của Check-out là phòng HIỆN TẠI của Stay, không phải `ReservationRoom` đặt ban đầu: là
predicate `EXISTS` ở database trên `stay_room_assignment` đang mở (`assigned_to IS NULL`), so khớp
số phòng không phân biệt hoa/thường theo dạng chứa. Vì lọc trước khi phân trang nên `totalElements`
và số trang chính xác, đúng sau Room Change và với Stay nhiều phòng. Không cần migration/index mới.

## 59.6 Khoảng ngày

Expenses và Additional Revenue: nếu cả `fromDate` và `toDate` có giá trị thì phải `from <= to`; nếu
không, hiển thị thông báo thân thiện ("From Date must not be after To Date."), giữ nguyên giá trị đã
nhập và không thực hiện tìm kiếm (hành vi này đã có từ trước; Task 26 xác nhận và bổ sung kiểm thử).

# 60. EN / VI Internationalization Foundation (Task 27A)

## 60.1 Ngôn ngữ và cơ chế

PMS UI hỗ trợ đúng hai ngôn ngữ: Tiếng Việt (`vi`) và Tiếng Anh (`en`). Ngôn ngữ chạy thực tế mặc định
là **Tiếng Việt**; tiếng Anh là ngôn ngữ dự phòng (khóa thiếu trong `messages_vi.properties` dùng bản
`messages.properties`). `Accept-Language` của trình duyệt KHÔNG được dùng để chọn ngôn ngữ ban đầu. Không
lưu ngôn ngữ trong `AppUser`, không có thay đổi schema.

- **Cookie**: `pms-lang` (`vi`/`en`), `Path=/`, `HttpOnly`, `SameSite=Lax`, tuổi thọ 365 ngày; cờ `Secure`
  cấu hình bằng `hotel.i18n.cookie-secure` (bật khi chạy HTTPS). Cookie không chứa dữ liệu cá nhân và không
  gắn với session nên vẫn còn sau logout/login và khi khởi động lại trình duyệt.
- **Chuyển ngôn ngữ**: `?lang=vi` hoặc `?lang=en` (`LocaleChangeInterceptor`); giá trị khác hoặc cookie bị
  sửa được bỏ qua và quay về mặc định (không tạo locale tùy ý, không lỗi). Bộ chuyển `VI | EN` có ở trang
  login và sidebar (giữ nguyên URL và query hiện tại).
  Với trang được render sau một POST (ví dụ form lỗi validation), liên kết ngôn ngữ KHÔNG trỏ tới URL của POST:
  controller có thể đặt `languageSwitchPath` là route GET tương ứng (Staff: `/staff/new`, `/staff/{id}/edit`);
  nếu không, dùng đích an toàn `/reservations`. Không replay POST và không đưa dữ liệu form vào URL.
- `<html lang>` phản ánh ngôn ngữ đang dùng.
- **Môi trường test**: mặc định `en` (`src/test/resources/application.properties`,
  `hotel.i18n.default-locale=en`) để giữ các test hiện có; các test i18n chuyên biệt ghim `vi` rõ ràng.
- **/api/****: luôn tiếng Anh, không phụ thuộc cookie UI và `?lang=` không có tác dụng.

## 60.2 Message bundle và quy ước khóa

`messages.properties` (English, nền/fallback) và `messages_vi.properties` (UTF-8). Khóa ngữ nghĩa, phân
tách bằng dấu chấm, không dùng câu tiếng Anh làm khóa và không đặt tên theo hình thức hiển thị:
`navigation.*`, `common.*`, `table.*`, `auth.*`, `dashboard.*`, `reservation.*`, `guest.*`, `room.*`,
`checkin.*`, `checkout.*`, `payment.*`, `expense.*`, `revenue.*`, `staff.*`, `user.*`, `role.*`,
`validation.*`, `error.*`, `enum.*`, `js.*`. Thông điệp có giá trị động dùng tham số MessageFormat
(`{0}`), không nối chuỗi; nhân đôi dấu nháy đơn trong thông điệp có tham số. Test `MessageBundlesTest`
bảo đảm hai bundle cùng tập khóa và cùng placeholder.

**Quy tắc cho lập trình viên: văn bản giao diện MỚI KHÔNG ĐƯỢC hard-code. Màn hình mới (kể cả Task 33) phải
dùng khóa message ngay từ khi tạo.**

## 60.3 Enum / trạng thái

Giá trị nội bộ (DB, form, URL, enum Java, class CSS) KHÔNG đổi; chỉ nhãn hiển thị được dịch, khóa
`enum.<semanticType>.<VALUE>` (ví dụ `enum.reservationStatus.CHECKED_IN`). Template dùng fragment
`layout/enum :: label(type, value)` / `badge(...)`; mã Java (báo cáo/PDF/Excel về sau) dùng
`UiMessages.enumLabel(locale, type, value)`. Giá trị chưa có nhãn hiển thị nguyên giá trị.

## 60.4 Lỗi nghiệp vụ và validation

Service/domain không biết locale: ném `LocalizedResponseStatusException(status, messageKey, defaultEnglish,
args...)` (kế thừa `ResponseStatusException`, giữ nguyên HTTP status và lý do tiếng Anh). Lớp trình bày dùng
`UiMessages.error(exception)` (thay `safeMessage` lặp lại trong controller) để dịch theo locale hiện tại;
exception thường giữ nguyên hành vi cũ. Đã di chuyển mẫu cho Staff (`staff.error.*`, `staff.flash.*`);
các nơi còn lại sẽ di chuyển dần. Bean Validation dùng khóa `{validation.*}` (ví dụ
`@NotBlank(message = "{validation.staff.firstName.required}")`, `@Size(max = 100, message =
"{validation.size.max}")`) được Spring resolve qua MessageSource theo locale của request; `/api/**` luôn
nhận tiếng Anh vì locale của API cố định.

## 60.5 JavaScript

Chuỗi dịch cho JS được server render vào `<head>` dưới dạng `<meta name="pms-i18n:KEY" content="...">`
(`layout/base`) và đọc bằng `PmsI18n.t(key, fallbackEnglish)` (`static/js/common/i18n.js`); không cần
framework. `confirmation.js` giữ nguyên: nội dung `data-confirm-*` do server render từ khóa message.

## 60.6 Định dạng và dữ liệu

Ngày hiển thị `dd/MM/yyyy` và `dd/MM/yyyy HH:mm` cho cả hai ngôn ngữ; định dạng HTML/form vẫn `yyyy-MM-dd`.
Tiền tệ giữ cách nhóm số hiện tại (ví dụ `1,500,000 VND`). Dữ liệu nghiệp vụ trong database (ghi chú, tên
danh mục/loại phòng người dùng nhập, quốc tịch) KHÔNG bị dịch hay sửa; chỉ nhãn hệ thống (enum, vai trò,
quyền) mới có nhãn dịch. Trang lỗi (403/404/500) chỉ có sẵn khóa `error.*`; thiết kế trang thuộc Task 33.
Báo cáo/PDF/Excel (Task 28–32) dùng lại `MessageSource`, chưa triển khai ở đây.

## 60.7 Thuật ngữ tiếng Việt ban đầu

Reservation = Đặt phòng; Guest = Khách; Room = Phòng; Check-in = Nhận phòng; Check-out = Trả phòng;
Payment = Thanh toán; Expense = Chi phí; Additional Revenue = Doanh thu bổ sung; Staff = Nhân viên.
CẦN XÁC NHẬN theo ngữ cảnh nghiệp vụ trước khi dịch: **Folio**, **Stay**, Working Time / Work Check-in,
Room Change, OTA.

# 61. Reports — Overview và quy ước báo cáo (Task 28)

## 61.1 Phạm vi các task

```text
Task 28 : Reports Overview (điểm vào khu vực báo cáo) — CHỈ điều hướng, không tính toán
Task 29 : Monthly Financial Report
Task 30 : Monthly Occupancy Report
          (trước khi hoàn tất Task 30 phải audit/giải quyết Backend Gap A và B, mục 61.5)
Task 31 : PDF export
Task 32 : Excel export
```

`GET /reports` (`PERM_VIEW_REPORT`, cùng quy ước với Dashboard; không có permission mới; ADMIN/MANAGER có,
STAFF không) hiển thị trang Overview với hai thẻ thông tin "Monthly Financial Report" và "Monthly Occupancy
Report" ở trạng thái "Sắp có" (không có liên kết tới route chưa tồn tại). Sidebar có mục **Reports** (hiển thị
khi `canViewReport`, đặt sau Dashboard). Trang báo cáo dùng class `page-reports` trên `app-shell` để mục
Reports luôn ở trạng thái active; các route con sau này (`/reports/...`) dùng lại class này. Task 28 KHÔNG có
truy vấn, service tính toán, DTO số liệu, bộ chọn tháng, biểu đồ hay export. Văn bản giao diện dùng message key
`report.*` / `navigation.reports` (EN/VI).

## 61.2 Quy ước Monthly Financial Report (áp dụng cho Task 29–32)

- **Tiền tệ báo cáo/base: VND.**
- **Room Revenue**: dựa trên pricing snapshot đã đặt của `ReservationRoom`, ghi nhận theo từng ĐÊM PHÒNG đã
  đặt theo lịch. Một đêm thuộc về `LocalDate` mà đêm đó BẮT ĐẦU. Ví dụ check-in 30/09, check-out 03/10 → các
  đêm 30/09, 01/10, 02/10: tháng 9 nhận 1 đêm, tháng 10 nhận 2 đêm. Đây là doanh thu booked/economic theo mô
  hình V1 hiện tại và KHÔNG tự thay đổi khi thực tế khác đi (check-out sớm, sự kiện vận hành trễ, đổi phòng)
  trừ khi domain điều chỉnh pricing đã đặt một cách tường minh.
- **Room Revenue không phải VND**: KHÔNG được âm thầm quy đổi bằng exchange rate của Payment (đó là thông tin
  thanh toán, không phải FX snapshot ghi nhận doanh thu). Cho tới khi có hỗ trợ FX ghi nhận doanh thu, doanh thu
  phòng không phải VND phải được gắn cờ/loại trừ tường minh khỏi tổng VND (cách hiển thị cảnh báo do Task 29 quyết
  định).
- **Additional Revenue**: ghi nhận khi `status = RECORDED`, theo `revenueDate`; `VOIDED` bị loại.
- **Expense**: ghi nhận khi `status = POSTED`, theo `expenseDate`; `APPROVED` chưa `POSTED` bị loại.
- **Payment** là thông tin thanh toán/cash-flow; `Payment.amount` và `Payment.appliedAmount` KHÔNG là nguồn
  doanh thu ghi nhận.
- **Room vs các charge khác**: Room Revenue là giá phòng đã đặt / charge kinh tế loại ROOM; các loại charge khác
  KHÔNG được âm thầm đưa vào Room Revenue. Trước khi hiện thực tổng Task 29 phải kiểm tra không trùng lặp giữa
  charge không phải ROOM và `AdditionalRevenue`.
- **Total Revenue** = Room Revenue + Additional Revenue. **Net Profit** = Total Revenue − Recognized Expense.
- **Profit Margin** = Net Profit / Total Revenue × 100 khi Total Revenue > 0; khi Total Revenue = 0 hiển thị
  N/A (không báo cáo 0% gây hiểu nhầm).

## 61.3 Quy ước Monthly Occupancy Report (Task 30)

- Dùng occupancy THỰC TẾ vật lý; nguồn lịch sử có thẩm quyền: `StayRoomAssignment`.
- Một đêm thuộc `LocalDate` mà đêm đó bắt đầu (check-in 30/09 14:00, check-out 03/10 11:00 → 30/09, 01/10, 02/10:
  tháng 9 = 1, tháng 10 = 2).
- Lưu trú cùng ngày (check-in và check-out cùng `LocalDate`) đóng góp 0 occupied room night.
- Đổi phòng KHÔNG làm tăng occupied room nights ở cấp khách sạn: một lineage stay/reservation chiếm một phòng
  khách sạn trong một đêm chỉ tính một occupied room night, kể cả khi assignment đổi trong cùng ngày. Việc gán
  phòng lịch sử vẫn có thể dùng sau này cho room-type performance nhưng không được đếm trùng occupancy.
- **Available Room Nights**: định nghĩa mong muốn là số đêm phòng CÓ THỂ BÁN theo lịch sử. `Room.status` và
  `Room.active` chỉ là trạng thái HIỆN TẠI, không đủ để dựng lại tồn kho bán được trong quá khứ; không được coi
  trạng thái hiện tại là availability lịch sử.
- **Room Type Performance** không được chỉ dựa vào `Room.roomType` hiện tại (có thể sửa được).

## 61.4 Kiến trúc dịch vụ báo cáo (dự kiến, chưa hiện thực)

```text
domain / repositories
        ↓
reporting calculation service
        ↓
locale-free immutable report result
   ├──────────┬──────────┐
   ↓          ↓          ↓
  Web        PDF       Excel
   ↓          ↓          ↓
presentation-layer localization
```

PDF/Excel dùng cùng result/model với báo cáo web, không tự tính lại số liệu. Dữ liệu tính toán không chứa chuỗi
hiển thị đã dịch. Task 29 thiết lập result model đầu tiên. Task 28 không chọn thư viện PDF/Excel.

## 61.5 Backend Gap (ghi nhận, chưa giải quyết)

- **Gap A — Historical room sellability/availability**: cần biết các khoảng thời gian như Room 101
  `OUT_OF_ORDER` từ 10/09 → 15/09 ngay cả sau khi phòng đã trở lại available. Mô hình hiện tại chỉ giữ trạng
  thái hiện tại.
- **Gap B — Historical RoomType snapshot**: báo cáo lịch sử không được thay đổi khi một Room sau này bị sửa sang
  RoomType khác; `ReservationRoom`/`StayRoomAssignment` hiện không lưu snapshot room type.

Hai gap này sẽ được audit riêng trước Task 30; Task 28 không thiết kế hay tạo thay đổi schema. Task 30A giải quyết
chúng bằng `RoomInventoryPeriod` (mục 61.7).

## 61.6 Monthly Financial Report — quyết định Task 29

`GET /reports/monthly-financial?month=yyyy-MM` (`PERM_VIEW_REPORT`); không có `month` thì dùng
`YearMonth.now(clock)` với `Clock` của khách sạn; `month` không hợp lệ hiển thị thông báo thân thiện, không
lỗi 500. Kỳ báo cáo `[month.atDay(1), month.plusMonths(1).atDay(1))` dùng `LocalDate` (không chuyển đổi Instant).
Thẻ Financial Report ở trang Overview liên kết tới báo cáo này; Occupancy Report vẫn "Sắp có".

- **Trạng thái đủ điều kiện cho Room Revenue**: chỉ Reservation `CHECKED_IN` và `CHECKED_OUT`. `DRAFT`,
  `CONFIRMED`, `CANCELLED`, `NO_SHOW` bị loại. Doanh thu của `CONFIRMED` là doanh thu tương lai/on-the-books,
  KHÔNG phải doanh thu tài chính đã ghi nhận của PMS; Booked Revenue không thuộc Task 29.
- **Room Revenue**: với mỗi `ReservationRoom` có khoảng đặt giao với tháng (`checkInDate < nextMonthStart` và
  `checkOutDate > monthStart`) và Reservation ở trạng thái đủ điều kiện, `overlapStart = max(checkInDate,
  monthStart)`, `overlapEnd = min(checkOutDate, nextMonthStart)`, doanh thu tháng = `nightlyRate ×
  DAYS(overlapStart, overlapEnd)`. Không dùng `Charge.chargedAt` hay `Payment`.
- **Toàn vẹn snapshot**: `nightlyRate × số đêm đặt` phải bằng `totalAmount`. Nếu lệch, báo cáo thất bại một cách
  xác định (`ReportDataIntegrityException`, người dùng thấy thông báo dịch được và HTTP 500; chi tiết chỉ ghi
  log) — KHÔNG sửa dữ liệu, KHÔNG phân bổ tỉ lệ `totalAmount`, KHÔNG làm tròn tự đặt, KHÔNG bỏ qua dòng.
- **Room Revenue không phải VND**: không quy đổi, không dùng exchange rate của Payment; loại khỏi
  `roomRevenue`, `totalRevenue`, `netProfit`; kết quả có `nonVndWarning` gồm số Reservation phân biệt, số dòng
  `ReservationRoom`, và danh sách mã tiền tệ đã sắp xếp/phân biệt (`null` nếu không có).
- **Additional Revenue**: `SUM(amount)` với `status = RECORDED` và `revenueDate` trong kỳ. **Expense**:
  `SUM(amount)` với `status = POSTED` và `expenseDate` trong kỳ. Không có dòng → 0.
- **Công thức**: `totalRevenue = roomRevenue + additionalRevenue`; `netProfit = totalRevenue − expense`;
  `profitMargin = netProfit / totalRevenue × 100` khi `totalRevenue > 0`, làm tròn 2 chữ số thập phân
  `HALF_UP` (chỉ cho tỷ lệ phần trăm; số tiền không bị làm tròn); `totalRevenue = 0` → `null` (hiển thị N/A).
- **Giới hạn V1 — charge không phải ROOM**: các `Charge` không phải ROOM (breakfast, laundry, minibar, service,
  extra bed, other) KHÔNG nằm trong tổng của Task 29. Đây là giới hạn báo cáo đã biết, không phải khẳng định
  các sự kiện đó không phải doanh thu: staff có thể ghi bán hàng phụ qua Charge trong Folio và độc lập qua
  `AdditionalRevenue`, mô hình hiện tại không có định danh sự kiện kinh tế chung hay khử trùng tự động, nên gộp
  cả hai có thể đếm trùng doanh thu do nhập tay trùng. Task 29 không thay đổi schema/liên kết.
- **Kết quả**: `MonthlyFinancialReport` / `NonVndRoomRevenueWarning` là record bất biến, không chứa chuỗi hiển thị
  hay locale; PDF/Excel (Task 31–32) dùng lại đúng kết quả này. Văn bản UI dùng `report.financial.*`.

## 61.7 Reporting Inventory Foundation — quyết định Task 30A

Để giải quyết Gap A và Gap B (mục 61.5), hệ thống lưu lịch sử tồn kho hiệu lực theo thời gian của từng Room
trong `room_inventory_period` (entity `RoomInventoryPeriod`). Task 30A CHỈ xây nền tảng dữ liệu; Monthly
Occupancy Report thuộc Task 30B.

- **Nội dung một period**: `room`, `roomType` (RoomType hiệu lực), `unavailableReason` (null = sellable;
  `MAINTENANCE` hoặc `OUT_OF_ORDER` = không sellable), `reason` (V1 Hardening: free-text mô tả lý do không
  sellable, tối đa 2000 ký tự; luôn null khi `unavailableReason` null — một period sellable không bao giờ mang
  `reason`; period `BOOTSTRAP` hợp lệ không có `reason`), `origin` (`BOOTSTRAP` | `RECORDED`), `effectiveFrom`,
  `effectiveTo` (null = period đang mở). Không có cột `sellable`; sellable suy ra từ `unavailableReason`. `reason`
  là metadata mô tả, không ảnh hưởng công thức booking hay occupancy nào (mục 61.8).
- **Sellable inventory**: `AVAILABLE`, `OCCUPIED`, `DIRTY`, `CLEANING` thuộc tồn kho bán được; `MAINTENANCE` và
  `OUT_OF_ORDER` bị loại khỏi Sellable Room Nights.
- **Lưu Instant thật**: `effectiveFrom/effectiveTo` là `TIMESTAMPTZ`, ghi đúng thời điểm chuyển đổi theo `Clock`
  của khách sạn; KHÔNG cắt về `LocalDate` khi lưu.
- **Quy tắc quy đêm (end-of-date)**: một period phủ đêm `d` khi
  `localDate(effectiveFrom) <= d < localDate(effectiveTo)` theo `Asia/Ho_Chi_Minh`; period đang mở dùng điểm cuối
  (exclusive) do báo cáo cung cấp. Trạng thái/loại phòng CUỐI CÙNG của một ngày lịch sở hữu đêm đó (OUT_OF_ORDER
  từ 10/09 15:00 → đêm 10/09 không sellable; trở lại AVAILABLE 15/09 10:00 → đêm 15/09 sellable; đổi
  DOUBLE→FAMILY 10/10 15:00 → đêm 10/10 thuộc FAMILY; Room tạo 16/09 15:00 → đêm 16/09 sellable). Quy tắc này
  cùng công thức với `StayRoomAssignment`, nên đêm occupied luôn nằm trong đêm sellable.
- **Chia period**: một period là khoảng tối đa mà `roomType` và `unavailableReason` không đổi. Chỉ chia khi một
  trong hai giá trị đổi: tạo Room (mở period đầu, `RECORDED`), chuyển `AVAILABLE↔MAINTENANCE`,
  `AVAILABLE↔OUT_OF_ORDER`, và đổi RoomType (giữ nguyên `unavailableReason`). Chuyển trạng thái trong tồn kho
  sellable (`OCCUPIED`, `DIRTY`, `CLEANING`, room change release…), sửa số phòng/tầng KHÔNG tạo period. Đóng
  period cũ và mở period mới dùng đúng MỘT Instant, cùng transaction với thay đổi Room; lỗi lịch sử làm rollback
  thay đổi Room và ngược lại. Update Room lấy khóa ghi Room như các chuyển trạng thái.
- **Bất biến**: đúng một period mở cho mỗi Room; `effectiveTo` null hoặc `> effectiveFrom`; không trùng
  `(room_id, effective_from)`; `unavailableReason`/`origin` thuộc tập cho phép. Period đã đóng không bao giờ bị
  sửa qua ứng dụng; thay đổi duy nhất là đóng period đang mở. Không dùng exclusion constraint/`btree_gist`;
  chồng lấn/liên tục được kiểm tra thêm ở service và kiểm tra toàn vẹn của báo cáo (Task 30B). Room thiếu
  period mở khiến thao tác thất bại rõ ràng, không tự sửa.
- **Bootstrap khi cài đặt**: migration tạo một period mở `BOOTSTRAP` cho mỗi Room hiện có, dùng RoomType hiện tại
  và `unavailableReason` suy từ trạng thái hiện tại (`MAINTENANCE`/`OUT_OF_ORDER`, còn lại null), với
  `effectiveFrom` = thời điểm cài đặt (không dùng `Room.createdAt`, không lùi ngày). Bootstrap chỉ khẳng định
  trạng thái tồn kho TẠI thời điểm cài đặt, không nói gì về quá khứ. Cột audit user để null vì là bản ghi hệ thống.
  Database mới (không có Room khi migrate) không có dòng `BOOTSTRAP`; Room tạo sau đó là `RECORDED`.
- **Ranh giới lịch sử (full calendar month)**: `historyStart` = ngày (giờ khách sạn) của `effectiveFrom` `BOOTSTRAP`
  sớm nhất. `firstFullySupportedMonth` = tháng của `historyStart` nếu đó là ngày 1, ngược lại là tháng kế tiếp
  (20/09 → tháng 10; 01/09 → tháng 9). Không có dòng `BOOTSTRAP` thì không tạo ranh giới giả; Task 30B xác định
  hỗ trợ từ lịch sử `RECORDED` thực tế.
- **`Room.active`** chưa nằm trong mô hình (chưa có nghiệp vụ deactivate/reactivate); sẽ cần lịch sử riêng khi có.
- **Dữ liệu demo (chỉ profile `dev`)**: `DemoDataSeeder` bổ sung `StayRoomAssignment` cho Stay demo CHECKED_IN
  (mở) / CHECKED_OUT (đóng tại `actualCheckOutAt`, lineage = `original_reservation_room_id`) và lịch sử tồn kho
  `RECORDED` tổng hợp phủ cửa sổ demo, tách biệt với `BOOTSTRAP` production. Có bước backfill idempotent cho DB
  demo đã tồn tại, chỉ chạm Room/Stay mang dấu demo. Không dùng dữ liệu demo làm bằng chứng lịch sử thật.
- **Không backfill production trước V22**: migration KHÔNG suy diễn hay tạo `StayRoomAssignment` lịch sử. Nếu
  dữ liệu production cũ cần dựng lại, đó là một thao tác riêng, được duyệt riêng.

## 61.8 Monthly Occupancy Report — quyết định Task 30B

`GET /reports/monthly-occupancy?month=yyyy-MM` (`PERM_VIEW_REPORT`, không có permission mới); không có `month` thì
dùng `YearMonth.now(clock)` với `Clock` khách sạn (`Asia/Ho_Chi_Minh`); `month` sai định dạng hiển thị thông báo
thân thiện. Thẻ Occupancy ở trang Overview liên kết tới báo cáo này; mục Reports trên sidebar vẫn là một mục duy
nhất và luôn active.

- **Kỳ báo cáo**: tháng đã hoàn tất dùng `[monthStart, nextMonthStart)`. Tháng hiện tại chỉ tính các đêm ĐÃ HOÀN
  TẤT: `reportEnd` = ngày khách sạn hôm nay (exclusive), ví dụ hôm nay 20/09 → các đêm 01/09..19/09, không gồm
  đêm nay; ngày đầu tháng hiện tại có 0 đêm hoàn tất và cho kết quả 0/0 (tỷ lệ N/A) với `reportedThrough` rỗng.
  Tháng tương lai bị TỪ CHỐI (không trả về 0 gây hiểu nhầm).
- **Hỗ trợ lịch sử**: nếu có dòng `BOOTSTRAP` thì tháng trước `firstFullySupportedMonth` (mục 61.7) bị từ chối
  bằng thông báo "lịch sử chưa có"; không tính tháng dở dang. Không có dòng `BOOTSTRAP` thì không tạo ranh giới
  giả; thay vào đó mọi Room góp đêm cho báo cáo phải có `RoomInventoryPeriod` phủ đêm đó, nếu không là lỗi toàn
  vẹn dữ liệu (không đoán theo trạng thái hiện tại).
- **Occupied Room Nights**: nguồn duy nhất là `StayRoomAssignment` (không dùng ReservationRoom, trạng thái
  Reservation, `Room.status`, `Room.roomType` hiện tại). Mỗi assignment đóng góp các đêm `d` với
  `localDate(assignedFrom) <= d < localDate(assignedTo)` (Asia/Ho_Chi_Minh), cắt theo `[reportStart, reportEnd)`;
  assignment đang mở kết thúc tại `reportEnd` (không đếm đêm tương lai). Lineage là
  `original_reservation_room_id`; một occupied room-night là `(lineage, hotelNight)`. Đổi phòng nằm trong cùng
  một lineage nên không làm tăng số đêm; đặt nhiều phòng có nhiều lineage nên mỗi phòng góp một đêm (không gộp
  theo Stay + ngày).
- **Available Room Nights** (nhãn UI đã duyệt) nghĩa là SELLABLE room nights: các đêm có `RoomInventoryPeriod`
  phủ với `unavailableReason` null (mục 61.7, quy tắc end-of-date). `AVAILABLE`/`OCCUPIED`/`DIRTY`/`CLEANING`
  thuộc mẫu số; `MAINTENANCE`/`OUT_OF_ORDER` bị loại. Không phải "phòng có `Room.status = AVAILABLE` hiện tại".
- **RoomType lịch sử**: loại phòng của cả đêm sellable lẫn đêm occupied lấy từ `RoomInventoryPeriod` phủ
  `(phòng vật lý, đêm)`, không phải `Room.roomType` hiện tại, nên báo cáo cũ không đổi khi Room sau này bị sửa loại.
- **Room Type Performance (V1)**: mỗi RoomType xuất hiện trong kỳ có `occupiedRoomNights`, `sellableRoomNights`,
  `occupancyRate`; sắp xếp theo `RoomType.code`. KHÔNG có doanh thu theo RoomType, nguồn đặt phòng hay biểu đồ.
- **Công thức**: `occupancyRate = occupied / sellable × 100`, 2 chữ số thập phân `HALF_UP`, `null` (hiển thị N/A)
  khi `sellable = 0`. Tỷ lệ toàn khách sạn tính từ TỔNG số đêm, không lấy trung bình tỷ lệ theo RoomType.
- **Lỗi toàn vẹn (báo cáo thất bại, không sửa, không khử trùng lặp, UI chỉ hiện thông báo chung, chi tiết ghi
  log)**: trùng `(lineage, đêm)`; một phòng bị chiếm hai lần trong một đêm; assignment có `assignedTo` không sau
  `assignedFrom`; booked room của một Stay trong kỳ không có assignment nào; đêm occupied không có period tồn
  kho phủ hoặc rơi vào period không sellable; hai period của một Room chồng lấn hoặc hở nhau; chuỗi period của
  một Room kết thúc đóng trước cuối kỳ.
- **Kết quả**: `MonthlyOccupancyReport` / `RoomTypeOccupancy` là record bất biến, không chứa chuỗi hiển thị hay
  locale. Văn bản UI dùng khóa `report.occupancy.*`. Không có migration mới (dùng V28).

## 61.9 Monthly Hotel Performance PDF — quyết định Task 31

`GET /reports/monthly-performance.pdf?month=yyyy-MM` (`PERM_VIEW_REPORT`, không có permission mới) trả về báo
cáo PDF MỘT trang A4. Nguồn tham chiếu hình ảnh (visual source-of-truth):
`docs/report-templates/monthly_hotel_performance_report_mockup_v3.pdf`; nguồn nghiệp vụ vẫn là Task 29/30 (V3 chỉ
quyết định bố cục; các giá trị/đơn vị trong V3, ví dụ JPY, chỉ là minh họa).

- **Tháng**: `month` mặc định `YearMonth.now(clock)` (Asia/Ho_Chi_Minh). Từ chối (KHÔNG tạo PDF một phần): tháng
  sai định dạng, tháng tương lai, tháng trước `firstFullySupportedMonth` của occupancy (mục 61.7/61.8). Khi bị từ
  chối, người dùng được chuyển về `/reports` kèm thông báo đã dịch (flash); lỗi toàn vẹn dữ liệu chỉ ghi log. Hợp
  đồng của trang web Task 29 và Task 30 không đổi.
- **Dataset dùng chung** `MonthlyHotelPerformanceReport` (không chứa chuỗi dịch, tọa độ hay cắt bớt dữ liệu; Task
  32 Excel dùng lại): gồm nguyên vẹn `MonthlyFinancialReport` và `MonthlyOccupancyReport` (không tính lại), ngày
  tạo theo `Clock` khách sạn, so sánh tháng trước, xu hướng doanh thu, nguồn đặt phòng và Additional Revenue theo
  category. Renderer PDF chỉ trình bày, không truy vấn và không tính công thức nghiệp vụ.
- **KPI**: Total Revenue / Total Expenses / Net Profit lấy trực tiếp từ Task 29 (VND); Occupancy lấy từ Task 30
  (đêm phòng thực tế đã hoàn tất / đêm phòng sellable). Tháng hiện tại: tài chính theo Task 29 giữ nguyên, occupancy
  theo Task 30 (các đêm đã hoàn tất, PDF hiển thị "Data through" khi có cắt ngày) và có ngày tạo báo cáo.
- **So sánh với tháng trước**: Revenue/Expenses/Net Profit = `(current − previous) / abs(previous) × 100` (tính
  scale 4, hiển thị 1 chữ số thập phân HALF_UP), N/A khi `previous = 0`. Occupancy = chênh lệch điểm phần trăm
  `current − previous` (hiển thị `+5.2 pp`), N/A khi một trong hai rate không có. Tháng trước KHÔNG bắt buộc có lịch
  sử occupancy: nếu thiếu thì chỉ so sánh occupancy là N/A, PDF vẫn được tạo.
- **Revenue Trend**: sáu tháng dương lịch kết thúc ở tháng đã chọn (theo thứ tự thời gian), một chuỗi Total Revenue
  = Room Revenue + Additional Revenue theo đúng Task 29 cho từng tháng.
- **Reservation Source**: định nghĩa Dashboard đã duyệt: `Reservation.checkInDate` trong tháng, MỌI trạng thái, nhóm
  theo `BookingSource` (DIRECT, AGODA, BOOKING_COM, AIRBNB, điền 0), phần trăm theo tổng (0 khi tổng = 0).
- **Occupancy Summary**: tỷ lệ occupancy, số đêm phòng đã bán (occupied), số đêm phòng khả dụng (sellable) từ Task 30
  và tổng số Reservation của tập Reservation Source. Không có biểu đồ theo ngày.
- **Room Type Performance**: Room Type / Sold / Available / Occupancy trực tiếp từ Task 30 (RoomType lịch sử);
  không có doanh thu theo RoomType. **Financial Summary**: Room Revenue, Additional Revenue, Total Revenue,
  Operating Expenses, Net Operating Profit từ Task 29 (không có Profit Margin).
- **Top Additional Revenue**: Additional Revenue `RECORDED` theo `revenueDate` trong tháng, nhóm theo category,
  sắp xếp `totalAmount` giảm dần rồi `category.code` tăng dần, phần trăm theo Additional Revenue của tháng. PDF
  hiển thị 4 category đầu + "Others" (tổng phần còn lại) chỉ khi có hơn 4 category; dataset giữ đầy đủ.
- **Non-VND**: cảnh báo của Task 29 (doanh thu phòng không phải VND bị loại, không quy đổi) được in thành ghi chú
  ngắn trong PDF; PDF vẫn được tạo.
- **Ngôn ngữ**: PDF theo locale giao diện PMS hiện tại (Task 27), không có tham số ngôn ngữ riêng; nhãn dịch nằm ở
  tầng render, dataset không chứa chuỗi dịch. Ngày định dạng `dd/MM/yyyy`, số nhóm bằng dấu phẩy, tiền `VND 1,284,000`.
- **Một trang A4 (bắt buộc)**: hình học cố định theo V3; sức chứa Room Type là 5 hàng (nhiều hơn thì 4 hàng đầu
  + "Others" gộp tổng, tỷ lệ tính từ tổng); văn bản dài bị cắt bằng dấu "…" theo độ rộng thực của font, ghi chú cảnh
  báo tối đa 2 dòng; không bao giờ sinh trang thứ hai.
- **Font**: Noto Sans Regular/Bold (SIL OFL 1.1, hỗ trợ tiếng Việt) đóng gói tại `src/main/resources/fonts/` cùng
  `OFL.txt`; không dùng font hệ điều hành. Thư viện: Apache PDFBox 3.0.8, tạo PDF trong bộ nhớ.
- **Phản hồi**: `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="hotel-performance-yyyy-MM.pdf"`,
  `Cache-Control: no-store`; không ghi file tạm. Lối vào tải xuống: thẻ nhỏ trên trang Reports Overview.

## 61.10 Monthly Hotel Performance Excel — quyết định Task 32

`GET /reports/monthly-performance.xlsx?month=yyyy-MM` (`PERM_VIEW_REPORT`, không có permission mới) trả về workbook
Excel tạo trong bộ nhớ. Template runtime và nguồn bố cục duy nhất (KHÔNG có bản sao thứ hai trong `docs/`):
`src/main/resources/report-templates/hotel_monthly_report_excel_mockup_final.xlsx`; workbook được nạp bằng Apache
POI (`poi-ooxml` 5.5.1) và chỉ điền giá trị, giữ nguyên viền, màu, font, độ rộng cột, chiều cao dòng, merge và chart.

- **Tháng**: cùng hợp đồng như PDF (mục 61.9): mặc định `YearMonth.now(clock)` (Asia/Ho_Chi_Minh); từ chối tháng sai
  định dạng, tháng tương lai, tháng trước `firstFullySupportedMonth` của occupancy và lỗi toàn vẹn dữ liệu bằng
  chuyển hướng về `/reports` kèm thông báo đã dịch; không tạo workbook một phần.
- **Năm sheet, tên cố định bằng tiếng Anh** (mọi locale): `Monthly Summary`, `Reservations`, `Payments`, `Expenses`,
  `Occupancy` (chart tham chiếu `'Monthly Summary'`). Nhãn và nội dung BÊN TRONG sheet theo locale giao diện PMS hiện
  tại (không có tham số ngôn ngữ riêng); tiêu đề và tên series của chart cũng được dịch, tham chiếu giữ nguyên.
- **Dataset**: `MonthlyHotelPerformanceExcelData` = `MonthlyHotelPerformanceReport` dùng chung (không đổi) + dòng chi
  tiết Reservation/Payment/Expense. `RevenueTrendPoint` mang thêm Room Revenue (Task 29) để chart Excel có hai series
  Total Revenue và Room Revenue; PDF vẫn chỉ vẽ một series Total Revenue.
- **Monthly Summary** (không chèn dòng): KPI A5:H5, Financial Summary, Reservation Source, Room Type (5 dòng cố định:
  nhiều hơn 5 loại thì 4 dòng đầu + "Others" gộp tổng, tỷ lệ tính từ tổng), Additional Revenue Top 4 + Others (không
  có "Others" giả khi ≤ 4 category), bảng xu hướng sáu tháng A25:C30. Dòng không dùng được xóa giá trị. `A3` ghi chú
  "Data through" chỉ cho tháng hiện tại (đêm đã hoàn tất theo Task 30); `A6` ghi chú cảnh báo doanh thu phòng không phải
  VND của Task 29 khi có; nếu không có thì để trống.
- **Reservations**: Reservation có `checkInDate` trong tháng, MỌI trạng thái (cùng tập với Reservation Source; số dòng
  bằng số Reservations ở Summary), MỘT dòng cho mỗi Reservation (không theo ReservationRoom). Cột: Reservation No.,
  Source, External Booking Ref (`otaBookingReference`, trống với DIRECT), Guest, Check-in, Check-out, Room Type (các
  RoomType phân biệt của phòng đã đặt, theo thứ tự code, nối bằng ", ", qua RoomType hiện tại của Room như analytics
  booked-room; không có snapshot lịch sử), Booking Amount và Currency của Reservation (không quy đổi), Status. Sắp xếp
  `checkInDate` rồi `reservationNumber`.
- **Payments** (dòng tiền thu, KHÔNG phải doanh thu Task 29): `status IN (PAID, REFUNDED)`, mỏ neo `paidAt` trong
  `[đầu tháng, đầu tháng sau)` theo Instant của Asia/Ho_Chi_Minh; không gồm `PENDING`/`FAILED`. Cột: Date (ngày giờ),
  Reservation No., Guest (qua Stay → Reservation → Guest), Method, Reference, Amount (số tiền gốc, không quy đổi,
  không dùng `appliedAmount`), Currency (cột mới, duy nhất thay đổi cấu trúc template, ngay sau Amount), Status. Sắp xếp
  `paidAt` rồi `id`. **Hạn chế V1 đã biết**: Payment `REFUNDED` vẫn là CHÍNH dòng đó, neo theo `paidAt`, hiển thị
  trạng thái Refunded; KHÔNG có dòng âm giả, không bịa `refundedAt` và không chuyển sang tháng hoàn tiền. Hoàn tiền xảy
  ra ở tháng sau KHÔNG xuất hiện như một sự kiện dòng tiền riêng vì domain chưa có lịch sử sự kiện hoàn tiền.
- **Expenses**: dùng tập Task 29: `POSTED` và `expenseDate` trong tháng; cột Date, Category, Description, Amount (VND),
  Status, Created By (username người tạo); sắp xếp `expenseDate` rồi `id`.
- **Occupancy**: theo từng RoomType (không theo ngày), lấy ĐẦY ĐỦ `roomTypePerformance` của Task 30 (RoomType lịch sử,
  đêm occupied/sellable, không gấp 5 dòng), theo thứ tự code; cột Notes để trống (không có ghi chú "Highest occupancy").
- **Ngày/giờ**: ô ngày/ngày giờ thật của Excel với định dạng `dd/MM/yyyy` và `dd/MM/yyyy HH:mm` (quyết định sản phẩm,
  ghi đè `yyyy-mm-dd` trong mock); Instant chuyển theo Asia/Ho_Chi_Minh. Tháng báo cáo (H5) giữ định dạng tháng của
  template. Nhãn enum (nguồn, trạng thái Reservation, phương thức/trạng thái Payment, trạng thái Expense) dùng nhãn đã dịch.
- **Chống formula injection**: mọi văn bản do người dùng/nghiệp vụ nhập được ghi dưới dạng ô chuỗi (không bao giờ là công
  thức); giá trị bắt đầu bằng `=`, `+`, `-`, `@`, tab hoặc xuống dòng dùng style quote-prefix để giữ nguyên chữ hiển thị.
  Không có công thức ô, liên kết ngoài hay macro trong output.
- **Làm sạch metadata**: thông tin cá nhân của template (đường dẫn tuyệt đối Windows, tên người sửa cuối) được thay bằng
  giá trị ứng dụng chung khi sinh file; template gốc không bị sửa.
- **Phản hồi**: `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`,
  `Content-Disposition: attachment; filename="hotel-performance-yyyy-MM.xlsx"`, `Cache-Control: no-store`; không ghi
  file tạm. Lối vào tải xuống: nút "Download Excel" cạnh "Download PDF" trên thẻ Reports Overview (cùng ô chọn tháng).

## 62. Housekeeping Workspace (V1)

Housekeeping thuộc phạm vi V1. Workspace là worklist vận hành, KHÔNG phải màn hình quản trị Room.

- **Route và quyền**: `GET /housekeeping` yêu cầu `MANAGE_HOUSEKEEPING` (không cần `MANAGE_ROOM`). Hành động là POST + CSRF:
  `POST /housekeeping/rooms/{id}/start-cleaning` (DIRTY -> CLEANING) và `/finish-cleaning` (CLEANING -> AVAILABLE),
  gọi lại đúng `RoomService.startCleaning`/`finishCleaning` (state machine mục 21, không thêm transition), rồi redirect về
  `/housekeeping`. Hành động cũ ở Room Detail (yêu cầu `MANAGE_HOUSEKEEPING`) giữ nguyên.
- **Không mở rộng quyền**: `MANAGE_HOUSEKEEPING` KHÔNG cấp đọc/tạo/sửa Room, Room Detail, API `/api/rooms`, maintenance hay
  out-of-order (vẫn `MANAGE_ROOM`). Workspace dùng read model riêng, không nới quyền đọc của module Rooms. V1 không có role
  Housekeeper riêng.
- **Điều hướng**: sidebar HOTEL gồm Rooms (`MANAGE_ROOM`) và Housekeeping (`MANAGE_HOUSEKEEPING`); hai mục hiển thị độc lập.
- **Read model**: chỉ Room `active`. Bốn nhóm và bộ đếm: **Needs Cleaning** = `DIRTY`; **Cleaning** = `CLEANING`;
  **Ready** = `AVAILABLE` (READY là khái niệm trình bày dẫn xuất, KHÔNG lưu trong database); **Issues** = `MAINTENANCE` và
  `OUT_OF_ORDER` (chỉ hiển thị trạng thái, không có hành động). Room `OCCUPIED` và Room không active không xuất hiện. Hành động
  theo ngữ cảnh: DIRTY -> Start Cleaning, CLEANING -> Mark Clean, nhóm khác không có hành động.
- **Next arrival**: với mỗi Room, `checkInDate` nhỏ nhất của `ReservationRoom` thuộc Reservation có status `CONFIRMED` và
  `checkInDate >= ngày hiện tại của khách sạn` (Clock nghiệp vụ Asia/Ho_Chi_Minh). `DRAFT`, `CANCELLED`, `NO_SHOW`,
  `CHECKED_IN`, `CHECKED_OUT` và Reservation CONFIRMED đã quá ngày check-in không được tính. Hiển thị Today / Tomorrow /
  dd/MM/yyyy / No upcoming arrival; không có ETA hay giờ đến.
- **Ưu tiên (dẫn xuất, không lưu)**: Room `DIRTY` có arrival hôm nay là Urgent. Thứ tự Needs Cleaning: Urgent trước, rồi
  arrival gần nhất, rồi Room không có arrival; hòa thì theo room number. Các nhóm còn lại xếp theo room number (không có
  timestamp bắt đầu dọn).
- **Stale action**: domain vẫn là nguồn quyết định; transition không hợp lệ (ví dụ hai người cùng Start Cleaning) trả về
  thông báo nghiệp vụ trên workspace, không đổi trạng thái.
- **Ngoài phạm vi**: phân công nhân viên buồng phòng, checklist, inspection, stayover cleaning, linen, work order bảo trì,
  Front Desk và Arrival Readiness.

## 63. Arrival Readiness (V1 Foundation)

Arrival Readiness cho biết một Reservation có thể check-in ngay bây giờ hay không và vì sao. Nó là khái niệm DẪN XUẤT
(application/presentation): KHÔNG lưu trong database, KHÔNG phải `ReservationStatus`, và `READY` / `NEEDS_ATTENTION`
không phải trạng thái persisted.

- **Một bộ luật duy nhất**: `ArrivalReadinessRules` được dùng cả bởi `ReservationService.checkIn` (thao tác check-in vẫn là
  nguồn quyết định, thông điệp lỗi giữ nguyên) và bởi read model readiness, nên hai bên không thể lệch nhau. Test nhất quán
  kiểm tra: check-in được chấp nhận khi và chỉ khi readiness không có BLOCKER.
- **Kết quả**: `ArrivalReadiness` gồm `state` (`READY` nếu không có BLOCKER, ngược lại `NEEDS_ATTENTION`), `timing`
  (`EARLY`/`NORMAL`/`LATE` theo ngày hiện tại của khách sạn, Clock Asia/Ho_Chi_Minh) và danh sách `issues` có cấu trúc
  (`severity`, `code`, `roomNumber` nếu là lỗi của một phòng), sắp xếp BLOCKER -> WARNING -> INFO. Không chứa văn bản
  localized; văn bản được localize ở tầng hiển thị (`checkin.readiness.*`, EN/VI).
- **Severity**: `BLOCKER` ngăn check-in (thao tác check-in cũng từ chối đúng điều kiện đó); `WARNING` là ngữ cảnh vận hành,
  không ngăn check-in và không đổi `state`; `INFO` là ngữ cảnh (phòng đã sẵn sàng).
- **BLOCKER V1 (chính xác)**: (1) Reservation không ở `CONFIRMED`; (2) ngày hiện tại trước ngày check-in (early check-in);
  (3) đã tồn tại Stay cho Reservation; (4) phòng được gán không `active`; (5) phòng không `AVAILABLE`, phân biệt theo
  trạng thái: `DIRTY` (cần housekeeping), `CLEANING` (đang dọn), `OCCUPIED` (đang có khách), `MAINTENANCE` và
  `OUT_OF_ORDER` (không sử dụng được). Sự sẵn sàng của phòng phụ thuộc vào trạng thái `AVAILABLE` hiện có (mục 21), không
  có `READY` được lưu. Không có blocker nào khác được thêm.
- **WARNING V1**: `ARRIVAL_OVERDUE` (Reservation `CONFIRMED` đã quá ngày check-in: vẫn hiển thị, check-in muộn vẫn được
  phép theo quy tắc hiện có, KHÔNG tự động chuyển `NO_SHOW`); `PASSPORT_MISSING` (ảnh hộ chiếu vẫn là tùy chọn trong V1,
  thiếu hộ chiếu KHÔNG chặn check-in). Thanh toán/số dư/đặt cọc KHÔNG là điều kiện check-in và không phát sinh issue.
- **INFO V1**: `ROOM_READY` cho mỗi phòng active `AVAILABLE`.
- **Nhiều phòng**: mỗi phòng được đánh giá riêng và issue nêu số phòng bị ảnh hưởng; một phòng có blocker khiến toàn bộ
  Reservation `NEEDS_ATTENTION`, các phòng còn lại vẫn hiển thị `ROOM_READY`.
- **Tích hợp**: mục "Arrival Readiness" trên Check-in Review (`CheckInReviewResponse.readiness`), giữ nguyên quyền
  `CHECK_IN`; không cấp thêm quyền nào. Nút Confirm Check-in chỉ hiển thị khi không có BLOCKER (chỉ để tiện dùng; backend
  vẫn xác thực). Chưa có hành động phục hồi (đổi phòng trước check-in, v.v.) và chưa có Front Desk workspace.

## 64. Pre-check-in Room Reassignment (V1 Recovery)

Khi một Reservation `CONFIRMED` không thể check-in vì phòng được gán không dùng được (các blocker `ROOM_*` của mục 63),
staff có thể thay phòng của MỘT dòng phòng thay vì hủy và tạo lại Reservation. Đây là thao tác đặt phòng, KHÔNG phải Stay
Room Change (mục 8.3): chưa có khách nào ở phòng cũ.

- **Điều kiện**: Reservation ở `CONFIRMED` và chưa có Stay. `DRAFT`, `CANCELLED`, `NO_SHOW`, `CHECKED_IN`, `CHECKED_OUT` bị từ chối,
  kể cả khi đã có Stay.
- **Chỉ đổi phòng**: chỉ `reservation_room.room_id` của dòng được chọn thay đổi. Reservation ID, số reservation, guest,
  source, OTA reference, ngày, trạng thái, ghi chú, giá mỗi đêm, tổng tiền và các dòng phòng khác giữ nguyên. Với
  Reservation nhiều phòng, mỗi lần chỉ thay một dòng.
- **Không tự động tính lại giá**: nightly rate/total là snapshot lúc đặt và được giữ nguyên, kể cả khi phòng thay thế thuộc
  RoomType khác (cùng nguyên tắc với Stay Room Change: giá đi theo booking, không theo phòng). V1 không có rate engine.
- **Phòng cũ**: trạng thái không đổi (`OUT_OF_ORDER` vẫn `OUT_OF_ORDER`, `DIRTY` vẫn `DIRTY`, ...). Không phát sinh `DIRTY`,
  không dùng `StayRoomAssignment`, không dùng `CHANGE_ROOM`.
- **Phòng thay thế hợp lệ**: `active` + `AVAILABLE` (check-in-ready ngay), không có Reservation `CONFIRMED`/`CHECKED_IN` chồng
  lấn trong khoảng `[check_in, check_out)` của dòng (nửa mở: check-out trùng ngày check-in không xung đột), chưa nằm trong
  Reservation này và khác phòng hiện tại. Danh sách gợi ý và bước xác thực dùng cùng một điều kiện
  (`RoomAvailabilityService.isCheckInReadyForPeriod`), nên không tái hiện lỗi danh sách ứng viên của Stay Room Change.
- **Transaction/đồng thời**: một transaction; khóa hai phòng (cũ, mới) theo thứ tự id rồi khóa dòng Reservation
  (`PESSIMISTIC_WRITE`) — cùng thứ tự phòng-rồi-reservation với check-in. Dưới khóa, xác thực lại: còn `CONFIRMED`, chưa có
  Stay, dòng vẫn giữ đúng phòng cũ (chống UI cũ), phòng mới còn hợp lệ và không xung đột. Hai lần thay đồng thời cùng một
  dòng chỉ một lần thành công; hai Reservation tranh cùng một phòng thay thế không thể cùng thắng. Lỗi thì không thay đổi gì.
- **Quyền**: `CHECK_IN` (quyền của luồng check-in mà thao tác phục hồi; hẹp hơn `MANAGE_BOOKING` vốn gồm tạo/hủy/no-show).
  Không cần và không cấp `MANAGE_ROOM`, `MANAGE_HOUSEKEEPING`. POST + CSRF.
- **Lịch sử/audit**: ghi `AuditLog` với `action = REASSIGN_ROOM`, `entity_type = RESERVATION`, `old_value = "Room <số cũ>"`,
  `new_value = "Room <số mới>"`, người thực hiện và thời điểm; `updated_by` của Reservation và dòng phòng được cập nhật. Không
  dùng `CHANGE_ROOM`. Không có bảng lịch sử gán phòng trước check-in riêng.
- **Giao diện**: trên Check-in Review, mỗi blocker của một phòng có nút "Reassign Room" (`/check-in/reservations/{id}/rooms/{roomId}/reassign`);
  chọn phòng thay thế, xác nhận, quay lại Review nơi Arrival Readiness được TÍNH LẠI (không lưu). Housekeeping và Front Desk
  chưa được liên kết/triển khai.

## 65. Front Desk Workspace (V1)

Front Desk (`GET /front-desk`) là workspace vận hành chỉ đọc: nó GHÉP thông tin Reservation, Stay, Room, Arrival Readiness
(mục 63) và số dư Stay (mục 24) thành worklist và liên kết tới các thao tác ĐÃ CÓ. Đây là lớp điều phối vận hành, KHÔNG phải
miền nghiệp vụ mới: không có trạng thái, bảng, cột hay permission mới, không có role FRONT_DESK, và không thực hiện
Check-in, Check-out, Housekeeping, Thanh toán, Room Change hay Room Reassignment (chỉ liên kết).

- **Ba view**: Arrivals, Departures, In-house (`?view=arrivals|departures|in-house`). Mỗi request chỉ nạp view được chọn
  và được phép. View mặc định: Arrivals nếu có `CHECK_IN`, ngược lại Departures.
- **Dòng dữ liệu**: Arrivals = một Reservation; Departures và In-house = một Stay. Không có dòng theo phòng: Reservation/Stay
  nhiều phòng hiển thị tất cả phòng trong một dòng (check-in ở mức reservation, check-out ở mức Stay).
- **Ngày giờ**: dùng Clock của khách sạn (Asia/Ho_Chi_Minh). `actualCheckInAt` hiển thị theo múi giờ khách sạn. Không có
  thay đổi trạng thái tự động theo ngày (không tự động NO_SHOW, không tự động check-out).
- **Arrivals**: `Reservation.status = CONFIRMED`, chưa có Stay, `checkInDate <= hôm nay`. Hôm nay = Today Arrival;
  `checkInDate < hôm nay` = Overdue Arrival (vẫn có thể check-in, theo quy tắc muộn hiện có). Không gồm arrival tương lai,
  `DRAFT`, `CANCELLED`, `NO_SHOW`, `CHECKED_IN`, `CHECKED_OUT` (sau check-in, Stay thuộc In-house).
- **Arrival Needs Attention** (dẫn xuất, không lưu): Overdue Arrival HOẶC Arrival Readiness có BLOCKER (dùng
  `ArrivalReadinessRules`, không quy tắc riêng). Thiếu passport chỉ là WARNING và không được đánh giá trên Front Desk
  (vẫn có trên Check-in Review). Thứ tự: needs-attention quá hạn, needs-attention khác, rồi Ready; hòa thì theo
  `checkInDate`, số reservation. Hành động: mở Check-in Review (nút "Check in" khi Ready, "Review" khi cần xử lý; Reassign
  Room nằm trong Check-in Review); liên kết `/housekeeping` chỉ khi có blocker `ROOM_DIRTY`/`ROOM_CLEANING` và user có
  `MANAGE_HOUSEKEEPING`.
- **Departures**: Stay `CHECKED_IN` của Reservation `CHECKED_IN` có `checkOutDate <= hôm nay` (hôm nay = Today Departure,
  trước hôm nay = Overdue Departure). Không gồm departure tương lai. Phòng hiển thị là các `StayRoomAssignment` ĐANG MỞ
  (`assignedTo IS NULL`), không phải `ReservationRoom` và không phải assignment lịch sử.
- **Departure readiness / Needs Attention**: dùng chung `DepartureReadinessRules` (Outstanding = tổng Charge - tổng Payment
  `PAID` `appliedAmount`; bằng 0 -> `READY`, khác 0 -> `PAYMENT_REQUIRED`) với Check-out. Needs Attention khi
  `checkOutDate < hôm nay` HOẶC `PAYMENT_REQUIRED`. Đây là readiness tài chính vận hành, không đảm bảo check-out không thể
  thất bại: `ReservationService.checkOut` vẫn là nguồn quyết định (ví dụ kiểm tra trạng thái phòng).
- **Hiển thị tiền**: số tiền Outstanding chỉ có và chỉ hiển thị khi user có `MANAGE_PAYMENT`; user chỉ có `CHECK_OUT` chỉ thấy
  nhãn READY/PAYMENT_REQUIRED.
- **In-house**: mọi Stay `CHECKED_IN` của Reservation `CHECKED_IN`; phòng hiện tại từ assignment đang mở; gồm
  `actualCheckInAt`, `checkOutDate` dự kiến. Không đọc số dư.
- **Danh tính khách**: họ tên đầy đủ (nếu có) và Guest Code; thiếu tên thì chỉ hiển thị Guest Code. Không đổi quy ước hiển thị
  khách ở các màn hình khác.
- **Quyền**: truy cập cần `CHECK_IN` HOẶC `CHECK_OUT` (không có cả hai -> 403). Arrivals cần `CHECK_IN`; Departures và
  In-house cần `CHECK_OUT`; yêu cầu một view không được phép bị từ chối (403) và không nạp dữ liệu. Sidebar hiển thị mục Front
  Desk khi có `CHECK_IN` hoặc `CHECK_OUT`. Mỗi liên kết giữ permission gốc: Check-in Review/Reassign (`CHECK_IN`), Check-out
  Review (`CHECK_OUT`), Folio (`MANAGE_PAYMENT`), Housekeeping (`MANAGE_HOUSEKEEPING`), Reservation Detail (`VIEW_BOOKING`),
  Room Change (`CHANGE_ROOM`). Front Desk không cấp thêm quyền; backend của route đích vẫn là nguồn quyết định.
- **Truy vấn**: read model dùng số truy vấn cố định, không truy vấn theo dòng và không gọi `CheckInService.review` hay
  `StayBalanceService` theo dòng: Arrivals 3 (reservation + guest; phòng + RoomType; Stay đã tồn tại), Departures 4 (Stay +
  reservation + guest; assignment mở + Room + RoomType; tổng Charge theo Stay; tổng Payment PAID theo Stay), In-house 2.
- **Giới hạn đã biết**: danh sách ứng viên của Room Change trong Stay (đã biết) chưa được sửa; không có view arrival tương lai;
  Dashboard chưa tích hợp Front Desk.

## 66. Reservation Guest Composition — Adults & Children (P1 Task A)

Reservation có thêm `adultCount` và `childCount` (cột `adult_count`, `child_count`, migration V30) để lưu quy mô đoàn khách vật lý.

- **Bất biến**: `adultCount >= 1` (bắt buộc), `childCount >= 0` (bắt buộc), cả hai là số nguyên; giá trị thiếu, thập phân hoặc không hợp lệ bị từ chối
  (Bean Validation ở form/API, kiểm tra lại ở `ReservationService`, bất biến ở entity và ràng buộc CHECK + NOT NULL ở database). Không có nhóm tuổi hay "infant".
- **`partySize = adultCount + childCount`**: giá trị dẫn xuất, KHÔNG lưu thành cột. Hiển thị là "Total Guests"/"Tổng số khách" (không gọi là
  "Occupancy" vì Occupancy trong báo cáo hiện tại là room-night occupancy và KHÔNG thay đổi).
- **Guest chính và quy mô đoàn tách biệt**: Reservation vẫn có đúng một Guest chính bắt buộc. Số Guest profile không cần bằng `partySize`; số lượng không được
  suy ra từ Guest, phòng, `RoomType.capacity` hay ảnh passport. Passport và các quy tắc Guest không đổi.
- **Tạo Reservation**: mọi luồng tạo mới (form Create Reservation, `POST /api/reservations`, OTA Booking Not Entered, Walk-in) phải cung cấp số lượng tường minh;
  form mặc định Adults = 1, Children = 0. Walk-in chuyển số lượng vào Reservation DIRECT được tạo. Constructor dành cho fixture/dữ liệu lịch sử dùng mặc định 1 người lớn, 0 trẻ em.
- **Sửa DRAFT**: có thể đổi số lượng như các trường DRAFT khác. Reservation `CONFIRMED` trở đi KHÔNG thể đổi qua Draft edit (thao tác "Update Guest Composition" cho reservation đã
  confirm thuộc task sau và chưa được triển khai).
- **Dữ liệu cũ**: migration V30 thêm cột `NOT NULL DEFAULT` nên mọi Reservation hiện có được backfill `1` người lớn, `0` trẻ em; giá trị mặc định ở database được giữ lại để các INSERT SQL trực tiếp
  (fixture/seeder) vẫn hợp lệ, còn ứng dụng luôn truyền giá trị tường minh cho Reservation mới.
- **Hiển thị**: Reservation Detail hiển thị Adults, Children và Total Guests; Walk-in Review hiển thị số lượng. Check-in Review, Front Desk và mọi báo cáo/Dashboard/PDF/Excel KHÔNG đổi trong Task A.
- **Sức chứa (capacity)**: KHÔNG được kiểm tra trong Task A. Quy tắc dự kiến ở task sau (`adultCount <= SUM(RoomType.capacity)`, trẻ em không chiếm sức chứa người lớn) chưa được triển khai; DRAFT (và
  confirm/check-in) có thể có số người lớn vượt sức chứa phòng, `RoomType.capacity` không được sửa hay dùng. **Accompanying Guests → Task B; kiểm tra capacity → Task C** (chưa triển khai).

## 66.1 Accompanying Guests (P1 Task B)

Ngoài Guest chính (bắt buộc, vẫn nằm ở `reservation.guest_id`), Reservation có thể có **0..N Accompanying Guests**: chính các Guest profile tái sử dụng hiện có,
được liên kết qua bảng `reservation_guest` (migration V31: `id`, `reservation_id` FK, `guest_id` FK, cột audit, `UNIQUE (reservation_id, guest_id)`).

- **Bản chất**: Accompanying Guest là Guest profile đã có, không tạo thực thể "person" thứ hai và không sao chép dữ liệu Guest vào quan hệ. Một Guest có thể là Guest chính
  của Reservation này và là Accompanying Guest của Reservation khác, và có thể quay lại ở các lần đặt sau.
- **Hồ sơ đã biết ≠ quy mô đoàn**: số Guest profile đã biết KHÔNG cần bằng `partySize = adultCount + childCount` và không được suy ra `adultCount`/`childCount`
  (ví dụ 3 người lớn + 1 trẻ em với chỉ Guest chính và 1 Accompanying Guest là hợp lệ).
- **Bất biến**: Guest chính không được đồng thời là Accompanying Guest; một Guest chỉ xuất hiện một lần trong Accompanying Guests của một Reservation. Ràng buộc "khác Guest chính"
  được thực thi ở domain/application (`Reservation`, `ReservationService`), KHÔNG bằng trigger; unique và khóa ngoại được thực thi ở database.
- **Không gán theo phòng, không thuộc Stay**: Accompanying Guests không gắn với `ReservationRoom`, không được sao chép vào Stay/`StayRoomAssignment`, và Room Change không thay đổi chúng.
  Stay đọc qua `Stay → Reservation`. Không snapshot Guest.
- **Tạo/sửa DRAFT**: `CreateRequest.accompanyingGuestIds` (tùy chọn, có thể rỗng). Service từ chối id không tồn tại, id trùng, và Guest chính nằm trong danh sách; không tạo Guest tự động. Khi sửa DRAFT,
  cả tập được thay thế cùng thao tác (giữ các dòng liên kết còn lại), nên đổi Guest chính sang một Guest đang ở trong danh sách mới bị từ chối rõ ràng, không tự xóa dữ liệu. Reservation `CONFIRMED` trở đi không sửa được
  qua Draft edit; thao tác "Update Guest Composition" cho Reservation đã confirm chưa được triển khai.
- **Hiển thị (chỉ đọc)**: Reservation Detail và Check-in Review hiển thị Adults, Children, Total Guests, Guest chính và Accompanying Guests (kèm trạng thái rỗng "No accompanying guests"). Theo quy ước hiện có,
  Guest được nhận diện bằng Guest Code (liên kết tới hồ sơ chỉ khi có `MANAGE_GUEST`); người dùng chỉ có `VIEW_BOOKING`/`CHECK_IN` không nhận thêm dữ liệu hồ sơ Guest. Không hiển thị bản ghi giả cho người chưa định danh.
  Check-in Review không đổi eligibility, Arrival Readiness hay blocker; không yêu cầu mọi người có Guest profile.
- **Passport**: ngữ nghĩa V1 không đổi — cảnh báo passport vẫn dựa trên Guest chính; Accompanying Guests không thêm blocker, cảnh báo hay yêu cầu ảnh passport; `GuestDocument` hiện có không bị di chuyển hay diễn giải lại.
- **Quyền**: dùng quyền hiện có. Liên kết Guest hiện có với Reservation thuộc `MANAGE_BOOKING` (không cần `MANAGE_GUEST`); tạo/sửa Guest vẫn `MANAGE_GUEST`; Check-in Review `CHECK_IN`; Reservation Detail `VIEW_BOOKING`.
- **Truy vấn**: Accompanying Guests được nạp bằng MỘT truy vấn (fetch join Guest) cho Detail, form sửa và Check-in Review — không truy vấn theo từng Guest.
- **Chưa thay đổi**: kiểm tra capacity (Task C, chưa triển khai), Front Desk (vẫn chỉ Guest chính), Dashboard/báo cáo/PDF/Excel, audit (vẫn là entry mức thao tác CREATE/UPDATE, không audit theo trường).

## 66.2 Adult Capacity (P1 Task C)

`RoomType.capacity` là **sức chứa NGƯỜI LỚN** và là bất biến của Reservation:

```text
adultCount <= SUM(RoomType.capacity của mọi phòng đang được gán cho Reservation)
```

- **Một quy tắc dùng chung**: `AdultCapacityRules` (thuần, không phụ thuộc MVC/HTTP/template/văn bản localized) trả về kết quả có cấu trúc `VALID`,
  `INSUFFICIENT_ADULT_CAPACITY` hoặc `CAPACITY_NOT_CONFIGURED` (cùng `adultCount`, tổng sức chứa, tên RoomType chưa cấu hình). Confirm, Arrival Readiness, Check-in và Pre-check-in Room Reassignment đều
  gọi đúng quy tắc này, không tự cài lại công thức.
- **Trẻ em không chiếm sức chứa người lớn** trong V1 (không có child capacity, tuổi, infant, giường phụ). `partySize` và số Accompanying Guest profile KHÔNG tham gia phép tính.
- **Cộng dồn theo Reservation, không gán người vào phòng**: sức chứa là tổng của tất cả phòng (ví dụ 3 người lớn hợp lệ với DOUBLE(2) + SINGLE(1)); không kiểm tra theo từng phòng và không có gán người-phòng.
  Sức chứa lấy từ RoomType hiện tại của Room; không lưu snapshot sức chứa trên `ReservationRoom` và không lưu tổng sức chứa.
- **`capacity = null` = sức chứa chưa biết / lỗi cấu hình RoomType**: KHÔNG được hiểu là 0, không giới hạn hay bỏ qua phòng đó; nếu bất kỳ phòng nào có RoomType chưa cấu hình sức chứa thì kiểm tra không thể thành công
  (`CAPACITY_NOT_CONFIGURED`) và chặn tiến trình vòng đời. Cột `capacity` vẫn nullable (không thêm migration).
- **DRAFT được phép tạm vượt sức chứa**: Create và Draft edit không chặn (không cảnh báo capacity trong UI Draft).
- **Confirm chặn cứng**: sau khi khóa phòng và kiểm tra overlap, capacity được kiểm tra trên đúng tập phòng đã khóa; lỗi (409) không làm thay đổi Reservation và không tự thêm/đổi phòng, sửa số người hay giá.
- **Arrival Readiness**: capacity không hợp lệ là BLOCKER (`INSUFFICIENT_ADULT_CAPACITY`, `CAPACITY_NOT_CONFIGURED`), hiển thị localized trên Check-in Review; Front Desk dùng readiness dùng chung nên đưa reservation vào Needs Attention
  mà không có logic capacity riêng. Các blocker/cảnh báo khác giữ nguyên.
- **Check-in chặn cứng và đánh giá lại độc lập**: backend kiểm tra lại sức chứa HIỆN TẠI (không tin rằng đã confirm nghĩa là hợp lệ) sau các kiểm tra trạng thái phòng hiện có và trước mọi thay đổi (không tạo Stay/ROOM charge khi lỗi).
- **Pre-check-in Room Reassignment**: kiểm tra tập phòng KẾT QUẢ (phòng hiện tại với phòng bị thay được đổi thành phòng đích) trong cùng thao tác có khóa; sai thì từ chối, không thay đổi gì. Danh sách phòng thay thế cũng lọc theo cùng quy tắc.
- **Chưa thay đổi / hoãn**: hành vi capacity của Stay Room Change (sau check-in) là quyết định còn hoãn và KHÔNG thay đổi trong Task C; thao tác "Update Guest Composition" cho Reservation đã confirm vẫn chưa được triển khai;
  Occupancy trong báo cáo vẫn là room-night occupancy, không đổi.

## 66.3 Guest Composition Update for CONFIRMED Reservations (P1 Task D)

Reservation `CONFIRMED` (chưa có Stay) có thể cập nhật Guest Composition qua MỘT thao tác chuyên biệt, KHÔNG phải sửa Reservation tổng quát:
`ReservationService.updateConfirmedGuestComposition` (MVC `GET|POST /reservations/{id}/guest-composition`, REST `POST /api/reservations/{id}/guest-composition`).

- **Trạng thái cho phép**: chỉ `CONFIRMED` và không tồn tại Stay (kể cả khi dữ liệu bất thường để status vẫn `CONFIRMED`). `DRAFT` tiếp tục dùng Draft Edit (không đổi, vẫn DRAFT-only);
  `CANCELLED`, `NO_SHOW`, `CHECKED_IN`, `CHECKED_OUT` bị từ chối. Sau check-in, composition bị đóng băng trong V1 và không có thao tác composition ở cấp Stay.
- **Trường được đổi**: chỉ `adultCount`, `childCount` và tập Accompanying Guests. Guest chính KHÔNG đổi được; ngày, nguồn, OTA reference, tiền tệ, phòng, giá, tổng tiền, ghi chú, số reservation và dữ liệu Stay không đổi
  (request không có các trường đó).
- **Bất biến giữ nguyên**: `adultCount >= 1`, `childCount >= 0`; Guest chính không nằm trong Accompanying Guests; không trùng lặp; mọi id phải tồn tại; số Guest profile không cần bằng `partySize` và không suy ra số lượng.
- **Capacity dùng `AdultCapacityRules`** (không nhân đôi công thức): adult count MỚI được đánh giá trên tập phòng hiện đang gán (nạp bằng một truy vấn); trẻ em và Accompanying Guests không tham gia; `capacity = null` hoặc thiếu RoomType
  cho kết quả `CAPACITY_NOT_CONFIGURED`. Hệ thống không tự thêm/đổi phòng, giảm số người hay đổi giá; nhân viên xử lý phòng riêng rồi thử lại.
- **Atomic**: toàn bộ đề xuất được kiểm tra (trạng thái, Stay, số lượng, Guest, capacity) TRƯỚC khi thay đổi bất cứ thứ gì; lỗi thì số lượng, danh sách Accompanying Guests và audit đều không đổi. Tập liên kết được đối chiếu theo Guest
  (giữ dòng còn lại, chỉ thêm/xóa phần chênh lệch) nên không vi phạm `UNIQUE (reservation_id, guest_id)`.
- **Khóa/đồng thời**: khóa dòng Reservation (`PESSIMISTIC_WRITE`) TRƯỚC, rồi mới đọc trạng thái, Stay và tập phòng. Room Reassignment chỉ đổi phòng khi giữ cùng khóa dòng Reservation (khóa Room rồi Reservation) và check-in cập nhật dòng
  Reservation, nên các thao tác được tuần tự hóa và capacity được đánh giá trên tập phòng hiện hành; chỉ khóa Reservation (không khóa Room) để giữ thứ tự khóa không deadlock. Không dùng khóa toàn cục.
- **Audit**: mỗi lần cập nhật thành công ghi `AuditLog` `UPDATE_GUEST_COMPOSITION` (entity Reservation, người thực hiện, thời điểm, giá trị trước/sau dạng `adults=…, children=…, accompanying=…`); cập nhật thất bại không ghi entry thành công.
- **Readiness/Check-in/Reassignment**: Arrival Readiness vẫn là dẫn xuất (không lưu READY/NEEDS_ATTENTION) và Check-in, Pre-check-in Room Reassignment tự nhiên đọc `adultCount` mới qua `AdultCapacityRules`; logic của chúng không đổi. Front Desk chỉ phản ánh readiness dẫn xuất.
- **Quyền**: `MANAGE_BOOKING` (không cần `MANAGE_GUEST` để liên kết Guest hiện có; tạo/sửa Guest vẫn `MANAGE_GUEST`); `VIEW_BOOKING` một mình không đủ.
- **Giao diện**: trên Reservation Detail, action "Edit Guest Composition" chỉ hiện cho `CONFIRMED` và user có `MANAGE_BOOKING`; form hiển thị Guest chính chỉ đọc và dùng lại picker Accompanying Guests của Draft form. Lỗi capacity/trạng thái được localize (EN/VI).
- **Không đổi / hoãn**: passport (vẫn cảnh báo dựa trên Guest chính), báo cáo (Occupancy vẫn là room-night), và hành vi capacity của Stay Room Change (sau check-in) vẫn là quyết định hoãn — không triển khai trong Task D.

## 67. Room Change-aware Booking Availability (P1 Inventory Integrity)

Tính sẵn sàng khi ĐẶT phòng (booking availability) dùng MỘT primitive overlap duy nhất, nhận biết vòng đời: `RoomAvailabilityService.conflictedRoomIds / hasInventoryConflict`
(một truy vấn JPQL có giới hạn trong `RoomRepository.findRoomIdsWithInventoryConflict`, theo danh sách room-id, không N+1). Mọi nơi cần kiểm tra overlap phải đi qua primitive này.

- **`ReservationRoom` là snapshot đặt phòng/giá bất biến** và KHÔNG bị Room Change thay đổi (báo cáo, Charge, giá không đổi).
- **Reservation `CONFIRMED`** chặn phòng bằng khoảng ngày của `ReservationRoom`. **Reservation `CHECKED_IN`** chặn phòng bằng các `StayRoomAssignment` THỰC TẾ (đổi sang ngày khách sạn); `ReservationRoom` của Reservation CHECKED_IN KHÔNG được đếm thêm.
  Các trạng thái khác (DRAFT, CANCELLED, NO_SHOW, CHECKED_OUT) không chặn.
- **Khoảng nửa mở `[in, out)`**: lưu trú kết thúc đúng ngày `in` không chặn lưu trú bắt đầu ngày đó.
- **Ranh giới ngày Room Change**: Room Change vào ngày khách sạn D chuyển tồn kho từ D (A=`[20/09,21/09)`, B=`[21/09,24/09)`). Assignment đã đóng phủ `[date(assignedFrom), date(assignedTo))` theo cùng quy ước đêm khách sạn của Occupancy Report.
  Việc đổi `Instant` sang ngày khách sạn dùng `Clock`/múi giờ khách sạn được tiêm (Asia/Ho_Chi_Minh), tính bằng Java, không đổi múi giờ trong SQL.
- **Quá hạn (overdue)**: assignment đang mở bảo vệ tới `max(ngày check-out dự kiến, hôm nay + 1)`; nghĩa là stay quá hạn chỉ bảo vệ đêm hiện tại. Hệ quả: vào ngày check-out dự kiến khi còn CHECKED_IN, phòng vẫn bị giữ cho đêm nay tới khi check-out đóng assignment.
- **Status tách khỏi availability theo ngày**: `Room.status` (OCCUPIED/DIRTY/CLEANING…) không bao giờ chặn Confirm hay đặt tương lai; kiểm tra vật lý lúc check-in (AVAILABLE, `ROOM_OCCUPIED`) và Room Change (đích phải AVAILABLE) giữ nguyên.
- **Nơi gọi**: `ReservationService.confirm`, `RoomChangeService` (danh sách ứng viên + `changeRoom`), tra cứu phòng (`RoomAvailabilityService`, gồm Pre-check-in Reassignment với ngữ nghĩa không đổi). Người khách đổi lại B→A và đổi nhiều bước A→B→C đúng mà không cần loại trừ đặc biệt.
- **Đồng thời**: V1 do ứng dụng bảo đảm; kiểm tra overlap luôn thực hiện KHI đang giữ khóa dòng Room (`lockAllByIdIn`, sắp xếp theo id) ở cả Confirm lẫn Room Change nên hai thao tác cạnh tranh cùng phòng/ngày được tuần tự hóa.
- **Không tự sửa** các xung đột đã tồn tại trong dữ liệu. Mô hình phân bổ ở mức DB (bảng allocation / exclusion constraint) được hoãn. Stay Extension được triển khai ở mục 68.

## 68. Stay Extension (P1, V1)

Một Stay `CHECKED_IN` có thể được gia hạn (dời ngày trả phòng dự kiến muộn hơn) qua MỘT thao tác chuyên biệt: `StayExtensionService.extend`
(MVC `GET|POST /reservations/{id}/stay-extension`, REST `POST /api/reservations/{id}/stay-extension`). Không có PATCH ngày tổng quát.

- **Các khái niệm tách biệt**: `ReservationRoom` = snapshot đặt phòng/giá GỐC bất biến (không bị Stay Extension sửa: phòng, ngày, giá đêm, thành tiền); `Reservation.checkOutDate` = ngày trả phòng dự kiến HIỆN TẠI sau khi check-in
  (`Reservation.extendCheckOut`: chỉ `CHECKED_IN`, chỉ tiến lên, không có setter chung); `StayRoomAssignment` = chiếm phòng thực tế; `StayExtension` = một sự kiện gia hạn; `StayExtensionRoom` = snapshot lưu trú/giá của MỘT lineage (kèm
  phòng thực tế tại thời điểm gia hạn, không suy ra lại về sau); `Charge` = khoản ROOM đã ghi vào folio. Không gộp các khái niệm này.
- **Tổng tiền**: `Reservation.totalAmount` vẫn là Original Booking Total (không cộng tiền gia hạn). Extension Amount = Σ `StayExtensionRoom.amount`; Current Accommodation Total = `totalAmount` + Extension Amount là giá trị DẪN XUẤT (không lưu).
- **Trạng thái**: chỉ khi Reservation `CHECKED_IN` VÀ Stay tồn tại và `CHECKED_IN`; DRAFT/CONFIRMED/CANCELLED/NO_SHOW/CHECKED_OUT, thiếu Stay hoặc Reservation/Stay không nhất quán bị từ chối, không tự sửa dữ liệu.
- **Quy tắc ngày** (ngày khách sạn từ `Clock`): `newCheckOutDate > currentPlannedCheckOut` VÀ `newCheckOutDate >= hotelToday` (đã đổi ở mục 71). Được phép trước ngày trả phòng, đúng ngày trả phòng, và khi quá hạn; luôn dời ngày trả phòng về phía trước. Giai đoạn gia hạn luôn là `[previousCheckOutDate, newCheckOutDate)`,
  nên đêm quá hạn KHÔNG biến mất khỏi tính tiền (hôm nay 23/09, hiện tại 22/09: mới 22/09 bị từ chối; 23/09 → 1 đêm; 24/09 → 2 đêm 22 và 23).
- **Toàn bộ Stay**: mọi lineage của assignment đang mở đều tham gia cùng `[previous, new)`; không gia hạn từng phòng, không trả phòng một phần.
- **Nhiều lần gia hạn**: chuỗi tuyến tính theo `sequence_no`; `previousCheckOutDate` của lần sau bằng `Reservation.checkOutDate` đọc dưới khóa. Ràng buộc DB `UNIQUE(stay_id, sequence_no)` và `UNIQUE(stay_id, previous_check_out_date)` chỉ là lớp chặn cuối.
- **Giá**: luôn là `nightlyRate` của `ReservationRoom` gốc của lineage (kể cả sau A→B→C); không dùng `RoomType.basePrice`, giá phòng hiện tại hay giá động. `amount = nightlyRate × số đêm thêm` (BigDecimal); giá và thành tiền được lưu snapshot ở `StayExtensionRoom`.
  Không hỗ trợ gia hạn miễn phí/giá 0 (Charge và extension yêu cầu amount > 0).
- **Room Change tương thích**: dòng gia hạn lưu `original_reservation_room_id` (lineage, neo giá) VÀ `room_id` (phòng thực tế lúc gia hạn). Room Change sau đó không đổi lịch sử gia hạn cũ; lần gia hạn sau dùng phòng hiện tại và vẫn lấy giá từ lineage.
  Room Change dùng `Reservation.checkOutDate` làm ngày trả phòng dự kiến hiện tại (ứng viên, review và điều kiện `today < planned`); hành vi nghiệp vụ khác của Room Change không đổi.
- **Tồn kho (mục 67)**: assignment CHECKED_IN đang mở bảo vệ tới `max(Reservation.checkOutDate, hôm nay + 1)` (nguồn planned checkout đổi từ `ReservationRoom` sang `Reservation`); CONFIRMED vẫn dùng `ReservationRoom`; assignment đã đóng không đổi.
  Kiểm tra gia hạn dùng primitive dùng chung với tham số `excludedStayId` (chỉ Stay Extension truyền Stay hiện tại, tránh tự xung đột đặc biệt khi gia hạn đúng/sau ngày trả phòng). Confirm, Room Change và tra cứu không loại trừ gì.
  Xung đột với đặt phòng/lưu trú khác bị TỪ CHỐI; không tự chuyển phòng, không tự hủy.
- **Billing**: với mỗi `StayExtensionRoom` tạo MỘT `Charge` ROOM chỉ cho các đêm thêm (quantity = số đêm, unitPrice = giá lineage, amount = thành tiền, mô tả `Room <số phòng> extension dd/MM/yyyy - dd/MM/yyyy`); `StayExtensionRoom.charge` tham chiếu Charge (mỗi Charge thuộc tối đa một dòng).
  Charge ROOM gốc lúc check-in không bị tạo lại/sửa/xóa. ROOM Charge thủ công qua `ChargeService` vẫn bị cấm (chỉ hệ thống tạo: check-in và Stay Extension).
- **Không chặn theo thanh toán**: không yêu cầu outstanding = 0 hay trả trước; `StayBalanceService`/check-out readiness tự phản ánh số dư mới. Check-out sớm vẫn như cũ: không hoàn tiền, không rút ngắn, không đảo Charge.
- **Bảo vệ yêu cầu cũ**: request mang `expectedCurrentCheckOutDate` và `newCheckOutDate`; dưới khóa, `Reservation.checkOutDate` phải bằng expected, nếu không bị từ chối (stale, HTTP 409).
- **Khóa/đồng thời**: (1) khóa Stay (`findByReservationIdForUpdate`), (2) khóa các Room của assignment đang mở theo thứ tự id, (3) đọc lại assignment dưới khóa, (4) kiểm tra lại trạng thái, (5) ngày dự kiến, (6) tồn kho với Stay hiện tại bị loại trừ, (7) thay đổi trong cùng transaction.
  Room Change được sửa tối thiểu để cũng khóa Stay TRƯỚC khi khóa Room (thứ tự Stay → Rooms dùng chung với Stay Extension và Check-out); Confirm vẫn khóa Room. Các cặp Extend/Confirm, Extend/Extend, Extend/Check-out, Extend/Room Change được tuần tự hóa.
- **Atomic**: kiểm tra trạng thái, khóa, tồn kho, `Reservation.checkOutDate`, `StayExtension`, `StayExtensionRoom`, `Charge` và `AuditLog` trong MỘT transaction; lỗi thì không có gì được ghi.
- **Schema (V32, additive)**: `stay_extension(id, stay_id, sequence_no, previous_check_out_date, new_check_out_date, audit)` với `CHECK new > previous`, `UNIQUE(stay_id, sequence_no)`, `UNIQUE(stay_id, previous_check_out_date)`;
  `stay_extension_room(id, stay_extension_id, original_reservation_room_id, room_id, from_date, to_date, nightly_rate, amount, charge_id, audit)` với `CHECK to_date > from_date`, `CHECK amount > 0`, `UNIQUE(stay_extension_id, original_reservation_room_id)`, `UNIQUE(charge_id)`.
  Không backfill; Reservation cũ có 0 bản ghi gia hạn; không sửa dòng `ReservationRoom` nào.
- **Báo cáo**: Monthly Financial Report cộng thêm doanh thu gia hạn như nguồn thứ hai (cùng trạng thái đủ điều kiện CHECKED_IN/CHECKED_OUT, cùng công thức chồng lấn tháng theo `[from_date, to_date)`, cùng kiểm tra toàn vẹn `amount = rate × số đêm`, tiền tệ theo `Reservation.currency`,
  ngoài VND vào cảnh báo non-VND); tính theo lineage gốc. Phần `ReservationRoom` giữ nguyên. Occupancy và Room Type Performance vẫn dựa trên `StayRoomAssignment` thực tế. PDF/Excel dùng lại kết quả báo cáo nên tổng/KPI tài chính đã gồm doanh thu gia hạn; bố cục workbook đã duyệt (kể cả sheet Reservations, cột "Booking Amount" vẫn là tổng đặt phòng gốc) KHÔNG đổi.
- **Quyền**: `EXTEND_STAY` (đã thay `MANAGE_BOOKING` ở mục 72; mặc định ADMIN, MANAGER, STAFF); không cần `MANAGE_PAYMENT` vì Charge ROOM do server tính (tương tự Charge ROOM tự động lúc check-in). Số dư chỉ hiển thị trên form cho user có `MANAGE_PAYMENT`.
- **Giao diện**: Reservation Detail của Reservation `CHECKED_IN` có action "Extend Stay" (cần `EXTEND_STAY`) và hiển thị Original Booking Total / Extension Amount / Current Accommodation Total và lịch sử gia hạn khi đã có gia hạn; Front Desk In-house/Departures có liên kết
  tới cùng form. Form chỉ nhận ngày trả phòng mới (kèm ngày hiện tại ẩn để chống yêu cầu cũ); không chọn phòng, không sửa giá/số tiền. Văn bản dùng i18n EN/VI (`stayextension.*`).
- **Audit**: một `AuditLog` `EXTEND_STAY` (entity RESERVATION) khi thành công: ngày trả phòng cũ → mới, số đêm, extension id, từng phòng hiện tại với giá × số đêm = thành tiền, và tổng tiền gia hạn. Thất bại không ghi audit thành công.
- **Hoãn/Không thuộc V1**: giá gia hạn do nhân viên nhập, giá động, gia hạn miễn phí, rút ngắn lưu trú, hoàn tiền check-out sớm, gia hạn/trả phòng từng phòng, trả phòng một phần, mô hình DB `RoomAllocation` và exclusion constraint.

## 69. Folio ↔ Revenue Reconciliation (P1, V1)

Các khái niệm tài chính tách biệt; V1 chỉ thêm liên kết và kiểm tra tính toàn vẹn, KHÔNG có sổ cái (RevenueLedger/RevenueEntry/double-entry).

- **Folio**: `Charge` (khách nợ) + `Payment` (khách đã trả). Total Charges = Σ `Charge.amount`; Total Payments = Σ `Payment.appliedAmount` với status `PAID`; Outstanding = Charges − Payments (`StayBalanceService`). PENDING/FAILED/REFUNDED không tính là đã trả; không có outstanding âm. Không đổi refund.
- **Doanh thu phòng**: `ReservationRoom` (gốc) + `StayExtensionRoom` (gia hạn). `Charge` KHÔNG là nguồn doanh thu phòng; `Payment` KHÔNG tạo doanh thu (Payment không đọc trong báo cáo doanh thu).
- **Doanh thu dịch vụ**: `AdditionalRevenue`. Charge dịch vụ của khách (`BREAKFAST`, `EXTRA_BED`, `LAUNDRY`, `MINIBAR`, `SERVICE`, `OTHER`) tạo ĐÚNG MỘT `AdditionalRevenue` liên kết (`additional_revenue.charge_id`, UNIQUE) trong CÙNG transaction: amount = `Charge.amount`,
  `revenueDate` = ngày khách sạn của `Charge.chargedAt` (không có serviceDate; không theo ngày Payment), currency VND, danh mục hệ thống theo mã ổn định `GUEST_<LOẠI>` (seed ở V33; danh mục có sẵn không đổi; danh mục hệ thống được dùng bất kể cờ active).
  `paymentMethod` = NULL cho dòng liên kết Charge (đăng Charge không phải thanh toán, không bịa CASH); cột chỉ nullable khi có `charge_id` (CHECK). ROOM/gia hạn không tạo `AdditionalRevenue`; TAX/DISCOUNT vẫn không hỗ trợ.
  Reservation không phải VND thì Charge dịch vụ bị TỪ CHỐI nguyên tử (thông báo i18n), không ghi doanh thu sai tiền tệ. `AdditionalRevenue` độc lập (không có `charge_id`) giữ nguyên hành vi và quyền `MANAGE_ADDITIONAL_REVENUE`.
- **Doanh thu liên kết do hệ thống quản lý**: dòng có `charge_id` không được sửa/hủy độc lập (bị từ chối; giao diện ẩn Edit/Void); không có sửa/xóa/đảo Charge trong V1 (hoãn).
- **Charge ROOM gốc**: `charge.source_reservation_room_id` (FK nullable, chỉ ROOM, partial UNIQUE) trỏ tới `ReservationRoom` nguồn, đặt khi check-in trong cùng transaction; số tiền vẫn lấy từ snapshot. Charge ROOM gia hạn không dùng cột này (dùng `stay_extension_room.charge_id`).
  Backfill V33 chỉ liên kết dòng KHÔNG mơ hồ: charge ROOM có mô tả `Room <số phòng đặt>` và số tiền bằng `ReservationRoom.total_amount`, không thuộc dòng gia hạn, khớp đúng 1 ReservationRoom của Reservation của Stay và ReservationRoom đó khớp đúng 1 charge; còn lại để NULL (reconciliation báo cáo). Không sửa/xóa bản ghi lịch sử.
- **Reconciliation (chỉ chẩn đoán)**: `FolioReconciliationService.reconcile(stayId)` (4 truy vấn có giới hạn, không tự sửa): kỳ vọng = Σ `ReservationRoom.totalAmount` + Σ `StayExtensionRoom.amount` so với ROOM charge thực tế; báo `MISSING_ORIGINAL_ROOM_CHARGE`,
  `ORIGINAL_ROOM_CHARGE_AMOUNT_MISMATCH`, `MISSING_EXTENSION_CHARGE`, `EXTENSION_CHARGE_AMOUNT_MISMATCH`, `ORPHAN_ROOM_CHARGE`, `SERVICE_CHARGE_WITHOUT_REVENUE`, `SERVICE_REVENUE_AMOUNT_MISMATCH`. KHÔNG phải điều kiện check-out
  (check-out vẫn chỉ cần outstanding = 0). Reservation Detail (CHECKED_IN/CHECKED_OUT) hiển thị "Financial Integrity: Matched / Needs Review" cho user có `MANAGE_PAYMENT` (không thêm quyền).
- **Ghi nhận doanh thu phòng theo đêm đã bắt đầu**: Reservation `CHECKED_IN` chỉ ghi nhận các đêm có ngày bắt đầu ≤ hôm nay theo `Clock` khách sạn: khoảng ghi nhận kết thúc (exclusive) = `min(kết thúc khoảng, hôm nay + 1)` (ví dụ 20→25, hôm nay 21 → đêm 20 và 21). Áp dụng cho `ReservationRoom` và `StayExtensionRoom`,
  vẫn chia theo tháng. `CHECKED_OUT` giữ nguyên đêm theo hợp đồng (không hoàn/tính lại khi trả phòng sớm). Báo cáo tài chính: Room Revenue = ReservationRoom + StayExtensionRoom đã ghi nhận; Additional Revenue = `RECORDED` (đã gồm dòng liên kết Charge); KHÔNG cộng Charge riêng.
- **Khóa**: mọi ghi vào folio đi qua khóa Stay (`findByIdForUpdate`/`findByReservationIdForUpdate`, cùng hàng): Charge và Payment (tạo mới, mark-paid, refund) khóa Stay TRƯỚC rồi kiểm tra lại `CHECKED_IN`; check-out, Stay Extension, Room Change cũng khóa Stay trước rồi mới khóa Room. Không đảo thứ tự khóa; không khóa toàn cục.
- **Không đổi/Hoãn**: Deposit/Prepayment (không phải doanh thu khi nhận, KHÔNG dùng AdditionalRevenue để biểu diễn; hoãn), sửa/đảo Charge, refund sau check-out, Payment âm, sổ cái, doanh thu đa tiền tệ, sửa lại doanh thu khi trả phòng sớm.

## 70. Deposit / Prepayment (P1, V1)

- **Thuật ngữ**: V1 chỉ có "Prepayment / Advance Payment" (Thanh toán trước) = tiền ĐÃ nhận cho một Reservation TRƯỚC khi check-in. KHÔNG có Security Deposit, card hold, thanh toán trước ở trạng thái chờ, lịch trả góp, phí hủy/no-show hay tịch thu (forfeiture).
- **Sở hữu**: mọi `Payment` thuộc vĩnh viễn đúng một Reservation (`payment.reservation_id` NOT NULL; V34 backfill từ `stay.reservation_id`). Trước check-in `stay_id` = NULL; khi check-in CHÍNH dòng đó nhận `stay_id` (không sao chép, không tạo Payment mới, không đổi amount/currency/appliedAmount/method/paidAt/reference).
  FK ghép `(stay_id, reservation_id)` → `stay(id, reservation_id)` bảo đảm Stay thuộc đúng Reservation; CHECK: Payment không có Stay chỉ có thể `PAID` hoặc `REFUNDED`. Không dùng trigger.
- **Ghi nhận** (`PrepaymentService.record`): chỉ Reservation `CONFIRMED` chưa có Stay; khóa dòng Reservation trước khi kiểm tra; ghi thẳng `PAID` (không có PENDING/FAILED trước check-in); dùng chung xác thực và quy đổi tiền tệ của `PaymentService` (appliedAmount theo `Reservation.currency`, tỷ giá 1 USD = rate VND);
  giữ nguyên `PaymentMethod` (không thêm phương thức "PREPAYMENT"; OTA vẫn bắt buộc reference). Nhiều khoản thanh toán trước được phép. Trần: Σ appliedAmount của các dòng PAID chưa gắn Stay + khoản mới ≤ `Reservation.totalAmount` (REFUNDED không tính; không có số dư có).
  Chống trùng ứng dụng (không có UNIQUE DB toàn cục): cùng Reservation + cùng method + reference (đã trim, không rỗng) với Payment chưa FAILED/REFUNDED bị từ chối; reference rỗng không kích hoạt.
- **Hoàn tiền**: hoàn TOÀN BỘ một khoản trước check-in (khóa Reservation, kiểm tra lại dưới khóa: dòng thuộc Reservation, chưa có Stay, PAID, Reservation vẫn CONFIRMED); dùng lại vòng đời `PAID → REFUNDED` (cần lý do); Reservation vẫn CONFIRMED; audit `REFUND_PAYMENT`. Không hoàn một phần.
- **Hủy / No-show**: bị TỪ CHỐI khi còn khoản thanh toán trước đang hiệu lực (PAID, chưa gắn Stay); nhân viên phải hoàn tiền trước. Không tự hoàn tiền, không tịch thu, không tạo doanh thu/phí/AdditionalRevenue.
- **Check-in**: tự động áp dụng các khoản thanh toán trước PAID bằng cách gắn CÙNG các dòng vào Stay mới (một truy vấn có khóa), audit `APPLY_PREPAYMENT` (số lượng, tổng); công thức folio giữ nguyên: Outstanding = Σ Charges − Σ Payments PAID. Thanh toán đủ trước → outstanding 0, check-out không đổi;
  gia hạn và Charge dịch vụ sau đó cộng vào outstanding như bình thường.
- **Doanh thu**: Payment/prepayment KHÔNG tạo doanh thu và không tạo Charge hay AdditionalRevenue; báo cáo tài chính không đổi.
- **Khóa vòng đời trước check-in**: thứ tự Rooms (sắp theo id) → hàng Reservation, cùng thứ tự với Room Reassignment. Check-in và Confirm khóa phòng rồi khóa Reservation và chỉ đọc trạng thái Reservation SAU khóa; Cancel, No-show, ghi nhận và hoàn thanh toán trước chỉ khóa Reservation. Stay vẫn là ranh giới tuần tự hóa của folio sau check-in (Stay → Rooms).
  Nhờ đó Check-in/Cancel/No-show/prepayment/refund được tuần tự hóa (trước đây Check-in và Cancel/No-show không khóa Reservation).
- **Quyền**: `MANAGE_PAYMENT` để xem/ghi nhận/hoàn thanh toán trước; Check-in (`CHECK_IN`) tự áp dụng các khoản đã ghi nhận, không cần `MANAGE_PAYMENT`. Reference chỉ là mã tham chiếu nhân viên nhìn thấy; KHÔNG lưu số thẻ, CVV hay thông tin đăng nhập ngân hàng.
- **Giao diện/Excel**: Reservation Detail (CONFIRMED, `MANAGE_PAYMENT`) có mục Prepayments (tổng đã nhận/đang hiệu lực/đã hoàn, lịch sử, Record/Refund); Check-in Review hiển thị tóm tắt chỉ đọc; sau check-in các dòng xuất hiện trong Folio thông thường. Sheet Payments của Excel lấy dữ liệu theo `Payment → Reservation` nên có cả thanh toán trước check-in (không đổi bố cục).
- **Hoãn**: Security Deposit, hoàn một phần, phí hủy/no-show, forfeiture, cổng thanh toán, card hold, số dư có/overpayment.

## 71. Overdue Departure (P1, V1)

- **Định nghĩa (dẫn xuất, không lưu)**: Reservation `CHECKED_IN` VÀ `Reservation.checkOutDate < hotelToday` (ngày khách sạn từ `Clock`). Không có trạng thái `OVERDUE`. `overdueDays = max(0, DAYS.between(checkOutDate, hotelToday))` (0 nếu trả phòng hôm nay hoặc sau; 1 nếu là hôm qua). Quy tắc nằm ở MỘT nơi (`OverdueDeparture`) và được Front Desk, Check-out Review và lệnh check-out dùng chung.
- **Check-out**: một stay quá hạn KHÔNG thể check-out, kể cả khi Outstanding = 0 (ví dụ đã thanh toán trước đủ). Điều kiện check-out gồm `checkOutDate >= hotelToday` VÀ Outstanding = 0 (và các điều kiện hiện có). Được kiểm tra trong `ReservationService.checkOut` dưới khóa Stay (Stay khóa trước, Reservation đọc sau khóa),
  nên REST/MVC/gọi service đều được bảo vệ như nhau. Từ chối là lỗi nghiệp vụ 409 ổn định ("Overdue stay must be extended before check-out", có bản dịch EN/VI trên MVC) và không thay đổi gì (Room vẫn OCCUPIED, assignment còn mở, không có `actualCheckOutAt`, Reservation vẫn CHECKED_IN).
  `DepartureReadinessRules` vẫn chỉ là readiness tài chính (Outstanding = 0); điều kiện đủ check-out = readiness tài chính + không quá hạn. Không có "Checkout Anyway", miễn phí, bỏ qua hay ép check-out.
- **Cách xử lý**: Stay Extension. Quy tắc ngày: `newCheckOutDate > currentCheckOutDate` VÀ `newCheckOutDate >= hotelToday`; stay quá hạn có thể gia hạn ĐÚNG đến hôm nay. Giai đoạn `[previousCheckOutDate, newCheckOutDate)` tính giá đêm gốc của `ReservationRoom` (22/09→23/09 = 1 đêm; 22/09→24/09 = 2 đêm).
  Sau khi gia hạn đến hôm nay, `checkOutDate == hotelToday` nên hết quá hạn và có thể check-out sau khi thanh toán phần Charge mới. Check-out không ghi đè `Reservation.checkOutDate`; giờ trả phòng thực tế nằm ở `Stay.actualCheckOutAt`.
- **Không có**: tự động gia hạn, tính tiền tự động, `ChargeType` mới, scheduler/night audit, miễn phí đêm quá hạn, phí trả phòng muộn theo giờ, giờ trả phòng cấu hình, phí phạt, thông báo tự động, tự chuyển phòng. Đêm quá hạn chỉ được tính tiền qua Stay Extension.
- **Không đổi**: bảo vệ tồn kho của assignment đang mở (đêm hiện tại khi quá hạn), Room Change (từ chối khi hôm nay ≥ ngày trả phòng dự kiến; sau khi gia hạn quá hôm nay thì cho phép), trạng thái phòng (OCCUPIED cho đến khi check-out hợp lệ rồi DIRTY), ghi nhận doanh thu theo hợp đồng/gia hạn, Occupancy và Room Type Performance theo assignment thực tế
  (occupancy có thể tạm đi trước doanh thu ghi nhận trong lúc chưa gia hạn; chênh lệch này được chấp nhận và bị chặn bởi việc bắt buộc gia hạn trước check-out).
- **Giao diện**: Front Desk giữ thứ tự (quá hạn trước), badge "Overdue Departure" và thêm "Overdue N day(s)"; Check-out Review có mục quá hạn nổi bật (ngày dự kiến, hôm nay, số ngày, giải thích chặn check-out, nút Extend Stay khi có quyền `EXTEND_STAY`, ngược lại thông báo nhờ quản lý/nhân viên có quyền) và không có nút xác nhận check-out.
  Quyền Stay Extension là `EXTEND_STAY` (mục 72); STAFF không có `MANAGE_BOOKING`.

## 72. Permission EXTEND_STAY (Stay Extension Permission Refinement)

- **`EXTEND_STAY`** = quyền gia hạn một stay `CHECKED_IN` (dời ngày trả phòng dự kiến) qua thao tác Stay Extension hiện có, cho CẢ gia hạn thông thường lẫn xử lý stay quá hạn (mục 71). Đây là quyền vận hành trong stay đang hoạt động, tách biệt với `MANAGE_BOOKING`, `CHECK_OUT` và `CHANGE_ROOM`; không có quyền riêng "chỉ quá hạn".
- **Cấp mặc định** (migration V35, id `...0117`, idempotent theo mẫu V23): ADMIN, MANAGER và STAFF, cấp tường minh, KHÔNG suy ra từ `MANAGE_BOOKING` và không chạm grant tùy biến khác. STAFF là vai trò vận hành lễ tân (check-in, check-out, thanh toán, đổi phòng, gia hạn) và KHÔNG có `MANAGE_BOOKING`.
- **Ủy quyền**: Stay Extension được bảo vệ CHỈ bởi `EXTEND_STAY` ở MVC GET/POST `/reservations/{id}/stay-extension` và REST `POST /api/reservations/{id}/stay-extension`. `MANAGE_BOOKING` một mình KHÔNG đủ; không có phân cấp hay "MANAGE_BOOKING hoặc EXTEND_STAY". Ẩn nút chỉ là tiện ích, việc chặn nằm ở server.
- **Giao diện**: hiển thị "Extend Stay" theo `EXTEND_STAY` ở Reservation Detail (CHECKED_IN), Front Desk (In-house/Departures) và Check-out Review quá hạn; user có `CHECK_OUT` nhưng không có `EXTEND_STAY` thấy thông báo nhờ quản lý/nhân viên có quyền. Quyền hiện trong ma trận Roles & Permissions (nhóm Reservations) để ADMIN cấp/thu hồi.
- **Không đổi**: mọi kiểm tra nghiệp vụ (trạng thái, ngày mới > hiện tại và >= hôm nay, tồn kho, khóa), giá đêm gốc của `ReservationRoom`, lịch sử, Charge ROOM và audit `EXTEND_STAY` (ghi người thực hiện). Quyền không bỏ qua bất kỳ quy tắc nào và không cho nhập giá.

## 73. Controlled CONFIRMED Reservation Modification (V1)

Reservation `CONFIRMED` chưa có Stay có đúng hai thao tác sửa chuyên biệt mới; đây KHÔNG phải màn hình hay API sửa Reservation tổng quát. Draft Edit, Update Guest Composition, Room Reassignment, Cancel, No-show, Prepayment và Check-in vẫn là các thao tác độc lập và giữ nguyên hành vi.

- **Ranh giới vòng đời và quyền**: cả hai thao tác chỉ cho phép khi `Reservation.status = CONFIRMED` và không tồn tại Stay, dùng quyền hiện có `MANAGE_BOOKING` ở MVC và REST. Backend kiểm tra lại điều kiện dưới khóa; việc ẩn nút chỉ là hướng dẫn UI. Khi Stay đã tồn tại, `ReservationRoom` tiếp tục là snapshot đặt phòng/giá gốc bất biến và không thao tác nào trong mục này được phép chạy.
- **Change Reservation Dates**: chỉ nhận `newCheckInDate`, `newCheckOutDate`; yêu cầu ngày nhận mới `>= hotelToday` từ `Clock` khách sạn và ngày trả mới `>` ngày nhận mới. Reservation đến trễ có thể được phục hồi bằng cách dời ngày nhận tới hôm nay hoặc tương lai, không được giữ ngày nhận trong quá khứ.
- **Phòng và giá khi đổi ngày**: giữ nguyên chính các dòng `ReservationRoom`, Room, `nightlyRate` và currency; không gọi rate engine và không cho nhập giá. Cập nhật ngày trên Reservation và mọi ReservationRoom; với `nightCount = DAYS.between(newCheckInDate, newCheckOutDate)`, mỗi dòng có `totalAmount = nightlyRate × nightCount`, còn `Reservation.totalAmount` là tổng các dòng.
- **Tồn kho**: dùng primitive dùng chung của `RoomAvailabilityService` với khoảng nửa mở `[checkInDate, checkOutDate)`. Date Change truyền `excludedReservationId` tường minh để chỉ bỏ qua các `ReservationRoom` của chính Reservation đang sửa; Reservation khác và mọi `StayRoomAssignment` đang hoạt động vẫn gây xung đột. Các caller khác không tự động được loại trừ Reservation.
- **Khóa và nguyên tử**: khóa các Room liên quan theo thứ tự id ổn định rồi khóa Reservation, sau đó đọc/kiểm tra lại trạng thái, Stay, tập Room, tồn kho và tiền thanh toán trước. Date Change được tuần tự hóa với Check-in, Room Reassignment, Prepayment recording và Date Change khác; thay đổi ngày, tổng tiền và audit cùng thành công hoặc cùng rollback.
- **Bất biến prepayment**: dùng đúng định nghĩa hiện có về tổng prepayment PAID đang hiệu lực. Tổng đó phải `<= proposedReservationTotal`; nếu rút ngắn làm tổng mới nhỏ hơn khoản đã thu thì từ chối, không tự hoàn tiền/sửa Payment và không ghi audit thành công. Nhân viên phải hoàn phần vượt trước rồi thử lại. Kiểm tra luôn chạy dù người gọi không có `MANAGE_PAYMENT`; UI và lỗi không tiết lộ số tiền cho người không có quyền tài chính.
- **Correct OTA Booking Reference**: chỉ nhận và thay đổi `Reservation.otaBookingReference`, chỉ cho source khác `DIRECT`; giữ nguyên source và legacy `externalBookingId`. Dùng lại ngữ nghĩa hiện có: bắt buộc giá trị không blank đối với OTA, tối đa 255 ký tự, giữ nguyên whitespace của giá trị không blank và không thêm uniqueness.
- **Audit**: Date Change ghi một `AuditLog` action `CHANGE_RESERVATION_DATES` với ngày nhận/trả và tổng Reservation trước/sau. OTA correction ghi `CORRECT_OTA_REFERENCE` với reference trước/sau. Thao tác thất bại không có audit thành công.
- **Giao diện và read model**: Reservation Detail chỉ hiển thị action đủ điều kiện theo trạng thái, Stay, source và quyền; form là narrow form, dùng i18n EN/VI và không mở các trường Guest, source, currency, số Reservation, `reservedAt`, Room, giá, `externalBookingId` hay notes. Front Desk tự đọc ngày Reservation mới; Housekeeping next-arrival tự đọc ngày ReservationRoom đã đồng bộ; không lưu readiness/cache mới và không đổi báo cáo hay lịch sử Stay.

## 74. Booking Contact + Reservation Notes (V1)

Reservation có ba trường snapshot mới `bookingContactName`, `bookingContactPhone`, `bookingContactEmail`, và
`Reservation.notes` hiện có được mở rộng phạm vi chỉnh sửa. Đây KHÔNG phải Guest, không phải entity riêng, không
phải lịch sử nhiều phiên bản, và không phải CRM.

- **Mô hình Booking Contact**: là snapshot ba trường trên Reservation, độc lập hoàn toàn với Primary Guest,
  Accompanying Guests, `otaBookingReference` và `externalBookingId`. Booking Contact KHÔNG bắt buộc phải là một
  Guest; mỗi trường tùy chọn độc lập và Reservation hợp lệ khi cả ba đều rỗng/`null`. Không thêm ràng buộc
  định dạng/độ dài chặt hơn Guest hiện có (`bookingContactPhone` ≤ 100 ký tự, `bookingContactEmail` ≤ 255 ký tự
  như Guest; `bookingContactName` ≤ 200 ký tự).
- **Mặc định từ Primary Guest**: khi tạo Reservation mới, mỗi trường Booking Contact rỗng/`null` được sao chép
  một lần từ Primary Guest tương ứng (`firstName + lastName`, `phone`, `email`) tại thời điểm tạo; giá trị được
  nhập tường minh (không rỗng) luôn được giữ nguyên. Đây là bản sao (snapshot), KHÔNG phải tham chiếu sống: sau
  khi tạo, sửa hồ sơ Guest không tự đổi Booking Contact đã lưu, và sửa Booking Contact không đổi Guest. Draft Edit
  và thao tác sửa Booking Contact có kiểm soát KHÔNG áp lại mặc định này — giá trị rỗng khi sửa nghĩa là xóa, đổi
  Primary Guest trong Draft Edit không tự đồng bộ lại Booking Contact.
- **Dự phòng khi đọc (không ghi)**: với Reservation không có snapshot Booking Contact (cả ba trường rỗng, gồm dữ
  liệu lịch sử trước tính năng này), màn hình đọc (Reservation Detail, Front Desk Arrivals) hiển thị thông tin
  Primary Guest hiện tại làm dự phòng hiển thị. Dự phòng này KHÔNG được ghi vào Reservation, không tạo Guest,
  không sinh audit, và không được gán nhãn như dữ liệu snapshot lịch sử; Reservation Detail hiển thị rõ khi đang
  dùng dự phòng. Không có migration backfill dữ liệu lịch sử vì không xác định được Primary Guest lịch sử có
  đúng là người liên hệ đặt phòng hay không.
- **Vòng đời/quyền Booking Contact**: sửa được khi `Reservation.status` là DRAFT, CONFIRMED hoặc CHECKED_IN; bị
  từ chối khi CHECKED_OUT, CANCELLED hoặc NO_SHOW. Không yêu cầu "không có Stay" như mục 73 — Booking Contact độc
  lập với Room/giá/Stay. Xem dùng `VIEW_BOOKING` (không yêu cầu `MANAGE_GUEST`, để STAFF vận hành lễ tân biết
  liên hệ mà không cần quyền quản lý Guest); sửa dùng `MANAGE_BOOKING` hiện có ở MVC và REST. Backend kiểm tra
  lại vòng đời dưới khóa Reservation (`PESSIMISTIC_WRITE`, không khóa Room); ẩn nút chỉ là hướng dẫn UI.
- **Reservation Notes**: giữ nguyên `Reservation.notes` hiện có (ghi chú nội bộ/vận hành, tối đa 5000 ký tự,
  được phép rỗng/`null`, không có category/attachment/trường khách-hàng-thấy riêng). Sửa được (thao tác có kiểm
  soát mới, chỉ đổi đúng `notes`) khi DRAFT, CONFIRMED hoặc CHECKED_IN, cùng ranh giới vòng đời và quyền
  (`VIEW_BOOKING` để xem, `MANAGE_BOOKING` để sửa) như Booking Contact; bị từ chối khi CHECKED_OUT, CANCELLED
  hoặc NO_SHOW. Draft Edit hiện có (chỉ khi DRAFT) không đổi.
- **Khóa và nguyên tử**: cả hai thao tác chỉ khóa Reservation (`PESSIMISTIC_WRITE`), không khóa Room, theo đúng
  mẫu của Correct OTA Booking Reference (mục 73) — nhỏ nhất đủ an toàn vì không đụng Room/tồn kho/giá/thanh
  toán. Nhờ vậy tuần tự hóa an toàn với Check-in, Cancel, No-show, Date Change, Guest Composition update và một
  thao tác Contact/Notes khác, không đảo thứ tự khóa Rooms-rồi-Reservation của các thao tác kia.
- **Audit — quy tắc riêng tư bắt buộc**: Booking Contact update ghi một `AuditLog` action
  `UPDATE_BOOKING_CONTACT`; `oldValue` rỗng, `newValue` chỉ chứa `changedFields=<danh sách trường đã đổi>` (ví dụ
  `changedFields=phone`), KHÔNG BAO GIỜ chứa tên/số điện thoại/email thật. Notes update ghi action
  `UPDATE_RESERVATION_NOTES` với `oldValue`/`newValue` đều rỗng — chỉ định danh thao tác (ai, khi nào, Reservation
  nào) là đủ, không lưu nội dung notes vào audit. Thao tác thất bại (vòng đời đóng) không ghi audit thành công.
- **Giao diện**: Booking Contact xuất hiện ở Create Reservation, Draft Edit và Reservation Detail (cùng action
  sửa có kiểm soát khi vòng đời/quyền cho phép); Front Desk Arrivals hiển thị tối thiểu số điện thoại liên hệ
  hiệu lực (snapshot hoặc dự phòng Primary Guest) cho STAFF có `VIEW_BOOKING`, không cần `MANAGE_GUEST`. Notes
  hiển thị ở Reservation Detail với action sửa có kiểm soát cùng điều kiện; không mở rộng Front Desk/Check-in
  Review/Stay ngoài phạm vi tối thiểu này.
- **Không đổi**: Primary Guest, Accompanying Guests, adult/child, Source, `otaBookingReference`,
  `externalBookingId`, Date Change, Room Reassignment, Prepayment, Check-in, Room Change, Stay Extension,
  Checkout, Housekeeping, báo cáo/export (Monthly Financial/Occupancy/Performance Excel/PDF) — không trường nào
  trong mục này được thêm vào báo cáo. Không tạo entity `BookingContact`/lịch sử contact/`ReservationNote` mới,
  không CRM, không company/travel-agent profile, không channel manager, không messaging/email/SMS, không nhiều
  Booking Contact trên một Reservation.

## 75. Cancellation Reason + No-show Guard/Reason (V1)

Reservation có thêm ba trường mới: `cancellationReasonCode`, `cancellationReasonDetail` (khi hủy), và
`noShowReason` (khi đánh dấu no-show). Đây KHÔNG phải cancellation fee engine, KHÔNG tự động tịch thu tiền, KHÔNG
tự động chuyển NO_SHOW theo lịch, và KHÔNG có bảng lịch sử/entity lý do riêng.

- **State machine không đổi**: `Cancel` (CONFIRMED → CANCELLED) và `No-show` (CONFIRMED → NO_SHOW) vẫn chỉ khả
  dụng từ CONFIRMED; mọi trạng thái khác tiếp tục bị từ chối với lỗi 409 hiện có. Không thêm transition, không
  bypass state validation.
- **Cancellation Reason — bắt buộc**: mọi lần `Cancel` mới phải cung cấp `cancellationReasonCode` (enum
  `CancellationReasonCode`: `GUEST_REQUEST`, `CHANGE_OF_PLANS`, `DUPLICATE_BOOKING`, `PAYMENT_ISSUE`,
  `HOTEL_OPERATIONAL`, `OTA_CANCELLATION`, `OTHER`). `cancellationReasonDetail` là tùy chọn, TRỪ khi
  `cancellationReasonCode = OTHER` thì bắt buộc không rỗng (kiểm tra ở Bean Validation, service, và một
  DB CHECK constraint). `OTA_CANCELLATION` chỉ có nghĩa "PMS ghi nhận hủy vì OTA đã hủy đặt phòng" — KHÔNG kéo
  theo OTA synchronization, channel manager, hay tích hợp API nào.
- **No-show Reason — bắt buộc**: mọi lần `No-show` mới phải cung cấp `noShowReason` (free text bắt buộc, không
  category/enum). Không đại diện cho việc biết chắc lý do thật của khách; chỉ là lời giải thích vận hành của
  nhân viên (ví dụ "Guest did not arrive and could not be contacted.").
- **No-show Temporal Guard — bất biến nghiệp vụ bắt buộc**: chỉ được đánh dấu NO_SHOW khi
  `reservation.checkInDate < hotelToday` (hotel Clock hiện có, KHÔNG dùng `LocalDate.now()` trần). Arrival hôm
  nay (`checkInDate == hotelToday`) và arrival tương lai (`checkInDate > hotelToday`) đều bị từ chối (409, message
  key `reservation.noShow.error.notEligible`). Không có check-in cutoff giờ trong ngày (không có 18:00, 22:00,
  23:59, midnight, hay giờ đến dự kiến) — repository chỉ có ngữ nghĩa `LocalDate`.
- **Quyền không đổi**: cả hai thao tác tiếp tục yêu cầu `MANAGE_BOOKING` (ADMIN/MANAGER), giống hệt trước khi có
  tính năng này. STAFF KHÔNG có quyền Cancel hay No-show trước đây và vẫn không có sau tính năng này — không
  permission mới được tạo.
- **Prepayment không đổi**: active PAID prepayment (chưa gắn Stay) tiếp tục chặn cả Cancel và No-show; nhân viên
  phải hoàn tiền trước ("refund first" giữ nguyên). Không có forfeiture, không có phí hủy/no-show tự động.
- **Bất biến sau khi chuyển trạng thái**: lý do được ghi đúng một lần tại thời điểm transition (CANCELLED/NO_SHOW
  là trạng thái cuối, không có transition quay lại nên không có đường sửa lại lý do); không có API sửa lý do
  riêng, không có entity lịch sử lý do.
- **Dữ liệu lịch sử**: ba cột mới NULLABLE. Reservation CANCELLED/NO_SHOW đã tồn tại trước migration V37 giữ
  NULL vĩnh viễn — không backfill, không suy đoán giá trị UNKNOWN/OTHER/legacy. Reservation Detail hiển thị
  trạng thái "No reason recorded" trung tính cho các dòng lịch sử này thay vì bịa lý do.
- **AuditLog — quy tắc riêng tư giữ nguyên**: `CANCEL` và `NO_SHOW` tiếp tục chỉ ghi state transition
  (`oldValue`/`newValue` là tên trạng thái trước/sau), giống mọi action hiện có. `cancellationReasonDetail` và
  `noShowReason` KHÔNG BAO GIỜ được sao chép vào AuditLog, theo đúng nguyên tắc riêng tư đã áp dụng cho Booking
  Contact/Notes (mục 74) và Room Change (mục 8.3).
- **Tồn kho không đổi**: `ReservationRoom` tiếp tục được giữ nguyên làm snapshot lịch sử; không xóa, không đổi
  `Room.status`. Availability tiếp tục tự do vì conflict query chỉ tính Reservation `CONFIRMED`, không cần dọn
  dẹp thủ công.
- **Khóa/transaction không đổi**: Cancel/No-show tiếp tục chỉ khóa Reservation (`PESSIMISTIC_WRITE`), không khóa
  Room, cùng transaction với việc ghi lý do — không có lock mới, không đổi thứ tự khóa hiện có với Check-in,
  Prepayment/refund, Date Change, Guest Composition, Room Reassignment, Booking Contact/Notes.
- **Giao diện — tối thiểu**: form Cancel trên Reservation Detail thêm dropdown lý do (bắt buộc) và ô chi tiết
  (bắt buộc khi OTHER); form No-show thêm ô lý do bắt buộc. Reservation Detail hiển thị lý do đã lưu cho
  Reservation CANCELLED/NO_SHOW. Không có màn hình mới, không đổi Front Desk, không đổi báo cáo
  (Monthly Financial/Occupancy/Performance Excel/PDF) — cancellation/no-show reason analytics là V2.

## 76. V2 / Out of Scope (Cancellation Reason + No-show)

Hoãn tới V2, không triển khai trong mục 75: cancellation fee engine, automatic prepayment forfeiture, automatic
no-show scheduler/night audit, configurable check-in cutoff, same-day no-show override, OTA cancellation
synchronization, channel manager, cancellation/no-show analytics dashboard, generalized lifecycle-event/reason
framework dùng chung nhiều thao tác, reservation reinstatement.

## 77. Room Images (V1)

Ảnh gắn với phòng vật lý (`Room`), KHÔNG gắn với `RoomType`: hai phòng cùng RoomType (ví dụ hai phòng DOUBLE)
có thể có ảnh khác nhau. Đây KHÔNG phải marketing gallery công khai, KHÔNG phải RoomType imagery, và KHÔNG
đồng bộ với website khách sạn hay OTA trong V1.

- **Cardinality**: một Room có 0..10 ảnh. Room không có ảnh là hợp lệ (không bắt buộc). Upload nhiều ảnh một
  lúc được hỗ trợ; tổng số ảnh sau khi upload (số hiện có + số file mới) không được vượt quá 10, kiểm tra
  TRƯỚC khi lưu bất kỳ file nào — vượt giới hạn thì từ chối toàn bộ batch, không lưu một phần.
- **Primary image**: khi Room có 0 ảnh thì có 0 ảnh primary; khi có từ 1 ảnh trở lên thì có ĐÚNG MỘT ảnh
  primary. Ảnh đầu tiên upload thành công cho một Room tự động là primary; các ảnh sau đó (kể cả trong cùng
  một batch) không tự động là primary. Nhân viên có thể đổi ảnh primary bất kỳ lúc nào. Xóa ảnh primary tự
  động thăng ảnh CÒN LẠI CŨ NHẤT (theo `createdAt`, rồi `id`) lên làm primary; nếu không còn ảnh nào, Room có 0
  ảnh primary. Bất biến "tối đa một primary mỗi Room" được thực thi bằng partial unique index PostgreSQL trên
  `room_image (room_id) WHERE is_primary`, cùng mẫu với `ux_stay_room_assignment_open_room` (mục 22). Không có
  `display_order`/sắp xếp thủ công trong V1.
- **Validation**: định dạng JPG/JPEG/PNG; tối đa 5 MB mỗi file (giống Passport Image); tên file tối đa 255 ký
  tự; từ chối file rỗng (0 byte). Khác với Passport Image hiện có (chỉ tin đuôi file và Content-Type do trình
  duyệt khai báo), Room Image V1 giải mã và xác minh NỘI DUNG THỰC của file bằng bộ đọc ảnh JDK có sẵn
  (`javax.imageio`), từ chối file không thực sự là JPEG/PNG hợp lệ dù tên file và Content-Type khai báo đúng
  định dạng. Đây là cải tiến bảo mật chỉ áp dụng cho Room Image; Passport Image giữ nguyên hành vi hiện có,
  không thay đổi trong phạm vi mục này.
- **Lưu trữ**: KHÔNG lưu nhị phân trong PostgreSQL. Ảnh lưu trên một thư mục riêng, cấu hình qua
  `hotel.storage.room-images.path`, TÁCH BIỆT hoàn toàn với thư mục Passport Image
  (`hotel.storage.guest-documents.path`) — hai domain không bao giờ chia sẻ thư mục hay storage key. Storage
  key do server sinh (UUID + đuôi file đã xác thực), không bao giờ dùng tên file của client làm đường dẫn.
  Serving qua endpoint riêng, xác thực (KHÔNG public/static, KHÔNG unauthenticated), xác minh lại
  Room-sở-hữu-ảnh mỗi lần đọc (không có IDOR: URL Room A kết hợp id ảnh của Room B không bao giờ trả về ảnh).
- **Xử lý ảnh**: chỉ lưu ảnh gốc. V1 KHÔNG resize, KHÔNG nén, KHÔNG tạo thumbnail file riêng, KHÔNG WebP,
  KHÔNG xử lý EXIF/orientation. Giao diện có thể hiển thị ảnh gốc ở kích thước nhỏ bằng CSS/HTML thông thường.
- **Quyền**: `MANAGE_ROOM` (permission Room Management hiện có) bảo vệ xem/upload/xóa/đổi primary trong Room
  Management. Không tạo permission mới; không có `VIEW_ROOM`. Không mở rộng quyền của STAFF.
- **Vòng đời Room**: ảnh độc lập với `Room.status` — giữ nguyên ảnh khi Room ở AVAILABLE, OCCUPIED, DIRTY,
  CLEANING, MAINTENANCE, hoặc OUT_OF_ORDER. Room hiện chưa có thao tác xóa (hard delete); V1 không định nghĩa
  hành vi dọn ảnh khi xóa Room vì thao tác đó chưa tồn tại.
- **Audit**: `RoomImage` dùng audit fields chuẩn (`createdAt/By`, `updatedAt/By`) như mọi entity khác. Upload/
  xóa/đổi primary KHÔNG ghi `AuditLog` riêng trong V1 (giống cách Passport Image hiện có không ghi AuditLog
  cho upload/remove).
- **Giao diện**: chỉ thêm mục Images tối thiểu trên Room Detail (xem danh sách, đánh dấu ảnh primary, upload,
  đổi primary, xóa). Không có ảnh trên Room List, không đổi Reservation room picker, Pre-check-in Room
  Reassignment, Check-in Review, Stay Room Change, Front Desk, Housekeeping, hay báo cáo — trình bày ở các màn
  hình đó thuộc phạm vi Task33 (nếu được duyệt sau này), không triển khai ở đây.

## 78. V2 / Out of Scope (Room Images)

Hoãn tới V2, không triển khai trong mục 77: public hotel-website media serving, CDN, S3/object storage,
thumbnail/resize/compression pipeline, WebP conversion, xử lý EXIF, sắp xếp ảnh thủ công (display order),
RoomType marketing imagery, đồng bộ ảnh với OTA, channel manager, video upload, caption/tag ảnh, bulk
cross-room media management, sửa lỗi actual-content-validation cho Passport Image, AuditLog riêng cho thao
tác Room Image.

## 79. Operational Timeline / Activity (V1)

Reservation Detail có thêm mục "Activity": danh sách hoạt động của một Reservation theo thứ tự thời gian, trả
lời "chuyện gì / khi nào / ai làm". KHÔNG phải sổ kế toán, KHÔNG phải event sourcing, KHÔNG phải audit log
console chung, KHÔNG phải event bus tích hợp hệ thống khác.

- **Nguồn dữ liệu**: đọc trực tiếp từ bảng `audit_log` hiện có, lọc `entity_type='RESERVATION' AND
  entity_id=:reservationId`, sắp theo `created_at ASC, id ASC`. KHÔNG thêm bảng timeline/event riêng, KHÔNG
  thêm cột `journeyId`/`correlationId`/`reservationId` vào `audit_log`, KHÔNG cần migration: mọi hành động đã
  ghi audit trong vòng đời Reservation/Stay/Room Change/Stay Extension/Prepayment/Payment đều đã ghi
  `entity_type='RESERVATION'` với `entity_id` là chính Reservation, nên một truy vấn theo Reservation là đủ.
- **Hai audit event nghiệp vụ mới**: `RECORD_CHARGE` (ghi khi tạo Charge thủ công qua `ChargeService.create`,
  KHÔNG ghi cho ROOM charge tự động tạo bởi Check-in hay Stay Extension — hai luồng đó đã có sự kiện
  `CHECK_IN`/`EXTEND_STAY` riêng, tránh trùng lặp) và `RECORD_PAYMENT` (ghi khi một Payment thường trở thành
  PAID qua `PaymentService.recordPaid` hoặc `PaymentService.markPaid`; KHÔNG ghi khi tạo Payment còn PENDING).
  `RECORD_PREPAYMENT`, `APPLY_PREPAYMENT`, `REFUND_PAYMENT` giữ nguyên, không trùng với `RECORD_PAYMENT` cho
  cùng một thao tác. Ghi AuditLog trong CÙNG transaction với thao tác nghiệp vụ; rollback thao tác thì AuditLog
  cũng rollback.
- **AuditLog KHÔNG phải nguồn sự thật tài chính**: số tiền/trạng thái tài chính hiện tại luôn đọc từ bảng
  `charge`/`payment`, KHÔNG parse `AuditLog.oldValue`/`newValue` để tính toán.
- **Không hiển thị giá trị thô**: `AuditLog.oldValue`/`newValue` KHÔNG bao giờ đưa thẳng ra giao diện. Mỗi hành
  động được ánh xạ sang một nhãn đã dịch (message key `reservation.activity.action.*`), hành động không xác
  định (tương lai hoặc dữ liệu cũ) rơi về nhãn chung an toàn, không làm hỏng trang.
- **Quyền xem**: theo quyền xem Reservation Detail hiện có (`VIEW_BOOKING`). Không tạo permission mới. Người
  dùng chỉ có `VIEW_BOOKING` (không có `MANAGE_PAYMENT`) thấy được một hoạt động tài chính đã xảy ra (ví dụ
  "Payment recorded") nhưng KHÔNG thấy số tiền/phương thức/tham chiếu thanh toán kèm theo trong V1.
- **i18n**: nhãn dịch tại thời điểm hiển thị (render-time) qua hạ tầng EN/VI hiện có, giống mọi nhãn khác
  trong hệ thống; AuditLog tiếp tục lưu mã hành động ổn định (`CONFIRM`, `CHECK_IN`, `RECORD_PAYMENT`, …).
- **Không backfill lịch sử**: chỉ hiển thị các dòng AuditLog thực sự tồn tại; Reservation không có hoạt động
  nào được ghi thì hiển thị danh sách rỗng, không tự suy diễn/tạo sự kiện giả cho lịch sử trước khi tính năng
  này tồn tại.
- **Không phân trang trong V1**: tải toàn bộ lịch sử của một Reservation cho trang chi tiết (quy mô 10-20
  phòng), không có truy vấn timeline toàn cục.
- **Không phải event bus tích hợp**: `AuditLog`/Activity Timeline vẫn là dữ liệu nội bộ vận hành. Nếu tương lai
  cần tích hợp hệ thống khác (RMS, Booking Engine, Channel Manager), phải thiết kế cơ chế outbox/event/API
  riêng, không tái sử dụng bảng này.

## 80. V2 / Out of Scope (Operational Timeline)

Hoãn tới V2, không triển khai trong mục 79: màn hình Audit Log toàn cục, bộ lọc/tìm kiếm/export hoạt động,
phân trang, chính sách lưu trữ/retention, hiển thị số tiền/phương thức thanh toán chi tiết cho người chỉ có
`VIEW_BOOKING`, actor SYSTEM cho hành động tự động, snapshot tên người dùng trên AuditLog, ghi AuditLog cho
Guest/Room/Housekeeping/Room Image/Staff/User/Role/Expense/AdditionalRevenue, cơ chế event/outbox tích hợp hệ
thống khác.

## 81. Charge / Payment Void Correction (V1)

Bổ sung cách kiểm soát để sửa sai nghiệp vụ (nhập sai số tiền, sai phương thức, trùng lặp) mà không sửa/xóa
bản ghi tài chính gốc. Đây KHÔNG phải sửa trực tiếp, KHÔNG phải hard delete, KHÔNG phải Charge/Payment âm, và
KHÔNG mở rộng phạm vi ngoài Charge/Payment thủ công của một Stay đang `CHECKED_IN`.

- **Khái niệm bắt buộc phân biệt**: `REFUND` = tiền đã thực nhận và sau đó trả lại khách (giữ nguyên hành vi
  hiện có, `PAID → REFUNDED`, lý do lưu ở `refund_reason`). `VOID` = bản ghi bị sai; số tiền đại diện chưa bao
  giờ thực sự di chuyển (chưa từng nhận, hoặc chưa từng trả). Hai khái niệm này không bao giờ được lẫn vào
  nhau trong dữ liệu đã lưu; `VOID` không tái sử dụng `refund_reason`/`REFUNDED`.
- **Charge**: thêm vòng đời `status` (`ACTIVE` mặc định, `VOIDED` là trạng thái cuối, chuyển đúng một chiều
  `ACTIVE → VOIDED`). Chỉ Charge thủ công KHÔNG PHẢI `ROOM` mới được void; ROOM Charge tự động (check-in gốc
  và Stay Extension) không bao giờ được void trực tiếp trong V1 — chặn ở service/domain, không chỉ ẩn ở UI.
  Void yêu cầu lý do dạng free text bắt buộc (không rỗng sau khi trim), lưu ở `charge.void_reason`; không có
  `voidedAt`/`voidedBy` riêng — `updated_at`/`updated_by` (audit hiện có) là timestamp/actor void có thẩm
  quyền, cùng cách REFUNDED của Payment không có `refundedAt`/`refundedBy` riêng. Charge bị void vẫn tồn tại
  vật lý, không bị sửa/xóa.
- **Charge ↔ Additional Revenue nguyên tử**: void một Charge dịch vụ thủ công (đã liên kết Additional Revenue
  hệ thống quản lý, xem mục 69) đồng thời void Additional Revenue liên kết trong CÙNG transaction; nếu thiếu
  hoặc không nhất quán, toàn bộ thao tác bị từ chối và rollback, không để lệch trạng thái Folio/báo cáo. Đây
  là cơ chế nội bộ riêng cho thao tác void Charge; quy tắc hiện có "Additional Revenue liên kết Charge không
  sửa/void độc lập" (mục 69) không đổi cho bất kỳ luồng nào khác.
- **Payment**: thêm trạng thái cuối `VOIDED` bên cạnh `PENDING/PAID/FAILED/REFUNDED`. Transition mới duy nhất:
  `PAID → VOIDED`. Không có transition ra khỏi `VOIDED`, và không có transition giữa `VOIDED` và `REFUNDED`
  theo bất kỳ chiều nào. Void yêu cầu lý do bắt buộc lưu ở cột riêng `payment.void_reason` (không dùng chung
  `refund_reason`), theo đúng quy ước bắt buộc lý do khi `REFUNDED` đã có ở mục 11. Payment bị void vẫn tồn
  tại vật lý.
- **Prepayment**: một prepayment PAID (chưa gắn Stay) ghi sai (sai số tiền, sai phương thức, trùng) có thể
  void bằng thao tác riêng, phân biệt với hoàn tiền hiện có (mục 70). Sau khi void: không còn tính vào tổng
  prepayment đang hiệu lực/trần thanh toán trước, không còn chặn Cancel/No-show, và cùng phương thức+reference
  đó có thể dùng lại ngay cho một prepayment ghi đúng. Giữ nguyên sở hữu Reservation hiện có; không copy/tạo
  lại dòng.
- **Số dư (Outstanding)**: `Total Charges = SUM(ACTIVE Charge.amount)` (trước đây là mọi Charge); `Total
  Payments` giữ nguyên `SUM(appliedAmount WHERE status = PAID)` (VOIDED vốn đã không nằm trong `PAID` nên
  không cần đổi công thức Payment). Bộ lọc `ACTIVE` áp dụng tập trung tại tầng truy vấn dùng chung bởi
  `StayBalanceService`, không cài đặt lại rải rác ở controller/query service khác. Doanh thu phòng (mục 69)
  không đổi.
- **Sau CHECKED_OUT**: không được void Charge, không được void Payment, không mở lại Folio, không thay đổi
  hành vi Refund hiện có. Ranh giới bất biến tài chính sau check-out (mục 8.2, 12) giữ nguyên.
- **Quyền**: dùng lại `MANAGE_PAYMENT` cho void Charge, void Payment, và void prepayment lỗi — không có
  permission mới, không đổi role-permission mapping hiện có.
- **AuditLog**: hai action mới `VOID_CHARGE`, `VOID_PAYMENT` (entityType `RESERVATION`, cùng quy ước hiện có).
  `VOID_PAYMENT` dùng chung cho void Payment thường và void prepayment, giống cách `REFUND_PAYMENT` đã dùng
  chung cho cả hai ngữ cảnh. Không lưu lý do void thô, không lưu số tiền/phương thức/tham chiếu vào
  `old_value`/`new_value`.
- **Operational Timeline**: nhãn đã dịch `reservation.activity.action.VOID_CHARGE`/`VOID_PAYMENT` (EN/VI),
  theo đúng quy ước ẩn giá trị tài chính hiện có (mục 79) — không hiển thị số tiền, lý do, hay giá trị
  `AuditLog` thô.
- **Không đổi/Hoãn**: sửa trực tiếp amount/type/description/quantity/unitPrice/nguồn gốc Charge; hard delete;
  Charge/Payment âm; hoàn tiền một phần; sửa sau CHECKED_OUT; mở lại Folio; workflow phê duyệt quản lý;
  permission void riêng; accounting period; general ledger/AccountingEntry; tích hợp kế toán ngoài; sửa
  Standalone Additional Revenue (giữ nguyên hành vi/permission `MANAGE_ADDITIONAL_REVENUE` hiện có); sửa
  Expense; sửa ROOM Charge/`ReservationRoom`/`StayExtensionRoom`; FK lineage thay thế/hiệu chỉnh
  (`replacementOf`/`correctionOf`); tự động tạo bản ghi đúng thay thế (staff phải tự ghi lại sau khi void).

**End of Specification v1.0**
