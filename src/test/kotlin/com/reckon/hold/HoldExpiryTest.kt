package com.reckon.hold
import com.reckon.hold.service.AuthorizationService
import com.reckon.hold.service.HoldExpiryService
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
        val payer = fixtures.walletWith(50000)
        val payee = fixtures.walletWith(0)
        val hold = auth.authorize("e1", UUID.randomUUID(), payer, payee, 20000, 600)
        jdbc.update("UPDATE holds SET expires_at = now() - interval '1 hour' WHERE id = ?", hold)
        val released = expiry.expireDue(Instant.now())
        assertEquals(1, released)
        assertEquals(0, jdbc.queryForObject("SELECT reserved_balance FROM accounts WHERE id=?", Long::class.java, payer))
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM holds WHERE id=?", String::class.java, hold))
    }
}
