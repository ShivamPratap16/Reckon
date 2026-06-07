# Reckon Plan 9 — Hold → Capture (Two-Phase Payments) Implementation Plan

> Implementation plan — each task is a small, independently-verifiable checklist of steps (`- [ ]`), built and tested incrementally.

**Goal:** Add card/UPI-style two-phase payments: an **authorization** reserves funds (without moving money), a **capture** settles (moves money, full or partial), and a **void/expiry** releases the reservation. Available balance = `balance − reserved`; the double-entry invariant `balance == SUM(entries)` is preserved because holds write NO ledger entries — only captures do.

**Architecture:** Add `accounts.reserved_balance` (available = balance − reserved) and a `holds` table. Authorize locks the payer row, checks `balance − reserved ≥ amount`, increments `reserved`, records a HELD hold (no ledger entries). Capture (in one transaction) decrements `reserved` by the hold amount and performs the real ledger transfer of the captured amount via the existing `TransferExecutor` (partial capture returns the remainder to available). Void/expiry decrements `reserved` and closes the hold. A scheduled expiry job (gated, like recovery/reconciliation) releases holds past `expires_at`.

**Tech Stack:** Existing. Migration V7. Reuses `TransferExecutor` for the capture's money movement and the fixed-order locking discipline.

**Builds on:** Plans 1–8 (`com.reckon`, `TransferExecutor.execute`, `AccountRepository` locking, idempotency replay pattern, reconciliation, 63 tests).

---

## Money model (precise)
- `available = balance − reserved`. Funds check at AUTHORIZE uses available. Holds move nothing.
- AUTHORIZE(amount): assert `available ≥ amount`; `reserved += amount`; hold = HELD.
- CAPTURE(captureAmount ≤ holdAmount): `reserved −= holdAmount`; transfer `captureAmount` payer→payee (2 ledger entries, balances updated); hold = CAPTURED. Net effect: payer `available` changes by `−captureAmount + holdAmount` (the unused reserve, `holdAmount − captureAmount`, returns to available); payee balance `+= captureAmount`. Money conserved.
- VOID / EXPIRE: `reserved −= holdAmount`; hold = VOIDED/EXPIRED. Nothing moves.
- Invariant additions: `reserved_balance ≥ 0`; `reserved == SUM(outstanding HELD hold amounts)` per account (new reconciliation audit).

---

## File Structure
```
src/main/resources/db/migration/V7__holds.sql            # NEW
src/main/kotlin/com/reckon/
├── account/Account.kt                                    # MODIFY: reserved in Account + repo reserve/release (locked)
├── hold/
│   ├── Hold.kt                                           # NEW: Hold domain + status enum
│   ├── HoldRepository.kt                                 # NEW
│   ├── AuthorizationService.kt                           # NEW: authorize / capture / void (idempotent)
│   ├── HoldExpiryService.kt                              # NEW: scheduled release of expired holds
│   └── PaymentsController.kt                             # NEW: /payments/authorize | capture | void
├── recon/ReconciliationRepository.kt                     # MODIFY: reserved-vs-holds audit
└── recon/ReconciliationReport.kt                         # MODIFY: reservedDrifts
src/main/resources/application.yml                        # MODIFY: reckon.holds.expiry.{enabled,poll-ms}
src/test/kotlin/com/reckon/hold/
├── AuthorizationServiceTest.kt                           # NEW: authorize/capture/partial/void/insufficient/idempotent
├── HoldConcurrencyTest.kt                                # NEW: concurrent authorizes never over-reserve
└── HoldExpiryTest.kt                                     # NEW: expiry releases reserve
```

---

