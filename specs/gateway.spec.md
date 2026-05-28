# Spec — API Gateway

**Module:** `services/gateway/`
**Package root:** `com.example.fintech.gateway`
**Status:** Draft for review
**Related:** [`../docs/ARCHITECTURE.md` §1.2](../docs/ARCHITECTURE.md#part-1--overall-architecture) · [`../docs/ARCHITECTURE.md` §3](../docs/ARCHITECTURE.md#part-3--fintech-security) · [`../docs/api.md` §1](../docs/api.md#1-conventions) · [ADR-0003](../docs/decisions/0003-auth-stack.md)

---

## 1. Purpose

The gateway is the **single ingress** to the platform. It owns all cross-cutting concerns at the edge so the downstream services can be simpler:

- TLS termination
- JWT validation (against Keycloak's JWKS)
- Coarse authorization (route ↔ required scope)
- Rate limiting
- Circuit breaking
- Correlation ID injection
- CORS
- Request logging + tracing root span

Services downstream can assume: the request has a valid JWT, the caller has at least the route's coarse scope, and a `X-Correlation-Id` header is present.

## 2. Scope

### In scope

- Spring Cloud Gateway running on port 8080 (in compose) / 443 with TLS (in prod)
- Route table → downstream service
- `OncePerRequestFilter` chain for JWT, rate-limit, correlation-id, CORS, logging
- Health / Prometheus on a separate management port (8081)
- Configuration via `application.yaml` only — no Java-coded routes (routes are YAML, filters are Java)

### Out of scope

- Service discovery dynamic registration (we use static route configuration for compose; Consul/Eureka is documented in `ARCHITECTURE.md` §1 for production but not implemented here)
- WAF (web application firewall — done at the load balancer in front of the gateway, not in the gateway itself)
- Mutual TLS service-to-service (mesh-level concern; documented but not implemented in compose)

---

## 3. Contract

### 3.1 Route table

```yaml
spring.cloud.gateway.routes:

  - id: auth-users
    uri: http://auth-service:8080
    predicates:
      - Path=/v1/users,/v1/users/**
    filters:
      - name: RequireScope
        args: {scopes: "users:write"}
        when: { method: [PATCH] }
      # POST /v1/users (registration) is exempt — no token required
      - StripPrefix=0

  - id: auth-sessions
    uri: http://auth-service:8080
    predicates:
      - Path=/v1/sessions,/v1/sessions/**

  - id: auth-oauth
    uri: http://auth-service:8080
    predicates:
      - Path=/v1/oauth/**
    filters:
      - SkipAuth                                       # token endpoint accepts unauthenticated calls

  - id: accounts
    uri: http://account-service:8080
    predicates:
      - Path=/v1/accounts,/v1/accounts/**
    filters:
      - name: RequireScope
        args: {scopes: "accounts:read", whenMethod: [GET]}
      - name: RequireScope
        args: {scopes: "accounts:write", whenMethod: [POST, PATCH]}

  - id: transactions
    uri: http://transaction-service:8080
    predicates:
      - Path=/v1/transactions,/v1/transactions/**
    filters:
      - name: RequireScope
        args: {scopes: "transactions:read", whenMethod: [GET]}
      - name: RequireScope
        args: {scopes: "transactions:write", whenMethod: [POST]}

  - id: journal-entries
    uri: http://account-service:8080            # owns the read-model in MVP
    predicates:
      - Path=/v1/journal-entries,/v1/journal-entries/**
    filters:
      - name: RequireRole
        args: {roles: "auditor,operator"}

  - id: reports
    uri: http://account-service:8080
    predicates:
      - Path=/v1/reports/**
    filters:
      - name: RequireRole
        args: {roles: "auditor"}
```

(Routes for documented-only services like Notification are omitted.)

### 3.2 Filter chain (in order)

| Order | Filter | Type | Concern | Output |
|---|---|---|---|---|
| 1 | `RateLimitFilter` | `WebFilter` @ `-101` (pre-security) | Token bucket per (IP + userId-if-known + route); returns 429 if exhausted | Request continues or 429 |
| 2 | `CorrelationIdFilter` | `GlobalFilter` @ `HIGHEST_PRECEDENCE` | If `X-Correlation-Id` absent, generate a ULID; set on MDC; propagate downstream | Request enriched |
| 3 | `JwtAuthenticationFilter` | Spring Security `WebFilter` @ `-100` | Validates JWT against Keycloak JWKS (cached). Sets `sub`, `scope`, `acr`, `roles` on the security context. Returns 401 on invalid token. Skipped for routes with `SkipAuth`. | Request authenticated |
| 4 | `DenyListFilter` | `GlobalFilter` | Checks Redis deny-list for the token's JTI; returns 401 if denied. (Used for explicit logout / compromised tokens.) | Request continues |
| 5 | `RequireScopeFilter` | `GlobalFilter` | Per-route scope check from §3.1 metadata; 403 if missing | Request continues |
| 6 | `RequireRoleFilter` | `GlobalFilter` | Per-route role check; 403 if missing | Request continues |
| 7 | `CorsFilter` | `WebFilter` | Standard CORS preflight + headers | OPTIONS short-circuited |
| 8 | `LoggingFilter` | `GlobalFilter` | Structured-log access entry (method, path, status, latency, correlationId, userId) | Request continues to downstream |

**Filter-type rationale.** Spring Cloud Gateway reactive runs `GlobalFilter`s inside `FilteringWebHandler`, which executes *after* all `WebFilter`s — so a `GlobalFilter` cannot precede Spring Security's reactive `WebFilterChainProxy` (registered at order `-100`). `RateLimitFilter` therefore has to be a `WebFilter` at order `-101`: only then can the anon (IP) bucket short-circuit unauthenticated DoS traffic *before* a protected route would 401 it (and consume no token). The correlation-id filter stays a `GlobalFilter` because the only consumers of `MDC[correlationId]` are inside the gateway filter chain or the downstream propagation header — the rate-limit filter reads `X-Correlation-Id` directly from the inbound header for its 429 body since MDC isn't populated yet.

**Route id at pre-security time.** Spring Cloud Gateway hasn't matched a route yet when `RateLimitFilter` runs, so `ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR` is null. The route id used in the user-bucket key is derived from the URL path prefix via `RateLimitKeyResolver.routeIdFromPath(...)` — that mapping is the source of truth for the bucket id and **must be kept in sync with the route table in §3.1**. An ArchUnit/integration test asserting this alignment is a follow-up on §5.3.

### 3.3 Internal package structure

```
com.example.fintech.gateway/
├── GatewayApplication.java
│
├── filter/
│   ├── RateLimitFilter.java                        ← WebFilter @ -101 (pre-security)
│   ├── CorrelationIdGlobalFilter.java              ← GlobalFilter @ HIGHEST_PRECEDENCE
│   ├── JwtAuthenticationFilter.java                ← (delegates to Spring Security @ -100)
│   ├── DenyListFilter.java                         ← GlobalFilter
│   ├── RequireScopeFilter.java                     ← GlobalFilter
│   ├── RequireRoleFilter.java                      ← GlobalFilter
│   ├── CorsFilter.java                             ← WebFilter
│   └── LoggingFilter.java                          ← GlobalFilter
│
├── ratelimit/
│   ├── RateLimitProperties.java                    ← @ConfigurationProperties("ratelimit")
│   ├── RateLimitDecision.java                      ← record returned by Lua call
│   ├── RateLimitKeyResolver.java                   ← anon-IP / user-route key + path → route-id
│   └── RedisRateLimitClient.java                   ← Redis Lua script for atomic decrement
│
├── error/
│   ├── ProblemDetailErrorWebExceptionHandler.java  ← converts gateway-level errors to RFC 7807 + code+params
│   └── GatewayErrorAttributes.java                 ← Spring's default attribute extractor, customised
│
├── auth/
│   ├── JwksClient.java                             ← Keycloak JWKS fetcher with caching
│   └── DenyListClient.java                         ← Redis client for JTI lookup
│
└── config/
    ├── SecurityConfig.java                         ← Spring Security routes (auth required vs anonymous)
    ├── ObservabilityConfig.java
    └── RedisConfig.java
```

---

## 4. Behaviour

### 4.1 JWT validation

`JwtAuthenticationFilter` delegates to Spring Security's `oauth2ResourceServer().jwt()` for the heavy lifting:

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${KEYCLOAK_ISSUER_URI}                # e.g. http://keycloak:8080/realms/fintech
  jwk-set-uri: ${KEYCLOAK_ISSUER_URI}/protocol/openid-connect/certs
```

The JWKS is fetched on startup and cached. Spring rotates the cache on receiving a token signed with an unknown `kid` (single retry to re-fetch the JWKS; second failure → 401).

**Claims extracted** to the `Authentication` principal:

| JWT claim | Where used |
|---|---|
| `sub` | Passed downstream as `X-User-Id` header; checked by services for ownership |
| `scope` (space-separated) | RequireScopeFilter |
| `realm_access.roles` (Keycloak default) | RequireRoleFilter |
| `acr` | Used by services for step-up checks |
| `jti` | Checked against deny-list |
| `exp`, `iat`, `nbf` | Standard expiry checks (Spring Security default) |

### 4.2 Rate limiting

Token bucket per composite key. Keys are computed as:

```
unauthenticated  → "anon:" + ipApprox(/24)
authenticated    → "user:" + sub + ":" + route + ":" + METHOD
```

Two buckets evaluated per request: the IP bucket and (if authenticated) the user-route bucket. **Both** must have tokens.

The filter runs **pre-security** as a `WebFilter` (see §3.2) — so authenticated identification uses an *unverified* decode of the Bearer JWT payload to extract `sub`. This is intentional: signature validation happens downstream and rejects bad tokens with 401, so an attacker forging a token at most lands in a bucket they don't own — they still can't get a 2xx out of the gateway. The route id used in the user key is derived from the path prefix via `RateLimitKeyResolver.routeIdFromPath(...)`.

Default limits (overridable via `application.yaml`):

| Bucket | Capacity | Refill rate |
|---|---|---|
| `anon` | 60 | 60/min (1/sec) |
| `user:*:transactions` (any method) | 600 | 600/min (10/sec) |
| `user:*:transactions:POST` | 60 | 60/min (1/sec) — write rate-limited harder |
| `user:*:sessions:POST` (login) | 10 | 10/min — anti-brute-force at the edge in addition to Keycloak |
| `user:*` (everything else) | 6000 | 6000/min (100/sec) — generous default for reads |

Implementation: Redis-backed via a Lua script that atomically decrements + sets TTL. We do not use Spring Cloud Gateway's `RequestRateLimiter` because we want per-route limits with composite keys; we ship our own filter.

On 429: response includes `Retry-After: <seconds-until-token-available>`, the `X-RateLimit-*` headers, and a Problem Detail body with `code=RATE_LIMITED` and `params={available: 0, limit: ..., resetAt: ...}`.

### 4.3 Deny list

Logout (DELETE /v1/sessions/current) and admin "revoke a compromised token" actions add the token's JTI to a Redis set with TTL set to the token's natural `exp`. The gateway checks this set on every authenticated request.

Performance: Redis `SISMEMBER` is sub-millisecond; the check is in the hot path but cheap. Caching the *negative* result (token not denied) for 60s would be possible if Redis becomes a bottleneck — not implemented for MVP.

If Redis is down, the gateway **fails open** on deny-list checks (logs a warning, allows the request) — we prefer availability to a very-rare freshness gap. Documented; reviewable.

### 4.4 Error responses

All gateway-level errors (rate-limit, deny-list, bad token, missing scope) return RFC 7807 Problem Detail with the same `code` + `params` extension that downstream services use. The format is identical so FE code doesn't care whether the error came from the gateway or a service.

`ProblemDetailErrorWebExceptionHandler` maps:

| Cause | HTTP | `code` |
|---|---|---|
| No `Authorization` header | 401 | `UNAUTHORIZED` |
| Invalid / expired / wrong-issuer JWT | 401 | `UNAUTHORIZED` |
| JTI on deny-list | 401 | `UNAUTHORIZED` |
| Missing required scope | 403 | `FORBIDDEN` |
| Missing required role | 403 | `FORBIDDEN` |
| Rate limit exceeded | 429 | `RATE_LIMITED` |
| Downstream timeout | 504 | `DEPENDENCY_UNAVAILABLE` |
| Downstream circuit open | 503 | `DEPENDENCY_UNAVAILABLE` |
| Downstream `Connection refused` | 502 | `DEPENDENCY_UNAVAILABLE` |

### 4.5 Circuit breaking

[Resilience4j](https://resilience4j.readme.io/) at the route level. Default config:

```yaml
resilience4j.circuitbreaker:
  instances:
    transactions:
      failure-rate-threshold: 50            # %
      slow-call-rate-threshold: 50
      slow-call-duration-threshold: 2000ms
      sliding-window-size: 50
      minimum-number-of-calls: 20
      wait-duration-in-open-state: 30s
      permitted-number-of-calls-in-half-open-state: 5
```

When the transaction-service circuit opens, the gateway short-circuits with `503 DEPENDENCY_UNAVAILABLE` and `Retry-After: 30`. No requests reach the failing service while the circuit is open — protects the dependency from a thundering herd.

Per-route circuit config; not all routes need the same thresholds.

### 4.6 Configuration

```yaml
spring:
  application.name: gateway
  cloud.gateway.routes: ... (see §3.1)
  data.redis:
    host: ${REDIS_HOST}
    port: ${REDIS_PORT}
  security.oauth2.resourceserver.jwt:
    issuer-uri: ${KEYCLOAK_ISSUER_URI}

server.port: 8080
management.server.port: 8081
management.endpoints.web.exposure.include: health,prometheus,info

ratelimit:
  defaults:
    anon: { capacity: 60, refill-per-second: 1 }
    user-transactions-write: { capacity: 60, refill-per-second: 1 }
    user-sessions-post: { capacity: 10, refill-per-second: 0.17 }
    user-default: { capacity: 6000, refill-per-second: 100 }

denylist:
  fail-open: true        # if Redis unreachable, allow

cors:
  allowed-origins: ${CORS_ORIGINS:https://app.example.com}
  allowed-methods: GET, POST, PUT, PATCH, DELETE, OPTIONS
  allowed-headers: Authorization, Content-Type, Idempotency-Key, If-Match, Accept-Language, traceparent
  exposed-headers: X-Correlation-Id, X-RateLimit-*, Retry-After, Location, ETag, Deprecation, Sunset
  max-age-seconds: 3600
```

---

## 5. Tests

See `transaction-service.spec.md` §5.0 — **Testcontainers** for the gateway too. Real Redis, real Keycloak, real downstream services (or [WireMock](https://wiremock.org/) for the downstream when the spec is about gateway behaviour only).

### 5.1 Unit

- Composite rate-limit key construction
- CORS preflight handler edge cases
- Problem-Detail conversion for each error class

### 5.2 Integration

Extends `IntegrationTestBase`. Starts: Redis, Keycloak (with realm export), and either real services or WireMock stubs depending on the test focus.

| Scenario | Asserts |
|---|---|
| No Authorization on protected route | `401 UNAUTHORIZED` Problem Detail |
| Invalid JWT (bad signature) | `401 UNAUTHORIZED` |
| Expired JWT | `401 UNAUTHORIZED` |
| Wrong issuer | `401 UNAUTHORIZED` |
| Valid JWT, missing required scope | `403 FORBIDDEN` |
| Valid JWT, sufficient scope | request reaches the downstream (asserted via WireMock) |
| `POST /v1/users` without token | passes through (route is anonymous) |
| `POST /v1/oauth/token` without token | passes through (SkipAuth filter applied) |
| Rate limit per-IP for anon | After 60 requests/min, 61st returns `429 RATE_LIMITED` with `Retry-After` |
| Rate limit per-user-write | After 60 POST /transactions/min, 61st returns 429 |
| Deny-list hit | Token JTI added to Redis; next request with that token → `401 UNAUTHORIZED` |
| Redis down + `fail-open=true` | Deny-list check skipped (warning logged); request passes |
| Redis down + `fail-open=false` | `503 DEPENDENCY_UNAVAILABLE` |
| Downstream service timeout | `504 DEPENDENCY_UNAVAILABLE` |
| Circuit open after N failures | Next request short-circuits to `503` without hitting the downstream |
| CORS preflight | OPTIONS with `Origin` returns 204 + ACA-* headers; body forwarding does not happen |
| Correlation-ID propagation | A downstream stub asserts the `X-Correlation-Id` header is present and equals the inbound value if provided |
| Logging | A request produces one access-log entry with method, path, status, latency, correlationId — verified by capturing the appender |

### 5.3 Filter-order ArchUnit test

A test that asserts the `@Order` (or `Ordered.getOrder()`) values of the filter beans match the order declared in §3.2. A regression where someone adds a new filter without an order, or reorders filters, fails this test.

---

## 6. Operational concerns

### 6.1 Metrics

| Metric | Tags |
|---|---|
| `spring.cloud.gateway.requests` (default) | routeId, status |
| `gateway_jwt_validation_seconds` | outcome |
| `gateway_rate_limit_rejections_total` | bucket, route |
| `gateway_deny_list_hits_total` | — |
| `gateway_circuit_state` (gauge) | service (open/half-open/closed represented as 0/1/2) |
| `gateway_downstream_call_seconds` | service, status |

### 6.2 Logs

Per-request structured log:

```json
{
  "timestamp": "...",
  "level": "INFO",
  "service": "gateway",
  "event": "http.request",
  "method": "POST",
  "path": "/v1/transactions",
  "routeId": "transactions",
  "status": 201,
  "latencyMs": 87,
  "correlationId": "...",
  "userId": "U-...",        // when JWT was valid
  "ipApprox": "203.0.113.0/24"
}
```

**Never log:** Authorization header, full IP, request body, response body.

### 6.3 Health checks

- **Liveness:** JVM up.
- **Readiness:** Redis ping + Keycloak JWKS reachable. We don't require downstream services to be ready — the gateway must boot even when services come up later.

### 6.4 Graceful shutdown

Drain in-flight requests (30s timeout), stop accepting new ones, exit. No special state to flush (Redis-resident rate-limit and deny-list survive).

---

## 7. Open questions

| # | Question | Default |
|---|---|---|
| 7.1 | Spring Cloud Gateway reactive (Netty) or MVC servlet? | **Reactive** — gateway is I/O-bound, reactive scales much better at the edge. We use `spring-cloud-starter-gateway` (reactive). Downstream services remain MVC (servlet). |
| 7.2 | TLS in compose, or only at prod LB? | **Plain HTTP in compose** (port 8080); TLS terminated by the LB in prod. Compose simplicity > local TLS realism. |
| 7.3 | JWT caching of validation results? | **No** — Spring's resource server caches the JWKS (the expensive part); per-token validation is essentially HMAC and is fast. Avoid the cache-invalidation problem. |
| 7.4 | Per-route timeouts? | **Yes**, set on the route via `Resilience4j` (e.g. transactions: 5s; reports: 30s). |
| 7.5 | Rate-limit filter type — `GlobalFilter` (post-security) or `WebFilter` (pre-security)? | **Pre-security `WebFilter` at order -101**. A `GlobalFilter` runs inside `FilteringWebHandler`, *after* Spring Security's reactive `WebFilterChainProxy` (order `-100`), so an unauthenticated request to a protected route would 401 before consuming a token — defeating anon-DoS protection. The cost: at WebFilter time SCG hasn't matched a route, so the route id has to be derived from the URL path. The mapping lives in `RateLimitKeyResolver.routeIdFromPath` and must stay in sync with §3.1. |

---

## 8. Acceptance criteria

- [ ] Routes in §3.1 are configured in `application.yaml`
- [ ] Filters in §3.2 exist and are ordered correctly (asserted by the ArchUnit test in §5.3)
- [ ] JWT validation against a Testcontainers Keycloak passes
- [ ] Rate-limit integration tests pass
- [ ] Deny-list integration test passes
- [ ] Circuit-breaker integration test passes
- [ ] Every error returns Problem Detail with `code` and `params` matching the catalogue in `api.md` §3
- [ ] CORS preflight test passes for the configured origin
- [ ] No filter in the codebase lacks an `@Order` annotation (ArchUnit)
- [ ] `RateLimitKeyResolver.routeIdFromPath` mapping matches the §3.1 route table (asserted by an ArchUnit or integration test)
- [ ] Metrics in §6.1 visible in the preloaded dashboard
