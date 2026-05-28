# Spec — docker-compose

**Status:** Draft for review
**Related:** [`../docs/ARCHITECTURE.md` §7.1](../docs/ARCHITECTURE.md#part-7--devops) · service specs · `data-model.spec` (§7 migrations) · `events.spec` (§3 schema registry)

---

## 1. Purpose

`docker-compose.yml` is one of the four mandated deliverables in the brief (Part 5). A reviewer must be able to `docker compose up -d` and exercise the full platform end-to-end — register → login → open accounts → transfer → observe — without further setup.

This spec defines: every container we run, every port and volume, every dependency relationship, every health check, and the boot order that makes the platform reliably reach a working state.

## 2. Scope

### In scope

- The base `docker-compose.yml` (the deliverable)
- An optional `docker-compose.observability.yml` overlay (Prometheus / Grafana / Loki / OTel collector)
- An optional `docker-compose.debezium.yml` overlay (documents the production outbox path — not part of the default boot)
- Init containers / init scripts for Mongo replica set + Keycloak realm + Apicurio schema registration
- Compose-level networking and volume conventions
- Reviewer's quickstart commands

### Out of scope

- Kubernetes deployment (separate `k8s/` Helm sketch in `ARCHITECTURE.md` §7.3)
- Production-grade configurations (HA replicas, secret management, TLS) — compose is the *local* manifestation
- The contents of `infra/keycloak/realm-export.json` and `infra/mongo/init-replica-set.js` — those are referenced here, defined where they're owned

---

## 3. Container catalogue

### 3.1 Base compose (`docker-compose.yml`)

| Service | Image | Ports (host:container) | Why |
|---|---|---|---|
| `gateway` | locally-built `fintech/gateway:dev` | `8080:8080` | API ingress |
| `auth-service` | locally-built `fintech/auth-service:dev` | (internal only) `8081:8081` for actuator | Auth + sessions + OAuth proxy |
| `account-service` | locally-built `fintech/account-service:dev` | (internal only) `8082:8081` actuator | Accounts |
| `transaction-service` | locally-built `fintech/transaction-service:dev` | (internal only) `8083:8081` actuator | Transactions |
| `keycloak` | `quay.io/keycloak/keycloak:25.0` | `8090:8080` (admin), `8091:8443` (TLS) | OIDC IdP |
| `mongo1` | `mongo:7.0.14` | `27017:27017` | Mongo RS primary candidate |
| `mongo2` | `mongo:7.0.14` | (internal only) | Mongo RS secondary |
| `mongo3` | `mongo:7.0.14` | (internal only) | Mongo RS secondary |
| `mongo-init` | `mongo:7.0.14` (one-shot) | — | Runs `init-replica-set.js` and creates roles |
| `kafka` | `bitnami/kafka:3.7` (KRaft mode) | `9092:9092` | Event bus |
| `schema-registry` | `apicurio/apicurio-registry-mem:2.6` | `8085:8080` | Schema registry |
| `redis` | `redis:7.4-alpine` | `6379:6379` | Gateway rate-limit + deny-list |
| `vault` | `hashicorp/vault:1.18` (dev mode) | `8200:8200` | Secrets (dev mode auto-unsealed; tokens are dev-only) |

All container versions are **pinned** — no `:latest`. Pinning is enforced by a CI test that greps `docker-compose.yml` for `:latest`.

### 3.2 Observability overlay (`docker-compose.observability.yml`)

| Service | Image | Ports | Why |
|---|---|---|---|
| `prometheus` | `prom/prometheus:v2.55` | `9090:9090` | Metrics scrape |
| `grafana` | `grafana/grafana:11.2` | `3000:3000` | Dashboards (admin/admin in dev) |
| `loki` | `grafana/loki:3.1` | `3100:3100` | Logs |
| `otel-collector` | `otel/opentelemetry-collector-contrib:0.108` | `4317:4317`, `4318:4318` | Traces (gRPC + HTTP) |
| `promtail` | `grafana/promtail:3.1` | — | Ships container logs to Loki |

Run with: `docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d`.

### 3.3 Debezium overlay (`docker-compose.debezium.yml`) — documented only

Per [ADR-0002](../docs/decisions/0002-idempotency-and-exactly-once.md), the polling publisher we ship is the simpler option. Debezium is the production path. This overlay is provided so a reviewer can run it if curious; it is **not** wired into the default boot.

| Service | Image | Why |
|---|---|---|
| `kafka-connect` | `debezium/connect:2.7` | Hosts the Debezium Mongo connector |
| `connect-init` | curl one-shot | POSTs the connector config from `infra/debezium/connector.json` |

When this overlay runs, services are configured (via env var `OUTBOX_PUBLISHER_ENABLED=false`) to **stop** their polling worker — Debezium takes over.

---

## 4. Networking & service discovery

### 4.1 Network

A single user-defined bridge network: `fintech-net`. All services join it.

Service-to-service DNS is by container name: `auth-service:8080`, `mongo1:27017`, etc. The gateway's route table (`gateway.spec` §3.1) uses these names.

Ports exposed to the host:
- `8080` — gateway (the only externally-visible HTTP endpoint of the platform itself)
- `8090` — Keycloak admin console
- `9090` — Prometheus, `3000` — Grafana (observability overlay only)
- `27017` — Mongo (for ad-hoc shell access)
- `9092` — Kafka (for `kafka-console-*` tools)
- `8085` — Schema registry UI
- `6379` — Redis

In production we'd expose only `443` (LB-terminated TLS). Compose simplifies for local use.

### 4.2 Volumes

Named volumes — explicit, named — so a `docker compose down` doesn't lose state, and `docker compose down -v` clearly drops it:

| Volume | Mounted at | Purpose |
|---|---|---|
| `mongo1-data` / `mongo2-data` / `mongo3-data` | `/data/db` in each Mongo container | Mongo datafiles |
| `kafka-data` | `/bitnami/kafka` | Kafka logs |
| `keycloak-data` | `/opt/keycloak/data` | Keycloak's H2 (dev mode) |
| `prometheus-data` | `/prometheus` | TSDB |
| `grafana-data` | `/var/lib/grafana` | Dashboards + state |
| `loki-data` | `/loki` | Log store |
| `vault-data` | `/vault/file` | Dev Vault state |

Config files (read-only, not state) are bind-mounted from `infra/`:

| Bind | Read-only? | Source |
|---|---|---|
| `infra/mongo/init-replica-set.js` → `/docker-entrypoint-initdb.d/init.js` (on `mongo-init`) | yes | `data-model.spec` artefact |
| `infra/keycloak/realm-export.json` → `/opt/keycloak/data/import/realm.json` | yes | `auth-service.spec` artefact |
| `infra/observability/prometheus.yml` → `/etc/prometheus/prometheus.yml` | yes | own config |
| `infra/observability/grafana/dashboards/` → `/var/lib/grafana/dashboards/` | yes | own config |
| `infra/observability/loki-config.yaml` → `/etc/loki/local-config.yaml` | yes | own config |
| `infra/observability/otel-collector.yaml` → `/etc/otel-collector-config.yaml` | yes | own config |
| `events/schemas/` → `/schemas/` (on a `schema-init` one-shot) | yes | `events.spec` artefact |

---

## 5. Boot order & health-checks

### 5.1 Dependency graph

```
mongo1, mongo2, mongo3   ←  mongo-init (waits for all 3 healthy, runs rs.initiate())
keycloak                 ←  starts independently; realm imported via `--import-realm` flag
kafka                    ←  starts independently (KRaft single-broker mode)
schema-registry          ←  depends_on kafka healthy
                            schema-init  ← uploads events/schemas/*.json to Apicurio
redis, vault             ←  start independently

auth-service             ←  depends_on mongo-init complete (Mongo RS ready), keycloak healthy
account-service          ←  depends_on mongo-init complete, kafka healthy, schema-registry healthy
transaction-service      ←  depends_on mongo-init complete, kafka healthy, schema-registry healthy, account-service healthy*
gateway                  ←  depends_on keycloak healthy, redis healthy

* transaction-service depending on account-service is a soft dependency to avoid the gateway routing to a tx-service that has nothing to validate against. Not strictly required.
```

### 5.2 Health-checks

Every container declares one. Compose uses these to gate `depends_on: condition: service_healthy`.

| Service | Health-check |
|---|---|
| `mongo1/2/3` | `mongosh --quiet --eval "db.adminCommand({ping:1})"` |
| `mongo-init` | one-shot — `condition: service_completed_successfully` |
| `keycloak` | `curl -fs http://localhost:8080/health/ready` |
| `kafka` | `kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null` |
| `schema-registry` | `curl -fs http://localhost:8080/health/ready` |
| `redis` | `redis-cli ping` |
| `vault` | `vault status` |
| Each app service | `curl -fs http://localhost:8081/actuator/health/readiness` |

Compose-level overall readiness is achieved when `gateway`'s readiness probe is green; reviewers can scripted-wait with:

```bash
until curl -fs http://localhost:8080/v1/health > /dev/null; do sleep 2; done
```

### 5.3 Init scripts

| File | Runs in | Does |
|---|---|---|
| `infra/mongo/init-replica-set.js` | `mongo-init` one-shot | `rs.initiate({_id:"rs0", members:[{...}, {...}, {...}]})`; waits for primary election; creates Mongo users `fintech_writer`, `fintech_journal_writer`, `fintech_reader` per `data-model.spec` §3.4; creates each DB and runs Mongock migrations bootstrap (via a follow-up application startup). |
| `infra/keycloak/realm-export.json` | imported by `keycloak` via `--import-realm` flag | Creates realm `fintech`, clients (mobile, web, gateway, auth-service service-account), roles (operator, auditor, user), default scopes, password policy, MFA enforcement policy. |
| `infra/schemas/upload.sh` | `schema-init` one-shot | POSTs each `events/schemas/*.json` to Apicurio under the subject `<topic>-value`. |

The init scripts are versioned in Git. They are the **same files** used by the integration test suites — there is no test-only fork (per `feedback-testcontainers`).

---

## 6. Configuration (env vars)

### 6.1 Service env vars set by compose

| Var | Where used | Default in compose |
|---|---|---|
| `MONGO_URI` | all app services | `mongodb://mongo1:27017,mongo2:27017,mongo3:27017/fintech?replicaSet=rs0&authSource=admin&...` |
| `KAFKA_BOOTSTRAP` | account / transaction services | `kafka:9092` |
| `SCHEMA_REGISTRY_URL` | account / transaction services | `http://schema-registry:8080/apis/registry/v2` |
| `KEYCLOAK_ISSUER_URI` | gateway + all app services | `http://keycloak:8080/realms/fintech` |
| `KEYCLOAK_BASE_URL` | auth-service | `http://keycloak:8080` |
| `KEYCLOAK_ADMIN_SECRET` | auth-service | from Vault (compose sets it to a dev value `dev-only-secret-not-for-prod`) |
| `REDIS_HOST` / `REDIS_PORT` | gateway | `redis` / `6379` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | all services (observability overlay) | `http://otel-collector:4317` |
| `OTEL_SERVICE_NAME` | each service | `transaction-service` / etc. |
| `SPRING_PROFILES_ACTIVE` | all services | `compose` |

### 6.2 Dev-only conveniences

Compose ships some conveniences that are **not** present in production:

- Vault runs in **dev mode** (auto-unsealed, in-memory by default — but we mount a volume so dev sessions are sticky). All "secrets" are placeholder values.
- Keycloak runs with `--features=preview` and uses its built-in H2 database. The realm import on startup means every `docker compose up` produces a deterministic Keycloak state.
- Mongo replica set is 3 nodes on **one host** — exactly what production *isn't* (where they'd be on 3 AZs).

