# Event schemas

JSON Schema definitions for every Kafka event the platform publishes. See [`specs/events.spec.md`](../specs/events.spec.md) for the policy.

## Layout

| File | Subject |
|---|---|
| [`_envelope.v1.json`](_envelope.v1.json) | The shared envelope (every event imports this via `$ref`) |
| `transactions.transfer.completed-value.v1.json` | `transactions.transfer.completed` topic, v1 |
| `transactions.transfer.failed-value.v1.json` | `transactions.transfer.failed` topic, v1 |
| `transactions.transfer.reversed-value.v1.json` | `transactions.transfer.reversed` topic, v1 |
| `accounts.account.opened-value.v1.json` | `accounts.account.opened` topic, v1 |
| `accounts.account.status-changed-value.v1.json` | `accounts.account.status-changed` topic, v1 |
| `users.user.registered-value.v1.json` | `users.user.registered` topic, v1 |

## Compatibility

All schemas are registered in Apicurio under subject `<topic>-value` with `BACKWARD` compatibility. A PR that breaks BACKWARD against the latest registered version fails CI.

## Validation

Every event published by any service must validate against the corresponding schema. Producer-side validation runs at build time (CI) and consumer-side runs implicitly via JSON deserialisation in the listener.
