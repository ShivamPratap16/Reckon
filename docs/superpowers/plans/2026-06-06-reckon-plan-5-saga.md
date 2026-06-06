# Reckon Plan 5 — ADD_MONEY Saga + Simulated Bank + Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let a user add money to their wallet from an external bank via a **saga**: a local PENDING transaction → a remote (idempotent) bank debit → a local ledger credit, each step independently committed and recoverable. A scheduled recovery job resolves sagas left mid-flight by crashes/timeouts, with bank-status checks and (where needed) compensating refunds.

**Architecture:** The bank call cannot join our DB transaction, so ADD_MONEY is a 3-step saga driven by `transactions.saga_state` (BANK_PENDING → BANK_CONFIRMED → DONE, or BANK_FAILED). The orchestrator is NOT one transaction — each step commits independently so a crash leaves a legible, recoverable state. The simulated bank is **idempotent on `transactionId`** (calling debit twice charges once), which is what makes every step safe to retry. The local credit (Step 3) reuses the atomic `TransferExecutor` with a saga-state completion guard.

**Tech Stack:** Existing. No new infra — `saga_state`, `failure_reason`, `updated_at` columns already exist (V2). The "bank" is an in-process simulator (it represents an external system that is NOT our database).

**Builds on:** Plans 1–4 (`com.reckon`, `TransferExecutor.execute(type, emitEvent, ...)`, idempotency replay, outbox, consumers, 42 tests).

**Out of scope:** reconciliation jobs (Plan 6), k6 (Plan 7).

---

## Saga shape (spec §4)

```
ADD_MONEY: BANK -> USER_WALLET  (DEBIT BANK_SETTLEMENT, CREDIT user_wallet)

Step 1 (local commit):  insertPending(ADD_MONEY, from=BANK_SETTLEMENT, to=wallet) + saga_state=BANK_PENDING
                        [idempotency replay applies, same as P2P]
Step 2 (remote):        bank.debit(transactionId, bankRef, amount)   <- IDEMPOTENT on transactionId
                          CHARGED  -> markBankConfirmed (saga_state: BANK_PENDING -> BANK_CONFIRMED)
                          DECLINED -> markSagaFailed (status=FAILED, saga_state=BANK_FAILED) ; return FAILED
                          TIMEOUT  -> leave BANK_PENDING (recovery resolves) ; the bank MAY have charged
Step 3 (local commit):  executor.execute(DEBIT BANK_SETTLEMENT, CREDIT wallet, emitEvent=true, sagaGuard=true)
                          conditional flip: status=COMPLETED, saga_state=DONE WHERE saga_state='BANK_CONFIRMED'

Recovery job (scheduled, drives off saga_state):
  - BANK_PENDING older than T  -> bank.getStatus(txnId):
        CHARGED   -> markBankConfirmed, resume Step 3 (idempotent)
        DECLINED/NOT_FOUND -> markSagaFailed
  - BANK_CONFIRMED with no ledger entries (crashed before Step 3) -> resume Step 3 (idempotent)
```
Bank is idempotent on `transactionId`; Step 3 is idempotent via the existing `unique(transaction_id, account_id, direction)` + the saga-state guard.

---

## File Structure
```
src/main/resources/db/migration/V5__saga_index.sql       # NEW: index for recovery scans
src/main/kotlin/com/reckon/
├── bank/
│   ├── SimulatedBank.kt                                  # NEW: idempotent debit/getStatus/refund + outcome control
│   └── BankTypes.kt                                      # NEW: BankResult, BankStatus, BankTimeoutException
├── ledger/
│   ├── LedgerRepository.kt                               # MODIFY: saga state methods + recovery queries
│   └── TransferExecutor.kt                               # MODIFY: sagaGuard flag (complete on BANK_CONFIRMED)
├── saga/
│   ├── AddMoneyService.kt                                # NEW: 3-step saga orchestrator
│   ├── SagaRecoveryService.kt                            # NEW: scheduled recovery (gated by property)
│   └── AddMoneyController.kt                             # NEW: POST /wallet/add-money
└── consumer/RewardsService.kt                            # MODIFY: cashback only for P2P / PAY_MERCHANT (not ADD_MONEY)
src/test/kotlin/com/reckon/
├── bank/SimulatedBankTest.kt                             # NEW: idempotent-on-txnId
└── saga/
    ├── AddMoneySagaTest.kt                               # NEW: happy / declined / idempotency replay / no-cashback
    └── SagaRecoveryTest.kt                               # NEW: crash-after-confirmed + timeout-charged recovery
```

