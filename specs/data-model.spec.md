# Spec — Data model (MongoDB)

**Scope:** All MongoDB collections owned by the platform's services. This document is the canonical schema contract; any service that touches Mongo touches it through the shapes defined here.
**Status:** Draft for review
**Related:** [`../docs/ARCHITECTURE.md` §2](../docs/ARCHITECTURE.md#part-2--data-architecture) · [ADR-0001](../docs/decisions/0001-mongodb-as-ledger-store.md) · [ADR-0002](../docs/decisions/0002-idempotency-and-exactly-once.md) · service specs (`transaction-service`, `account-service`, `auth-service`)

---

## 1. Purpose

A FinTech platform's data is its product. Service code can be rewritten; the schema is forever (existing rows last as long as the regulator's retention period — typically 7 years). This spec pins down every collection so that:

1. The schema is **versioned and reviewable** as code, not as tribal knowledge.
2. Validators, indexes, and roles are **created by migrations** with the same JSON definitions that `application.yaml` references — not by hand in a Mongo shell, never to be repeated.
3. Cross-service writes (Transaction Service writing `accounts.balance` under one transaction) have a single explicit document.

## 2. Scope

### In scope

- Every collection used by any of the four services in compose.
- Field types, required fields, JSON Schema validators applied at collection level.
- Indexes (compound, unique, partial, TTL).
- Role-based privileges (which Mongo user can read / insert / update / delete).
- Multi-document transaction boundaries (which collections are touched together).
- Schema migration tooling.
- Sharding plan for the Part 9 scale targets.

### Out of scope

- Read-model projections / views (owned by the consumers that build them — e.g. Accounting projector if/when implemented). They derive from the canonical collections here.
- Backups, PITR, and operational concerns — covered in [ADR-0005](../docs/decisions/0005-multi-region-and-ha.md).
- Keycloak's internal schema — Keycloak owns its own PostgreSQL (or H2 in dev) and is opaque to us.

---

## 3. Cross-cutting conventions

### 3.1 Field types

| Concept | Mongo type | Rationale |
|---|---|---|
| Identifier (ULID/UUID strings with prefix) | `string` | Opaque, time-sortable, debug-friendly |
| Monetary amount | `long` (BSON `Int64`) — **minor units** | No floats anywhere; matches the Java `long` we use in code |
| Currency | `string` (3-char ISO 4217) | Validated by JSON Schema `pattern` |
| Timestamp | `date` (BSON `Date`, UTC) | Indexable; sortable; no string-comparison gotchas |
| Enum | `string` from a closed set | Validated by JSON Schema `enum` |
| Version (optimistic lock) | `long` | Monotonically increasing per document |
| Boolean | `bool` | Self-explanatory |

**No `Decimal128`.** At our scale (1 667 tx/s, max value `Long.MAX_VALUE` ≈ 9.2 × 10¹⁸ minor units = 92 quadrillion units), `long` is more than sufficient. `Decimal128` is for use cases that require arbitrary precision; we do not. The Java `long` ↔ Mongo `Int64` round-trip is lossless, which `Decimal128 ↔ BigDecimal` is not in some driver versions.

### 3.2 Document conventions

Every document has:

| Field | Type | Purpose |
|---|---|---|
| `_id` | string | The resource ID (ULID + prefix). We do **not** use Mongo's default `ObjectId` — opaque IDs we control are friendlier across the API. |
| `createdAt` | date | Set on insert, never modified. |
| `updatedAt` | date | Set on every update. |
| `version` | long | Optimistic-lock token, incremented on every update. |

### 3.3 Validators are enforced

Every collection is created with a JSON Schema validator (`validator` + `validationLevel: "strict"` + `validationAction: "error"`). A write that violates the validator fails — there is no quiet acceptance.

Validators are committed to `infra/mongo/schemas/<collection>.schema.json` and applied by the migration tool (§7).

### 3.4 Role-based privileges

Three Mongo users, created by the init script (`infra/mongo/init-roles.js`):

