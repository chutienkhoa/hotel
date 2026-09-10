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


Expense ────────────────> AccountingEntry


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
SINGLE
DOUBLE
TWIN
DELUXE
SUITE
```

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

Relationship:

```text
RoomType 1 ─── N Room
```

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

---

# 9. Charge Domain

Charge đại diện cho khoản khách phải trả.

## 9.1 Charge

Fields:

```text
id
stay_id
type
description
quantity
unit_price
amount
charged_at
created_at
created_by
updated_at
updated_by
```

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

Constraint:

```text
quantity > 0
```

Charge và Payment là hai khái niệm khác nhau.

Ví dụ:

```text
Charges:
Room       ¥30,000
Breakfast   ¥3,000
Tax         ¥3,300
Discount   -¥2,000

Total = ¥34,300
```

---

# 10. Payment Domain

Payment đại diện cho tiền khách đã thanh toán.

## 10.1 Payment

Fields:

```text
id
stay_id
amount
method
status
transaction_reference
paid_at
created_at
created_by
updated_at
updated_by
```

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

Constraint:

```text
amount > 0
```

---

# 11. Payment State Machine

Allowed transitions:

```text
PENDING
 ├── PAID
 └── FAILED

PAID
 └── REFUNDED
```

Không cho phép:

```text
PAID → PENDING
PAID → FAILED
REFUNDED → PAID
REFUNDED → PENDING
```

---

# 12. Outstanding Balance

Tổng tiền phải trả được xác định từ Charges.

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

Không được check-out khi còn outstanding balance, trừ trường hợp có cơ chế override được cấp quyền.

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

Expense status:

```text
DRAFT
SUBMITTED
APPROVED
REJECTED
POSTED
```

Expense có thể tạo Accounting Entry.

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
Deposit/payment rule
User có CHECK_IN permission
```

## Check-out

Phải kiểm tra:

```text
Final bill đã được calculate
Outstanding balance = 0
Không còn pending charge
User có CHECK_OUT permission
```

---

# 21. Room State Machine

Allowed transitions:

```text
AVAILABLE
 ├── OCCUPIED
 ├── MAINTENANCE
 └── OUT_OF_ORDER
```

```text
OCCUPIED
 └── DIRTY
```

```text
DIRTY
 └── CLEANING
```

```text
CLEANING
 └── AVAILABLE
```

```text
MAINTENANCE
 └── AVAILABLE
```

```text
OUT_OF_ORDER
 └── AVAILABLE
```

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
Verify Payment / Deposit
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

```text
Stay
 ↓
Calculate final amount
 ↓
Check unpaid balance
 ↓
Receive payment
 ↓
Check-out
 ↓
Reservation = CHECKED_OUT
 ↓
Room = DIRTY
```

Nếu còn outstanding balance:

```text
Check-out = rejected
```

trừ khi có quyền override.

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
 └── VIEW_BOOKING
```

```text
MANAGER
 ├── MANAGE_ROOM
 ├── MANAGE_BOOKING
 ├── MANAGE_PAYMENT
 ├── VIEW_REPORT
 ├── VIEW_BOOKING
 └── MANAGE_GUEST
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
quantity > 0
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

Dashboard phải hiển thị các thông tin chính:

```text
Today's Check-in
Today's Check-out
Pending Payment
Dirty Rooms
Maintenance Rooms
Upcoming Reservations
```

Các KPI đã thống nhất:

```text
Occupancy
Check-in count
Check-out count
Revenue
Expense
Profit
```

Ví dụ:

```text
Occupancy: 78%
Check-in: 5
Check-out: 3

Revenue: ¥185,000
Expense: ¥52,000
Profit: ¥133,000
```

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

Revenue và Expense phải có accounting representation phù hợp.

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

7. Check-out yêu cầu outstanding balance = 0,
   trừ trường hợp override có permission phù hợp.

8. Payment amount > 0.

9. Charge quantity > 0.

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