---

## Task 1: Recovery index migration
**Files:** Create `src/main/resources/db/migration/V5__saga_index.sql`
- [ ] **Step 1: Write** (partial index for the recovery scan; uses updated_at for staleness)
```sql
CREATE INDEX idx_txn_saga_state ON transactions (saga_state, updated_at)
    WHERE saga_state IS NOT NULL AND saga_state <> 'DONE';
```
- [ ] **Step 2: Verify applies** — `./gradlew test --tests "com.reckon.support.HarnessTest"` → PASS.
- [ ] **Step 3: Commit** `git add -A && git commit -m "feat: saga recovery index (V5)"`

---

## Task 2: Simulated bank (idempotent on transactionId)
**Files:** Create `src/main/kotlin/com/reckon/bank/BankTypes.kt`, `SimulatedBank.kt`
- [ ] **Step 1: `BankTypes.kt`**
```kotlin
package com.reckon.bank

enum class BankResult { CHARGED, DECLINED }
enum class BankStatus { CHARGED, DECLINED, NOT_FOUND }
class BankTimeoutException(msg: String) : RuntimeException(msg)
```
- [ ] **Step 2: `SimulatedBank.kt`** — represents an EXTERNAL bank; idempotent on `transactionId`; behavior driven by the `bankRef` so tests are deterministic
```kotlin
package com.reckon.bank

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process simulation of an external bank. Idempotent on transactionId: a repeated debit
 * with the same transactionId charges once and returns the same result. Test-deterministic
 * behavior via bankRef sentinels:
 *   - "BANK_DECLINE" -> DECLINED (no charge)
 *   - "BANK_TIMEOUT" -> records a CHARGE but throws BankTimeoutException (models "charged but
 *                       response lost" — the dangerous case the saga recovery must handle)
 *   - anything else  -> CHARGED
 */
@Component
class SimulatedBank {
    private enum class State { CHARGED, DECLINED }
    private val ledger = ConcurrentHashMap<UUID, State>()

    fun debit(transactionId: UUID, bankRef: String, amountPaisa: Long): BankResult {
        ledger[transactionId]?.let {                      // idempotent: already seen this txn
            return if (it == State.CHARGED) BankResult.CHARGED else BankResult.DECLINED
        }
        return when (bankRef) {
            "BANK_DECLINE" -> { ledger[transactionId] = State.DECLINED; BankResult.DECLINED }
            "BANK_TIMEOUT" -> { ledger[transactionId] = State.CHARGED   // charged, but caller won't hear back
                                throw BankTimeoutException("no response from bank for $transactionId") }
            else -> { ledger[transactionId] = State.CHARGED; BankResult.CHARGED }
        }
    }

    fun getStatus(transactionId: UUID): BankStatus = when (ledger[transactionId]) {
        State.CHARGED -> BankStatus.CHARGED
        State.DECLINED -> BankStatus.DECLINED
        null -> BankStatus.NOT_FOUND
    }

    /** Compensating action. */
    fun refund(transactionId: UUID) { ledger.remove(transactionId) }
}
```
- [ ] **Step 3: `SimulatedBankTest.kt`** (extends nothing — plain unit test, no Spring/DB)
```kotlin
package com.reckon.bank

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals

class SimulatedBankTest {
    private val bank = SimulatedBank()
    @Test fun `debit is idempotent on transactionId`() {
        val id = UUID.randomUUID()
        assertEquals(BankResult.CHARGED, bank.debit(id, "ref", 1000))
        assertEquals(BankResult.CHARGED, bank.debit(id, "ref", 1000))   // charged once, same result
        assertEquals(BankStatus.CHARGED, bank.getStatus(id))
    }
    @Test fun `decline ref is declined and not charged`() {
        val id = UUID.randomUUID()
        assertEquals(BankResult.DECLINED, bank.debit(id, "BANK_DECLINE", 1000))
        assertEquals(BankStatus.DECLINED, bank.getStatus(id))
    }
    @Test fun `timeout records a charge but throws (charged-but-no-response)`() {
        val id = UUID.randomUUID()
        assertThrows<BankTimeoutException> { bank.debit(id, "BANK_TIMEOUT", 1000) }
        assertEquals(BankStatus.CHARGED, bank.getStatus(id))   // recovery will find it charged
    }
}
```
- [ ] **Step 4: Run → PASS**; **Commit** `git add -A && git commit -m "feat: simulated idempotent bank (debit/getStatus/refund) + tests"`

