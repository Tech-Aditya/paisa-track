# PaisaTrack

A personal finance, daily expense and money management API. It is built as a
modular Spring Boot monolith on PostgreSQL.

> **Status:** Phases 1-5 are complete: foundation and security; accounts,
> categories and transactions; salary, budgets and the dashboard; lending and
> borrowing; loans and EMIs. Savings goals come next, in Phase 6.
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

3. Run the app. The `dev` profile is the default.

   ```bash
   ./mvnw spring-boot:run
   ```

   On Windows, use `mvnw.cmd spring-boot:run`.

Once it starts:

- Web app (dashboard): <http://localhost:8080/>
- API: <http://localhost:8080/api/v1>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- Health: <http://localhost:8080/actuator/health>

Flyway applies the migrations automatically on startup.

### Without Docker

This starts the app on a throwaway embedded PostgreSQL. Data is lost when it stops.

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.paisatrack.LocalDevApplication
```

On Windows, use `mvnw.cmd` the same way.

The dev profile also reads `.env`. If `JWT_SECRET` is empty there, a random
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
| `dev` (default) | Your own machine (`application-dev.yml`). Compose database (or the embedded one via `LocalDevApplication`), Swagger on, readable one-line console logs, CORS for `localhost:3000`/`5173`, random JWT key allowed, slow-request warning at 500 ms. |
| `prod` | Production (`application-prod.yml`). Every secret from the environment and startup fails if one is missing. JSON (ECS) logs with service name, version and environment; optional rotating log file. Tuned connection pool, graceful shutdown, response compression, forwarded headers, Swagger off. |
| `test` | Automated tests (`src/test/resources/application-test.yml`). The test harness provides the database. |

`application.yml` holds only what every environment shares.

Environment variables:

| Variable | Required | Description |
|---|---|---|
| `DB_URL` | prod | JDBC URL, e.g. `jdbc:postgresql://db:5432/paisatrack` |
| `DB_USERNAME`, `DB_PASSWORD` | prod | Database credentials |
| `JWT_SECRET` | prod | Base64 HMAC key of at least 32 bytes. Startup fails if it is missing or too short. |
| `CORS_ALLOWED_ORIGINS` | no | Comma-separated browser origins. Empty means no cross-origin access. |
| `SWAGGER_ENABLED` | no | `true` exposes the API docs in prod (default `false`) |
| `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` | no | Hikari pool sizing (defaults 10 and 2) |
| `PORT` | no | HTTP port in prod (default 8080) |
| `LOG_FILE` | no | Also write JSON logs to this rotating file (50 MB × 14) |
| `APP_ENVIRONMENT` | no | `service.environment` in logs (default `production`) |
| `SLOW_REQUEST_THRESHOLD` | no | Requests at least this slow log at WARN (default `2s`) |
| `SPRING_PROFILES_ACTIVE` | prod | Set to `prod` |

Other settings live under `paisatrack.*` in `application.yml`:

- token lifetimes: 15 minutes for access tokens, 30 days for refresh tokens
- auth rate limit: 10 requests per minute, per IP and endpoint
- refresh-token cleanup: schedule and retention

### Logging

- Dev: `12:04:31.218  INFO [3f9a1c0d7e2b4a61] UserService : Login succeeded userId=…`. The bracket is the request id, also returned as `X-Request-Id`.
- One access line per request: `GET /api/v1/dashboard 200 31ms`. Server errors and slow requests are logged at WARN; health checks and static files at DEBUG.
- Prod: one JSON object per line. `requestId` and the signed-in `userId` are separate fields, so you can filter a single request or user.
- Never logged: passwords, tokens, request bodies, query strings, or amounts tied to a person.

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

**Salary, budgets and dashboard** (all bearer):

| Method | Path | Description |
|---|---|---|
| GET, POST | `/income-sources` | Salary and other sources, with this month's expected vs received |
| GET, PATCH | `/income-sources/{id}` | Get, or update name, type, category, account |
| PUT | `/income-sources/{id}/expectation` | New expected amount from a month onward (history kept) |
| GET | `/income-sources/{id}/history?year=` | Month-by-month expected vs received |
| GET | `/income-sources/expected?month=YYYY-MM` | All sources for one month: RECEIVED, PARTIALLY_RECEIVED, PENDING, OVERDUE |
| POST | `/income-sources/{id}/deactivate`, `/activate` | Stop or resume tracking |
| GET, POST | `/budgets` | Budgets with utilization for the period containing `date` |
| GET, PATCH, DELETE | `/budgets/{id}` | One budget; change amount, end date, alert thresholds |
| POST | `/budgets/{id}/deactivate`, `/activate` | Pause or resume |
| GET | `/dashboard?from=&to=` | Today/week/month cards, balances, budgets, expected income, charts, recent activity |
| GET | `/dashboard/recent-transactions?limit=` | Latest transactions |
| GET | `/dashboard/category-spending?from=&to=` | Spending by category with shares |

Record a salary credit with `POST /income` and `incomeSourceId` (category and account default from the source;
`periodMonth` defaults to the credit month).

**Lending and borrowing** (all bearer). Tracked separately: never in balances, net worth, income or spending.

