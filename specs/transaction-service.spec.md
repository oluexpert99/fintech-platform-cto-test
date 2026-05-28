# Spec — Transaction Service

**Module:** `services/transaction-service/`
**Package root:** `com.example.fintech.transactions`
**Status:** Draft for review (template for the other 7 specs)
**Related:** [`../docs/ARCHITECTURE.md` §5](../docs/ARCHITECTURE.md#part-5--transactionservice-implementation-plan) · [`../docs/api.md` §10](../docs/api.md#10-transactions) · [ADR-0001](../docs/decisions/0001-mongodb-as-ledger-store.md) · [ADR-0002](../docs/decisions/0002-idempotency-and-exactly-once.md) · [ADR-0004](../docs/decisions/0004-event-schema-and-evolution.md) · [transfer-sequence diagram](../docs/diagrams/transfer-sequence.mmd)

---

## 1. Purpose

Transaction Service owns the movement of money on the platform. It is the only service permitted to write to the `accounts.balance` field, the only writer of `journal` entries, and the source of all `transactions.transfer.completed` events. Every other read or projection of value flows is downstream of this service.

This is the **focal service** of the submission. It exists to demonstrate that the platform handles the hard parts of FinTech correctly: ACID under load, idempotency under retry, exactly-once event publishing under failure, double-entry bookkeeping, and reversal-without-mutation.

## 2. Scope

### In scope

- `POST /v1/transactions` (`TRANSFER` and `REVERSAL` discriminated by `type`)
- `GET /v1/transactions/{id}`
- `GET /v1/transactions` (caller's history, filtered + paginated)
- Internal transactional logic: balance check, double-entry write, outbox row, all inside one Mongo transaction
- The **outbox polling publisher** — the in-process `@Scheduled` worker that drains `outbox` to Kafka
- Idempotency enforcement via unique-index on `transactions.idempotencyKey`
- Reconciliation hook (count + sum endpoint used by the daily ledger-balance job; the job itself runs elsewhere)
- Metrics, structured logging, health checks per the conventions in `data-model.spec` and `ARCHITECTURE.md` §8

### Out of scope

- Account creation, freeze, unfreeze (owned by **Account Service**)
- User registration, login, MFA (owned by **Auth Service**)
- Notification, fraud-detection, accounting projections (downstream consumers of `transactions.transfer.completed` — *documented only* per the resolved scope decisions)
- FX / cross-currency conversion — explicitly rejected per the scope decision; cross-currency requests return `422 CURRENCY_MISMATCH`
- The reconciliation job itself (a separate scheduled component, not part of this service)

---

## 3. Contract

### 3.1 HTTP surface

Authoritative documentation lives in [`../docs/api.md` §10](../docs/api.md#10-transactions). Summary:

| Method | Path | Auth | Idempotency-Key | Returns |
|---|---|---|---|---|
| `POST` | `/v1/transactions` | required (`transactions:write` or `transactions:reverse`) | required | `201 Created` with `TransactionResponse` |
| `GET`  | `/v1/transactions/{id}` | required | n/a | `200 OK` with `TransactionResponse` |
| `GET`  | `/v1/transactions` | required | n/a | `200 OK` with paginated `{ data, page }` |

Error model: RFC 7807 + our `code`/`params` extension. See `api.md` §3 for the catalogue. The mandatory mapping (exception → HTTP code → `code`) lives in `ProblemExceptionHandler` and is asserted in tests — never as silent string matching.

### 3.2 Internal package structure

Following the locked layout in `[[project-codebase-structure]]`:

```
com.example.fintech.transactions/
├── TransactionsServiceApplication.java          ← @SpringBootApplication
│
├── api/
│   ├── TransactionsController.java
│   ├── dto/
│   │   ├── CreateTransactionRequest.java        ← record, validated
│   │   ├── TransferDetails.java                 ← record nested in request
│   │   ├── ReversalDetails.java                 ← record nested in request
│   │   ├── TransactionResponse.java             ← record
│   │   └── ProblemResponse.java                 ← RFC 7807 + code+params
│   └── ProblemExceptionHandler.java             ← @RestControllerAdvice
│
├── domain/
│   ├── model/
│   │   ├── AccountId.java                       ← value object wrapping String
│   │   ├── TransactionId.java
│   │   ├── JournalEntryId.java
│   │   ├── Money.java                           ← amount (long, minor units) + currency (Currency)
│   │   ├── TransactionType.java                 ← enum {TRANSFER, REVERSAL, FEE, REFUND}
│   │   ├── TransactionStatus.java               ← enum {PENDING, COMPLETED, FAILED, REVERSED}
│   │   ├── Side.java                            ← enum {DEBIT, CREDIT}
│   │   └── Transaction.java                     ← domain aggregate
│   ├── exception/
│   │   ├── DomainException.java                 ← sealed base
│   │   ├── AccountNotFoundException.java
│   │   ├── AccountUnavailableException.java
│   │   ├── CurrencyMismatchException.java
│   │   ├── InsufficientFundsException.java
│   │   ├── SelfTransferException.java
│   │   ├── LimitExceededException.java
│   │   ├── IdempotencyConflictException.java
│   │   ├── OriginalTransactionNotReversibleException.java
│   │   └── OperatorApprovalRequiredException.java
│   └── policy/
│       ├── TransferLimitsPolicy.java            ← per-day / per-tx limits
│       └── CurrencyPolicy.java                  ← same-currency enforcement
│
├── application/
│   ├── TransferService.java                     ← TRANSFER orchestration
│   ├── ReversalService.java                     ← REVERSAL orchestration
│   ├── IdempotencyService.java                  ← key lookup + replay
│   └── TransactionFinder.java                   ← GET endpoints
│
├── persistence/
│   ├── document/
│   │   ├── AccountDocument.java                 ← @Document("accounts")
│   │   ├── TransactionDocument.java             ← @Document("transactions")
│   │   ├── JournalEntryDocument.java            ← @Document("journal")
│   │   └── OutboxRecordDocument.java            ← @Document("outbox")
│   ├── repository/
│   │   ├── AccountRepository.java               ← extends MongoRepository + custom
│   │   ├── AccountRepositoryCustom.java         ← conditionalDebit, conditionalCredit
│   │   ├── TransactionRepository.java
│   │   ├── JournalEntryRepository.java
│   │   └── OutboxRepository.java                ← findPendingForLease, markSent
│   └── mapper/
│       ├── TransactionMapper.java               ← document ↔ domain
│       └── (etc.)
│
├── messaging/
│   ├── envelope/
│   │   └── EventEnvelopeBuilder.java
│   ├── event/
│   │   └── TransactionCompletedEvent.java       ← serialisable record matching events/schemas/...
│   └── OutboxPublisher.java                     ← @Scheduled
│
└── config/
    ├── SecurityConfig.java                      ← OAuth2 resource server, method security
    ├── MongoConfig.java                         ← MongoTransactionManager
    ├── KafkaConfig.java                         ← producer factory (acks=all, idempotent)
    ├── ObservabilityConfig.java                 ← MeterRegistry, OTel
    └── OpenApiConfig.java                       ← springdoc → /v3/api-docs
```

### 3.3 Public method signatures (the contract)

```java
// application/TransferService.java
@Service
public class TransferService {
    /**
     * Execute a transfer. All-or-nothing inside one Mongo multi-document transaction.
     * Idempotency: callers MUST supply an idempotencyKey unique per logical intent;
     * duplicate keys with matching payload return the original result.
     */
    public TransactionResponse transfer(
        UserId callerSub,           // from JWT
        String idempotencyKey,      // from header
        TransferDetails req,        // validated DTO
        StepUpAuthContext stepUp    // from gateway-resolved JWT acr claim
    );
}

// application/ReversalService.java
@Service
public class ReversalService {
    /**
     * Post compensating entries for a previously-COMPLETED transaction.
     * Requires operator role + dual-control approver.
     */
    public TransactionResponse reverse(
        UserId callerSub,
        String idempotencyKey,
        ReversalDetails req
    );
}

// persistence/repository/AccountRepositoryCustom.java
public interface AccountRepositoryCustom {
    /**
     * Atomic debit guarded by a balance precondition.
     * Returns true if matched-and-modified, false if the precondition didn't hold
     * (balance < amount OR status != ACTIVE OR version mismatch).
     * Must be called inside an active Mongo transaction.
     */
    boolean conditionalDebit(AccountId src, long amount, long expectedVersion);

    /**
     * Unconditional credit (no balance check; destination just receives).
     * Still checks status == ACTIVE; returns false if account is FROZEN/CLOSED.
     */
    boolean conditionalCredit(AccountId dst, long amount, long expectedVersion);
}
```

---

## 4. Behaviour

### 4.1 Transfer flow (happy path)

The full sequence is shown in [transfer-sequence.png](../docs/diagrams/transfer-sequence.png). The algorithm here is the implementation contract:

```
transfer(caller, key, req, stepUp):

  // 1. Pre-transactional checks (cheap fail-fast)
  validate req fields (handled by @Valid in controller — caught before this method)
  if req.source == req.destination:
    throw SelfTransferException                        → 422 SELF_TRANSFER
  if stepUp.required and not stepUp.fresh:
    throw StepUpRequiredException                      → 401 STEP_UP_REQUIRED

  // 2. Idempotency lookup (outside the transaction)
  existing = idempotencyService.find(caller, "transfer", key)
  if existing != null:
    if existing.payloadMatches(req):
      return existing.response                          → 200 OK (replay)
    else:
      throw IdempotencyConflictException                → 409 IDEMPOTENCY_KEY_CONFLICT

  // 3. Single Mongo transaction
  withMongoTransaction(retryOnTransientWriteConflict = 3):

    src = accountRepository.findById(req.source)
    if src == null OR src.ownerUserId != caller:
      throw AccountNotFoundException                    → 404 RESOURCE_NOT_FOUND
    dst = accountRepository.findById(req.destination)
    if dst == null:
      throw AccountNotFoundException                    → 404 RESOURCE_NOT_FOUND

    if src.currency != req.currency OR dst.currency != req.currency:
      throw CurrencyMismatchException                   → 422 CURRENCY_MISMATCH

    if not transferLimitsPolicy.allow(caller, req.amount):
      throw LimitExceededException                      → 422 LIMIT_EXCEEDED

    // 3a. Atomic debit (this is where insufficient-funds is detected)
    ok = accountRepository.conditionalDebit(src.id, req.amount, src.version)
    if not ok:
      // re-read to distinguish the cause
      reload = accountRepository.findById(src.id)
      if reload.status != ACTIVE:
        throw AccountUnavailableException               → 422 ACCOUNT_UNAVAILABLE
      if reload.balance < req.amount:
        throw InsufficientFundsException                → 422 INSUFFICIENT_FUNDS
      // else: version conflict — handled by retryOnTransientWriteConflict above

    // 3b. Credit
    ok = accountRepository.conditionalCredit(dst.id, req.amount, dst.version)
    if not ok:
      reload = accountRepository.findById(dst.id)
      if reload.status != ACTIVE:
        throw AccountUnavailableException               → 422 ACCOUNT_UNAVAILABLE

    // 3c. Journal — two lines, append-only
    txId = TransactionId.generate()                     // ULID
    journalRepository.insertAll([
      new JournalEntry(JL-..., txId, src.id, DEBIT,  req.amount, req.currency, now),
      new JournalEntry(JL-..., txId, dst.id, CREDIT, req.amount, req.currency, now)
    ])

    // 3d. Transaction document (the canonical record)
    txDoc = new TransactionDocument(
      txId,
      idempotencyKey = scopedKey(caller, "transfer", key),  // unique index
      type           = TRANSFER,
      status         = COMPLETED,
      source         = req.source,
      destination    = req.destination,
      amount         = req.amount,
      currency       = req.currency,
      description    = req.description,
      journalLineIds = [JL-..., JL-...],
      correctsTransactionId = null,
      createdAt      = now,
      completedAt    = now,
      callerSub      = caller,
      payloadHash    = sha256(canonicalJson(req))            // for replay match
    )
    transactionRepository.insert(txDoc)                 // unique-index enforces idempotency

    // 3e. Outbox row — same transaction, NOT a separate publish
    event = TransactionCompletedEvent(...txDoc fields...)
    outboxRepository.insert(new OutboxRecord(
      id          = OB-...,
      aggregateId = txId,
      topic       = "transactions.transfer.completed",
      eventId     = ulid(),
      payload     = envelope(event),
      status      = PENDING,
      createdAt   = now,
      attempts    = 0
    ))

  // Transaction commits here. Kafka publish does NOT happen on this thread.
  return TransactionResponse(txDoc)                     → 201 Created
```

**Key implementation rules:**

- **No `kafkaTemplate.send()` anywhere inside `transfer()`**. The outbox row is the publish intent. The polling worker (§4.4) handles the wire.
- **No floats.** `amount` is `long` in minor units throughout. The DTO record uses `long`. The `Money` value object uses `long`. There is no `BigDecimal` in this codepath — overkill at our scale and a footgun for arithmetic.
- **Transaction retries.** The Spring `MongoTransactionManager` wrapper retries up to 3× on `MongoTransientWriteException` / write conflicts with exponential jitter (50ms, 150ms, 400ms). Permanent failures bubble.
- **Re-read on `conditionalDebit` failure.** A failed conditional update is ambiguous between "balance too low" and "status changed" and "version stale" — we re-read to map to the correct `code`. This is one extra read on the failure path; acceptable.

### 4.2 Reversal flow

```
reverse(caller, key, req):

  authz: caller must have role "operator"
  authz: req.approverId must differ from caller AND have role "operator"
  if either fails:
    throw OperatorApprovalRequiredException             → 403 OPERATOR_APPROVAL_REQUIRED

  // Idempotency lookup as in §4.1

  withMongoTransaction:
    orig = transactionRepository.findById(req.correctsTransactionId)
    if orig == null OR orig.type == REVERSAL OR orig.status != COMPLETED:
      throw OriginalTransactionNotReversibleException   → 422 ORIGINAL_TRANSACTION_NOT_REVERSIBLE
    if orig.status == REVERSED:
      throw OriginalTransactionNotReversibleException   → 422 (already reversed)

    // Post compensating entries — SWAPPED direction
    revTxId = TransactionId.generate()
    journalRepository.insertAll([
      new JournalEntry(JL-..., revTxId, orig.destination, DEBIT,  orig.amount, orig.currency, now),
      new JournalEntry(JL-..., revTxId, orig.source,      CREDIT, orig.amount, orig.currency, now)
    ])

    // Update balances atomically using the same conditional methods
    accountRepository.conditionalDebit (orig.destination, orig.amount, dst.version)
    accountRepository.conditionalCredit(orig.source,      orig.amount, src.version)

    // Mark the original as REVERSED (audit-preserving; no field of journal lines is touched)
    transactionRepository.markReversed(orig.id, revTxId)

    revDoc = TransactionDocument(
      type                   = REVERSAL,
      status                 = COMPLETED,
      correctsTransactionId  = orig.id,
      reason                 = req.reason,
      approverId             = req.approverId,
      ...
    )
    transactionRepository.insert(revDoc)
    outboxRepository.insert(reversalCompletedEvent)

  return TransactionResponse(revDoc)                    → 201
```

The journal **never** sees an UPDATE or DELETE during reversal — only INSERTs. This is the audit-preserving property required for ledgers (and enforced at the Mongo role level; see `data-model.spec`).

### 4.3 Idempotency check sequence

Per [ADR-0002](../docs/decisions/0002-idempotency-and-exactly-once.md):

```
idempotencyService.find(caller, op, clientKey):

  scopedKey = sha256(caller + "|" + op + "|" + clientKey)
  existing = transactionRepository.findByIdempotencyKey(scopedKey)
  if existing == null:
    return null

  if existing.status == PENDING:
    throw IdempotencyInProgressException              → 409 IDEMPOTENCY_IN_PROGRESS (with Retry-After)

  return existing
```

The follow-up payload-match check is in the calling service (`TransferService.transfer`), not in `IdempotencyService` — the latter doesn't know what a "matching payload" means for each operation.

**The race-free guarantee** comes from the unique index, not the lookup. Two concurrent requests with the same key both reach §4.1 step 3d; one wins the insert, the other gets `DuplicateKeyException`, the catch block maps that to a replay.

### 4.4 Outbox publisher algorithm

```java
@Scheduled(fixedDelayString = "${outbox.publisher.tick-millis:200}")
public void drain() {
  if (!leader()) return;                  // see §4.4.1 below

  var batch = outboxRepository.findPendingForLease(BATCH_SIZE);   // claim by lease token
  for (var record : batch) {
    try {
      var meta = kafkaTemplate.send(record.topic(), record.aggregateId(), record.payload())
                              .get(SEND_TIMEOUT);
      outboxRepository.markSent(record.id(), meta.offset(), meta.partition(), now());
      sentCounter.increment();
    } catch (Exception e) {
      outboxRepository.releaseLease(record.id());
      record.incrementAttempts();
      if (record.attempts() > MAX_ATTEMPTS) {
        outboxRepository.markPoisoned(record.id(), e.getMessage());
        poisonedCounter.increment();
        alertOps(record);
      }
      log.warn("outbox send failed; will retry", e);
    }
  }
}
```

#### 4.4.1 Leadership / preventing concurrent publishers

A naïve `@Scheduled` runs in *every* replica → multiple replicas publish the same outbox row in parallel → duplicate Kafka messages (consumer dedupe handles it, but it inflates Kafka load and metrics).

We use a **lease-based claim** rather than leader election to keep things simple:

- `OutboxRepository.findPendingForLease(batchSize)`:
  ```
  // Mongo-atomic
  findAndModify({
    query:  { status: PENDING, leaseUntil: { $lt: now } },
    update: { $set: { leaseUntil: now + LEASE_DURATION, leasedBy: instanceId } },
    sort:   { createdAt: 1 },
    limit:  batchSize
  })
  ```
- `LEASE_DURATION` = 5 seconds. If a replica crashes mid-publish, the lease expires and another replica picks the row up.
- This is at-least-once: if a replica publishes to Kafka then crashes before `markSent`, the next replica re-publishes. Consumer dedupe is mandatory (see ADR-0002).

#### 4.4.2 Sizing

| Knob | Default | Where set |
|---|---|---|
| `outbox.publisher.tick-millis` | `200` | `application.yaml` |
| `outbox.publisher.batch-size` | `100` | `application.yaml` |
| `outbox.publisher.send-timeout-millis` | `5000` | `application.yaml` |
| `outbox.publisher.lease-duration-seconds` | `5` | `application.yaml` |
| `outbox.publisher.max-attempts` | `10` | `application.yaml` |

At 100 k tx/min (1 667 tx/s), the steady-state load is 1 667 outbox rows/sec. At a 200 ms tick + 100-row batch, throughput is 500 rows/sec/replica — so production runs ≥ 4 replicas. The defaults above are tuned for dev compose, not production.

### 4.5 Configuration surface (`application.yaml`)

```yaml
spring:
  application.name: transaction-service
  data.mongodb:
    uri: ${MONGO_URI}                  # e.g. mongodb://mongo1:27017,mongo2:27017,mongo3:27017/fintech?replicaSet=rs0
    auto-index-creation: false         # indexes are managed by data-model spec / migrations
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP}
    producer:
      acks: all
      enable-idempotence: true
      properties:
        max.in.flight.requests.per.connection: 5
        compression.type: lz4
  security.oauth2.resourceserver.jwt:
    issuer-uri: ${KEYCLOAK_ISSUER_URI} # e.g. http://keycloak:8080/realms/fintech
    jwk-set-uri: ${KEYCLOAK_ISSUER_URI}/protocol/openid-connect/certs

server.port: 8080
management:
  server.port: 8081                    # actuator on separate port (not gateway-exposed)
  endpoint.health.probes.enabled: true
  endpoints.web.exposure.include: health,prometheus,info

outbox:
  publisher:
    tick-millis: 200
    batch-size: 100
    send-timeout-millis: 5000
    lease-duration-seconds: 5
    max-attempts: 10

transactions:
  limits:
    per-tx-max-amount: 100000000       # 1,000,000.00 in minor units
    per-day-max-amount: 1000000000
    step-up-threshold-amount: 50000000 # above this, require fresh MFA
  idempotency:
    retention-hours: 24
```

All defaults are overridable via env vars (`OUTBOX_PUBLISHER_TICK_MILLIS=…`). Sensitive values (`MONGO_URI` if it includes credentials, `KEYCLOAK_ISSUER_URI` per env) come from Vault via the Spring Cloud Config + Vault backend — never plaintext.

### 4.6 Error → HTTP mapping

`ProblemExceptionHandler` exhaustively maps every domain exception. The table in `api.md` §10 is the contract; the handler is asserted by a test that iterates the `DomainException` sealed hierarchy and confirms every subtype has a mapping.

| Exception | HTTP | `code` |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST` |
| `MissingIdempotencyKeyException` | 400 | `MISSING_IDEMPOTENCY_KEY` |
| `AuthenticationException` | 401 | `UNAUTHORIZED` |
| `StepUpRequiredException` | 401 | `STEP_UP_REQUIRED` |
| `AccessDeniedException` | 403 | `FORBIDDEN` |
| `OperatorApprovalRequiredException` | 403 | `OPERATOR_APPROVAL_REQUIRED` |
| `AccountNotFoundException` | 404 | `RESOURCE_NOT_FOUND` |
| `IdempotencyConflictException` | 409 | `IDEMPOTENCY_KEY_CONFLICT` |
| `IdempotencyInProgressException` | 409 | `IDEMPOTENCY_IN_PROGRESS` |
| `OptimisticLockingFailureException` | 409 | `VERSION_CONFLICT` |
| `SelfTransferException` | 422 | `SELF_TRANSFER` |
| `AccountUnavailableException` | 422 | `ACCOUNT_UNAVAILABLE` |
| `CurrencyMismatchException` | 422 | `CURRENCY_MISMATCH` |
| `InsufficientFundsException` | 422 | `INSUFFICIENT_FUNDS` |
| `LimitExceededException` | 422 | `LIMIT_EXCEEDED` |
| `OriginalTransactionNotReversibleException` | 422 | `ORIGINAL_TRANSACTION_NOT_REVERSIBLE` |
| `RateLimitExceededException` | 429 | `RATE_LIMITED` |
| `Exception` (uncaught) | 500 | `INTERNAL` (logged at ERROR with full stack + correlationId) |
| `MongoSocketException` / circuit-open | 503 | `DEPENDENCY_UNAVAILABLE` |

`params` for each error is built from the exception's typed fields — `InsufficientFundsException(accountId, available, requested, currency)` exposes those four values. The exception classes carry typed fields, never just a String message.

---

## 5. Tests

### 5.0 Testing principles (apply across all 8 specs)

- **Testcontainers is the canonical mechanism for any test that touches infrastructure.** Real MongoDB replica set, real Kafka (KRaft), real Keycloak — no in-memory substitutes, no embedded brokers, no driver-level fakes.
- Specifically banned: **Flapdoodle embedded Mongo**, **EmbeddedKafkaBroker** (`spring-kafka-test`), **H2 / HSQLDB**, in-memory caches mimicking external services. Tests must exercise the actual driver and wire protocol; behaviour that only shows up against the real binary (transaction semantics, write conflicts, Kafka idempotent producer, Keycloak JWT format) must be exercised under it.
- **Container reuse** is enabled (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`) for fast local feedback; CI uses fresh containers per run.
- **Init scripts are committed to the repo and shared with docker-compose.** The Mongo replica-set init JS and the Keycloak realm export used by tests are **the same files** used by `docker-compose.yml` — there is no test-only fork. (Path: `infra/mongo/init-replica-set.js`, `infra/keycloak/realm-export.json`.)
- **Shared container lifecycle:** containers start once per Surefire fork (static `@Container` in an abstract `IntegrationTestBase`). Tests are independent via per-test fixtures (collection drops or schema reset between tests), not via per-test containers.
- **Network determinism:** tests pin container versions (`mongo:7.0.14`, `confluentinc/cp-kafka:7.7.1`, `quay.io/keycloak/keycloak:25.0`) — no `:latest` tags. Image digests pinned in CI.

The test pyramid below applies these principles consistently.

### 5.1 Unit (`src/test/java/.../unit`)

Pure JUnit 5 + Mockito. No Spring context, no I/O.

| Class under test | What's asserted |
|---|---|
| `Money`, `AccountId`, `TransactionId` | value-object equality, validation, no float arithmetic |
| `TransferLimitsPolicy` | per-tx and per-day boundaries; off-by-one cases |
| `CurrencyPolicy` | same-currency rule; FX rejection |
| `TransferService` (mocked repos) | every branch of §4.1: happy path, self-transfer, currency mismatch, insufficient funds, frozen source/destination, idempotent replay, idempotency conflict |
| `ReversalService` (mocked repos) | role checks, dual-control, original-not-reversible variants |
| `IdempotencyService` (mocked repo) | found / not-found / in-progress branches |
| `EventEnvelopeBuilder` | every required envelope field set; traceparent propagation; eventId is a ULID |

### 5.2 Integration (`src/test/java/.../integration`)

`@SpringBootTest` extending `IntegrationTestBase`, which starts (via Testcontainers): a 3-node MongoDB replica set, a Kafka broker (KRaft), and Keycloak (with the production realm imported). No in-memory substitutes — see §5.0.

| Scenario | Assertions |
|---|---|
| **Happy-path transfer** | `accounts.balance` debited+credited correctly; 2 journal lines posted (DEBIT + CREDIT, same `transactionId`); `transactions` doc has `status=COMPLETED`; `outbox` row inserted with `status=PENDING`; after `OutboxPublisher.drain()` runs, the row is `SENT` and a message is on the Kafka topic with the expected envelope and payload. |
| **Idempotent replay (same key, same payload)** | Two requests with the same `Idempotency-Key` and identical body produce **one** transactions doc, **one** outbox row, **one** Kafka message; second response has the same `transactionId` as the first. |
| **Idempotent conflict (same key, different payload)** | Second request returns `409 IDEMPOTENCY_KEY_CONFLICT`; no new transactions doc created. |
| **Concurrent identical requests (race)** | Spawn `N=20` threads sending the same `Idempotency-Key` simultaneously. Only **one** transactions doc exists at the end. The other 19 either get `200 OK` (replay) or `409 IDEMPOTENCY_IN_PROGRESS`. |
| **Insufficient funds** | Account balance unchanged; no journal lines; no outbox row; response is `422 INSUFFICIENT_FUNDS` with `params: { available, requested, accountId, currency }`. |
| **Currency mismatch** | Same: state unchanged; `422 CURRENCY_MISMATCH`. |
| **Frozen source account** | `422 ACCOUNT_UNAVAILABLE`. |
| **Self-transfer** | Caught pre-transaction; `422 SELF_TRANSFER`. |
| **Crash between commit and publish** | Force-kill the OutboxPublisher mid-batch (via a test hook). Restart. Assert the outbox row is republished and the consumer sees the event exactly once (via inbox dedupe in a test consumer). |
| **Kafka down** | Stop the Kafka container. Submit a transfer. Assert the transaction commits, the outbox row stays `PENDING`, the response is `201`. Restart Kafka; assert the outbox drains and the event appears. |
| **Reversal happy path** | Original tx becomes `REVERSED`; reversal tx is `COMPLETED` with `correctsTransactionId` set; **journal lines for the original are untouched**; two new journal lines posted; balances restored to pre-original state. |
| **Reversal of a reversal** | `422 ORIGINAL_TRANSACTION_NOT_REVERSIBLE`. |
| **Get history pagination** | Insert 60 transactions. `GET /v1/transactions?limit=25` returns 25 + `nextCursor`; next page returns 25 + `nextCursor`; final page returns 10 + `hasMore=false`. |
| **Concurrent insert during pagination** | Start paging; insert a new transaction at position 0; continue paging. Assert no duplicate, no skip (cursor stability). |

### 5.3 API (`src/test/java/.../api`)

`@WebMvcTest` + MockMvc. Confirms HTTP-layer concerns without the persistence stack.

| Scenario | Assertions |
|---|---|
| `Authorization` missing | 401 with `code=UNAUTHORIZED`, `Content-Type: application/problem+json` |
| `Idempotency-Key` missing on POST | 400 with `code=MISSING_IDEMPOTENCY_KEY` |
| Malformed JSON | 400 with `code=MALFORMED_REQUEST` |
| Field validation (negative amount) | 400 with `code=VALIDATION_FAILED` and `errors[].code=MUST_BE_POSITIVE` |
| `Accept-Language: fr-FR` | `title` / `detail` in French (if message bundles present); `code` unchanged |
| Every `DomainException` subtype is mapped | Reflective test: iterate sealed subtypes, instantiate, throw, assert handler emits a non-500 response with a non-null `code` |

### 5.4 Property-based

One test using **jqwik**: *for any random sequence of valid transfers among a closed set of accounts, the sum of all balances equals the initial sum.* This is the fundamental theorem of accounting; if it ever fails, we have a structural defect.

### 5.5 Performance smoke

A `gatling-it` profile (kept out of normal CI; runnable on demand) that hammers `POST /v1/transactions` at 200 RPS for 60s against a Testcontainers-launched Mongo + Kafka + Keycloak stack (same `IntegrationTestBase`). Asserts p99 < 500 ms and zero errors. Not a SLO test — just a regression canary.

---

## 6. Operational concerns

### 6.1 Metrics emitted

All via Micrometer with `service=transaction-service` tag plus per-metric tags below.

| Metric | Type | Tags | Purpose |
|---|---|---|---|
| `http_server_requests_seconds` | histogram | `route`, `method`, `status`, `code` | RED metrics; `code` tag lets us slice by error type |
| `transactions_total` | counter | `type`, `status`, `currency` | TPS by outcome |
| `transactions_amount_total` | counter | `type`, `currency` | Value moved per currency (for business dashboards) |
| `transactions_in_flight` | gauge | — | Concurrent transfer requests |
| `transactions_duration_seconds` | histogram | `type`, `status` | Distribution; p50/p95/p99 of transfer latency |
| `transactions_idempotent_replay_total` | counter | `route` | How often clients retry — sudden spike == client bug or network event |
| `outbox_pending_count` | gauge | — | Backlog. Alert if > 10 000 |
| `outbox_published_total` | counter | `topic`, `outcome` | Publisher success/failure rate |
| `outbox_poisoned_total` | counter | `topic` | Permanent failures sent to alert |
| `mongo_transaction_retries_total` | counter | `outcome` | Transient-write-conflict retries |
| `mongo_transaction_duration_seconds` | histogram | — | Transaction wall time |

### 6.2 Structured log fields (all logs)

```json
{
  "timestamp":     "2026-05-28T10:00:00.123Z",
  "level":         "INFO",
  "service":       "transaction-service",
  "version":       "1.4.2",
  "correlationId": "01HZ8M...",
  "traceId":       "00-...",
  "spanId":        "...",
  "userId":        "U-01HZ...",       (when known)
  "transactionId": "TX-01HZ...",      (when known)
  "event":         "transfer.completed",
  "message":       "..."
}
```

PII (email, phone, fullName) is **never** logged. The schema validator on the structured-log appender rejects messages with PII-shaped fields — a defence in depth.

### 6.3 Health checks

| Endpoint | Checks |
|---|---|
| `GET /actuator/health/liveness` | JVM only (always returns UP unless the process can't respond) |
| `GET /actuator/health/readiness` | Mongo ping AND Kafka producer healthy AND Keycloak JWKS reachable (cached, 60s freshness). All three must be UP for readiness. |

Failing readiness → pod removed from Service endpoints; failing liveness → restarted. Strict readiness + lenient liveness is the contract (see `ARCHITECTURE.md` §8).

### 6.4 Graceful shutdown

`spring.lifecycle.timeout-per-shutdown-phase: 30s`. Sequence on SIGTERM:

1. Mark readiness DOWN (gateway stops routing new traffic).
2. Stop accepting new HTTP requests.
3. Wait for in-flight `transfer()` calls to complete (≤ 30s).
4. Stop the outbox publisher mid-batch — uncommitted rows have leases that expire after `LEASE_DURATION` so another replica picks them up.
5. Drain Kafka producer (flush buffered sends).
6. Exit.

A pod that exits within 30s leaves no half-committed state — guaranteed by the all-or-nothing Mongo transaction.

---

## 7. Open questions

| # | Question | Default if no answer |
|---|---|---|
| 7.1 | Java version: 21 (current LTS) or 17 (previous LTS)? | **21** — newer APIs (sealed classes used in `DomainException`, record patterns) make the code cleaner; distroless `java21-debian12` image is available. |
| 7.2 | Spring Boot version: 3.5.x, 4.0.x, or pin to a specific patch? | **4.0.6** — current default on start.spring.io; brings Spring Framework 7, Jackson 3 (`tools.jackson.*`), Spring Cloud 2025.1.x. We migrated off Mongock (no SB4 artifact yet) to a `SchemaInitializer` Spring bean using `MongoTemplate.indexOps.createIndex` directly. |
| 7.3 | Mongo driver: Spring Data MongoDB (annotation-driven) or the lower-level reactive driver? | **Spring Data MongoDB** (sync). Reactive is overkill for our scale and complicates transactions; the team also has stronger sync-Mongo skills. |
| 7.4 | Validation message source: simple `messages.properties` or full ICU MessageFormat? | **Simple `messages.properties`** for the test; ICU is overkill given the FE handles interpolation from `params`. |
| 7.5 | OpenAPI generation: springdoc-openapi to auto-generate at `/v3/api-docs`, or hand-written YAML? | **springdoc-openapi**. Auto-generated stays in sync with the code. Hand-written drifts. |
| 7.6 | Should the outbox publisher emit OpenTelemetry spans across the Kafka boundary? | **Yes** — `traceparent` is in the envelope and the Kafka headers; consumers continue the trace. Needed for the end-to-end trace claim in `ARCHITECTURE.md` §8. |
| 7.7 | Should we ship a `gatling-it` Maven profile in the test submission, even if it's not run by default? | **Yes** — proves we thought about performance regression. Wires through `mvn -Pgatling verify`. |

Defaults above will be applied unless you'd prefer to discuss any individually.

---

## 8. Acceptance criteria

This spec is considered complete when:

- [ ] Every class listed in §3.2 exists with the responsibilities described in §3.3
- [ ] The transfer flow in §4.1 is implemented exactly (deviation = bug)
- [ ] The reversal flow in §4.2 is implemented; journal lines are never UPDATEd
- [ ] The outbox publisher in §4.4 runs in compose and the integration test in §5.2 (crash-recovery) passes
- [ ] Every exception in §4.6 is mapped; the reflective test in §5.3 passes
- [ ] All metrics in §6.1 are visible in Grafana via the preloaded dashboard
- [ ] `application.yaml` exposes the knobs in §4.5 and every value is overridable by env var
- [ ] No `kafkaTemplate.send()` exists outside `OutboxPublisher` (enforced by an ArchUnit test)
- [ ] No `BigDecimal` or `double` exists in the money path (enforced by an ArchUnit test)
- [ ] No string concatenation into Mongo queries exists (enforced by ArchUnit + Sonar rule)

The ArchUnit tests in particular are part of the deliverable — they encode the spec's *negative* constraints as executable rules.
