# Architecture Rules

## 1. Purpose

This document defines the mandatory package and resource structure for the Hotel Management System.

Codex and other coding agents MUST follow this document when creating, moving, renaming, or modifying source code and frontend resources.

Do NOT create new packages, domains, layers, or frontend resource structures without explicit approval.

---

# 2. Architecture Overview

The application consists of:

- Backend: Java + Spring Boot
- Frontend: Thymeleaf + HTML + CSS + JavaScript
- Database: PostgreSQL

The backend follows a **package-by-layer** architecture.

The frontend follows a **domain/UI-based resource organization**.

---

# 3. Backend Package Structure

The base package is:

```text
com.example.hotel
```

The backend structure is:

```text
com.example.hotel
│
├── controller
│   ├── customer
│   │   └── CustomerController.java
│   │
│   ├── booking
│   │   └── BookingController.java
│   │
│   ├── room
│   │   └── RoomController.java
│   │
│   └── common
│
├── service
│   ├── customer
│   │   ├── CustomerService.java
│   │   └── CustomerServiceImpl.java
│   │
│   ├── booking
│   │   ├── BookingService.java
│   │   └── BookingServiceImpl.java
│   │
│   ├── room
│   │   ├── RoomService.java
│   │   └── RoomServiceImpl.java
│   │
│   └── common
│
├── repository
│   ├── customer
│   │   └── CustomerRepository.java
│   │
│   ├── booking
│   │   └── BookingRepository.java
│   │
│   ├── room
│   │   └── RoomRepository.java
│   │
│   └── common
│
├── entity
│   ├── customer
│   │   └── Customer.java
│   │
│   ├── booking
│   │   └── Booking.java
│   │
│   ├── room
│   │   └── Room.java
│   │
│   └── common
│
├── dto
│   ├── customer
│   │   ├── request
│   │   └── response
│   │
│   ├── booking
│   │   ├── request
│   │   └── response
│   │
│   ├── room
│   │   ├── request
│   │   └── response
│   │
│   └── common
│       ├── request
│       └── response
│
├── mapper
│   ├── customer
│   ├── booking
│   ├── room
│   └── common
│
├── exception
├── security
├── config
│
└── common
    ├── constant
    ├── util
    ├── validation
    └── response
```

---

# 4. Backend Layer Dependency

Backend layers MUST follow this dependency direction:

```text
Controller
    ↓
Service
    ↓
Repository
    ↓
Database
```

The normal request/response flow is:

```text
Request
   ↓
Controller
   ↓
Request DTO
   ↓
Service
   ↓
Entity
   ↓
Repository
   ↓
Database
```

Response flow:

```text
Database
   ↓
Repository
   ↓
Entity
   ↓
Mapper
   ↓
Response DTO
   ↓
Controller
   ↓
Response
```

---

# 5. Backend Package Rules

## 5.1 Domain-specific classes

If a class belongs to a specific business domain, place it under that domain.

Examples:

```text
CustomerController
→ controller.customer

BookingController
→ controller.booking

RoomController
→ controller.room

CustomerService
→ service.customer

BookingService
→ service.booking

RoomRepository
→ repository.room

Customer
→ entity.customer
```

---

## 5.2 Layer-specific classes without a domain

If a class belongs to a specific layer but does not belong to:

- customer
- booking
- room

place it under:

```text
{layer}.common
```

Examples:

```text
HotelInfoController
→ controller.common

HotelInfoService
→ service.common

HotelInfoServiceImpl
→ service.common

HotelInfoRepository
→ repository.common

HotelInfo
→ entity.common
```

---

## 5.3 Cross-cutting technical classes

Cross-cutting technical utilities MUST NOT be placed into a layer-specific `common` package.

Use the existing top-level `common` package and its appropriate category.

Examples:

```text
DateUtils
→ common.util

StringUtils
→ common.util

ApplicationConstants
→ common.constant

CommonValidator
→ common.validation
```

Security-related classes belong under:

```text
security
```

Configuration-related classes belong under:

```text
config
```

---

# 6. Backend Layer Responsibilities

## Controller

Responsible for handling HTTP requests and responses.

Controllers MUST NOT directly access repositories.

```text
Controller → Service
```

NOT:

```text
Controller → Repository
```

---

## Service

Responsible for application/business processing.

Services may access repositories.

```text
Service → Repository
```

---

## Repository

Responsible for database access.

Repositories MUST NOT depend on controllers.

Repositories MUST NOT depend on frontend resources.

---

## Entity

Represents persistent/domain data.

Entities MUST NOT depend on:

- Controller
- Service
- Repository
- Frontend resources

---

## DTO

Used for request and response data transfer.

DTOs are organized by domain and separated into:

```text
request
response
```

---

## Mapper

Responsible for converting between:

```text
Entity ↔ DTO
```

Mappers are organized by domain.

---

# 7. Frontend Resource Structure

Frontend resources are located under:

```text
src/main/resources
```

The frontend structure is:

```text
src/main/resources
│
├── templates
│   ├── dashboard
│   ├── customer
│   ├── booking
│   ├── room
│   └── common
│
└── static
    ├── css
    │   ├── customer
    │   ├── booking
    │   ├── room
    │   └── common
    │
    ├── js
    │   ├── customer
    │   ├── booking
    │   ├── room
    │   └── common
    │
    ├── images
    └── fonts
```

---

# 8. Thymeleaf Templates

HTML/Thymeleaf templates MUST be placed under:

```text
src/main/resources/templates
```

Domain-specific pages MUST be placed under the corresponding domain.

Examples:

```text
templates/customer/
templates/booking/
templates/room/
```

Example:

```text
templates/customer/list.html
templates/customer/detail.html
templates/customer/form.html

templates/booking/list.html
templates/booking/detail.html
templates/booking/form.html

templates/room/list.html
templates/room/form.html
```

Shared Thymeleaf templates MUST be placed under:

```text
templates/common/
```

Examples:

```text
templates/common/layout.html
templates/common/header.html
templates/common/sidebar.html
templates/common/error.html
```

---

# 9. CSS Structure

CSS files MUST be placed under:

```text
src/main/resources/static/css
```

Domain-specific CSS:

```text
static/css/customer/
static/css/booking/
static/css/room/
```

Shared CSS:

```text
static/css/common/
```

Example:

```text
static/css/common/layout.css
static/css/common/table.css
static/css/common/form.css

static/css/customer/customer.css
static/css/booking/booking.css
static/css/room/room.css
```

Shared styles SHOULD be reused when applicable.

Do NOT create unnecessary duplicated CSS files.

---

# 10. JavaScript Structure

JavaScript files MUST be placed under:

```text
src/main/resources/static/js
```

Domain-specific JavaScript:

```text
static/js/customer/
static/js/booking/
static/js/room/
```

Shared JavaScript:

```text
static/js/common/
```

Example:

```text
static/js/common/common.js
static/js/common/modal.js
static/js/common/validation.js

static/js/customer/customer.js
static/js/booking/booking.js
static/js/room/room.js
```

---

# 11. Frontend Domain Organization

Frontend resources use the same business-domain classification as the backend where applicable.

```text
Backend                         Frontend
────────────────────────────────────────────────
controller.customer      →      templates/customer/
service.customer         →      static/js/customer/
dto.customer             →      static/css/customer/

controller.booking       →      templates/booking/
service.booking          →      static/js/booking/
dto.booking              →      static/css/booking/

controller.room          →      templates/room/
service.room             →      static/js/room/
dto.room                 →      static/css/room/
```

The frontend MUST NOT copy the backend layer structure.

Do NOT create:

```text
templates/controller/
templates/service/
templates/repository/
```

Frontend resources are organized by UI/business domain, not backend layer.

---

# 12. Backend + Frontend Relationship

For a domain-specific feature, the related components should follow this structure:

```text
Backend

com.example.hotel.controller.booking
com.example.hotel.service.booking
com.example.hotel.repository.booking
com.example.hotel.entity.booking
com.example.hotel.dto.booking
com.example.hotel.mapper.booking


Frontend

templates/booking/
static/css/booking/
static/js/booking/
```

Example:

```text
BookingController
      ↓
templates/booking/list.html
      ↓
static/css/booking/booking.css
static/js/booking/booking.js
```

---

# 13. Common Rule

`common` MUST NOT become a dumping ground.

Use:

```text
{layer}.common
```

when the class belongs to a specific backend layer but does not belong to a specific business domain.

Use:

```text
common.{category}
```

when the class is a cross-cutting technical component.

Examples:

```text
HotelInfoController
→ controller.common

HotelInfoService
→ service.common

HotelInfoRepository
→ repository.common

HotelInfo
→ entity.common

DateUtils
→ common.util

SecurityConfig
→ config

JwtService
→ security
```

---

# 14. New Domain / Package Rule

The currently defined business domains are:

```text
customer
booking
room
```

Codex MUST NOT create a new business domain or package without explicit approval.

For example, Codex MUST NOT independently decide to create:

```text
payment
employee
inventory
report
reservation
```

unless explicitly requested and approved.

---

# 15. Architecture Change Rule

When implementing a task, Codex MUST preserve the existing architecture.

Codex MUST NOT:

- Create a new layer without approval.
- Create a new business domain without approval.
- Move an existing class to another package without approval.
- Rename packages without approval.
- Introduce a different package architecture.
- Mix backend layers.
- Put frontend resources into Java packages.
- Put Java classes into frontend resource directories.
- Modify unrelated architecture.

If the requested implementation cannot be completed while following this architecture, Codex MUST stop and ask for clarification rather than inventing a new structure.

---

# 16. Architecture Decision Summary

The project uses:

```text
Backend
→ Package-by-Layer

Frontend
→ Domain/UI-based resource organization
```

Backend:

```text
controller
service
repository
entity
dto
mapper
exception
security
config
common
```

Frontend:

```text
templates
static/css
static/js
static/images
static/fonts
```

Business domains:

```text
customer
booking
room
```

Non-domain classes belonging to a backend layer:

```text
{layer}.common
```

Cross-cutting technical classes:

```text
common.{category}
```

The architecture defined in this document is mandatory.