| Role | Privileges | Used by |
|---|---|---|
| `fintech_writer` | `find`, `insert`, `update`, `remove` on most collections; **NOT** on `journal` (insert-only) | Account Service, Auth Service |
| `fintech_journal_writer` | `find`, `insert` on `journal` and `journal-only` write on `accounts`, `transactions`, `outbox` — see §5.3 | Transaction Service only |
| `fintech_reader` | `find` only, across all collections | Accounting projector, support tools |

The split between `fintech_writer` and `fintech_journal_writer` exists to enforce **at the database level** that only Transaction Service can write the journal. A bug in Account Service that tries to insert into `journal` fails with `Unauthorized`, not with a silent corruption.

### 3.5 No referential integrity in Mongo, but…

Mongo has no FK constraints. We enforce referential integrity in application code (with integration tests) and use **denormalisation discipline**:

- `accounts.ownerUserId` references `users._id`. The owning service (Account) validates the user exists at account-open time. Deletes are forbidden (we soft-delete via `status`); the FK can never dangle.
- `transactions.sourceAccount` / `destinationAccount` reference `accounts._id`. Validated at transfer time inside the Mongo transaction.
- `journal.transactionId` references `transactions._id`. Inserted in the same transaction → guaranteed consistent.

---

## 4. Collection catalogue (summary)

| Collection | Owned by | Writers | Readers | Estimated size at 1M users |
|---|---|---|---|---|
| `users` | Auth Service | Auth Service | All | ~1M docs, ~1 GB |
| `accounts` | Account Service | Account Service, **Transaction Service** (balance only, under transaction) | All | ~2M docs (avg 2 accounts/user), ~500 MB |
| `transactions` | Transaction Service | Transaction Service | All | ~1.3 B docs at 1M tx/day for 3 years, **sharded** |
| `journal` | Transaction Service | Transaction Service (insert-only) | All | ~2.6 B docs (2× transactions), **sharded**, append-only |
| `outbox_txn` | Transaction Service | Transaction Service | Transaction Service publisher | ~10k docs at any time (drained quickly) |
| `outbox_acc` | Account Service | Account Service | Account Service publisher | small |
| `outbox_auth` | Auth Service | Auth Service | Auth Service publisher | small |
| `sessions` | Auth Service | Auth Service | Auth Service | ~5M docs at peak (sessions expire) |
| `chart_of_accounts` | Accounting Service | Accounting Service (seeded once at boot) | All | ~10 system accounts; user wallets are computed refs, not stored docs |
| `inbox_accounting` | Accounting Service | Accounting Service | Accounting Service | per-event dedupe row; TTL ~30 days |
| `idempotency_long_term` | (shared utility) | All write paths | All write paths | ~24h sliding window |

We deliberately use **per-service outbox collections** (`outbox_txn`, `outbox_acc`, `outbox_auth`) rather than one shared `outbox`. Reasons: each service's publisher reads only its own; lease contention is isolated; per-service metrics; clearer ownership.

---

## 5. Collections — detail

The full JSON Schema for each lives in `infra/mongo/schemas/<collection>.schema.json`. The summaries below are the spec; the JSON Schema is the executable contract that the validator enforces.

### 5.1 `users`

```jsonc
{
  "_id":         "U-01HZ...",
  "email":       "alice@example.com",
  "phone":       "+447700900000",
  "fullName":    "Alice Liddell",
  "keycloakSub": "auth0|abc123",     // foreign key into Keycloak; the JWT's `sub` claim
  "status":      "ACTIVE",            // PENDING_VERIFICATION | ACTIVE | SUSPENDED | DELETED
  "kycLevel":    "BASIC",             // NONE | BASIC | ENHANCED
  "createdAt":   ISODate("2026-05-28T10:00:00Z"),
  "updatedAt":   ISODate("2026-05-28T10:00:00Z"),
  "version":     1
}
```

**JSON Schema highlights:**
- `email` matches `^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$`
- `phone` matches E.164 `^\\+[1-9]\\d{1,14}$`
- `status` ∈ closed enum
- `email` is unique (see indexes)

**Indexes:**

