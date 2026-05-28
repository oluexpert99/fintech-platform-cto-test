# ADR-0005 — Multi-region & high availability for 99.99%

- **Status:** Accepted
- **Date:** 2026-05-28
- **Deciders:** CTO candidate response
- **Related:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [ADR-0001](0001-mongodb-as-ledger-store.md)

## Context

The brief targets **99.99 % availability** — approximately **52 minutes of downtime per year**, or about **4.3 minutes per month**. That is **not** achievable in a single Availability Zone, nor in a single region without a tested failover. Past 99.95 %, every minute of unplanned downtime requires structural choices, not just operational diligence.

This ADR lays out the topology we'd deploy for production. The local docker-compose deliverable for this test trivially flattens to single-node; the production topology is documented here so the architecture answer is concrete.

## Decision

| Concern | Choice |
|---|---|
| Primary topology | **Active-Active across two regions**, **Active-Passive within each region's AZs for the database tier** |
| Compute | **Multi-AZ Kubernetes** in each region; ≥ 3 AZs per region |
| Database | **MongoDB replica set spanning 3 AZs** in the primary region + **delayed secondary in the DR region** for fast failover; sharded once we cross ~10 k tx/min |
| Event bus | **Kafka cluster per region**, replicated with **MirrorMaker 2** (or Confluent Replicator) on key topics |
| Object storage | **Cross-region replicated** S3-compatible bucket for backups, journal archives, large attachments |
| Identity | **Keycloak active-active** across regions, fronted by a Keycloak replication tier (PostgreSQL streaming replication or managed equivalent) |
| Global traffic management | **DNS-based + anycast** routing (Route53 latency + health checks, or equivalent); regional **read traffic** routed to nearest healthy region; **write traffic** to the active write region with sub-minute failover |
| Backups | Hourly snapshots + PITR (point-in-time-recovery) to 5-minute granularity; quarterly **restore drills** |
| Targets | **RPO ≤ 1 minute** (data loss on failover), **RTO ≤ 5 minutes** (time to recover) |

## Why active-active across regions (and not three regions)

- **Two regions** is the smallest topology that lets us tolerate one full regional outage and still serve 100 % of users.
- **Three regions** is more resilient but adds 50 % infrastructure cost and complicates Kafka topology (cross-region quorum in three regions has nasty latency characteristics). For a target of 99.99 %, two regions with rigorous failover is sufficient.
- **Active-active** (vs active-passive across regions) means the passive region is **continuously exercised** — capacity tested, certificates valid, deployments current. Active-passive regions silently rot until the day you need them.

## Why active-passive at the database tier (with DR secondary)

