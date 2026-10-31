# PaisaTrack architecture

Status: Phases 1-3 (foundation and security; accounts, categories and transactions;
salary, budgets and dashboard) are done. This document records the decisions later phases build on.

## Shape

A modular monolith: one Spring Boot application with one PostgreSQL database.
Modules are Java packages under `com.paisatrack`. Each module owns its
controllers, services, repositories, entities and DTOs. Entities and
repositories stay package-private wherever possible, so other modules go
through the module's public service.

| Package | Responsibility |
|---|---|
| `auth` | Registration and login orchestration, refresh tokens (rotation, reuse detection, revocation), logout |
| `user` | User identity, password hashing and checks, profile, preferences |
| `security` | Spring Security filter chain, JWT encode/decode, `CurrentUser`, 401/403 responses, auth rate limiting |
| `account` | Accounts, derived balances, default account, per-currency summary |
| `category` | Two-level expense/income categories, default seeding |
| `transaction` | Expenses, income, transfers, void/restore, search, daily summary |
| `income` | Income sources, monthly expectations by effective month, expected vs received |
| `budget` | Overall/category budgets, monthly/weekly/custom periods, utilization and alerts |
| `dashboard` | Read-only home-screen aggregates |
| `audit` | Append-only field-level audit trail (`audit_events`) |
| `idempotency` | `Idempotency-Key` replay protection for creates |
| `common` | Response envelope, paging, money rules, auditable entity base |
| `exception` | `ErrorCode`, `ApiException`, global exception handler |
| `validation` | Reusable constraints: currency code, IANA timezone, password policy, null-or-not-blank |
| `logging` | Correlation id filter and the per-request access log |
| `config` | Clock and auditing, OpenAPI, CORS properties |

Dependencies point one way: `auth → user`, `transaction → income, account, category, user`,
`budget → transaction`, `dashboard → everything it summarises`, all of them `→ (common, exception, validation, audit, idempotency)`.
The `user` module never calls `auth`. When a password changes, `user`
publishes a `PasswordChangedEvent`. `auth` handles it in the same database
transaction and revokes that user's refresh tokens.

Where a lower module needs data from a higher one, it defines an interface the
higher module implements: `AccountFlowProvider` (net flows for balances),
`IncomeReceiptProvider` (received per source and pay month) and
`CategoryUsageProvider` (is a category used) are implemented in `transaction`;
`budget` and `income` also implement `CategoryUsageProvider` in small standalone beans
so no two services depend on each other.
On registration, `UserRegisteredEvent` seeds a default Cash account and the
default categories in the same transaction.

Planned modules are `lending`, `borrowing`, `repayment`, `loan`/`emi`,
`recurring`, `report`, `currency`, `notification` and `attachment`. Each will
follow the same layout.

## API conventions

- Versioned base path: `/api/v1`.
- Every response uses the same envelope:

  ```json
  {
    "success": false,
    "message": "Request validation failed",
    "error": { "code": "VALIDATION_FAILED", "fields": [{ "field": "email", "message": "must be a well-formed email address" }] },
    "requestId": "2f0c…",
    "timestamp": "2026-10-31T10:00:00Z"
  }
  ```

  Successful responses carry `data` instead of `error`. `204 No Content`
  responses have no body.
- Status codes: 400 for validation or malformed input, 401 when
  unauthenticated, 403 when forbidden or the account is disabled, 404 when not
  found, 409 for conflicts and concurrent edits, 422 for business-rule
  violations, 429 when rate limited, 500 for unexpected errors (no internal
  details are returned).
- `error.code` values come from `ErrorCode`. Codes are part of the API
  contract, so never rename one.
- DTOs only: JPA entities are never serialized.
- PATCH requests apply only the fields that are present.

## Identity and ownership

- The caller's id comes only from the validated access token (`CurrentUser.id()`).
  No endpoint takes a user id from the path, query or body. Unknown JSON
  fields such as `userId` are ignored.
- Every user-owned table will have a `user_id` column, and every query on it
  will filter by the caller's id. A record that exists but belongs to someone
  else returns 404, the same as a record that does not exist, so ids cannot be
  probed.

## Authentication

- **Access tokens** are HS256 JWTs that live for 15 minutes. Claims are `iss`,
  `aud`, `sub` (the user id), `iat`, `exp` and `jti`. Issuer, audience,
  signature and expiry are all verified. `alg: none` and tokens signed with a
  different key are rejected.
- **Refresh tokens** are 256-bit random opaque values that live for 30 days.
  Only their SHA-256 hash is stored. Each one can be used once: `/refresh`
  revokes it and issues a replacement in the same *family*.
  - If an already-rotated token is presented again, it is treated as stolen
    and the whole family is revoked. Other devices keep their own families.
  - The row is locked with `SELECT … FOR UPDATE`, so concurrent refreshes of
    the same token cannot both succeed.
  - Logout revokes the family. Logout-all and password change revoke every
    family the user has.
  - A nightly job deletes tokens that expired more than 7 days ago.
- Passwords are hashed with BCrypt through Spring's `DelegatingPasswordEncoder`
  (stored with a `{bcrypt}` prefix), so the algorithm can be upgraded later.
  Hashes are upgraded on the next login.