## Task 1: Migration — reserved_balance + holds
**Files:** Create `src/main/resources/db/migration/V7__holds.sql`
- [ ] **Step 1: Write**
```sql
ALTER TABLE accounts ADD COLUMN reserved_balance bigint NOT NULL DEFAULT 0
    CONSTRAINT reserved_nonneg CHECK (reserved_balance >= 0);

CREATE TABLE holds (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key  text NOT NULL,
    initiator_id     uuid NULL,
    payer_account_id uuid NOT NULL REFERENCES accounts(id),
    payee_account_id uuid NOT NULL REFERENCES accounts(id),
    amount           bigint NOT NULL CONSTRAINT hold_amount_positive CHECK (amount > 0),
    captured_amount  bigint NOT NULL DEFAULT 0,
    status           text NOT NULL,        -- HELD | CAPTURED | VOIDED | EXPIRED
    capture_txn_id   uuid NULL REFERENCES transactions(id),
    expires_at       timestamptz NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_hold_initiator_idem UNIQUE (initiator_id, idempotency_key)
);
CREATE INDEX idx_holds_status_expiry ON holds (status, expires_at) WHERE status = 'HELD';
CREATE INDEX idx_holds_payer ON holds (payer_account_id) WHERE status = 'HELD';
```
- [ ] **Step 2:** `./gradlew test --tests "com.reckon.support.HarnessTest"` → PASS. **Commit** `git add -A && git commit -m "feat: holds table + reserved_balance (V7)"`

---

## Task 2: Account reserved field + locked reserve/release
**Files:** Modify `src/main/kotlin/com/reckon/account/Account.kt`
- [ ] **Step 1: Add `reserved` to the `Account` data class** and the row mapper (`rs.getLong("reserved_balance")`). Update `findById`/`findByOwner`/`lockByIdsInOrder` SELECTs to include `reserved_balance`. `createWallet` returns reserved=0.
- [ ] **Step 2: Add reserve/release** (must be called inside a transaction that already locked the row via `lockByIdsInOrder`):
```kotlin
/** Reserve funds if available (balance - reserved >= amount). Returns rows updated (0 = insufficient available). */
fun reserveIfAvailable(id: java.util.UUID, amount: Long): Int = jdbc.update(
    """UPDATE accounts SET reserved_balance = reserved_balance + ?, updated_at = now()
       WHERE id = ? AND (balance - reserved_balance) >= ?""", amount, id, amount)

/** Release a reservation. Returns rows updated. */
fun releaseReserve(id: java.util.UUID, amount: Long): Int = jdbc.update(
    "UPDATE accounts SET reserved_balance = reserved_balance - ?, updated_at = now() WHERE id = ? AND reserved_balance >= ?",
    amount, id, amount)
```
- [ ] **Step 3: Verify compiles**; **Commit** `git add -A && git commit -m "feat: account reserved_balance + reserve/release operations"`

---

