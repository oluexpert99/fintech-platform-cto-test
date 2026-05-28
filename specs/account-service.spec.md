# Spec — Account Service

**Module:** `services/account-service/`
**Package root:** `com.example.fintech.accounts`
**Status:** Draft for review
**Related:** [`../docs/ARCHITECTURE.md` §4](../docs/ARCHITECTURE.md#part-4--microservices-design) · [`../docs/api.md` §9](../docs/api.md#9-accounts) · `data-model.spec` (§5.2 `accounts`) · `events.spec` (§7.4, §7.5 account events)

---

## 1. Purpose

Account Service owns the lifecycle of customer accounts: open, label, freeze, unfreeze, close. It is the only service that writes the *non-balance* fields of `accounts.*`. Balance is written by Transaction Service inside the transfer transaction (`data-model.spec` §5.2); everything else — `label`, `status`, `statusReason`, lifecycle timestamps — lives here.

It is a small, focused service. Most of its job is enforcing the **state-transition state machine** and publishing the resulting events.

## 2. Scope

### In scope

- `POST /v1/accounts` (open)
- `GET /v1/accounts/{id}` (read one)
- `GET /v1/accounts` (list mine)
- `PATCH /v1/accounts/{id}` (label edits, status transitions — freeze/unfreeze/close)
- `GET /v1/accounts/{id}/balance` (lightweight balance read with `readConcern: majority`)
- Publishing `accounts.account.opened` and `accounts.account.status-changed` via the **transactional outbox** (per [ADR-0002](../docs/decisions/0002-idempotency-and-exactly-once.md))
- Idempotency enforcement
- Ownership check (caller `sub` == `accounts.ownerUserId`) on all read and write endpoints
- Optimistic concurrency via `If-Match: <version>` header

### Out of scope

- Money movement (owned by **Transaction Service**)
- User identity / KYC level / status (owned by **Auth Service**)
- Reading the journal or building reports (owned by **Accounting projector** — documented only)
- FX, multi-currency conversion (out per scope decision)

---

## 3. Contract

### 3.1 HTTP surface

Authoritative in [`../docs/api.md` §9](../docs/api.md#9-accounts). Summary:

| Method | Path | Idempotency-Key | Returns | Scope |
|---|---|---|---|---|
| `POST`  | `/v1/accounts` | required | `201 Created` + Location | `accounts:write` |
| `GET`   | `/v1/accounts/{id}` | n/a | `200 OK` | `accounts:read` |
| `GET`   | `/v1/accounts` | n/a | paginated `{ data, page }` | `accounts:read` |
| `PATCH` | `/v1/accounts/{id}` | required; supports `If-Match: <version>` | `200 OK` (updated account) | `accounts:write` (or operator role for operator-only transitions) |
| `GET`   | `/v1/accounts/{id}/balance` | n/a | `200 OK` (lightweight) | `accounts:read` |

### 3.2 Internal package structure

```
com.example.fintech.accounts/
├── AccountServiceApplication.java
│
├── api/
│   ├── AccountsController.java
│   ├── dto/
│   │   ├── OpenAccountRequest.java
│   │   ├── PatchAccountRequest.java        ← all fields optional; ≥1 required
│   │   ├── AccountResponse.java
│   │   ├── BalanceResponse.java
│   │   └── ProblemResponse.java
│   └── ProblemExceptionHandler.java
│
├── domain/
│   ├── model/
│   │   ├── AccountId.java
│   │   ├── AccountStatus.java              ← enum {ACTIVE, FROZEN, CLOSED}
│   │   ├── AccountType.java                ← enum {CHECKING, SAVINGS}
│   │   ├── StatusReason.java               ← enum (per api.md §9)
│   │   └── Account.java                    ← domain aggregate
│   ├── exception/
│   │   ├── AccountNotFoundException.java
│   │   ├── InvalidStateTransitionException.java
│   │   ├── OperatorApprovalRequiredException.java
│   │   ├── DualControlRequiredException.java
│   │   ├── ForbiddenFieldEditException.java
│   │   └── (others...)
│   └── policy/
│       ├── StatusTransitionPolicy.java     ← the state machine in §4.2
│       └── FieldEditPolicy.java            ← who can edit what (§4.3)
│
├── application/
│   ├── OpenAccountService.java
│   ├── PatchAccountService.java
│   ├── AccountFinder.java
│   └── IdempotencyService.java
│
├── persistence/
│   ├── document/
│   │   ├── AccountDocument.java
│   │   └── OutboxRecordDocument.java
│   ├── repository/
│   │   ├── AccountRepository.java
│   │   └── OutboxRepository.java
│   └── mapper/
│       └── AccountMapper.java
│
├── messaging/
│   ├── envelope/EventEnvelopeBuilder.java
│   ├── event/
│   │   ├── AccountOpenedEvent.java
│   │   └── AccountStatusChangedEvent.java
│   └── OutboxPublisher.java
│
└── config/
    ├── SecurityConfig.java
    ├── MongoConfig.java
    ├── KafkaConfig.java
    └── ObservabilityConfig.java
```

Identical shape to `transaction-service`, scoped smaller.

### 3.3 Public method signatures

```java
@Service
public class OpenAccountService {
    public AccountResponse open(UserId caller, String idempotencyKey, OpenAccountRequest req);
}

@Service
public class PatchAccountService {
    public AccountResponse patch(UserId caller,
                                 Set<Role> callerRoles,
                                 AccountId accountId,
                                 String idempotencyKey,
                                 Long ifMatchVersion,             // nullable
                                 PatchAccountRequest req);
}

@Service
public class AccountFinder {
    public AccountResponse get(UserId caller, AccountId accountId);
    public Page<AccountResponse> list(UserId caller, ListAccountsQuery query);
    public BalanceResponse balance(UserId caller, AccountId accountId);
}
```

---

## 4. Behaviour

### 4.1 Open account

```
open(caller, key, req):
  // 1. Idempotency check (same pattern as transaction-service.spec §4.3)
  existing = idempotencyService.find(caller, "open-account", key)
  if existing && existing.payloadMatches(req): return existing.response
  if existing && !payloadMatches: throw IdempotencyConflict           → 409

  // 2. Atomic insert (no multi-doc TX needed — single collection)
  account = new AccountDocument(
    _id          = AccountId.generate(),
    ownerUserId  = caller,
    currency     = req.currency,
    type         = req.type,
    label        = req.label,
    balance      = 0,
    status       = ACTIVE,
    version      = 1,
    createdAt    = now,
    updatedAt    = now,
    idempotencyKey = scopedKey(caller, "open-account", key)
  )

  withMongoTransaction:                                                 // for outbox consistency
    accountRepository.insert(account)                                   // unique-index on idempotencyKey
    outboxRepository.insert(new OutboxRecord(
      topic   = "accounts.account.opened",
      eventId = ulid(),
      payload = envelope(new AccountOpenedEvent(account))
    ))

  return AccountResponse(account)                                       → 201
```

Note: even though this is a single-collection write, we wrap the `accounts.insert` and `outbox_acc.insert` in **one Mongo transaction** so the outbox record is durably committed iff the account is — same dual-write defence as Transaction Service.

### 4.2 PATCH — the state-transition state machine

The policy class `StatusTransitionPolicy.java` is the single chokepoint. It accepts the current account state, the target patch, and the caller's roles, and returns either `Allowed` or a specific exception.

```
patch(caller, roles, accountId, key, ifMatchVersion, req):
  // 1. Idempotency check (skip body for brevity)
  ...

  withMongoTransaction:
    account = accountRepository.findById(accountId)
    if account == null:                                                    → 404 RESOURCE_NOT_FOUND
    if account.ownerUserId != caller && !roles.contains(OPERATOR):
                                                                           → 403 FORBIDDEN
    if ifMatchVersion != null && account.version != ifMatchVersion:
                                                                           → 409 VERSION_CONFLICT

    fieldEditPolicy.check(account, roles, req)
      // - `label` only by owner
      // - `status` per state machine
      // - any field not in {label, status, reason} → ForbiddenFieldEditException

    if req.status != null:
      statusTransitionPolicy.check(account, roles, req.status, req.reason)
      // Throws one of:
      //   InvalidStateTransitionException        → 409 INVALID_STATE_TRANSITION
      //   OperatorApprovalRequiredException      → 403 OPERATOR_APPROVAL_REQUIRED
      //   DualControlRequiredException           → 403 OPERATOR_APPROVAL_REQUIRED (with details)

    // Apply patch (only the fields present in the request)
    account.applyPatch(req, now)
    account.version += 1
    accountRepository.save(account)

    if req.status != null:
      outboxRepository.insert(new OutboxRecord(
        topic   = "accounts.account.status-changed",
        eventId = ulid(),
        payload = envelope(new AccountStatusChangedEvent(account, previousStatus, req.reason, operatorId))
      ))

  return AccountResponse(account)                                          → 200
```

### 4.3 State transition matrix

Codified in `StatusTransitionPolicy`. **Must** match `data-model.spec` §5.2 and `api.md` §9 exactly.

| From → To | `ACTIVE` | `FROZEN` | `CLOSED` |
|---|---|---|---|
| `ACTIVE`  | — (no-op, return current) | owner OR operator | owner-only, **requires `balance == 0`** |
| `FROZEN`  | **operator only**, **dual-control** if `reason ∈ {FRAUD_SUSPECTED, COMPLIANCE_HOLD}` | — (no-op) | operator-only |
| `CLOSED`  | ❌ throws `InvalidStateTransition` | ❌ | terminal — any change throws |

Implementation:

```java
public class StatusTransitionPolicy {
    public void check(Account account, Set<Role> roles, AccountStatus target, StatusReason reason) {
        if (account.status() == CLOSED) {
            throw new InvalidStateTransitionException(account.status(), target);
        }
        if (target == CLOSED && account.balance() != 0) {
            throw new InvalidStateTransitionException(account.status(), target, "balance must be zero");
        }
        if (account.status() == FROZEN && target == ACTIVE) {
            if (!roles.contains(OPERATOR)) {
                throw new OperatorApprovalRequiredException();
            }
            // Note: we don't check the dual-control approver here at the policy level;
            // the controller demands `approverId` in the body for unfreezes from sensitive reasons,
            // verified against a separate `pendingApprovals` collection (out of scope for this spec).
        }
        if (target == FROZEN && account.status() == ACTIVE) {
            // owner OR operator — both allowed (owner can freeze their own account)
        }
        // ... etc
    }
}
```

### 4.4 Field-edit permissions (`FieldEditPolicy`)

| Field in PATCH body | Who can change it |
|---|---|
| `label` | **Owner only.** Operators cannot rename customer accounts. |
| `status` | Per `StatusTransitionPolicy`. |
| `reason` | Required whenever `status` is supplied. |
| Anything else (e.g. `currency`, `balance`, `ownerUserId`, `version`, `createdAt`) | Forbidden — `ForbiddenFieldEditException` → `403 FORBIDDEN`. |

The DTO `PatchAccountRequest` only declares the editable fields, but a malformed client could attempt to include extras as ignored properties; we run a Jackson `FAIL_ON_UNKNOWN_PROPERTIES = true` so unknown fields fail at deserialisation (400).

### 4.5 Lightweight balance endpoint

```
GET /v1/accounts/{id}/balance:
  caller, accountId ← from JWT + URL
  account = accountRepository.findById(accountId, readConcern=MAJORITY)
  authz: account.ownerUserId == caller OR roles.OPERATOR
  return BalanceResponse(account.id, account.balance, account.currency, account.updatedAt)
```

Distinct from `GET /v1/accounts/{id}` because:
- it returns less (no `label`, `type`, etc.) — caching-friendly
- it reads with `majority` concern even when the rest of the API would tolerate `local`, because the FE needs read-your-writes after a transfer

### 4.6 Outbox publisher

Same lease-based algorithm as `transaction-service.spec` §4.4 — the implementation is **per-service**, not a shared library (per the codebase structure decision). Each service has its own `OutboxPublisher` reading its own `outbox_<svc>` collection.

Configuration knobs:
- `outbox.publisher.tick-millis: 300` (slightly slower than tx-service; account events are less hot)
- `outbox.publisher.batch-size: 50`
- `outbox.publisher.lease-duration-seconds: 5`
- `outbox.publisher.max-attempts: 10`

### 4.7 Configuration (`application.yaml`)

```yaml
spring:
  application.name: account-service
  data.mongodb.uri: ${MONGO_URI}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP}
    producer:
      acks: all
      enable-idempotence: true
      properties:
        max.in.flight.requests.per.connection: 5
        compression.type: lz4
  security.oauth2.resourceserver.jwt:
    issuer-uri: ${KEYCLOAK_ISSUER_URI}

server.port: 8080
management.server.port: 8081
management.endpoints.web.exposure.include: health,prometheus,info

outbox.publisher:
  tick-millis: 300
  batch-size: 50
  lease-duration-seconds: 5
  max-attempts: 10
```

### 4.8 Error → HTTP mapping

| Exception | HTTP | `code` |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST` |
| `MissingIdempotencyKeyException` | 400 | `MISSING_IDEMPOTENCY_KEY` |
| `AuthenticationException` | 401 | `UNAUTHORIZED` |
| `AccessDeniedException` | 403 | `FORBIDDEN` |
| `OperatorApprovalRequiredException` | 403 | `OPERATOR_APPROVAL_REQUIRED` |
| `DualControlRequiredException` | 403 | `OPERATOR_APPROVAL_REQUIRED` |
| `ForbiddenFieldEditException` | 403 | `FORBIDDEN` |
| `AccountNotFoundException` | 404 | `ACCOUNT_NOT_FOUND` |
| `IdempotencyConflictException` | 409 | `IDEMPOTENCY_KEY_CONFLICT` |
| `IdempotencyInProgressException` | 409 | `IDEMPOTENCY_IN_PROGRESS` |
| `InvalidStateTransitionException` | 409 | `INVALID_STATE_TRANSITION` |
| `OptimisticLockingFailureException` (or `If-Match` mismatch) | 409 | `VERSION_CONFLICT` |
| `Exception` | 500 | `INTERNAL` |
| Mongo unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |

The reflective test (`AccountDomainException` sealed hierarchy → mapping presence) is mandatory, same pattern as `transaction-service.spec` §5.3.

---

## 5. Tests

See `transaction-service.spec.md` §5.0 — **Testcontainers only**.

### 5.1 Unit (`unit/`)

| Class | Asserts |
|---|---|
| `StatusTransitionPolicy` | Every cell of the §4.3 matrix. ~12 test cases. Including: "ACTIVE→CLOSED with non-zero balance throws"; "FROZEN→ACTIVE without operator role throws"; etc. |
| `FieldEditPolicy` | Label by owner OK; label by operator throws; status by operator OK; unknown field throws |
| `OpenAccountService` (mocked repos) | Happy path; duplicate idempotency key replay; conflict |
| `PatchAccountService` (mocked repos) | Each policy violation produces the expected exception |
| `EventEnvelopeBuilder` | Envelope conformance to `events.spec` §4 |

### 5.2 Integration (`integration/`)

Extends `IntegrationTestBase` (real Mongo RS + real Kafka via Testcontainers).

| Scenario | Asserts |
|---|---|
| Open account happy path | Account inserted; outbox row inserted with `topic="accounts.account.opened"`; after publisher tick, Kafka receives the event with correct envelope |
| Open account idempotent replay (same key, same payload) | Single account in collection; single outbox row; single Kafka message |
| Open account idempotency conflict | `409 IDEMPOTENCY_KEY_CONFLICT` |
| PATCH ACTIVE → FROZEN by owner | `accounts.status == FROZEN`, `accounts.statusReason == USER_REQUESTED`, `accounts.frozenAt` set; outbox `accounts.account.status-changed` event with `previousStatus=ACTIVE`, `newStatus=FROZEN` |
| PATCH FROZEN → ACTIVE by owner | `403 OPERATOR_APPROVAL_REQUIRED`; state unchanged |
| PATCH FROZEN → ACTIVE by operator (non-sensitive reason) | Allowed; status changed; event published |
| PATCH FROZEN → ACTIVE by operator (FRAUD_SUSPECTED reason) without approver | `403 OPERATOR_APPROVAL_REQUIRED` (dual-control required) |
| PATCH ACTIVE → CLOSED with non-zero balance | `409 INVALID_STATE_TRANSITION` |
| PATCH ACTIVE → CLOSED with zero balance | Allowed; status changed; event published |
| PATCH label by owner | Allowed; updated; `outbox` row **not** emitted (only status changes emit) |
| PATCH label by operator | `403 FORBIDDEN` |
| PATCH `currency` field | `400 VALIDATION_FAILED` or `403 FORBIDDEN` (depending on whether the DTO rejects it earlier — both acceptable; the test just asserts the patch did not apply) |
| Optimistic-lock conflict via `If-Match` | Get account at version=5; PATCH with `If-Match: 5`; concurrently PATCH from another thread → version becomes 6; first PATCH commits → `409 VERSION_CONFLICT` returned by the *second* PATCH attempt (assuming our re-test approach); a third PATCH with `If-Match: 6` succeeds |
| Cross-service write rule | Run a contract test that uses the `fintech_writer` role to attempt an `update` on `journal` — assert Mongo returns `Unauthorized`. (Repeated from `data-model.spec` for this service's role.) |
| Crash mid-PATCH | Force-kill mid-transaction; restart; assert no half-applied state (`accounts.status` unchanged, `outbox_acc` row not present) |

### 5.3 API (`api/`)

Standard MockMvc tests for HTTP-layer concerns (401, 400, validation, problem-detail shape with `code` + `params`).

### 5.4 Property-based

Given any sequence of valid PATCH operations starting from `ACTIVE`, the final state is reachable per the §4.3 matrix and the journal of `accounts.account.status-changed` events plays back the same final state. (Useful for catching policy-class bugs at scale.)

---

## 6. Operational concerns

### 6.1 Metrics

| Metric | Tags |
|---|---|
| `http_server_requests_seconds` (Spring default) | route, method, status, `code` |
| `accounts_opened_total` | currency, type |
| `accounts_status_changes_total` | previousStatus, newStatus, reason |
| `accounts_state_transition_denied_total` | reason (`forbidden`, `state-machine`, `dual-control-missing`) |
| `outbox_pending_count{service="account-service"}` | — |
| `outbox_published_total{service="account-service"}` | topic, outcome |

### 6.2 Structured logging fields

Inherits the conventions in `transaction-service.spec` §6.2. Account-specific addition: `accountId` when known.

### 6.3 Health checks

- **Liveness:** JVM up.
- **Readiness:** Mongo ping + Kafka producer healthy + Keycloak JWKS reachable (cached 60s).

### 6.4 Graceful shutdown

Same shutdown sequence as `transaction-service.spec` §6.4 — drain HTTP, stop publisher, flush Kafka, exit.

---

## 7. Open questions

| # | Question | Default |
|---|---|---|
| 7.1 | Should account opening assert KYC level via a remote call to Auth Service, or trust the JWT's `kyc` claim? | **Trust the JWT claim** for MVP. The gateway has validated the token; Auth Service is the issuer of the claim. Avoids a synchronous cross-service call on the hot path. |
| 7.2 | Should we support `POST /v1/accounts/bulk` for operator account-creation? | **No** for MVP — out of scope. |
| 7.3 | Should `GET /v1/accounts/{id}` return the journal-line count alongside the balance? | **No** — keep the read endpoint focused; consumers who need that ask the Accounting projector. |
| 7.4 | Should we emit `accounts.account.label-changed` events? | **No** — labels are private user metadata. No downstream consumer needs them. |

---

## 8. Acceptance criteria

- [ ] Every class in §3.2 exists; the package layout matches
- [ ] §4.2 PATCH algorithm implemented; the §4.3 matrix is in `StatusTransitionPolicy` and asserted by 12+ unit tests
- [ ] `FieldEditPolicy` enforces the §4.4 rules; integration test confirms operator can't relabel
- [ ] `If-Match` header support implemented; integration test (§5.2) passes
- [ ] Every exception in §4.8 mapped; reflective test passes
- [ ] Outbox publisher runs and the integration tests in §5.2 covering events all pass
- [ ] All metrics in §6.1 visible in the preloaded Grafana dashboard
- [ ] No `kafkaTemplate.send` outside `OutboxPublisher` (ArchUnit)
- [ ] No write to `journal` from this service possible (database-role test)
