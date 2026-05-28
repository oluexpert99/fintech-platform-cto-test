# ADR-0003 — Authentication & authorization stack

- **Status:** Accepted
- **Date:** 2026-05-28
- **Deciders:** CTO candidate response
- **Related:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [ADR-0002](0002-idempotency-and-exactly-once.md)

## Context

The platform serves end users (mobile + web) and internal services. Identity, session, and authorization decisions must be:

- **Centralised** — one source of truth for who a user is, what they can do, and when their session ends.
- **Standards-based** — interoperate with mobile SDKs, third-party identity providers (Apple, Google), and a future B2B partner programme.
- **Hardened against the common attack classes** — credential stuffing, brute force, replay, token theft, MFA bypass.
- **Scoped narrowly** — issuing money-movement scopes only to clients that need them; revoking instantly when a device is lost.

We need decisions about: identity provider, protocol, token format, token lifetimes, MFA, service-to-service auth, and session revocation.

## Decision

| Concern | Choice |
|---|---|
| Identity Provider | **Keycloak** (self-hosted in our cluster; migratable to a managed IdP later) |
| Protocol for end users | **OAuth 2.0 + OpenID Connect**, **Authorisation Code with PKCE** for web & mobile |
| Token format | Signed **JWT** (RS256), short-lived |
| Access token lifetime | **15 minutes** |
| Refresh token lifetime | **24 hours**, **rotating, single-use** |
| MFA | **TOTP** mandatory after a threshold (account value or first transfer); **step-up auth** for high-value operations |
| Service-to-service | **mTLS** within the mesh; OAuth 2.0 **client_credentials** for cross-team integrations |
| Authorization | Coarse scopes at the gateway; fine-grained ownership checks in services via Spring Security `@PreAuthorize` |
| Session revocation | Token introspection unnecessary thanks to short TTLs; a small **deny-list** (Redis) for explicit logout / compromised tokens, checked at the gateway only |

## Why Keycloak

- **Mature, free, deployable in a container.** Fits the brief's stack constraint (Spring Boot + Docker) without locking us into a paid vendor.
- **Supports everything we need out of the box:** OIDC, social logins, TOTP MFA, password policies (incl. HIBP integration), realm-level RBAC, token customisation, brute-force lockout, account linking.
- **Standard JWTs:** any Spring Security resource server can validate Keycloak tokens with two lines of config (`spring.security.oauth2.resourceserver.jwt.issuer-uri`). No vendor SDK required.
- **Replaceable:** if we move to a managed IdP (Auth0, AWS Cognito, Okta) later, the application code that validates JWTs doesn't change — only `issuer-uri` and signing-key endpoint change.
- **In-cluster** keeps the auth round-trip on the same network as the services; we can still expose Keycloak's external endpoints for login.

## Why OAuth 2.0 + OIDC, Authorisation Code + PKCE

- **OIDC** layers identity (user info) on top of OAuth's delegation model — we get both `sub`, `email`, etc. *and* scope-based delegation.
- **Authorisation Code + PKCE** is the OAuth 2.1 recommendation for *all* public clients. Mobile and SPA clients have no secure place to store a client secret; PKCE replaces the secret with a per-flow code verifier that's pinned to the originating device.
- **No implicit grant**, no resource-owner password grant — both are deprecated for good reasons (token leakage in URLs, no way to enforce MFA properly).

## Why JWT, and why short-lived

- **Stateless validation** — services validate the JWT signature locally with the Keycloak JWKS. No round-trip to the IdP per request. Crucial at 1 667 tx/s.
- **Short lifetimes (15 min)** cap the blast radius of a leaked access token. The cost is more refresh-token round-trips, which is fine — refresh is the only IdP-touching call.
- **Refresh tokens rotate.** Every refresh issues a new refresh token and invalidates the old one. If an attacker steals a refresh token and uses it, the legitimate client's next refresh fails — we detect the theft.
- **No long-lived JWTs.** A 24-hour access token is a regulatory finding waiting to happen.

### Why not opaque tokens + introspection?

- Introspection per request is a synchronous call to the IdP. At our scale it adds latency, couples us to IdP availability, and forces the IdP to scale to peak request volume.
- We accept the trade-off: revocation is not instant. The 15-minute TTL bounds the damage; the deny-list covers the urgent "this device was stolen" case.

## Authorization model

Two layers:

1. **Scopes (coarse, at the gateway).** A token carries scopes like `account:read`, `transfer:write`, `admin:*`. The gateway has a route-to-required-scope mapping and rejects with 403 before the request hits the service. Defence-in-depth: services *also* check, never trusting the gateway alone.
2. **Ownership (fine, in the service).** A token may carry `transfer:write`, but the user can only transfer from accounts they own. The service code reads `accounts.ownerUserId` and compares with the JWT `sub` claim:
   ```java
   @PreAuthorize("@accountOwnership.canTransferFrom(#req.sourceAccount, authentication)")
   public TransferResponse transfer(...) { ... }
   ```
3. **Admin roles** are first-class Keycloak roles (`operator`, `incident-manager`, `auditor`). High-impact admin actions (reverse a transaction, unfreeze a flagged account) require **dual control** — two operators must approve in the admin UI before the action commits.