## Task 3: Hold domain + repository
**Files:** Create `src/main/kotlin/com/reckon/hold/Hold.kt`, `HoldRepository.kt`
- [ ] **Step 1: `Hold.kt`**
```kotlin
package com.reckon.hold

import java.time.Instant
import java.util.UUID

enum class HoldStatus { HELD, CAPTURED, VOIDED, EXPIRED }

data class Hold(
    val id: UUID, val payerAccountId: UUID, val payeeAccountId: UUID,
    val amount: Long, val capturedAmount: Long, val status: HoldStatus, val expiresAt: Instant,
)
```
- [ ] **Step 2: `HoldRepository.kt`** — insert (returns id; throws DuplicateKeyException on dup idempotency), get, status transition (guarded by current status), findExpired
```kotlin
package com.reckon.hold

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class HoldRepository(private val jdbc: JdbcTemplate) {
    fun insertHeld(idempotencyKey: String, initiatorId: UUID, payer: UUID, payee: UUID,
                   amount: Long, expiresAt: Instant): UUID = jdbc.queryForObject(
        """INSERT INTO holds(idempotency_key, initiator_id, payer_account_id, payee_account_id, amount, status, expires_at)
           VALUES (?, ?, ?, ?, ?, 'HELD', ?) RETURNING id""",
        UUID::class.java, idempotencyKey, initiatorId, payer, payee, amount, java.sql.Timestamp.from(expiresAt))!!

    private val mapper = { rs: java.sql.ResultSet, _: Int -> Hold(
        rs.getObject("id", UUID::class.java), rs.getObject("payer_account_id", UUID::class.java),
        rs.getObject("payee_account_id", UUID::class.java), rs.getLong("amount"), rs.getLong("captured_amount"),
        HoldStatus.valueOf(rs.getString("status")), rs.getTimestamp("expires_at").toInstant()) }

    fun find(id: UUID): Hold? = jdbc.query(
        "SELECT * FROM holds WHERE id = ?", mapper, id).firstOrNull()

    fun findByInitiatorAndKey(initiatorId: UUID, key: String): Hold? = jdbc.query(
        "SELECT * FROM holds WHERE initiator_id = ? AND idempotency_key = ?", mapper, initiatorId, key).firstOrNull()

    /** Guarded transition from HELD. Returns rows updated (0 = not HELD anymore). */
    fun markCaptured(id: UUID, capturedAmount: Long, captureTxnId: UUID): Int = jdbc.update(
        """UPDATE holds SET status='CAPTURED', captured_amount=?, capture_txn_id=?, updated_at=now()
           WHERE id=? AND status='HELD'""", capturedAmount, captureTxnId, id)

    fun markClosed(id: UUID, status: HoldStatus): Int = jdbc.update(
        "UPDATE holds SET status=?, updated_at=now() WHERE id=? AND status='HELD'", status.name, id)

    fun findExpired(now: Instant, limit: Int): List<Hold> = jdbc.query(
        "SELECT * FROM holds WHERE status='HELD' AND expires_at < ? ORDER BY expires_at LIMIT ?",
        mapper, java.sql.Timestamp.from(now), limit)
}
```
- [ ] **Step 3: Verify compiles**; **Commit** `git add -A && git commit -m "feat: hold domain + repository"`

---

