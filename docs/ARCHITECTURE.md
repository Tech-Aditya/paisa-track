# PaisaTrack architecture

Status: Phases 1-4 (foundation and security; accounts, categories and transactions;
salary, budgets and dashboard; lending and borrowing) are done. This document records the decisions later phases build on.

## Shape

A modular monolith: one Spring Boot application with one PostgreSQL database.
Code is split in two:

- `com.paisatrack.platform`: cross-cutting technical concerns with no business
  logic (API envelope, errors, security, logging, validation, money rules,
  persistence base, audit, idempotency).
- `com.paisatrack.modules`: business features. A module keeps entities,
  repositories and services in its root package (internals stay package-private),
  REST controllers in `web` and request/response records in `dto`. Other modules
  use only its public services, `*Ref` records and provider interfaces.

Modules depend on platform, never the reverse.

| Module (`modules.*`) | Responsibility |
|---|---|
| `auth` | Registration and login orchestration, refresh tokens (rotation, reuse detection, revocation), logout |
| `user` | User identity, password hashing and checks, profile, preferences |
| `account` | Accounts, derived balances, default account, per-currency summary |
| `category` | Two-level expense/income categories, default seeding |
| `transaction` | Expenses, income, transfers, void/restore, search, daily summary |
| `income` | Income sources, monthly expectations by effective month, expected vs received |
| `budget` | Overall/category budgets, monthly/weekly/custom periods, utilization and alerts |
| `people` | Contacts you lend to or borrow from |
| `debt` | Money lent and borrowed, repayments, write-offs, overdue status, reminders |
| `loan` | Formal loans, EMI schedules (amortization), EMI payments, prepayments, upcoming/missed EMIs |
| `recurring` | Recurring rules (rent, bills, subscriptions, SIPs), expected occurrences, confirm/skip, generation job |
| `notification` | In-app reminders from every module, read state, per-type preferences, reminder job |
| `report` | Daily/weekly/monthly/yearly/custom reports, like-for-like comparisons, insights |
| `currency` | Currency reference data, exchange rates (manual and provider), conversion |
| `dashboard` | Read-only home-screen aggregates |

| Platform (`platform.*`) | Responsibility |
|---|---|
| `security` | Spring Security filter chain, JWT encode/decode, `CurrentUser`, 401/403 responses, auth rate limiting |
| `audit` | Append-only field-level audit trail (`audit_events`) |
| `idempotency` | `Idempotency-Key` replay protection for creates |
| `api`, `money`, `persistence` | Response envelope and paging; currency precision rules; auditable entity base |
| `exception` | `ErrorCode`, `ApiException`, global exception handler |
| `validation` | Reusable constraints: currency code, IANA timezone, password policy, null-or-not-blank |
| `logging` | Request id, access log, slow-request warnings, startup summary |
| `config` | Clock and auditing, OpenAPI, CORS, web app routes |

Dependencies point one way: `auth → user`, `transaction → income, account, category, user`,
`budget → transaction`, `debt → people, user`, `loan → transaction, account, category`,
`recurring → transaction, account, category`, `notification → loan, recurring, debt, budget, income, user`,
`report → account, budget, category, currency, debt, loan, user` (plus read-only SQL over the ledger tables),
`currency → user`, `dashboard → currency` too,
`dashboard → everything it summarises`. Every module may use `platform`.
The `user` module never calls `auth`. When a password changes, `user`
publishes a `PasswordChangedEvent`. `auth` handles it in the same database
transaction and revokes that user's refresh tokens.

Where a lower module needs data from a higher one, it defines an interface the
higher module implements: `AccountFlowProvider` (net flows for balances),
`IncomeReceiptProvider` (received per source and pay month) and
`CategoryUsageProvider` (is a category used) are implemented in `transaction`;
`budget` and `income` also implement `CategoryUsageProvider` in small standalone beans
so no two services depend on each other. `people` defines `PersonUsageProvider`, which `debt` implements.
`account` defines `LiabilityProvider` (outstanding loan principal for net worth), which `loan` implements
alongside `CategoryUsageProvider` (a loan's interest category can't be deleted).
`transaction` publishes `TransactionChangedEvent` (edited, voided, restored) inside the database transaction;
`recurring` listens so a confirmed occurrence follows its transaction without `transaction` depending on it.
On registration, `UserRegisteredEvent` seeds a default Cash account and the
default categories in the same transaction.

