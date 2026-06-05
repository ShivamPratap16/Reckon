# WalletX — Design Spec

**Date:** 2026-06-05
**Author:** Shivam Raj
**Status:** Approved design → ready for implementation planning

---

## 1. Goal & Scope

WalletX is a wallet/payments backend (think PhonePe/Paytm Wallet core) built as a
**resume + interview** project. The optimization target is therefore:

- **Provable correctness** under concurrency and failure — money is never lost or created.
- A **clean, whiteboard-able story** for the concepts interviewers probe: double-entry
  ledger, idempotency, saga/compensation, exactly-once *effect*, reconciliation.
- Ruthless YAGNI on anything that doesn't serve that story.

**Non-goals:** real bank rails, KYC, multi-currency FX, refunds UI, admin dashboards,
horizontal scaling. These are mentioned only as "what I'd do next" notes where relevant.

### Stack
- **Kotlin + Spring Boot** (concise, null-safe, matches modern Indian fintech: PhonePe/CRED/Razorpay), Gradle build.
- **PostgreSQL** — system of record.
- **Kafka** — asynchronous side-effects (rewards, notifications) + saga coordination.
- **Redis** — optional idempotency fast-path only (never source of truth).
- **Testcontainers + k6/Gatling** — integration tests and the concurrency load-test proof.

---

## 2. Architecture — Modular Monolith + Kafka

Single deployable Spring Boot app with **hard internal module boundaries**. Modules call
each other in-process for synchronous needs, but cross-module *side-effects* go through
Kafka events, so any module could later be extracted into its own service with no logic
change.

```
walletx (single Spring Boot app, Gradle, Kotlin)
│
├── auth            → signup/login, BCrypt, JWT issue+verify, request identity
├── account         → account lifecycle; user wallets + system accounts
│                      (BANK_SETTLEMENT, REWARDS_POOL, MERCHANT)
├── ledger          → THE core. transactions + double-entry ledger_entries.
│                      ONLY module that writes money tables. Computed-but-denormalized balances.
├── transfer        → use-case orchestration: add-money, p2p, pay-merchant.
│                      owns idempotency keys + the saga state machine.
├── bank            → SIMULATED external bank (random latency + failures, idempotent on txnId)
│                      — makes the saga + compensation genuinely necessary.
├── rewards         → cashback consumer; demonstrates idempotent Kafka consumer.
│                      Writes money ONLY via the ledger module API.
├── reconciliation  → scheduled audits (sum=0, balance vs ledger, stuck PENDING).
└── platform        → shared: Kafka config, transactional outbox + poller,
                       error handling, observability/metrics.
```

### Boundary rules (the senior-level part)
1. **Only `ledger` writes to money tables** (`accounts`, `transactions`, `ledger_entries`).
   Every other module — including rewards — calls the ledger API.
2. Synchronous cross-module needs = in-process calls. Cross-module **side-effects**
   (rewards, notifications, settlement reactions) = **Kafka events**.
3. Events are published via a **transactional outbox**: the event row is written in the
   *same DB transaction* as the ledger write, so it can never be lost even if Kafka is down.

### Concurrency & balance model — DECIDED
- **Balance is a transaction-consistent denormalization, not a cache.** Each money move:
  `lock account rows (fixed id order) → check balance ≥ amount → insert 2 ledger entries
  → UPDATE balance`, **all in one DB transaction**. Because balance commits atomically
  with the entries, it cannot drift in normal operation. The ledger remains authoritative;
  **reconciliation verifies `balance == SUM(entries)`, it never maintains it.** This gives
  O(1) transfers regardless of ledger depth.
- **Pessimistic (`SELECT … FOR UPDATE`) vs optimistic (`version` column) is a swappable
  concurrency strategy on the same schema** — benchmarked against each other, not two schemas.
- **Stretch goal (clearly labeled):** periodic balance *snapshots* (sum entries since last
  snapshot) — the TigerBeetle-style purist version. Not built unless time permits.

### Outbox delivery — DECIDED
- **Polling publisher** (scheduled task):
  `SELECT … WHERE published=false ORDER BY id LIMIT n FOR UPDATE SKIP LOCKED` → publish →
  mark `published=true`. `SKIP LOCKED` makes it safe under multiple instances.
- Explicitly **not Debezium/CDC** — adds infra weight without teaching more for this scope.

