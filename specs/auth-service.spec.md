# Spec — Auth Service

**Module:** `services/auth-service/`
**Package root:** `com.example.fintech.auth`
**Status:** Draft for review
**Related:** [`../docs/api.md` §6](../docs/api.md#6-users) · [`../docs/api.md` §7](../docs/api.md#7-sessions--tokens) · [`../docs/api.md` §8](../docs/api.md#8-mfa-factors) · [ADR-0003](../docs/decisions/0003-auth-stack.md) · `data-model.spec` (§5.1 `users`, §5.6 `sessions`) · `events.spec` (§7.6 user.registered)

---

## 1. Purpose

Auth Service is a **thin adapter** over Keycloak. It does not implement password hashing, MFA factor generation, or session revocation — Keycloak does. The service exists for two reasons:

1. **API ergonomics.** Keycloak's native endpoints (admin REST, OIDC discovery) are not the shape we want to expose to clients. We translate to our RESTful, idempotency-aware, RFC-7807 contract.
2. **Local domain state.** Our Mongo holds `users` (for `accounts.ownerUserId` referential integrity and KYC level) and `sessions` (for the active-sessions UX). Auth Service writes both.

The hard parts of identity (credential storage, MFA, brute-force protection, refresh-token rotation, key signing) stay in Keycloak. Auth Service is intentionally small.

## 2. Scope

### In scope

- `POST /v1/users` (registration)
- `GET /v1/users/me` (profile)
- `PATCH /v1/users/me` (profile updates)
- `POST /v1/sessions` (login)
- `DELETE /v1/sessions/current` (logout)
- `GET /v1/sessions` (list active)
- `DELETE /v1/sessions/{id}` (revoke specific)
- `POST /v1/oauth/token` (OAuth2 token endpoint — proxied through to Keycloak with grant_type=refresh_token)
- `GET /v1/users/me/mfa-factors` (list enrolled factors)
- `POST /v1/users/me/mfa-factors` (enrol)
- `POST /v1/users/me/mfa-factors/{id}/verifications` (verify)
- `DELETE /v1/users/me/mfa-factors/{id}` (remove)
- Publishing `users.user.registered` via the transactional outbox

### Out of scope

- Credential storage (Keycloak)
- Password reset flows (Keycloak's own pages handle this — we don't proxy)
- Social/federated login (Keycloak; could be added without API change)
- Admin operations on users (operator UI is out of scope)

---

## 3. Contract

### 3.1 HTTP surface

Authoritative in [`../docs/api.md` §6–§8](../docs/api.md#6-users).

### 3.2 Internal package structure

```
com.example.fintech.auth/
├── AuthServiceApplication.java
│
├── api/
│   ├── UsersController.java
│   ├── SessionsController.java
│   ├── OAuthController.java                 ← passthrough to Keycloak
│   ├── MfaFactorsController.java
│   ├── dto/
│   │   ├── RegisterUserRequest.java
│   │   ├── UserResponse.java
│   │   ├── PatchUserRequest.java
│   │   ├── CreateSessionRequest.java
│   │   ├── SessionResponse.java
│   │   ├── EnrolMfaRequest.java
│   │   ├── MfaFactorResponse.java
│   │   ├── VerifyMfaRequest.java
│   │   ├── MfaVerificationResponse.java
│   │   ├── TokenResponse.java               ← OAuth2 spec snake_case
│   │   └── ProblemResponse.java
│   └── ProblemExceptionHandler.java
│
├── domain/
│   ├── model/
│   │   ├── UserId.java
│   │   ├── SessionId.java
│   │   ├── MfaFactorId.java
│   │   ├── UserStatus.java                  ← {PENDING_VERIFICATION, ACTIVE, SUSPENDED, DELETED}
│   │   ├── KycLevel.java                    ← {NONE, BASIC, ENHANCED}
│   │   └── User.java
│   ├── exception/
│   │   ├── EmailAlreadyRegisteredException.java
│   │   ├── WeakPasswordException.java
│   │   ├── InvalidCredentialsException.java
│   │   ├── MfaRequiredException.java
│   │   ├── MfaInvalidException.java
│   │   ├── AccountLockedException.java
│   │   ├── RefreshTokenRevokedException.java
│   │   └── (others...)
│   └── policy/
│       └── PasswordPolicy.java              ← length + HIBP check (delegated to Keycloak)
│
├── application/
│   ├── RegisterUserService.java
│   ├── LoginService.java
│   ├── LogoutService.java
│   ├── SessionService.java
│   ├── MfaService.java
│   ├── OAuthService.java                    ← refresh-token grant
│   ├── UserFinder.java
│   └── IdempotencyService.java
│
├── persistence/
│   ├── document/
│   │   ├── UserDocument.java
│   │   ├── SessionDocument.java
│   │   └── OutboxRecordDocument.java
│   ├── repository/
│   │   ├── UserRepository.java
│   │   ├── SessionRepository.java
│   │   └── OutboxRepository.java
│   └── mapper/
│       └── UserMapper.java
│
├── integration/                             ← outbound Keycloak clients
│   ├── KeycloakAdminClient.java             ← user CRUD, MFA factor management
│   ├── KeycloakTokenClient.java             ← /token endpoint passthrough
│   ├── KeycloakWebhookController.java       ← receives session-revoke events from Keycloak
│   └── dto/                                 ← Keycloak's own DTOs (not exposed externally)
│
├── messaging/
│   ├── envelope/EventEnvelopeBuilder.java
│   ├── event/UserRegisteredEvent.java
│   └── OutboxPublisher.java
│
└── config/
    ├── SecurityConfig.java
    ├── MongoConfig.java
    ├── KafkaConfig.java
    ├── KeycloakConfig.java                  ← admin client wiring; cached service-account token
    └── ObservabilityConfig.java
```

### 3.3 Key external integration: Keycloak admin client

`KeycloakAdminClient` wraps the official `keycloak-admin-client` Maven artifact. Auth Service authenticates to Keycloak as a service account (`grant_type=client_credentials`) with a long-lived secret (Vault) and a short-lived access token (cached, auto-refreshed).

Methods used:

```java
public interface KeycloakAdminClient {
    String createUser(String email, String password, String fullName);                   // returns Keycloak sub
    void   updateUser(String keycloakSub, Map<String, Object> updates);
    void   enrolTotp(String keycloakSub);                                                 // returns secret + qr URL via separate call
    boolean verifyTotp(String keycloakSub, String otp);
    void   revokeAllSessions(String keycloakSub);                                          // for password-reset / dispute
    void   revokeSession(String keycloakSessionId);
    boolean validateCredentials(String email, String password);                           // does NOT issue token; checks only
    TokenResponse refreshToken(String refreshToken);                                       // proxy /token endpoint
}
```

**The login flow does not use Keycloak's password grant directly.** We call `validateCredentials` to check the password, then request a token via `client_credentials` with the user's identity attached (token exchange). This gives us:

- Server-side MFA orchestration (Keycloak's password grant doesn't naturally accept an OTP in one round trip)
- Server-side brute-force visibility (Keycloak's lockout still applies because `validateCredentials` flows through it)
- A clean place to create the local `sessions` row

---

## 4. Behaviour

### 4.1 Register

```
register(req, idempotencyKey):
  // 1. Idempotency
  existing = idempotencyService.find(noUser, "register", key)
  if existing: handle replay/conflict

  // 2. Create in Keycloak (synchronous, no transaction — Keycloak owns its DB)
  try:
    keycloakSub = keycloakAdminClient.createUser(req.email, req.password, req.fullName)
  catch KeycloakUserExists:                                  → 409 EMAIL_ALREADY_REGISTERED
  catch KeycloakWeakPassword:                                → 400 WEAK_PASSWORD

  // 3. Create local user + outbox row in one Mongo TX
  user = new UserDocument(
    _id           = UserId.generate(),
    email         = req.email,
    phone         = req.phone,
    fullName      = req.fullName,
    keycloakSub   = keycloakSub,
    status        = PENDING_VERIFICATION,
    kycLevel      = NONE,
    createdAt     = now,
    updatedAt     = now,
    version       = 1
  )

  withMongoTransaction:
    userRepository.insert(user)              // unique-index on email; if conflict here → compensate (delete from Keycloak)
    outboxRepository.insert(new OutboxRecord(
      topic   = "users.user.registered",
      eventId = ulid(),
      payload = envelope(new UserRegisteredEvent(user.id, now))
    ))

  return UserResponse(user)                                  → 201
```

**Compensation on the rare race:** `userRepository.insert` could fail with `DuplicateKey` on email if two registrations raced through Keycloak (Keycloak is the first to enforce email uniqueness, so this is *extremely* unlikely, but possible). We catch and roll back by calling `keycloakAdminClient.deleteUser(keycloakSub)`. The window is very small but documented.

### 4.2 Login (create session)

```
login(req):
  // No idempotency-key required — see api.md §1 carve-outs

  // 1. Credential check (delegated to Keycloak)
  if !keycloakAdminClient.validateCredentials(req.email, req.password):
                                                              → 401 INVALID_CREDENTIALS
  // (Keycloak handles lockout; we surface it as 423 ACCOUNT_LOCKED)

  // 2. MFA gate
  user = userRepository.findByEmail(req.email)
  if user.mfaEnabled():
    if req.otp == null:                                       → 401 MFA_REQUIRED
                                                                  (params.availableFactors = ["TOTP"])
    if !keycloakAdminClient.verifyTotp(user.keycloakSub, req.otp):
                                                              → 401 MFA_INVALID

  // 3. Issue token via token exchange (Keycloak issues a real OIDC token)
  tokens = keycloakAdminClient.exchangeToken(user.keycloakSub, scope="...")

  // 4. Record session row (display metadata only)
  session = new SessionDocument(
    _id              = SessionId.generate(),
    userId           = user.id,
    keycloakSession  = tokens.keycloakSessionState,
    deviceLabel      = uaParser.parse(req.userAgent),
    ipApprox         = anonymise(req.remoteAddr),
    createdAt        = now,
    lastSeenAt       = now,
    expiresAt        = now + refreshTokenLifetime
  )
  sessionRepository.insert(session)         // not in Mongo TX; eventual is fine

  return SessionResponse(session, tokens)                     → 201
```

**Note on transactional consistency:** the session row insert is **not** wrapped in a TX with the Keycloak token issue. If session insert fails after a token is already issued, the user has a valid token but we don't know about the session — they're logged in but their session won't appear in `GET /v1/sessions`. That's acceptable; the next refresh will reconcile (we also expose Keycloak's `/sessions` endpoint as a fallback if our local copy is empty).

### 4.3 Logout

```
DELETE /v1/sessions/current:
  caller, sessionId ← from JWT (session_state claim)
  withMongoTransaction:
    sessionRepository.deleteByKeycloakSession(sessionId)
    // Optionally: revoke at Keycloak (best-effort, fire-and-forget)
  keycloakAdminClient.revokeSession(sessionId)  // async
  return 204
```

The access token's residual TTL (≤ 15 minutes) is acceptable for logout — the deny-list at the gateway picks up the bearer on its next use. The deny-list is a Redis set with the token's JTI claim and TTL set to the token's `exp`.

### 4.4 List active sessions

```
GET /v1/sessions:
  caller ← from JWT
  sessions = sessionRepository.findByUserId(caller, sort=lastSeenAt DESC, paginated)
  // Mark current
  for s in sessions: s.current = (s.keycloakSession == jwt.sessionState)
  return paginated envelope
```

If our local `sessions` collection is **empty** for the user but Keycloak says they have an active session, we fall back to fetching from Keycloak (degraded mode, less metadata).

### 4.5 OAuth2 token endpoint

```
POST /v1/oauth/token:
  Content-Type: application/x-www-form-urlencoded
  Only refresh_token grant is supported.

  if grant_type != "refresh_token":                            → 400 invalid_grant
  tokens = keycloakAdminClient.refreshToken(refresh_token)
  if Keycloak detects rotation reuse:
    → 401 invalid_grant + code REFRESH_TOKEN_REVOKED
    Keycloak invalidates all sessions for the user automatically; we have nothing to do here.

  return TokenResponse(tokens)                                 → 200 (OAuth2 snake_case JSON)
```

This is essentially a passthrough. We expose it ourselves so the FE can use one base URL for all auth concerns, but the heavy lifting is Keycloak's.

### 4.6 MFA factor endpoints

All four endpoints are thin wrappers over the Keycloak admin API.

```
POST /v1/users/me/mfa-factors:
  resp = keycloakAdminClient.enrolTotp(jwt.keycloakSub)
  return { factorId: resp.id, type: "TOTP", status: "PENDING_VERIFICATION",
           secret: resp.secret, qrCodeUrl: resp.qrCodeUrl, createdAt: now }

POST /v1/users/me/mfa-factors/{factorId}/verifications:
  if !keycloakAdminClient.verifyTotp(jwt.keycloakSub, req.otp):
                                                                → 422 MFA_INVALID
  return { verificationId, factorId, status: "SUCCEEDED", verifiedAt: now }

GET /v1/users/me/mfa-factors:
  factors = keycloakAdminClient.listTotpFactors(jwt.keycloakSub)
  return paginated envelope of factors

DELETE /v1/users/me/mfa-factors/{factorId}:
  if jwt.acr indicates step-up not satisfied:                  → 401 STEP_UP_REQUIRED
  if factor is the only active factor and user requires MFA:   → 409 INVALID_STATE_TRANSITION
  keycloakAdminClient.removeTotpFactor(jwt.keycloakSub, factorId)
  return 204
```

The `MfaVerification` resource is conceptual — there's no Mongo collection. The `verificationId` we return is generated on demand (`VER-<ulid>`) and serves as an idempotency-trace ID, not a persistent record. (If we ever need to audit verification attempts, we'd add a `mfa_verifications` collection.)

### 4.7 Configuration

```yaml
spring:
  application.name: auth-service
  data.mongodb.uri: ${MONGO_URI}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP}
    producer:
      acks: all
      enable-idempotence: true
  security.oauth2.resourceserver.jwt:
    issuer-uri: ${KEYCLOAK_ISSUER_URI}

keycloak:
  server-url: ${KEYCLOAK_BASE_URL}                # e.g. http://keycloak:8080
  realm: fintech
  admin-client-id: auth-service
  admin-client-secret: ${KEYCLOAK_ADMIN_SECRET}   # from Vault
  token-cache-ttl-seconds: 240                    # < 300s default Keycloak token lifetime

server.port: 8080
management.server.port: 8081

outbox.publisher:
  tick-millis: 300
  batch-size: 50

sessions:
  ua-parser-cache-size: 10000
  ip-anonymisation: /24
```

### 4.8 Error → HTTP mapping

| Exception | HTTP | `code` |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` |
| `WeakPasswordException` | 400 | `WEAK_PASSWORD` |
| `MissingIdempotencyKeyException` | 400 | `MISSING_IDEMPOTENCY_KEY` |
| `AuthenticationException` | 401 | `UNAUTHORIZED` |
| `InvalidCredentialsException` | 401 | `INVALID_CREDENTIALS` |
| `MfaRequiredException` | 401 | `MFA_REQUIRED` (params.availableFactors) |
| `MfaInvalidException` (login context) | 401 | `MFA_INVALID` |
| `MfaInvalidException` (verifications context) | **422** | `MFA_INVALID` |
| `StepUpRequiredException` | 401 | `STEP_UP_REQUIRED` |
| `RefreshTokenRevokedException` | 401 (OAuth format) | `REFRESH_TOKEN_REVOKED` |
| `AccessDeniedException` | 403 | `FORBIDDEN` |
| `UserNotFoundException` | 404 | `RESOURCE_NOT_FOUND` |
| `EmailAlreadyRegisteredException` | 409 | `EMAIL_ALREADY_REGISTERED` |
| `IdempotencyConflictException` | 409 | `IDEMPOTENCY_KEY_CONFLICT` |
| `InvalidStateTransitionException` (removing last MFA) | 409 | `INVALID_STATE_TRANSITION` |
| `AccountLockedException` | 423 | `ACCOUNT_LOCKED` (+ Retry-After) |
| `RateLimitExceededException` | 429 | `RATE_LIMITED` |
| Keycloak unavailable | 503 | `DEPENDENCY_UNAVAILABLE` |

---

## 5. Tests

See `transaction-service.spec.md` §5.0 — **Testcontainers for Mongo, Kafka, and Keycloak**. No in-memory IdP mock.

### 5.1 Unit

- `PasswordPolicy` boundaries
- `UaParser` correctness (mobile vs desktop labels)
- `IpAnonymiser` /24 truncation; IPv6 /64
- `LoginService` branches with mocked Keycloak client

### 5.2 Integration

Extends `IntegrationTestBase` which starts Mongo + Kafka + **Keycloak** (with the production realm export imported).

| Scenario | Asserts |
|---|---|
| Register happy path | User in Keycloak; user in Mongo (`status: PENDING_VERIFICATION`); outbox row with `users.user.registered`; Kafka receives event |
| Register duplicate email (Keycloak rejects) | `409 EMAIL_ALREADY_REGISTERED`; no Mongo row; no outbox row |
| Register weak password (HIBP hit; we use the test HIBP fixture) | `400 WEAK_PASSWORD` |
| Register race causing Mongo conflict after Keycloak success | Compensation: Keycloak user deleted; clean state |
| Login without MFA | `201`, valid JWT, session row inserted |
| Login with MFA required, no OTP | `401 MFA_REQUIRED` with `params.availableFactors=["TOTP"]` |
| Login with wrong OTP | `401 MFA_INVALID` |
| Login with valid OTP | `201`, session row inserted |
| Login after 5 failed attempts (Keycloak brute-force) | `423 ACCOUNT_LOCKED` with `Retry-After` |
| Logout | Session row deleted; token added to gateway deny-list (verified by querying Redis); Keycloak session also revoked |
| List sessions | Returns paginated envelope; `current=true` on the JWT's session |
| Revoke specific session | Row deleted; Keycloak session revoked |
| Refresh token rotation | New tokens issued; old refresh token reuse fails with `REFRESH_TOKEN_REVOKED`; all sessions for the user revoked |
| Enrol TOTP | Keycloak now has a pending TOTP factor; response carries `secret` + `qrCodeUrl` |
| Verify TOTP with correct OTP | Factor becomes `ACTIVE`; user's `mfaEnabled` becomes true |
| Verify TOTP with wrong OTP | `422 MFA_INVALID` (note: **422**, not 401, because user is authenticated) |
| List MFA factors | Returns paginated envelope; never includes `secret` |
| Remove last MFA factor when user has MFA-required policy | `409 INVALID_STATE_TRANSITION` |
| Remove MFA factor without step-up | `401 STEP_UP_REQUIRED` |
| Keycloak down | Login returns `503 DEPENDENCY_UNAVAILABLE`; refresh returns the same; existing tokens continue to validate at the gateway (JWKS cached) |

### 5.3 Contract test against the production realm export

A test boots Keycloak with `infra/keycloak/realm-export.json` (the file compose uses) and asserts every client / role / scope our code expects is present. Catches realm-export drift.

---

## 6. Operational concerns

### 6.1 Metrics

| Metric | Tags |
|---|---|
| `http_server_requests_seconds` (default) | route, method, status, `code` |
| `auth_registrations_total` | outcome |
| `auth_logins_total` | outcome (success/invalid_credentials/mfa_required/mfa_invalid/locked) |
| `auth_token_refresh_total` | outcome |
| `auth_sessions_active` (gauge) | — |
| `keycloak_admin_call_seconds` | operation, outcome |
| `keycloak_admin_token_renewals_total` | — |
| `outbox_pending_count{service="auth-service"}` | — |

### 6.2 Sensitive log handling

- **Never** log: `password`, `otp`, full IP, raw User-Agent (only the parsed `deviceLabel`), Authorization header, full refresh token.
- DO log: `userId` (our internal ID), `keycloakSub` (Keycloak's), `sessionId`, `correlationId`, `outcome`.

### 6.3 Keycloak admin token caching

The service-account token is cached in memory with TTL `keycloak.token-cache-ttl-seconds` (default 240s, well below Keycloak's 300s default). On 401 from a Keycloak admin call, the cached token is invalidated and one retry is attempted with a freshly-obtained token.

### 6.4 Graceful shutdown

Same sequence as other services. Additionally, on shutdown the cached Keycloak admin token is invalidated explicitly (best-effort `POST /logout` to Keycloak) so the next instance starts clean.

---

## 7. Open questions

| # | Question | Default |
|---|---|---|
| 7.1 | Should we mirror MFA factor records into Mongo for offline list, or always call Keycloak? | **Always call Keycloak** for MVP. Keeps consistency simple; the response is small. Revisit if list latency becomes a problem. |
| 7.2 | Email verification flow — link in email vs OTP code? | **Link in email**, handled entirely by Keycloak's built-in pages. We just transition `users.status` from `PENDING_VERIFICATION` to `ACTIVE` on the Keycloak webhook. Webhook handler in `integration/KeycloakWebhookController.java`. |
| 7.3 | Service-account token rotation — manual or automated? | **Automated** via the `KeycloakConfig` bean. Rotated every 24h; old token continues to work until expiry. |
| 7.4 | Should `POST /v1/sessions` accept a `clientDeviceLabel` field from the body so a mobile app can override the parsed label? | **Yes, optional.** Mobile apps know their own name better than UA parsing does. |

---

## 8. Acceptance criteria

- [ ] Every class in §3.2 exists; package layout matches
- [ ] Keycloak admin client wraps the official artifact; integration test against a real Keycloak container passes
- [ ] Registration + login + logout + refresh end-to-end works against Testcontainers Keycloak with the production realm export
- [ ] The MFA flow (enrol → verify → list → remove) works against Testcontainers Keycloak
- [ ] Every exception in §4.8 is mapped; reflective test passes
- [ ] No password / OTP / Authorization header appears in any structured log (grep test)
- [ ] `users.user.registered` event is published via outbox
- [ ] All metrics in §6.1 visible in the preloaded dashboard
- [ ] Realm-export contract test (§5.3) passes