The planned module is `attachment`. Each will
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
  deleted, so history always resolves. Loan principal and investments are not
  expense categories: loans have their own transaction types (Phase 5).
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
- **Web app.** Served from `/app` as ES modules (`js/api`, `dom`, `charts`, `forms`,
  `views/*`) with no build step and a strict CSP. All user data is inserted as text.
  Tokens live in `sessionStorage` and refresh calls are serialised. Static files are
  sent with `Cache-Control: no-cache` (revalidated, 304 when unchanged), so a deploy
  is never stale. Phase 9 can grow it, or replace it with a framework app.

## Lending and borrowing (Phase 4)

Decisions confirmed with the product owner:

- **Tracking only.** Money lent or borrowed lives in `debts` and `debt_repayments`,
  not in `transactions`. It never changes account balances and is never income or
  expense, so the principal-is-not-income/expense rules hold by construction.
- **Net worth stays accounts-only.** What others owe you and what you owe are shown
  next to it (`/debts/summary`, dashboard `debts`), never added in.
- **No interest yet.** Repayments are principal only.
- **Write-off closes the record** with the unpaid balance recorded separately; it is not
  income or expense and can be reopened.
- One table for both directions (`direction` LENT/BORROWED), exposed as `/lending` and
  `/borrowing`. The original amount is never changed by a repayment; each repayment is
  its own row, voided rather than deleted.
- **Outstanding is derived**: principal − active repayments − written-off amount. Status
  (OPEN, PARTIALLY_REPAID, OVERDUE, SETTLED, WRITTEN_OFF) is computed on read using the
  user's "today".
- **Concurrency.** Repayments, voids, write-offs and edits take a row lock on the debt, so
  parallel repayments can't push outstanding below zero. Creates and repayments accept
  `Idempotency-Key`.
- **Reminders** are prepared, not sent: `remind_on` and `/debts/reminders?days=` give the
  notifications phase what it needs.
- People are matched by name (case-insensitive) when a record names one, so "amit" and
  "Amit" are the same contact. A contact with records can't be deleted.

## Loans and EMIs (Phase 5)

Decisions confirmed with the product owner:

- **An EMI is split in the ledger.** Paying an EMI posts two entries from the chosen
  account: the interest as an `EXPENSE` (default category Financial Charges › Interest)
  and the principal as `LOAN_PRINCIPAL`. The account balance drops by the full EMI, but
  only the interest counts as spending, in totals, budgets and the dashboard. Principal is
  never counted twice.
- **Disbursement is optional and never income.** When the money was credited to one of
  your accounts, a `LOAN_DISBURSEMENT` inflow raises that balance; income totals ignore it.
- **Outstanding principal is a liability in net worth**: assets − card balances owed −
  loans outstanding. It is derived (principal − active principal payments), never stored.
- **Standard reducing-balance EMI**: `P·r·(1+r)^n / ((1+r)^n − 1)` with `r` = annual
  rate / 12, rounded to currency precision; 0 % loans split the principal evenly. Each
  month's interest is the opening balance × `r`, and the last installment clears the
  remainder. A bank-quoted EMI can be entered instead; one too small to cover interest
  is rejected.
- **Schedules are stored** (`loan_installments`). Paid rows are frozen. A prepayment
  keeps the EMI and regenerates the unpaid rows from the new balance, so the loan ends
  sooner. Voiding a prepayment regenerates them again.
- **EMIs are paid in order, and only the latest payment can be voided.** This keeps the
  schedule and payments consistent without a reconciliation step. Voiding reverses the
  payment's ledger entries.
