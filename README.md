# Reckon

> A double-entry ledger that reckons every paisa.

Reckon is a wallet/payments backend (think the core inside PhonePe / Paytm Wallet) built to be **provably correct under concurrency and failure** — money is never lost or created. It's a study in the hard parts of fintech backends: double-entry ledgers, idempotency, distributed-transaction failure handling, and reconciliation.

## Status

**Phase 1 (complete, fully tested):** the correctness-critical core.
- JWT auth (signup/login, BCrypt, wallet-on-signup, stateless security)
- Double-entry ledger with an atomic, concurrency-safe money-writer
- Authenticated P2P transfers
- 22 tests, including concurrency and atomicity proofs (Testcontainers + real Postgres)

**Designed, next phases** (see [`docs/superpowers/specs`](docs/superpowers/specs)): ADD_MONEY **saga** over a simulated idempotent bank with compensation, Kafka **transactional outbox** + exactly-once-effect consumers (cashback/notifications), idempotency-key **4-way replay**, and **reconciliation** jobs.

## Engineering highlights

- **Money is conserved, by construction.** Balances are never mutated directly — every movement is two append-only ledger entries (a debit and a credit) that sum to zero. The `balance` column is a *transaction-consistent denormalization* committed atomically with the entries; reconciliation *verifies* `balance == SUM(entries)` rather than maintaining it. Integer paisa everywhere — no floats.
- **The transfer is genuinely atomic.** Lock the two accounts in fixed id order → check funds → write two entries → update balances → flip status, all in one DB transaction. A real Spring gotcha surfaced here: a `@Transactional` method called on the same bean bypasses the proxy and silently runs in auto-commit. Reckon routes the transactional body through a separate bean (`TransferExecutor`) so the proxy actually engages.
- **Deadlock-safe under load.** Concurrent transfers acquire row locks in a fixed order; `SELECT ... FOR NO KEY UPDATE` is used (not `FOR UPDATE`) so writer locks don't conflict with the `FOR KEY SHARE` locks Postgres takes for the `ledger_entries → accounts` foreign key — eliminating a real deadlock while preserving writer exclusion.
- **Correctness is proven, not asserted.** Tests include 200 concurrent same-account debits (never overdraws, money conserved, no orphaned entries) and bidirectional A→B / B→A transfers that actually exercise the lock ordering. Failures are persisted in their own `REQUIRES_NEW` transaction so a rolled-back transfer still leaves a legible `FAILED` record.

## Tech stack

Kotlin · Spring Boot 3.3 · PostgreSQL · Flyway · Gradle · JUnit 5 · Testcontainers

## Running it

```bash
# Postgres for local runs
docker compose up -d

# Full test suite (uses Testcontainers — Docker must be running)
./gradlew test

# Run the app
./gradlew bootRun
```

### API (Phase 1)

```
POST /auth/signup     { email, password }            -> { token }
POST /auth/login      { email, password }            -> { token }
POST /transfers/p2p   { idempotencyKey, toUserId, amountPaisa }   (Bearer token)
```

## Design docs

The full design — including the failure-mode reasoning behind idempotency replay, the system-account sign convention, the outbox delivery guarantees, and the recovery-vs-slow-request race — lives in [`docs/superpowers/specs`](docs/superpowers/specs) and the staged implementation plan in [`docs/superpowers/plans`](docs/superpowers/plans).
