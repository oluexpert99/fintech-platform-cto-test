# Spec — Events (Kafka)

**Scope:** Every Kafka topic and event payload the platform produces or consumes.
**Status:** Draft for review
**Related:** [`../docs/ARCHITECTURE.md` §6](../docs/ARCHITECTURE.md#part-6--event-driven-architecture) · [ADR-0002](../docs/decisions/0002-idempotency-and-exactly-once.md) · [ADR-0004](../docs/decisions/0004-event-schema-and-evolution.md) · `data-model.spec` (outbox collections)

---

## 1. Purpose

Events written to Kafka are **immutable** — once published, they exist forever (key topics use infinite retention; see §5.3). The schema you ship on day one is the schema future consumers will replay against in year five. This spec freezes the v1 envelope and v1 payloads explicitly, alongside the rules for evolving them.

If `data-model.spec` is the contract for what we store, this spec is the contract for what we tell other services has happened.

## 2. Scope

### In scope

- The shared **event envelope** and its fields.
- Concrete **JSON Schema** for every event we publish at MVP.
- **Topic configuration**: name, partition count, replication factor, retention, cleanup policy, key, compaction.
- **Producer wire-config** (acks, idempotence, compression).
- **Consumer wire-config** (offset strategy, manual commit, inbox dedupe).
- **DLT & retry topic** conventions.
- Schema registry compatibility mode.

### Out of scope

- The outbox publisher algorithm (see `transaction-service.spec` §4.4 — it's per-service implementation).
- Consumer business logic for downstream services (Notification, Fraud Detection, Accounting projector are *documented only* for this submission).
- Cross-region replication (MirrorMaker 2 details — see [ADR-0005](../docs/decisions/0005-multi-region-and-ha.md)).

---

## 3. Serialisation and registry

Per [ADR-0004](../docs/decisions/0004-event-schema-and-evolution.md):

- **Format:** JSON.
- **Schema declaration:** [JSON Schema 2020-12](https://json-schema.org/draft/2020-12).
- **Registry:** [Apicurio](https://www.apicurio.io/) (Confluent-compatible REST API).
- **Compatibility mode:** `BACKWARD` — new schemas can read messages written with the previous schema; consumers upgrade first, then producers.
- **Schema files** live in `events/schemas/<topic>-value.v<n>.json`. The version number is part of the filename; the registry stores all versions under one *subject* (`<topic>-value`).

### 3.1 Registration in CI

The CI pipeline (`ci.spec.md`) runs a job that:
1. POSTs each `events/schemas/*.json` file to a Testcontainers Apicurio.
2. Asserts every file registers cleanly *under BACKWARD compatibility*.
3. For every PR that modifies a schema file, asserts the change is BACKWARD-compatible against the version already in the central registry.

A PR that violates BACKWARD compatibility **cannot merge**.

---

## 4. Envelope

Every event on every topic shares the same outer envelope. The payload-specific shape lives in `data`.

### 4.1 Envelope schema (`events/schemas/_envelope.v1.json`)

```jsonc
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id":     "https://api.example.com/schemas/_envelope.v1.json",
  "title":   "EventEnvelope v1",
  "type":    "object",
  "required": ["eventId", "eventType", "eventVersion", "occurredAt", "producedAt", "producer", "correlationId", "data"],
  "additionalProperties": false,
  "properties": {
    "eventId":       { "type": "string", "pattern": "^[0-9A-HJKMNP-TV-Z]{26}$" },          // ULID
    "eventType":     { "type": "string", "pattern": "^[A-Z][A-Za-z0-9]+Event$" },
    "eventVersion":  { "type": "integer", "minimum": 1 },
    "occurredAt":    { "type": "string", "format": "date-time" },
    "producedAt":    { "type": "string", "format": "date-time" },
    "producer":      { "type": "string", "pattern": "^[a-z-]+@[0-9]+\\.[0-9]+\\.[0-9]+$" }, // e.g. transaction-service@1.4.2
    "correlationId": { "type": "string" },
    "causationId":   { "type": ["string", "null"] },
    "traceparent":   { "type": ["string", "null"] },
    "data":          { "type": "object" }
  }
}
```

Each event's specific schema imports this envelope via `$ref` and tightens the `data` shape.

### 4.2 Field semantics

| Field | Notes |
|---|---|
| `eventId` | ULID. **Globally unique.** This is the consumer's dedupe key (inbox pattern). The same `eventId` reappearing means "republish" — never "new event". |
| `eventType` | The Java class name of the payload. Used for routing in polymorphic consumers. |
| `eventVersion` | Human-readable. Authoritative version is the schema registered against the subject. `eventVersion` is for the developer skimming logs. |
| `occurredAt` | When the business event happened. May predate `producedAt` (e.g., if events are batched). |
| `producedAt` | When the envelope was constructed. `producedAt - occurredAt` is the producer-side latency. |
| `producer` | Service name + version. Lets us correlate event quality to a specific deploy. |
| `correlationId` | The originating HTTP request's correlation ID. Survives the Kafka boundary so logs across services can be joined. |
| `causationId` | The `eventId` (or command ID) that caused this event. Powers saga / debugging tools. |
| `traceparent` | W3C Trace Context — also placed in **Kafka headers** for tooling that reads only headers. Duplicated in the body for replay-time access. |
| `data` | Event-specific payload, governed by per-event schemas in §5. |

### 4.3 Kafka message headers

Producers MUST set the following Kafka headers in addition to placing the same values in the envelope:

| Header | Value |
|---|---|
| `eventId` | The envelope's `eventId` — consumers can dedupe without deserialising the body |
| `eventType` | The envelope's `eventType` — polymorphic routing |
| `traceparent` | W3C Trace Context — OpenTelemetry auto-instrumentation reads this header by default |
| `content-type` | `application/json; charset=utf-8` |

Kafka headers travel through MirrorMaker 2; we do not lose them on cross-region replication.

---

## 5. Topic catalogue

### 5.1 Naming

Pattern: `<bounded-context>.<aggregate>.<event-in-past-tense>`.

| Pattern part | Example | Constraint |
|---|---|---|
| bounded-context | `transactions`, `accounts`, `users` | lower-kebab, plural matches resource URL |
| aggregate | `transfer`, `account`, `user` | lower-kebab, singular |
| event-in-past-tense | `completed`, `failed`, `reversed`, `opened`, `frozen`, `registered` | lower-kebab, past tense |

Reserved suffixes (not bounded contexts):

- `<topic>.retry` — delayed re-delivery (see §6.3)
- `<topic>.DLT` — dead-letter topic (see §6.4)

### 5.2 Topics at MVP

| Topic | Producer | Initial consumers | Key | Partitions (dev / prod) | Cleanup policy | Retention |
|---|---|---|---|---|---|---|
| `transactions.transfer.completed` | Transaction Service | Notification, Fraud Detection, Accounting projector (all documented-only for MVP) | `transactionId` | **6 / 48** | `delete` | **infinite** (-1) — the event log of money movement |
| `transactions.transfer.failed` | Transaction Service | Notification, ops dashboards | `transactionId` | 3 / 24 | `delete` | 365 days |
| `transactions.transfer.reversed` | Transaction Service | Notification, Accounting projector | `transactionId` | 3 / 24 | `delete` | **infinite** |
| `accounts.account.opened` | Account Service | Onboarding follow-up, Accounting projector | `accountId` | 3 / 12 | `delete` | 365 days |
| `accounts.account.status-changed` | Account Service | Notification, fraud history | `accountId` | 3 / 12 | `delete` | 365 days |
| `users.user.registered` | Auth Service | Onboarding email | `userId` | 3 / 12 | `delete` | 90 days |

Plus their `.retry` and `.DLT` siblings, configured identically except retention `30 days`.

### 5.3 Why some topics are infinite retention

`transactions.transfer.completed` and `.reversed` collectively *are the event log of the platform's money*. A consumer subscribing in three years' time must be able to replay the entire history from offset 0 to build a balance projection. Infinite retention costs storage but it's the cost of an event-sourced system — and storage is the cheapest part of Kafka.

Operational topics (`*.retry`, `*.DLT`) and informational topics (`registered`, `opened`, `status-changed`) keep 30–365 days.

### 5.4 Partition strategy

| Decision | Rationale |
|---|---|
| Partition key = the aggregate ID (e.g. `transactionId` or `accountId`) | All events for the same aggregate land in the same partition → per-aggregate ordering preserved. Consumers can run one thread per partition without out-of-order processing. |
| **48** partitions in prod for `transactions.transfer.completed` | At 100 k tx/min = 1 667 tx/s, distributed across 48 partitions = ~35 events/sec/partition — comfortably within Kafka's per-partition consumer throughput, with headroom for re-balancing. |
| Partition count is set **once, at topic creation** | Resharding live topics is operationally painful (you can only add partitions, which breaks aggregate-key ordering for keys whose hash changes assignment). We over-provision conservatively at creation. |
| Compaction is **OFF** by default | Compaction discards old keys' messages, which would corrupt the event-log semantic. We only enable compaction on dedicated state-snapshot topics (not in this MVP). |

### 5.5 Replication & durability

| Config | Dev (compose) | Prod |
|---|---|---|
| `replication.factor` | 1 | **3** |
| `min.insync.replicas` | 1 | **2** |
| `unclean.leader.election.enable` | `false` | **`false`** |

`unclean.leader.election = false` means a broker that's been out of sync can never become leader — which gives correctness over availability. For money events, we choose correctness. The trade-off is that losing two of three brokers makes the cluster read-only until one recovers; we accept this and address availability through multi-AZ replication.

---

## 6. Producers and consumers

### 6.1 Producer wire-config

All producers across all services use:

```yaml
spring.kafka.producer:
  bootstrap-servers: ${KAFKA_BOOTSTRAP}
  acks: all                                            # leader + 2 ISRs
  enable-idempotence: true                             # exactly-once-per-producer-session
  properties:
    max.in.flight.requests.per.connection: 5           # OK with idempotence
    retries: 2147483647                                # rely on delivery.timeout
    delivery.timeout.ms: 120000
    compression.type: lz4
    request.timeout.ms: 30000
  key-serializer: org.apache.kafka.common.serialization.StringSerializer
  value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

`acks=all` + `enable.idempotence=true` is **mandatory** for producers that publish money-relevant events. Producers that do not set both are caught by an ArchUnit/integration test that fails the build.

### 6.2 Consumer wire-config

```yaml
spring.kafka.consumer:
  bootstrap-servers: ${KAFKA_BOOTSTRAP}
  enable-auto-commit: false                            # manual commit only — see inbox pattern
  auto-offset-reset: earliest                          # new consumers replay from beginning
  isolation-level: read_committed                      # ignore aborted transactional writes
  max-poll-records: 100
  properties:
    session.timeout.ms: 30000
    heartbeat.interval.ms: 10000
    fetch.min.bytes: 1
    partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
  key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
  value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
```

`enable.auto.commit = false` is **mandatory.** Auto-commit loses messages on rebalance — unacceptable for an inbox-pattern consumer. Build-time check enforces this.

### 6.3 Inbox dedupe (mandatory on every consumer)

Per [ADR-0002](../docs/decisions/0002-idempotency-and-exactly-once.md), every consumer that has side effects keeps a `processed_events` collection in its own Mongo database:

```jsonc
{
  "_id":         "<eventId>",          // ULID — same as envelope.eventId
  "topic":       "transactions.transfer.completed",
  "processedAt": ISODate("..."),
  "consumerGroup": "notif",
  "expireAt":    ISODate("...")        // 30 days; old enough that any redelivery would already have happened
}
```

**Consumer handler pattern:**

```java
@KafkaListener(topics = "transactions.transfer.completed", groupId = "notif")
public void onEvent(ConsumerRecord<String, EventEnvelope<TransactionCompletedEvent>> rec,
                    Acknowledgment ack) {
  try {
    inboxRepo.insert(new ProcessedEvent(rec.value().eventId(), rec.topic()));
  } catch (DuplicateKeyException e) {
    ack.acknowledge();                  // already processed → skip
    meter.counter("events.deduped", "topic", rec.topic()).increment();
    return;
  }
  handle(rec.value());                  // do the work
  ack.acknowledge();                    // commit offset ONLY on success
}
```

**Critical rule:** the inbox insert happens **before** the handler runs, and the offset commit happens **after**. This means:

- Inbox insert fails ⇒ another instance is processing it, or we already did. Skip.
- Inbox insert succeeds, handler fails ⇒ offset not committed ⇒ Kafka re-delivers ⇒ inbox dedupe skips ⇒ message lost from this consumer's perspective. **Acceptable only if the handler is idempotent** (e.g. notification provider keyed by `eventId`); otherwise the handler+inbox-insert must be wrapped in a single DB transaction.

The choice between "inbox-first then handler" and "handler+inbox in one TX" depends on the consumer; the rule is documented in each consumer's spec.

### 6.4 Retry topic + DLT

Failed processing (transient — e.g. external dependency timeout) is handled via a **retry topic** with delay header, **not** by in-place retry that head-of-line-blocks the partition.

```
main topic: transactions.transfer.completed
  ├─ on transient failure → publish to transactions.transfer.completed.retry
  │                          with header `x-delay-until = now + backoff`
  ├─ retry consumer waits until x-delay-until, then re-feeds main consumer logic
  └─ after N retries (default 5) → publish to transactions.transfer.completed.DLT
                                    operator alert; manual triage
```

Backoff schedule (per attempt): **30s, 5m, 30m, 2h, 12h**. After the 5th, DLT.

DLT volume is a tracked metric (`kafka_dlt_messages_total{topic}`). A non-zero DLT for `transactions.*` is an immediate page.

---

## 7. Event payloads (v1)

### 7.1 `transactions.transfer.completed` — `TransactionCompletedEvent v1`

`events/schemas/transactions.transfer.completed-value.v1.json`:

```jsonc
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id":     "https://api.example.com/schemas/transactions.transfer.completed-value.v1.json",
  "allOf":   [{ "$ref": "_envelope.v1.json" }],
  "properties": {
    "eventType":    { "const": "TransactionCompletedEvent" },
    "eventVersion": { "const": 1 },
    "data": {
      "type": "object",
      "required": ["transactionId", "type", "sourceAccount", "destinationAccount", "amount", "currency", "completedAt"],
      "additionalProperties": false,
      "properties": {
        "transactionId":      { "type": "string", "pattern": "^TX-[0-9A-HJKMNP-TV-Z]{26}$" },
        "type":               { "const": "TRANSFER" },
        "sourceAccount":      { "type": "string" },
        "destinationAccount": { "type": "string" },
        "amount":             { "type": "integer", "minimum": 1 },
        "currency":           { "type": "string", "pattern": "^[A-Z]{3}$" },
        "description":        { "type": ["string", "null"], "maxLength": 140 },
        "completedAt":        { "type": "string", "format": "date-time" }
      }
    }
  }
}
```

**Worked example (full envelope + payload):**

```json
{
  "eventId":       "01HZ8K1234567890ABCDEFGHJK",
  "eventType":     "TransactionCompletedEvent",
  "eventVersion":  1,
  "occurredAt":    "2026-05-28T10:00:00Z",
  "producedAt":    "2026-05-28T10:00:00.123Z",
  "producer":      "transaction-service@1.4.2",
  "correlationId": "01HZ8M9876543210ZYXWVUTSRQ",
  "causationId":   null,
  "traceparent":   "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
  "data": {
    "transactionId":      "TX-01HZ8K1234567890ABCDEFGH",
    "type":               "TRANSFER",
    "sourceAccount":      "ACC001",
    "destinationAccount": "ACC002",
    "amount":             100,
    "currency":           "USD",
    "description":        "Coffee",
    "completedAt":        "2026-05-28T10:00:00Z"
  }
}
```

**Note on PII:** the event carries `userId` nowhere intentionally. Consumers that need to notify or score look up the user by `sourceAccount` → `accounts.ownerUserId`. We do not bake names, emails, or phone numbers into the event — they'd live in the log forever.

### 7.2 `transactions.transfer.failed` — `TransactionFailedEvent v1`

Same envelope. Payload:

```jsonc
{
  "data": {
    "type": "object",
    "required": ["transactionId", "type", "sourceAccount", "destinationAccount", "amount", "currency", "failedAt", "reasonCode"],
    "additionalProperties": false,
    "properties": {
      "transactionId":      { "type": "string", "pattern": "^TX-..." },
      "type":               { "const": "TRANSFER" },
      "sourceAccount":      { "type": "string" },
      "destinationAccount": { "type": "string" },
      "amount":             { "type": "integer", "minimum": 1 },
      "currency":           { "type": "string", "pattern": "^[A-Z]{3}$" },
      "failedAt":           { "type": "string", "format": "date-time" },
      "reasonCode":         {
        "type": "string",
        "enum": ["INSUFFICIENT_FUNDS","ACCOUNT_UNAVAILABLE","CURRENCY_MISMATCH","LIMIT_EXCEEDED","INTERNAL_ERROR"]
      }
    }
  }
}
```

`reasonCode` mirrors the API `code` taxonomy — same machine-readable strings on the wire as in HTTP errors.

### 7.3 `transactions.transfer.reversed` — `TransactionReversedEvent v1`

```jsonc
{
  "data": {
    "type": "object",
    "required": ["transactionId", "type", "correctsTransactionId", "sourceAccount", "destinationAccount", "amount", "currency", "completedAt", "reason"],
    "additionalProperties": false,
    "properties": {
      "transactionId":         { "type": "string" },
      "type":                  { "const": "REVERSAL" },
      "correctsTransactionId": { "type": "string" },
      "sourceAccount":         { "type": "string" },
      "destinationAccount":    { "type": "string" },
      "amount":                { "type": "integer", "minimum": 1 },
      "currency":              { "type": "string", "pattern": "^[A-Z]{3}$" },
      "completedAt":           { "type": "string", "format": "date-time" },
      "reason":                { "type": "string", "maxLength": 500 }
    }
  }
}
```

### 7.4 `accounts.account.opened` — `AccountOpenedEvent v1`

```jsonc
{
  "data": {
    "type": "object",
    "required": ["accountId", "ownerUserId", "currency", "accountType", "openedAt"],
    "additionalProperties": false,
    "properties": {
      "accountId":   { "type": "string", "pattern": "^ACC[0-9]{6,}$" },
      "ownerUserId": { "type": "string", "pattern": "^U-..." },
      "currency":    { "type": "string", "pattern": "^[A-Z]{3}$" },
      "accountType": { "type": "string", "enum": ["CHECKING","SAVINGS"] },
      "openedAt":    { "type": "string", "format": "date-time" }
    }
  }
}
```

### 7.5 `accounts.account.status-changed` — `AccountStatusChangedEvent v1`

```jsonc
{
  "data": {
    "type": "object",
    "required": ["accountId", "previousStatus", "newStatus", "reason", "changedAt"],
    "additionalProperties": false,
    "properties": {
      "accountId":      { "type": "string" },
      "previousStatus": { "type": "string", "enum": ["ACTIVE","FROZEN","CLOSED"] },
      "newStatus":      { "type": "string", "enum": ["ACTIVE","FROZEN","CLOSED"] },
      "reason":         { "type": "string", "enum": ["USER_REQUESTED","USER_REQUEST_CLEARED","KYC_PENDING","FRAUD_SUSPECTED","COMPLIANCE_HOLD","CUSTOMER_CLOSED"] },
      "operatorId":     { "type": ["string", "null"] },
      "changedAt":      { "type": "string", "format": "date-time" }
    }
  }
}
```

### 7.6 `users.user.registered` — `UserRegisteredEvent v1`

```jsonc
{
  "data": {
    "type": "object",
    "required": ["userId", "registeredAt"],
    "additionalProperties": false,
    "properties": {
      "userId":       { "type": "string", "pattern": "^U-..." },
      "registeredAt": { "type": "string", "format": "date-time" }
    }
  }
}
```

Deliberately minimal — no email, no name. Consumers fetch them via the Users API if needed.

---

## 8. Schema evolution worked examples

The rules from [ADR-0004](../docs/decisions/0004-event-schema-and-evolution.md), with concrete examples we expect to face:

### 8.1 ✅ Adding an optional field — BACKWARD-compatible

v2 of `TransactionCompletedEvent` adds `category`. We update `events/schemas/transactions.transfer.completed-value.v2.json` adding the field as optional (`required` unchanged). Old consumers ignore it; new consumers see it.

### 8.2 ❌ Renaming a field — breaking

To rename `amount` to `amountMinor`:
1. **v2:** emit *both* `amount` and `amountMinor`; consumers read whichever they prefer.
2. Wait for all consumers to update to use `amountMinor`.
3. **v3:** mark `amount` deprecated, then in **v4** remove it (with a default to keep BACKWARD-compatibility).

Three releases minimum. In practice, we rarely rename — we add the new field and live with both for a long time.

### 8.3 ❌ Changing a field's type — never silently

`amount: integer` → `amount: string` would silently break readers and is forbidden. If the value really must become a string, give it a new name (`amountString`) and migrate as in §8.2.

### 8.4 ✅ Tightening validation — BACKWARD-compatible, sometimes

Tightening `maxLength` on `description` from 140 → 100 is BACKWARD-compatible for *consumers* (a 100-char string is still a valid 140-char string) but is a **producer-side break**. We treat such changes the same as field removals — multi-step rollout.

---

## 9. Tests

See `transaction-service.spec.md` §5.0 for the Testing principles — **Testcontainers spins up a real Kafka and a real Apicurio**; no `EmbeddedKafkaBroker`, no in-memory registry.

| Test | Asserts |
|---|---|
| Schema registration | Every `events/schemas/*.json` registers against a fresh Apicurio container under BACKWARD compatibility |
| Backward-compatibility CI gate | A modified schema is checked against the prior registered version; incompatible changes fail the test |
| Envelope conformance | A sample event from each topic validates against `_envelope.v1.json` + its payload schema |
| Producer wire-config audit | Reflective test: every `KafkaTemplate` bean is configured with `acks=all` + `enable.idempotence=true`. Mis-configured producer fails the test. |
| Consumer wire-config audit | Reflective test: every `@KafkaListener` is in a factory with `enable.auto.commit=false` and uses `Acknowledgment` (manual commit). |
| Inbox dedupe under duplicate delivery | Send the same event twice; assert handler is invoked once, `events_deduped_total` increments once, offset commits. |
| Retry topic round-trip | Throw a transient exception from a handler; assert message lands on `.retry` with a `x-delay-until` header; after delay, assert main consumer re-processes successfully. |
| DLT on poison | Throw a non-retryable exception; assert message lands on `.DLT` after the configured retry count; assert metric `kafka_dlt_messages_total` increments. |
| Cross-region header preservation (documented test only) | Verify `traceparent` header survives MirrorMaker — out of scope to run for the test submission. |

---

## 10. Operational concerns

### 10.1 Metrics emitted

| Metric | Type | Tags |
|---|---|---|
| `kafka_producer_record_send_total` | counter | `topic`, `outcome` |
| `kafka_producer_record_send_seconds` | histogram | `topic` |
| `kafka_consumer_records_consumed_total` | counter | `topic`, `consumer_group` |
| `kafka_consumer_lag` | gauge | `topic`, `partition`, `consumer_group` |
| `kafka_consumer_processing_seconds` | histogram | `topic`, `consumer_group` |
| `events_deduped_total` | counter | `topic`, `consumer_group` |
| `kafka_dlt_messages_total` | counter | `topic` |
| `schema_registry_failures_total` | counter | `subject`, `outcome` |

### 10.2 Alerts

- `kafka_consumer_lag{topic="transactions.transfer.completed"} > 50_000` for 5 min → page
- `kafka_dlt_messages_total{topic="transactions.transfer.*"} > 0` → page immediately
- `outbox_pending_count > 10_000` for 5 min → page (events not flowing)
- `schema_registry_failures_total > 0` over 5 min → page (build artifact or registry config wrong)

### 10.3 What's safe to replay

| Topic | Safe to replay from offset 0? |
|---|---|
| `transactions.transfer.completed` | ✅ Yes — designed for it. New consumers replay full history. |
| `transactions.transfer.reversed` | ✅ Yes. |
| `transactions.transfer.failed` | ✅ Yes (within retention). |
| `accounts.account.opened` | ✅ Yes (within retention). |
| `users.user.registered` | ✅ Yes (within retention) — but does not contain enough to re-send emails. |
| `.retry`, `.DLT` | ❌ No — these are operational topics, not the event log. |

---

## 11. Open questions

| # | Question | Default |
|---|---|---|
| 11.1 | Confluent JSON Schema converter vs raw `JsonSerializer`? | **Raw JsonSerializer.** Confluent's converter adds magic bytes (the schema-id prefix) which complicates non-Confluent consumers. Plain JSON + envelope-carried `eventType` is simpler. |
| 11.2 | One Schema Registry instance shared across all environments, or per-environment? | **Per-environment.** A dev producer publishing a broken schema must never poison the prod registry. |
| 11.3 | Topic naming with dots vs dashes? | **Dots between hierarchy levels, dashes within each level.** `transactions.transfer.completed` (3 levels). Aligns with Confluent's published examples. |
| 11.4 | Should we encrypt event payloads at rest? | **No** — events don't carry PII or PAN by policy (§7.1 note). Kafka cluster's transport (TLS) and disk encryption (cloud provider) are sufficient. |

---

## 12. Acceptance criteria

- [ ] `events/schemas/_envelope.v1.json` exists and validates each sample event in §7
- [ ] Each payload schema in §7 exists at `events/schemas/<topic>-value.v1.json`
- [ ] CI registers each schema in a Testcontainers Apicurio and asserts BACKWARD compatibility
- [ ] Producer wire-config audit test passes for every service that publishes
- [ ] Consumer wire-config audit test passes for every `@KafkaListener` in the codebase
- [ ] Inbox dedupe integration test (§9 row 6) passes
- [ ] Retry + DLT integration tests pass
- [ ] Metrics in §10.1 are visible in a preloaded Grafana dashboard
- [ ] No `EmbeddedKafkaBroker` exists in test code (ArchUnit / grep gate)
