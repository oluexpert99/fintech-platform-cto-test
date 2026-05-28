# REST API reference

**Companion to:** [`ARCHITECTURE.md`](ARCHITECTURE.md)
**Related ADRs:** [0002 — Idempotency](decisions/0002-idempotency-and-exactly-once.md), [0003 — Auth](decisions/0003-auth-stack.md), [0004 — Event schema](decisions/0004-event-schema-and-evolution.md)
**Status:** Draft for review

This document is the contract surface of the platform. The brief (Part 4) asks for REST APIs, DTOs, HTTP codes, and error responses — this is where those live.

---

## Table of contents

1. [Conventions](#1-conventions)
2. [Mapping from the brief's example endpoints](#2-mapping-from-the-briefs-example-endpoints)
3. [Error model](#3-error-model)
4. [Idempotency](#4-idempotency)
5. [Pagination, filtering, sorting](#5-pagination-filtering-sorting)
6. [Users](#6-users)
7. [Sessions & tokens](#7-sessions--tokens)
8. [MFA factors](#8-mfa-factors)
9. [Accounts](#9-accounts)
10. [Transactions](#10-transactions)
11. [Journal & reports (Accounting Service)](#11-journal--reports-accounting-service)
12. [Health, readiness & operational endpoints](#12-health-readiness--operational-endpoints)
13. [Versioning & deprecation policy](#13-versioning--deprecation-policy)
14. [Appendix A — DTO ↔ Entity separation](#appendix-a--dto--entity-separation)
15. [Appendix B — cURL examples](#appendix-b--curl-examples)

---

## 1. Conventions

### Resource naming — REST done properly

Every URL identifies a **resource** (a noun), never an action (a verb). State changes happen through:

- **`POST` on the collection** — create a new resource
- **`GET` on the collection** — list / search
- **`GET` on the item** — read
- **`PATCH` on the item** — partial update (including state transitions like `status: FROZEN`)
- **`PUT` on the item** — full replace (rarely used in this API)
- **`DELETE` on the item** — destroy

When a domain "action" doesn't naturally fit CRUD (e.g. a reversal of a transfer), we model it as **creating a different *kind* of the same resource** via a discriminating field (e.g. `POST /transactions { "type": "REVERSAL", ... }`). We do **not** invent verb sub-paths like `/transactions/{id}/reverse`.

### Base URL & versioning

All endpoints are exposed via the API Gateway at:

```
https://api.example.com/v1/...
```

- **URI versioning** (`/v1/`, `/v2/`). New major version only on breaking changes.
- **No version in DTOs.** Versioning lives in the URL path.

### Transport

- **TLS 1.3 only**; HSTS preload list.
- **HTTP/2** end-to-end.
- **JSON** request/response bodies (`Content-Type: application/json; charset=utf-8`).
- **UTF-8** everywhere.

### Required request headers

| Header | Required on | Notes |
|---|---|---|
| `Authorization: Bearer <jwt>` | Every endpoint except `POST /v1/users`, `POST /v1/sessions`, `POST /v1/oauth/token`, `/v1/health` | Validated at the gateway against Keycloak JWKS |
| `Idempotency-Key: <uuid>` | Every **state-changing** endpoint (`POST`, `PATCH`, `DELETE` that mutates), **except** the carve-outs listed below | UUID v4; per-user scope. See [§4](#4-idempotency) |
| `If-Match: <version>` | Optional on `PATCH` / `PUT` against a resource that exposes a `version` field | Server returns `409 VERSION_CONFLICT` if the supplied version doesn't match the current one. Mirrors the `version` value returned by the prior `GET`. |
| `X-Correlation-Id: <uuid>` | Optional inbound; **mandatory** between services | Propagated across services & Kafka events |
| `Accept-Language: <bcp47>` | Optional | Used only to choose the language of `title` / `detail` in error responses. **Never** changes `code` — that field is locale-independent |
| `traceparent: <w3c-trace>` | Optional inbound; propagated | W3C Trace Context |
| `Content-Type: application/json` | Every request with a body (except `POST /v1/oauth/token` which is `application/x-www-form-urlencoded` per OAuth2) | |
| `Accept: application/json` | Recommended | |

**`Idempotency-Key` carve-outs.** The header is **not** required on:

| Endpoint | Why |
|---|---|
| `POST /v1/sessions` (login) | Credentials are the user's de-duplication signal. A network retry that succeeds twice creates two sessions, which is benign — sessions are individually listable (`GET /v1/sessions`) and revocable (`DELETE /v1/sessions/{id}`). |
| `POST /v1/oauth/token` | OAuth2 spec doesn't define an idempotency header; refresh-token rotation is its own dedupe mechanism (a rotated token is single-use). |

All other state-changing endpoints **must** carry `Idempotency-Key`; missing it returns `400 MISSING_IDEMPOTENCY_KEY`.

### Standard response headers

| Header | Meaning |
|---|---|
| `X-Correlation-Id` | Echoed/generated; quote this when reporting issues |
| `X-RateLimit-Limit` | Permitted requests in the current window |
| `X-RateLimit-Remaining` | Remaining requests |
| `X-RateLimit-Reset` | Epoch seconds when the window resets |
| `Retry-After` | Present on `429`, `503`, `423 ACCOUNT_LOCKED`, and `409 IDEMPOTENCY_IN_PROGRESS`. Value is either delta-seconds or an HTTP-date (per RFC 7231) — clients must accept both. |
| `ETag: <version>` | Present on resource reads that support optimistic concurrency. Pass the value back as `If-Match` on the next `PATCH`. |
| `Location` | Present on `201 Created`. URI of the new resource. |
| `Deprecation` | Present on deprecated endpoints; value is a date |
| `Sunset` | Present on deprecated endpoints; the retirement date |

### Naming

- **Paths** are lowercase, kebab-case, plural nouns: `/accounts`, `/transactions`, `/mfa-factors`.
- **JSON fields** are `lowerCamelCase`.
- **Enum values** are `SCREAMING_SNAKE_CASE` (`status: "ACTIVE"`, `type: "TRANSFER"`).
- **IDs** are opaque strings the client should not parse.
- **Times** are RFC 3339 UTC strings ending in `Z`: `"2026-05-28T10:00:00Z"`.
- **Money** is always an integer in the smallest currency unit (`"amount": 12345` = 123.45 USD). **Never floats.**
- **Currencies** are ISO 4217 codes (`"USD"`, `"EUR"`).

### Authorization scopes

OAuth2 scopes carried by the access token. Naming convention: **plural resource noun, colon, verb**.

| Scope | Grants |
|---|---|
| `accounts:read` | Read own accounts and their balance |
| `accounts:write` | Open, label, freeze (user-initiated) own accounts |
| `transactions:read` | Read own transactions |
| `transactions:write` | Create `TRANSFER` transactions on own accounts |
| `transactions:reverse` | Operator scope. Create `REVERSAL` transactions (also requires the `operator` role and dual control) |
| `sessions:read` | List own active sessions |
| `sessions:write` | Revoke own sessions |
| `users:write` | Update own profile |
| `admin:*` | Operator / auditor scope; gates the journal and reports |

A scope grants the *capability*; **ownership** is enforced separately per endpoint (the JWT `sub` must own the resource being touched, regardless of scope).

### Identifier patterns

| Resource | Format | Example |
|---|---|---|
| User | `U-<ulid>` | `U-01HZ8K...` |
| Session | `SES-<ulid>` | `SES-01HZ8K...` |
| Account | `ACC<six-or-more-digits>` (display); internal ULID | `ACC001`, `ACC100042` |
| Transaction | `TX-<ulid>` | `TX-01HZ8L...` |
| Journal entry | `JL-<ulid>` | `JL-01HZ8L...` |
| MFA factor | `MFA-<ulid>` | `MFA-01HZ8K...` |
| MFA verification | `VER-<ulid>` | `VER-01HZ8K...` |

ULIDs are sortable by time, which keeps `journal` indexes cache-friendly.

---

## 2. Mapping from the brief's example endpoints

The brief lists illustrative example endpoints (Part 4) using verb-in-URL style. We have refactored them to RESTful resource endpoints. The mapping is explicit so the evaluator can trace each requirement to its implementation.

| Brief's example | Canonical RESTful endpoint | Rationale |
|---|---|---|
| `POST /auth/register` | **`POST /v1/users`** | Registration creates a `User`. |
| `POST /auth/login` | **`POST /v1/sessions`** | A login creates a `Session`. |
| `GET /users/me` | **`GET /v1/users/me`** | Already noun-based — kept as-is. |
| `POST /accounts` | **`POST /v1/accounts`** | Already noun-based. |
| `GET /accounts/{id}` | **`GET /v1/accounts/{id}`** | Already noun-based. |
| `POST /transactions/transfer` | **`POST /v1/transactions`** with `{ "type": "TRANSFER", ... }` | A transfer creates a `Transaction`. The `type` discriminator extends to `REVERSAL`, `REFUND`, `FEE` without new endpoints. |
| `GET /transactions/history` | **`GET /v1/transactions`** | Listing the collection *is* the history. Filter via query params. |

No verb-style URLs are exposed. The example contracts are a faithful subset of the canonical model.

---

## 3. Error model

Every error response uses an extension of [**RFC 7807 — Problem Details**](https://datatracker.ietf.org/doc/html/rfc7807) (`Content-Type: application/problem+json`).

### Shape

```json
{
  "type":          "https://api.example.com/problems/insufficient-funds",
  "code":          "INSUFFICIENT_FUNDS",
  "title":         "Insufficient funds",
  "status":        422,
  "detail":        "Account ACC001 has balance 50, requested 100",
  "params": {
    "accountId":  "ACC001",
    "available":  50,
    "requested":  100,
    "currency":   "USD"
  },
  "instance":      "/v1/transactions",
  "correlationId": "01HZ8M...",
  "timestamp":     "2026-05-28T10:00:00Z",
  "errors": [
    {
      "field":   "amount",
      "code":    "EXCEEDS_BALANCE",
      "message": "Amount exceeds available balance",
      "params":  { "available": 50, "requested": 100 }
    }
  ]
}
```

### Field reference

| Field | Required | Audience | Purpose |
|---|---|---|---|
| `code` | **always** | Frontend / mobile client code | **Stable, locale-independent, SCREAMING_SNAKE_CASE enum.** This is the field clients switch on. It drives locale-bundle lookup (`messages.<locale>.<code>`), analytics, and business logic. **Never changes once shipped.** |
| `params` | when interpolation values exist | Frontend | Machine-readable values for substitution into the localised template. The FE *should not* parse `detail` to extract these. |
| `type` | always | Engineers, docs | Browsable URL pointing to the docs page describing this error. Stable. |
| `title` | always | Humans (fallback only) | Short English title. Used by the FE only when no locale bundle is loaded. |
| `detail` | always | Humans (fallback only) | English sentence with the values interpolated. For logs, support tools, and as an FE last-resort fallback. |
| `status` | always | Both | HTTP status code, mirrored from the response line for convenience. |
| `instance` | always | Engineers | The request URI that produced the error. |
| `correlationId` | always | Support / on-call | The single handle that ties the response to its trace + logs. |
| `timestamp` | always | Engineers | When the server produced the response. |
| `errors[]` | only for `400` | Frontend | Field-level validation failures. Each item carries `{ field, code, message, params }`. **Note:** the field-level prose key is `message` (not `detail`) — intentional, to match the form-field UX convention used by Stripe and most FE form libraries. Top-level `detail` and item-level `message` carry the same kind of value (localised English fallback) but live at different levels of the response. |

### How the frontend uses this

```js
// Pseudo-FE code
if (response.ok) return response.json();
const problem = await response.json();

// 1. Drive business flow off code
switch (problem.code) {
  case "STEP_UP_REQUIRED": return triggerMfaFlow();
  case "RATE_LIMITED":     return scheduleRetry(response.headers.get("Retry-After"));
  // ...
}

// 2. Render a localised message
const template = i18n.t(`errors.${problem.code}`, { defaultValue: problem.detail });
const message  = interpolate(template, problem.params);
toast.error(message);

// 3. For validation errors, mark each field
for (const e of problem.errors ?? []) {
  form.setFieldError(e.field, i18n.t(`errors.${e.code}`, { defaultValue: e.message }, e.params));
}
```

### `code` vs `type` — why both

| Field | When you use it |
|---|---|
| `code` | In code. Switch statements, locale-bundle keys, analytics events, error budgets. |
| `type` | In a browser. Click the URL to read the human-readable docs. |

They carry the same *meaning*, but `code` is optimised for machines and `type` for humans. Shipping only `type` (a URL) forces clients to parse the path and couples them to URL structure — a bad foundation. Shipping only `code` loses the discoverable, browsable docs link. Both is the right answer; the cost is two fields per error.

### HTTP code catalogue

| HTTP code | When | Retry-safe? |
|---|---|---|
| `200 OK` | Successful read; **idempotent replay** of a completed write | n/a |
| `201 Created` | New resource created. `Location:` header points to it | No (would be a 2nd attempt) |
| `202 Accepted` | Async work queued. We **do not** use this on `POST /transactions` — transfers are synchronous | n/a |
| `204 No Content` | Successful operation with no body (e.g. logout) | No |
| `400 Bad Request` | Malformed JSON; failed schema validation | No — fix request |
| `401 Unauthorized` | Missing/invalid/expired token | After re-auth |
| `403 Forbidden` | Authenticated but lacks permission | No |
| `404 Not Found` | Resource doesn't exist (or caller can't see it) | No |
| `409 Conflict` | Idempotency-key reuse with different payload; version conflict (optimistic locking); invalid state transition | No |
| `415 Unsupported Media Type` | Wrong `Content-Type` | No |
| `422 Unprocessable Entity` | Well-formed request that violates a business rule (insufficient funds, frozen account, currency mismatch) | No |
| `423 Locked` | Account locked by brute-force protection | Yes — after delay |
| `429 Too Many Requests` | Rate-limit exceeded. `Retry-After` header included | Yes — after delay |
| `500 Internal Server Error` | Unhandled exception. Alerts fire | Yes — with backoff |
| `502 Bad Gateway` | Downstream service returned malformed response | Yes — with backoff |
| `503 Service Unavailable` | Dependency outage, circuit open. `Retry-After` header included | Yes — after delay |
| `504 Gateway Timeout` | Downstream timed out | Yes — with backoff & idempotency key |

**4xx responses are deterministic failures — never retry blindly.** 5xx are recoverable; retry **with the same `Idempotency-Key`** to be safe.

### Problem catalogue (top-level `code` values)

The `code` enum is the contract — adding values is fine, renaming is **forbidden once published**. The current catalogue:

#### Generic

| `code` | `type` URL | Status | Meaning |
|---|---|---|---|
| `VALIDATION_FAILED` | `/problems/validation-failed` | 400 | Request body failed schema validation; `errors[]` lists field-level details |
| `MALFORMED_REQUEST` | `/problems/malformed-request` | 400 | JSON couldn't be parsed |
| `MISSING_IDEMPOTENCY_KEY` | `/problems/missing-idempotency-key` | 400 | A write endpoint was called without `Idempotency-Key` |
| `UNAUTHORIZED` | `/problems/unauthorized` | 401 | Token missing/invalid/expired |
| `INVALID_CREDENTIALS` | `/problems/invalid-credentials` | 401 | Wrong email/password — same response shape as account-not-found to avoid enumeration |
| `MFA_REQUIRED` | `/problems/mfa-required` | 401 | MFA enrolled but no `otp` supplied. `params.availableFactors` lists the enrolled factor types (`["TOTP"]` today, `["TOTP","WEBAUTHN"]` in future) so the FE knows which challenge UI to render. Clients **must** read `params.availableFactors` even when it's currently always `["TOTP"]` — this lets us add factor types without a breaking change. |
| `MFA_INVALID` | `/problems/mfa-invalid` | 401 on `POST /v1/sessions` (auth failure during login); **422** on `POST .../verifications` (business rule failure — the user is already authenticated) | OTP wrong. Different HTTP status by context; the `code` is the same so FE logic doesn't fork. |
| `STEP_UP_REQUIRED` | `/problems/step-up-required` | 401 | Operation requires fresher MFA; client must re-auth |
| `FORBIDDEN` | `/problems/forbidden` | 403 | Authenticated, not authorised |
| `RESOURCE_NOT_FOUND` | `/problems/resource-not-found` | 404 | Resource missing or not visible to the caller |
| `IDEMPOTENCY_KEY_CONFLICT` | `/problems/idempotency-key-conflict` | 409 | Same key, different payload |
| `IDEMPOTENCY_IN_PROGRESS` | `/problems/idempotency-in-progress` | 409 | Same key still processing |
| `VERSION_CONFLICT` | `/problems/version-conflict` | 409 | Optimistic-lock failure |
| `INVALID_STATE_TRANSITION` | `/problems/invalid-state-transition` | 409 | Target state cannot be reached from current state (e.g. unfreeze an already-active account) |
| `ACCOUNT_LOCKED` | `/problems/account-locked` | 423 | Brute-force lockout active; `Retry-After` set |
| `RATE_LIMITED` | `/problems/rate-limited` | 429 | Caller above rate limit |
| `INTERNAL` | `/problems/internal` | 500 | Caught at the top of the stack; `correlationId` is your only handle |
| `DEPENDENCY_UNAVAILABLE` | `/problems/dependency-unavailable` | 503 | Downstream sick (e.g. IdP, DB) |

#### Users

| `code` | `type` URL | Status | Meaning |
|---|---|---|---|
| `EMAIL_ALREADY_REGISTERED` | `/problems/email-already-registered` | 409 | Email is in use |
| `WEAK_PASSWORD` | `/problems/weak-password` | 400 | Password fails policy or is in HIBP list |

#### Accounts

| `code` | `type` URL | Status | Meaning |
|---|---|---|---|
| `ACCOUNT_NOT_FOUND` | `/problems/account-not-found` | 404 | Account doesn't exist (or not visible to caller) |
| `ACCOUNT_UNAVAILABLE` | `/problems/account-unavailable` | 422 | Source/destination is `FROZEN` or `CLOSED` |
| `OPERATOR_APPROVAL_REQUIRED` | `/problems/operator-approval-required` | 403 | Action requires operator role / dual control |

#### Transactions

| `code` | `type` URL | Status | Meaning |
|---|---|---|---|
| `INSUFFICIENT_FUNDS` | `/problems/insufficient-funds` | 422 | Source balance < amount |
| `CURRENCY_MISMATCH` | `/problems/currency-mismatch` | 422 | Account currency ≠ request currency, or source/destination currencies differ |
| `SELF_TRANSFER` | `/problems/self-transfer` | 422 | `sourceAccount == destinationAccount` |
| `LIMIT_EXCEEDED` | `/problems/limit-exceeded` | 422 | Per-day / per-tx limit hit |
| `ORIGINAL_TRANSACTION_NOT_REVERSIBLE` | `/problems/original-transaction-not-reversible` | 422 | The `correctsTransactionId` target is not in a state allowing reversal |

### Field-level error codes

`errors[].code` is also a closed enum. Initial catalogue:

| `code` | Meaning |
|---|---|
| `REQUIRED` | Field is missing or null |
| `INVALID_FORMAT` | Doesn't match the expected pattern (e.g. ISO 4217, E.164, RFC 5321) |
| `UNKNOWN_ENUM_VALUE` | Value isn't a known enum member |
| `OUT_OF_RANGE` | Value violates min/max |
| `TOO_LONG` / `TOO_SHORT` | String length violations |
| `MUST_BE_POSITIVE` | Numeric must be > 0 |
| `MUST_DIFFER` | Two fields that must not be equal are equal (e.g. source vs destination account) |
| `EXCEEDS_BALANCE` | Field is correct in shape but the value exceeds the available balance |
| `EXCEEDS_LIMIT` | Field violates a configured per-day/per-tx limit |

---

## 4. Idempotency

See [ADR-0002](decisions/0002-idempotency-and-exactly-once.md) for the full design. Client contract:

- Generate a **UUID v4** per logical user intent.
- Send it on every retry **of the same intent**.
- If you change the payload, generate a **new** key — re-using a key with a different payload returns `409 IDEMPOTENCY_KEY_CONFLICT`.
- Server retention: **24 hours**. After that, the same key is treated as new.
- Scope: per-user × per-endpoint. A `POST /transactions` key and a `PATCH /accounts/{id}` key don't collide even if equal.

Responses:

| Situation | Status | `code` |
|---|---|---|
| First request with key K | `201 Created` (or operation-appropriate) | — (success) |
| Retry with same K and same payload, original completed | `200 OK` with the **original response body** | — |
| Retry with same K and same payload, original still in flight | `409 Conflict` with `Retry-After` | `IDEMPOTENCY_IN_PROGRESS` |
| Retry with same K and **different** payload | `409 Conflict` | `IDEMPOTENCY_KEY_CONFLICT` |
| Key missing on a write | `400 Bad Request` | `MISSING_IDEMPOTENCY_KEY` |

---

## 5. Pagination, filtering, sorting

### Rule: every collection endpoint returns a paginated envelope

**Every endpoint that returns a collection of resources or rows MUST return the `{ data, page }` envelope below — never a bare JSON array at the top level, and never an unpaginated array inside a response.**

This rule applies to:

- Resource list endpoints (`GET /v1/accounts`, `GET /v1/transactions`, `GET /v1/sessions`, `GET /v1/journal-entries`, …).
- Report endpoints that return per-row breakdowns (e.g. trial balance's `byAccount`).
- Any future endpoint added to this API whose response would naturally be a JSON array.

It does **not** apply to short, fixed-cardinality arrays that are *part of a single resource* (e.g. `transaction.journalLineIds`, `problem.errors[]` in a validation response). Those are properties of one resource, not a collection.

**Why the rule is strict:**

- A top-level array gives the client no place to put pagination metadata, link headers, or additive metadata later — moving to an envelope is a *breaking change*.
- Inline arrays in reports lie about scale: today's 10-row response can be 10 000 rows next quarter, and the FE that did `response.byAccount.forEach(...)` will OOM or block the render thread.
- Uniformity reduces client-side adapter code: one parser handles every collection in the API.

### Envelope

```
GET /v1/transactions?limit=50&cursor=eyJpZCI6Ii4uLiJ9
```

```json
{
  "data": [ ... ],
  "page": {
    "nextCursor": "eyJpZCI6Ii4uLiJ9",
    "hasMore":    true,
    "limit":      50
  }
}
```

- `limit`: default **25**, max **100**.
- `cursor` is opaque base64-encoded; never parse on the client.
- **Cursor-based** because offset pagination is broken under concurrent inserts (a new row at position 0 silently shifts every page).
- `nextCursor` is `null` when `hasMore` is `false`. Clients must check `hasMore`, not `nextCursor !== null`, to decide whether to keep paging — that allows us to add a non-paginating "this was the last page" cursor in the future without breaking clients.

### Why cursor, not offset/limit (page + pageSize)

The familiar `?page=2&pageSize=25` pattern is **not** used in this API. The choice is deliberate, not a stylistic preference. The reasons specific to a payment platform:

| Concern | Cursor | Offset/limit |
|---|---|---|
| **Concurrent inserts** | Cursor encodes "where I was" (a key value). New rows inserted at the top do not shift the view; the user sees a consistent slice. | Page 2 of a list that grew by one row between requests **duplicates an item or skips one**. For a bank statement, this is a defect a support agent has to explain. |
| **Deep pagination performance** | Index range scan from a known key — **O(log N)**. Latency is flat from page 1 to page 1 000. | `MongoDB.skip(N)` walks and discards N documents — **O(N)**. Latency degrades sharply past a few hundred pages. |
| **Scale ceiling** | Stays correct at billions of rows (our Part 7 target on the `journal` collection). | Operationally untenable past tens of millions; requires lying about totals via caches. |
| **Audit correctness** | A journal-entry list cannot show the same entry twice. | Routinely shows the same entry twice under load. Not acceptable for a ledger. |

What we give up — and why we accept it:

| Cost of cursor | How we mitigate |
|---|---|
| Can't jump to "page 7 of 200" | We don't expose any UX that needs random-access pagination. Statement views scroll; admin views filter to small result sets. |
| No `totalCount` without a separate query | Counting at scale is expensive enough that we'd cache the answer anyway. If a screen genuinely needs a total, we'll add a dedicated `GET /v1/transactions/count` (cacheable, eventually consistent). |
| Less familiar to new FE developers | One paragraph in the FE onboarding doc. The FE never *constructs* a cursor — it round-trips the opaque `nextCursor` string back to the server. The contract is arguably simpler than `page + pageSize`. |

This is the same call Stripe, Square, PayPal, GitHub v4, and Twilio all made, for the same reasons. Offset/limit is the right pattern for CRUD admin tools and content APIs; it is not the right pattern for write-heavy financial data.

If a future use case genuinely requires offset/limit (e.g., an internal admin search across a bounded set), we will add it to **that specific endpoint** rather than retrofit the whole API. The §5 rule (`{ data, page }` envelope) is forward-compatible with adding `page.totalCount` or `page.pageNumber` later, additively.

### Filtering

Query parameters, never request bodies on GET:

```
GET /v1/transactions?from=2026-05-01&to=2026-05-28&status=COMPLETED&minAmount=1000
```

### Sorting

```
GET /v1/transactions?sort=-createdAt        # default: descending by time
```

`-` prefix = descending. Multiple sort keys comma-separated.

---

## 6. Users

### `POST /v1/users` — register

Create a new user account.

**Auth:** none.
**Idempotency-Key:** required.

**Request:**
```json
{
  "email":     "alice@example.com",
  "password":  "Correct horse battery staple!",
  "fullName":  "Alice Liddell",
  "phone":     "+447700900000"
}
```

| Field | Type | Constraints |
|---|---|---|
| `email` | string | RFC 5321; not already registered |
| `password` | string | length ≥ 12; checked against HIBP breached-password list |
| `fullName` | string | length 1–120 |
| `phone` | string | E.164 |

**`201 Created`** with `Location: /v1/users/U-...`:
```json
{
  "userId":    "U-01HZ...",
  "email":     "alice@example.com",
  "status":    "PENDING_VERIFICATION",
  "createdAt": "2026-05-28T10:00:00Z"
}
```

**Errors:**

| Status | `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `WEAK_PASSWORD` |
| 409 | `EMAIL_ALREADY_REGISTERED` |

---

### `GET /v1/users/me` — current user profile

**Auth:** required, scope `accounts:read` or implicit.

**`200 OK`:**
```json
{
  "userId":     "U-01HZ...",
  "email":      "alice@example.com",
  "fullName":   "Alice Liddell",
  "phone":      "+447700900000",
  "status":     "ACTIVE",
  "mfaEnabled": true,
  "createdAt":  "2026-05-28T10:00:00Z"
}
```

---

### `PATCH /v1/users/me` — update profile

**Auth:** required.
**Idempotency-Key:** required.

**Request (partial — any subset of allowed fields):**
```json
{ "fullName": "Alice L." }
```

**`200 OK`** — returns the updated user. Email and password changes go through a separate, higher-friction flow (out of scope for this submission).

**Errors:**

| Status | `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `MISSING_IDEMPOTENCY_KEY` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` (attempting to PATCH a field that isn't user-editable, e.g. `email`) |
| 409 | `IDEMPOTENCY_KEY_CONFLICT`, `VERSION_CONFLICT` |

---

## 7. Sessions & tokens

We separate two concerns:
- **Sessions** model the user-facing login lifecycle (create on login, destroy on logout, list active sessions).
- **OAuth2 tokens** are the access/refresh-token machinery, using the **standard OAuth2 token endpoint**. We don't pretend OAuth2 is bespoke REST — we expose it for what it is.

### `POST /v1/sessions` — login

Create a new session and return its tokens.

**Auth:** none.
**Idempotency-Key:** not required (the credentials *are* the de-duplication key from the user's perspective).

**Request:**
```json
{
  "email":    "alice@example.com",
  "password": "Correct horse battery staple!",
  "otp":      "123456"
}
```

`otp` is required if the user has MFA enrolled.

**`201 Created`** with `Location: /v1/sessions/SES-...`:
```json
{
  "sessionId":        "SES-01HZ...",
  "userId":           "U-01HZ...",
  "accessToken":      "eyJhbGciOi...",
  "refreshToken":     "eyJhbGciOi...",
  "tokenType":        "Bearer",
  "expiresIn":        900,
  "refreshExpiresIn": 86400,
  "scope":            "accounts:read transactions:write",
  "createdAt":        "2026-05-28T10:00:00Z"
}
```

**Errors:**

| Status | `code` |
|---|---|
| 400 | `VALIDATION_FAILED` |
| 401 | `INVALID_CREDENTIALS`, `MFA_REQUIRED`, `MFA_INVALID` |
| 423 | `ACCOUNT_LOCKED` (with `Retry-After`) |
| 429 | `RATE_LIMITED` |

---

### `DELETE /v1/sessions/current` — logout

Invalidate the caller's current session.

**Auth:** required.
**Idempotency-Key:** required.

**`204 No Content`.**

The access token is added to the gateway deny-list until its natural expiry; the refresh token is revoked at Keycloak.

---

### `GET /v1/sessions` — list active sessions

**Auth:** required.

Returns the caller's currently active sessions across all devices. Useful for the "sign out everywhere" UX.

**`200 OK`:**
```json
{
  "data": [
    {
      "sessionId":  "SES-01HZ...",
      "current":    true,
      "deviceLabel":"iPhone 15 Pro (Safari)",
      "ipApprox":   "203.0.113.0/24",
      "createdAt":  "2026-05-28T10:00:00Z",
      "lastSeenAt": "2026-05-28T10:00:00Z"
    }
  ],
  "page": { "nextCursor": null, "hasMore": false, "limit": 25 }
}
```

### `DELETE /v1/sessions/{id}` — revoke a specific session

**Auth:** required; caller must own the session, or hold admin role.
**Idempotency-Key:** required.

**`204 No Content`.**

---

### `POST /v1/oauth/token` — OAuth2 token endpoint

The standard OAuth2 token endpoint. Supports `refresh_token` grant for token rotation; `password` grant is **not** exposed (use `POST /v1/sessions` instead, which gives us the room to handle MFA, lockout, and session creation in one place).

**Content-Type:** `application/x-www-form-urlencoded` (per OAuth2 spec).

**Request (refresh):**
```
grant_type=refresh_token&refresh_token=eyJhbGciOi...
```

**`200 OK`:**
```json
{
  "access_token":      "eyJhbGciOi...",
  "refresh_token":     "eyJhbGciOi...",
  "token_type":        "Bearer",
  "expires_in":        900,
  "refresh_expires_in": 86400,
  "scope":             "accounts:read transactions:write"
}
```

(Note: snake_case fields — this is the OAuth2 spec, not our naming convention.)

**Errors** follow the OAuth2 error format (`{"error":"invalid_grant","error_description":"..."}`) **plus** our `code` field for FE convenience:

```json
{
  "error":             "invalid_grant",
  "error_description": "Refresh token has been revoked or rotated",
  "code":              "REFRESH_TOKEN_REVOKED",
  "correlationId":     "..."
}
```

Re-using an already-rotated refresh token triggers the **refresh-token theft** alarm in Keycloak (see [ADR-0003](decisions/0003-auth-stack.md)) — *all* sessions for that user are invalidated.

---

## 8. MFA factors

### `GET /v1/users/me/mfa-factors` — list enrolled factors

**Auth:** required.

**`200 OK`:**
```json
{
  "data": [
    {
      "factorId":    "MFA-01HZ...",
      "type":        "TOTP",
      "status":      "ACTIVE",
      "createdAt":   "2026-05-28T10:00:00Z",
      "lastUsedAt":  "2026-05-28T10:05:00Z"
    }
  ],
  "page": { "nextCursor": null, "hasMore": false, "limit": 25 }
}
```

Used by the FE to render the "Two-factor authentication" settings screen. Never includes the TOTP `secret` — that's returned only at enrolment time.

---

### `POST /v1/users/me/mfa-factors` — enrol a new factor

**Auth:** required.
**Idempotency-Key:** required.

**Request:**
```json
{ "type": "TOTP" }
```

**`201 Created`** with `Location: /v1/users/me/mfa-factors/MFA-...`:
```json
{
  "factorId":   "MFA-01HZ...",
  "type":       "TOTP",
  "status":     "PENDING_VERIFICATION",
  "secret":     "JBSWY3DPEHPK3PXP",
  "qrCodeUrl":  "otpauth://totp/...",
  "createdAt":  "2026-05-28T10:00:00Z"
}
```

The factor isn't active until verified.

### `POST /v1/users/me/mfa-factors/{factorId}/verifications` — verify

**Auth:** required.
**Idempotency-Key:** required.

**Request:**
```json
{ "otp": "123456" }
```

**`201 Created`:**
```json
{
  "verificationId": "VER-01HZ...",
  "factorId":       "MFA-01HZ...",
  "status":         "SUCCEEDED",
  "verifiedAt":     "2026-05-28T10:00:00Z"
}
```

On success the parent factor transitions to `status: ACTIVE`.

**Errors:**

| Status | `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `MISSING_IDEMPOTENCY_KEY` |
| 401 | `UNAUTHORIZED` |
| 404 | `RESOURCE_NOT_FOUND` (no such factor for this user) |
| 422 | `MFA_INVALID` (OTP wrong; the user *is* authenticated — this is a business rule failure, not an auth failure, so it's 422 not 401) |
| 429 | `RATE_LIMITED` |

### `DELETE /v1/users/me/mfa-factors/{factorId}` — remove a factor

Requires step-up auth.

**`204 No Content`** on success.

**Errors:**

| Status | `code` |
|---|---|
| 401 | `UNAUTHORIZED`, `STEP_UP_REQUIRED` |
| 404 | `RESOURCE_NOT_FOUND` |
| 409 | `INVALID_STATE_TRANSITION` (cannot remove the only enrolled factor if the user has the MFA-required policy) |

---

## 9. Accounts

### `POST /v1/accounts` — open

**Auth:** required, scope `accounts:write`.
**Idempotency-Key:** required.

**Request:**
```json
{
  "currency": "USD",
  "type":     "CHECKING",
  "label":    "Daily spending"
}
```

| Field | Type | Constraints |
|---|---|---|
| `currency` | string | ISO 4217 |
| `type` | string | `CHECKING` / `SAVINGS` |
| `label` | string | optional, ≤ 60 chars |

**`201 Created`** with `Location: /v1/accounts/ACC100042`:
```json
{
  "accountId":   "ACC100042",
  "ownerUserId": "U-01HZ...",
  "currency":    "USD",
  "type":        "CHECKING",
  "label":       "Daily spending",
  "balance":     0,
  "status":      "ACTIVE",
  "version":     1,
  "createdAt":   "2026-05-28T10:00:00Z",
  "updatedAt":   "2026-05-28T10:00:00Z"
}
```

**Errors:**

| Status | `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `MISSING_IDEMPOTENCY_KEY` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` |
| 409 | `IDEMPOTENCY_KEY_CONFLICT`, `IDEMPOTENCY_IN_PROGRESS` |

---

### `GET /v1/accounts/{accountId}` — read one

**Auth:** required. Caller must own the account or hold an admin role.

**`200 OK`:**
```json
{
  "accountId":   "ACC100042",
  "ownerUserId": "U-01HZ...",
  "currency":    "USD",
  "type":        "CHECKING",
  "label":       "Daily spending",
  "balance":     12345,
  "status":      "ACTIVE",
  "version":     42,
  "createdAt":   "2026-05-28T10:00:00Z",
  "updatedAt":   "2026-05-28T10:00:00Z"
}
```

**Errors:** `401 UNAUTHORIZED`, `403 FORBIDDEN`, `404 RESOURCE_NOT_FOUND` (we do **not** distinguish "doesn't exist" from "not visible to caller", to avoid enumeration).

---

### `GET /v1/accounts` — list mine

Filtered to caller's `userId` automatically.

**Query:** `status`, `currency`, pagination.

**`200 OK`:**
```json
{
  "data": [ { "accountId": "ACC100042", ... } ],
  "page": { "nextCursor": "...", "hasMore": false, "limit": 25 }
}
```

---

### `PATCH /v1/accounts/{accountId}` — update (state transitions, label)

**Auth:** required.
**Idempotency-Key:** required.
**`If-Match: <version>`:** optional but **strongly recommended**. When supplied, the server returns `409 VERSION_CONFLICT` if the supplied version doesn't match the current one. Without it, the last write wins and a UI that hasn't refreshed could silently clobber another tab's change.

This is the single endpoint for **status transitions** (freeze, unfreeze, close) and **label edits**. The server enforces allowed transitions and required roles per target status. The body must contain a subset of `{status, reason, label}` — any other field is rejected with `403 FORBIDDEN`.

**Request (freeze):**
```json
{
  "status": "FROZEN",
  "reason": "USER_REQUESTED"
}
```

**Request (unfreeze):**
```json
{
  "status": "ACTIVE",
  "reason": "USER_REQUEST_CLEARED"
}
```

**Request (relabel):**
```json
{ "label": "Holiday fund" }
```

**Permissions per field:**

| Field | Who can change it |
|---|---|
| `label` | **Owner only.** Operators cannot rename customer accounts — preserves trust and prevents silent edits during incidents. |
| `status` | Per the transition matrix below. |
| `reason` | Required whenever `status` is supplied. Free-text for operator reasons, enum for user reasons. |

**Allowed status transitions:**

| From \ To | `ACTIVE` | `FROZEN` | `CLOSED` |
|---|---|---|---|
| `ACTIVE` | — | owner or operator | owner (only if `balance == 0`) |
| `FROZEN` | **operator only** (dual control if `reason ∈ {FRAUD_SUSPECTED, COMPLIANCE_HOLD}`) | — | operator |
| `CLOSED` | ❌ | ❌ | terminal |

`reason` enum: `USER_REQUESTED`, `USER_REQUEST_CLEARED`, `KYC_PENDING`, `FRAUD_SUSPECTED`, `COMPLIANCE_HOLD`, `CUSTOMER_CLOSED`.

**`200 OK`:** returns the updated account (including the incremented `version`).

**Errors:**

| Status | `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `MISSING_IDEMPOTENCY_KEY` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN`, `OPERATOR_APPROVAL_REQUIRED` |
| 404 | `ACCOUNT_NOT_FOUND` |
| 409 | `INVALID_STATE_TRANSITION`, `VERSION_CONFLICT`, `IDEMPOTENCY_KEY_CONFLICT` |

---

### `GET /v1/accounts/{accountId}/balance` — lightweight balance read

Reads with `readConcern: majority` to give read-your-writes on the write path.

**`200 OK`:**
```json
{ "accountId": "ACC100042", "balance": 12345, "currency": "USD", "asOf": "2026-05-28T10:00:00Z" }
```

---

## 10. Transactions

This is the resource implemented in code (Part 5 of the brief).

A **transaction** is the umbrella resource for any double-entry movement. The `type` field discriminates:

| `type` | Creates | Operator role required |
|---|---|---|
| `TRANSFER` | A normal user-to-user (or user-to-self) money movement | No |
| `REVERSAL` | A compensating reversal of an existing transaction | **Yes** (+ dual control) |
| `FEE` | A platform-debited fee | **Yes** (system context) |
| `REFUND` | A refund initiated by the merchant counterparty | merchant scope |

This submission implements `TRANSFER` end-to-end and exposes `REVERSAL` as an operator endpoint (the Part 10 incident-correction path). `FEE` / `REFUND` are documented for completeness.

### `POST /v1/transactions` — create

**Auth:** required, scope `transactions:write` (for `TRANSFER`) or `transactions:reverse` (for `REVERSAL`).
**Idempotency-Key:** **required**.
**Authorization:** for `TRANSFER`, the JWT `sub` must own `sourceAccount`. For `REVERSAL`, operator role + dual control.

#### Request — `TRANSFER`
```json
{
  "type":               "TRANSFER",
  "sourceAccount":      "ACC001",
  "destinationAccount": "ACC002",
  "amount":             100,
  "currency":           "USD",
  "description":        "Coffee"
}
```

| Field | Type | Constraints |
|---|---|---|
| `type` | string enum | `"TRANSFER"` |
| `sourceAccount` | string | `REQUIRED`; caller must own it |
| `destinationAccount` | string | `REQUIRED`; must differ from `sourceAccount` |
| `amount` | integer | `MUST_BE_POSITIVE`; minor units; ≤ daily limit |
| `currency` | string | ISO 4217; must match both accounts |
| `description` | string | optional, ≤ 140 chars |

#### Request — `REVERSAL` (operator-only)
```json
{
  "type":                  "REVERSAL",
  "correctsTransactionId": "TX-01HZ...orig",
  "reason":                "duplicate_post_incident_2026_05_28",
  "approverId":            "U-operator-2"
}
```

| Field | Type | Constraints |
|---|---|---|
| `type` | string enum | `"REVERSAL"` |
| `correctsTransactionId` | string | must reference a `COMPLETED` transaction |
| `reason` | string | free text, audited |
| `approverId` | string | second operator (dual control); must differ from caller |

#### `201 Created`

```json
{
  "transactionId":         "TX-01HZ...",
  "type":                  "TRANSFER",
  "status":                "COMPLETED",
  "sourceAccount":         "ACC001",
  "destinationAccount":    "ACC002",
  "amount":                100,
  "currency":              "USD",
  "description":           "Coffee",
  "correctsTransactionId": null,
  "createdAt":             "2026-05-28T10:00:00Z",
  "completedAt":           "2026-05-28T10:00:00Z"
}
```

`Location: /v1/transactions/TX-01HZ...` header set.

#### Errors (full table for `TRANSFER`)

| Status | `code` | Cause |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Field missing / not positive / bad currency code |
| 400 | `MISSING_IDEMPOTENCY_KEY` | Header missing |
| 401 | `UNAUTHORIZED` | Token missing/invalid/expired |
| 401 | `STEP_UP_REQUIRED` | Amount above threshold; needs fresh MFA |
| 403 | `FORBIDDEN` | Caller does not own `sourceAccount` |
| 404 | `RESOURCE_NOT_FOUND` | Either account doesn't exist (or caller can't see source) |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | Key reused with different payload |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | Same key still processing; client should poll the resource |
| 422 | `SELF_TRANSFER` | `sourceAccount == destinationAccount` |
| 422 | `ACCOUNT_UNAVAILABLE` | Source/destination is `FROZEN` or `CLOSED` |
| 422 | `CURRENCY_MISMATCH` | Account currency ≠ request currency, or accounts differ |
| 422 | `INSUFFICIENT_FUNDS` | Source balance < amount |
| 422 | `LIMIT_EXCEEDED` | Per-day / per-tx limit hit |
| 429 | `RATE_LIMITED` | Caller above rate limit |
| 500 | `INTERNAL` | Unhandled |
| 503 | `DEPENDENCY_UNAVAILABLE` | Mongo unavailable; circuit open |

For `REVERSAL`, add: `403 OPERATOR_APPROVAL_REQUIRED`, `422 ORIGINAL_TRANSACTION_NOT_REVERSIBLE`.

#### Behaviour notes

- **Synchronous.** The response reflects the final state. We do not return `202`.
- The response is the **canonical** record — clients should treat retries returning `200 OK` with the same body as success.
- On success, the server publishes `TransactionCompletedEvent` via the transactional outbox after commit. See [ADR-0004](decisions/0004-event-schema-and-evolution.md).

---

### `GET /v1/transactions/{transactionId}` — read one

**Auth:** required. Caller must own source or destination, or hold admin role.

**`200 OK`:**
```json
{
  "transactionId":         "TX-01HZ...",
  "type":                  "TRANSFER",
  "idempotencyKey":        "...",
  "status":                "COMPLETED",
  "sourceAccount":         "ACC001",
  "destinationAccount":    "ACC002",
  "amount":                100,
  "currency":              "USD",
  "description":           "Coffee",
  "journalLineIds":        ["JL-01HZ...", "JL-01HZ..."],
  "correctsTransactionId": null,
  "createdAt":             "2026-05-28T10:00:00Z",
  "completedAt":           "2026-05-28T10:00:00Z"
}
```

`status ∈ {PENDING, COMPLETED, FAILED, REVERSED}`.

---

### `GET /v1/transactions` — list

The caller's transactions across all their accounts. **This is the "history" endpoint** — the brief's `GET /transactions/history` maps directly here.

**Auth:** required.
**Query:** `accountId` (optional), `type` (`TRANSFER`/`REVERSAL`/...), `status`, `from`, `to`, `minAmount`, `maxAmount`, pagination.

**`200 OK`:** standard `{ data, page }` envelope.

Example:
```
GET /v1/transactions?accountId=ACC001&status=COMPLETED&from=2026-05-01&sort=-createdAt&limit=50
```

---

## 11. Journal & reports (Accounting Service)

Read-model projection of the journal. **No mutations** via this API — the journal is owned by Transaction Service and only altered via `POST /v1/transactions`.

### `GET /v1/journal-entries` — list

**Auth:** required; **auditor** or **operator** role.

**Query:** `account`, `transactionId`, `from`, `to`, `side` (`DEBIT`/`CREDIT`), pagination.

**`200 OK`:**
```json
{
  "data": [
    {
      "journalEntryId": "JL-01HZ...",
      "transactionId":  "TX-01HZ...",
      "account":        "ACC001",
      "side":           "DEBIT",
      "amount":         100,
      "currency":       "USD",
      "postedAt":       "2026-05-28T10:00:00Z"
    }
  ],
  "page": { "nextCursor": "...", "hasMore": false, "limit": 25 }
}
```

### `GET /v1/reports/trial-balance` — trial balance summary

Returns the system-wide totals as of a point in time. The per-account breakdown is fetched separately via the paginated endpoint below.

**Auth:** required; **auditor** role.

**Query:** `asOf` (default: now), `currency`.

**`200 OK`:**
```json
{
  "asOf":     "2026-05-28T10:00:00Z",
  "currency": "USD",
  "totals": {
    "debits":  123456789,
    "credits": 123456789,
    "delta":   0
  }
}
```

`totals.delta` **must** be 0 — if it isn't, that is a P1 reconciliation incident (see Part 10 of `ARCHITECTURE.md`).

### `GET /v1/reports/trial-balance/by-account` — paginated per-account breakdown

**Auth:** required; **auditor** role.

**Query:** `asOf` (default: now), `currency`, `minImbalance` (optional, filter rows where `|balance| ≥ N`), pagination params.

**`200 OK`:**
```json
{
  "data": [
    { "account": "ACC001", "debits": 100, "credits": 0,   "balance": -100, "currency": "USD" },
    { "account": "ACC002", "debits": 0,   "credits": 100, "balance":  100, "currency": "USD" }
  ],
  "page": { "nextCursor": "...", "hasMore": false, "limit": 25 }
}
```

This is paginated because real trial-balance reports cover every account in the ledger — potentially millions of rows. The summary endpoint above is the cheap call; this one is the drill-down.

---

## 12. Health, readiness & operational endpoints

All services expose these on a separate management port (not on the public gateway):

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Liveness + readiness composite |
| `GET /actuator/health/liveness` | Is the process up? |
| `GET /actuator/health/readiness` | Can it serve traffic? (DB, Kafka, schema registry) |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |
| `GET /actuator/info` | Build info, git SHA, service version |

These are Spring Boot Actuator conventions. We don't refactor them into resource-style URLs because they're a separate, operator-facing API governed by Spring Boot's contract.

**Readiness is strict** (DB pool healthy, Kafka producer alive). **Liveness is lenient** (process up) — so transient DB outages don't trigger pod restarts and amplify the problem.

---

## 13. Versioning & deprecation policy

- **Additive changes** (new optional fields, new endpoints, new enum values where the client treats unknowns as passthrough) → **no version bump**.
- **Breaking changes** → new major version at a new URL prefix (`/v2/`). Both versions run side by side for ≥ 6 months. Clients get `Deprecation:` and `Sunset:` headers on the old version.
- **Enum extension** — clients **must** treat unknown enum values (including new `code` values) as a passthrough.
- **Error `code` values are stable.** Once published, a `code` is **never** renamed and **never** changes meaning. New codes can be added; existing ones cannot move.
- **Error `type` URLs are stable** under the same rules.

---

## Appendix A — DTO ↔ Entity separation

Every endpoint uses an explicit DTO record (`CreateTransactionRequest`, `TransactionResponse`, `AccountView`, …) decoupled from the persistence entity. Reasons:

- **Persistence shape ≠ wire shape.** The `accounts` document has `version`, `updatedAt`, internal `ownerUserId`; the response should not blindly expose all of those.
- **Validation belongs on the DTO**, not on the entity. Jakarta Bean Validation annotations live on the record.
- **Refactoring storage does not break the API.**
- **Read DTOs and write DTOs differ** — a write doesn't include `createdAt`, a read doesn't include `password`.

We use **Java records** (Java 16+) — immutable, terse, built-in `equals`/`hashCode`. Mapping uses hand-written mappers on the hot path; **MapStruct** for boilerplate-heavy admin views.

---

## Appendix B — cURL examples

A full happy-path flow:

```bash
# 1. Register a user
curl -X POST https://api.example.com/v1/users \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 11111111-1111-4111-8111-111111111111' \
  -d '{"email":"alice@example.com","password":"Correct horse battery staple!","fullName":"Alice","phone":"+447700900000"}'
# → 201 Created
# → Location: /v1/users/U-01HZ...

# 2. Log in (create a session)
curl -X POST https://api.example.com/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"...","otp":"123456"}'
# → 201 Created  { "accessToken": "...", ... }

# 3. Open an account
curl -X POST https://api.example.com/v1/accounts \
  -H 'Authorization: Bearer eyJhbGciOi...' \
  -H 'Idempotency-Key: 22222222-...' \
  -H 'Content-Type: application/json' \
  -d '{"currency":"USD","type":"CHECKING","label":"Daily"}'
# → 201 Created

# 4. Transfer
curl -X POST https://api.example.com/v1/transactions \
  -H 'Authorization: Bearer eyJhbGciOi...' \
  -H 'Idempotency-Key: 33333333-...' \
  -H 'Content-Type: application/json' \
  -d '{
    "type":"TRANSFER",
    "sourceAccount":"ACC001",
    "destinationAccount":"ACC002",
    "amount":100,
    "currency":"USD",
    "description":"Coffee"
  }'
# → 201 Created
# → Location: /v1/transactions/TX-01HZ...

# 5. Retry the same request (network blip) — same Idempotency-Key
# → 200 OK with the original response body

# 6. Refresh access token
curl -X POST https://api.example.com/v1/oauth/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=refresh_token&refresh_token=eyJhbGciOi...'
# → 200 OK  { "access_token": "...", "refresh_token": "...", ... }

# 7. Log out
curl -X DELETE https://api.example.com/v1/sessions/current \
  -H 'Authorization: Bearer eyJhbGciOi...' \
  -H 'Idempotency-Key: 44444444-...'
# → 204 No Content
```

A failed transfer:

```bash
curl -i -X POST https://api.example.com/v1/transactions \
  -H 'Authorization: Bearer eyJhbGciOi...' \
  -H 'Idempotency-Key: 55555555-...' \
  -H 'Accept-Language: fr-FR' \
  -H 'Content-Type: application/json' \
  -d '{
    "type":"TRANSFER",
    "sourceAccount":"ACC001",
    "destinationAccount":"ACC002",
    "amount":99999999,
    "currency":"USD"
  }'

# HTTP/2 422
# Content-Type: application/problem+json
# X-Correlation-Id: 01HZ8M...
#
# {
#   "type":          "https://api.example.com/problems/insufficient-funds",
#   "code":          "INSUFFICIENT_FUNDS",
#   "title":         "Solde insuffisant",
#   "status":        422,
#   "detail":        "Le compte ACC001 a un solde de 123,45 USD ; 999999,99 demandé.",
#   "params": {
#     "accountId":  "ACC001",
#     "available":  12345,
#     "requested":  99999999,
#     "currency":   "USD"
#   },
#   "instance":      "/v1/transactions",
#   "correlationId": "01HZ8M...",
#   "timestamp":     "2026-05-28T10:00:00Z"
# }
```
