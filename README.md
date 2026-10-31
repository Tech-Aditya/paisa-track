# PaisaTrack

A personal finance, daily expense and money management API. It is built as a
modular Spring Boot monolith on PostgreSQL.

> **Status:** Phases 1 (foundation and security) and 2 (accounts, categories,
> transactions) are complete. Salary, budgets and the dashboard come next, in Phase 3.
> Design decisions are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tech stack

| | |
|---|---|
| Runtime | Java 25, Spring Boot 4.1 (Web MVC, Data JPA, Security, Validation, Actuator) |
| Auth | Spring Security OAuth2 Resource Server (Nimbus JWT, HS256) and opaque rotating refresh tokens |
| Database | PostgreSQL 18, Flyway migrations, Hibernate (schema validation only) |
| API docs | springdoc-openapi 3 (Swagger UI) |
| Tests | JUnit 5, Mockito, Spring Boot Test, Testcontainers. An embedded PostgreSQL is used when Docker is not available. |
| Build | Maven wrapper (no local Maven install needed) |

## Running locally

Prerequisites: JDK 25 and Docker (for the database).

1. Copy the example environment file:

   ```bash
   cp .env.example .env
   ```

2. Start PostgreSQL:

   ```bash
   docker compose up -d
   ```

3. Run the app. The `local` profile is the default.

   ```bash
   ./mvnw spring-boot:run
   ```

   On Windows, use `mvnw.cmd spring-boot:run`.

Once it starts:

- API: <http://localhost:8080/api/v1>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Health: <http://localhost:8080/actuator/health>

Flyway applies the migrations automatically on startup.

The local profile also reads `.env`. If `JWT_SECRET` is empty there, a random
signing key is generated at startup, so tokens stop working after a restart.
To keep tokens valid across restarts, set a key:

```bash
openssl rand -base64 48
```

### Quick try (curl)

```bash
curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' -d '{"email":"asha@example.com","password":"Secret123","fullName":"Asha Rao"}'
```

```bash
curl -s localhost:8080/api/v1/users/me -H "Authorization: Bearer <accessToken from the response>"
```

## Profiles and configuration

| Profile | Purpose |
|---|---|
| `local` (default) | Developer machine. Uses the compose database, DEBUG logging for `com.paisatrack`, CORS for `localhost:3000` and `localhost:5173`, and allows a random JWT key. |
| `test` | Automated tests (`src/test/resources/application-test.yml`). The test harness provides the database. |
| `prod` | Every secret comes from the environment, Swagger is off by default, logs are structured JSON (ECS), shutdown is graceful, and forwarded headers are honoured. |

Environment variables:

| Variable | Required | Description |
|---|---|---|
| `DB_URL` | prod | JDBC URL, e.g. `jdbc:postgresql://db:5432/paisatrack` |
| `DB_USERNAME`, `DB_PASSWORD` | prod | Database credentials |
| `JWT_SECRET` | prod | Base64 HMAC key of at least 32 bytes. Startup fails if it is missing or too short. |
| `CORS_ALLOWED_ORIGINS` | no | Comma-separated browser origins. Empty means no cross-origin access. |
| `SWAGGER_ENABLED` | no | `true` exposes the API docs in prod (default `false`) |
| `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` | no | Hikari pool sizing (defaults 10 and 2) |
| `SPRING_PROFILES_ACTIVE` | prod | Set to `prod` |

Other settings live under `paisatrack.*` in `application.yml`:

- token lifetimes: 15 minutes for access tokens, 30 days for refresh tokens
- auth rate limit: 10 requests per minute, per IP and endpoint
- refresh-token cleanup: schedule and retention

## API