## Task 4: AuthorizationService (authorize / capture / void)
**Files:** Create `src/main/kotlin/com/reckon/hold/AuthorizationService.kt`
- [ ] **Step 1: Implement** — each operation is `@Transactional`, locks the payer (and payee for capture) via `lockByIdsInOrder`, reuses `TransferExecutor` for the capture transfer. Idempotent authorize via the holds unique constraint.
```kotlin
package com.reckon.hold

import com.reckon.account.AccountRepository
import com.reckon.ledger.*
import com.reckon.platform.ApiException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class AuthorizationService(
    private val accounts: AccountRepository,
    private val holds: HoldRepository,
    private val ledger: LedgerRepository,
    private val executor: TransferExecutor,
) {
    /** Reserve funds. No money moves. Idempotent on (initiator, key). */
    @Transactional
    fun authorize(idempotencyKey: String, initiatorId: UUID, payer: UUID, payee: UUID, amount: Long, ttlSeconds: Long): UUID {
        if (amount <= 0) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "amount must be positive")
        accounts.lockByIdsInOrder(listOf(payer))   // lock payer row for the available-funds check + reserve
        val holdId = try {
            holds.insertHeld(idempotencyKey, initiatorId, payer, payee, amount,
                Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS))
        } catch (e: DuplicateKeyException) {
            return holds.findByInitiatorAndKey(initiatorId, idempotencyKey)!!.id   // idempotent replay
        }
        if (accounts.reserveIfAvailable(payer, amount) == 0) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_AVAILABLE", "insufficient available balance")
        }
        return holdId
    }

    /** Settle up to the held amount; release any uncaptured remainder. */
    @Transactional
    fun capture(holdId: UUID, captureAmount: Long?): UUID {
        val hold = holds.find(holdId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_HOLD", "hold not found")
        if (hold.status != HoldStatus.HELD) throw ApiException(HttpStatus.CONFLICT, "HOLD_NOT_HELD", "hold is ${hold.status}")
        val toCapture = captureAmount ?: hold.amount
        if (toCapture <= 0 || toCapture > hold.amount)
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CAPTURE", "capture must be in (0, amount]")
        accounts.lockByIdsInOrder(listOf(hold.payerAccountId, hold.payeeAccountId))
        // release the FULL reservation; the actual transfer then debits the captured amount
        if (accounts.releaseReserve(hold.payerAccountId, hold.amount) == 0)
            throw IllegalStateException("reservation missing for hold $holdId")
        val captureTxn = ledger.insertPending(TxnType.PAY_MERCHANT, "capture:$holdId", "-", toCapture,
            hold.payerAccountId, hold.payerAccountId, hold.payeeAccountId)
        executor.execute(captureTxn, TxnType.PAY_MERCHANT, hold.payerAccountId, hold.payeeAccountId, toCapture)
        if (holds.markCaptured(holdId, toCapture, captureTxn) == 0)
            throw IllegalStateException("hold $holdId no longer HELD")
        return captureTxn
    }

    /** Cancel a hold and release the reservation. */
    @Transactional
    fun void(holdId: UUID) {
        val hold = holds.find(holdId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_HOLD", "hold not found")
        if (hold.status != HoldStatus.HELD) throw ApiException(HttpStatus.CONFLICT, "HOLD_NOT_HELD", "hold is ${hold.status}")
        accounts.lockByIdsInOrder(listOf(hold.payerAccountId))
        accounts.releaseReserve(hold.payerAccountId, hold.amount)
        if (holds.markClosed(holdId, HoldStatus.VOIDED) == 0) throw IllegalStateException("hold $holdId no longer HELD")
    }
}
```
Note: `executor.execute` re-locks the payer/payee in the same transaction — `FOR NO KEY UPDATE` is reentrant within a transaction, so this is safe. The capture debits `payer.balance` by `toCapture` (real money move); the reservation was already released, so available math nets out per the money model above.
- [ ] **Step 2: Verify compiles**; **Commit** `git add -A && git commit -m "feat: authorization service (authorize/capture/void) reusing the ledger executor"`

---

