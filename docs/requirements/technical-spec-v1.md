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

Không implement automatic tax calculation, discount calculation, negative Charge behavior, currency-conversion, hoặc advanced rounding behavior trong current scope. Detailed behavior sẽ được định nghĩa trong future specification change.

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
amount = quantity * unitPrice
amount > 0
```

Với ITEMIZED Charge mới được tạo, backend phải authoritative khi tính `amount = quantity * unitPrice`. Client chỉ cung cấp `quantity` và `unitPrice`; client không được independently determine authoritative calculated `amount`.

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

UI entry phải làm rõ hai mode: ITEMIZED entry nhận `quantity` và `unitPrice` rồi application calculates `amount`; FIXED AMOUNT entry nhận `amount` và không nhận `quantity` hoặc `unitPrice`. UI không được encourage user nhập ba independent monetary/calculation values. Không quy định JavaScript behavior trong specification này.

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

Fields:

```text
id
stayId
amount
method
status
paidAt
reference
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
method is required
reference is optional for all Payment methods
reference has no method-specific format or uniqueness rule in Payment v1
audit fields are backend-controlled
```

Client chỉ được provide:

```text
amount
method
reference
```

Client không được provide:

```text
id
stayId in request body
status
paidAt
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

Payment v1 chỉ hỗ trợ:

```text
POST /api/stays/{stayId}/payments
GET  /api/stays/{stayId}/payments

POST /api/payments/{id}/mark-paid
POST /api/payments/{id}/mark-failed
POST /api/payments/{id}/refund
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

Mỗi Payment mới bắt đầu với:

```text
status = PENDING
paidAt = null
```

Client không được chọn initial status. Direct creation với `PAID`, `FAILED`, hoặc `REFUNDED` không được phép.

Khi chuyển `PENDING -> PAID`, backend ghi `paidAt` bằng backend current time. Client không được provide hoặc modify `paidAt`.

Payment `FAILED` giữ `paidAt = null`. Không định nghĩa `refundedAt` hoặc refund timestamp behavior trong Payment v1.

Chỉ Payment có status `PAID` đóng góp vào Total Payments.

```text
PENDING: không đóng góp
FAILED: không đóng góp
PAID: đóng góp amount
REFUNDED: không đóng góp
```

Overpayment không được hỗ trợ.

Trước khi chuyển `PENDING -> PAID`, backend phải bảo đảm việc công nhận Payment là `PAID` không làm:

```text
Total PAID Payments > Total Charges
```

Trong đó:

```text
Total Charges = SUM(charge.amount)
Total PAID Payments = SUM(payment.amount WHERE status = PAID)
```

Nếu transition gây overpayment, phải reject. Không implement general Outstanding service trong Payment v1.

---

# 11. Payment State Machine

Allowed transitions:

```text
PENDING -> PAID
Operation: mark-paid

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

---

# 12. Outstanding Balance

Total Charges:

```text
SUM(charge.amount)
```

Total Payments:

```text
SUM(payment.amount WHERE payment.status = PAID)
```

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
```

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

# 24. Check-out Flow

Check-out operates atomically on the entire Reservation.

Nếu Reservation chứa nhiều assigned Rooms:

```text
every Room phải OCCUPIED trước check-out
every Room chuyển OCCUPIED -> DIRTY
Reservation chuyển CHECKED_IN -> CHECKED_OUT
Stay chuyển CHECKED_IN -> CHECKED_OUT
actual_check_out_at được ghi nhận
```

Không hỗ trợ partial-room check-out trong current version.

Nếu bất kỳ assigned Room không thể chuyển `OCCUPIED -> DIRTY`, toàn bộ check-out thất bại và không có partial check-out.

Check-out transaction phải atomically:

```text
1. Load CHECKED_IN Reservation
2. Load unique Stay
3. Calculate Total Charges
4. Calculate Total PAID Payments
5. Calculate Outstanding
6. Reject nếu Outstanding != 0
7. Lock all assigned Rooms
8. Require every Room = OCCUPIED
9. Transition every Room OCCUPIED -> DIRTY
10. Transition Reservation CHECKED_IN -> CHECKED_OUT
11. Transition Stay CHECKED_IN -> CHECKED_OUT
12. Record actual_check_out_at
13. Apply authenticated-user audit updates
14. Write approved CHECK_OUT audit log
```

Nếu bất kỳ step nào fail, transaction phải roll back.

Check-out yêu cầu `CHECK_OUT`.

Không có check-out override permission.

Existing `CHECK_IN` authorization remains unchanged.

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
 └── CHECK_OUT
```

```text
MANAGER
 ├── MANAGE_ROOM
 ├── MANAGE_BOOKING
 ├── MANAGE_PAYMENT
 ├── VIEW_REPORT
 ├── VIEW_BOOKING
 ├── MANAGE_GUEST
 └── CHECK_OUT
```

```text
STAFF
 ├── VIEW_BOOKING
 ├── CHECK_IN
 └── CHECK_OUT
```

`DELETE_RESERVATION` được xác định là permission có thể tồn tại trong hệ thống, nhưng business flow reservation không sử dụng hard delete.

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

**End of Specification v1.0**