- A login with an unknown email still runs one hash comparison, so response
  times don't reveal which emails are registered.

## Data and time

- UUID primary keys, `TIMESTAMPTZ` for instants, and `created_at`,
  `updated_at` and `version` (for optimistic locking) on mutable entities.
- Flyway owns the schema. Hibernate only validates it (`ddl-auto: validate`).
- The application uses one injected `Clock`. Hibernate's JDBC timezone is UTC.
- User preferences hold `timezone` (IANA), `weekStartDay` (MONDAY by default)
  and `defaultCurrency` (INR by default). These will drive date boundaries in
  reports from Phase 2 on.
- Money: `NUMERIC(19,4)` columns, `BigDecimal` in Java, and every amount
  stored with its own ISO currency code. Changing the default currency never
  changes stored amounts. Amounts may not have more decimals than their currency
  allows (INR 2, JPY 0) and are returned at that scale.
- `transaction_date` is the user's calendar date (DATE), separate from the
  `created_at` instant, so back-dated entries work. "Today" means today in the
  user's timezone.

## Financial model (Phase 2)

Decisions confirmed with the product owner:

- **Balances are derived, never stored.** Balance = opening balance + active
  inflows − active outflows, computed by one SQL aggregate per read. Edits and
  voids can't leave a stale balance behind.
- **Transaction types.** `EXPENSE` and `INCOME` need a category of the same type.
  `TRANSFER` moves money between two of the user's accounts and is never income
  or expense.
- **Credit cards are liabilities.** Their balance is the amount owed, shown
  positive: a card purchase is an expense that raises it, and paying the bill is a
  transfer from the bank that lowers it. The purchase is counted once.
- **Edit in place, delete = void.** `PUT` replaces a transaction and writes an
  `UPDATED` audit event with `{field: {from, to}}`. Void keeps the row (status
  `VOIDED`) but drops it from balances, lists and totals; restore undoes it.
- **Categories** are per user, two levels, typed. Defaults are copied from
  `category_templates`. A used category can be renamed or deactivated but not
  deleted, so history always resolves. EMIs, loan principal and investments are
  not seeded as expense categories: they get their own transaction types later.
- **One currency per account.** A transaction uses its account's currency; a
  transfer needs both accounts in the same currency. Conversion arrives in the
  multi-currency phase. Summaries are per currency, never summed across.
- **Retries are safe.** Creates accept `Idempotency-Key`: the same key and body
  returns the original transaction; a different body is rejected. Duplicates
  that arrive together serialize on the key's primary key.
- **Quick entry.** Only amount and category are required. Account defaults to
  the user's default account, date to today, payment method to the account type.

## Salary, budgets and dashboard (Phase 3)

Decisions confirmed with the product owner:

- **Expected is not received.** An income source's expected monthly amount is a
  plan. Only INCOME transactions count as income; the dashboard reports expected
  and received side by side.
- **Pay period.** An income transaction linked to a source settles one pay month
  (`income_period`), defaulting to the credit month, so a salary credited on
  1 Nov can settle October. Reports still use the credit date.
- **Expectations have history.** `income_expectations` rows apply from their
  month until the next row, so a raise doesn't rewrite earlier months. A row
  with no amount means nothing is expected from that month.
- **Month status:** RECEIVED (≥ expected), PARTIALLY_RECEIVED, PENDING (nothing
  yet, due date not passed), OVERDUE, NOT_EXPECTED.
- **Budgets count actual expenses only**, in the budget's currency; a category
  budget includes its subcategories. No rollover: every period starts fresh.
  Weekly periods follow the user's week-start day. Alert thresholds (default
  75/90/100 %) are reported as crossed; notifications come in Phase 6.
- **Dashboard** figures are in the user's default currency and come from the same
  SQL totals as the transaction list and daily summary. Entries in other
  currencies are counted, not summed.
- **Web app.** A static page at `/app` (no build step, strict CSP, all user data
  inserted as text). Tokens are kept in `sessionStorage`; refresh calls are
  serialised. Phase 9 replaces it with the full frontend.

## Observability

- Every request gets an `X-Request-Id`. A safe id sent by the client is
  reused; otherwise a new one is generated. The id goes into the MDC (so it
  appears in log lines) and into the response header and envelope.
- One access-log line per request: method, path, status and duration. Query
  strings, headers and bodies are never logged.
- The `prod` profile writes structured ECS JSON logs.

## Known trade-offs

- **Rate limiting** is in-memory and per instance. It limits each client IP
  per endpoint on `POST /api/v1/auth/**`. Running more than one instance will
  need a shared store such as Redis.
- **Access tokens are stateless.** After a logout or password change, an
  access token that was already issued still works until it expires (up to
  15 minutes).
- **Strict refresh rotation.** If a client sends two refresh requests at the
  same time with the same token, it signs itself out. Clients must serialise
  refresh calls.
- **Transfers need matching currencies** until exchange rates exist.
- **Account and category lists are not paginated**; they are small per user.
- **Budget amount changes apply to every period**, past ones included; budgets
  have no history table like income expectations do.
- **Budget utilization runs one query per budget.** Fine for a handful of
  budgets; batch it if users keep many.
- **Forgot/reset password** needs an email provider. It is deferred to the
  notifications phase.