These shortcuts are documented in the compose file itself with inline comments so a reader doesn't mistake them for production-ready.

---

## 7. Reviewer quickstart

The expected reviewer experience is documented here so we can verify it on a clean machine.

```bash
# 1. Boot the platform
docker compose up -d

# 2. Wait for readiness (≤ 60s on a warm machine)
until curl -fs http://localhost:8080/v1/health > /dev/null; do sleep 2; done

# 3. Run the happy-path cURL flow from docs/api.md Appendix B
./scripts/demo-happy-path.sh

# 4. (optional) Open dashboards
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
open http://localhost:3000        # Grafana — admin/admin
open http://localhost:9090        # Prometheus
open http://localhost:8090        # Keycloak admin — admin/admin
```

`scripts/demo-happy-path.sh` is a shell script we ship alongside compose that runs:

1. `POST /v1/users` (register)
2. `POST /v1/sessions` (login → access token)
3. `POST /v1/accounts` × 2 (source + destination)
4. `POST /v1/transactions` (transfer)
5. `GET /v1/transactions` (list — should show the transfer)
6. Retry step 4 with the same `Idempotency-Key` (expect `200 OK` with original body)

The script exits non-zero on any failure — usable as a smoke test in CI.

---

## 8. Tests

See `transaction-service.spec.md` §5.0 — **Testcontainers** is the canonical mechanism for service-level tests. The compose-level smoke test is different in nature:

### 8.1 Compose smoke (`scripts/compose-smoke.sh`)

A bash script run in CI on a Linux runner:

1. `docker compose up -d`
2. Wait up to 90s for gateway readiness
3. Run `scripts/demo-happy-path.sh`
4. `docker compose logs > compose.log`
5. `docker compose down -v`
6. Fail the build if step 2 or 3 didn't succeed

This is the only test that exercises the compose file as a unit. The point isn't to retest service logic (that's done by Testcontainers in each service); the point is to catch compose-level regressions (init script broke, port collision, env var typo).

### 8.2 Init-script tests

`infra/mongo/init-replica-set.js` and the schema-upload script are tested as part of `data-model.spec` and `events.spec` test suites — they run inside Testcontainers Mongo / Apicurio with the same scripts, so a working integration test guarantees the script will work in compose.

### 8.3 Container-version-pinning test

A trivial test in CI:

```bash
if grep -E '^\s*image:.*:latest' docker-compose*.yml; then
  echo "FAIL: image with :latest tag"
  exit 1
fi
```

---

## 9. Operational concerns

### 9.1 Resource sizing for the host

The full stack is moderately heavy:

| Service | RAM (steady state) |
|---|---|
| 4 app services (JVM) | ~512 MB each = 2 GB |
| 3 Mongo replicas | ~300 MB each = 900 MB |
| Kafka | ~600 MB |
| Keycloak | ~700 MB |
| Schema registry + Redis + Vault | ~200 MB combined |
| Observability overlay (when running) | ~1.5 GB |

