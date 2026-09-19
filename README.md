# IntelliDesk

## AI-Assisted IT Support & Ticket Resolution Platform

IntelliDesk is a production-style **Spring Boot backend** for IT/customer support:
customers raise tickets, the system classifies them (category, priority, sentiment,
suggested response) through a replaceable AI abstraction, assigns them to the best
suited agent via a deterministic scoring algorithm, enforces a controlled ticket
workflow, and monitors SLA deadlines with automatic escalation.

> **Status: Phase 1 complete** — project setup, database design, entities,
> repositories, seed data. Later phases add auth, workflow, assignment,
> SLA scheduling, AI classification, search, dashboards, docs and Docker.
> See [DEVELOPMENT_NOTES.md](DEVELOPMENT_NOTES.md) for engineering notes.

## Technology stack

| Layer     | Technology |
|-----------|------------|
| Language  | Java 21 |
| Framework | Spring Boot 3.3 (Web, Data JPA, Security, Validation) |
| ORM       | Hibernate 6.5 |
| Database  | PostgreSQL (primary), H2 (tests / zero-setup profile) |
| Auth      | JWT (jjwt) + BCrypt (added in Phase 2) |
| Docs      | springdoc-openapi (Swagger UI) |
| Build     | Maven |
| Tests     | JUnit 5, Mockito, Spring Boot Test |

## Project structure

```
com.intellidesk
├── common        # shared kernel (BaseEntity)
├── config        # app-wide beans (PasswordEncoder, DataSeeder)
├── user          # users, roles, (Phase 2: auth)
├── ticket        # tickets, comments, history, workflow
├── category      # support categories
├── agent         # agent skills
├── sla           # SLA policies (Phase 6: scheduler)
├── dashboard     # (Phase 8) statistics
└── ai            # (Phase 7) classification abstraction
```

## Quick start

```bash
# Option A - zero-setup demo (in-memory H2, data lost on shutdown)
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2

# Option B - PostgreSQL
export INTELLIDESK_DB_URL=jdbc:postgresql://localhost:5432/intellidesk
export INTELLIDESK_DB_USERNAME=intellidesk
export INTELLIDESK_DB_PASSWORD=intellidesk
./mvnw spring-boot:run

# Run tests
./mvnw test
```

### Environment variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `INTELLIDESK_DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/intellidesk` |
| `INTELLIDESK_DB_USERNAME` | DB user | `intellidesk` |
| `INTELLIDESK_DB_PASSWORD` | DB password | `intellidesk` |
| `INTELLIDESK_JWT_SECRET` | **Required** HMAC secret, >= 32 chars (empty = fail fast) | *(none)* |
| `INTELLIDESK_JWT_EXPIRATION_MINUTES` | Token lifetime | `60` |
| `INTELLIDESK_ADMIN_EMAIL` | First admin account | `admin@intellidesk.local` |
| `INTELLIDESK_ADMIN_PASSWORD` | First admin password (**change it!**) | `Admin@12345` |

## Authentication & security (Phase 2)

### API

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| POST | `/api/auth/register` | public | Create a CUSTOMER account, returns JWT |
| POST | `/api/auth/login` | public | Email/password -> JWT |
| GET | `/api/auth/me` | authenticated | Identity of the caller |
| GET | `/api/admin/ping` | ADMIN only | Authorization probe (chain rule + `@PreAuthorize`) |

### Security flow

```
client                    filter chain                       service
  |  POST /api/auth/login   |                                  |
  |------------------------>| permitAll -> AuthService         |
  |<-- 200 {token, user} ---| DaoAuthenticationProvider         |
  |                         |   (BCrypt compares)               |
  |  GET /api/x             |                                  |
  |  Authorization: Bearer  |                                  |
  |------------------------>| JwtAuthenticationFilter           |
  |                         |   verify signature+expiry         |
  |                         |   load user from DB (revocable)   |
  |                         |   set SecurityContext             |
  |                         | AuthorizationFilter               |
  |                         |   hasRole(...) per path           |
  |<-- 200 / 401 / 403 -----| 401=unknown/invalid  403=wrong role
```

Design decisions: stateless (no sessions, CSRF off because there are no
cookies), single error shape for 401/403 (JSON, no stack traces), account
enumeration prevented (wrong password and unknown email return the same 401
message), roles cannot be chosen at registration (fixed CUSTOMER; admins are
seeded/managed server-side), JWT secret externalized via
`INTELLIDESK_JWT_SECRET` with a startup failure if missing/too short.

### Example requests

```bash
# Register (201, returns token; duplicate email -> 409; bad input -> 400)
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"riya@example.com","password":"Passw0rd123","fullName":"Riya Sharma"}'

# Login (200; wrong credentials -> 401)
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"riya@example.com","password":"Passw0rd123"}'

# Authenticated call (401 without / garbage token)
curl http://localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"

# Role check (customer -> 403, admin -> 200)
curl http://localhost:8080/api/admin/ping -H "Authorization: Bearer $ADMIN_TOKEN"
```

## Database schema (Phase 1)

```
users ──1:N──> tickets (reporter_id, assigned_agent_id)
categories ──1:N──> tickets
sla_policies ──1:N──> tickets
tickets ──1:N──> ticket_comments ──N:1──> users (author)
tickets ──1:N──> ticket_history ──N:1──> users (changed_by)
users ──1:N──> agent_skills ──N:1──> categories
```

Key design decisions (all deliberate, all interview-friendly):

1. **Roles are an enum column, not a table.** IntelliDesk has exactly three
   system roles (`CUSTOMER`, `AGENT`, `ADMIN`) owned by the code. A roles table
   pays off only when roles are created at runtime; here it would add a join and
   an admin UI for no benefit.
2. **`users` table name** — `user` is a reserved word in PostgreSQL.
3. **SLA deadline is frozen on the ticket.** The ticket references the policy row
   for context but stores its own `sla_deadline_at`, computed once at creation.
   Editing a policy later never rewrites history (auditability).
4. **Optimistic locking (`version` column) on tickets** — two agents updating the
   same ticket concurrently get a clear conflict, not silent overwrites.
5. **Append-only `ticket_history`** — every state change is an immutable row;
   system events (SLA escalation) use a nullable actor plus a reason string.
6. **`ticket_number` business key + `id` surrogate key** — humans read
   `TKD-2026-000042`, foreign keys use the numeric id.
7. **`Instant` timestamps stored in UTC** (`hibernate.jdbc.time_zone=UTC`) —
   no server-timezone drift.
8. **LAZY associations + fetch graphs** — REST never serializes entities directly
   (DTOs in Phase 3), so no lazy-init surprises and no circular JSON.

## SLA targets (seeded into `sla_policies`)

| Priority | Resolution target |
|----------|-------------------|
| CRITICAL | 2 hours |
| HIGH     | 8 hours |
| MEDIUM   | 24 hours |
| LOW      | 48 hours |

## Ticket workflow (enforced from Phase 4)

```
OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED
        ↑              ↓    ↑        ↓
        |    WAITING_FOR_CUSTOMER  reopen
        └──── ESCALATED (SLA engine) ──→ IN_PROGRESS / ASSIGNED
```

## License

MIT (portfolio project).