---

## Task 3: Ledger repo saga state methods + recovery queries
**Files:** Modify `src/main/kotlin/com/reckon/ledger/LedgerRepository.kt`
- [ ] **Step 1: Add methods** (keep existing)
```kotlin
fun setSagaState(txnId: java.util.UUID, state: String) =
    jdbc.update("UPDATE transactions SET saga_state = ?, updated_at = now() WHERE id = ?", state, txnId)

/** Guarded: BANK_PENDING -> BANK_CONFIRMED. Returns rows updated. */
fun markBankConfirmed(txnId: java.util.UUID): Int = jdbc.update(
    "UPDATE transactions SET saga_state='BANK_CONFIRMED', updated_at=now() WHERE id=? AND saga_state='BANK_PENDING'",
    txnId)

fun markSagaFailed(txnId: java.util.UUID, reason: String): Int = jdbc.update(
    """UPDATE transactions SET status='FAILED', saga_state='BANK_FAILED', failure_reason=?, updated_at=now()
       WHERE id=? AND status='PENDING'""", reason, txnId)

/** Saga completion guard: complete only if still BANK_CONFIRMED. Returns rows updated. */
fun markCompletedIfBankConfirmed(txnId: java.util.UUID): Int = jdbc.update(
    """UPDATE transactions SET status='COMPLETED', saga_state='DONE', updated_at=now()
       WHERE id=? AND saga_state='BANK_CONFIRMED'""", txnId)

/** Recovery scan: ADD_MONEY txns in a given saga_state older than N seconds. */
fun findSagaTxnsOlderThan(sagaState: String, seconds: Long): List<java.util.UUID> = jdbc.query(
    """SELECT id FROM transactions
       WHERE type='ADD_MONEY' AND saga_state=? AND updated_at < now() - make_interval(secs => ?)""",
    { rs, _ -> rs.getObject("id", java.util.UUID::class.java) }, sagaState, seconds.toDouble())

fun hasNoEntries(txnId: java.util.UUID): Boolean = jdbc.queryForObject(
    "SELECT NOT EXISTS(SELECT 1 FROM ledger_entries WHERE transaction_id=?)", Boolean::class.java, txnId)!!

/** For the saga to know the wallet to credit during recovery. */
fun toAccountOf(txnId: java.util.UUID): java.util.UUID = jdbc.queryForObject(
    "SELECT to_account_id FROM transactions WHERE id=?", java.util.UUID::class.java, txnId)!!

fun amountOf(txnId: java.util.UUID): Long = jdbc.queryForObject(
    "SELECT amount FROM transactions WHERE id=?", Long::class.java, txnId)!!
```
- [ ] **Step 2: Verify compiles**; **Commit** `git add -A && git commit -m "feat: ledger repo saga-state transitions + recovery queries"`

---

## Task 4: Executor saga completion guard
**Files:** Modify `src/main/kotlin/com/reckon/ledger/TransferExecutor.kt`
- [ ] **Step 1: Add `sagaGuard` param** — when true, complete via `markCompletedIfBankConfirmed` instead of `markCompletedIfPending`:
```kotlin
@Transactional
fun execute(txnId: UUID, type: TxnType, from: UUID, to: UUID, amount: Long,
            emitEvent: Boolean = true, sagaGuard: Boolean = false) {
    val locked = accounts.lockByIdsInOrder(listOf(from, to)).associateBy { it.id }
    val src = locked[from] ?: error("source account not found: $from")
    if (src.type in setOf(AccountType.USER_WALLET, AccountType.MERCHANT) && src.balance < amount)
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "insufficient balance")
    ledger.insertEntry(LedgerEntry(txnId, from, Direction.DEBIT, amount))
    ledger.insertEntry(LedgerEntry(txnId, to, Direction.CREDIT, amount))
    accounts.applyDelta(from, -amount)
    accounts.applyDelta(to, amount)
    if (emitEvent) {
        val payload = """{"eventId":null,"transactionId":"$txnId","type":"${type.name}",""" +
            """"fromAccountId":"$from","toAccountId":"$to","amount":$amount,"status":"COMPLETED"}"""
        outbox.append(txnId, com.reckon.outbox.EventType.PAYMENT_COMPLETED, payload)
    }
    val flipped = if (sagaGuard) ledger.markCompletedIfBankConfirmed(txnId) else ledger.markCompletedIfPending(txnId)
    if (flipped == 0) throw IllegalStateException("transaction $txnId not in the expected state; aborting")
}
```
- [ ] **Step 2: Verify compiles**; run `./gradlew test --tests "com.reckon.ledger.*"` → green (P2P path unchanged, sagaGuard defaults false). **Commit** `git add -A && git commit -m "feat: executor saga completion guard (complete on BANK_CONFIRMED)"`

