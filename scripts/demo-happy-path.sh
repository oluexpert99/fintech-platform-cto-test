#!/usr/bin/env bash
# End-to-end happy-path demo against a running docker-compose.
#
# Steps:
#   1. Register a new user
#   2. Log in (returns access + refresh tokens)
#   3. Open two accounts
#   4. Transfer between them
#   5. Retry the same transfer with the same Idempotency-Key (must return original)
#   6. Refresh the access token
#   7. Log out
#
# Usage:  ./scripts/demo-happy-path.sh
# Requires: curl, jq, uuidgen

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-demo-$(date +%s)@example.com}"
PASSWORD="${PASSWORD:-DemoP@ssw0rd123!}"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }

require() {
    command -v "$1" >/dev/null 2>&1 || { red "Missing dependency: $1"; exit 1; }
}
require curl
require jq
require uuidgen

# ---- 1. Register ----
bold "1. POST /v1/users  (register)"
REGISTER_KEY=$(uuidgen)
REGISTER_RESP=$(curl -fs -X POST "$BASE_URL/v1/users" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $REGISTER_KEY" \
    -d "{
      \"email\":\"$EMAIL\",
      \"password\":\"$PASSWORD\",
      \"fullName\":\"Demo User\",
      \"phone\":\"+447700900099\"
    }")
USER_ID=$(echo "$REGISTER_RESP" | jq -r .userId)
green "registered userId=$USER_ID email=$EMAIL"

# ---- 2. Login ----
bold "2. POST /v1/sessions  (login)"
LOGIN_RESP=$(curl -fs -X POST "$BASE_URL/v1/sessions" \
    -H "Content-Type: application/json" \
    -d "{
      \"email\":\"$EMAIL\",
      \"password\":\"$PASSWORD\"
    }")
ACCESS_TOKEN=$(echo "$LOGIN_RESP" | jq -r .accessToken)
REFRESH_TOKEN=$(echo "$LOGIN_RESP" | jq -r .refreshToken)
SESSION_ID=$(echo "$LOGIN_RESP" | jq -r .sessionId)
green "session=$SESSION_ID token=${ACCESS_TOKEN:0:24}..."

# ---- 3. Open two accounts ----
bold "3a. POST /v1/accounts  (source account)"
ACC_KEY_1=$(uuidgen)
SRC=$(curl -fs -X POST "$BASE_URL/v1/accounts" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $ACC_KEY_1" \
    -d '{"currency":"USD","type":"CHECKING","label":"Source"}' | jq -r .id)
green "opened source=$SRC"

bold "3b. POST /v1/accounts  (destination account)"
ACC_KEY_2=$(uuidgen)
DST=$(curl -fs -X POST "$BASE_URL/v1/accounts" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $ACC_KEY_2" \
    -d '{"currency":"USD","type":"CHECKING","label":"Destination"}' | jq -r .id)
green "opened destination=$DST"

# NOTE: For a real demo, the source account would need to be funded first via a top-up flow
# (not implemented). For now the transfer will likely 422 with INSUFFICIENT_FUNDS — expected.

# ---- 4. Transfer ----
bold "4. POST /v1/transactions  (transfer 100)"
TRANSFER_KEY=$(uuidgen)
TRANSFER_RESP=$(curl -s -X POST "$BASE_URL/v1/transactions" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $TRANSFER_KEY" \
    -d "{
      \"type\":\"TRANSFER\",
      \"sourceAccount\":\"$SRC\",
      \"destinationAccount\":\"$DST\",
      \"amount\":100,
      \"currency\":\"USD\",
      \"description\":\"Demo transfer\"
    }")
echo "$TRANSFER_RESP" | jq .

# ---- 5. Replay ----
bold "5. POST /v1/transactions  (replay with same Idempotency-Key)"
REPLAY_RESP=$(curl -s -X POST "$BASE_URL/v1/transactions" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $TRANSFER_KEY" \
    -d "{
      \"type\":\"TRANSFER\",
      \"sourceAccount\":\"$SRC\",
      \"destinationAccount\":\"$DST\",
      \"amount\":100,
      \"currency\":\"USD\",
      \"description\":\"Demo transfer\"
    }")
echo "$REPLAY_RESP" | jq .

# ---- 6. Refresh token ----
bold "6. POST /v1/oauth/token  (refresh)"
REFRESH_RESP=$(curl -fs -X POST "$BASE_URL/v1/oauth/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=refresh_token" \
    --data-urlencode "refresh_token=$REFRESH_TOKEN")
NEW_TOKEN=$(echo "$REFRESH_RESP" | jq -r .access_token)
green "new access token=${NEW_TOKEN:0:24}..."

# ---- 7. Logout ----
bold "7. DELETE /v1/sessions/current  (logout)"
LOGOUT_KEY=$(uuidgen)
curl -fs -X DELETE "$BASE_URL/v1/sessions/current" \
    -H "Authorization: Bearer $NEW_TOKEN" \
    -H "Idempotency-Key: $LOGOUT_KEY" \
    -o /dev/null
green "logged out"

green "==> Demo complete"