| Method | Path | Description |
|---|---|---|
| GET, POST | `/lending`, `/borrowing` | List (`status=`, `personId=`) or record money lent / borrowed (`personName` or `personId`) |
| GET, PATCH, DELETE | `/lending/{id}`, `/borrowing/{id}` | One record with repayment history; edit; delete (only without repayments) |
| POST | `/lending/{id}/repayments` (and borrowing) | Full or partial repayment; 422 if more than outstanding |
| POST | `/lending/{id}/write-off`, `/reopen` (and borrowing) | Close with the rest unpaid (not income or expense); undo |
| GET | `/lending/{id}/history` (and borrowing) | Audit trail |
| GET | `/repayments/{id}`; POST `/repayments/{id}/void` | One repayment; void it (kept in history) |
| GET | `/debts/summary` | Owed to you and by you, per currency, with overdue totals |
| GET | `/debts/reminders?days=14` | Open records overdue or due/reminding soon |
| GET, POST, PATCH, DELETE | `/people`, `/people/{id}` | Contacts (delete only without records) |

**Loans and EMIs** (all bearer). Each EMI is split: interest is an expense (Financial Charges › Interest),
principal only reduces the loan. Outstanding principal is a liability in net worth.

| Method | Path | Description |
|---|---|---|
| POST | `/loans/preview` | EMI calculator: EMI, total interest and full schedule; nothing saved |
| GET, POST | `/loans` | List (`includeClosed=`) or add a loan. EMI is calculated when omitted; `disbursementAccountId` credits the principal (not income); `emisPaidBeforeTracking` for existing loans |
| GET, PATCH, DELETE | `/loans/{id}` | One loan with payments; edit (terms only before any payment); delete (only without payments) |
| GET | `/loans/{id}/schedule` | Every installment with principal/interest split and PAID, MISSED, DUE or UPCOMING |
| GET | `/loans/{id}/history` | Audit trail |
| POST | `/loans/{id}/emi-payments` | Pay the next EMI (in order); optional `paymentDate`, `accountId` |
| POST | `/loans/{id}/prepayments` | Prepay principal; EMI stays the same and the schedule ends sooner |
| POST | `/loans/{id}/payments/{paymentId}/void` | Void the latest payment and its ledger entries |
| GET | `/loans/summary?days=30` | Outstanding, monthly EMI, principal and interest paid this year, per currency |
| GET | `/emis/upcoming?days=30` | EMIs missed or due soon across all loans |

Ledger entries a loan creates (`LOAN_DISBURSEMENT`, `LOAN_PRINCIPAL` and the interest expense) carry `loanId`
and can only be changed through the loan.

Error codes are listed in
[`ErrorCode`](src/main/java/com/paisatrack/platform/exception/ErrorCode.java).

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
  PaisaTrackApplication.java
  platform/              cross-cutting, no business logic
    api/                 response envelope, paging
    audit/               field-level audit trail
    config/              clock, OpenAPI, CORS, web app routes
    exception/           error codes, global handler
    idempotency/         Idempotency-Key replay protection
    logging/             request id, access log, startup line
    money/               currency precision rules
    persistence/         auditable entity base
    security/            filter chain, JWT, CurrentUser, rate limiting
    validation/          currency, timezone, password constraints
  modules/               business features
    <module>/            entities, repositories, services (internals package-private)
    <module>/dto/        request and response records
    <module>/web/        REST controllers
    auth, user, account, category, transaction, income, budget, people, debt, loan, dashboard
src/main/resources/
  application.yml               shared settings
  application-dev.yml           local development (default)
  application-prod.yml          production
  db/migration/                 Flyway migrations (V1 … V10)
  static/app/                   web app: index.html, css/, js/ (ES modules, no build step)
src/test/java/com/paisatrack/   same layout as main, plus support/ and LocalDevApplication
```

### Web app

Open <http://localhost:8080/>. It is plain HTML, CSS and ES modules served by the
backend, with no build step and a strict Content-Security-Policy. All user data is
inserted as text.

- **Overview:** spending today, this week and this month; net cash flow and
  savings rate; daily spending and category donut charts; budgets with alerts;
  expected income, with a one-click **Record** for anything due; accounts and
  net worth (less loans outstanding); EMIs missed or due; a 6-month trend; recent activity. Date presets: this month, last
  month, 30 days, custom.
- **Transactions:** search, filter by type, category, account and dates; grouped
  by day with daily totals; delete (void) and restore; paging.
- **Loans:** outstanding, monthly EMI and interest this year; EMIs missed or due
  with one-click **Pay**; each loan opens its schedule, payments, **Pay EMI**,
  **Prepay**, void-latest and delete. Adding a loan shows a live EMI preview.
- **Lending & borrowing:** what others owe you and what you owe, with overdue flags;
  each record opens a panel with repayment history, **Record repayment**, write-off,
  reopen, delete and void.
- **Add:** one dialog for expense, income or transfer, plus dialogs for budgets,
  accounts and income sources. Errors show next to the field. A double submit
  never creates two entries.
- Light and dark themes, a sidebar on desktop and a bottom tab bar on phones.
  Press `n` anywhere to add a transaction.
