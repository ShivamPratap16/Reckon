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
    @Autowired lateinit var expiry: HoldExpiryService
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

    @Test fun `concurrent capture and expiry never double-release or double-move`() {
        val payer = fixtures.walletWith(50000); val payee = fixtures.walletWith(0)
        repeat(40) { i ->   // repeat to actually exercise the race
            val hold = auth.authorize("race-$i-${java.util.UUID.randomUUID()}", java.util.UUID.randomUUID(), payer, payee, 1000, 600)
            // make it expirable so the expiry sweep will act on it
            jdbc.update("UPDATE holds SET expires_at = now() - interval '1 hour' WHERE id = ?", hold)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            val captured = java.util.concurrent.atomic.AtomicInteger(0)
            val expired = java.util.concurrent.atomic.AtomicInteger(0)
            pool.submit { try { auth.capture(hold, payer, null); captured.incrementAndGet() } catch (e: Exception) {} }
            pool.submit { try { if (expiry.expireDue(java.time.Instant.now()) > 0) expired.incrementAndGet() } catch (e: Exception) {} }
            pool.shutdown(); pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
        }
        // after all rounds: reserved must be exactly 0 (never negative, never stranded), money conserved
        val reserved = jdbc.queryForObject("SELECT reserved_balance FROM accounts WHERE id=?", Long::class.java, payer)!!
        assertEquals(0L, reserved, "reservation must net to zero (no double-release / no leak)")
        assertEquals(50000L, fixtures.balanceOf(payer) + fixtures.balanceOf(payee), "money conserved")
        assertTrue(reserved >= 0, "reserved never negative")
    }
}
