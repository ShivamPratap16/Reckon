package com.walletx.ledger

import com.walletx.support.Fixtures
import com.walletx.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerConcurrencyTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var fixtures: Fixtures

    @Test fun `100 concurrent debits never overdraw and conserve money`() {
        val a = fixtures.walletWith(10000)   // ₹100 -> exactly 100 transfers of ₹1
        val b = fixtures.walletWith(0)
        val pool = Executors.newFixedThreadPool(16)
        val ok = AtomicInteger(0); val failed = AtomicInteger(0)

        val tasks = (1..200).map { i ->   // 200 attempts on a balance that funds only 100
            Runnable {
                try {
                    ledger.recordTransfer(TxnType.P2P, "c$i", "h$i", UUID.randomUUID(), a, b, 100)
                    ok.incrementAndGet()
                } catch (e: Exception) { failed.incrementAndGet() }
            }
        }
        tasks.forEach { pool.submit(it) }
        pool.shutdown(); pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(100, ok.get())                    // exactly the funded number succeeded
        assertEquals(100, failed.get())                // the rest correctly rejected
        assertEquals(0, fixtures.balanceOf(a))         // never negative
        assertEquals(10000, fixtures.balanceOf(b))     // all money landed
        assertTrue(fixtures.balanceOf(a) >= 0)
    }
}
