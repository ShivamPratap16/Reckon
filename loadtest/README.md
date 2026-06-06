# Reckon Load Test

k6-based HTTP load test that drives concurrent P2P transfers (including deliberate duplicate idempotency-key retries) against the running Reckon backend, then asserts zero balance inconsistencies.

## Prerequisites

- Docker (running)
- k6 v2.0.0+ on PATH
- Java 21

## How to run

```bash
./loadtest/run.sh
```

The script:
1. Boots Postgres + Kafka via `docker compose up -d`
2. Builds and starts the Spring Boot app (`bootJar` + `java -jar`)
3. Waits for `/actuator/health` to return UP
4. Seeds a payee user, resolves their DB userId
5. Runs `transfer-load.js` via k6 (default: 20 VUs, 30s)
6. Runs post-load reconciliation SQL assertions

### Environment overrides

| Variable   | Default | Description                    |
|------------|---------|--------------------------------|
| `VUS`      | 20      | Number of k6 virtual users     |
| `DURATION` | 30s     | k6 test duration               |

Example:
```bash
VUS=10 DURATION=60s ./loadtest/run.sh
```

## What is asserted

### HTTP thresholds (k6)
- `http_req_failed` rate < 5% (transfers fail only on legitimate 4xx: 404 toUserId not found, 422 insufficient funds)
- `http_req_duration` p(95) < 800ms

### Post-run reconciliation (SQL)
Two SQL checks run against the live DB after k6 finishes:

1. **Unbalanced transactions:** `SUM(entries per txn)` must net to zero for every completed transaction. Any non-zero means money was created or destroyed.
2. **Balance drift:** Every `accounts.balance` must equal `SUM(CASE direction WHEN CREDIT THEN amount ELSE -amount END)` from `ledger_entries`. Any drift means the denormalized balance diverged from the ledger.

Output:
```
unbalanced_txns=0  balance_drifts=0
RECONCILIATION CLEAN ✅
```

A non-zero count causes the script to exit 1 (failure).

## What the load test proves

- Concurrent P2P transfers under real HTTP load do not corrupt balances
- Duplicate idempotency-key requests (20% of requests re-send a previously sent key) do not cause double-debits — the second call returns the cached response without re-executing the transfer
- The pessimistic locking strategy (FOR NO KEY UPDATE) handles real concurrent HTTP traffic without deadlocks
