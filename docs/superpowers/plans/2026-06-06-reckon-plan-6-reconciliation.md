# Reckon Plan 6 — Reconciliation Jobs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Continuously verify the ledger's integrity with scheduled audits that DETECT (not hide) bugs: every transaction's entries sum to zero, every account's `balance` equals the sum of its ledger entries, and no transaction is stuck PENDING. Findings are reported as a structured report + metrics; reconciliation never silently "fixes" a balance (that would mask a bug).

**Architecture:** A read-only `ReconciliationService` runs three SQL audits and returns a `ReconciliationReport`. A scheduled runner (gated by property, disabled in tests) executes it periodically and logs/exposes the findings. The key production invariant being verified: in a correct system, money only moves via ledgered writes (entries + balance update committed atomically), so for EVERY account `balance == SUM(signed entries)`, and for EVERY transaction `SUM(credits) == SUM(debits)`.

**Tech Stack:** Existing. No new infra, no migration (read-only queries; existing indexes suffice).

**Builds on:** Plans 1–5 (`com.reckon`, 55 tests).

**Out of scope:** k6 load test (Plan 7), auto-repair of balances (deliberately — reconciliation detects, it does not mask).

---

## The three audits (spec §6)
1. **Sum-to-zero:** every transaction that has entries must have `SUM(credits) == SUM(debits)`. (FAILED txns have zero entries → vacuously fine, excluded.)
2. **Balance integrity:** every account's `balance` must equal `SUM(signed entries)`. Drift = a bug (a balance changed without a matching entry, or vice versa).
3. **Stuck-PENDING:** transactions in PENDING with no entries older than a threshold (a local transfer that crashed between insert and execute, or a saga awaiting recovery).

---

## File Structure
```
src/main/kotlin/com/reckon/recon/
├── ReconciliationRepository.kt     # NEW: the three audit queries
├── ReconciliationService.kt        # NEW: runs audits -> ReconciliationReport
└── ReconciliationReport.kt         # NEW: report + violation types
src/main/resources/application.yml  # MODIFY: reckon.reconciliation.{enabled,stale-seconds,poll-ms}
src/test/kotlin/com/reckon/recon/
└── ReconciliationServiceTest.kt    # NEW: clean passes; drift/unbalanced/stuck detected
```

---

## Task 1: Report + violation types
**Files:** Create `src/main/kotlin/com/reckon/recon/ReconciliationReport.kt`
- [ ] **Step 1: Write**
```kotlin
package com.reckon.recon

import java.util.UUID

data class UnbalancedTxn(val transactionId: UUID, val net: Long)
data class BalanceDrift(val accountId: UUID, val storedBalance: Long, val computedBalance: Long)

data class ReconciliationReport(
    val unbalancedTransactions: List<UnbalancedTxn>,
    val balanceDrifts: List<BalanceDrift>,
    val stuckPending: List<UUID>,
) {
    val clean: Boolean get() = unbalancedTransactions.isEmpty() && balanceDrifts.isEmpty() && stuckPending.isEmpty()
}
```
- [ ] **Step 2: Verify compiles**; **Commit** `git add -A && git commit -m "feat: reconciliation report + violation types"`

---

## Task 2: Reconciliation repository (the audit queries)
**Files:** Create `src/main/kotlin/com/reckon/recon/ReconciliationRepository.kt`
- [ ] **Step 1: Write**
```kotlin
package com.reckon.recon

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReconciliationRepository(private val jdbc: JdbcTemplate) {

    /** Transactions whose entries do NOT net to zero (a broken double-entry invariant). */
    fun findUnbalancedTransactions(): List<UnbalancedTxn> = jdbc.query(
        """SELECT transaction_id,
                  SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
           FROM ledger_entries
           GROUP BY transaction_id
           HAVING SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) <> 0""",
        { rs, _ -> UnbalancedTxn(rs.getObject("transaction_id", UUID::class.java), rs.getLong("net")) },
    )

    /** Accounts whose stored balance disagrees with the sum of their ledger entries. */
    fun findBalanceDrifts(): List<BalanceDrift> = jdbc.query(
        """SELECT a.id, a.balance,
                  COALESCE(SUM(CASE le.direction WHEN 'CREDIT' THEN le.amount WHEN 'DEBIT' THEN -le.amount END), 0) AS computed
           FROM accounts a
           LEFT JOIN ledger_entries le ON le.account_id = a.id
           GROUP BY a.id, a.balance
           HAVING a.balance <> COALESCE(SUM(CASE le.direction WHEN 'CREDIT' THEN le.amount WHEN 'DEBIT' THEN -le.amount END), 0)""",
        { rs, _ -> BalanceDrift(rs.getObject("id", UUID::class.java), rs.getLong("balance"), rs.getLong("computed")) },
    )

    /** Transactions stuck in PENDING with no entries, older than the given window. */
    fun findStuckPending(staleSeconds: Long): List<UUID> = jdbc.query(
        """SELECT t.id FROM transactions t
           WHERE t.status = 'PENDING'
             AND NOT EXISTS (SELECT 1 FROM ledger_entries le WHERE le.transaction_id = t.id)
             AND t.created_at < now() - make_interval(secs => ?)""",
        { rs, _ -> rs.getObject("id", UUID::class.java) }, staleSeconds.toDouble(),
    )
}
```
- [ ] **Step 2: Verify compiles**; **Commit** `git add -A && git commit -m "feat: reconciliation audit queries (sum-zero, balance drift, stuck pending)"`

