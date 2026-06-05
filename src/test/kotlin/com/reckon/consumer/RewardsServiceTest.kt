package com.reckon.consumer

import com.reckon.account.SystemAccounts
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals

@TestPropertySource(properties = ["reckon.consumers.enabled=false"])
class RewardsServiceTest : PostgresTestBase() {
    @Autowired lateinit var rewards: RewardsService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun event(payer: UUID, amount: Long, id: UUID = UUID.randomUUID(), type: String = "P2P") =
        PaymentEvent(id, UUID.randomUUID(), type, payer, UUID.randomUUID(), amount, "COMPLETED")

    @Test fun `cashback is applied once and is idempotent on redelivery`() {
        val payer = fixtures.walletWith(0)
        val rewardsPoolBefore = jdbc.queryForObject(
            "SELECT balance FROM accounts WHERE id = ?", Long::class.java, SystemAccounts.REWARDS_POOL)!!
        val e = event(payer, 20000)        // ₹200 -> 1% = 200 paisa cashback

        rewards.award(e)
        rewards.award(e)                   // redelivery (same eventId) -> must be a no-op
        rewards.award(e)                   // and again

        assertEquals(200, fixtures.balanceOf(payer))   // credited EXACTLY once
        val rewardsPoolAfter = jdbc.queryForObject(
            "SELECT balance FROM accounts WHERE id = ?", Long::class.java, SystemAccounts.REWARDS_POOL)!!
        assertEquals(rewardsPoolBefore - 200, rewardsPoolAfter)   // pool debited exactly once (may go negative)
        // exactly one CASHBACK transaction for this payer
        val cashbackTxns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE type='CASHBACK' AND to_account_id=?", Long::class.java, payer)
        assertEquals(1L, cashbackTxns)
    }

    @Test fun `cashback event type is skipped (no loop)`() {
        val payer = fixtures.walletWith(0)
        rewards.award(event(payer, 20000, type = "CASHBACK"))
        assertEquals(0, fixtures.balanceOf(payer))   // no cashback on a cashback event
    }
}