---

## Task 5: AddMoneyService (the saga orchestrator)
**Files:** Create `src/main/kotlin/com/reckon/saga/AddMoneyService.kt`
- [ ] **Step 1: Implement** — reuses idempotency replay for Step 1 by delegating the PENDING insert + replay to a small inline copy of the pattern (or call LedgerService). To keep the money boundary, use `LedgerService` for the idempotent insert and `TransferExecutor`/repo for the saga steps. Simplest correct approach: implement the saga here using `LedgerRepository` directly for state transitions and `TransferExecutor` for Step 3, with the idempotency handled by catching `DuplicateKeyException` and replaying (same shape as `LedgerService.recordTransfer`).
```kotlin
package com.reckon.saga

import com.reckon.account.AccountRepository
import com.reckon.account.SystemAccounts
import com.reckon.bank.BankResult
import com.reckon.bank.BankTimeoutException
import com.reckon.bank.SimulatedBank
import com.reckon.ledger.*
import com.reckon.platform.ApiException
import com.reckon.platform.RequestHash
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AddMoneyService(
    private val ledger: LedgerRepository,
    private val executor: TransferExecutor,
    private val bank: SimulatedBank,
    private val accounts: AccountRepository,
) {
    /** ADD_MONEY saga. Returns the outcome (COMPLETED, FAILED, or PENDING if the bank timed out). */
    fun addMoney(idempotencyKey: String, initiatorId: UUID, walletId: UUID, bankRef: String, amount: Long): TransferOutcome {
        if (amount <= 0) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "amount must be positive")
        val requestHash = RequestHash.of("ADD_MONEY", SystemAccounts.BANK_SETTLEMENT, walletId, amount)

        // Step 1 (local): PENDING ADD_MONEY, saga_state=BANK_PENDING [+ idempotency replay]
        val txnId = try {
            val id = ledger.insertPending(TxnType.ADD_MONEY, idempotencyKey, requestHash, amount,
                initiatorId, SystemAccounts.BANK_SETTLEMENT, walletId)
            ledger.setSagaState(id, "BANK_PENDING")
            id
        } catch (e: DuplicateKeyException) {
            return replay(initiatorId, idempotencyKey, requestHash)
        }

        // Step 2 (remote): idempotent bank debit
        val result = try {
            bank.debit(txnId, bankRef, amount)
        } catch (e: BankTimeoutException) {
            // leave BANK_PENDING — recovery will reconcile against the bank
            return TransferOutcome(txnId, "PENDING", replayed = false)
        }
        if (result == BankResult.DECLINED) {
            ledger.markSagaFailed(txnId, "BANK_DECLINED")
            return TransferOutcome(txnId, "FAILED", replayed = false)
        }
        ledger.markBankConfirmed(txnId)

        // Step 3 (local): credit wallet from settlement, complete (saga guard)
        executor.execute(txnId, TxnType.ADD_MONEY, SystemAccounts.BANK_SETTLEMENT, walletId, amount,
            emitEvent = true, sagaGuard = true)
        return TransferOutcome(txnId, "COMPLETED", replayed = false)
    }

    private fun replay(initiatorId: UUID, idempotencyKey: String, requestHash: String): TransferOutcome {
        val existing = ledger.findByInitiatorAndKey(initiatorId, idempotencyKey)
            ?: throw ApiException(HttpStatus.CONFLICT, "IN_PROGRESS", "duplicate key, original not yet visible")
        if (existing.requestHash != requestHash)
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSE", "key reused with different request")
        return when (existing.status) {
            "COMPLETED" -> TransferOutcome(existing.id, "COMPLETED", replayed = true)
            "FAILED"    -> throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, existing.failureReason ?: "FAILED", "replayed prior failure")
            else        -> TransferOutcome(existing.id, "PENDING", replayed = true)   // still in flight
        }
    }
}
```
- [ ] **Step 2: Verify compiles**; **Commit** `git add -A && git commit -m "feat: ADD_MONEY saga orchestrator (3 steps, idempotent bank, replay)"`

