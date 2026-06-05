package com.reckon.consumer

import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@TestPropertySource(properties = ["reckon.consumers.enabled=false"])
class TwoConsumerDedupTest : PostgresTestBase() {
    @Autowired lateinit var rewards: RewardsService
    @Autowired lateinit var notifications: NotificationsService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `rewards and notifications dedup the same event independently`() {
        val payer = fixtures.walletWith(0)
        val eventId = UUID.randomUUID()
        val e = PaymentEvent(eventId, UUID.randomUUID(), "P2P", payer, UUID.randomUUID(), 20000, "COMPLETED")

        rewards.award(e)
        assertTrue(notifications.notify(e))      // first notification succeeds
        assertFalse(notifications.notify(e))     // redelivery deduped
        rewards.award(e)                         // rewards redelivery deduped

        assertEquals(200, fixtures.balanceOf(payer))   // cashback once
        val rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Long::class.java, eventId)
        assertEquals(2L, rows)   // one row per consumer (rewards + notifications) for the SAME event
    }
}
