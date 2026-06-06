# Reckon

> A double-entry ledger that reckons every paisa.

Reckon is a wallet/payments backend (think the core inside PhonePe / Paytm Wallet) built to be **provably correct under concurrency and failure** — money is never lost or created. It's a study in the hard parts of fintech backends: double-entry ledgers, idempotency, distributed-transaction failure handling, and reconciliation.

## Status — complete

All seven phases of the design are implemented and tested (**63 tests**, Testcontainers with real Postgres / Kafka / Redis):

1. JWT auth + double-entry ledger + atomic, concurrency-safe P2P transfers
2. Stripe-style idempotency — 4-way replay, DB-authoritative with a Redis fast-path
3. Kafka transactional outbox — at-least-once, ordered by aggregate, poison-message → dead-letter
4. Idempotent consumers → exactly-once *effect* (rewards cashback, notifications)
5. ADD_MONEY **saga** over a simulated idempotent bank, with crash/timeout recovery + compensating refund
6. Scheduled **reconciliation** jobs — sum-to-zero, balance integrity, stuck-PENDING
7. k6 load test + pessimistic-vs-optimistic locking **benchmark**

Full design in [`docs/superpowers/specs`](docs/superpowers/specs); staged implementation plans in [`docs/superpowers/plans`](docs/superpowers/plans).

## Architecture

```mermaid
flowchart LR
    Client([Client]) -->|"JWT"| API

    subgraph App["Reckon · modular monolith (Spring Boot)"]
        direction TB
        API["auth · transfer · wallet<br/>REST + JWT"]
        Ledger["<b>ledger</b><br/>the only money-writer<br/>double-entry + balances"]
        Saga["saga<br/>ADD_MONEY + recovery"]
        Bank["bank<br/>simulated, idempotent"]
        Outbox["outbox publisher<br/>poll + SKIP LOCKED"]
        Consumers["consumers<br/>rewards · notifications<br/>processed_events dedup"]
        Recon["reconciliation<br/>scheduled audits"]
    end

    API -->|"P2P / pay merchant"| Ledger
    API -->|"add money"| Saga
    Saga -->|"debit (idempotent on txnId)"| Bank
    Saga -->|"credit"| Ledger
    API -.->|"terminal-replay fast path"| Redis[("Redis<br/>idempotency cache")]

    Ledger -->|"2 entries + balances + outbox row<br/>(one atomic txn)"| PG[("PostgreSQL<br/>accounts · transactions<br/>ledger_entries · outbox")]
    Outbox -->|"read unpublished"| PG
    Outbox -->|"payment.completed<br/>key = aggregate"| Kafka{{"Kafka · payment-events"}}
    Kafka --> Consumers
    Consumers -->|"CASHBACK via ledger API"| Ledger
    Recon -.->|"verify Σ=0 & balance=Σentries"| PG
```

**Key invariants:** money moves only through the ledger; the entry writes and the `payment.completed` outbox row commit in one transaction (no dual-write); Postgres' `unique(initiator_id, idempotency_key)` is the sole double-debit guard; Kafka is at-least-once and consumers dedup for exactly-once *effect*; Redis and every scheduler degrade gracefully (the system is correct without them).

## Engineering highlights

- **Money is conserved, by construction.** Balances are never mutated directly — every movement is two append-only ledger entries (a debit and a credit) that sum to zero. The `balance` column is a *transaction-consistent denormalization* committed atomically with the entries; reconciliation *verifies* `balance == SUM(entries)` rather than maintaining it. Integer paisa everywhere — no floats.
- **The transfer is genuinely atomic.** Lock the two accounts in fixed id order → check funds → write two entries → update balances → flip status, all in one DB transaction. A real Spring gotcha surfaced here: a `@Transactional` method called on the same bean bypasses the proxy and silently runs in auto-commit. Reckon routes the transactional body through a separate bean (`TransferExecutor`) so the proxy actually engages.
- **Deadlock-safe under load.** Concurrent transfers acquire row locks in a fixed order; `SELECT ... FOR NO KEY UPDATE` is used (not `FOR UPDATE`) so writer locks don't conflict with the `FOR KEY SHARE` locks Postgres takes for the `ledger_entries → accounts` foreign key — eliminating a real deadlock while preserving writer exclusion.
- **Correctness is proven, not asserted.** Tests include 200 concurrent same-account debits (never overdraws, money conserved, no orphaned entries) and bidirectional A→B / B→A transfers that actually exercise the lock ordering. Failures are persisted in their own `REQUIRES_NEW` transaction so a rolled-back transfer still leaves a legible `FAILED` record.
- **Idempotency is DB-authoritative with a Redis fast-path.** The `unique(initiator_id, idempotency_key)` constraint in Postgres is the ONLY race guard against double-debits — no amount of Redis failure can cause a double-spend. Redis caches only immutable **terminal** results (COMPLETED/FAILED), never in-flight PENDING, so a stale or missing cache entry never produces a wrong answer. On a cache hit the result is served without touching Postgres; on Redis failure the system degrades transparently to the DB-only path. The fast-path is gated by `reckon.idempotency.cache.enabled`; all 60 pre-Redis tests run with the cache disabled, proving idempotency works without Redis.