## Task 5: Expiry job + payments endpoint
**Files:** Create `src/main/kotlin/com/reckon/hold/HoldExpiryService.kt`, `PaymentsController.kt`; Modify `application.yml`, `PostgresTestBase`
- [ ] **Step 1: application.yml** — add `reckon.holds.expiry.{enabled: true, poll-ms: 30000, batch-size: 100}`.
- [ ] **Step 2: `HoldExpiryService.kt`**
```kotlin
package com.reckon.hold

import com.reckon.account.AccountRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class HoldExpiryService(
    private val holds: HoldRepository,
    private val accounts: AccountRepository,
    @Value("\${reckon.holds.expiry.enabled:true}") private val enabled: Boolean,
    @Value("\${reckon.holds.expiry.batch-size:100}") private val batchSize: Int,
) {
    /** Release reservations for holds past expiry. Each hold released in its own transaction. */
    fun expireDue(now: Instant): Int {
        var n = 0
        for (hold in holds.findExpired(now, batchSize)) { if (expireOne(hold)) n++ }
        return n
    }

    @Transactional
    protected fun expireOne(hold: Hold): Boolean {
        accounts.lockByIdsInOrder(listOf(hold.payerAccountId))
        if (holds.markClosed(hold.id, HoldStatus.EXPIRED) == 0) return false   // already closed by a racing capture/void
        accounts.releaseReserve(hold.payerAccountId, hold.amount)
        return true
    }

    @Scheduled(fixedDelayString = "\${reckon.holds.expiry.poll-ms:30000}")
    fun scheduled() { if (enabled) expireDue(Instant.now()) }
}
```
Note: `expireOne` is `protected @Transactional` and called from `expireDue` on the same bean — to avoid the self-invocation proxy trap, make `expireOne` PUBLIC and move it to a separate bean OR have `expireDue` itself be the transactional unit per hold. SIMPLEST: make `expireDue` iterate and call a separate `@Service HoldExpiryWorker.expireOne(...)` cross-bean. Implement it that way (mirror the Plan 3/Plan 5 separate-bean pattern). Do NOT leave a same-bean self-invocation of a @Transactional method.
- [ ] **Step 3: `PaymentsController.kt`**
```kotlin
package com.reckon.hold

import com.reckon.account.AccountRepository
import com.reckon.auth.CurrentUser
import com.reckon.platform.ApiException
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class AuthorizeRequest(val idempotencyKey: String, val toUserId: UUID, @field:Positive val amountPaisa: Long, val ttlSeconds: Long = 600)
data class CaptureRequest(val amountPaisa: Long? = null)
data class HoldResponse(val holdId: UUID, val status: String)

@RestController
@RequestMapping("/payments")
class PaymentsController(private val auth: AuthorizationService, private val accounts: AccountRepository) {
    @PostMapping("/authorize")
    fun authorize(@CurrentUser caller: UUID, @jakarta.validation.Valid @RequestBody req: AuthorizeRequest): HoldResponse {
        val payer = accounts.findByOwner(caller) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "no wallet")
        val payee = accounts.findByOwner(req.toUserId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_PAYEE", "no payee")
        return HoldResponse(auth.authorize(req.idempotencyKey, caller, payer.id, payee.id, req.amountPaisa, req.ttlSeconds), "HELD")
    }
    @PostMapping("/{holdId}/capture")
    fun capture(@CurrentUser caller: UUID, @PathVariable holdId: UUID, @RequestBody req: CaptureRequest): HoldResponse {
        auth.capture(holdId, req.amountPaisa); return HoldResponse(holdId, "CAPTURED")
    }
    @PostMapping("/{holdId}/void")
    fun void(@CurrentUser caller: UUID, @PathVariable holdId: UUID): HoldResponse {
        auth.void(holdId); return HoldResponse(holdId, "VOIDED")
    }
}
```
- [ ] **Step 4:** add `reckon.holds.expiry.enabled=false` to `PostgresTestBase` `@TestPropertySource`. **Verify compiles**; **Commit** `git add -A && git commit -m "feat: hold expiry job + /payments authorize|capture|void endpoint"`

---

## Task 6: Reconciliation reserved-balance audit
**Files:** Modify `src/main/kotlin/com/reckon/recon/ReconciliationReport.kt`, `ReconciliationRepository.kt`, `ReconciliationService.kt`
- [ ] **Step 1:** Add `ReservedDrift(accountId, storedReserved, computedReserved)` to the report (+ include in `clean`). Add a query:
```kotlin
/** Accounts whose reserved_balance disagrees with the sum of their outstanding HELD holds. */
fun findReservedDrifts(): List<ReservedDrift> = jdbc.query(
    """SELECT a.id, a.reserved_balance,
              COALESCE((SELECT SUM(h.amount) FROM holds h WHERE h.payer_account_id = a.id AND h.status='HELD'),0) AS computed
       FROM accounts a
       WHERE a.reserved_balance <> COALESCE((SELECT SUM(h.amount) FROM holds h WHERE h.payer_account_id=a.id AND h.status='HELD'),0)""",
    { rs, _ -> ReservedDrift(rs.getObject("id", java.util.UUID::class.java), rs.getLong("reserved_balance"), rs.getLong("computed")) })
```
Wire it into `ReconciliationService.run()` and the report.
- [ ] **Step 2: Verify compiles**; **Commit** `git add -A && git commit -m "feat: reconciliation audits reserved_balance vs outstanding holds"`

---

