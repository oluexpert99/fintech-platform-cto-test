# Spec — Accounting Service

**Module:** `services/accounting-service/`
**Package root:** `com.example.fintech.accounting`
**Status:** Draft for review
**Related:** [`../docs/ARCHITECTURE.md` §1](../docs/ARCHITECTURE.md#part-1--overall-architecture) · [`../docs/api.md` §11](../docs/api.md#11-journal--reports-accounting-service) · `data-model.spec` (§5.4 `journal`) · `events.spec` (§7.1 `transactions.transfer.completed`)

---

## 1. Purpose

Accounting Service is the **read-side projector** for the platform's ledger. It owns no writes; it exposes paginated views of `journal` entries and reports (trial balance, future: daily statements, audit pulls).

It exists as a separate service for three reasons:
1. **Resource isolation.** Reporting queries (full-table aggregates) must never compete with the transaction hot path for connection pool, CPU, or Mongo working set.
2. **Different privilege model.** The Mongo role is `fintech_reader` — `find` only, no insert/update/delete on any collection. Codebase boundary enforces "no writes ever from this service".
3. **Different SLO.** Reports can be eventually-consistent and cached aggressively; the transaction path can't.

## 2. Scope

### In scope

- `GET /v1/journal-entries` (paginated; filter by account, transactionId, from/to, side)
- `GET /v1/reports/trial-balance` (summary: global totals as-of timestamp)
- `GET /v1/reports/trial-balance/by-account` (paginated per-account breakdown)
- Read-only Mongo access via `fintech_reader` role
- Kafka consumer **stub** of `transactions.transfer.completed` and `transactions.transfer.reversed` with inbox-pattern dedupe (per `events.spec` §6.3) — wired but no projections built yet
- Standard observability + RFC 7807 error responses

### Out of scope (this iteration)

- Materialised projection collections (e.g. `daily_account_balances` rolled up from journal). Calculated on-demand via aggregation pipelines; we'll add materialisation when query latency demands it.
- Reconciliation job (`Σdebits == Σcredits`). Will run as a separate scheduled process, not part of this service's request path.
- Cold-archive integration for journal entries older than the hot retention window.
- Write-back endpoints. **There are none, by design.**

## 3. Contract

### 3.1 HTTP surface

Authoritative in [`../docs/api.md` §11](../docs/api.md#11-journal--reports-accounting-service).

| Method | Path | Auth | Scope / Role |
|---|---|---|---|
| `GET` | `/v1/journal-entries` | required | `admin:*` (auditor or operator) |
| `GET` | `/v1/reports/trial-balance` | required | `admin:*` (auditor) |
| `GET` | `/v1/reports/trial-balance/by-account` | required | `admin:*` (auditor) |

All endpoints return RFC 7807 Problem Details on error, with the same `code` + `params` extension downstream services emit.

### 3.2 Internal package structure

```
com.example.fintech.accounting/
├── AccountingServiceApplication.java
├── api/
│   ├── JournalEntriesController.java
│   ├── ReportsController.java
│   ├── dto/{JournalEntryResponse, TrialBalanceResponse, AccountBalanceResponse,
│   │        PagedResponse, ProblemResponse}.java
│   └── ProblemExceptionHandler.java
├── application/
│   ├── JournalFinder.java
│   └── TrialBalanceCalculator.java
├── persistence/
│   ├── document/{JournalEntryDocument, AccountDocument}.java   ← read-only views of shared collections
│   ├── repository/{JournalEntryRepository, AccountRepository}.java
│   └── init/SchemaInitializer.java                              ← inbox collection only; no writes to shared
├── messaging/
│   └── TransactionEventsListener.java                           ← stub @KafkaListener for projection cache (TODO)
└── config/{SecurityConfig, MongoConfig, KafkaConsumerConfig}.java
```

### 3.3 Mongo role

Service uses the `fintech_reader` Mongo user (read-only across the database). Any attempt to insert/update/delete from this service code returns `Unauthorized` from the database — enforced at the role layer in addition to code discipline.

The two exceptions this service owns and **writes**:
- `inbox_accounting` — consumer-side dedupe (event-id uniqueness) for the Kafka subscriber
- `chart_of_accounts` — the COA itself; seeded once at boot and (in future) maintained by operator endpoints. See §3.4.

### 3.4 Chart of Accounts

A real ledger references a structured Chart of Accounts. We adopt the standard 5-class numbering scheme:

| Prefix | Type | Normal balance side |
|---|---|---|
| `1xxx` | Asset | DR |
| `2xxx` | Liability | CR |
| `3xxx` | Equity | CR |
| `4xxx` | Revenue | CR |
| `5xxx` | Expense | DR |

**Seeded system accounts** (created by `SchemaInitializer` on first boot if absent):

| `_id` | Name | Type | Description |
|---|---|---|---|
| `1000` | Customer Cash Holdings | Asset | Aggregated cash we hold on behalf of users; mirrors the sum of all user wallet balances |
| `1100` | Platform Float | Asset | Our own bank account; populated on top-ups |
| `2100` | Customer Wallet Liability | Liability | What we owe customers, in aggregate |
| `3000` | Retained Earnings | Equity | |
| `4000` | Fee Income | Revenue | Platform fee revenue |
| `4100` | FX Spread Income | Revenue | (future — currency conversion margin) |
| `5000` | Operating Expenses | Expense | |
| `5100` | Bad Debt Write-offs | Expense | Permanent reversals due to fraud / regulatory hold |

**User wallets** are conceptually leaf sub-accounts under `2100`. We do **not** create a `chart_of_accounts` document per user wallet (that would explode the collection at scale). Instead, every journal entry posted on a user wallet uses `coaAccount = "2100.<accountId>"` — a deterministic, computed reference. Parent type (Liability) is derivable from the `2100.` prefix.

**Schema of `chart_of_accounts`:**

```jsonc
{
  "_id":          "2100",
  "name":         "Customer Wallet Liability",
  "type":         "LIABILITY",            // ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE
  "normalSide":   "CREDIT",               // DR for ASSET/EXPENSE; CR for the rest
  "parentId":     null,                   // hierarchy (future)
  "system":       true,                   // false would mean operator-managed
  "currency":     "USD",
  "createdAt":    ISODate("..."),
  "updatedAt":    ISODate("...")
}
```

### 3.5 The `coaAccount` field on `journal`

`journal` documents (owned by Transaction Service per `data-model.spec` §5.4) gain one new optional field: `coaAccount`. Every journal line posted from this iteration onward MUST set it; old rows without it default to `2100.<account>` for backwards-compatible reads.

For the existing TRANSFER flow:
- DEBIT line on source: `coaAccount = "2100." + sourceAccount`
- CREDIT line on destination: `coaAccount = "2100." + destinationAccount`

For REVERSAL: swapped accordingly. Future FEE / REFUND lines reference system accounts directly (`4000`, etc.).

## 4. Behaviour

### 4.1 Journal-entries query

Cursor-paginated (per `api.md` §5 — same pattern as transaction-service). Sort: `postedAt` DESC then `_id` DESC for stability under concurrent inserts. Filter via query params; if the caller is not an auditor/operator we 403.

### 4.2 Trial balance summary

Aggregates the entire journal up to `asOf` (default = now). Two `sum` rollups: `debits`, `credits`. `delta = debits - credits` — **must** be zero; a non-zero value is a P1 reconciliation incident (see `ARCHITECTURE.md` §10).

**COA-type roll-up:** beyond the closed-loop `delta == 0` check, the summary breaks down by COA account type:

```json
{
  "asOf": "...",
  "currency": "USD",
  "totals": { "debits": 123456789, "credits": 123456789, "delta": 0 },
  "byType": {
    "ASSET":     { "debits": ..., "credits": ..., "net":  ... },
    "LIABILITY": { "debits": ..., "credits": ..., "net":  ... },
    "EQUITY":    { "debits": ..., "credits": ..., "net":  ... },
    "REVENUE":   { "debits": ..., "credits": ..., "net":  ... },
    "EXPENSE":   { "debits": ..., "credits": ..., "net":  ... }
  }
}
```

Each line's type is derived from the first digit of `coaAccount` (or the lookup for hierarchical COA refs like `2100.ACC100001` which roll up to the prefix's type).

Cacheable: the same `(asOf, currency)` tuple yields the same result. Cache TTL 60s in-process; production caches further via Redis. Cache miss = one Mongo aggregation.

### 4.3 Trial-balance by-account

Paginated per-account breakdown: `{ account, debits, credits, balance, currency }` per row. Sort by `_id` ASC. Bounded by `asOf`. Million-row reports are real, so this **must** be paginated — `api.md` §5 enforces.

### 4.4 Kafka consumer (stub)

`TransactionEventsListener` subscribes to `transactions.transfer.completed` and `.reversed` in consumer group `accounting`. Per `events.spec` §6.3, every event is deduped via an inbox collection (`inbox_accounting`) before any side effect. For this iteration the handler is a no-op with a log line — the projection caches that justify the consumer arrive in a follow-up.

### 4.5 Configuration

```yaml
spring:
  application.name: accounting-service
  data.mongodb.uri: ${MONGO_URI}        # connection uses fintech_reader credentials
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP}
    consumer:
      group-id: accounting
      enable-auto-commit: false
      auto-offset-reset: earliest
      isolation-level: read_committed
  security.oauth2.resourceserver.jwt.issuer-uri: ${KEYCLOAK_ISSUER_URI}

server.port: ${SERVER_PORT:8080}
management.server.port: ${MANAGEMENT_PORT:8081}

reports:
  cache:
    trial-balance-summary-ttl-seconds: 60
```

### 4.6 Error mapping

Same shape as the other services. Most failures are 401/403/400/500. Notable: no 422s — accounting is read-only.

## 5. Tests

See `transaction-service.spec.md` §5.0 — Testcontainers everywhere. Real Mongo, real Kafka.

- **Unit:** cursor decoding edge cases, aggregation pipeline construction, JWT-role extraction.
- **Integration:** seed `journal` with N entries; verify `GET /journal-entries` pagination + filters; verify trial balance sums and that `delta == 0`; verify the consumer dedupes a duplicate-delivery scenario via `inbox_accounting`.
- **ArchUnit:** no class in this service writes to `journal`, `transactions`, `accounts`, or `outbox_txn`. Only `inbox_accounting` is writeable.

## 6. Operational concerns

| Metric | Notes |
|---|---|
| `http_server_requests_seconds` | Default Spring metric, tagged with route + code |
| `reports_trial_balance_seconds` | Histogram of aggregation latency |
| `reports_trial_balance_delta` | Gauge — should be 0; **alert if non-zero** |
| `kafka_consumer_lag{topic="transactions.transfer.completed",consumer_group="accounting"}` | Standard consumer lag |
| `events_deduped_total{consumer_group="accounting"}` | Inbox dedupe counter |

## 7. Open questions

| # | Question | Default |
|---|---|---|
| 7.1 | Do we materialise `daily_account_balances` or compute on-demand? | **On-demand** for MVP; materialise when p99 latency requires it. |
| 7.2 | Trial-balance cache: in-process or Redis? | **In-process (Caffeine)** for MVP; move to Redis when we have multiple replicas needing shared cache. |
| 7.3 | Should we publish `accounting.reconciliation.failed` events when `delta != 0`? | **Yes** — out of scope for MVP scaffold but should be the alert path long-term. |

## 8. Acceptance criteria

- [ ] Module compiles, boots, tests pass
- [ ] `GET /v1/journal-entries` returns paginated envelope with the `{ data, page }` shape
- [ ] `GET /v1/reports/trial-balance` returns `{ asOf, currency, totals }`
- [ ] `GET /v1/reports/trial-balance/by-account` returns paginated rows
- [ ] Role-restriction integration test: writing to `journal` from this service's connection returns `Unauthorized`
- [ ] Kafka consumer stub subscribes successfully and dedupes via inbox table
- [ ] Gateway routes `/v1/journal-entries` and `/v1/reports/**` to this service, not account-service