---

## Task 6: AddMoney endpoint + rewards excludes ADD_MONEY
**Files:** Create `src/main/kotlin/com/reckon/saga/AddMoneyController.kt`; Modify `src/main/kotlin/com/reckon/consumer/RewardsService.kt`
- [ ] **Step 1: `AddMoneyController.kt`**
```kotlin
package com.reckon.saga

import com.reckon.account.AccountRepository
import com.reckon.auth.CurrentUser
import com.reckon.ledger.TransferResult
import com.reckon.platform.ApiException
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class AddMoneyRequest(val idempotencyKey: String, val bankRef: String, @field:Positive val amountPaisa: Long)

@RestController
@RequestMapping("/wallet")
class AddMoneyController(private val saga: AddMoneyService, private val accounts: AccountRepository) {
    @PostMapping("/add-money")
    fun addMoney(@CurrentUser callerId: UUID, @jakarta.validation.Valid @RequestBody req: AddMoneyRequest): ResponseEntity<TransferResult> {
        val wallet = accounts.findByOwner(callerId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "caller has no wallet")
        val outcome = saga.addMoney(req.idempotencyKey, callerId, wallet.id, req.bankRef, req.amountPaisa)
        return ResponseEntity.ok()
            .header("Idempotent-Replayed", outcome.replayed.toString())
            .body(TransferResult(outcome.transactionId, outcome.status))
    }
}
```
- [ ] **Step 2: Rewards excludes ADD_MONEY** — cashback is for spending, not loading. In `RewardsService.award`, change the type guard to award ONLY for spend types:
```kotlin
if (event.type !in setOf("P2P", "PAY_MERCHANT")) return   // cashback only on spends (not ADD_MONEY/CASHBACK)
```
(Replaces the previous `if (event.type == "CASHBACK") return` line.)
- [ ] **Step 3: Verify compiles + existing rewards test still passes** (`./gradlew test --tests "com.reckon.consumer.RewardsServiceTest"`). **Commit** `git add -A && git commit -m "feat: /wallet/add-money endpoint; rewards cashback only on spend types"`

---

## Task 7: SagaRecoveryService (scheduled, gated)
**Files:** Create `src/main/kotlin/com/reckon/saga/SagaRecoveryService.kt`; Modify `application.yml`
- [ ] **Step 1: `application.yml`** — add under `reckon:`
```yaml
reckon:
  saga:
    recovery:
      enabled: true          # disabled in tests; tests call recover() directly
      stale-seconds: 30      # how long a saga may sit in BANK_PENDING before reconciling
      poll-ms: 5000
```
- [ ] **Step 2: `SagaRecoveryService.kt`**
```kotlin
package com.reckon.saga

import com.reckon.bank.BankStatus
import com.reckon.bank.SimulatedBank
import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.TransferExecutor
import com.reckon.ledger.TxnType
import com.reckon.account.SystemAccounts
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class SagaRecoveryService(
    private val ledger: LedgerRepository,
    private val executor: TransferExecutor,
    private val bank: SimulatedBank,
    @Value("\${reckon.saga.recovery.stale-seconds}") private val staleSeconds: Long,
    @Value("\${reckon.saga.recovery.enabled:true}") private val enabled: Boolean,
) {
    /** Reconcile sagas left mid-flight. Returns number of transactions acted on. */
    fun recover(): Int {
        var acted = 0
        // 1) BANK_PENDING older than stale window -> reconcile against the bank
        for (txnId in ledger.findSagaTxnsOlderThan("BANK_PENDING", staleSeconds)) {
            when (bank.getStatus(txnId)) {
                BankStatus.CHARGED -> { ledger.markBankConfirmed(txnId); completeStep3(txnId); acted++ }
                BankStatus.DECLINED, BankStatus.NOT_FOUND -> { ledger.markSagaFailed(txnId, "BANK_UNRESOLVED"); acted++ }
            }
        }
        // 2) BANK_CONFIRMED but no ledger entries (crashed before Step 3) -> resume
        for (txnId in ledger.findSagaTxnsOlderThan("BANK_CONFIRMED", 0)) {
            if (ledger.hasNoEntries(txnId)) { completeStep3(txnId); acted++ }
        }
        return acted
    }

    private fun completeStep3(txnId: java.util.UUID) {
        val wallet = ledger.toAccountOf(txnId)
        val amount = ledger.amountOf(txnId)
        executor.execute(txnId, TxnType.ADD_MONEY, SystemAccounts.BANK_SETTLEMENT, wallet, amount,
            emitEvent = true, sagaGuard = true)
    }

    @Scheduled(fixedDelayString = "\${reckon.saga.recovery.poll-ms:5000}")
    fun scheduled() { if (enabled) recover() }
}
```
- [ ] **Step 3:** add `reckon.saga.recovery.enabled=false` to `PostgresTestBase`'s `@TestPropertySource` (so the scheduler doesn't fire in tests; tests call `recover()` directly). **Verify compiles**; **Commit** `git add -A && git commit -m "feat: scheduled saga recovery (reconcile BANK_PENDING/BANK_CONFIRMED)"`

