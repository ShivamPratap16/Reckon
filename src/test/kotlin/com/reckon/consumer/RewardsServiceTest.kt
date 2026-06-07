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
            "SELECT balance FROM accounts WHERE id = ?",
            Long::class.java,
            SystemAccounts.REWARDS_POOL,
        )!!
        val e = event(payer, 20000) // ₹200 -> 1% = 200 paisa cashback

        rewards.award(e)
        rewards.award(e) // redelivery (same eventId) -> must be a no-op
        rewards.award(e) // and again

        assertEquals(200, fixtures.balanceOf(payer)) // credited EXACTLY once
        val rewardsPoolAfter = jdbc.queryForObject(
            "SELECT balance FROM accounts WHERE id = ?",
            Long::class.java,
            SystemAccounts.REWARDS_POOL,
        )!!
        assertEquals(rewardsPoolBefore - 200, rewardsPoolAfter) // pool debited exactly once (may go negative)
        // exactly one CASHBACK transaction for this payer
        val cashbackTxns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE type='CASHBACK' AND to_account_id=?",
            Long::class.java,
            payer,
        )
        assertEquals(1L, cashbackTxns)
    }

    @Test fun `cashback event type is skipped (no loop)`() {
        val payer = fixtures.walletWith(0)
        rewards.award(event(payer, 20000, type = "CASHBACK"))
        assertEquals(0, fixtures.balanceOf(payer)) // no cashback on a cashback event
    }

    @Test fun `concurrent redelivery of same event awards cashback exactly once`() {
        val payer = fixtures.walletWith(0)
        val eventId = java.util.UUID.randomUUID()
        val e = PaymentEvent(eventId, java.util.UUID.randomUUID(), "P2P", payer, java.util.UUID.randomUUID(), 20000, "COMPLETED")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val errors = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val tasks = (1..8).map {
            Runnable {
                try {
                    rewards.award(e)
                } catch (ex: Exception) {
                    errors.add(ex.javaClass.simpleName + ":" + ex.message)
                }
            }
        }
        tasks.forEach { pool.submit(it) }
        pool.shutdown()
        pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
        kotlin.test.assertTrue(errors.isEmpty(), "unexpected errors: $errors")
        assertEquals(200, fixtures.balanceOf(payer)) // exactly one cashback despite 8 concurrent deliveries
        val cashbackTxns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE type='CASHBACK' AND to_account_id=?",
            Long::class.java,
            payer,
        )
        assertEquals(1L, cashbackTxns)
    }

    @Test fun `failure after dedup mark rolls back the processed_events row`() {
        // payer account does NOT exist -> executor.lockByIdsInOrder throws inside recordCashback,
        // after markProcessed already inserted in the same transaction -> whole txn must roll back
        val ghostPayer = java.util.UUID.randomUUID()
        val eventId = java.util.UUID.randomUUID()
        val e = PaymentEvent(eventId, java.util.UUID.randomUUID(), "P2P", ghostPayer, java.util.UUID.randomUUID(), 20000, "COMPLETED")
        org.junit.jupiter.api.assertThrows<Exception> { rewards.award(e) }
        val marks = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE consumer_name='rewards' AND event_id=?",
            Long::class.java,
            eventId,
        )
        assertEquals(0L, marks) // dedup mark rolled back with the failed cashback -> event can be retried
    }
}
