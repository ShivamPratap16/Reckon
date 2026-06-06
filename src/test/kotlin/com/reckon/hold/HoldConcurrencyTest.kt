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