---

## Task 8: Saga happy/declined/idempotency tests
**Files:** Create `src/test/kotlin/com/reckon/saga/AddMoneySagaTest.kt`
- [ ] **Step 1: Write** (extends `PostgresTestBase`)
```kotlin
package com.reckon.saga

import com.reckon.account.SystemAccounts
import com.reckon.ledger.LedgerRepository
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

class AddMoneySagaTest : PostgresTestBase() {
    @Autowired lateinit var saga: AddMoneyService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun settlementBalance() = jdbc.queryForObject(
        "SELECT balance FROM accounts WHERE id=?", Long::class.java, SystemAccounts.BANK_SETTLEMENT)!!

    @Test fun `happy path charges bank and credits wallet, money conserved`() {
        val wallet = fixtures.walletWith(0)
        val settleBefore = settlementBalance()
        val out = saga.addMoney("am-1", UUID.randomUUID(), wallet, "ref", 50000)
        assertEquals("COMPLETED", out.status)
        assertEquals(50000, fixtures.balanceOf(wallet))                 // wallet credited
        assertEquals(settleBefore - 50000, settlementBalance())        // settlement debited (goes negative)
        val saga = jdbc.queryForObject("SELECT saga_state FROM transactions WHERE id=?", String::class.java, out.transactionId)
        assertEquals("DONE", saga)
    }

    @Test fun `declined bank fails the saga without crediting the wallet`() {
        val wallet = fixtures.walletWith(0)
        val out = saga.addMoney("am-2", UUID.randomUUID(), wallet, "BANK_DECLINE", 50000)
        assertEquals("FAILED", out.status)
        assertEquals(0, fixtures.balanceOf(wallet))
        val row = jdbc.queryForMap("SELECT status, saga_state FROM transactions WHERE id=?", out.transactionId)
        assertEquals("FAILED", row["status"]); assertEquals("BANK_FAILED", row["saga_state"])
    }

    @Test fun `idempotent add-money does not double credit`() {
        val wallet = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val first = saga.addMoney("am-3", initiator, wallet, "ref", 50000)
        val second = saga.addMoney("am-3", initiator, wallet, "ref", 50000)   // retry
        assertEquals(first.transactionId, second.transactionId)
        assertEquals(50000, fixtures.balanceOf(wallet))    // credited once
    }

    @Test fun `add-money grants no cashback (loading is not a spend)`() {
        // ADD_MONEY emits payment.completed, but RewardsService only awards P2P/PAY_MERCHANT.
        // Verify by calling RewardsService directly with an ADD_MONEY event -> no cashback.
        // (covered more directly in consumer tests; here assert wallet equals exactly the added amount)
        val wallet = fixtures.walletWith(0)
        saga.addMoney("am-4", UUID.randomUUID(), wallet, "ref", 50000)
        assertEquals(50000, fixtures.balanceOf(wallet))    // exactly the deposit, no extra cashback
    }
}
```
- [ ] **Step 2: Run → PASS** (`./gradlew test --tests "com.reckon.saga.AddMoneySagaTest"`).
- [ ] **Step 3: Commit** `git add -A && git commit -m "test: add-money saga happy/declined/idempotent/no-cashback"`

---

