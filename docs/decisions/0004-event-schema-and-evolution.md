# ADR-0004 — Event schema and evolution

- **Status:** Accepted
- **Date:** 2026-05-28
- **Deciders:** CTO candidate response
- **Related:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [ADR-0002](0002-idempotency-and-exactly-once.md)

## Context

Kafka becomes the durable, multi-consumer integration bus of the platform. Every event we publish today will be consumed by some service we haven't designed yet. Two things will inevitably happen:

1. **Producers will evolve.** New fields, deprecated fields, restructured payloads.
2. **Consumers will lag behind.** A consumer pinned to v1 will still be running when the producer ships v3.

If we don't decide a schema strategy *before* the first event is published, we will pay for it forever — events in the log are immutable, so a poor v1 contract is permanent. This ADR fixes the rules now.

## Decision

| Concern | Choice |
|---|---|
| Serialisation | **JSON** with a **registered JSON Schema** in the Schema Registry |
| Schema registry | **Apicurio Registry** (open-source, OSS-friendly licence) |
| Compatibility mode | **BACKWARD** (new schema can read old data — readers upgrade first) |
| Envelope | Fixed outer envelope wrapping the event-specific payload |
| Versioning | Schema version registered against a subject per topic; `eventVersion` in envelope for human readability |
| Event identity | UUID `eventId` in every envelope; used by inbox-pattern dedupe |
| Naming | Events named in past tense, `<Aggregate><PastTenseVerb>Event` — e.g. `TransactionCompletedEvent` |
| Topic naming | `<bounded-context>.<aggregate>.<event>` — e.g. `transactions.transfer.completed` |

## Why JSON + JSON Schema (not Avro / Protobuf)

The default FinTech industry choice is **Avro** because of compact binary encoding and tight registry integration with Confluent. We deliberately chose JSON for this submission:

- **Operability and debuggability.** Engineers can `kafka-console-consumer.sh` and read a payload. With Avro you need the schema bytes to deserialise — a real source of friction during incidents.
- **Spring Boot ergonomics.** Jackson is already in the stack; no extra codec dependencies.
- **Performance is not the constraint at our scale.** At 1 667 tx/s with payloads ~500 bytes each, JSON ↔ Avro doesn't move the needle.
- **Schema enforcement is what matters, not encoding.** JSON Schema in Apicurio gives us the same compatibility-checking guarantees Avro does.

We will revisit if any of these is true:
- We exceed ~50 k events/s on a single topic, *and* network/CPU profile shows serialisation as the dominant cost.
- A partner integration requires Avro for their tooling.

The path to migrate is well-trodden: dual-publish under a new topic in Avro, migrate consumers, retire the old topic.

## Why Apicurio (not Confluent Schema Registry)

- **Licence.** Apicurio is Apache 2.0; Confluent Schema Registry is under the Confluent Community Licence which restricts SaaS resale.
- **API-compatible.** Apicurio implements the Confluent Schema Registry REST API, so Spring Kafka clients work unchanged.
- **Same compatibility guarantees** (`BACKWARD`, `FORWARD`, `FULL`, `NONE`).
- **Works offline / in docker-compose** without a Confluent licence.

## Why BACKWARD compatibility

Compatibility modes define *which side has to change first* during a schema upgrade.

| Mode | Meaning | Who upgrades first |
|---|---|---|
| `BACKWARD` | New schema can read messages written with the **previous** schema | Consumers upgrade first, then producers |
| `FORWARD` | Old schema can read messages written with the **new** schema | Producers upgrade first, then consumers |
| `FULL` | Both directions | No order required, but rules are strictest |
| `NONE` | No compatibility checking | Any change accepted; we don't use this |

We chose **BACKWARD** because:
- A new consumer subscribing today should be able to **replay history from offset 0**. With FORWARD, an old log entry's schema may be unreadable by the new code.
- In our deployment topology, the producer (Transaction Service) is the highest-risk to roll back. Consumers can stay one step ahead.
- BACKWARD enforces the discipline that all new fields must be **optional with sensible defaults** and you can only **remove fields that have defaults** — both reasonable contracts.

## Envelope

Every event on every topic carries the same outer envelope:

```json
{
  "eventId":        "01HZ...",         // ULID / UUID — unique per event
  "eventType":      "TransactionCompletedEvent",
  "eventVersion":   1,                 // human-readable; not the only source of truth
  "occurredAt":     "2026-05-28T10:00:00Z",
  "producedAt":     "2026-05-28T10:00:00.123Z",
  "producer":       "transaction-service@1.4.2",
  "correlationId":  "...",             // matches the originating HTTP request
  "causationId":    "...",             // event/command that caused this event (saga tracing)
  "traceparent":    "00-...-...-01",   // W3C trace context for distributed tracing
  "data": { ... }                      // event-specific payload — schema-evolved separately
}
```

- **Envelope is stable**, payload (`data`) evolves under the compatibility rules.
- Tracing headers (`traceparent`) also live in **Kafka headers** for tooling, duplicated in the body for replay-time access.
- `causationId` lets us reconstruct the causal chain of a transaction across services.

## Schema registration & CI gate

