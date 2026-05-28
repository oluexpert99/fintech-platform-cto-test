# FinTech Payment Platform — Architecture

**Document owner:** CTO candidate response
**Brief:** [`TECHNICAL TEST -CTO - English.docx`](TECHNICAL%20TEST%20-CTO%20-%20English.docx)
**Status:** Draft for review

---

## 0. Executive summary

We are designing a digital payment platform that must support **1 000 000 users**, **10 000 transactions / minute** at launch (with a path to 100 000 tx/min), and **99.99 % availability** (≈ 52 minutes downtime / year).

The platform is built as a set of **bounded-context microservices** on **Spring Boot + Spring Cloud**, persisting to **MongoDB replica sets** (per the brief), communicating asynchronously through **Apache Kafka**, and exposed through a single **API Gateway** secured by **Keycloak / OAuth2 / JWT**. Deployment is containerised (Docker) and orchestrated by **Kubernetes**, with **Prometheus / Grafana / Loki / OpenTelemetry** providing the observability stack.

Three design principles drive every decision in this document:

1. **Money is not eventually consistent.** Inside a single financial operation we use strong consistency (multi-document ACID transactions on a MongoDB replica set). Across service boundaries we use eventual consistency with the **transactional outbox** pattern to guarantee no dual-write divergence.
2. **Double-entry bookkeeping is non-negotiable.** Every value movement produces *two* journal lines — one debit, one credit — totalling zero. Errors are corrected by **compensating entries**, never by mutation of historical records.
3. **Idempotency everywhere.** Every state-changing API call accepts an `Idempotency-Key`. Every event consumer is idempotent on `eventId`. This is the single most important defence against the *double-debit* failure mode in Part 8.

This document maps **1:1** to the 8 parts of the brief and is supported by 5 ADRs in [`decisions/`](decisions/) and the REST contract reference in [`api.md`](api.md).

---

## Table of contents