Target host: **8 GB free RAM** minimum, **4 cores**. Documented in the top-level `README.md` § "Running locally".

### 9.2 Reset / clean state

```bash
docker compose down -v           # removes containers AND volumes — clean slate
docker compose pull              # update pinned images (only if you've bumped a tag)
docker compose build --no-cache  # rebuild local service images
```

The compose file ships with `restart: unless-stopped` on all services so a reboot of the host brings everything back up.

### 9.3 Log access

`docker compose logs -f <service>` — works as expected. The observability overlay adds Loki, which aggregates the same logs and lets a reviewer search across services in Grafana.

---

## 10. Open questions

| # | Question | Default |
|---|---|---|
| 10.1 | Mongo replica set on a single host — is this an honest signal of behaviour? | **Mostly yes** — it exercises the same code path as production for transactions, write concern, election. The thing it doesn't catch is real network-partition behaviour. Documented limitation. |
| 10.2 | Should we ship a `docker-compose.prod-shape.yml` overlay that runs 2 replicas of each app service to exercise leadership / lease contention? | **No for MVP** — the unit/integration tests cover the lease behaviour. Adding it to compose just slows boot. |
| 10.3 | Should the observability overlay be the default? | **No** — keeps the default boot lean. Reviewers who want dashboards opt in. |
| 10.4 | Vault in dev mode is unauthenticated by default — is that an acceptable signal? | **Yes for compose**, with inline comments warning. Production uses Vault in HA mode with proper auth. |

---

## 11. Acceptance criteria

- [ ] `docker compose up -d` brings every service to healthy within 90 seconds on a 4-core / 8-GB host
- [ ] `scripts/demo-happy-path.sh` runs to completion (exit 0) against the running stack
- [ ] Every image tag in compose is pinned (no `:latest`) — verified by the §8.3 CI test
- [ ] Healthchecks declared on every container
- [ ] Init scripts (Mongo RS, Keycloak realm, schema upload) are the **same files** used by Testcontainers integration tests
- [ ] Named volumes are explicit; `docker compose down -v` cleanly removes state
- [ ] Observability overlay starts independently and dashboards are preloaded
- [ ] Debezium overlay starts independently and toggles app services into outbox-publisher-off mode
- [ ] `README.md` includes the §7 quickstart commands verbatim