| Index | Reason |
|---|---|
| `{ email: 1 }` **unique** | Registration uniqueness; login lookup |
| `{ keycloakSub: 1 }` **unique** | JWT-`sub` → user lookup (hot path) |
| `{ status: 1, createdAt: -1 }` partial (`status: "PENDING_VERIFICATION"`) | Pending-verification cleanup job |

**Why we keep a local copy of the user separately from Keycloak:** we need `_id` to be referenced by `accounts.ownerUserId`, we need to attach domain fields (`kycLevel`), and we need fast list/search without Keycloak admin-API calls. Keycloak remains the source of truth for credentials and MFA factors.

### 5.2 `accounts`

```jsonc
{
  "_id":          "ACC100042",
  "ownerUserId":  "U-01HZ...",
  "currency":     "USD",
  "type":         "CHECKING",         // CHECKING | SAVINGS
  "label":        "Daily spending",
  "balance":      12345,              // minor units, long
  "status":       "ACTIVE",           // ACTIVE | FROZEN | CLOSED
  "statusReason": "USER_REQUESTED",   // present when status != ACTIVE
  "frozenAt":     null,
  "closedAt":    null,
  "createdAt":   ISODate("..."),
  "updatedAt":   ISODate("..."),
  "version":      42
}
```

**JSON Schema highlights:**
- `balance >= 0` enforced by `{ "bsonType": "long", "minimum": 0 }` — a database-level guard against negative balances; conditional update in the service layer is the primary protection but this is defence in depth.
- `currency` matches `^[A-Z]{3}$`.
- `status` ∈ `["ACTIVE","FROZEN","CLOSED"]`.
- `statusReason` enum: `USER_REQUESTED`, `USER_REQUEST_CLEARED`, `KYC_PENDING`, `FRAUD_SUSPECTED`, `COMPLIANCE_HOLD`, `CUSTOMER_CLOSED`.
- If `status == FROZEN`, `statusReason` and `frozenAt` must be set (JSON Schema `oneOf`).
- If `status == CLOSED`, `closedAt` must be set and `balance == 0`.

**Indexes:**

| Index | Reason |
|---|---|
| `{ _id: 1 }` (default) | Point lookup during transfer |
| `{ ownerUserId: 1, createdAt: -1 }` | List a user's accounts |
| `{ ownerUserId: 1, status: 1 }` | Filter by status in lists |

**Cross-service write rule:**
- **Account Service** writes the entire document on create and the `label`/`status`/`statusReason` on patch.
- **Transaction Service** writes **only** `balance`, `updatedAt`, and `version` — and only inside a Mongo transaction. It uses a privileged role (`fintech_journal_writer`) that the database restricts to those fields via a **document validator** check pattern; see §5.3.

### 5.3 `transactions`

```jsonc
{
  "_id":                   "TX-01HZ...",
  "idempotencyKey":        "sha256-of-scoped-key",
  "type":                  "TRANSFER",       // TRANSFER | REVERSAL | FEE | REFUND
  "status":                "COMPLETED",      // PENDING | COMPLETED | FAILED | REVERSED
  "sourceAccount":         "ACC001",
  "destinationAccount":    "ACC002",
  "amount":                100,
  "currency":              "USD",
  "description":           "Coffee",
  "journalLineIds":        ["JL-01HZ...", "JL-01HZ..."],
  "correctsTransactionId": null,             // set on REVERSAL
  "reason":                null,             // set on REVERSAL
  "approverId":            null,             // set on REVERSAL (dual control)
  "callerSub":             "U-01HZ...",      // the JWT sub that initiated
  "payloadHash":           "sha256-...",     // for idempotent-replay payload-match
  "createdAt":             ISODate("..."),
  "completedAt":           ISODate("..."),
  "version":               1
}
```