### Hot-account serialization — measured trade-off
Per-account row locks mean hot accounts (esp. `BANK_SETTLEMENT`, which participates in
*every* add-money) serialize. This is **expected and correct**. The README's "what I'd do
next" section documents the **entry-sharding** fix (split a hot account into N sub-accounts,
sum across shards) — measured, not hand-waved.

---

## 3. Data Model

Money is **integer paisa** everywhere (never float). Ledger entries are **append-only**
(never updated/deleted). Direction carries the sign; amounts are always positive.

```sql
-- ACCOUNTS: user wallets + system accounts
accounts (
  id         uuid pk,
  owner_id   uuid null,            -- null for system accounts
  type       text,                 -- USER_WALLET | BANK_SETTLEMENT | REWARDS_POOL | MERCHANT
  currency   text default 'INR',
  balance    bigint not null default 0,   -- PAISA
  version    bigint not null default 0,   -- for optimistic strategy
  status     text default 'ACTIVE',
  created_at, updated_at,
  -- system accounts may go negative by design (see Sign Convention below):
  CHECK (balance >= 0 OR type IN ('BANK_SETTLEMENT','REWARDS_POOL'))
)

-- TRANSACTIONS: one row per money-movement use-case (the "header")
transactions (
  id              uuid pk,
  type            text,            -- ADD_MONEY | P2P | PAY_MERCHANT | CASHBACK
  status          text,            -- PENDING | COMPLETED | FAILED | COMPENSATED
  idempotency_key text not null,
  request_hash    text not null,   -- hash(type, from, to, amount): detects key reuse w/ different body
  amount          bigint not null,
  initiator_id    uuid,
  from_account_id uuid null,       -- recorded up-front so FAILED txns (no entries) are still legible
  to_account_id   uuid null,
  saga_state      text null,       -- BANK_PENDING | BANK_CONFIRMED | BANK_FAILED | DONE (null for pure-local)
  failure_reason  text null,
  response_code   int null,        -- stored result for replay
  response_body   jsonb null,      -- stored result for replay
  created_at, updated_at,
  CHECK (amount > 0),
  unique (initiator_id, idempotency_key)   -- THE race protection (scoped per initiator)
)

-- LEDGER_ENTRIES: immutable double-entry record, append-only
ledger_entries (
  id             bigserial pk,
  transaction_id uuid fk -> transactions,
  account_id     uuid fk -> accounts,
  direction      text,             -- DEBIT | CREDIT
  amount         bigint not null,  -- always positive
  created_at,
  CHECK (amount > 0),
  -- invariant: per transaction_id, SUM(credit) == SUM(debit)
  -- step-3 idempotency guard for saga resume:
  unique (transaction_id, account_id, direction)
)

-- OUTBOX: events written in the same txn as the ledger write
outbox (
  id           bigserial pk,
  event_id     uuid not null default gen_random_uuid(),  -- consumer dedup key, carried in envelope
  aggregate_id uuid,                                     -- usually transaction_id
  event_type   text,            -- payment.completed | payment.failed | ...
  payload      jsonb,
  published    boolean default false,
  attempts     int default 0,
  last_error   text null,
  created_at, published_at
)
-- partial index so the poller never full-scans:
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published = false;

-- PROCESSED_EVENTS: consumer-side dedup for exactly-once EFFECT
processed_events (
  consumer_name text,
  event_id      uuid,
  processed_at  timestamptz,
  primary key (consumer_name, event_id)
)

-- USERS (auth module)
users ( id uuid pk, email text unique, password_hash text, created_at )

-- Indexes needed day one:
CREATE INDEX ON ledger_entries (account_id, id);   -- statements + reconciliation SUM
CREATE INDEX ON ledger_entries (transaction_id);   -- FK lookups + sum=0 audit
CREATE INDEX ON transactions (status, created_at); -- stuck-PENDING scan
```

### Sign convention (document explicitly — common interview probe)
In add-money, money enters the user wallet *from* the bank. The `BANK_SETTLEMENT` system
account is **debited continuously** as users add money, so its balance trends large and
negative — and that is **correct double-entry**, not a bug. Hence non-negative balance is
enforced in **application logic only for `USER_WALLET`/`MERCHANT`**, with the DB `CHECK`
above as defense-in-depth. "Where does the money come from in add-money?" → from the
settlement account, which represents our claim on funds held at the bank.