All endpoints are under `/api/v1`. Responses use the envelope
`{success, message, data | error, requestId, timestamp}`.

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/auth/register` | public | Create an account and sign in (201). `defaultCurrency` and `timezone` are optional; the defaults are INR and Asia/Kolkata. |
| POST | `/auth/login` | public | Sign in. Returns the access token, refresh token and profile. |
| POST | `/auth/refresh` | public | Exchange a refresh token for a new token pair. Refresh tokens can be used once. |
| POST | `/auth/logout` | public | Revoke the session the refresh token belongs to (204) |
| POST | `/auth/logout-all` | bearer | Revoke every session of the caller (204) |
| GET | `/users/me` | bearer | Profile and preferences |
| PATCH | `/users/me` | bearer | Update `fullName` |
| PUT | `/users/me/password` | bearer | Change password. Revokes all refresh tokens (204). |
| GET | `/users/me/preferences` | bearer | `defaultCurrency`, `timezone`, `weekStartDay`, `theme` |
| PATCH | `/users/me/preferences` | bearer | Update any of the above |

**Accounts and categories** (all bearer):

| Method | Path | Description |
|---|---|---|
| GET | `/accounts` | Accounts with derived balances (`includeInactive=true` for all) |
| GET | `/accounts/summary` | Assets, liabilities and net worth per currency |
| POST | `/accounts` | Create (CASH, SAVINGS, CURRENT, CREDIT_CARD, WALLET, OTHER) |
| GET, PATCH | `/accounts/{id}` | Get, or update details and opening balance |
| POST | `/accounts/{id}/deactivate`, `/activate`, `/make-default` | Lifecycle and the default account for quick entry |
| GET | `/accounts/{id}/transactions` | Account history, both sides of transfers |
| GET | `/categories` | Category tree (`type=EXPENSE` or `INCOME`) |
| POST | `/categories` | Create a category, or a subcategory with `parentId` |
| GET, PATCH, DELETE | `/categories/{id}` | Get, rename/reorder, delete (unused only) |
| POST | `/categories/{id}/deactivate`, `/activate` | Hide from or return to new transactions |

**Transactions** (all bearer). Creates accept an optional `Idempotency-Key` header so retries are safe.

| Method | Path | Description |
|---|---|---|
| POST, GET | `/expenses` | Record (only `amount` and `categoryId` required) or list expenses |
| PUT | `/expenses/{id}` | Replace an expense |
| POST, GET | `/income` | Record or list income actually received |
| PUT | `/income/{id}` | Replace an income entry |
| POST, GET | `/transfers` | Move money between own accounts, incl. card bill payments |
| PUT | `/transfers/{id}` | Replace a transfer |
| GET | `/transactions` | Search: type, from/to, accountId, categoryId, paymentMethod, q, amount range, paging, sorting |
| GET | `/transactions/{id}` | One transaction |
| POST | `/transactions/{id}/void`, `/restore` | Delete (void) and undo |
| GET | `/transactions/{id}/history` | Audit trail with field-level from/to |
| GET | `/transactions/daily-summary?date=` | Day totals, count, average, highest expense, category breakdown |

Error codes are listed in
[`ErrorCode`](src/main/java/com/paisatrack/exception/ErrorCode.java).

## Tests

```bash
./mvnw test
```

Integration tests always run against a real PostgreSQL:

- With Docker available, they use Testcontainers (`postgres:18-alpine`).
- Without Docker, they fall back to an embedded PostgreSQL 18 binary
  (zonky embedded-postgres).

To force one mode, set `-Dpaisatrack.test.db=testcontainers` or
`-Dpaisatrack.test.db=embedded`. The `PAISATRACK_TEST_DB` environment variable
works too.

## Project layout

```
src/main/java/com/paisatrack/
  auth/         register/login/refresh/logout, refresh-token store
  user/         users, profile, password, preferences
  security/     filter chain, JWT, CurrentUser, rate limiting
  account/      accounts, derived balances, default account
  category/     categories, subcategories, default seeding
  transaction/  expenses, income, transfers, void/restore, summaries
  audit/        field-level audit trail
  idempotency/  Idempotency-Key replay protection
  common/       response envelope, paging, money rules, auditable entity base
  exception/    error codes and the global handler
  validation/   currency, timezone, password constraints
  logging/      correlation id and access log
  config/       clock and auditing, OpenAPI, CORS
src/main/resources/db/migration/   Flyway migrations (V1, V2, …)
```