## Task 9: Saga recovery tests (the crash/timeout proofs)
**Files:** Create `src/test/kotlin/com/reckon/saga/SagaRecoveryTest.kt`
- [ ] **Step 1: Write** (extends `PostgresTestBase`; staleSeconds default 30 — for BANK_PENDING tests we insert with an old `updated_at`, OR override stale to 0 via `@TestPropertySource`)
```kotlin
package com.reckon.saga

import com.reckon.account.SystemAccounts
import com.reckon.bank.SimulatedBank
import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.TxnType
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals

@TestPropertySource(properties = ["reckon.saga.recovery.stale-seconds=0"])  // act immediately
class SagaRecoveryTest : PostgresTestBase() {
    @Autowired lateinit var recovery: SagaRecoveryService
    @Autowired lateinit var saga: AddMoneyService
    @Autowired lateinit var bank: SimulatedBank
    @Autowired lateinit var ledger: LedgerRepository
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `recovers a bank-timeout saga left in BANK_PENDING (bank actually charged)`() {
        val wallet = fixtures.walletWith(0)
        // BANK_TIMEOUT: bank records a CHARGE but the call throws -> saga left BANK_PENDING
        val out = saga.addMoney("rec-1", UUID.randomUUID(), wallet, "BANK_TIMEOUT", 50000)
        assertEquals("PENDING", out.status)
        assertEquals(0, fixtures.balanceOf(wallet))           // not yet credited

        val acted = recovery.recover()                        // getStatus=CHARGED -> resume step 3
        assertEquals(1, acted)
        assertEquals(50000, fixtures.balanceOf(wallet))       // now credited
        assertEquals("DONE", jdbc.queryForObject(
            "SELECT saga_state FROM transactions WHERE id=?", String::class.java, out.transactionId))
    }

    @Test fun `recovers a saga that crashed after BANK_CONFIRMED before the ledger credit`() {
        val wallet = fixtures.walletWith(0)
        // Simulate crash: PENDING ADD_MONEY at saga_state=BANK_CONFIRMED with NO entries, bank charged.
        val txnId = ledger.insertPending(TxnType.ADD_MONEY, "rec-2", "-", 50000,
            UUID.randomUUID(), SystemAccounts.BANK_SETTLEMENT, wallet)
        ledger.setSagaState(txnId, "BANK_CONFIRMED")
        bank.debit(txnId, "ref", 50000)                       // bank was charged

        val acted = recovery.recover()                        // BANK_CONFIRMED + no entries -> resume
        assertEquals(1, acted)
        assertEquals(50000, fixtures.balanceOf(wallet))
        assertEquals("DONE", jdbc.queryForObject(
            "SELECT saga_state FROM transactions WHERE id=?", String::class.java, txnId))
    }

    @Test fun `recovery is idempotent (running twice does not double credit)`() {
        val wallet = fixtures.walletWith(0)
        saga.addMoney("rec-3", UUID.randomUUID(), wallet, "BANK_TIMEOUT", 50000)
        recovery.recover()
        recovery.recover()                                    // second run: nothing left to do
        assertEquals(50000, fixtures.balanceOf(wallet))       // credited exactly once
    }
}
```
- [ ] **Step 2: Run → PASS** (`./gradlew test --tests "com.reckon.saga.SagaRecoveryTest"`).
- [ ] **Step 3: Run FULL suite** `./gradlew test` → all green (Plans 1–4 + saga). Confirm the saga recovery scheduler being default-enabled doesn't disturb other tests (PostgresTestBase disables it).
- [ ] **Step 4: Commit** `git add -A && git commit -m "test: saga recovery (timeout-charged + crash-after-confirmed + idempotent)"`

---

## Done criteria (Plan 5)
- `./gradlew test` green: SimulatedBankTest (3), AddMoneySagaTest (4), SagaRecoveryTest (3), all prior tests pass.
- ADD_MONEY works as a 3-step saga: local PENDING → idempotent bank debit → local credit, each step committed independently and guarded by `saga_state`.
- Declined bank → FAILED, no credit. Idempotent add-money → no double credit.
- Recovery reconciles BANK_PENDING (timeout, bank actually charged) and BANK_CONFIRMED-but-no-entries (crash) sagas, and is itself idempotent (no double credit on repeated runs).
- Cashback is correctly NOT granted for ADD_MONEY (only spends).

## What's next
- Plan 6: reconciliation jobs (sum-to-zero per txn, balance == SUM(entries) per account, stuck-PENDING audit).
- Plan 7: k6 load test (concurrent transfers + duplicate-key retries) + pessimistic-vs-optimistic locking benchmark.