### Notes / deliberate limitations
- `unique(transaction_id, account_id, direction)` (the saga step-3 guard) quietly forbids a
  future transaction with two same-direction legs to one account (fees, split payments). A
  `leg_index` column is the fix; **omitted deliberately** for this scope.
- `saga_state` on `transactions` holds **current** state only. A dedicated `saga_steps`
  history table (state-history vs current-state) is the **production evolution** — noted,
  not built.

---

## 4. Transfer, Saga & Exactly-Once Flow

### Three use-cases, two execution shapes

| Use-case | Money path | Shape | Why |
|---|---|---|---|
| **P2P** | `USER_WALLET → USER_WALLET` | Single local DB txn, **no saga** | Both accounts in our DB; one ACID txn is correct + simplest. |
| **PAY_MERCHANT** | `USER_WALLET → MERCHANT` | Single local DB txn + emits `payment.completed` | Same as P2P; rewards/notifications react via event. |
| **ADD_MONEY** | `BANK → USER_WALLET` | **Saga** | External bank call can't join our DB txn — the only honest place a saga is needed. |

### Idempotency replay — FOUR distinct behaviors (spell out in API docs)
On `INSERT transactions` hitting `unique(initiator_id, idempotency_key)`:
1. **Existing status = COMPLETED**, same `request_hash` → replay stored `response_code/body`.
2. **Existing status = FAILED**, same `request_hash` → replay the stored failure.
3. **Existing status = PENDING** (first attempt still in flight), same `request_hash` →
   `409 Conflict` (or `202 Accepted` + transaction id). **Do NOT re-execute.**
4. **Different `request_hash`** (same key, different amount/recipient) → `422 Unprocessable`
   (client bug — Stripe-style).

### Local transfer (P2P / merchant) — one atomic transaction
```
POST /transfers/p2p  { idempotencyKey, toUserId, amountPaisa }
  1. Resolve caller from JWT → initiator_id
  2. request_hash = hash(type, from, to, amount)
  3. INSERT transactions(status=PENDING, from_account_id, to_account_id, request_hash, ...)
       └─ on unique violation → apply 4-way replay logic above
  4. BEGIN
       SELECT ... FOR UPDATE on [from, to] in fixed id order   (deadlock-safe)
       assert from.balance >= amount        (app-level, USER_WALLET/MERCHANT)
       INSERT ledger_entries (DEBIT from, CREDIT to)            -- sum = 0
       UPDATE balances on both rows
       INSERT outbox(event_id, 'payment.completed', payload)    -- same txn!
       UPDATE transactions SET status=COMPLETED, response_code/body=...
     COMMIT
  5. return result
```
Ledger + balances + outbox + status flip **commit together or not at all**.

**Failure handling on the local path:**
- **Insufficient funds:** inner txn rolls back; then in a **new** txn set
  `status=FAILED, failure_reason='INSUFFICIENT_FUNDS'` + stored response, so a same-key
  retry replays the failure (case 2) instead of looking stuck.
- **Crash between step 3 and step 4:** leaves a PENDING txn with no entries → handled by the
  recovery job (see §6): PENDING + no entries + older than T → mark FAILED.

### ADD_MONEY — the saga
```
Step 1 (local):  INSERT transactions(type=ADD_MONEY, status=PENDING, saga_state=BANK_PENDING)
                   [+ idempotency check / 4-way replay]

Step 2 (remote): bank.debit(userBankRef, amount, transactionId)   <-- IDEMPOTENT on transactionId
                   success → saga_state=BANK_CONFIRMED
                   failure → saga_state=BANK_FAILED, status=FAILED   (nothing to compensate)
                   timeout → leave BANK_PENDING (recovery job resolves)

Step 3 (local):  BEGIN
                   lock [BANK_SETTLEMENT, user_wallet] in fixed order
                   INSERT ledger_entries (DEBIT settlement, CREDIT wallet)  -- guarded by
                                                          -- unique(txn_id, account_id, direction)
                   UPDATE balances
                   INSERT outbox('payment.completed')
                   status=COMPLETED, saga_state=DONE
                 COMMIT
```
**The bank simulator is idempotent on `transactionId`**: calling `debit` twice with the same
id charges once and returns the same result — mirrors real PSP/bank APIs and is what makes
the saga safe to retry.

