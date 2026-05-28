#!/usr/bin/env bash
# Seed demo users with distinct realm roles so the platform-ui shows role-based views.
#
# Users are created through the PUBLIC register API (POST /v1/users), so they exist in BOTH
# Keycloak and the app's Mongo store with the correct keycloakSub linkage — unlike the realm
# export's seeded users (alice/operator1/auditor1), which exist only in Keycloak and therefore
# cannot log in to the app. Realm roles are then assigned via the Keycloak admin API.
#
# Idempotent: re-running is safe (register 409 = already present; role assignment is a no-op).
#
# Usage (host, stack already up):   ./scripts/seed-users.sh
# Runs automatically as the compose `seed-users` one-shot on `docker compose up`.
#
# Requires: curl, jq.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"     # gateway
KC_URL="${KC_URL:-http://localhost:8090}"         # keycloak
REALM="${REALM:-fintech}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin}"
SEED_PASSWORD="${SEED_PASSWORD:-DemoP@ssw0rd1!}"

# email | full name | space-separated realm roles
USERS="\
demo@fintech.local|Demo User|user
operator@fintech.local|Demo Operator|user operator
auditor@fintech.local|Demo Auditor|user auditor"

log() { printf '%s\n' "$*"; }

admin_token() {
  curl -fsS -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
    -d "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASSWORD" \
    | jq -r '.access_token // empty'
}

log "Waiting for Keycloak at $KC_URL ..."
ADMIN_TOKEN=""
for _ in $(seq 1 60); do
  ADMIN_TOKEN="$(admin_token 2>/dev/null || true)"
  [ -n "$ADMIN_TOKEN" ] && break
  sleep 2
done
[ -n "$ADMIN_TOKEN" ] || { log "ERROR: could not obtain Keycloak admin token"; exit 1; }

assign_roles() {
  local email="$1"; shift
  local uid
  uid="$(curl -fsS "$KC_URL/admin/realms/$REALM/users?email=$email&exact=true" \
        -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.[0].id // empty')"
  if [ -z "$uid" ]; then log "  ! $email not found in Keycloak; skipping role assignment"; return; fi
  local reps="[]"
  for role in "$@"; do
    local rep
    rep="$(curl -fsS "$KC_URL/admin/realms/$REALM/roles/$role" \
          -H "Authorization: Bearer $ADMIN_TOKEN" | jq -c '{id, name}')"
    reps="$(printf '%s' "$reps" | jq -c ". + [$rep]")"
  done
  curl -fsS -X POST "$KC_URL/admin/realms/$REALM/users/$uid/role-mappings/realm" \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "$reps" >/dev/null
  log "  roles [$*] -> $email"
}

log "Seeding users via $BASE_URL ..."
printf '%s\n' "$USERS" | while IFS='|' read -r email full_name roles; do
  [ -z "$email" ] && continue
  # -f is intentionally omitted so a 409 (already registered) does not abort the run.
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/v1/users" \
        -H "Content-Type: application/json" -H "Idempotency-Key: seed-$email" \
        -d "{\"email\":\"$email\",\"password\":\"$SEED_PASSWORD\",\"fullName\":\"$full_name\",\"phone\":\"+447700900000\"}")"
  log "register $email -> HTTP $code"
  assign_roles "$email" $roles
done

log ""
log "Seed complete. Sign in at the UI (password: $SEED_PASSWORD):"
log "  demo@fintech.local      (user)     — Accounts, Transactions"
log "  operator@fintech.local  (operator) — + Accounting (journal, chart of accounts)"
log "  auditor@fintech.local   (auditor)  — + Accounting reports / trial balance"
