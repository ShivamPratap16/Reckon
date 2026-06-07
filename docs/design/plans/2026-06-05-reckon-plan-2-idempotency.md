# Reckon Plan 2 — Idempotency Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make money-moving endpoints safe to retry: a repeated request with the same idempotency key never double-charges — it replays the first attempt's outcome. Implements the spec's 4-way replay (COMPLETED / FAILED / in-flight PENDING / different request → 422).

**Architecture:** The `transactions` table already has `unique(initiator_id, idempotency_key)`, `request_hash`, `response_code`, `response_body`. We rely on the DB unique constraint as the race-protection source of truth (catch the violation), compute a stable SHA-256 `request_hash`, store the response on terminal states, and on a duplicate key decide replay vs conflict vs reject.

**Tech Stack:** Existing — Kotlin, Spring Boot, Postgres, JdbcTemplate, JUnit5, Testcontainers. No new infra (Redis fast-path is explicitly deferred to a later plan; DB-enforced idempotency is the correctness source).

**Builds on:** Plan 1 (package `com.reckon`, `LedgerService.recordTransfer`, `LedgerRepository`, `TransferController`, `TxnStatusWriter`).

**Out of scope (later plans):** Redis fast-path, stuck-PENDING recovery job (Plan touches saga/reconciliation), Kafka, saga.

---

## Key behavior: the 4-way replay (from spec §4)

On `recordTransfer`, compute `requestHash = sha256(type|from|to|amount)`. Insert the PENDING header. If the insert hits `unique(initiator_id, idempotency_key)`:
1. existing **COMPLETED**, same hash → return stored result (replayed=true), HTTP 200. **No new money movement.**
2. existing **FAILED**, same hash → re-throw the stored failure (same code), replayed.
3. existing **PENDING**, same hash → `409 CONFLICT` ("IN_PROGRESS") — first attempt still in flight; do NOT re-execute.
4. **different hash** (same key, different amount/recipient) → `422` ("IDEMPOTENCY_KEY_REUSE").

Terminal states (COMPLETED/FAILED) store `response_code` + `response_body` so replay returns the identical response.

---

## File Structure

```
src/main/kotlin/com/reckon/
├── platform/RequestHash.kt          # NEW: stable SHA-256 hashing helper
├── ledger/
│   ├── LedgerRepository.kt          # MODIFY: findByInitiatorAndKey, storeResponse; insertPending throws on dup
│   ├── LedgerService.kt             # MODIFY: replay logic, TransferOutcome return
│   ├── TransferOutcome.kt           # NEW: result type (txnId, status, replayed)
│   └── TransferController.kt        # MODIFY: map outcome → HTTP (200 replay)
└── platform/ApiError.kt             # (already handles ApiException)
src/test/kotlin/com/reckon/
├── platform/RequestHashTest.kt      # NEW
└── ledger/IdempotencyTest.kt        # NEW: the 4 replay cases + no-double-debit
```

---

## Task 1: Stable request hash

**Files:** Create `src/main/kotlin/com/reckon/platform/RequestHash.kt`, Test `src/test/kotlin/com/reckon/platform/RequestHashTest.kt`

- [ ] **Step 1: Failing test**
```kotlin
package com.reckon.platform

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RequestHashTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val b = UUID.fromString("00000000-0000-0000-0000-0000000000bb")

    @Test fun `same inputs produce same hash`() {
        assertEquals(RequestHash.of("P2P", a, b, 100), RequestHash.of("P2P", a, b, 100))
    }
    @Test fun `different amount produces different hash`() {
        assertNotEquals(RequestHash.of("P2P", a, b, 100), RequestHash.of("P2P", a, b, 200))
    }
    @Test fun `is stable hex string (deterministic across calls)`() {
        // SHA-256 hex is 64 chars
        assertEquals(64, RequestHash.of("P2P", a, b, 100).length)
    }
}
```

- [ ] **Step 2: Run → FAIL** (`./gradlew test --tests "com.reckon.platform.RequestHashTest"`) — `RequestHash` unresolved.