## Task 7: Authorization tests
**Files:** Create `src/test/kotlin/com/reckon/hold/AuthorizationServiceTest.kt`
- [ ] **Step 1: Write** (extends `PostgresTestBase`). Fund wallets via raw `walletWith` is OK here since we assert available/reserved deltas, not global balance==entries (holds add no entries; for capture we assert the transfer happened). Cover: authorize reserves (available down, balance unchanged); authorize beyond available → 422; capture moves money + releases reserve (full); partial capture returns remainder; void releases; idempotent authorize returns same hold; capture of a non-HELD hold → 409.
```kotlin
package com.reckon.hold

import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import com.reckon.platform.ApiException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

class AuthorizationServiceTest : PostgresTestBase() {
    @Autowired lateinit var auth: AuthorizationService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun reserved(id: UUID) = jdbc.queryForObject("SELECT reserved_balance FROM accounts WHERE id=?", Long::class.java, id)!!

    @Test fun `authorize reserves funds without moving money`() {
        val payer = fixtures.walletWith(50000); val payee = fixtures.walletWith(0)
        auth.authorize("h1", UUID.randomUUID(), payer, payee, 20000, 600)
        assertEquals(50000, fixtures.balanceOf(payer))   // balance unchanged
        assertEquals(20000, reserved(payer))             // reserved
        assertEquals(0, fixtures.balanceOf(payee))
    }

    @Test fun `authorize beyond available is rejected`() {
        val payer = fixtures.walletWith(10000); val payee = fixtures.walletWith(0)
        auth.authorize("h2", UUID.randomUUID(), payer, payee, 8000, 600)
        val ex = assertThrows<ApiException> { auth.authorize("h3", UUID.randomUUID(), payer, payee, 5000, 600) } // only 2000 available
        assertEquals("INSUFFICIENT_AVAILABLE", ex.code)
    }

    @Test fun `full capture moves money and releases the reservation`() {
        val payer = fixtures.walletWith(50000); val payee = fixtures.walletWith(0)
        val hold = auth.authorize("h4", UUID.randomUUID(), payer, payee, 20000, 600)
        auth.capture(hold, null)
        assertEquals(30000, fixtures.balanceOf(payer))   // 50000 - 20000
        assertEquals(20000, fixtures.balanceOf(payee))
        assertEquals(0, reserved(payer))                 // released
    }

    @Test fun `partial capture returns the remainder to available`() {
        val payer = fixtures.walletWith(50000); val payee = fixtures.walletWith(0)
        val hold = auth.authorize("h5", UUID.randomUUID(), payer, payee, 20000, 600)
        auth.capture(hold, 12000)
        assertEquals(38000, fixtures.balanceOf(payer))   // only 12000 captured
        assertEquals(12000, fixtures.balanceOf(payee))
        assertEquals(0, reserved(payer))                 // full reservation released; 8000 back to available
    }

    @Test fun `void releases the reservation`() {
        val payer = fixtures.walletWith(50000); val payee = fixtures.walletWith(0)
        val hold = auth.authorize("h6", UUID.randomUUID(), payer, payee, 20000, 600)
        auth.void(hold)
        assertEquals(50000, fixtures.balanceOf(payer)); assertEquals(0, reserved(payer))
    }

    @Test fun `authorize is idempotent`() {
        val payer = fixtures.walletWith(50000); val payee = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val a = auth.authorize("h7", initiator, payer, payee, 20000, 600)
        val b = auth.authorize("h7", initiator, payer, payee, 20000, 600)
        assertEquals(a, b)
        assertEquals(20000, reserved(payer))   // reserved once, not twice
    }

    @Test fun `capturing a voided hold is rejected`() {
        val payer = fixtures.walletWith(50000); val payee = fixtures.walletWith(0)
        val hold = auth.authorize("h8", UUID.randomUUID(), payer, payee, 20000, 600)
        auth.void(hold)
        val ex = assertThrows<ApiException> { auth.capture(hold, null) }
        assertEquals("HOLD_NOT_HELD", ex.code)
    }
}
```
- [ ] **Step 2: Run → PASS**; **Commit** `git add -A && git commit -m "test: authorize/capture/partial/void/idempotent/insufficient"`

