# FinTech Payment Platform — CTO Technical Test

> Response to the *"Technical Test — CTO (FinTech Platform Architecture)"* brief.
> A simplified digital payment platform targeting **1 M users**, **10 000 → 100 000 tx/min**, **99.99 % availability**.

| | |
|---|---|
| **Brief** | [`docs/TECHNICAL TEST -CTO - English.docx`](docs/TECHNICAL%20TEST%20-CTO%20-%20English.docx) |
| **Main architecture document** | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| **REST API contract** | [`docs/api.md`](docs/api.md) |
| **Architecture decisions** | [`docs/decisions/`](docs/decisions/) (5 ADRs) |
| **Diagrams** | [`docs/diagrams/`](docs/diagrams/) (system / sequence / event-flow) |

---

## Read this first — recommended reading order

A reviewer landing here can read the response in **20 minutes** by following this order. Each item links to the next thing you'd open.

| # | Open | Why |
|---|---|---|
| 1 | This file (5 min) | Orient yourself: scope, decisions, deliverables status |
| 2 | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) executive summary + table of contents (3 min) | One-screen view of the whole answer |
| 3 | [`docs/diagrams/system-architecture.png`](docs/diagrams/system-architecture.png) (1 min) | Visual platform map. Colour-coded: green = implemented, grey = documented-only |
| 4 | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) Parts 1, 2, 5, 6, 10 (8 min) | The load-bearing sections — architecture, data, the transfer flow, event-driven, the double-debit incident |
| 5 | [`docs/decisions/0001-mongodb-as-ledger-store.md`](docs/decisions/0001-mongodb-as-ledger-store.md) and [`0002-idempotency-and-exactly-once.md`](docs/decisions/0002-idempotency-and-exactly-once.md) (3 min) | The two ADRs where the most consequential calls were made |
| 6 | [`docs/api.md`](docs/api.md) (skim) | The full REST contract, error model, idempotency |