- [ ] **Step 3: Implement**
```kotlin
package com.reckon.platform

import java.security.MessageDigest
import java.util.UUID

object RequestHash {
    fun of(type: String, from: UUID, to: UUID, amount: Long): String {
        val payload = "$type|$from|$to|$amount"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Run → PASS** (3 tests).
- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: stable SHA-256 request hash for idempotency"`

---

## Task 2: Repository support for replay

**Files:** Modify `src/main/kotlin/com/reckon/ledger/LedgerRepository.kt`

- [ ] **Step 1: Add a row type + read/write methods**

Add to `LedgerRepository.kt` (keep existing methods):
```kotlin
data class ExistingTxn(
    val id: java.util.UUID,
    val status: String,
    val requestHash: String,
    val responseCode: Int?,
    val responseBody: String?,
)
```
Add methods:
```kotlin
fun findByInitiatorAndKey(initiatorId: java.util.UUID, idempotencyKey: String): ExistingTxn? =
    jdbc.query(
        """SELECT id, status, request_hash, response_code, response_body
           FROM transactions WHERE initiator_id = ? AND idempotency_key = ?""",
        { rs, _ -> ExistingTxn(
            rs.getObject("id", java.util.UUID::class.java),
            rs.getString("status"),
            rs.getString("request_hash"),
            rs.getObject("response_code") as Int?,
            rs.getString("response_body"),
        ) },
        initiatorId, idempotencyKey,
    ).firstOrNull()

fun storeResponse(txnId: java.util.UUID, code: Int, body: String) {
    jdbc.update(
        "UPDATE transactions SET response_code = ?, response_body = ?::jsonb, updated_at = now() WHERE id = ?",
        code, body, txnId,
    )
}
```

- [ ] **Step 2: Verify compiles** (`./gradlew compileKotlin`).
- [ ] **Step 3: Commit** `git add -A && git commit -m "feat: ledger repo lookup + response storage for idempotency"`

---

## Task 3: Replay logic in LedgerService + TransferOutcome

**Files:** Create `src/main/kotlin/com/reckon/ledger/TransferOutcome.kt`; Modify `LedgerService.kt`

- [ ] **Step 1: Create `TransferOutcome.kt`**
```kotlin
package com.reckon.ledger

import java.util.UUID

data class TransferOutcome(val transactionId: UUID, val status: String, val replayed: Boolean)
```

- [ ] **Step 2: Rewrite `LedgerService.recordTransfer` to return `TransferOutcome` and implement replay**