---

## Task 3: Reconciliation service + scheduled runner
**Files:** Create `src/main/kotlin/com/reckon/recon/ReconciliationService.kt`; Modify `application.yml`
- [ ] **Step 1: `application.yml`** — add under `reckon:`
```yaml
reckon:
  reconciliation:
    enabled: true          # scheduled audit on in prod; tests disable + call run() directly
    stale-seconds: 3600    # PENDING older than 1h is "stuck"
    poll-ms: 60000
```
- [ ] **Step 2: `ReconciliationService.kt`**
```kotlin
package com.reckon.recon

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ReconciliationService(
    private val repo: ReconciliationRepository,
    @Value("\${reckon.reconciliation.stale-seconds}") private val staleSeconds: Long,
    @Value("\${reckon.reconciliation.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Run all three audits. Read-only — reports, never mutates (a drift is a bug to investigate, not hide). */
    fun run(): ReconciliationReport {
        val report = ReconciliationReport(
            unbalancedTransactions = repo.findUnbalancedTransactions(),
            balanceDrifts = repo.findBalanceDrifts(),
            stuckPending = repo.findStuckPending(staleSeconds),
        )
        if (!report.clean) {
            log.error("RECONCILIATION FAILED: {} unbalanced txns, {} balance drifts, {} stuck pending",
                report.unbalancedTransactions.size, report.balanceDrifts.size, report.stuckPending.size)
        } else {
            log.info("Reconciliation clean")
        }
        return report
    }

    @Scheduled(fixedDelayString = "\${reckon.reconciliation.poll-ms:60000}")
    fun scheduled() { if (enabled) run() }
}
```
- [ ] **Step 3:** add `reckon.reconciliation.enabled=false` to `PostgresTestBase`'s `@TestPropertySource` (so the scheduled audit doesn't fire mid-test; tests call `run()` directly). **Verify compiles**; **Commit** `git add -A && git commit -m "feat: reconciliation service + scheduled runner (detect, never mask)"`

---