- Mongo replica sets are **single-primary**. Multi-primary "active-active" writes against the same dataset is not how Mongo works (and the trade-offs would be the same in Postgres anyway: you'd be back to conflict resolution).
- **Within the primary region:** the replica set spans 3 AZs (1 primary + 2 secondaries with `majority` write concern). Loss of any one AZ does not block writes.
- **Across regions:** a **delayed secondary** (a few seconds behind) lives in the DR region. On regional failure, we promote it. The delay protects against a *logical* corruption that has already been replicated synchronously — a 60-second cushion to detect "all balances suddenly zero" before the DR node is corrupted too.
- **Async cross-region replication** — synchronous cross-region replication ruins write latency. We accept the RPO ≤ 1 min instead.
- **Application writes are routed to the active region**; reads can be served locally in either region from the DR region's secondary for read-only workloads.

## Why Kafka per region + MirrorMaker

- **One global Kafka cluster spanning regions is a known anti-pattern.** Cross-region producer round-trips kill latency; partition leader failover across regions is slow.
- **Per-region clusters** + **MirrorMaker 2** replicates topics asynchronously to the other region. Consumers in either region can read the events.
- **Consumer offsets are replicated** by MM2 so a regional failover doesn't lose consumer position.
- **Producers always write to the local region's Kafka.** If the local Kafka is unhealthy, the producer's transactional outbox simply backs up — no transactions are lost, they just wait.

## Component-by-component HA design

### Stateless services (Gateway, Auth, Transaction, Account, Notification, Fraud)

- ≥ 3 replicas per service per region.
- **Pod anti-affinity** across AZs (`topologyKey: topology.kubernetes.io/zone`) so no AZ holds more than one replica.
- **PodDisruptionBudget** `minAvailable: 2` during voluntary disruptions.
- **HPA** on CPU + custom metrics with a generous over-provisioning factor (we target 50 % steady-state utilisation so a 2× spike doesn't queue).
- **Graceful shutdown:** `preStop` hook drains in-flight requests; readiness probe goes false before liveness; gateway stops sending new traffic.

### MongoDB

- **PSSS-D** (Primary + 2 Secondaries in region + Delayed Secondary in DR + Arbiter for tie-breaking only in the smallest deployments — we avoid arbiters in production because they trade safety for cost).
- `writeConcern: { w: "majority", j: true }` — durability before ack.
- `readConcern: "majority"` on balance reads in the write path; `readConcern: "local"` for analytics.
- **Heartbeat and failover** tuned: election timeout 10 s (default 10 s), catchUp timeout 60 s. Operational target: primary election completes within 30 s.
- **Backups:** hourly Atlas-style continuous snapshots OR `mongodump` snapshots; PITR via oplog replay. Restore is tested **quarterly** on a separate cluster.
- **Sharding (at ~10 k tx/min):** `hash(accountId)` shard key; balancer policy keeps shards balanced overnight, not during peak.

### Kafka

- 3 brokers per region (minimum); `replication.factor=3`, `min.insync.replicas=2`, `acks=all` on producers.
- Topic config for `transactions.*`: `retention.ms = -1` (infinite) — these *are* the event log.
- KRaft mode (no ZooKeeper) for operational simplicity.
- Cross-region: MirrorMaker 2 with `replication.factor=3` on the target side.

### Keycloak

- Two replicas per region behind a service. Backed by a Postgres cluster (Patroni / managed) with streaming replication.
- Realm config and signing keys are **identical** across regions so a JWT issued in region A is valid in region B.

### Caches (Redis, etc.) — explicitly *not* sources of truth

- Used only for rate-limit counters and the auth deny-list.
- Loss of cache must not affect correctness — at worst, we re-derive from the DB.

## Failure scenarios — what happens

| Scenario | Detection | Behaviour | RTO |
|---|---|---|---|
| Single pod crash | Liveness probe | Replaced; replicas absorb load | < 30 s |
| One AZ outage in primary region | Pod scheduling fails in that AZ | Other AZs absorb load; Mongo elects new primary in another AZ; gateway autoscales | < 2 min |
| Full primary region outage | Multi-AZ health checks fail | DNS swings to DR region; DR Mongo secondary promoted manually (or auto with a runbook); Keycloak in DR keeps serving; Kafka switches to DR cluster; outbox publishers in DR catch up from MM2-replicated topics | 5–10 min |
| Kafka cluster down in active region | Producer send failures | Outbox depth grows; alerts fire; transactions still commit to Mongo (graceful degradation); on recovery, outbox flushes | Recovery on Kafka return |
| Schema-registry down | Producers cache last schema; fail closed on **new** schema | New deploys blocked until registry recovers; existing traffic unaffected | Operational dependency on rebuild |
| Mongo primary fails | Election | New primary elected in same region; client driver re-routes | < 30 s |
| Logical corruption (e.g. an admin deletes a collection) | Reconciliation alert / monitoring | PITR restore to a few minutes before the corruption; replay events from Kafka if needed | 15–60 min |

## RPO/RTO targets — and the maths behind 99.99 %

To hit 99.99 %:

| Source of downtime | Budget |
|---|---|
| Planned maintenance (rolling deploys, DB upgrades) | 0 — must be zero-downtime |
| Single-region outages | Handled by failover; ≤ 5 min RTO × ≤ 1 event/year = ~5 min/year |
| Bug-induced outages | The hardest to control; mitigated by canary deploys, feature flags, fast rollback |
| Dependency outages (IdP, registry) | Designed to fail closed only on writes that need them; reads degrade gracefully |

The 52-minute annual budget is achievable, but it requires that:
- Every deploy is canary + auto-rollback (≤ 1 min decision latency).
- Every database operation has a rehearsed runbook.
- Every dependency outage has a documented degraded mode.

Anything less and 99.99 % is aspirational, not measured.

## Considered alternatives

### Alt — Single-region, multi-AZ only
- ✅ Simpler, cheaper.
- ❌ A regional outage (rare but not unheard-of for major cloud providers) is an instant SLO violation. Not consistent with 99.99 %.

### Alt — Active-active databases via Mongo Atlas Global Clusters / CockroachDB
- ✅ Truly multi-region writes.
- ❌ Either vendor lock-in (Atlas) or violates the brief's Mongo choice (Cockroach).
- ❌ Multi-region synchronous write commits introduce cross-region latency on every transfer — bad UX.

### Alt — Three or more regions
- ✅ Better than two for catastrophic failure.
- ❌ Cost; Kafka replication topology becomes ugly. Re-evaluate post-1M users.

## Consequences

- **Operations is a first-class part of the platform.** A runbook for regional failover exists, is tested quarterly, and is owned by a named team.
- **Every dependency has a documented degraded mode.** "What happens if Kafka is down" is a question with a one-paragraph answer, not a shrug.
- **Cost approximately doubles** vs single-region. We accept this as the cost of the SLO.
- **Deployment process** is non-negotiably zero-downtime — canary + readiness drain + DB migrations gated.
- **Observability** must distinguish per-region traffic so we can detect that "region A is sick but DNS hasn't swung yet" before users do.
- For the docker-compose deliverable in this test, the topology collapses to single-node — that's the *local* mode. The Helm chart sketch in `k8s/` represents the production topology.

## References

- Google SRE Book, ch. 26 (*Data Integrity*) and ch. 17 (*Testing for Reliability*)
- AWS, [*Disaster Recovery of Workloads on AWS*](https://docs.aws.amazon.com/whitepapers/latest/disaster-recovery-workloads-on-aws/disaster-recovery-workloads-on-aws.html)
- MongoDB, [*Replica Set Deployment Architectures*](https://www.mongodb.com/docs/manual/core/replica-set-architectures/)
- Confluent, [*Multi-region replication with MirrorMaker 2*](https://docs.confluent.io/platform/current/multi-dc-deployments/replicator/index.html)