**JSON Schema highlights:**
- `amount > 0` (minor units, long, max 9 223 372 036 854 775 807 — practical ceiling enforced in policy).
- `type` enum; `type == REVERSAL` requires `correctsTransactionId`, `reason`, `approverId` non-null (JSON Schema `if/then`).
- `status == COMPLETED` requires `completedAt` and `journalLineIds.length == 2`.
- `sourceAccount != destinationAccount` — enforced as JSON Schema `not` predicate on equality (this is doable in JSON Schema 2020-12 with a custom keyword via Mongo's validator; if not, application-level only, with a unit test).

**Indexes:**

| Index | Reason |
|---|---|
| `{ idempotencyKey: 1 }` **unique** | The race-free idempotency guarantee. See [ADR-0002](../docs/decisions/0002-idempotency-and-exactly-once.md). |
| `{ sourceAccount: 1, createdAt: -1 }` | History endpoint for source |
| `{ destinationAccount: 1, createdAt: -1 }` | History endpoint for destination |
| `{ callerSub: 1, createdAt: -1 }` | List all my transactions (across accounts) |
| `{ status: 1, createdAt: 1 }` partial (`status: "PENDING"`) | Stuck-PENDING detector |
| `{ correctsTransactionId: 1 }` partial (`correctsTransactionId: { $exists: true }`) | Reversal lookup |

**The "Transaction Service only writes balance on accounts" rule:**

We enforce this at the **role + validator + code** layer:
- The `fintech_journal_writer` role grants `update` on `accounts` — but the *validator* on `accounts` (§5.2) makes any modification to non-`balance`/`updatedAt`/`version` fields fail.
- The `fintech_writer` role (used by Account Service) is granted `update` on the full schema; it does **not** have `insert` permission on `journal` or `transactions`.
- Application code enforces field-level write discipline (the repository custom methods `conditionalDebit` / `conditionalCredit` are the only ways `accounts.balance` is touched from Transaction Service).

If a future engineer adds a method that bypasses these rules, the integration test [in §5.2 of `transaction-service.spec.md`] catches it because the test asserts that the only field of `accounts` that changes during a transfer is `balance`, `updatedAt`, and `version`.

### 5.4 `journal`

```jsonc
{
  "_id":           "JL-01HZ...",
  "transactionId": "TX-01HZ...",
  "account":       "ACC001",          // user wallet (sub-account of 2100)
  "coaAccount":    "2100.ACC001",     // formal Chart-of-Accounts reference; see accounting-service.spec §3.4
  "side":          "DEBIT",           // DEBIT | CREDIT
  "amount":        100,               // always positive; `side` carries the sign
  "currency":      "USD",
  "postedAt":      ISODate("...")
}
```

For TRANSFER and REVERSAL lines `coaAccount = "2100." + account` (user wallets are leaf sub-accounts of `2100 Customer Wallet Liability`). For system-account lines (future FEE, BAD_DEBT, etc.) the `coaAccount` is a top-level COA number (`4000`, `5100`, …); the `account` field is then `null` because no user wallet is touched.

**No `version`, no `updatedAt`.** Journal entries are **immutable** — there is no scenario where a posted entry is modified.

**JSON Schema highlights:**
- All fields required.
- `amount > 0`.
- `side` ∈ `["DEBIT","CREDIT"]`.
- Currency matches `^[A-Z]{3}$`.

**Indexes:**

| Index | Reason |
|---|---|
| `{ transactionId: 1 }` | Tie journal lines to a transaction; 2 lines per tx; bounded |
| `{ account: 1, postedAt: -1 }` | Statement reconstruction; balance reconciliation |
| `{ postedAt: 1 }` | Time-range queries for reports |

**Append-only enforcement:**

The database role `fintech_journal_writer` is granted `find` and **`insert`** on `journal` — explicitly **not** `update` or `remove`. Attempting an update returns `Unauthorized` from Mongo.

We additionally rely on archival: rows older than `RETENTION_HOT` days (default 90) are moved to cold storage (S3-compatible bucket with object-lock for compliance retention). The cold archive is the same JSON; the hot collection only carries the recent window.

### 5.5 `outbox_txn` (and per-service `outbox_<svc>`)

```jsonc
{
  "_id":         "OB-01HZ...",
  "aggregateId": "TX-01HZ...",
  "topic":       "transactions.transfer.completed",
  "eventId":     "01HZ...",         // ULID; carried in envelope; consumer-dedupe key
  "payload":     {                   // full event envelope, JSON
    "eventId":      "01HZ...",
    "eventType":    "TransactionCompletedEvent",
    "eventVersion": 1,
    "occurredAt":   "2026-05-28T10:00:00Z",
    "data":         { ... }
  },
  "status":      "PENDING",          // PENDING | SENT | POISONED
  "attempts":    0,
  "leaseUntil":  ISODate("1970-01-01T00:00:00Z"),  // never-leased
  "leasedBy":    null,
  "createdAt":   ISODate("..."),
  "sentAt":      null,
  "kafkaPartition": null,
  "kafkaOffset":    null,
  "lastError":   null
}
```

**JSON Schema highlights:**
- `status` enum.
- `attempts >= 0`.
- `topic` matches `^[a-z0-9.-]+$` (Kafka topic naming rule).

**Indexes:**

| Index | Reason |
|---|---|
| `{ status: 1, leaseUntil: 1, createdAt: 1 }` | Publisher's lease-claim query (see `transaction-service.spec` §4.4.1) |
| `{ eventId: 1 }` **unique** | Defence against double-insert from a buggy producer |
| `{ status: 1, sentAt: 1 }` partial (`status: "SENT"`) | TTL-based cleanup (sent rows deleted after 7 days, archived to cold storage first) |

**TTL:** sent rows are removed after 7 days via a TTL index on a separate field (`expireAt`, set to `sentAt + 7d` at the moment of `markSent`). We don't TTL `PENDING` or `POISONED` rows — those need human attention.

### 5.6 `sessions`

A **display-metadata** collection — authoritative session state (revocation lists, refresh-token rotation) lives in Keycloak. We mirror enough into Mongo to render the "Active sessions" UX without round-tripping to Keycloak's admin API on every list call.

```jsonc
{
  "_id":           "SES-01HZ...",     // matches the sessionId we return in the API
  "userId":        "U-01HZ...",
  "keycloakSession": "abc-def",       // for cross-reference and revocation
  "deviceLabel":   "iPhone 15 Pro (Safari)",  // derived from User-Agent
  "ipApprox":      "203.0.113.0/24",  // /24-anonymised for GDPR
  "createdAt":     ISODate("..."),
  "lastSeenAt":    ISODate("..."),
  "expiresAt":     ISODate("...")     // = createdAt + refreshTokenLifetime
}
```

**Indexes:**

| Index | Reason |
|---|---|
| `{ userId: 1, lastSeenAt: -1 }` | List my sessions |
| `{ expiresAt: 1 }` **TTL** | Auto-clean expired sessions |
| `{ keycloakSession: 1 }` | Cross-reference on revocation webhook |

### 5.7 `idempotency_long_term` (utility — used only where the resource collection's own unique index isn't sufficient)

Most endpoints use the **resource collection's own unique index on `idempotencyKey`** (e.g., `transactions.idempotencyKey`). The shared `idempotency_long_term` collection exists for the edge case of endpoints whose state-change *doesn't* create a row in a resource collection (e.g., a future webhook-acknowledge endpoint).

```jsonc
{
  "_id":           "sha256-of-scopedKey",
  "userId":        "U-01HZ...",
  "endpoint":      "/v1/...",
  "payloadHash":   "sha256-...",
  "responseStatus": 200,
  "responseBody":  "...",            // JSON string
  "expireAt":      ISODate("...")    // = createdAt + 24h
}
```

**TTL:** `{ expireAt: 1 }` TTL index removes rows 24h after their original write.

For this submission we do **not** create rows in this collection — every state-changing endpoint maps to a resource collection. The collection schema is documented here so future engineers don't reinvent it.

---

## 6. Multi-document transactions — boundaries

A Mongo multi-document transaction must touch documents on a single replica set (or, for sharded clusters, ideally a single shard). The **transfer transaction** (the only multi-doc TX in this submission) touches:

| Collection | Operation | Document(s) |
|---|---|---|
| `accounts` | `updateOne` × 2 | source + destination |
| `journal` | `insertMany` | 2 lines (debit + credit) |
| `transactions` | `insertOne` | 1 |
| `outbox_txn` | `insertOne` | 1 |

**Sharding compatibility:** the shard key for `accounts` is `hash(_id)`; for `journal` it's `hash(transactionId)`; for `transactions` it's `hash(_id)`; for `outbox_txn` it's `hash(aggregateId)` (which equals the transactionId).

When a transfer touches two accounts on different shards, Mongo's distributed transactions coordinate across shards — slower than single-shard but still ACID. We monitor the proportion of cross-shard transfers and may revisit shard key choice if it exceeds 30 %.

**Read concern / write concern** inside the transaction:
- `readConcern: "snapshot"`
- `writeConcern: { w: "majority", j: true }`

Outside the transaction (history reads, list endpoints):
- `readConcern: "majority"` on the write path's read-your-writes (balance reads)
- `readConcern: "local"` on reporting / list endpoints (lower latency, eventual is fine)

---

## 7. Migrations

Schema-and-index initialisation is owned by a `SchemaInitializer` Spring bean per service. The bean implements `ApplicationListener<ApplicationReadyEvent>` and calls `MongoTemplate.indexOps(...).createIndex(...)` for each declared index. Each call is idempotent — Mongo's `createIndex` is a no-op when the index already exists with the same definition.

**Why not Mongock / Flyway / Liquibase?** At time of writing there is **no Spring-Boot-4-compatible Mongock release** (the latest published artifact, `mongock-springboot-v3` 5.5.1, is tied to Spring Framework 6 / Spring Boot 3.x). Rather than pin the platform back to Spring Boot 3.x, we own initialisation in-process for the test submission. The cost: no distributed migration lock and no audited change-log. We accept this for the index-only scope; if the schema grows beyond indexes (e.g. data backfills), we reintroduce a proper migration tool once SB4 support lands.

**Bootstrap indexes (owned by each service's `SchemaInitializer`):**

| Service | Indexes |
|---|---|
| Auth | `users.email` unique, `users.keycloakSub` unique, `sessions` TTL, `outbox_auth` lease + eventId-unique + TTL |
| Account | `accounts` ownerUserId + status, `outbox_acc` lease + eventId-unique + TTL |
| Transaction | `transactions.idempotencyKey` unique + history indexes, `journal` lookup indexes, `outbox_txn` lease + eventId-unique + TTL |

**Schema validators** on collections (the JSON Schema `validator` option) are applied via a one-shot init script in `infra/mongo/init-replica-set.js` — see `docker-compose.spec` §5.3. That script also creates the role-based Mongo users (`fintech_writer`, `fintech_journal_writer`, `fintech_reader`).

---

## 8. Validation that the model holds

A daily **reconciliation job** (not part of any service in this submission, but documented here as the canonical check) asserts:

```
for each account a:
  Σ(journal.credits where account=a) - Σ(journal.debits where account=a)
    must equal
  accounts[a].balance

for the whole system:
  Σ(journal.credits) == Σ(journal.debits)              (double-entry invariant)
  Σ(accounts.balance where status != CLOSED) == that sum
```

If either fails, the job emits a P1 alert and writes a row to a `reconciliation_failures` collection (out of scope for this spec). The Part 10 *double-debit* defence relies on this job running.

A **continuous lightweight version** runs every 5 minutes against the most recent N transactions, so the alerting latency is bounded.

---

## 9. Tests

See `transaction-service.spec.md` §5.0 for the Testing principles — **Testcontainers is the only mechanism for any test that touches Mongo**. No Flapdoodle.

Data-model-specific tests (`infra/mongo/src/test/java/.../DataModelSpecTest.java` or equivalent):

| Scenario | Assertion |
|---|---|
| Apply all migrations on a fresh Mongo container | Every collection exists; every validator is installed; every index is present and the indexes are *exactly* the set declared here (no extras left over from experiments) |
| Insert a document that violates each validator | Each validator rejects with `WriteError` |
| `fintech_writer` cannot insert into `journal` | Mongo returns `Unauthorized` |
| `fintech_writer` cannot update `journal` | Mongo returns `Unauthorized` |
| `fintech_journal_writer` modifying a non-balance field on `accounts` | Validator rejects (or, if Mongo doesn't support that level of field-pin, application test catches via the cross-service-write rule in `transaction-service.spec` §5.2) |
| `journal` insert with `update` privilege | Confirmed denied |
| TTL on `outbox_txn` | `expireAt`-tagged rows older than the TTL are removed (test forces TTL monitor to run) |

---

## 10. Operational concerns

### 10.1 Capacity & growth

| Collection | Steady-state size at 1M users | Growth rate at 100k tx/min |
|---|---|---|
| `users` | ~1M docs (~1 GB) | linear with signups (~1 k/day) |
| `accounts` | ~2M docs (~500 MB) | linear with onboarding |
| `transactions` | sharded | **2.6 M / 30 min**; ~50 GB / day; ~18 TB / year |
| `journal` | sharded | **5.2 M / 30 min** (2 per tx); ~100 GB / day; ~36 TB / year |
| `outbox_*` | tiny (drained) | ~50 GB / day churned through; TTL keeps steady state low |

We need **archival** of `transactions` and `journal` to object storage; the hot collection holds 90 days, archive holds the regulatory window (7 years typically). The archive format is the same JSON; tools must remain able to scan it (e.g. via Athena on the S3 bucket).

### 10.2 Sharding plan

| Collection | Shard key | When to enable |
|---|---|---|
| `accounts` | `hash(_id)` | When the collection exceeds ~50 GB |
| `transactions` | `hash(_id)` | Day one — we know it will be large |
| `journal` | `hash(transactionId)` | Day one |
| `outbox_txn` | `hash(aggregateId)` | Optional; outbox is small enough to live on one shard |
| `users` / `sessions` / `outbox_acc` / `outbox_auth` | Not sharded | Small |

Shard key choice rationale: `hash(_id)` distributes evenly and is the only honest choice for write-balanced collections. `createdAt` would be monotonic → hot shard.

### 10.3 Indexes are budgeted

Every additional index pays for itself in write throughput. We resist the urge to add "just in case" indexes. Any new index in this spec must come with a query that needs it (in the relevant service spec). Reviewers should reject indexes without a matching query.

---

## 11. Open questions

| # | Question | Default |
|---|---|---|
| 11.1 | Mongock or [Liquibase MongoDB extension](https://github.com/liquibase/liquibase-mongodb)? | **Mongock** — better Spring Boot integration, mature, Java-native change-logs. |
| 11.2 | Do we want `accounts.balance` enforced by validator (`minimum: 0`) when the application layer already prevents negatives? | **Yes** — defence in depth. Validator cost is negligible per write. |
| 11.3 | Should `idempotencyKey` be the document `_id` itself instead of a separate field? | **No.** `_id = TX-<ulid>` (sortable, time-ordered) is far more useful for indexes and as the API resource ID. Idempotency key is the *write-time* uniqueness; resource ID is the *lifetime* identifier. |
| 11.4 | Should we use `Decimal128` for `amount` after all? | **No** — `long` is sufficient at our scale and the driver round-trip is more reliable. Documented in §3.1. |

Defaults applied unless flagged.

---

## 12. Acceptance criteria

- [ ] `infra/mongo/schemas/<collection>.schema.json` exists for every collection in §5
- [ ] Each migration in §7 applies cleanly to a fresh Mongo container and is idempotent
- [ ] Every index declared in §5 is created by a migration and the data-model test asserts it exists
- [ ] No index exists in the cluster that is not declared in this spec (the test enumerates `db.<coll>.getIndexes()` and compares to the declared set)
- [ ] The role-based privilege tests in §9 pass
- [ ] The reconciliation queries in §8 run successfully against a populated Testcontainers fixture
- [ ] Cross-service tests confirm: Transaction Service can write `accounts.balance` but not `accounts.label`; Account Service cannot insert into `journal`
