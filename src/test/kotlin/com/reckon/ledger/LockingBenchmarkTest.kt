package com.reckon.ledger

import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockingBenchmarkTest : PostgresTestBase() {
    @Autowired lateinit var pessimistic: TransferExecutor

    @Autowired lateinit var optimistic: OptimisticTransferExecutor

    @Autowired lateinit var ledger: LedgerRepository

    @Autowired lateinit var fixtures: Fixtures

    @Autowired lateinit var jdbc: JdbcTemplate

    private val TRANSFERS = 200
    private val THREADS = 8
    private val HOT_ACCOUNTS = 8 // more accounts = less per-account contention for optimistic

    private fun sumEntries(a: UUID) = ledger.sumEntries(a)

    @Test fun `pessimistic vs optimistic - both correct, throughput logged`() {
        val resultP = runWorkload("pessimistic")
        val resultO = runWorkload("optimistic")
        println("=== LOCKING BENCHMARK ($TRANSFERS transfers, $THREADS threads, hot $HOT_ACCOUNTS-account set) ===")
        println("pessimistic: ${resultP.ms}ms  ${"%.0f".format(TRANSFERS * 1000.0 / resultP.ms)} tps  ok=${resultP.ok}")
        println(
            "optimistic : ${resultO.ms}ms  ${"%.0f".format(TRANSFERS * 1000.0 / resultO.ms)} tps  ok=${resultO.ok}  avgAttempts=${"%.2f".format(
                resultO.attempts.toDouble() / maxOf(1,resultO.ok),
            )}",
        )
        // both must be correct
        assertTrue(resultP.ok > 0 && resultO.ok > 0)
    }

    private data class Result(val ms: Long, val ok: Int, val attempts: Long)

    private fun runWorkload(strategy: String): Result {
        // hot wallets, each funded enough that no transfer is rejected for funds
        val seedBalance = 100_000_000L
        val accts = (1..HOT_ACCOUNTS).map { fixtures.walletWith(seedBalance) }
        val before = accts.sumOf { fixtures.balanceOf(it) }
        val pool = Executors.newFixedThreadPool(THREADS)
        val ok = AtomicInteger(0)
        val attempts = AtomicLong(0)
        val start = System.nanoTime()
        (1..TRANSFERS).forEach { i ->
            pool.submit {
                val from = accts[i % HOT_ACCOUNTS]
                val to = accts[(i + 1) % HOT_ACCOUNTS]
                try {
                    val txnId = ledger.insertPending(TxnType.P2P, "$strategy-$i", "-", 100, UUID.randomUUID(), from, to)
                    if (strategy == "pessimistic") {
                        pessimistic.execute(txnId, TxnType.P2P, from, to, 100, emitEvent = false)
                    } else {
                        attempts.addAndGet(optimistic.execute(txnId, TxnType.P2P, from, to, 100).toLong())
                    }
                    ok.incrementAndGet()
                } catch (e: Exception) { /* contention give-up counts as not-ok */ }
            }
        }
        pool.shutdown()
        pool.awaitTermination(300, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - start) / 1_000_000
        // CORRECTNESS: money conserved across the hot set
        val after = accts.sumOf { fixtures.balanceOf(it) }
        assertEquals(before, after, "[$strategy] money not conserved")
        // balance delta == sum(entries): seeded balance (not in ledger) + net entries == current balance
        accts.forEach {
            val net = seedBalance + sumEntries(it)
            assertEquals(fixtures.balanceOf(it), net, "[$strategy] balance != seedBalance + sum(entries) for $it")
        }
        return Result(ms, ok.get(), attempts.get())
    }
}
