package com.walletx.ledger

import com.walletx.support.Fixtures
import com.walletx.support.PostgresTestBase
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
        val a = fixtures.walletWith(1000)   // funds exactly 10 transfers of 100
        val b = fixtures.walletWith(0)
        val pool = Executors.newFixedThreadPool(8)
        val ok = AtomicInteger(0); val failed = AtomicInteger(0)
        val allExceptions = CopyOnWriteArrayList<Pair<Int, Throwable>>()

        val tasks = (1..20).map { i ->   // 20 attempts on a balance that funds only 10
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
        pool.shutdown(); pool.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS)

        val successCount = ok.get()
        val insufficientFunds = allExceptions.count { (_, e) ->
            e is com.walletx.platform.ApiException && e.code == "INSUFFICIENT_FUNDS"
        }
        val unexpectedExceptions = allExceptions.filter { (_, e) ->
            e !is com.walletx.platform.ApiException || (e as com.walletx.platform.ApiException).code != "INSUFFICIENT_FUNDS"
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

        // Atomicity: under the auto-commit bug, failed attempts would leave orphaned
        // CREDIT entries on b with no balance increment, so sumEntries(b) > balanceOf(b).
        // a is seeded by raw SQL with no ledger entry — its entry sum must equal negative of amount moved
        val movedAmount = 1000L - balanceA
        assertEquals(-movedAmount, repo.sumEntries(a), "ledger entries for a don't match balance delta")
        // b starts at 0 so its entire history is in ledger entries: balance must equal sumEntries
        assertEquals(repo.sumEntries(b), balanceB, "ledger entries for b don't match balance (orphaned entries?)")

        // Exactly 2 entries per successful transfer — no orphaned entries from failed attempts
        val totalEntries = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long::class.java)!!
        assertEquals(2L * successCount, totalEntries, "orphaned ledger entries found")

        // Every transfer that committed should be counted as ok (no unexpected errors)
        val committedCount = (movedAmount / 100).toInt()
        assertEquals(committedCount, successCount,
            "unexpected errors caused valid transfers to be counted as failures: ${unexpectedExceptions.map { (i, e) -> "task$i: ${e.javaClass.simpleName}(${e.message})" }}")
        assertEquals(10, committedCount,
            "wrong number of transfers committed — only $committedCount succeeded instead of 10")
    }
}