- **Status is computed on read**: PAID, MISSED (due date passed), DUE (next, not yet late),
  UPCOMING. `/emis/upcoming` lists missed and soon-due EMIs for reminders.
- **Existing loans.** `emisPaidBeforeTracking` marks earlier installments paid with no
  account and no ledger entries, so past spending isn't invented.
- **Terms lock after the first payment.** Amount, rate, tenure, EMI and dates can change
  only before any payment; name, lender, type, payment account, interest category and notes
  stay editable. A loan with
  payments can't be deleted.
- **Loan-managed entries** carry `transactions.loan_id`. Editing, voiding or restoring them
  through `/transactions` returns `LOAN_MANAGED`; they change only through the loan.
- **Concurrency.** Payments, prepayments, voids and edits take a row lock on the loan, and a
  partial unique index stops two active payments for one installment. Creates and payments
  accept `Idempotency-Key`.

## Recurring transactions and notifications (Phase 6)

Decisions confirmed with the product owner:

- **Confirm only.** A due date creates an expected occurrence and a reminder, never a transaction.
  Confirming records a normal expense, income or transfer (actual amount, date and account may
  differ from the plan); skipping says it didn't happen. Expected occurrences never appear in
  balances, totals, budgets or reports.
- **Scope.** Rules cover expenses, income and transfers such as rent, bills, subscriptions,
  insurance and SIPs. Salary stays in income sources and EMIs in loans, so nothing is expected twice.
- **Overdue stays overdue** until confirmed or skipped. Later dates keep being generated.
- **Reminders** have per-type preferences (on/off, 0-30 days before, default 3). An item gets at
  most one notification per stage: UPCOMING (inside the window), DUE, OVERDUE; budgets get one
  ALERT per period for the highest threshold reached (and one when exceeded).

How it works:

- **Schedules are anchored on the start date** (`start + n × interval`), so a rule starting on the
  31st falls on the last day of shorter months without drifting. Frequencies: daily, weekly,
  monthly, quarterly, yearly, each with an interval (e.g. every 2 weeks).
- **Generation is idempotent.** Occurrences are generated up to 35 days ahead, from the day a rule is
  added or resumed (no back-fill). `UNIQUE (rule_id, due_date)` plus `insert ... on conflict do
  nothing` and a row lock on the user's active rules mean retries, restarts and parallel runs never
  duplicate. Editing the amount, accounts or schedule, pausing or stopping drops expected dates from
  today on and regenerates them; overdue ones are kept.
- **Confirmation** locks the occurrence row, so parallel confirms record one transaction; it also
  accepts `Idempotency-Key`. If the user later voids that transaction from the ledger, the
  occurrence becomes expected again (and confirmed again if the transaction is restored). Reopening
  an occurrence voids its transaction.
- **Notifications** are built from the owning modules' read APIs and stored with a dedupe key
  (`user_id, dedupe_key` unique, insert on conflict do nothing). The hourly job runs per user in
  their own timezone and own transaction; a failure is logged and the rest continue. The web app
  also triggers a check on start.

## Reports and analytics (Phase 7)

Decisions confirmed with the product owner:

- **Investments** are transfers into an account of type `INVESTMENT` (withdrawals are transfers out of
  one). They are never spending; reports show invested, withdrawn and net invested.
- **Loan interest** stays in expenses, so report totals match the dashboard and budgets, and is shown
  as its own line; "consumption" is spending without interest.
- **Like-for-like comparison.** A period still running is compared with the same point of the
  previous period (1-15 Nov vs 1-15 Oct, the clamped day for shorter months; this week so far vs the
  same days last week; year to date vs the same date last year). Finished periods compare in full;
  custom ranges compare with the same number of days just before; a day with the day before.
- **Salary used** = spending in the period ÷ income from SALARY sources credited in it; not shown
  without salary.

How it works:

- **One source of truth.** The `report` module aggregates the ledger directly in SQL (a read model,
  read-only): ACTIVE transactions in one currency with the same date rules as the transaction list,
  daily summary and dashboard, so totals reconcile with the entries behind them (tested by summing the
  transaction list). Each section is one aggregate query; nothing loads transaction histories into
  memory. Series are per day up to 62 days, else per month.