1. [Part 1 — Overall architecture](#part-1--overall-architecture)
2. [Part 2 — Data architecture](#part-2--data-architecture)
3. [Part 3 — Fintech security](#part-3--fintech-security)
4. [Part 4 — Microservices design](#part-4--microservices-design)
5. [Part 5 — TransactionService implementation plan](#part-5--transactionservice-implementation-plan)
6. [Part 6 — Event-driven architecture](#part-6--event-driven-architecture)
7. [Part 7 — DevOps](#part-7--devops)
8. [Part 8 — Observability](#part-8--observability)
9. [Part 9 — Scalability](#part-9--scalability-10k--100k-tx-min)
10. [Part 10 — Incident management: double debit](#part-10--incident-management-double-debit)
11. [Cross-cutting concerns & deliverables](#cross-cutting-concerns--deliverables)

---

## Part 1 — Overall architecture

### 1.1 Architecture diagram (text form)

```
                          ┌──────────────────────────┐
                          │   Web / Mobile clients   │
                          └─────────────┬────────────┘
                                        │ HTTPS
                                        ▼
                          ┌──────────────────────────┐
                          │      API Gateway         │  Spring Cloud Gateway
                          │  (TLS, JWT, rate-limit,  │  • Routing
                          │   request validation)    │  • AuthN via Keycloak
                          └──┬───────────────────┬───┘  • Rate limiting
                             │                   │       • Circuit breaking
            ┌────────────────┼────────────┬──────┴────────┐
            ▼                ▼            ▼               ▼
     ┌────────────┐   ┌────────────┐ ┌────────────┐ ┌────────────┐
     │   Auth     │   │  Account   │ │ Transaction│ │ Accounting │
     │  Service   │   │  Service   │ │  Service   │ │  Service   │
     │ (Keycloak  │   │            │ │  (Part 5)  │ │  (read-    │
     │  adapter)  │   │            │ │            │ │   model)   │
     └─────┬──────┘   └─────┬──────┘ └─────┬──────┘ └─────┬──────┘
           │                │              │              │
           ▼                ▼              ▼              ▼
     ┌──────────────────────────────────────────────────────────┐
     │              MongoDB Replica Set (sharded)               │
     │  users  •  accounts  •  transactions  •  journal  •  outbox
     └──────────────────────────────────────────────────────────┘
                                  │
                                  │ outbox tail / CDC (Debezium)
                                  ▼
            ┌──────────────────────────────────────────┐
            │            Apache Kafka                  │
            │  topics: tx.completed, tx.failed, ...    │
            └──┬───────────────┬───────────────┬───────┘
               ▼               ▼               ▼
        ┌────────────┐  ┌────────────┐  ┌────────────┐
        │ Notification│  │   Fraud   │  │ Accounting │
        │  Service   │  │ Detection │  │ Projector  │
        └────────────┘  └────────────┘  └────────────┘

       ┌────────────────────────────────────────────────┐
       │       Cross-cutting platform services          │
       │  • Keycloak (IdP)                              │
       │  • Spring Cloud Config (centralised config)    │
       │  • Service registry (Consul / Eureka)          │
       │  • Prometheus + Grafana + Loki + OTel collector│
       │  • Vault (secrets)                             │
       └────────────────────────────────────────────────┘
```

The Mermaid source lives in [`diagrams/system-architecture.mmd`](diagrams/system-architecture.mmd) (renders natively on GitHub); a PNG export for the PDF deliverable is generated from the same file — see [`diagrams/README.md`](diagrams/README.md) for the one-line command.

### 1.2 Component roles

| Component | Role | Why it exists |
|---|---|---|
| **API Gateway** (Spring Cloud Gateway) | Single ingress; terminates TLS, validates JWTs, enforces rate limits, routes to downstream services, breaks circuits on failure. | Hides service topology from clients, centralises cross-cutting concerns (auth, throttling, observability), enables independent service evolution. |
| **Auth Service** | Thin Spring Boot facade over **Keycloak**: registration, login, profile, password reset, MFA enrolment. Issues OAuth2 access + refresh tokens. | Keeps domain code free of identity logic and lets us swap Keycloak for a managed IdP later without API breakage. |
| **Account Service** | Owns the `accounts` collection: open, freeze, close accounts; expose balance read APIs. | Account lifecycle is distinct from individual money movements; isolating it lets us enforce KYC / regulatory rules in one place. |
| **Transaction Service** (Part 5) | Owns transfer execution: validates, debits source, credits destination, writes two journal lines, publishes `TransactionCompletedEvent`. | The hot path of the platform — sized, monitored, scaled independently. |
| **Accounting Service** | Read-model / projector that builds reporting views from the journal (daily balances, trial balance, reconciliation reports). | Heavy reporting queries must never compete with the write path. |
| **Notification Service** | Kafka consumer of `tx.completed` → email / SMS / push. | Decouples user comms from the transaction critical path. |
| **Fraud Detection Service** | Kafka consumer of `tx.completed` → scoring → may emit `tx.flagged`. | Async, stateless; can be scaled or temporarily disabled without blocking transfers. |
| **API Gateway service-discovery + config** | Spring Cloud Config Server + Consul (or Eureka). | Twelve-factor config, zero-downtime config changes, dynamic routing. |
| **Kafka** | Event backbone for inter-service communication and audit. | Replayable log → easy event sourcing, recovery, and adding new consumers without touching producers. |
| **MongoDB replica set (sharded)** | Primary store for users, accounts, transactions, journal, outbox. | Directive in Part 3 (*"Implement... in: MongoDB"*) and Part 5 (the docker-compose deliverable lists *"MongoDB Replica Set"*); multi-document transactions on a replica set give us ACID inside a single service boundary. See [ADR-0001](decisions/0001-mongodb-as-ledger-store.md). |
| **Keycloak** | Identity Provider (OAuth2 / OIDC). | Mature, supports MFA, social login, fine-grained scopes; spares us from rolling our own. See [ADR-0003](decisions/0003-auth-stack.md). |
| **Observability stack** | Prometheus (metrics), Grafana (dashboards), Loki (logs), OpenTelemetry (traces). | RED + USE method, SLO-driven alerting. See [Part 8](#part-8--observability). |
| **Vault** | Secrets management (DB creds, signing keys). | Never bake secrets into images or env vars in plain text. |

### 1.3 Why microservices (and where we stopped)

We deliberately do **not** carve every noun into its own service. The decomposition follows **bounded contexts**, not entities:

- *Identity* (Auth Service + Keycloak) — distinct lifecycle, regulatory boundary.
- *Account lifecycle* (Account Service) — open / close / freeze; KYC.
- *Money movement* (Transaction Service) — the hot, latency-sensitive write path.
- *Reporting / projection* (Accounting Service) — read-heavy, can lag.
- *Side effects* (Notification, Fraud Detection) — async, optional for the critical path.

Splitting further (e.g., a separate "Balance Service") would create distributed-transaction problems where none need to exist.

---

## Part 2 — Data architecture

> *"Explain how you will manage account consistency, financial transactions, and the choice between ACID and eventual consistency."*

### 2.1 Consistency model: strong inside, eventual outside

| Boundary | Consistency | Mechanism |
|---|---|---|
| Inside a single transfer (debit + credit + 2 journal lines + outbox row) | **Strong / ACID** | MongoDB multi-document transaction on the replica set |
| Between Transaction Service and downstream consumers (Notification, Fraud, Accounting projector) | **Eventual** | Kafka event published via transactional outbox |
| Between services for read views (e.g. balance shown on dashboard) | **Eventual** (read-your-writes for the actor's own session) | Account Service serves authoritative balance; UI optimistically updates after `201 Created`. |

Pure eventual consistency on the money path would let a user spend the same balance twice; pure ACID across all services would force a distributed two-phase commit and destroy availability. We use ACID **where the invariant lives** (account balance + journal) and eventual elsewhere.

### 2.2 The Mongo-as-ledger question

The brief lists MongoDB as a *"Recommended Technology"* at the architecture level (Part 1) but is directive at the implementation level (Part 3: *"Implement... in: MongoDB"*) and in the compose deliverable (Part 5). A purist would push back and use PostgreSQL for the ledger; we chose consistency between recommendation and code, and document the full reasoning in [ADR-0001](decisions/0001-mongodb-as-ledger-store.md). Short version:

- **Mongo replica set + multi-document transactions** gives us ACID over the documents involved in a single transfer.
- We design the schema so that the entire invariant (source debit, destination credit, two journal lines, outbox row) lives in **one transaction** — so the database can enforce all-or-nothing.
- We accept the trade-offs: weaker referential integrity, no native `CHECK` constraints (enforced in application + JSON Schema validators), shard-key choice constrains query patterns.

### 2.3 Account-balance integrity

Three layers defend balance integrity:

1. **At write time** — the transfer is one Mongo transaction. The debit step is conditional: `updateOne({ _id: src, balance: { $gte: amount }, status: "ACTIVE" }, { $inc: { balance: -amount }, ... })`. If the conditional update matches 0 documents, the transaction aborts.
2. **At schema time** — JSON Schema validator on the `accounts` collection enforces `balance ≥ 0` and a versioned document shape. Optimistic locking via a `version` field guards against lost updates.
3. **At reconciliation time** — a daily job re-aggregates the journal and compares it to stored balances. Any drift triggers a P1 alert. The journal is the source of truth; account balances are a materialised view.

### 2.4 Audit, history, rollback

- **Audit trail** — the `journal` collection is **append-only**. Every value-changing operation writes two lines (Dr/Cr); rows are never updated or deleted. We disable `update` and `delete` privileges on the journal collection at the database role level.
- **History tracking** — each transfer produces a `transactions` document carrying the full request payload, the resulting journal line IDs, status, timestamps, idempotency key, and a correlation ID for tracing. The `accounts` collection stores only the current state; history is reconstructed from the journal.
- **Rollback** — there is no "undo" of a posted entry. To reverse a transaction we **post compensating entries** (an equal and opposite pair of journal lines linked by `correctsTransactionId`). This preserves the audit trail and is the only approach acceptable to auditors. The same mechanism handles the Part 10 incident.

### 2.5 Schema sketch

```jsonc
// accounts
{
  "_id": "ACC001",
  "ownerUserId": "U-...",
  "currency": "USD",
  "balance": 12345,            // stored as minor units (cents) — never floats
  "status": "ACTIVE",          // ACTIVE | FROZEN | CLOSED
  "version": 42,               // optimistic lock
  "createdAt": "...",
  "updatedAt": "..."
}

// transactions  (one per transfer request, even if it failed)
{
  "_id": "TX-...",
  "idempotencyKey": "uuid-from-client",   // UNIQUE INDEX
  "type": "TRANSFER",
  "status": "COMPLETED",                  // PENDING | COMPLETED | FAILED | REVERSED
  "sourceAccount": "ACC001",
  "destinationAccount": "ACC002",
  "amount": 100,
  "currency": "USD",
  "journalLineIds": ["JL-...", "JL-..."],
  "correctsTransactionId": null,          // set on a reversal
  "createdAt": "...", "completedAt": "..."
}

// journal  (append-only; double-entry)
{
  "_id": "JL-...",
  "transactionId": "TX-...",
  "account": "ACC001",
  "side": "DEBIT",                        // DEBIT | CREDIT
  "amount": 100,
  "currency": "USD",
  "postedAt": "..."
}

// outbox
{
  "_id": "OB-...",
  "aggregateId": "TX-...",
  "topic": "tx.completed",
  "payload": { ... },                     // serialised event
  "status": "PENDING",                    // PENDING | SENT
  "createdAt": "...", "sentAt": null
}
```

All money values are integers in the smallest currency unit. **Never floats.**

### 2.6 Indexes that matter

| Collection | Index | Purpose |
|---|---|---|
| `accounts` | `{ _id: 1 }` (default) | Point lookups during transfer |
| `accounts` | `{ ownerUserId: 1 }` | List a user's accounts |
| `transactions` | `{ idempotencyKey: 1 }` **unique** | Idempotency enforcement — see [ADR-0002](decisions/0002-idempotency-and-exactly-once.md) |
| `transactions` | `{ sourceAccount: 1, createdAt: -1 }` | History endpoint |
| `journal` | `{ transactionId: 1 }` | Tie journal lines to a transaction |
| `journal` | `{ account: 1, postedAt: -1 }` | Balance reconstruction & statements |
| `outbox` | `{ status: 1, createdAt: 1 }` | Publisher tail query |

---

## Part 3 — Fintech security

> *"Authentication, authorization, API protection, and protection against replay / brute force / injection attacks."*

### 3.1 Authentication

- **Identity provider:** Keycloak, configured for OAuth2 + OpenID Connect.
- **End-user flow:** Authorisation-Code + PKCE for web/mobile clients. Short-lived **access tokens (15 min)**, refresh tokens (24 h, rotating, single-use).
- **MFA:** TOTP enforced for accounts with > X balance or after first transfer; step-up auth on high-value transactions.
- **Password policy:** length ≥ 12, breached-password check (HIBP API), Argon2id hashing handled inside Keycloak.
- **Service-to-service:** mTLS between services in the mesh; OAuth2 *client_credentials* for cross-team integrations.

See [ADR-0003](decisions/0003-auth-stack.md) for the full rationale.

### 3.2 Authorization

- **Coarse-grained:** OAuth2 scopes (`account:read`, `transfer:write`, `admin:*`) checked at the gateway.
- **Fine-grained:** in-service ownership checks — *the caller's `sub` claim must match the account owner*, or possess an admin role. Enforced via Spring Security method security (`@PreAuthorize`).
- **Admin & operator actions:** RBAC roles in Keycloak; admin actions on accounts (freeze, reverse) require dual control (two operator approvals for value-impacting ops).

### 3.3 API protection

| Threat | Defence |
|---|---|
| **TLS downgrade / MITM** | TLS 1.3 only at the gateway; HSTS preload; mTLS internally. |
| **Replay attacks** | `Idempotency-Key` header (UUID, unique-indexed) + short JWT lifetime + `nonce` claim on sensitive operations + clock-skew check. |
| **Brute force (login, OTP)** | Keycloak built-in brute-force lockout, exponential back-off; gateway-level per-IP rate limit; CAPTCHA after N failed logins. |
| **Credential stuffing** | HIBP check; device fingerprinting; anomaly alerts on impossible-travel logins. |
| **Injection (SQLi, NoSQLi, Mongo operator injection)** | No string concatenation into queries — Mongo driver parameterisation only; JSON Schema validation on every input; whitelist any field name that ends up inside a query. |
| **XSS / CSRF** | Stateless JWT auth (no cookies) avoids CSRF on APIs; CSP + sanitisation on the web client. |
| **SSRF** | Outbound traffic from services restricted via egress NetworkPolicy; URL inputs validated and resolved against an allow-list. |
| **DoS / abuse** | Gateway rate-limit per-IP + per-user + per-endpoint (token bucket); CDN / WAF in front; circuit breakers on dependencies. |
| **Sensitive data at rest** | Mongo encryption-at-rest (KMS-managed keys); field-level encryption for PII (PAN, government IDs). |
| **Secrets** | HashiCorp Vault; rotated automatically; never in env vars in plaintext. |

### 3.4 Audit & forensics

- Every request is logged with `correlationId`, `userId`, `clientIp`, `userAgent`, decision (allow/deny), and a hash of the request body for non-repudiation.
- Logs ship to Loki with a 13-month retention to satisfy typical FinTech regulatory windows.
- Write access to audit logs is restricted; logs are signed (hash chain) so tampering is detectable.

### 3.5 Compliance posture

We design for **PCI-DSS scope minimisation** (no PAN storage if we can avoid it — use a tokenisation provider), **GDPR** (right to erasure → pseudonymise PII in the journal rather than delete, since the ledger must be preserved), and **PSD2 SCA** (strong customer authentication for payments above the regulatory threshold).

---

## Part 4 — Microservices design

See [`api.md`](api.md) for the full REST contract reference. Summary here:

| Service | Owns | Sample endpoints |
|---|---|---|
| Auth | Users, sessions, MFA | `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /users/me` |
| Account | Accounts | `POST /accounts`, `GET /accounts/{id}`, `POST /accounts/{id}/freeze` |
| Transaction | Transfers | `POST /transactions/transfer`, `GET /transactions/history`, `GET /transactions/{id}` |
| Accounting | Read-only ledger views | `GET /journal`, `GET /reports/trial-balance` |

### 4.1 Conventions across all services

- **Versioning:** URI-versioned (`/v1/...`). New major version only on breaking changes.
- **DTOs:** All request/response DTOs are explicit Java records, separate from persistence entities. Validation via Jakarta Bean Validation (`@NotBlank`, `@Positive`, `@Pattern`).
- **Idempotency:** Every state-changing endpoint accepts an `Idempotency-Key` header (UUID). Duplicate keys within the retention window return the original response. See [ADR-0002](decisions/0002-idempotency-and-exactly-once.md).
- **Errors:** [RFC 7807 Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) JSON, e.g.:
  ```json
  {
    "type": "https://api.example.com/problems/insufficient-funds",
    "title": "Insufficient funds",
    "status": 422,
    "detail": "Account ACC001 has balance 50, requested 100",
    "instance": "/v1/transactions/transfer",
    "correlationId": "..."
  }
  ```
- **HTTP codes:**

| Code | When |
|---|---|
| `200 OK` | Successful read or idempotent replay of a completed write |
| `201 Created` | New resource created |
| `202 Accepted` | Accepted for async processing (we don't use this on transfers — they're synchronous) |
| `204 No Content` | Successful delete / state change without body |
| `400 Bad Request` | Malformed payload, validation failure |
| `401 Unauthorized` | Missing / invalid / expired token |
| `403 Forbidden` | Authenticated but lacks permission |
| `404 Not Found` | Resource doesn't exist (or caller can't see it) |
| `409 Conflict` | Idempotency-key reuse with different payload; version conflict |
| `422 Unprocessable Entity` | Business rule violation (insufficient funds, frozen account) |
| `429 Too Many Requests` | Rate limit exceeded |
| `500 Internal Server Error` | Unhandled exception (alert!) |
| `503 Service Unavailable` | Dependency outage; circuit open |

### 4.2 Cross-cutting middleware

Every service inherits a baseline starter that wires: correlation-ID propagation, structured JSON logging, Micrometer metrics with standard tags, OpenTelemetry tracing, exception → Problem Details mapper, idempotency-key filter, Spring Security with JWT validator.

---

## Part 5 — TransactionService implementation plan

This is the only service we will build end-to-end.

### 5.1 Endpoint

`POST /v1/transactions/transfer`

**Headers:** `Authorization: Bearer <jwt>`, `Idempotency-Key: <uuid>`, `Content-Type: application/json`.

**Request DTO (`TransferRequest`):**
```java
public record TransferRequest(
    @NotBlank String sourceAccount,
    @NotBlank String destinationAccount,
    @Positive  long  amount,        // minor units; never float
    @NotBlank  String currency
) {}
```

**Response DTO (`TransferResponse`, 201 Created):**
```java
public record TransferResponse(
    String transactionId,
    String status,                // "COMPLETED"
    String sourceAccount,
    String destinationAccount,
    long   amount,
    String currency,
    Instant completedAt
) {}
```

### 5.2 Transactional logic

The entire flow runs inside **one MongoDB transaction** so all-or-nothing is guaranteed by the database:

```
BEGIN TX
  1. Look up source + destination by _id;
     verify both ACTIVE and same currency.
  2. Conditionally debit source:
        updateOne(
          { _id: src, balance: { $gte: amount }, status: "ACTIVE", version: v },
          { $inc: { balance: -amount }, $set: { updatedAt: now },
            $inc: { version: 1 } }
        )
     If matchedCount == 0 → throw InsufficientFundsException → ABORT.
  3. Credit destination (same shape, no balance check).
  4. Insert two journal lines (DEBIT on src, CREDIT on dst) linked by transactionId.
  5. Insert transactions document with status = COMPLETED and journalLineIds.
  6. Insert outbox row { topic: "tx.completed", payload: TransactionCompletedEvent }.
COMMIT TX
```

The Kafka publish happens **outside** the DB transaction, by a separate **outbox publisher** (Debezium CDC or a polling worker). This kills the dual-write bug. See [ADR-0002](decisions/0002-idempotency-and-exactly-once.md) and [Part 6](#part-6--event-driven-architecture).

### 5.3 Idempotency

- The `transactions` collection has a unique index on `idempotencyKey`.
- The endpoint flow:
  1. Look up an existing `transactions` doc by `idempotencyKey`. If found and the request payload matches → return the original response (200 OK if replay, 201 if first time). If found and payload differs → 409 Conflict.
  2. Otherwise proceed with the transactional logic above. The unique-index constraint protects against concurrent duplicate requests with the same key.

### 5.4 Error handling

| Failure | Class | HTTP | Notes |
|---|---|---|---|
| Validation failure | `MethodArgumentNotValidException` | 400 | Field-level details in Problem response |
| Source = destination | `InvalidTransferException` | 400 | Caught early, before DB |
| Account not found | `AccountNotFoundException` | 404 | |
| Account frozen / closed | `AccountUnavailableException` | 422 | |
| Currency mismatch | `CurrencyMismatchException` | 422 | |
| Insufficient funds | `InsufficientFundsException` | 422 | Detected by conditional `updateOne` |
| Idempotency-key reuse w/ different payload | `IdempotencyConflictException` | 409 | |
| Mongo `WriteConflict` (transient) | retried up to 3× w/ jitter | — | Standard Mongo TX retry pattern |
| Unhandled | `Exception` | 500 | Alerts; correlation ID returned to caller |

### 5.5 Code layout (intended)

```
transaction-service/
├── src/main/java/com/example/tx/
│   ├── TransactionServiceApplication.java
│   ├── api/
│   │   ├── TransferController.java
│   │   ├── dto/{TransferRequest, TransferResponse, ProblemDetails}.java
│   │   └── ProblemExceptionHandler.java
│   ├── domain/
│   │   ├── Account.java          (entity)
│   │   ├── JournalEntry.java
│   │   ├── Transaction.java
│   │   ├── OutboxRecord.java
│   │   └── exception/...
│   ├── service/
│   │   ├── TransferService.java   (orchestration)
│   │   └── IdempotencyService.java
│   ├── persistence/
│   │   ├── AccountRepository.java
│   │   ├── JournalRepository.java
│   │   ├── TransactionRepository.java
│   │   └── OutboxRepository.java
│   ├── messaging/
│   │   └── OutboxPublisher.java   (scheduled tail of outbox → Kafka)
│   └── config/{MongoConfig, KafkaConfig, SecurityConfig}.java
├── src/test/java/...
│   ├── unit (domain + service with mocks)
│   └── integration (Testcontainers: real Mongo RS + Kafka)
├── Dockerfile
└── pom.xml
```

### 5.6 Test plan

- **Unit:** service-layer tests with mocked repositories — happy path, all exception classes.
- **Integration (Testcontainers):**
  - Spin up Mongo replica set + Kafka.
  - Happy-path transfer end-to-end; assert account balances, journal lines, outbox row, Kafka message.
  - Concurrent duplicate-key requests → only one succeeds, the other returns the original response.
  - Insufficient funds → 422, no journal lines written.
  - Crash between commit and outbox publish → publisher catches up on next poll; event still delivered exactly once (idempotent consumer dedupes).
- **Property-based (jqwik):** for any random sequence of valid transfers, the journal sum equals the system-wide balance change (the *fundamental theorem of accounting*).

---

## Part 6 — Event-driven architecture

### 6.1 The event

```json
{
  "eventId": "uuid",
  "eventType": "TransactionCompletedEvent",
  "eventVersion": 1,
  "occurredAt": "2026-05-28T10:00:00Z",
  "correlationId": "...",
  "data": {
    "transactionId": "TX-...",
    "sourceAccount": "ACC001",
    "destinationAccount": "ACC002",
    "amount": 100,
    "currency": "USD"
  }
}
```

Schema is registered in a **Schema Registry** (Confluent or Apicurio). See [ADR-0004](decisions/0004-event-schema-and-evolution.md).

### 6.2 Producer — transactional outbox

We do **not** call `kafkaTemplate.send()` from inside the transfer code path. That would be a *dual write*: the DB commit and the Kafka send can each succeed independently, producing either lost events or phantom events.

Instead:

1. The transfer transaction inserts an `outbox` row in the same DB transaction.
2. A separate publisher reads the outbox and publishes to Kafka. Two acceptable implementations — we'll use option **A** for the test (no extra infrastructure):

   **A. Polling publisher** (`@Scheduled` job): every 200 ms, read `outbox` rows with `status = PENDING` ordered by `createdAt`, publish each to Kafka, mark `SENT`. If Kafka send fails, leave the row PENDING — it'll be retried.

   **B. CDC publisher** (production-grade): Debezium watches the Mongo oplog and streams outbox inserts to Kafka. Lower latency, zero polling load, but more moving parts.

The producer is **at-least-once**: a crash between Kafka ack and the `status = SENT` update means we'll republish the same event. Consumers must deduplicate on `eventId`. See [ADR-0002](decisions/0002-idempotency-and-exactly-once.md).

Kafka producer config:
- `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`, `retries=Integer.MAX_VALUE`.
- `compression.type=lz4`.
- Partition key = `transactionId` → preserves per-transaction ordering.

### 6.3 Consumer — Notification Service & Fraud Detection

Both subscribe to `tx.completed` in **different consumer groups** so they each get every event.

Per-consumer requirements:
- **Idempotent processing.** Each consumer keeps a `processed_events` collection with `eventId` as `_id`. Before handling, attempt to insert; on duplicate-key, skip. (Pattern: *inbox table*.)
- **Manual commit** of Kafka offsets only **after** processing + inbox-insert have succeeded.
- **Dead-letter topic** for poison messages: after N retries (`tx.completed.DLT`), the message is parked. An operator dashboard surfaces DLT volume.
- **Retry topic** for transient failures: failed message republished to `tx.completed.retry` with a delay header; a separate consumer reads it after the delay.

### 6.4 Failure handling — summary table

| Failure mode | What happens | How we recover |
|---|---|---|
| TX commits, publisher crashes before sending | Outbox row stays `PENDING` | Publisher resumes; row goes out on next poll |
| Kafka send returns success, publisher crashes before marking `SENT` | Row re-sent → duplicate in Kafka | Consumer's inbox dedupes by `eventId` |
| Consumer crashes mid-processing | Offset not committed | Kafka re-delivers; inbox dedupes |
| Poison message (deserialisation, validation fails) | After N retries → DLT | Operator inspects, fixes, replays |
| Schema-incompatible producer update | Registry rejects publish | Build fails in CI before reaching prod |
| Whole Kafka cluster down | TX still commits; outbox backs up | Publisher catches up when cluster returns; alert on outbox depth |

---

## Part 7 — DevOps

### 7.1 Local — Docker Compose

`docker-compose.yml` boots the minimum runnable platform:

| Service | Image | Notes |
|---|---|---|
| `gateway` | local Spring Cloud Gateway image | `:8080` |
| `auth-service` | local image | thin Keycloak adapter |
| `transaction-service` | local image | the real one (Part 5) |
| `keycloak` | `quay.io/keycloak/keycloak` | preloaded realm via JSON import |
| `mongo-rs-{1,2,3}` | `mongo:7` | 3-node replica set initialised by an init container |
| `kafka` | `bitnami/kafka` (KRaft mode, no ZooKeeper) | single broker for dev |
| `schema-registry` | `apicurio/apicurio-registry` | optional |
| `prometheus`, `grafana`, `loki`, `otel-collector` | upstream images | preloaded dashboards |

Every service exposes `/actuator/health`, `/actuator/prometheus`, `/actuator/info`.

### 7.2 Dockerfile pattern

Multi-stage, distroless runtime, non-root user, pinned JDK:

```dockerfile
# build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# runtime stage
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java","-XX:+UseZGC","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
```

### 7.3 Kubernetes deployment

- One **Helm chart per service** (`charts/transaction-service/`), values per environment (`values-dev.yaml`, `-staging`, `-prod`).
- **HPA** on CPU + custom metric `kafka_consumer_lag` / `http_requests_in_flight`.
- **PodDisruptionBudget** to keep ≥ N pods during voluntary disruptions (rolling deploys, node drains).
- **Affinity / anti-affinity** to spread replicas across AZs.
- **NetworkPolicies** restricting egress to known dependencies.
- **PodSecurity standards = restricted** (no root, read-only FS, no privilege escalation).
- **Secrets** mounted from Vault via the CSI driver; never in plain `Secret` objects.
- **Service mesh** (Istio or Linkerd) for mTLS + retries + circuit breaking.
- **MongoDB & Kafka in production** are managed services (Atlas, Confluent Cloud / MSK) for the test we run them in-cluster.

### 7.4 CI/CD

Pipeline (GitHub Actions):

1. **Build & test** — Maven build, unit + integration tests with Testcontainers.
2. **Static analysis** — SpotBugs, Error Prone, Checkstyle, OWASP Dependency-Check.
3. **Container build** — multi-stage, push to registry with both `:sha` and `:branch` tags.
4. **Image scan** — Trivy / Grype; fail on HIGH/CRITICAL CVEs.
5. **SBOM** — Syft → attached as a build artefact and signed (cosign).
6. **Deploy to staging** — Helm upgrade; smoke tests; synthetic transfer.
7. **Promotion to prod** — manual approval; **canary** to 5 % of traffic; auto-rollback on SLO breach (error rate > 1 % over 5 min, or p99 latency > target).
8. **Post-deploy** — DB migrations (Mongo `mongock` or schema validators) run as a one-shot Job before the new app pods.

We deploy **forward only** — bad versions are remediated by deploying a fixed forward version, not by data rollback (the ledger forbids it).

---

## Part 8 — Observability

### 8.1 What we measure

Following the **RED** (Rate, Errors, Duration) method for request-driven services and **USE** (Utilisation, Saturation, Errors) for resources:

| Layer | Key metrics |
|---|---|
| **API (RED)** | `http_server_requests_seconds` histogram (p50/p95/p99 by route, status); request rate; error rate (4xx and 5xx separately) |
| **Business** | `transactions_total{status}`, `transactions_amount_total{currency}`, `transactions_in_flight`, `transactions_duration_seconds`, `tps` (rate of `transactions_total`) |
| **Kafka producer** | record send rate, error rate, batch size, request latency, outbox depth (`outbox_pending_count`) |
| **Kafka consumer** | consumer lag per topic-partition (the single most important consumer metric), processing duration, retries to DLT |
| **MongoDB** | ops/sec, replication lag (seconds), write conflicts, transaction retries, connections used, page cache hit ratio |
| **JVM (USE)** | heap used, GC pause time, thread states, file descriptors |
| **Pods (USE)** | CPU throttling, memory working set, restart count |

### 8.2 Logs

- **Structured JSON**, one event per line.
- Mandatory fields: `timestamp`, `level`, `service`, `correlationId`, `userId`, `traceId`, `spanId`, `event`, `message`.
- Loki for storage; Grafana for search. PII is masked at log time, not at query time.

### 8.3 Traces

- OpenTelemetry SDK + auto-instrumentation for Spring, Mongo, Kafka.
- Sample 100 % of errors, 10 % of healthy traffic, 100 % of `/transfer` requests at first (drop later as volume grows).
- Spans propagate over Kafka via the `traceparent` message header → end-to-end traces across the outbox boundary.

### 8.4 SLOs and alerting

| SLO | Target | Alert (burn-rate) |
|---|---|---|
| `/transfer` availability (`2xx`/total) | 99.9 % monthly | Fast burn: 2 % budget in 1 h |
| `/transfer` latency | p99 < 500 ms | p99 > 800 ms for 10 min |
| Transaction success rate | > 99.95 % | < 99.8 % for 5 min |
| Outbox depth | < 1 000 | > 10 000 for 5 min → "events not flowing" |
| Kafka consumer lag | < 5 000 per partition | > 50 000 → "consumer falling behind" |
| Mongo replication lag | < 5 s | > 30 s |

Pages go to PagerDuty; warnings to Slack. **Alert on symptoms (SLO burn) and a small number of canaries (outbox depth, consumer lag, replication lag), not on every metric.**

### 8.5 Dashboards we ship

1. **Platform overview** — RED for every service.
2. **Transaction flow** — TPS, success/failure, p99 latency, outbox depth, consumer lag of each downstream.
3. **Database** — Mongo ops, replication lag, conflicts.
4. **JVM & pods** — heap, GC, CPU, memory per service.
5. **Business** — daily transfer volume, value, by currency.

---

## Part 9 — Scalability (10k → 100k tx/min)

10 000 / min ≈ **167 tx/s**, 100 000 / min ≈ **1 667 tx/s**. Not extreme by volume; the difficulty is the *guarantees* (ACID + audit + 99.99 %).

### 9.1 Scaling axes

| Tier | At 10 k/min | At 100 k/min | How we get there |
|---|---|---|---|
| Gateway | 2 replicas | 6–10 replicas | Stateless; HPA on CPU + RPS |
| Transaction Service | 3 replicas | 15–20 replicas | Stateless; HPA on `http_requests_in_flight` |
| MongoDB | Replica set (PSS) | **Sharded** by `hash(accountId)` across N shards | Sharding is the big unlock; choose key carefully so a "hot" merchant doesn't melt one shard |
| Kafka | 6 partitions per topic | 24–48 partitions per topic | Partition count is set conservatively from the start — repartitioning later is painful |
| Consumers | 1 per partition | scaled with partitions | Consumer count never exceeds partition count |

### 9.2 Bottlenecks to watch

1. **Hot account.** A merchant account receiving thousands of credits per second becomes a serialisation point. **Mitigations:** (a) shard balance into sub-accounts and aggregate, (b) write credits to a separate "pending credits" stream and batch-apply, (c) optimistic-locking with retry and exponential back-off.
2. **Shard-key choice.** `hash(accountId)` distributes evenly but kills range queries on `account`. Acceptable because our hot path is point-lookup. Avoid using `createdAt` — guaranteed monotonic-key hot shard.
3. **Journal growth.** Append-only, large. Use **time-based partitioning** (monthly collections / TTL on cold data archived to object storage) so working-set fits in cache.
4. **Outbox publisher** can become a single point of throughput. Make it horizontally scalable: shard rows by `aggregateId % N` and run N publisher instances each owning one slice; or move to Debezium (no app-side bottleneck).
5. **Kafka rebalance storms** during deploys → use static group membership + cooperative-sticky assignor.
6. **JVM cold starts** add tail latency during scale-up → AOT / CDS / GraalVM native if needed, plus over-provisioned warm pool.
7. **Gateway TLS termination** at high TPS → offload to L4/L7 LB (NLB + Envoy).
8. **Schema-registry, IdP, Vault** must scale too — they often become invisible bottlenecks.

### 9.3 Capacity guardrails

- **Backpressure** — return 429 from the gateway before any downstream is saturated.
- **Circuit breakers** (Resilience4j) — open on dependency failure to fail fast instead of queuing.
- **Bulkheads** — separate thread pools per dependency so a slow consumer doesn't starve the rest.
- **Load tests in CI** — k6 or Gatling scenario hits `/transfer` at 2× peak before each release; fail the build on regression.

---

## Part 10 — Incident management: double debit

> *"A bug is causing double debit on some accounts. How do you detect it, fix it, and prevent it from happening again?"*

### 10.1 Detection

1. **Customer signal:** support tickets spike → first signal in practice.
2. **Automated detection (we should not rely on customers):**
   - **Reconciliation job** (hourly) — for every account, sum the journal (`SUM(credits) - SUM(debits)`) and compare to stored balance. Any drift > 0 raises a P1.
   - **Duplicate-detection job** — group `journal` by `(account, side, amount, transactionId)`; if two journal lines share the same `transactionId` + `side`, that's a duplicate post. Raises a P1.
   - **Per-user anomaly alert** — a single user generating > N debits in a window is flagged.
   - **Idempotency-key counter** — if the same `Idempotency-Key` was seen but produced two distinct transactions, alert immediately (a real bug since the unique index should prevent this).

### 10.2 Triage (first 30 minutes)

1. **Stop the bleeding** — feature-flag transfers off (or move them to a degraded "manual review" queue). 99.99 % SLO allows ~5 min/month; we accept the dent to prevent more harm.
2. **Quantify the blast radius** — query the journal for all duplicate posts in the suspect window; build the list of affected `(account, transactionId)` pairs.
3. **Communicate** — status page update; pre-drafted "we are investigating" comms.

### 10.3 Correction

- **Never** delete or update existing journal lines. The journal is the audit trail; auditors and regulators require append-only.
- For every duplicated debit, post a **compensating entry**: a `CREDIT` of equal amount, with `correctsTransactionId` pointing back to the duplicate, and a clear narrative (`"reversal of duplicated debit caused by INC-2026-05-28-001"`).
- Update the `transactions` document to `status = REVERSED`.
- Recompute and write the corrected `accounts.balance` (still atomically with the compensating journal lines, inside one Mongo transaction).
- Notify affected users (email + in-app).

### 10.4 Root cause & prevention

The double-debit class of bug has a small number of root causes; the prevention strategy covers all of them:

| Root cause | Prevention |
|---|---|
| Client-side retry without idempotency key | Make `Idempotency-Key` **required** on `/transfer`; reject requests without it (400). |
| Idempotency table check + insert is racy | Rely on the **unique index** on `transactions.idempotencyKey`, not on a read-then-write pattern. The DB is the arbiter. |
| Transfer logic not wrapped in a single transaction | Code review + integration test: kill the process between debit and credit and assert nothing was persisted. |
| Outbox row re-sent + a non-idempotent consumer treats the second event as a new transfer | Consumers must be idempotent on `eventId` (inbox pattern). Critically, **consumers must never re-issue financial writes** based on the event — `TransactionCompletedEvent` is informational only. |
| Operator manually re-runs a failed job | Operator tools require dual control and use the same idempotency-key path. |
| Code path that bypasses `TransferService` (e.g., admin tool) | Single chokepoint: one `TransferService.transfer(...)` method; admin reversals go through `TransferService.reverse(...)` which posts compensating entries — never raw inserts. |

Permanent guardrails added after such an incident:

- **Reconciliation job runs continuously** (every 5 min, not just hourly) until we have 30 days of clean runs.
- **Chaos test** in CI that fires concurrent identical `/transfer` requests with the same idempotency key and asserts that exactly one transaction is recorded.
- **Post-mortem** is blameless and produces concrete remediations with owners and dates.

---

## Cross-cutting concerns & deliverables

### What we'll deliver

- This document (`ARCHITECTURE.md`) plus its companion files in `docs/`.
- 5 ADRs in `docs/decisions/`.
- `docs/api.md` — REST reference.
- `docs/diagrams/` — Mermaid sources (`system-architecture.mmd`, `transfer-sequence.mmd`, `event-flow.mmd`) plus PNG exports for the PDF.
- `transaction-service/` — full implementation.
- `docker-compose.yml` — runnable local platform.
- `k8s/` — Helm chart sketch.
- `.github/workflows/ci.yml` — CI pipeline.
- A short README pointing readers to the right entry point.

### Resolved scope decisions

The four scope calls below were settled before implementation. They define exactly what `docker-compose up` will produce.

| # | Decision | Implication |
|---|---|---|
| 1 | **Full Keycloak in docker-compose.** Pre-imported realm with users, clients, scopes, and signing keys committed to Git. Services validate JWTs against Keycloak's JWKS endpoint. | Production parity. Adds ~30s to compose startup and a Keycloak service to compose. Realm export lives at `infra/keycloak/realm-export.json`. |
| 2 | **Outbox publisher = in-process `@Scheduled` polling worker** inside Transaction Service. Tail interval ~200 ms, batch size configurable. | Zero extra infrastructure. Debezium remains the documented production path (see [ADR-0002](decisions/0002-idempotency-and-exactly-once.md)) but is not built for this submission. |
| 3 | **Currency: field present and validated, single-currency per transfer.** Source, destination, and request `currency` must all match. No FX. | API contract in [`api.md`](api.md) is forward-compatible. Cross-currency requests return `422 /problems/currency-mismatch`. |
| 4 | **Real services in compose: API Gateway + Auth Service + Account Service + Transaction Service.** Auth and Account are minimal but functional Spring Boot apps, not canned stubs. | A reviewer can run register → login → open accounts → transfer end-to-end against the real platform. Notification, Fraud Detection, and Accounting are deferred (documented in this doc, not built). |

With these settled, the ADRs and implementation can proceed.