- Each topic has a *subject* in the registry: `transactions.transfer.completed-value`.
- Schemas are checked into the repo under `events/schemas/` and registered as part of the CI pipeline.
- A CI step rejects any PR that:
  - changes a schema in a way that violates BACKWARD compatibility,
  - introduces a producer publishing to a topic that has no registered schema,
  - publishes data with a field that isn't in the schema (strict mode).

Result: it is **impossible to merge** a producer change that would break a downstream consumer.

## Event taxonomy at MVP

| Topic | Event type | Producer | Initial consumers |
|---|---|---|---|
| `transactions.transfer.completed` | `TransactionCompletedEvent` | Transaction Service | Notification, Fraud Detection, Accounting projector |
| `transactions.transfer.failed` | `TransactionFailedEvent` | Transaction Service | Notification, ops dashboards |
| `transactions.transfer.reversed` | `TransactionReversedEvent` | Transaction Service | Notification, Accounting projector |
| `accounts.account.opened` | `AccountOpenedEvent` | Account Service | KYC follow-up, Accounting projector |
| `accounts.account.frozen` | `AccountFrozenEvent` | Account Service | Notification, fraud history |

Reserved: `*.DLT` (dead-letter), `*.retry` (delayed retry).

## Payload example — `TransactionCompletedEvent` v1

```json
{
  "data": {
    "transactionId":      "TX-01HZ...",
    "idempotencyKey":     "uuid",
    "sourceAccount":      "ACC001",
    "destinationAccount": "ACC002",
    "amount":             100,
    "currency":           "USD",
    "completedAt":        "2026-05-28T10:00:00Z"
  }
}
```

Rules we follow in payload design:
- **Monetary amounts as integers** in minor units. Never floats.
- **No PII in events** that don't strictly need it. The notification consumer can look up the user via `userId`; we don't bake names into the event.
- **No internal IDs that reveal implementation** (e.g. Mongo ObjectIds). Use the same canonical IDs surfaced in the REST API.
- **Times are RFC 3339 UTC** strings with explicit `Z`.

## Schema-evolution worked examples

### Adding an optional field — ✅ BACKWARD-compatible

v2 adds `description`. Old consumers ignore the field; new consumers see it.

```jsonc
// v2
{
  "data": {
    "transactionId": "...",
    "amount": 100,
    "currency": "USD",
    "description": "Coffee — optional, default null"
  }
}
```

### Renaming a field — ❌ Breaking, do it via dual-write

Rename `amount` → `amountMinor`. Two-step rollout:
1. v2 publishes both `amount` and `amountMinor`; consumers read `amountMinor` if present, else `amount`.
2. After all consumers upgrade, v3 drops `amount`.

### Changing a field's type — ❌ Breaking, never silently

`amount` from integer to string would silently break readers. Don't. If you must, give it a new name (`amountString`) and migrate as above.

### Removing a field — ✅ if the field has a default; otherwise dual-write

## Time, schema, and replay

A consumer added in 2027 should be able to subscribe to `transactions.transfer.completed` from offset 0 and process every event ever written. The combination of registry + BACKWARD + schemas-in-repo guarantees this.

**Retention policy:** key topics have **infinite retention** (set `retention.ms = -1`) — they *are* the event log. Operational topics (`*.retry`, `*.DLT`) keep 30 days.

**Compaction:** account-state topics (e.g. `accounts.account.snapshot`) use log compaction keyed by `accountId` so the topic holds the latest state for each key forever.

## Considered alternatives

### Alt — Avro + Confluent Schema Registry
- ✅ Industry-standard; tighter integration with the broader Kafka ecosystem; binary encoding is more compact.
- ❌ Operational opacity (need schema to read), Confluent licence concerns for our open-source compose deliverable.
- Reserved as a future migration if scale or partner integration demands it.

### Alt — Protobuf
- ✅ Great for RPC contracts; small wire format.
- ❌ More effort to standardise across our Java + (future) Python services; tooling story is less mature in Spring Boot than Avro or JSON Schema.

### Alt — No registry; producers publish JSON freely
- ❌ The state we are explicitly avoiding. A schema-less log becomes archaeologically untranslatable within 6 months.

### Alt — Versioned topics (`transactions.transfer.completed.v2`)
- ❌ Forces every consumer to rebuild on every change. Multiplies operational surface. The registry approach achieves the same goal with one topic.

## Consequences

- A new top-level directory `events/schemas/` holds JSON Schemas for every event the platform publishes.
- The CI pipeline runs the registry compatibility check on every PR touching `events/schemas/`.
- The TransactionService implementation publishes events via a small `EventEnvelope` builder that enforces the envelope contract.
- Operations team adds a Grafana panel for "schema rejections per consumer" — sudden spikes mean someone shipped an incompatible producer past CI.
- Replayability becomes a first-class testing concern: an integration test consumes the topic from offset 0 against the latest consumer code and asserts every event deserialises cleanly.

## References

- Martin Kleppmann, [*Schema evolution in Avro, Protocol Buffers and Thrift*](https://martin.kleppmann.com/2012/12/05/schema-evolution-in-avro-protocol-buffers-thrift.html)
- Confluent, [*Schema Registry compatibility modes*](https://docs.confluent.io/platform/current/schema-registry/avro.html)
- Apicurio Registry documentation
- Greg Young, [*Versioning in an Event Sourced System*](https://leanpub.com/esversioning)