- **Kept apart:** income; spending (of which interest); transfers (of which card bill payments and
  investments); loan principal repaid and loan money received; lending and borrowing started and
  repaid. Only income and spending feed net cash flow and the savings rate.
- **Days and weeks** follow the user's timezone and week-start day; the report's `asOf` is "today"
  for them, and a running period is measured up to it.
- **Insights** are rules over the report's own numbers (spending change ≥ 10 %, the top category, a
  category up ≥ 25 % that is ≥ 10 % of spending, savings-rate shifts ≥ 5 points, budgets over or
  near their limit, interest share, EMIs due in 30 days, money invested). Nothing is estimated.
- Sections that depend on other modules (budgets, salary, lending, loans) are left out of filtered
  custom reports, where they would not match the filter.

## Multi-currency (Phase 8)

Decisions confirmed with the product owner:

- **Rates: manual plus an optional provider.** Users can always enter a rate. An `ExchangeRateProvider`
  (Frankfurter, ECB reference rates, no key) fetches daily when `FX_PROVIDER=frankfurter`; it is off by
  default, so the app never needs the network. Rates are never hardcoded.
- **Each entry converts at the rate of its own date**: the latest rate on or before it. Past totals don't
  move when today's rate does.
- **Cross-currency transfers record both sides**: `amount` in the source currency and `to_amount` in the
  destination's; the implied rate is derived. Still never income or spending.
- **Combined totals in the default currency** on the dashboard and in reports (which can also run in any
  other currency). Entries without a rate are listed as unconverted, with the earliest date lacking one, and
  are never added unconverted.

How it works:

- **Nothing is rewritten.** Every account, transaction, budget, loan and debt keeps its own currency.
  Changing the default currency only changes how totals are shown.
- **Rate lookup** (`RateBook`): for a pair and date, the latest manual or provider rate on or before it,
  direct or inverse, or crossed through the provider's base (EUR). The latest day wins; on the same day a
  manual rate wins. Manual rates are private; provider rates are shared and stored once per pair and day
  (an upsert, so repeated fetches are harmless).
- **Conversions are explained.** A single conversion returns the original amount and currency, rate, rate
  date, source and converted amount. Reports and the dashboard list, per other currency, how many entries
  were converted, the range of rate dates used and how many had no rate.
- **Converted aggregation.** Report and dashboard queries group by currency and day; each group is
  converted with that day's rate and then summed, so nothing loads individual transactions into memory.
  Account figures (balances, money in and out) stay in the account's own currency; net worth converts each
  currency's totals at today's rate.
- Budgets count spending in their own currency only.

## Exports, receipts, import and calendar (Phase 10)

Decisions confirmed with the product owner:

- **Receipts live in a private folder on the server** (`ATTACHMENTS_DIR`) behind an `AttachmentStorage`
  interface, so object storage can replace it without touching the rest. Files are named by
  `userId/attachmentId`, never by what the user uploaded; the stored name is sanitised and its extension
  always matches the detected type.
- **Statement duplicates are flagged and left unticked**, never silently dropped: the user can still tick
  a row that really is new.
- **Voice entry uses the browser's own speech recognition** and only fills the form. Nothing is saved
  until the user presses Save; unsupported browsers don't show the button.
- **No shared/family expenses yet.**

How it works:

- **Exports** reuse the same services as the screens (`TransactionQueryService`, `ReportService`), so a
  file always shows the same figures as the page. Files are built in memory and never stored. Excel uses
  streaming (SXSSF); PDF uses OpenPDF. Limits: 20,000 rows and 3 years per transaction export. CSV cells
  starting with `= + - @` are prefixed with `'` (spreadsheet formula injection).
- **Receipts**: type sniffed from magic bytes (JPEG, PNG, WEBP, PDF); 5 MB and 5 per transaction; SHA-256
  stored. Upload writes to a temp file and moves it into place; if the database write fails the file is
  removed, and a delete removes the file only after the row's delete commits. Content is served with
  `nosniff`, `Cache-Control: private, no-store` and `Content-Security-Policy: default-src 'none'`. The web
  app fetches files with the bearer token and shows them from `blob:` URLs.