If you only have **5 minutes**: read this file's [Mapping to the brief's 8 Parts](#mapping-to-the-briefs-8-parts) and look at [`docs/diagrams/transfer-sequence.png`](docs/diagrams/transfer-sequence.png).

---

## At a glance

The platform is a set of **bounded-context microservices** on **Spring Boot + Spring Cloud**, persisting to a **MongoDB replica set**, communicating asynchronously through **Apache Kafka**, exposed through an **API Gateway** secured by **Keycloak / OAuth2 / JWT**. Deployment is containerised (Docker / Kubernetes) with **Prometheus / Grafana / Loki / OpenTelemetry** for observability.

Three principles drive every decision:

1. **Money is not eventually consistent.** Inside a transfer we use MongoDB multi-document ACID transactions. Across service boundaries we use eventual consistency via the **transactional outbox** pattern — no dual writes.
2. **Double-entry bookkeeping is non-negotiable.** Every transfer produces *two* journal lines (debit + credit). Corrections happen via **compensating entries** — the journal is append-only.
3. **Idempotency everywhere.** Every state-changing endpoint requires an `Idempotency-Key`. Every event consumer dedupes on `eventId`. This is the structural defence against the Part 10 *double-debit* failure mode.

---

## Resolved scope decisions

Scope calls were settled before any code was written. They define exactly what `docker-compose up` produces.

| # | Decision | Implication |
|---|---|---|
| 1 | **Full Keycloak in docker-compose** with a pre-imported realm. All services validate JWTs against Keycloak's JWKS endpoint. | Production parity over development convenience. |
| 2 | **Outbox publisher = in-process `@Scheduled` polling worker** with atomic lease-claim — one publisher per service (`outbox_txn`, `outbox_acc`, `outbox_auth`). | Zero extra infrastructure. Debezium documented as the production path in [ADR-0002](docs/decisions/0002-idempotency-and-exactly-once.md). |
| 3 | **Currency: field present and validated, single-currency per transfer.** Source, destination, and request `currency` must all match. | API contract is forward-compatible for FX without scope creep. Cross-currency requests return `422 CURRENCY_MISMATCH`. |
| 4 | **Five real services in compose**: API Gateway + Auth + Account + Transaction + Accounting. | A reviewer can run register → login → open accounts → transfer → query journal end-to-end. Notification + Fraud Detection remain *documented only* (Kafka consumers; not built). |
| 5 | **Chart of Accounts** seeded by Accounting Service on first boot (8 system accounts: cash holdings, platform float, customer wallet liability, retained earnings, fee/FX revenue, operating expense, bad-debt). Every journal entry carries a `coaAccount` ref; trial balance rolls up by accounting type (Asset/Liability/Equity/Revenue/Expense). | Real double-entry bookkeeping, not just wallet-to-wallet event logs. Extensible to fees, refunds, write-offs without schema changes. |
| 6 | **Spring Boot 4.0.6 + Spring Cloud 2025.1.0 + Java 21 + Jackson 3 (`tools.jackson.*`)**. Mongock dropped (no SB4 artifact); index init handled by per-service `SchemaInitializer` beans. | Current versions, Jackson 3 ready; the migration tool gap is documented in [`data-model.spec.md`](specs/data-model.spec.md) §7. |

---

## Mapping to the brief's 8 Parts

Every Part of the brief maps to a specific section of [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), supporting ADRs, and (where applicable) shipped code.

| Brief Part | Topic | Where it's answered |
|---|---|---|
| **1** | Overall architecture | [`ARCHITECTURE.md` §1](docs/ARCHITECTURE.md#part-1--overall-architecture) + [system-architecture diagram](docs/diagrams/system-architecture.mmd) |
| **2** | Data architecture (ACID vs eventual, audit, rollback) | [`ARCHITECTURE.md` §2](docs/ARCHITECTURE.md#part-2--data-architecture) + [ADR-0001](docs/decisions/0001-mongodb-as-ledger-store.md) |
| **3** | Security | [`ARCHITECTURE.md` §3](docs/ARCHITECTURE.md#part-3--fintech-security) + [ADR-0003](docs/decisions/0003-auth-stack.md) |
| **4** | Microservices design (REST, DTOs, HTTP codes) | [`ARCHITECTURE.md` §4](docs/ARCHITECTURE.md#part-4--microservices-design) + the full [`api.md`](docs/api.md) |
| **5** | TransactionService implementation | [`ARCHITECTURE.md` §5](docs/ARCHITECTURE.md#part-5--transactionservice-implementation-plan) + [transfer-sequence diagram](docs/diagrams/transfer-sequence.mmd) + (code: `transaction-service/`) |
| **6** | Event-driven architecture | [`ARCHITECTURE.md` §6](docs/ARCHITECTURE.md#part-6--event-driven-architecture) + [event-flow diagram](docs/diagrams/event-flow.mmd) + [ADR-0002](docs/decisions/0002-idempotency-and-exactly-once.md) + [ADR-0004](docs/decisions/0004-event-schema-and-evolution.md) |
| **7** | DevOps | [`ARCHITECTURE.md` §7](docs/ARCHITECTURE.md#part-7--devops) + (code: `docker-compose.yml`, `k8s/`) |
| **8** | Observability | [`ARCHITECTURE.md` §8](docs/ARCHITECTURE.md#part-8--observability) |
| **9** | Scalability (10 k → 100 k tx/min) | [`ARCHITECTURE.md` §9](docs/ARCHITECTURE.md#part-9--scalability-10k--100k-tx-min) + [ADR-0005](docs/decisions/0005-multi-region-and-ha.md) |
| **10** | Incident management — double debit | [`ARCHITECTURE.md` §10](docs/ARCHITECTURE.md#part-10--incident-management-double-debit) + [ADR-0002](docs/decisions/0002-idempotency-and-exactly-once.md) |

(The brief lists 8 Parts; I split scalability and incident-management into their own sections of `ARCHITECTURE.md` for clarity.)

---

## Brief deliverables — status

| Brief deliverable | Status | Location |
|---|---|---|
| Architecture document (PDF) | 🟡 Markdown source complete; PDF export pending | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Microservices code | ✅ 5 services on Spring Boot 4.0.6. **57 unit + ArchUnit + ProblemMapping + Jackson-compat tests passing without Docker** (gateway 8, transaction-service 23, account-service 26). Integration tests (Testcontainers) cover happy path + idempotency + crash-atomicity + 50-concurrent-same-key + reconciliation drift — require a running Docker daemon. | `services/{gateway, auth-service, account-service, transaction-service, accounting-service}/` |
| Working docker-compose | ✅ 5 services + Keycloak + 3-node Mongo RS + Kafka + Redis + init container; observability overlay separate | [`docker-compose.yml`](docker-compose.yml) + [`docker-compose.observability.yml`](docker-compose.observability.yml) |
| Platform architecture diagram | ✅ Mermaid source + PNG export | [`docs/diagrams/system-architecture.png`](docs/diagrams/system-architecture.png) |

**Additional artefacts beyond the brief's checklist:**

| Artefact | Why we shipped it |
|---|---|
| 5 ADRs in [`docs/decisions/`](docs/decisions/) | The five most consequential calls (Mongo-for-ledger, idempotency/outbox, auth stack, event schema, multi-region) deserve their own page; threading them through `ARCHITECTURE.md` would have buried them. |
| Full REST contract in [`docs/api.md`](docs/api.md) | The brief asks for "REST APIs, DTOs, HTTP codes, error responses" in Part 4. A dedicated contract document handles this at the depth the question deserves. |
| 9 implementation specs in [`specs/`](specs/) | Per-service + per-concern (data-model, events, ci, docker-compose, accounting). The contract a code reviewer reads alongside the PR. |
| Two extra diagrams (`transfer-sequence`, `event-flow`) | The hot path (Part 5) and the event flow (Part 6) are the technical heart of the answer. |
| Event JSON Schemas in [`events/schemas/`](events/schemas/) | Per `events.spec` §3, every Kafka event has a registered schema with BACKWARD compatibility. |
| Chart of Accounts seeded by Accounting Service | Real double-entry bookkeeping. See `specs/accounting-service.spec.md` §3.4. |
| GitHub Actions CI workflow | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) — build, lint, test, image scan, SBOM, signing. |
| Helm chart sketch | [`k8s/charts/transaction-service/`](k8s/) — representative chart; the other four follow the same shape. |

---

## Repository layout

```
.
├── README.md                                       ← you are here
├── pom.xml                                         ← parent Maven multi-module
├── docker-compose.yml                              ← 5 services + Keycloak + Mongo RS + Kafka + Redis
├── docker-compose.observability.yml                ← Prometheus / Grafana / Loki / OTel overlay
│
├── docs/                                           ← architecture documentation
│   ├── ARCHITECTURE.md                             ← main architecture doc (1:1 with brief's 8 Parts)
│   ├── api.md                                      ← REST contract
│   ├── decisions/                                  ← 5 ADRs
│   ├── diagrams/                                   ← Mermaid sources + PNG exports
│   └── TECHNICAL TEST -CTO - English.docx          ← the brief
│
├── specs/                                          ← per-service / per-concern implementation specs
│   ├── transaction-service.spec.md
│   ├── account-service.spec.md
│   ├── auth-service.spec.md
│   ├── accounting-service.spec.md
│   ├── gateway.spec.md
│   ├── data-model.spec.md
│   ├── events.spec.md
│   ├── docker-compose.spec.md
│   └── ci.spec.md
│
├── services/                                       ← Java code
│   ├── gateway/                                    ← Spring Cloud Gateway (reactive)
│   ├── auth-service/                               ← Keycloak adapter; sessions, MFA, OAuth2 refresh
│   ├── account-service/                            ← account lifecycle, status transitions, dual control
│   ├── transaction-service/                        ← focal service: /transactions, double-entry, outbox
│   └── accounting-service/                         ← Chart of Accounts, journal queries, trial balance
│
├── events/schemas/                                 ← JSON Schemas for every Kafka event
│
├── infra/                                          ← compose-side configuration
│   ├── keycloak/realm-export.json                  ← pre-imported realm
│   ├── mongo/init-replica-set.js                   ← replica-set + role-based users
│   └── observability/                              ← Prometheus / Loki / OTel / Grafana configs
│
├── k8s/                                            ← Helm chart sketch (representative)
│   └── charts/transaction-service/
│
├── scripts/
│   └── demo-happy-path.sh                          ← end-to-end cURL flow
│
└── .github/workflows/ci.yml                        ← PR pipeline (build, test, scan, sign)
```

---

## Running locally

```bash
# 1. Boot the platform — gateway, services, and the UI (≤ 90s on 4-core / 8 GB)
docker compose up -d

# 2. Wait for the gateway to be ready
until curl -fs http://localhost:8080/actuator/health > /dev/null; do sleep 2; done

# 3. Open the platform UI on http://localhost:5173
open http://localhost:5173

# 4. End-to-end demo: register → login → open accounts → transfer → replay → refresh → logout
./scripts/demo-happy-path.sh

# 5. (Optional) Observability overlay — Grafana on http://localhost:3000  (admin/admin)
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d

# 6. Reset
docker compose down -v
```

Then the cURL flow in [Appendix B of `api.md`](docs/api.md#appendix-b--curl-examples) walks through:

1. Register a user
2. Log in (create a session)
3. Open an account
4. Transfer between two accounts
5. Replay the same `Idempotency-Key` (observe `200 OK` with the original body)
6. Refresh the access token
7. Log out

Observability lives at:

- Grafana: `http://localhost:3000` (preloaded dashboards)
- Prometheus: `http://localhost:9090`
- Keycloak admin: `http://localhost:8081`

This section will be filled in with the exact commands once implementation lands.

---

## Tech stack at a glance

| Layer | Choice | Why |
|---|---|---|
| Language / runtime | Java 21, Spring Boot 3 | Brief mandate (Part 3) |
| Edge | Spring Cloud Gateway | Spring-native; JWT + rate-limit + circuit-break in one place |
| Identity | Keycloak (OAuth 2.1 + OIDC, PKCE) | Standards-based, replaceable, supports MFA out of the box ([ADR-0003](docs/decisions/0003-auth-stack.md)) |
| Database | MongoDB 7 (replica set, multi-doc transactions) | Brief direction in Part 3 + Part 5; trade-offs documented in [ADR-0001](docs/decisions/0001-mongodb-as-ledger-store.md) |
| Event bus | Apache Kafka (KRaft mode) | Replayable log; partition-keyed by `transactionId` for per-tx ordering |
| Event publishing | **Transactional outbox** + in-process polling publisher | No dual writes; documented in [ADR-0002](docs/decisions/0002-idempotency-and-exactly-once.md) |
| Schema management | JSON Schema + Apicurio Registry, BACKWARD compatibility | [ADR-0004](docs/decisions/0004-event-schema-and-evolution.md) |
| Secrets | HashiCorp Vault | Never plaintext env vars |
| Observability | Prometheus + Grafana + Loki + OpenTelemetry | RED + USE method; SLO-driven alerting |
| Deployment | Docker → Kubernetes (Helm) | Brief mandate (Parts 5, 7) |
| CI/CD | GitHub Actions → canary + auto-rollback | Forward-only deploys; the ledger forbids data rollback |

---

## A note on what's *not* in this submission

We chose depth over breadth. Specifically:

- **Notification Service, Fraud Detection Service, and the Accounting projector** are designed in `ARCHITECTURE.md` and the event-flow diagram, but not built. They are pure event consumers; their absence does not affect the correctness of the transfer flow.
- **FX (multi-currency transfers)** is deliberately out of scope. The `currency` field is present and validated; cross-currency requests return `422 CURRENCY_MISMATCH`. The contract is forward-compatible for an FX provider integration later.
- **PDF export** of `ARCHITECTURE.md` is pending; the Markdown source is the authoritative version. Render via `pandoc` or any Markdown-to-PDF tool.
- **Live Kubernetes deployment** is documented (Helm chart sketch) but not executed against a running cluster. The brief's deliverable is `docker-compose`, which we honour.

This is documented up-front so reviewers can calibrate. Anything claimed in `ARCHITECTURE.md` but not in code is explicitly labelled "documented only" — there are no covert gaps.

---

## License & attribution

This is a technical-test submission. The brief is © its author; this response is the candidate's work. Diagrams are authored in Mermaid (text source committed alongside the PNG exports).