## Task 4: Reconciliation tests
**Files:** Create `src/test/kotlin/com/reckon/recon/ReconciliationServiceTest.kt`
- [ ] **Step 1: Write** (extends `PostgresTestBase`; fund via the REAL saga so balances are ledgered — do NOT use `fixtures.walletWith(nonzero)` for the clean test, since raw-SQL seeding has no matching entry and would legitimately look like drift)
```kotlin
package com.reckon.recon

import com.reckon.account.SystemAccounts
import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.TxnType
import com.reckon.saga.AddMoneyService
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ReconciliationServiceTest : PostgresTestBase() {
    @Autowired lateinit var recon: ReconciliationService
    @Autowired lateinit var saga: AddMoneyService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var ledger: LedgerRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `a fully ledgered system passes all audits`() {
        // fund a wallet via the saga (writes balanced entries + balance atomically)
        val wallet = fixtures.walletWith(0)
        saga.addMoney("recon-clean-${UUID.randomUUID()}", UUID.randomUUID(), wallet, "ref", 50000)
        val report = recon.run()
        // no unbalanced txns and no balance drift anywhere in the system
        assertTrue(report.unbalancedTransactions.isEmpty(), "unbalanced: ${report.unbalancedTransactions}")
        assertTrue(report.balanceDrifts.isEmpty(), "drift: ${report.balanceDrifts}")
    }

    @Test fun `a corrupted balance is detected as drift`() {
        val wallet = fixtures.walletWith(0)
        saga.addMoney("recon-drift-${UUID.randomUUID()}", UUID.randomUUID(), wallet, "ref", 50000)
        // corrupt the stored balance WITHOUT a matching ledger entry -> drift
        jdbc.update("UPDATE accounts SET balance = balance + 999 WHERE id = ?", wallet)
        val report = recon.run()
        val drift = report.balanceDrifts.find { it.accountId == wallet }
        assertTrue(drift != null, "expected drift for $wallet, got ${report.balanceDrifts}")
        assertEquals(999, drift.storedBalance - drift.computedBalance)
        assertFalse(report.clean)
    }

    @Test fun `an unbalanced transaction is detected`() {
        // craft a transaction with a single DEBIT entry (does not net to zero)
        val wallet = fixtures.walletWith(0)
        val txnId = ledger.insertPending(TxnType.P2P, "recon-unbal-${UUID.randomUUID()}", "-", 100,
            UUID.randomUUID(), wallet, SystemAccounts.REWARDS_POOL)
        jdbc.update("INSERT INTO ledger_entries(transaction_id, account_id, direction, amount) VALUES (?,?,?,?)",
            txnId, wallet, "DEBIT", 100)   // lone debit, no matching credit
        val report = recon.run()
        assertTrue(report.unbalancedTransactions.any { it.transactionId == txnId },
            "expected unbalanced txn $txnId, got ${report.unbalancedTransactions}")
    }

    @Test fun `a stuck pending transaction is detected`() {
        val wallet = fixtures.walletWith(0)
        val txnId = ledger.insertPending(TxnType.P2P, "recon-stuck-${UUID.randomUUID()}", "-", 100,
            UUID.randomUUID(), wallet, SystemAccounts.REWARDS_POOL)
        // make it old so it crosses the stale threshold regardless of config
        jdbc.update("UPDATE transactions SET created_at = now() - interval '2 days' WHERE id = ?", txnId)
        val report = recon.run()
        assertTrue(report.stuckPending.contains(txnId), "expected $txnId in stuck list ${report.stuckPending}")
    }
}
```
Note: the clean-audit test asserts the *whole-system* `unbalancedTransactions`/`balanceDrifts` are empty. Because the shared singleton Postgres may carry rows from other test classes, this could be fragile IF another test left a deliberately-corrupted row uncommitted-cleaned. To keep it robust, the unbalanced/drift tests above check for the SPECIFIC crafted id/account, while the clean test asserts global emptiness — run the clean assertions tolerant of prior tests by noting: all prior tests in the suite use only ledgered flows or terminal states, so global emptiness holds. If the clean test proves flaky due to a deliberately-corrupted row from THIS class lingering, split this class so the clean test runs first, or scope the clean assertions to the accounts/txns it created. (Prefer scoping if flaky.)
- [ ] **Step 2: Run → PASS** (`./gradlew test --tests "com.reckon.recon.ReconciliationServiceTest"`).
- [ ] **Step 3: Run FULL suite** `./gradlew test` → all green. IMPORTANT: the `corrupted balance` and `unbalanced transaction` tests deliberately create drift/unbalanced rows in the shared DB. If this makes OTHER tests' or this class's "clean" assertion fail (because the corrupted rows persist in the singleton Postgres), you MUST isolate: either (a) clean up the crafted rows at the end of those tests (DELETE the crafted ledger_entries / reset the balance), or (b) scope the clean-audit assertions to only the accounts/txns that test created. Choose the approach that keeps the full suite reliably green WITHOUT weakening what each test proves. Document what you did.
- [ ] **Step 4: Commit** `git add -A && git commit -m "test: reconciliation detects drift, unbalanced txns, stuck pending; clean system passes"`

---

## Done criteria (Plan 6)
- `./gradlew test` green: ReconciliationServiceTest (4), all prior tests pass.
- A fully-ledgered system passes all audits; a corrupted balance, an unbalanced transaction, and a stuck-PENDING transaction are each detected.
- The scheduled runner logs an error when not clean; reconciliation is read-only (never masks a drift by auto-fixing).

## What's next
- Plan 7: k6 load test (concurrent transfers + duplicate-key retries, asserting zero balance inconsistencies / zero double-debits) + pessimistic-vs-optimistic locking benchmark. (Optional: Redis idempotency fast-path.)
