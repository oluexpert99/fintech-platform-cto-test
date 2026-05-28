# Platform UI

Single React app for testing platform services through the gateway.

This is an internal test console, not a customer-facing app.

## Included modules

- Auth: register, login, refresh token, current user, sessions, revoke, logout
- Accounts: open, list, get, balance, patch (label/status/reason/approver)
- Transactions: transfer, reversal, list, lookup by id
- Accounting: journal entries, chart of accounts, trial balance

## Project structure (simple)

```text
src/
  app shell + router in App.tsx
  features/
    auth/
    accounts/
    transactions/
    accounting/
  shared/
    api/http.ts         # axios client + auth header interceptor
    auth/tokenStore.ts  # localStorage token helpers
    ui/                 # reusable small UI components
```

## Local dev

```bash
npm install
npm run dev
```

By default, API calls target `http://localhost:8080`.

Set an alternate gateway with:

```bash
VITE_API_BASE_URL=http://localhost:8080 npm run dev
```

## Docker compose profile

`platform-ui` runs under the optional `ui` profile:

```bash
docker compose --profile ui up -d
```

UI: `http://localhost:5173`

## Basic usage flow

1. Go to **Auth** and register/login.
2. Save tokens in the top bar (or login will auto-save).
3. Use **Accounts** to open accounts and patch state.
4. Use **Transactions** to create transfer/reversal.
5. Use **Accounting** to inspect journal/trial balance.

## Notes

- This UI calls the gateway only (default `http://localhost:8080`).
- Protected endpoints need a valid JWT.
- Service status chips at the top show quick connectivity checks.
