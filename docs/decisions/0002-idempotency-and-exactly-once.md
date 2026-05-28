# ADR-0002 — Idempotency and exactly-once semantics

- **Status:** Accepted
- **Date:** 2026-05-28
- **Deciders:** CTO candidate response
- **Related:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [ADR-0001](0001-mongodb-as-ledger-store.md), [ADR-0004](0004-event-schema-and-evolution.md)

## Context

A payment platform must produce **at-most-one** effect per logical user intent — anything else is a regulatory and trust failure. The Part 10 *double-debit* scenario is exactly the failure this ADR prevents.

There are three failure modes we have to design against:

1. **Client retry.** Networks drop responses; mobile clients reissue. If we are not careful, the same intent becomes two transfers.
2. **Dual write.** Naïvely, a service does `db.commit()` and then `kafka.send()`. Either side can crash between the two, so we get either lost events or phantom transfers. **There is no in-app code that fixes this — only an architecture pattern does.**
3. **Re-delivery.** Kafka is fundamentally at-least-once; a consumer can see the same event more than once after a crash or rebalance.

True end-to-end "exactly once" is a marketing term. What we can actually achieve is **at-least-once delivery + idempotent processing at every step**, which is observationally equivalent to exactly-once for the outside world.

## Decision

Three patterns, applied uniformly:

1. **Idempotency-Key on every state-changing API call**, enforced by a unique index in the database.
2. **Transactional outbox** for publishing domain events. No service ever writes to the DB and Kafka in the same logical operation outside of an outbox.
3. **Inbox table** on every event consumer that processes events with side effects, keyed by `eventId`.

## 1. Idempotency-Key on writes

### Client contract

Every state-changing endpoint (`POST /transactions/transfer`, account creation, freeze, …) **requires** an `Idempotency-Key` HTTP header carrying a client-generated UUID v4. Requests without the header are rejected (`400 Bad Request`).

### Server enforcement

- A unique index on `transactions.idempotencyKey` (partial index, scoped to a 24 h sliding window via a TTL on a separate "fingerprint" collection if storage matters).
- Flow:
  1. Client sends `POST /transfer` with `Idempotency-Key: K1` and payload `P1`.
  2. Server starts the Mongo transaction. The transactional insert into `transactions` carries `idempotencyKey: K1`.
  3. If the insert succeeds, the transfer proceeds and commits. Response 201.
  4. If the insert fails with `DuplicateKey`, we abort and look up the existing transaction.
     - If its stored request payload **matches** `P1` → return the *original* response (the original 201 body, with the same `transactionId`). Status code becomes 200 to distinguish a replay, or 201 again if we want byte-for-byte equality — both are acceptable; we choose **200 OK** for clarity.
     - If the stored payload **differs** from `P1` → return `409 Conflict` with a Problem Detail explaining "idempotency key reuse with different payload". This catches client bugs.
- We **never** do the read-then-write pattern (`SELECT … if not exists → INSERT`). That's racy. The unique index is the arbiter.

### Why the unique index, not a Redis lock?

- A lock is a separate component that can fail open (lose the lock under partition) or fail closed (block all requests when Redis is sick).
- The unique index lives in the same DB as the transaction; if the DB is down, neither succeeds nor falsely accepts a duplicate. Failure modes are correlated, which is what we want.

### Edge cases

- **Key collision across users:** prefixing the stored idempotency key with the authenticated `userId` (`{userId}:{clientKey}`) prevents key collisions and stops one user replaying another's request.
- **Key reuse across endpoints:** scope the idempotency record to `(userId, endpoint, clientKey)`. The same client key on `/transfer` and `/account/freeze` is *not* the same intent.
- **Long-tail retries:** retention of 24 h is the standard industry choice (Stripe, Square). After that, the same key is fair game and the client should generate a new one.

## 2. Transactional outbox for event publishing

### The dual-write problem (why we don't `kafkaTemplate.send()` inline)

```java
// BROKEN — do not do this
@Transactional
public void transfer(...) {
   accountRepo.debit(src, amount);
   accountRepo.credit(dst, amount);
   journalRepo.insert(...);
   transactionRepo.insert(...);
   kafkaTemplate.send("tx.completed", event);   // <-- ❌
}
```

Failure modes:
- DB commits, JVM crashes before Kafka ack → **lost event**. Notification + fraud never run.
- Kafka commits, DB rollback fires for some reason → **phantom event**. Downstream "transfer succeeded" for a transfer that didn't.
- Both succeed but `kafkaTemplate.send()` returns success after a network blip causes a retry → **duplicate event**.

`@Transactional` does **not** cover the Kafka send.

### The fix — write the event to the same DB transaction

```java
@Transactional
public void transfer(...) {
   accountRepo.debit(src, amount);
   accountRepo.credit(dst, amount);
   journalRepo.insert(...);
   transactionRepo.insert(...);
   outboxRepo.insert(new OutboxRecord("tx.completed", event));  // ✅ same TX
}
```

A separate process publishes from `outbox` to Kafka. Two implementations:

#### Option A — Polling publisher (chosen for this submission)

```java
@Scheduled(fixedDelay = 200)
public void publishPending() {
   var batch = outboxRepo.findPending(BATCH_SIZE);
   for (var rec : batch) {
      try {
         kafkaTemplate.send(rec.topic(), rec.aggregateId(), rec.payload()).get();
         outboxRepo.markSent(rec.id());
      } catch (Exception e) {
         log.warn("publish failed, will retry", e);
         // leave row PENDING; retried next tick
      }
   }
}
```

- Pros: zero extra infrastructure; trivial to test; well within the test scope.
- Cons: small latency floor (≈ tick interval); the publisher is a service-local component.

