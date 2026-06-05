package com.reckon.outbox

import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestPropertySource(properties = [
    "reckon.outbox.scheduler.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",      // unreachable
    "spring.kafka.producer.properties.max.block.ms=1500",
    "reckon.outbox.send-timeout-ms=2000",
])
class OutboxFailureTest : PostgresTestBase() {
    @Autowired lateinit var publisher: OutboxPublisher
    @Autowired lateinit var outbox: OutboxRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach fun clean() { jdbc.execute("TRUNCATE outbox RESTART IDENTITY CASCADE") }

    @Test fun `send failure records error, leaves row unpublished for retry`() {
        val agg = UUID.randomUUID()
        outbox.append(agg, EventType.PAYMENT_COMPLETED, """{"eventId":null,"transactionId":"$agg"}""")
        publisher.publishBatch()   // broker unreachable -> send fails -> recordFailure
        val row = jdbc.queryForMap(
            "SELECT published, attempts, last_error FROM outbox WHERE aggregate_id = ?", agg)
        assertEquals(false, row["published"])                 // NOT marked published
        assertTrue((row["attempts"] as Number).toInt() >= 1)  // attempt recorded
        assertTrue(row["last_error"] != null)                 // error captured
    }
}