---

## Task 8: Concurrency + expiry tests
**Files:** Create `src/test/kotlin/com/reckon/hold/HoldConcurrencyTest.kt`, `HoldExpiryTest.kt`
- [ ] **Step 1: `HoldConcurrencyTest.kt`** — N concurrent authorizes on one payer with only enough available for some; assert total reserved never exceeds balance (no over-reservation)
```kotlin
package com.reckon.hold

import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoldConcurrencyTest : PostgresTestBase() {
    @Autowired lateinit var auth: AuthorizationService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `concurrent authorizes never over-reserve`() {
        val payer = fixtures.walletWith(10000)   // funds exactly 100 holds of 100
        val payee = fixtures.walletWith(0)
        val pool = Executors.newFixedThreadPool(16); val ok = AtomicInteger(0)
        (1..200).forEach { i -> pool.submit {
            try { auth.authorize("c$i", UUID.randomUUID(), payer, payee, 100, 600); ok.incrementAndGet() } catch (e: Exception) {}
        } }
        pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS)
        val reserved = jdbc.queryForObject("SELECT reserved_balance FROM accounts WHERE id=?", Long::class.java, payer)!!
        assertEquals(100, ok.get())          // exactly the affordable number reserved
        assertEquals(10000, reserved)        // reserved == balance, never more
        assertTrue(reserved <= fixtures.balanceOf(payer))   // never over-reserved
    }
}
```
- [ ] **Step 2: `HoldExpiryTest.kt`** — a hold past expiry is released by the expiry job
```kotlin
package com.reckon.hold

import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class HoldExpiryTest : PostgresTestBase() {
    @Autowired lateinit var auth: AuthorizationService
    @Autowired lateinit var expiry: HoldExpiryService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `expired hold is released`() {
        val payer = fixtures.walletWith(50000); val payee = fixtures.walletWith(0)
        val hold = auth.authorize("e1", UUID.randomUUID(), payer, payee, 20000, 600)
        jdbc.update("UPDATE holds SET expires_at = now() - interval '1 hour' WHERE id = ?", hold)
        val released = expiry.expireDue(Instant.now())
        assertEquals(1, released)
        assertEquals(0, jdbc.queryForObject("SELECT reserved_balance FROM accounts WHERE id=?", Long::class.java, payer))
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM holds WHERE id=?", String::class.java, hold))
    }
}
```
- [ ] **Step 3: Run → PASS**; run FULL `./gradlew test` → all green (Plans 1–8 + holds). **Commit** `git add -A && git commit -m "test: hold concurrency (no over-reserve) + expiry release"`

---

## Task 9: README
**Files:** Modify `README.md`
- [ ] **Step 1:** Add a "Two-phase payments (hold → capture)" note: available = balance − reserved; authorize reserves, capture settles (full/partial, remainder released), void/expiry release; `balance == SUM(entries)` preserved (holds write no entries); reconciliation now also audits `reserved == SUM(outstanding holds)`. Add the endpoints.
- [ ] **Step 2: Run FULL suite** → green. **Commit** `git add -A && git commit -m "docs: two-phase hold/capture payments"`

---

## Done criteria (Plan 9)
- `./gradlew test` green: AuthorizationServiceTest (7), HoldConcurrencyTest (1), HoldExpiryTest (1), all prior tests pass.
- Authorize reserves without moving money; capture settles (full + partial, remainder released); void + expiry release; available-funds enforced; idempotent; concurrent authorizes never over-reserve.
- `balance == SUM(entries)` still holds; reconciliation also verifies `reserved == SUM(outstanding HELD holds)`.

## What's next
- Plan 10: property-based ledger tests (cover transfers + holds invariants).
- Plan 11: chaos testing (Toxiproxy). Plan 12: observability (OTel + Grafana).
