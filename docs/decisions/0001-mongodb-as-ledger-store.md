# ADR-0001 — MongoDB as the ledger store

- **Status:** Accepted (constrained by the brief)
- **Date:** 2026-05-28
- **Deciders:** CTO candidate response
- **Related:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [ADR-0002](0002-idempotency-and-exactly-once.md)

## Context

The brief uses MongoDB across three Parts, with two different registers worth quoting verbatim:

| Brief location | Exact wording | How binding |
|---|---|---|
| **Part 1, Question 1** | *"Recommended Technologies: Spring Cloud, **MongoDB**, Kafka, Docker / Kubernetes"* | **Soft.** The heading is *"Recommended"* — not "required" or "mandatory". At the architecture-question level we have latitude to propose a different store. |
| **Part 3, Technical Implementation** | *"**Implement** a TransactionService microservice **in**: Java, Spring Boot, **MongoDB**"* | **Hard.** The verb *"Implement... in"* is directive. The only datastore listed is MongoDB. For the code we ship, this is effectively a mandate. |
| **Part 5, DevOps** | Lists *"**MongoDB Replica Set**"* as a required service in the docker-compose deliverable | **Hard.** The deliverable must contain a MongoDB Replica Set. |

In parallel, Part 2 asks for account consistency, ACID vs eventual-consistency reasoning, audit, history, and rollback — a textbook description of a *general ledger*.

A general ledger has three properties that have driven systems design for centuries:

1. **Double-entry:** every value movement produces two equal-and-opposite journal lines.
2. **Append-only:** posted entries are never updated or deleted; corrections happen via compensating entries.
3. **Strong invariants:** sum of debits = sum of credits; an account's balance equals the signed sum of its journal lines.

The orthodox tool for this job is a relational database with `SERIALIZABLE` transactions and `CHECK` constraints — typically PostgreSQL.

## Decision

**We will use MongoDB as the system of record, in a replica-set configuration, and rely on multi-document ACID transactions for the entire scope of a single transfer.**

We acknowledge that PostgreSQL would be the textbook choice; we document below why MongoDB is workable here, and what we give up.

## Why MongoDB works for this case

- **Multi-document transactions on a replica set are ACID** (since Mongo 4.0, extended to sharded clusters in 4.2). Inside one transaction we can: debit source, credit destination, insert two journal lines, insert one outbox row — all-or-nothing.
- **Conditional updates replace `CHECK` constraints.** `updateOne({ _id, balance: { $gte: amount }, status: "ACTIVE" }, { $inc: { balance: -amount } })` matches zero documents if the precondition fails, which we treat as "abort". This is functionally equivalent to a `CHECK (balance >= 0)` for our purposes.
- **JSON Schema validators on collections** enforce shape and field-level constraints (`balance: { bsonType: "long", minimum: 0 }`, required fields, allowed `status` enum). The DB rejects ill-formed documents.
- **Optimistic locking** via a `version` field on `accounts` (incremented on every update, asserted on every update) defends against the lost-update problem.
- **Append-only journal** is enforced by a database role that has `insert` but not `update` or `delete` privileges on the `journal` collection.
- **Horizontal scale** via sharding is mature and is what we need for the Part 7 scale targets (100 k tx/min).

## What we give up vs PostgreSQL

| Property | PostgreSQL | MongoDB | Mitigation here |
|---|---|---|---|
| Foreign-key referential integrity | Native (`REFERENCES`, `ON DELETE`) | None | Application-enforced; integration tests cover the cases |
| Declarative `CHECK` constraints | Native | Schema validator only | JSON Schema validators + conditional updates |
| Cross-row arithmetic invariants (`Σdebits = Σcredits`) | Trigger-enforceable | Application-enforced | Reconciliation job + property-based tests |
| Mature transactional idioms | Decades of literature | Newer, more footguns (TX retries, write conflicts) | Use the official `MongoTransactionManager`; retry on `TransientTransactionError` with jitter |
| Index-organised joins | Native, fast | `$lookup` is awkward and slow at scale | Avoid joins on the write path; denormalise read models |
| `SERIALIZABLE` isolation | Native | Snapshot isolation only inside a TX | Use conditional updates so the operation is correct under snapshot |