### Compensation / recovery job (scheduled) — drives off status/saga_state
```
- saga BANK_PENDING older than T  → bank.getStatus(txnId):
      charged   → resume Step 3 (idempotent)
      not found → mark FAILED
- saga BANK_CONFIRMED but no ledger entries (crashed before Step 3)
      → resume Step 3 (idempotent via the unique guard)
- local PENDING + no entries + older than T → mark FAILED
- where business rules require it → bank.refund(txnId) is the compensating action (→ COMPENSATED)
```

### Exactly-once *effect* on consumers
Kafka is at-least-once; consumers are made idempotent. The dedup mark and the side-effect
**commit in one DB transaction**, and the side-effect goes **through the ledger API**
(boundary preserved):
```
RewardsConsumer.on(payment.completed):
  BEGIN
    INSERT INTO processed_events(consumer_name='rewards', event_id) ON CONFLICT DO NOTHING
    if rowcount == 0: COMMIT; return        -- already processed → skip
    ledger.recordTransaction(                -- ledger module owns the money write
        type=CASHBACK,                       -- its OWN transaction header + sum-zero entries
        DEBIT REWARDS_POOL, CREDIT user_wallet, amount=cashback)
  COMMIT     -- dedup mark + cashback commit together
```
Cashback is a **separate `CASHBACK` transaction** (own header, own sum-zero entries) — it
never attaches entries to the original payment (which would break its sum=0 invariant) and
never dangles. Notifications consumer is identical with `consumer_name='notifications'`.

**"At-least-once delivery + idempotent consumer = effectively-once"** — demonstrated.

---

## 5. Auth Module

- `POST /auth/signup` → BCrypt-hashed password, creates `users` row + a default
  `USER_WALLET` account.
- `POST /auth/login` → verifies password, issues short-lived JWT (HS256).
- All `/transfers/*`, `/wallet/*` endpoints require a valid JWT; `initiator_id` is derived
  from the token, never trusted from the body.
- **Out of scope (YAGNI):** refresh tokens, OAuth, KYC, password reset.

---

## 6. Reconciliation & Recovery (scheduled jobs)

1. **Sum-to-zero audit:** for each transaction, assert `SUM(credit) == SUM(debit)`.
2. **Balance audit:** for each account, assert `balance == SUM(signed entries)` — *verifies*
   the denormalized balance, flags drift.
3. **Stuck-PENDING audit + recovery:** see §4 recovery job (saga + local paths).
4. Findings are logged + exposed as metrics; auto-fix only where provably safe (saga resume).

---

## 7. Proof / Load-Test Plan (the resume highlight)

- **k6 (or Gatling)** fires N concurrent transfers between a small hot set of accounts,
  including deliberate **duplicate-idempotency-key retries** and **concurrent debits of the
  same account**.
- **Assertions after the run:**
  - Total money in the system unchanged (`SUM(all balances)` conserved; `SUM(all entries)=0`).
  - No negative `USER_WALLET`/`MERCHANT` balances.
  - No duplicate debits for any idempotency key.
  - Every `balance == SUM(entries)` (reconciliation passes clean).
- **Reported numbers:** TPS, p50/p99 latency, and the hot-account serialization effect
  (with the entry-sharding note as the documented next step).
- **Pessimistic vs optimistic** concurrency strategies benchmarked head-to-head.

---

## 8. Staged Build Order (each stage is a working milestone)

1. Skeleton: Spring Boot + Postgres + Flyway migrations + Testcontainers; `accounts` +
   `users` + auth (JWT).
2. Ledger module: `transactions` + `ledger_entries`, double-entry write API, computed +
   denormalized balance, fixed-order locking. P2P transfer endpoint end-to-end.
3. Idempotency: unique constraint + `request_hash` + 4-way replay logic.
4. Kafka + transactional outbox + polling publisher; `payment.completed` emitted on transfer.
5. Idempotent consumers: rewards (CASHBACK via ledger API) + notifications, `processed_events` dedup.
6. ADD_MONEY saga + simulated bank (idempotent on txnId) + compensation/recovery job.
7. Reconciliation jobs (sum=0, balance audit, stuck-PENDING).
8. Load tests + metrics + README write-up (proof, trade-offs, "what I'd do next").

---

## 9. Deferred / "What I'd Do Next" (explicitly out of scope)

- Entry-sharding for hot system accounts.
- Balance snapshots (TigerBeetle-style).
- `saga_steps` history table.
- `leg_index` on ledger entries (multi-leg same-direction transactions: fees, splits).
- Extracting `bank` (or any module) into a separate microservice.
- Real bank rails, KYC, multi-currency/FX, refund flows UI.
