# Kubernetes deployment (sketch)

> **Scope:** this directory contains a **representative Helm chart sketch** for one of the five
> services. The other four services follow the identical chart shape — only `values.yaml` differs
> per service. We ship only the `transaction-service` chart in full to demonstrate the pattern;
> the rest are reproducible by copy-then-tweak.
>
> **Not in scope for this test deliverable:** a working cluster, a CD controller (Argo CD /
> FluxCD), or environment-specific overlays. The brief's required deployment artifact is the
> docker-compose at the repo root; this directory documents how production deployment would
> look (per `ARCHITECTURE.md` §7.3).

## Layout

```
k8s/
├── README.md                                       ← this file
└── charts/
    └── transaction-service/
        ├── Chart.yaml
        ├── values.yaml                             ← chart defaults
        ├── values-dev.yaml                         ← dev cluster overrides
        ├── values-staging.yaml                     ← staging cluster overrides
        ├── values-prod.yaml                        ← prod cluster overrides
        └── templates/
            ├── deployment.yaml
            ├── service.yaml
            ├── hpa.yaml                            ← HPA on CPU + http_requests_in_flight
            ├── pdb.yaml                            ← min 2 pods during voluntary disruptions
            ├── networkpolicy.yaml                  ← egress restricted to known dependencies
            ├── servicemonitor.yaml                 ← Prometheus Operator scrape config
            └── _helpers.tpl
```

## Per-environment differences (illustrative)

|                              | dev          | staging       | prod                    |
|------------------------------|--------------|---------------|-------------------------|
| `replicaCount`               | 1            | 2             | 6 (HPA 6→20)            |
| `image.tag`                  | `:main`      | `:<git-sha>`  | `:v<semver>` (signed)   |
| `resources.requests.cpu`     | 100m         | 250m          | 1 core                  |
| `resources.requests.memory`  | 512Mi        | 1Gi           | 2Gi                     |
| `mongo.uri`                  | in-cluster Mongo | Atlas dev cluster | Atlas prod cluster |
| `kafka.bootstrapServers`     | in-cluster Kafka | MSK dev    | MSK prod                |
| `keycloak.issuerUri`         | in-cluster   | staging IdP   | prod IdP (managed)      |
| `outbox.publisher.tickMillis`| 200          | 200           | 200                     |
| `podDisruptionBudget.minAvailable` | 0      | 1             | 4                       |

## Deployment

```bash
# Render
helm template tx ./k8s/charts/transaction-service \
     -f ./k8s/charts/transaction-service/values-prod.yaml \
     | kubectl diff -f -

# Apply
helm upgrade --install tx ./k8s/charts/transaction-service \
     -f ./k8s/charts/transaction-service/values-prod.yaml \
     --namespace fintech \
     --create-namespace
```

In production, this command is invoked by a CD controller (Argo CD or FluxCD), not by an
operator's local Helm. The CI pipeline (`.github/workflows/ci.yml`) tags an image; the CD
controller picks up the new tag and rolls it out canary-style with auto-rollback on SLO breach.