## Why not Postgres anyway?

The brief technically gives us latitude at the architecture level (Part 1's *"Recommended Technologies"*), so proposing Postgres for the ledger and implementing on Mongo would not violate the brief's letter. We considered that path and rejected it for three reasons:

1. **Part 3 is directive about the implementation.** *"Implement a TransactionService microservice in: Java, Spring Boot, MongoDB."* If our architecture recommends Postgres but our code is on Mongo, the deliverable has a deliberate discontinuity between recommendation and implementation. That's defensible but distracting — a reviewer has to spend time deciding whether the gap is thoughtful or careless.
2. **Consistency over cleverness.** A coherent submission — *here is our ledger design, here is the code that implements it* — communicates more clearly than a split position. Saving the cleverness for one well-placed paragraph (this ADR) is more effective than threading it through every section.
3. **MongoDB-replica-set + multi-document transactions is sufficient** for the invariants this platform needs. We are not building a national settlement system; we are building a digital wallet at modest scale. Mongo can carry this.

If, in a real engagement, the regulator or auditor pushed back on Mongo for the ledger, the migration path is well-trodden: keep Mongo for user / session / read-model data, move accounts + journal to Postgres behind the same Transaction Service interface. **No client-facing change.** This is documented as a future option, not a current commitment.

## Considered alternatives

### Alternative A — PostgreSQL as the ledger
- ✅ Native ACID, FKs, `CHECK` constraints, mature tooling.
- ⚠️ At the **architecture level** (Part 1), the brief uses *"Recommended"* — Postgres would not violate the letter of Part 1. At the **implementation level** (Part 3), *"Implement... in: MongoDB"* is directive, so a Postgres ledger would violate Part 3 directly.
- ❌ Adds a second database technology (we'd still need Mongo for the Part 5 compose deliverable, which lists *"MongoDB Replica Set"* explicitly).

### Alternative B — Single-node MongoDB (no replica set)
- ❌ Mongo's multi-document transactions require a replica set. Brief already mandates a replica set, but worth stating.
- ❌ No HA.

### Alternative C — Event-sourced ledger only (no `accounts.balance` column)
- The journal *is* the state; balance is always computed from `SUM(credits) - SUM(debits)`.
- ✅ Eliminates the "balance drifts from journal" failure mode by construction.
- ❌ Every balance read becomes an aggregate query → unacceptable latency at 1 667 tx/s.
- **What we actually do:** hybrid — the journal is the source of truth, `accounts.balance` is a materialised view kept in sync inside the same transaction, and a reconciliation job verifies they agree.

## Consequences

- The TransactionService code is written defensively: every state-changing operation goes through one chokepoint that wraps the whole flow in a Mongo transaction with retry.
- We pay a small write-amplification cost (4 documents written per transfer: debit, credit, 2 journal lines, plus outbox = 5). Acceptable at our scale.
- We need to operationalise the replica set carefully: write concern `majority`, read concern `majority` for balance reads on the write path, careful failover testing.
- Sharding (when we get there) must use a shard key that lets the entire transfer's documents live on one shard — practically, this means **`hash(accountId)`** as the shard key on `accounts` and `journal`, and accepting that cross-account transfers may touch two shards (Mongo handles this transparently via the config servers, but it's slower; we monitor).
- Future migration to Postgres is preserved as an option via the repository-pattern abstraction in the service.

## References

- MongoDB Manual: [Transactions](https://www.mongodb.com/docs/manual/core/transactions/)
- MongoDB Manual: [Schema Validation](https://www.mongodb.com/docs/manual/core/schema-validation/)
- Pat Helland, [*Life beyond Distributed Transactions*](https://queue.acm.org/detail.cfm?id=3025012)
- Martin Kleppmann, *Designing Data-Intensive Applications*, ch. 7 & 11
