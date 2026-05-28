# ADR-0006 — MFA out of scope for the technical-test deliverable

- **Status:** Accepted
- **Date:** 2026-05-28
- **Deciders:** CTO candidate response
- **Related:** [ADR-0003](0003-auth-stack.md), [`../TECHNICAL TEST -CTO - English.docx`](../TECHNICAL%20TEST%20-CTO%20-%20English.docx)

## Context

[ADR-0003](0003-auth-stack.md) describes the **production** authentication stack and includes mandatory TOTP MFA above a value threshold plus step-up authentication for high-value transfers. That ADR documents the target design, not the test deliverable.

The technical-test brief (`docs/TECHNICAL TEST -CTO - English.docx`) defines the Auth Service surface as:

- `POST /auth/register`
- `POST /auth/login`
- `GET /users/me`

…with the keywords **Authentication**, **Authorization**, **Keycloak**, **OAuth2**. It does **not** mention MFA, 2FA, TOTP, OTP, step-up, or `acr` anywhere.

We need an explicit scoping decision so the implementation matches the brief and reviewer expectations.

## Decision

For the test deliverable, **MFA is out of scope**. The `auth-service` ships with basic username/password login backed by Keycloak, issuing standard OAuth 2.0 / OIDC JWTs (RS256). No TOTP enrolment, no recovery codes, no step-up flow, no `acr`-claim enforcement at the gateway.

Concretely:

- Keycloak realm is imported on `docker-compose up` with the password policy enabled but **MFA flows disabled**.
- `/auth/register` creates the user in Keycloak with `emailVerified=true` (no email-verification round-trip either — also out of scope for the test).
- `/auth/login` exchanges credentials for a JWT via Keycloak's token endpoint.
- `/users/me` validates the JWT and returns the profile.
- Authorization is scope-based at the gateway plus ownership checks in services, exactly as in ADR-0003 — **only the MFA layer is removed**.

## Why

- **Match the brief.** The exercise asks for what is documented; adding MFA is unrequested scope and risks looking like gold-plating rather than judgement.
- **Reviewer signal.** Demonstrating that we read the brief carefully and scoped accordingly is itself part of the test.
- **Production design is not lost.** ADR-0003 still documents the target MFA design; this ADR records *why the test deliverable diverges from it*. A reviewer who asks "where's MFA?" gets a one-line answer plus a pointer to the production design.

## Consequences

- ADR-0003 remains the **production** reference. The sections on TOTP enforcement, step-up, recovery codes, and the `acr` claim describe the target system, not the shipped test.
- The Keycloak realm export checked into the repo has MFA flows **disabled** in the browser flow. Re-enabling is a config flip, not a code change — the resource-server validation path is unchanged.
- Tests do not need to mint tokens with `acr` claims or simulate TOTP devices.
- If the reviewer specifically asks for MFA during follow-up, enabling it is: turn on the Keycloak OTP required action + add the step-up enforcement filter at the gateway. Time-boxed at ~half a day.

## Considered alternatives

### Alt — Ship MFA anyway because ADR-0003 specifies it
- ❌ Adds scope the brief did not ask for; the test is judged on fit-to-brief plus judgement, not feature count.
- ❌ Increases the surface area to demo and test for no marginal credit.

### Alt — Silently drop MFA without an ADR
- ❌ Leaves an unexplained gap between ADR-0003 and the running code. A reviewer reading both would reasonably flag it as an oversight.

## References

- [ADR-0003 — Authentication & authorization stack](0003-auth-stack.md) (production design, MFA included)
- `docs/TECHNICAL TEST -CTO - English.docx` — brief; no MFA mention
