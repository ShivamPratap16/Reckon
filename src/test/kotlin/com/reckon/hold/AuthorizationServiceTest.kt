package com.reckon.hold

import com.reckon.platform.ApiException
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
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
        val payer = fixtures.walletWith(50000)
        val payee = fixtures.walletWith(0)
        auth.authorize("h1", UUID.randomUUID(), payer, payee, 20000, 600)
        assertEquals(50000, fixtures.balanceOf(payer)) // balance unchanged
        assertEquals(20000, reserved(payer)) // reserved
        assertEquals(0, fixtures.balanceOf(payee))
    }

    @Test fun `authorize beyond available is rejected`() {
        val payer = fixtures.walletWith(10000)
        val payee = fixtures.walletWith(0)
        auth.authorize("h2", UUID.randomUUID(), payer, payee, 8000, 600)
        val ex = assertThrows<ApiException> { auth.authorize("h3", UUID.randomUUID(), payer, payee, 5000, 600) } // only 2000 available
        assertEquals("INSUFFICIENT_AVAILABLE", ex.code)
    }

    @Test fun `full capture moves money and releases the reservation`() {
        val payer = fixtures.walletWith(50000)
        val payee = fixtures.walletWith(0)
        val hold = auth.authorize("h4", UUID.randomUUID(), payer, payee, 20000, 600)
        auth.capture(hold, payer, null)
        assertEquals(30000, fixtures.balanceOf(payer)) // 50000 - 20000
        assertEquals(20000, fixtures.balanceOf(payee))
        assertEquals(0, reserved(payer)) // released
    }

    @Test fun `partial capture returns the remainder to available`() {
        val payer = fixtures.walletWith(50000)
        val payee = fixtures.walletWith(0)
        val hold = auth.authorize("h5", UUID.randomUUID(), payer, payee, 20000, 600)
        auth.capture(hold, payer, 12000)
        assertEquals(38000, fixtures.balanceOf(payer)) // only 12000 captured
        assertEquals(12000, fixtures.balanceOf(payee))
        assertEquals(0, reserved(payer)) // full reservation released; 8000 back to available
    }

    @Test fun `void releases the reservation`() {
        val payer = fixtures.walletWith(50000)
        val payee = fixtures.walletWith(0)
        val hold = auth.authorize("h6", UUID.randomUUID(), payer, payee, 20000, 600)
        auth.void(hold, payer)
        assertEquals(50000, fixtures.balanceOf(payer))
        assertEquals(0, reserved(payer))
    }

    @Test fun `authorize is idempotent`() {
        val payer = fixtures.walletWith(50000)
        val payee = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val a = auth.authorize("h7", initiator, payer, payee, 20000, 600)
        val b = auth.authorize("h7", initiator, payer, payee, 20000, 600)
        assertEquals(a, b)
        assertEquals(20000, reserved(payer)) // reserved once, not twice
    }

    @Test fun `capturing a voided hold is rejected`() {
        val payer = fixtures.walletWith(50000)
        val payee = fixtures.walletWith(0)
        val hold = auth.authorize("h8", UUID.randomUUID(), payer, payee, 20000, 600)
        auth.void(hold, payer)
        val ex = assertThrows<ApiException> { auth.capture(hold, payer, null) }
        assertEquals("HOLD_NOT_HELD", ex.code)
    }
}
