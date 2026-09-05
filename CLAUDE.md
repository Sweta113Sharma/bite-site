# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Running the Application
- Start the application: `mvn spring-boot:run`
- With demo data: `export FLYWAY_LOCATIONS="classpath:db/migration,classpath:db/seed" && mvn spring-boot:run`
- Visit: http://localhost:8080/login

### Testing
- Run all tests: `mvn test`
- Run specific test: `mvn -Dtest=ClassName#methodName test`
- Security tests require separate database: `mysql -u root -e "CREATE DATABASE bitesite_test_db CHARACTER SET utf8mb4;"`

### Docker
- Build and run: `export DB_PASSWORD=$(openssl rand -base64 24) && docker compose up --build`
- Note: Docker artifacts are unverified in this environment

### Environment Variables
Key variables (set before running):
- `RAZORPAY_KEY_ID`/`_SECRET`/`_WEBHOOK_SECRET` - for payments
- `SMTP_HOST`/`_PORT`/`_USERNAME`/`_PASSWORD` - for email verification
- `TWILIO_ACCOUNT_SID`/`_AUTH_TOKEN`/`_FROM_NUMBER` - for SMS verification
- `VAPID_PUBLIC_KEY`/`_PRIVATE_KEY`/`_SUBJECT` - for push notifications
- `SENTRY_DSN` - for error tracking
- `UPLOAD_STORAGE_TYPE` - `local` (default) or `cloudinary`
- `FLYWAY_LOCATIONS` - `classpath:db/migration` (default) or add `,classpath:db/seed` for demo data
- `COOKIE_SECURE` - `false` for dev, `true` for production behind HTTPS proxy

### Database Setup
```bash
mysql -u root -e "CREATE DATABASE bitesite_db CHARACTER SET utf8mb4;"
```
Flyway migrations run automatically on startup.

## Code Architecture

### Multi-tenancy Approach
- **Account-based tenancy**: Tenant determination is based on the logged-in user's account, not URL/subdomain
- Every tenant-scoped table contains a `tenant_id` column
- `TenantResolutionInterceptor` sets `TenantContext` from the authenticated user's `tenant_id`
- All DAO methods require explicit `tenantId` parameter
- Platform roles (`SUPER_ADMIN`, `TECH_MANAGER`) have `tenant_id = NULL`

### Layered Structure
```
src/main/java/com/bitesite/
├── controller/         # Spring MVC controllers (organized by role: admin, canteen, student, etc.)
├── service/            # Business logic layer
├── dao/                # Data access layer using Spring JDBC/JdbcTemplate
├── model/              # JPA-like DTOs (though not using JPA/Hibernate)
├── dto/                # Data transfer objects
├── config/             # Configuration classes
├── exception/          # Custom exceptions
├── tenant/             # Tenant-specific utilities
├── privacy/            # Privacy-related functionality
└── web/                # Web utilities (service workers, icons, etc.)
```

### Key Components
- **Spring Security**: Form login, BCrypt, CSRF protection, method security
- **Spring Session JDBC**: HTTP sessions stored in MySQL (survives restarts, works across instances)
- **Flyway**: Database migrations in `src/main/resources/db/migration`
- **Razorpay**: Payment integration with both client-side callback and webhook verification
- **Rate Limiting**: MySQL-backed sliding window counter for various endpoints
- **Audit Log**: Tracks sensitive changes in `audit_log` table
- **File Storage**: Abstracted via `FileStorageService` (local disk or Cloudinary)

### Role-Based Access Control
| Role | Scope | Console |
|------|-------|---------|
| `SUPER_ADMIN` | Platform | `/admin` |
| `TECH_MANAGER` | Platform | `/techmgr` |
| `CANTEEN_MANAGER` | One college + one canteen | `/canteen` |
| `CANTEEN_OPERATOR` | One college + one canteen | `/canteen` |
| `STUDENT` | One college | `/student` |

### Order Lifecycle
```
AWAITING_PAYMENT → PAID → PREPARING → READY_FOR_PICKUP → COMPLETED
                 ↘ PAYMENT_FAILED / EXPIRED / CANCELLED
```
- Payment confirmation has dual paths: Razorpay client callback + webhook (idempotent)
- Order status transitions controlled by `OrderStatus.canTransitionTo()`

### Production Concerns Already Implemented
- Global exception handling (`GlobalExceptionHandler`)
- Structured JSON logging (opt-in via `LOG_FORMAT=ecs`)
- Privacy features: self-service account deletion with anonymization
- Email/phone verification for student registration
- Push notifications via Web Push API
- Sentry integration for error tracking
- Health checks via Spring Boot Actuator

### Testing Strategy
- Unit tests for business logic and utilities
- Mockito-based service tests
- Two full-stack security tests:
  - `TenantIsolationSecurityTest`: Verifies cross-tenant access is blocked
  - `RolePermissionSecurityTest`: Verifies role-based access controls
- Security tests use separate test database to avoid interfering with development data

### Important Notes
- **JDK 21 required**: Newer JDKs break Lombok silently
- **Seed data**: Opt-in via `FLYWAY_LOCATIONS` containing `classpath:db/seed`
- **Payment testing**: Requires Razorpay test credentials for end-to-end testing
- **Docker**: Multi-stage build with non-root user, but unverified in this environment
- **HTTPS**: Expects termination at reverse proxy; set `COOKIE_SECURE=true` in production