## Tech stack

Kotlin · Spring Boot 3.3 · PostgreSQL · Apache Kafka · Redis (idempotency fast-path) · Flyway · Gradle · JUnit 5 · Testcontainers · k6

## Running it

```bash
# Postgres for local runs
docker compose up -d

# Full test suite (uses Testcontainers — Docker must be running)
./gradlew test

# Run the app
./gradlew bootRun
```

### API

```
POST /auth/signup       { email, password }                              -> { token }
POST /auth/login        { email, password }                              -> { token }
POST /transfers/p2p     { idempotencyKey, toUserId, amountPaisa }         (Bearer)
POST /wallet/add-money  { idempotencyKey, bankRef, amountPaisa }          (Bearer)  -- saga
```

## Load testing & benchmark

### JVM locking benchmark (captured, both strategies correct)

`LockingBenchmarkTest` runs an identical hot-account workload through both the pessimistic and optimistic strategies, then **asserts correctness for both**: money is conserved across the hot set AND every account's `balance == seedBalance + sum(entries)`.

```
=== LOCKING BENCHMARK (200 transfers, 8 threads, hot 8-account set) ===
pessimistic: 307ms   651 tps  ok=200
optimistic : 1343ms  149 tps  ok=199  avgAttempts=2.89
```

> Note: these throughput numbers are indicative and host-dependent — they vary by machine (CPU, disk, JVM warmup, etc.).

**Trade-off:** The pessimistic strategy (FOR NO KEY UPDATE) serializes writers on each row, giving stable throughput under load with zero retries. The optimistic strategy (version CAS + bounded retry) avoids row locks but spends time on retries under contention (2.89 attempts per successful transfer on an 8-account hot set). For a typical wallet, where each user's transfers serialize naturally on their own row, the contention profile is far lower than the benchmark hot set. The hot-account serialization bottleneck (e.g. a popular merchant or top-up system account) is a known limitation — entry sharding (multiple sub-accounts summed by reconciliation) is the standard mitigation and is deferred as future work.

### k6 HTTP load test (run on this machine)

Run with 10 VUs for 20s against the real app (Spring Boot + Postgres + Kafka via docker-compose):

```
http_req_duration  avg=14.16ms  p(95)=40.96ms  max=917ms
http_req_failed    0.00%  (0 out of 2065 requests)
http_reqs          2065 total  (~97 req/s)
checks_succeeded   100.00% (2063/2063)
  - funded sender:       ✓
  - transfer ok or 4xx:  ✓
  - duplicate replay ok: ✓
```

All HTTP thresholds passed: `p(95) < 800ms` and `failure rate < 5%`.

**Post-run reconciliation (zero inconsistencies):**

```
unbalanced_txns=0  balance_drifts=0
RECONCILIATION CLEAN
```

Zero unbalanced transactions (every completed txn has a zero-sum entry pair) and zero balance drift (every `accounts.balance` equals the ledger sum) — confirming no double-debits, no money creation, and no orphaned entries under concurrent load with deliberate duplicate-key retries.

To run it yourself: `./loadtest/run.sh` (requires Docker + k6).

## Design docs

The full design — including the failure-mode reasoning behind idempotency replay, the system-account sign convention, the outbox delivery guarantees, and the recovery-vs-slow-request race — lives in [`docs/superpowers/specs`](docs/superpowers/specs) and the staged implementation plan in [`docs/superpowers/plans`](docs/superpowers/plans).