- **Import** is stateless: preview parses the file and returns rows; commit receives the rows the user
  kept and creates ordinary expenses and income through `TransactionService` (same validation, audit and
  events), all in one database transaction and once per `Idempotency-Key`. A batch row records file name
  and count. Duplicate check: same account, same direction (transfers in/out included), same amount,
  within 2 days; each existing entry matches at most one line.
- **Category suggestions** (`CategorySuggestionService`): words of the text against the user's last two
  years of categorised entries (weighted by how often), then a keyword list of well-known merchants mapped to
  the built-in categories by system code. Shared by the add dialog and import.
- **Calendar** (`/calendar`): daily totals from the report query, converted per day like reports; events
  from recurring occurrences, stored EMI schedules (paid or not), lending/borrowing due dates (settled or
  not) and each income source's expected day (received once a receipt settles that month).

## Observability

- Every request gets an `X-Request-Id`. A safe id sent by the client is
  reused; otherwise a new one is generated. The id goes into the MDC (so it
  appears in log lines) and into the response header and envelope.
- One access-log line per request: method, path, status and duration. Server
  errors and requests over `paisatrack.logging.slow-request-threshold` log at WARN;
  health checks and static files at DEBUG. Query strings, headers and bodies are
  never logged.
- The authenticated user id goes into the MDC as `userId`. The `prod` profile
  writes ECS JSON in which `requestId` and `userId` are fields; the dev console
  pattern shows only the request id, to keep lines short.
- A single `PaisaTrack ready` line at startup gives the profile, port, database host
  (no credentials) and whether API docs are on.
- Scheduled jobs log their outcome and duration (`Job refresh-token-cleanup done`,
  `Job recurring-generation done users=… created=… failed=…`). Per-user failures are WARN lines with
  the user id and error type.
- Logback is set up in `logback-spring.xml`: dev writes a readable console and an async rolling file
  (`logs/paisatrack.log`, with request and user ids); prod writes ECS JSON to stdout, plus an async
  rolling JSON file with the `logfile` profile. The user id stays in the MDC until the access line
  is written, so every access line names its user.
- A banner and a startup summary (profile, local/network/web/docs/health URLs, public URL from
  `APP_PUBLIC_URL`, database host) are printed once the app is ready. The prod banner is off by
  default so stdout stays pure JSON.

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
- **Loans have one fixed rate.** Floating-rate resets, EMI changes after a prepayment
  and partial EMIs aren't modelled; the stored schedule is regenerated only on prepay
  and void.
- **Assets bought with a loan aren't tracked** (no house or car value yet), so a home loan
  without a disbursement account makes net worth negative.
- **Exports are synchronous.** Large exports are capped (20,000 rows) rather than queued.
- **Receipts are on local disk.** Several app instances need shared storage (a volume or object storage
  behind `AttachmentStorage`); back the folder up with the database. Account deletion (when it comes)
  must remove the user's folder too.
- **Import reads CSV only.** Excel and PDF statements must be saved as CSV first; an imported row isn't
  linked back to its batch, so a whole import can't be undone in one step.
- **Suggestions are rule- and history-based**, not machine learning.
- **Budgets don't convert.** A budget only counts spending in its own currency.
- **Provider rates are daily reference rates** (ECB, about 30 currencies), not live market or card rates.
- **Account balances in reports are current balances**, next to the period's money in and out;
  balance-as-of-a-date is not computed.
- **Reminders are in-app only.** Email/push delivery can hang off the notifications table later.
- **The notification job visits every active user hourly.** Fine for thousands of users; beyond that,
  shard by user or keep a "next reminder at" per user.
- **Recurring amounts are fixed per rule.** Variable bills are handled at confirmation (the actual
  amount), not with estimates.
- **Forgot/reset password** needs an email provider. It is deferred to the
  notifications phase.