The current `recordTransfer` returns `UUID`. Change it to return `TransferOutcome` and add the duplicate-key handling. Inject `com.reckon.platform.RequestHash` (it's an object, call directly). Catch Spring's `DuplicateKeyException` from `insertPending`.

```kotlin
package com.reckon.ledger

import com.reckon.platform.ApiException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class LedgerService(
    private val ledger: LedgerRepository,
    private val executor: TransferExecutor,
    private val statusWriter: TxnStatusWriter,
) {
    fun recordTransfer(type: TxnType, idempotencyKey: String, requestHash: String,
                       initiatorId: UUID, from: UUID, to: UUID, amount: Long): TransferOutcome {
        if (amount <= 0) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "amount must be positive")
        if (from == to) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "cannot transfer to self")

        val txnId = try {
            ledger.insertPending(type, idempotencyKey, requestHash, amount, initiatorId, from, to)
        } catch (e: DuplicateKeyException) {
            return replay(initiatorId, idempotencyKey, requestHash)
        }

        try {
            executor.execute(txnId, from, to, amount)
        } catch (e: Exception) {
            val reason = (e as? ApiException)?.code ?: "EXECUTION_ERROR"
            statusWriter.failInOwnTxn(txnId, reason)
            // store the failure response so a retry replays it (case 2)
            val code = (e as? ApiException)?.status?.value() ?: 500
            ledger.storeResponse(txnId, code, """{"transactionId":"$txnId","status":"FAILED","code":"$reason"}""")
            throw e
        }
        ledger.storeResponse(txnId, 200, """{"transactionId":"$txnId","status":"COMPLETED"}""")
        return TransferOutcome(txnId, "COMPLETED", replayed = false)
    }

    /** Apply the 4-way replay decision for a duplicate (initiator, key). */
    private fun replay(initiatorId: UUID, idempotencyKey: String, requestHash: String): TransferOutcome {
        val existing = ledger.findByInitiatorAndKey(initiatorId, idempotencyKey)
            ?: throw ApiException(HttpStatus.CONFLICT, "IN_PROGRESS", "duplicate key, original not yet visible")
        if (existing.requestHash != requestHash)
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSE",
                "idempotency key reused with different request")
        return when (existing.status) {
            "COMPLETED" -> TransferOutcome(existing.id, "COMPLETED", replayed = true)
            "FAILED"    -> throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                              existing.responseBody?.let { extractCode(it) } ?: "FAILED",
                              "replayed prior failure")
            else        -> throw ApiException(HttpStatus.CONFLICT, "IN_PROGRESS",
                              "original request still in progress") // PENDING
        }
    }

    private fun extractCode(body: String): String =
        Regex("\"code\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "FAILED"
}
```

Notes:
- `recordTransfer`'s signature keeps `requestHash` as a parameter (the controller computes it via `RequestHash.of`). Return type changed `UUID` → `TransferOutcome`.
- The FAILED branch reconstructs the original failure code from the stored body so the retry sees the same `code`.

- [ ] **Step 3: Verify compiles** — this will break callers (`TransferController`, `LedgerServiceTest`). They're fixed in Tasks 4–5.
- [ ] **Step 4: Commit** `git add -A && git commit -m "feat: 4-way idempotency replay in LedgerService"`

---

## Task 4: Controller maps outcome → HTTP

**Files:** Modify `src/main/kotlin/com/reckon/ledger/TransferController.kt`

- [ ] **Step 1: Update controller**

The controller currently computes `requestHash` via `String.hashCode()` (with a TODO). Replace with `RequestHash.of`, and return the outcome. Replay still returns 200 with the same body shape.

```kotlin
// inside p2p(...)
val requestHash = com.reckon.platform.RequestHash.of("P2P", from.id, to.id, req.amountPaisa)
val outcome = ledger.recordTransfer(
    TxnType.P2P, req.idempotencyKey, requestHash, callerId, from.id, to.id, req.amountPaisa)
return TransferResult(outcome.transactionId, outcome.status)
```
(`TransferResult` already has `transactionId` + `status`. Remove the old TODO comment about hashCode.)

- [ ] **Step 2: Verify compiles** (`./gradlew compileKotlin`).
- [ ] **Step 3: Commit** `git add -A && git commit -m "feat: transfer endpoint uses SHA-256 hash + replay outcome"`

---

## Task 5: Fix existing tests for new return type + add idempotency tests

**Files:** Modify `src/test/kotlin/com/reckon/ledger/LedgerServiceTest.kt`; Create `src/test/kotlin/com/reckon/ledger/IdempotencyTest.kt`

- [ ] **Step 1: Fix `LedgerServiceTest` call sites** — `recordTransfer` now returns `TransferOutcome` not `UUID`. Where tests used the returned `UUID` (e.g. `val txn = ledger.recordTransfer(...)` then queried by it), change to `val txn = ledger.recordTransfer(...).transactionId`. Update all call sites; the assertions stay the same. Run `./gradlew test --tests "com.reckon.ledger.LedgerServiceTest"` → PASS (7).

- [ ] **Step 2: Write `IdempotencyTest.kt` (the 4 cases + no-double-debit)**
```kotlin
package com.reckon.ledger

import com.reckon.platform.ApiException
import com.reckon.platform.RequestHash
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class IdempotencyTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var repo: LedgerRepository

    private fun transfer(key: String, from: UUID, to: UUID, amount: Long, initiator: UUID = UUID.randomUUID()) =
        ledger.recordTransfer(TxnType.P2P, key, RequestHash.of("P2P", from, to, amount), initiator, from, to, amount)

    @Test fun `retry with same key and request does not double-debit and replays`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val first = transfer("dup-1", a, b, 20000, initiator)
        val second = transfer("dup-1", a, b, 20000, initiator)   // identical retry

        assertEquals(first.transactionId, second.transactionId)  // same txn replayed
        assertFalse(first.replayed); assertTrue(second.replayed)
        assertEquals(30000, fixtures.balanceOf(a))               // debited ONCE, not twice
        assertEquals(20000, fixtures.balanceOf(b))
    }

    @Test fun `same key different amount is rejected 422`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        transfer("dup-2", a, b, 20000, initiator)
        val ex = assertThrows<ApiException> { transfer("dup-2", a, b, 99999, initiator) }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status)
        assertEquals("IDEMPOTENCY_KEY_REUSE", ex.code)
    }

    @Test fun `in-flight PENDING with same key returns 409`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val hash = RequestHash.of("P2P", a, b, 20000)
        // simulate an in-flight first attempt: a PENDING row exists but execute hasn't completed
        repo.insertPending(TxnType.P2P, "dup-3", hash, 20000, initiator, a, b)
        val ex = assertThrows<ApiException> {
            ledger.recordTransfer(TxnType.P2P, "dup-3", hash, initiator, a, b, 20000)
        }
        assertEquals(HttpStatus.CONFLICT, ex.status)
        assertEquals("IN_PROGRESS", ex.code)
    }

    @Test fun `retry of a failed transfer replays the failure`() {
        val a = fixtures.walletWith(100); val b = fixtures.walletWith(0)   // insufficient
        val initiator = UUID.randomUUID()
        val first = assertThrows<ApiException> { transfer("dup-4", a, b, 99999, initiator) }
        assertEquals("INSUFFICIENT_FUNDS", first.code)
        val second = assertThrows<ApiException> { transfer("dup-4", a, b, 99999, initiator) }
        assertEquals("INSUFFICIENT_FUNDS", second.code)             // same failure replayed
        assertEquals(100, fixtures.balanceOf(a))                    // still untouched
    }

    @Test fun `different initiators may reuse the same key independently`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val c = fixtures.walletWith(50000)
        transfer("shared-key", a, b, 10000, UUID.randomUUID())
        // a different initiator using the SAME key string must NOT collide (scoped by initiator)
        val other = transfer("shared-key", c, b, 10000, UUID.randomUUID())
        assertFalse(other.replayed)
        assertEquals(40000, fixtures.balanceOf(c))
    }
}
```

- [ ] **Step 3: Run** `./gradlew test --tests "com.reckon.ledger.IdempotencyTest"` → PASS (5).
- [ ] **Step 4: Run FULL suite** `./gradlew test` → all green (Plan 1 tests + new).
- [ ] **Step 5: Commit** `git add -A && git commit -m "test: idempotency 4-way replay + no-double-debit + key scoping"`

---

## Done criteria (Plan 2)

- `./gradlew test` green: RequestHash (3), Idempotency (5), all Plan 1 tests still pass.
- A repeated P2P with the same key+request debits exactly once and replays the result; key reuse with a different body → 422; in-flight → 409; failed → replays failure; the same key string under different initiators does not collide.
- `request_hash` is a real SHA-256 digest; `response_code`/`response_body` populated on terminal states.

## What's next
- Plan 3: Kafka + transactional outbox + polling publisher (`payment.completed` emitted in the transfer's DB transaction).
- Plan 4: idempotent consumers (rewards CASHBACK via ledger API, notifications) + `processed_events` dedup.
- Plan 5: ADD_MONEY saga + simulated idempotent bank + recovery.
- (Later) Redis idempotency fast-path; k6 load test incl. duplicate-key retries.