## MFA & step-up

> **Test-deliverable scope:** MFA is **disabled** in the shipped docker-compose. See [ADR-0006](0006-mfa-out-of-scope-for-test.md). The section below describes the production target.

- **TOTP is enforced** at first login for any account that crosses a balance threshold or attempts its first transfer.
- **Step-up authentication** — `/transactions/transfer` for amounts above a configurable threshold requires the JWT to carry a `acr` claim showing the user re-authenticated with MFA within the last N minutes. If not, the gateway returns `401` with a `WWW-Authenticate: step-up` hint and the client triggers the IdP step-up flow.
- **Recovery codes** at MFA enrolment so users can't lock themselves out.

## Service-to-service authentication

- **mTLS** inside the cluster via the service mesh (Istio / Linkerd). Each pod gets a SPIFFE identity certificate auto-rotated by the mesh. Services verify the peer SPIFFE ID, not just "valid cert".
- For services that need a *user identity* to flow through (e.g., Accounting Service auditing on behalf of a user), the original JWT is propagated as a header; downstream services re-validate it.
- For cross-team / cross-cluster calls (rare): **OAuth 2.0 client_credentials grant** with a Keycloak service account. Same JWT validation path; different `aud` claim.
- **Never** use shared secrets / API keys as the primary mechanism. Service accounts are revocable in Keycloak; shared keys leak forever.

## Token contents

A minimal access-token payload after Keycloak issues it:

```json
{
  "iss": "https://auth.example.com/realms/fintech",
  "sub": "U-abc123",
  "aud": ["transaction-service", "account-service"],
  "exp": 1716900000,
  "iat": 1716899100,
  "scope": "account:read transfer:write",
  "acr": "loa2",                 // level of assurance — MFA recently used
  "client_id": "mobile-ios",
  "session_state": "..."
}
```

Anything else (roles, account list) is **looked up server-side** rather than packed into the token, to keep tokens small and to ensure revocation works.

## Brute force, replay, injection — defence layering

| Threat | Defence |
|---|---|
| Brute-force login | Keycloak lockout (15 attempts → 30 min lock, exponential thereafter); gateway per-IP rate limit; CAPTCHA after 3 failed attempts on a session |
| Credential stuffing | HIBP breached-password check on register & password change; device-fingerprint anomaly alerts; impossible-travel detection |
| Token replay | Short TTL + JTI claim + per-request `Idempotency-Key` (separate, see ADR-0002); refresh-token rotation detects refresh-token theft |
| Token theft via XSS | No tokens in `localStorage` — only in HTTP-only Secure SameSite=Strict cookies for the web app (CSRF is handled because the API is JWT-not-cookie for service calls; the web app uses a BFF that converts) |
| MFA-bypass via SS7 (SMS) | We don't offer SMS as the only second factor. TOTP / passkeys are primary; SMS only as a recovery factor with rate limits |
| Injection (SQLi / NoSQLi / LDAPi) | Parameterised queries; JSON Schema validation on every input; no user input in Mongo operator positions |
| CSRF | API uses bearer tokens (not cookies) — CSRF doesn't apply; the BFF for the web app uses double-submit cookies and SameSite=Strict |

## Considered alternatives

### Alt — Build our own auth service
- ❌ Reinventing OIDC is a known anti-pattern. Cryptographic mistakes, MFA UX errors, and audit-trail gaps are guaranteed.

### Alt — Managed IdP (Auth0, Cognito) from day one
- ✅ Less ops; arguably more secure than self-hosted.
- ❌ Vendor lock-in, per-MAU billing that scales with growth, data residency questions for a FinTech.
- Keycloak gives us the same API surface; we can switch later by changing one configuration property in our services.

### Alt — Static API keys per integration
- ❌ Revocation is painful; leaks are catastrophic.

### Alt — Session cookies + server-side sessions everywhere
- ❌ Requires sticky sessions or a session store; couples scaling. JWTs are the right choice for a microservices fleet.

## Consequences

- Every service includes `spring-boot-starter-oauth2-resource-server` and validates JWTs against Keycloak's JWKS endpoint.
- Keycloak realm config is checked into Git as a JSON export and imported on `docker-compose up` (so the local platform starts pre-configured).
- A small `auth-service` exists in front of Keycloak only to package convenience endpoints (`/auth/register`, `/auth/login`, `/users/me`) and to hide Keycloak realm URLs from clients. It is a thin adapter, not a re-implementation.
- The deny-list (Redis) is *only* consulted at the gateway — keeping the hot path stateless on the services.
- Token rotation, key rotation, and JWKS refresh are scheduled and tested.
- Penetration tests must specifically cover the step-up flow and the refresh-rotation detection.

## References

- IETF, [OAuth 2.1 draft](https://datatracker.ietf.org/doc/draft-ietf-oauth-v2-1/)
- IETF, [OAuth 2.0 Security Best Current Practice (RFC 9700)](https://datatracker.ietf.org/doc/html/rfc9700)
- NIST SP 800-63B, *Digital Identity Guidelines: Authentication and Lifecycle Management*
- Keycloak documentation, *Token exchange* and *Step-up authentication*
- OWASP Cheat Sheets: [JWT](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html), [Authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
