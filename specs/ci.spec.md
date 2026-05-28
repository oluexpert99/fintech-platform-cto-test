# Spec — CI/CD pipeline

**Status:** Draft for review
**Related:** [`../docs/ARCHITECTURE.md` §7.4](../docs/ARCHITECTURE.md#part-7--devops) · all service specs (§5 test suites) · `docker-compose.spec` (§8 smoke)

---

## 1. Purpose

The CI pipeline is the contract that says "this code is safe to merge". A PR that goes green has been:

1. Compiled
2. Statically analysed
3. Unit-tested
4. Integration-tested against real infrastructure (Testcontainers — per `feedback-testcontainers`)
5. Scanned for known vulnerabilities
6. Built into immutable, signed container images
7. Smoke-tested through the full docker-compose stack

A PR that fails any gate cannot merge. This spec defines exactly what the gates are, what tools we use, and what acceptable outcomes look like.

## 2. Scope

### In scope

- GitHub Actions workflows (committed at `.github/workflows/`)
- Per-PR pipeline (validation; runs on every push to a branch)
- Per-`main` pipeline (publishes images; runs after merge)
- Release pipeline (cuts a tagged version on demand)
- Static analysis, security scans, SBOM generation, image signing
- Branch protection rules

### Out of scope

- The deployment pipeline beyond image publish — Kubernetes/Helm rollout is documented in `ARCHITECTURE.md` §7.3 but not built for this submission
- Production secret management (deferred to deployment phase)
- Performance regression detection in CI — out of scope (gatling smoke is local-only per `transaction-service.spec` §5.5)

---

## 3. Pipeline stages

### 3.1 Per-PR pipeline (`.github/workflows/pr.yml`)

Runs on `pull_request` against `main`. Cancels prior runs on the same branch (`concurrency.cancel-in-progress: true`).

| # | Stage | Tool | Fails build on |
|---|---|---|---|
| 1 | **Checkout & cache** | `actions/checkout`, `actions/cache` | — |
| 2 | **Set up JDK 21** | `actions/setup-java` | — |
| 3 | **Build all modules** | `mvn -B -ntp -DskipTests package` | Compilation error |
| 4 | **Lint** | Checkstyle (Google style, customised) | Any violation |
| 5 | **Static analysis** | SpotBugs (`spotbugs-maven-plugin`), Error Prone (compiler plugin) | High-confidence bugs / Error Prone error-level findings |
| 6 | **Dependency vulnerabilities** | OWASP Dependency-Check | Any HIGH or CRITICAL CVE |
| 7 | **Unit tests** | `mvn test` (Surefire) | Any failure |
| 8 | **Integration tests** | `mvn verify -P integration` (Failsafe + Testcontainers) | Any failure |
| 9 | **ArchUnit gates** | runs with unit tests | Any rule from `transaction-service.spec` §8 fails |
| 10 | **Schema-registry compatibility** | `events/schemas/*.json` posted to a Testcontainers Apicurio against the prior version | Any BACKWARD-incompatible change |
| 11 | **Compose-version-pinning** | `grep` for `:latest` tags | Any `:latest` |
| 12 | **Mongo schema-validator check** | New `infra/mongo/schemas/*.schema.json` applied to a fresh Mongo container | Validator fails to parse, or a sample doc is rejected |
| 13 | **Build container images** | `docker buildx build` per service Dockerfile | Build error |
| 14 | **Container image scan** | Trivy (`aquasecurity/trivy-action`) — HIGH + CRITICAL CVEs | Any HIGH or CRITICAL CVE that has a fix available |
| 15 | **SBOM generation** | Syft (`anchore/syft-action`) — per image | Generation failure |
| 16 | **Coverage report** | JaCoCo, posted as PR comment | Below 80% per service (line coverage) |
| 17 | **Compose smoke** | `scripts/compose-smoke.sh` (per `docker-compose.spec` §8.1) | Demo flow fails |

Total wall-clock budget: **~12 minutes** on a `ubuntu-22.04` runner. Parallelism via Maven's `-T 1C` (one thread per core) and matrix jobs for the four services where it helps.

### 3.2 Per-`main` pipeline (`.github/workflows/main.yml`)

Runs on push to `main` (post-merge). Everything from the PR pipeline plus:

| # | Stage | Tool | Fails build on |
|---|---|---|---|
| 18 | **Push images to registry** | `docker push` to GHCR (`ghcr.io/example/fintech-<svc>:<sha>` and `:main`) | Push failure |
| 19 | **Sign images** | cosign (keyless OIDC signing via GitHub identity) | Signing failure |
| 20 | **Attach SBOM as attestation** | `cosign attest --predicate sbom.spdx.json` | Failure |
| 21 | **Push to GitHub Container Registry** | already done; attestations attached | — |
| 22 | **Publish release notes** (on tag push) | `release-drafter` or hand-curated | — |

Image tags published per merge to `main`:
- `:<git-sha>` — immutable
- `:main` — moving pointer to the latest `main`
- `:v<semver>` — only on a tag push

### 3.3 Release pipeline (`.github/workflows/release.yml`)

Triggered manually (`workflow_dispatch`) with a target version (`vX.Y.Z`):

1. Verify the target SHA's `:main` image exists and is signed
2. Re-tag with `:vX.Y.Z`
3. Generate a GitHub Release with auto-generated notes
4. (Future) Trigger Argo CD / FluxCD to roll out to staging

For this submission, the release workflow is implemented as far as image retagging; the actual deploy step is documented as "TODO when k8s lands".

---

## 4. Branch protection

Configured on `main`:

- Require PR review (1 approval) before merge
- Require all PR-pipeline checks to pass
- Require branch to be up to date with `main` before merging
- **No** force push or deletion
- **No** merge bypass for admins
- Linear history (squash-merge or rebase-merge only — no merge commits)

Configured on `release/*` (when we cut release branches): same as `main` but stricter (2 approvals).

---

## 5. Tooling rationale

### 5.1 Why each tool

| Concern | Tool | Why this one |
|---|---|---|
| Build | Maven multi-module | Conventional for Spring Boot. Per locked codebase-structure decision. |
| Lint | Checkstyle (Google base + customisations) | Mature, well-documented, IDE-integrated. Customisations live in `.checkstyle.xml` at root. |
| Static analysis | SpotBugs + Error Prone | SpotBugs catches bytecode-level bugs; Error Prone catches source-level patterns at compile time. Complementary. We don't add PMD — it overlaps both. |
| Coverage | JaCoCo | Industry standard. Threshold-gated; 80% line per service. |
| Dependency CVE | OWASP Dependency-Check | The bar. Catches CVEs in transitive dependencies. Pinning required to silence false positives — addressed via `dependency-check-suppressions.xml`. |
| Image CVE | Trivy | Fast, accurate, widely-trusted. We pin its DB to a snapshot in CI for reproducibility. |
| SBOM | Syft | Generates SPDX or CycloneDX. We use SPDX. |
| Signing | cosign + keyless OIDC | Free, supply-chain best practice. The signing identity is the GitHub Action's OIDC token — no long-lived keys to manage. |
| Integration tests | Testcontainers | The mandatory choice per `feedback-testcontainers`. |
| Schema compat | Apicurio's CompatibilityChecker | Wrapped in a small test harness. |

### 5.2 Tools we considered but rejected

| Tool | Why rejected |
|---|---|
| SonarQube / SonarCloud | Heavy dependency, vendor lock-in, the value over Checkstyle + SpotBugs + Error Prone is marginal for our scale. |
| Snyk | Excellent but commercial; OWASP Dependency-Check + Trivy cover the same ground for free. |
| Maven Wrapper enforcement | We use Maven Wrapper (`mvnw`) but don't fail CI on its absence — covered by the cache-key check. |

---

## 6. Artefacts

Each PR pipeline produces:

| Artefact | Path / where |
|---|---|
| Maven build artifacts | `services/*/target/*.jar` — not published, kept for 7 days as workflow artifact |
| JaCoCo coverage report | `services/*/target/site/jacoco/index.html` — posted as PR comment summary + uploaded as workflow artifact |
| Test reports | `services/*/target/surefire-reports/`, `failsafe-reports/` — uploaded if a test failed |
| OWASP Dependency-Check report | `target/dependency-check-report.html` — uploaded |
| Trivy scan report | `trivy-<service>.json` — uploaded |
| SBOM per image | `sbom-<service>.spdx.json` — uploaded; attached as attestation on `main` |
| Compose smoke logs | `compose.log` (from `docker compose logs > compose.log`) — uploaded only on smoke failure |

PR-comment summary (posted by a `peter-evans/create-or-update-comment` action) shows:
- Test pass/fail counts per service
- Coverage per service vs. threshold
- Number of HIGH / CRITICAL CVEs added vs. main
- Whether the compose smoke passed

---

## 7. Secrets management

| Secret | Storage | Used by |
|---|---|---|
| `GITHUB_TOKEN` | auto-provided by Actions | image push to GHCR; PR comments |
| `COSIGN_OIDC_TOKEN` | OIDC from Actions | image signing (no key material to manage) |
| `TRIVY_DB_REPOSITORY_TOKEN` | Org secret (optional) | rate-limit avoidance on Trivy's DB fetch |
| GHCR pull token (for production) | Vault — not in CI | runtime |

No long-lived signing keys. No service-account credentials in CI. Everything is short-lived OIDC.

---

## 8. Caching strategy

Aggressive caching to keep wall-clock under 12 min:

| Cache | Key | Hit rate target |
|---|---|---|
| `~/.m2/repository` | `mvn-deps-${{ hashFiles('**/pom.xml') }}` | >95% |
| `~/.gradle/caches` (if any) | — | n/a |
| Docker buildx layers | `docker-layers-${{ github.ref }}` (per branch) + fallback to `main` | >80% |
| Trivy DB | `trivy-db-${{ runner.os }}-${{ hashFiles('.trivyignore') }}` | >99% |
| Testcontainers images | not cached on GitHub-hosted runners (no Docker layer cache for non-buildx); on self-hosted, yes | n/a |

Container images for Testcontainers (`mongo:7.0.14`, `kafka:3.7`, etc.) are pulled fresh each run on GitHub-hosted runners — the cold-start cost is ~30s for the four images combined, acceptable in the budget.

---

## 9. Quality gates — explicit thresholds

| Gate | Threshold | Where enforced |
|---|---|---|
| Test pass | 100% | Surefire / Failsafe |
| Line coverage per service | ≥ 80% | JaCoCo `check` goal in pom.xml |
| Branch coverage per service | ≥ 70% | JaCoCo |
| New HIGH/CRITICAL CVEs added vs. main | 0 (with available fix) | OWASP Dependency-Check + Trivy diff |
| Image size per service | ≤ 250 MB compressed | size check in pipeline |
| Build time end-to-end | ≤ 15 min | timeout in workflow |
| Compose smoke | runs to exit 0 | `scripts/compose-smoke.sh` |

Suppressions (anything we knowingly accept) live in committed files:
- `dependency-check-suppressions.xml`
- `.trivyignore`
- `spotbugs-exclude.xml`

Every suppression must have a comment explaining *why* and *when to revisit*. Reviewers reject suppressions without a justification.

---

## 10. Tests of the pipeline itself

| Scenario | Asserts |
|---|---|
| A PR that introduces a failing unit test | Pipeline fails at stage 7 |
| A PR that drops coverage below 80% | Pipeline fails at stage 16 |
| A PR that adds a `:latest` tag to compose | Pipeline fails at stage 11 |
| A PR that breaks BACKWARD schema compatibility | Pipeline fails at stage 10 |
| A PR that adds a HIGH CVE dependency | Pipeline fails at stage 6 or 14 |
| A PR that violates an ArchUnit rule (e.g. `kafkaTemplate.send` outside `OutboxPublisher`) | Pipeline fails at stage 9 |
| A PR that breaks the compose boot | Pipeline fails at stage 17 with `compose.log` uploaded |
| A normal feature PR with green tests | Pipeline goes green in ≤ 12 min |

These are not automated meta-tests (we don't simulate breaking the build in CI of CI) — they are documented expectations a reviewer can manually verify by introducing the regression in a draft PR.

---

## 11. Open questions

| # | Question | Default |
|---|---|---|
| 11.1 | GitHub-hosted runners or self-hosted? | **GitHub-hosted** for the submission. Self-hosted would cache better but adds operational scope. |
| 11.2 | Run integration tests in parallel across services, or sequentially? | **Parallel** (matrix job, one per service). Cuts wall time roughly in half. |
| 11.3 | Should we run mutation testing (PIT)? | **No for MVP** — slow and noisy. Re-evaluate when test suite plateaus. |
| 11.4 | Should the PR pipeline include a Maven `dependency:tree` diff comment to catch unintended dependency changes? | **Yes** — small effort, useful signal. |
| 11.5 | Renovate / Dependabot? | **Dependabot** — built into GitHub, free, sufficient. Scheduled weekly; security updates immediately. |

---

## 12. Acceptance criteria

- [ ] `.github/workflows/pr.yml` exists and implements stages 1–17
- [ ] `.github/workflows/main.yml` exists and implements stages 1–22
- [ ] `.github/workflows/release.yml` exists with manual trigger
- [ ] Branch protection on `main` configured per §4
- [ ] PR template at `.github/pull_request_template.md` links to this spec and reminds the author to update specs alongside code
- [ ] All thresholds in §9 are enforced by configuration (not aspirational)
- [ ] A test PR that violates one of the scenarios in §10 fails the pipeline at the expected stage
- [ ] `compose-smoke.sh` runs on a GitHub-hosted Linux runner in ≤ 90 seconds
