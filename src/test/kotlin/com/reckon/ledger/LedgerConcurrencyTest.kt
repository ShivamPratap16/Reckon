package com.reckon.ledger
import com.reckon.ledger.enums.TxnType
import com.reckon.ledger.repository.LedgerRepository
import com.reckon.ledger.service.LedgerService
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerConcurrencyTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService

    @Autowired lateinit var fixtures: Fixtures

    @Autowired lateinit var repo: LedgerRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `100 concurrent debits never overdraw and conserve money`() {
        // Fund account a with exactly 10 transfers worth of balance (10 × 100 = 1000)
        // then fire 20 attempts: exactly 10 should succeed, 10 should be INSUFFICIENT_FUNDS.
        val a = fixtures.walletWith(1000) // funds exactly 10 transfers of 100
        val b = fixtures.walletWith(0)
        val pool = Executors.newFixedThreadPool(8)
        val ok = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val allExceptions = CopyOnWriteArrayList<Pair<Int, Throwable>>()

        val tasks = (1..20).map { i ->
            // 20 attempts on a balance that funds only 10
            Runnable {
                try {
                    ledger.recordTransfer(TxnType.P2P, "c$i", "h$i", UUID.randomUUID(), a, b, 100)
                    ok.incrementAndGet()
                } catch (e: Exception) {
                    failed.incrementAndGet()
                    allExceptions.add(i to e)
                }
            }
        }
        tasks.forEach { pool.submit(it) }
        pool.shutdown()
        pool.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS)

        val successCount = ok.get()
        val insufficientFunds = allExceptions.count { (_, e) ->
            e is com.reckon.platform.exception.ApiException && e.code == "INSUFFICIENT_FUNDS"
        }
        val unexpectedExceptions = allExceptions.filter { (_, e) ->
            e !is com.reckon.platform.exception.ApiException || (e as com.reckon.platform.exception.ApiException).code != "INSUFFICIENT_FUNDS"
        }

        println("=== CONCURRENCY TEST SUMMARY ===")
        println("ok=$successCount, failed=${failed.get()}, insufficientFunds=$insufficientFunds, unexpected=${unexpectedExceptions.size}")
        unexpectedExceptions.forEach { (i, e) -> println("  Task $i failed with: ${e.javaClass.simpleName}: ${e.message}") }
        println("================================")

        // Money-conservation invariants — these MUST hold regardless of error count
        assertTrue(fixtures.balanceOf(a) >= 0, "account a went negative")
        val balanceA = fixtures.balanceOf(a)
        val balanceB = fixtures.balanceOf(b)
        assertEquals(1000L, balanceA + balanceB, "money was created or destroyed")

        // Atomicity: under the auto-commit bug, the `balance_nonneg` CHECK on the source account
        // could abort the `applyDelta` for `a` after the DEBIT/CREDIT entries had already committed,
        // leaving orphaned entries whose sum diverges from `a`'s actual balance.
        // a is seeded by raw SQL with no ledger entry — its entry sum must equal negative of amount moved
        val movedAmount = 1000L - balanceA
        assertEquals(-movedAmount, repo.sumEntries(a), "ledger entries for a don't match balance delta")
        // b starts at 0 so its entire history is in ledger entries: balance must equal sumEntries
        assertEquals(repo.sumEntries(b), balanceB, "ledger entries for b don't match balance (orphaned entries?)")

        // Exactly 2 entries per successful transfer — no orphaned entries from failed attempts
        val entriesForTheseAccounts = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries WHERE account_id IN (?, ?)",
            Long::class.java,
            a,
            b,
        )!!
        assertEquals(2L * successCount, entriesForTheseAccounts, "orphaned ledger entries found")

        // Every transfer that committed should be counted as ok (no unexpected errors)
        val committedCount = (movedAmount / 100).toInt()
        assertEquals(
            committedCount,
            successCount,
            "unexpected errors caused valid transfers to be counted as failures: ${unexpectedExceptions.map { (i, e) ->
                "task$i: ${e.javaClass.simpleName}(${e.message})"
            }}",
        )
        assertEquals(
            10,
            committedCount,
            "wrong number of transfers committed — only $committedCount succeeded instead of 10",
        )
    }

    @Test fun `bidirectional concurrent transfers do not deadlock and conserve money`() {
        val a = fixtures.walletWith(100000) // both well-funded so few/no INSUFFICIENT_FUNDS
        val b = fixtures.walletWith(100000)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
        val errors = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        val tasks = (1..200).map { i ->
            Runnable {
                val (from, to) = if (i % 2 == 0) a to b else b to a // half A->B, half B->A
                try {
                    ledger.recordTransfer(TxnType.P2P, "bidi-$i", "h$i", java.util.UUID.randomUUID(), from, to, 100)
                } catch (e: com.reckon.platform.exception.ApiException) {
                    if (e.code != "INSUFFICIENT_FUNDS") errors.add("${e.code}: ${e.message}")
                } catch (e: Exception) {
                    errors.add(e.javaClass.simpleName + ": " + e.message) // a deadlock would surface here
                }
            }
        }
        tasks.forEach { pool.submit(it) }
        pool.shutdown()
        val finished = pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)

        kotlin.test.assertTrue(finished, "tasks did not finish in time (possible deadlock)")
        kotlin.test.assertTrue(errors.isEmpty(), "unexpected errors (deadlock/serialization failure?): $errors")
        // money conserved across the two accounts:
        kotlin.test.assertEquals(200000L, fixtures.balanceOf(a) + fixtures.balanceOf(b))
        // ledger consistent with balances for both (both seeded at 100000 by raw SQL with no ledger entry,
        // so balance - seed == sumEntries):
        kotlin.test.assertEquals(fixtures.balanceOf(a) - 100000L, repo.sumEntries(a))
        kotlin.test.assertEquals(fixtures.balanceOf(b) - 100000L, repo.sumEntries(b))
    }
}