#### Option B — CDC publisher (production-grade, documented but not built)

- Debezium watches the Mongo oplog and streams `outbox` inserts to Kafka.
- Pros: lower latency, decoupled from app process lifecycle, scales independently.
- Cons: more moving parts (Kafka Connect cluster), oplog access, schema-registry coupling.

We use **A** in the deliverable and document **B** as the path to scale.

### Failure semantics of the outbox

- **DB commit + crash before Kafka send** → row stays `PENDING` → next tick publishes it. Event delivered.
- **Kafka send succeeds, crash before `markSent`** → next tick republishes. **Duplicate in Kafka.** Consumers must dedupe — see §3.
- **Kafka cluster down** → outbox backs up; alert fires on `outbox_pending_count > N`; app keeps committing transfers (graceful degradation). When Kafka returns, the backlog flushes.
- **Outbox row corrupted / fails schema** → moved to `outbox_dlq`; operator alert.

### Outbox schema (recap)

```jsonc
{
  "_id": "OB-...",
  "aggregateId": "TX-...",         // partition key for Kafka
  "topic": "tx.completed",
  "eventId": "uuid",                // travels in event envelope; consumers dedupe on this
  "payload": { ... },               // full event payload (Avro-encoded or JSON)
  "status": "PENDING",
  "createdAt": "...",
  "sentAt": null,
  "attempts": 0
}
```

## 3. Inbox table on consumers

Because the producer is at-least-once, consumers **must** be idempotent on `eventId`.

### Pattern

Each consumer keeps a `processed_events` (inbox) collection with `eventId` as `_id` (unique).

```java
public void onEvent(ConsumerRecord<String, Event> rec, Acknowledgment ack) {
   try {
      inboxRepo.insert(new ProcessedEvent(rec.value().eventId(), rec.topic(), Instant.now()));
   } catch (DuplicateKeyException e) {
      ack.acknowledge();      // already processed; skip silently
      meter.counter("events.deduped").increment();
      return;
   }
   handle(rec.value());        // do the work
   ack.acknowledge();           // commit Kafka offset only on success
}
```

- **Manual offset commit** — we never use `enable.auto.commit=true`. Auto-commit can lose messages on rebalance.
- **Insert-first dedupe** — if the insert succeeds, we own the work; on retry the duplicate-key skip is the dedupe.
- **Crash between `inboxRepo.insert` and `handle()`** — on restart we'd skip the message because the inbox row exists, but the handler never ran. Two ways to handle this:
  - For *idempotent handlers* (notification send via Idempotency-Key to the email provider, fraud score write keyed by `eventId`): acceptable; the worst case is one undelivered notification, which is recoverable.
  - For *non-idempotent handlers* that produce side effects with cost: wrap the insert + handler in a single DB transaction where possible, or use the [transactional outbox **on the consumer side**] so the handler's writes and the inbox insert commit together.

### Retry + DLT

- Failed processing (transient): republish to `tx.completed.retry` with a delay header; a separate consumer reads it after delay.
- Exhausted retries: publish to `tx.completed.DLT`; operator alert; manual triage.
- **Never block the main consumer** by retrying in-place — that head-of-line-blocks the partition and rapidly creates lag.

## What this combination guarantees

| Property | Guarantee |
|---|---|
| Same client retries with same key | At most one transfer recorded |
| Same client retries with different payload, same key | 409 — caught client bug |
| Server crash mid-transaction | Either fully committed or fully rolled back (Mongo TX) |
| Server crash after commit, before publish | Eventually published (outbox tail) |
| Kafka delivers an event twice | Consumer dedupes (inbox) |
| Two consumers in the same group | Kafka delivers to one of them; the other sees a different partition |

## Considered alternatives

### Alt — Kafka transactions + `exactly_once_v2`
- Kafka's own transactional producer + read-process-write transactions provide exactly-once within Kafka.
- ❌ Doesn't solve the **DB ↔ Kafka** dual-write problem (the very thing the outbox solves). We'd still need a pattern there.
- We can layer Kafka transactions onto the consumer-side read-process-write later if needed; it's not the foundational fix.

### Alt — XA / two-phase commit between Mongo and Kafka
- ❌ Neither Mongo nor Kafka offers production-grade XA. And even where XA exists, it ruins availability under partition.

### Alt — Read-then-write idempotency check in app code
- ❌ Race condition: two concurrent requests both see "no existing record" and both proceed. The unique index is mandatory.

## Consequences

- Every state-changing endpoint has an `Idempotency-Key` test in the integration suite (concurrent same-key, same-key different-payload, replay after expiry).
- The outbox is part of the data model and visible in compose / Helm / metrics (`outbox_pending_count` gauge).
- Consumer code has a tiny amount of ceremony around the inbox table — acceptable cost.
- Operators get clear alerts: outbox depth, consumer lag, DLT volume.
- The Part 10 *double-debit* scenario is structurally prevented at the unique-index level, with reconciliation as the defence-in-depth layer.

## References

- Stripe, [*Designing robust and predictable APIs with idempotency*](https://stripe.com/blog/idempotency)
- Chris Richardson, *Microservices Patterns*, ch. 3 (Transactional Outbox), ch. 8 (Sagas)
- Confluent, [*Exactly-once semantics in Kafka*](https://www.confluent.io/blog/exactly-once-semantics-are-possible-heres-how-apache-kafka-does-it/)
- Gunnar Morling, [*Reliable microservices data exchange with the outbox pattern*](https://debezium.io/blog/2019/02/19/reliable-microservices-data-exchange-with-the-outbox-pattern/)
