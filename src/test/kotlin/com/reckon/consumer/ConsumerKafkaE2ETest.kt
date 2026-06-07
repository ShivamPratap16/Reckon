package com.reckon.consumer

import com.reckon.support.Fixtures
import com.reckon.support.KafkaPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals

@TestPropertySource(properties = ["reckon.consumers.enabled=true"])
class ConsumerKafkaE2ETest : KafkaPostgresTestBase() {
    @Autowired lateinit var kafka: KafkaTemplate<String, String>

    @Autowired lateinit var fixtures: Fixtures

    @Autowired lateinit var rewards: RewardsService

    // not used directly; ensures context wired
    @Value("\${reckon.outbox.topic}")
    lateinit var topic: String

    @Test fun `payment event published to kafka results in cashback via the consumer`() {
        val payer = fixtures.walletWith(0)
        val eventId = UUID.randomUUID()
        val payload = """{"eventId":"$eventId","transactionId":"${UUID.randomUUID()}","type":"P2P",""" +
            """"fromAccountId":"$payer","toAccountId":"${UUID.randomUUID()}","amount":50000,"status":"COMPLETED"}"""

        kafka.send(topic, payer.toString(), payload).get()

        // await async consumption: cashback = 1% of 50000 = 500 paisa
        var balance = 0L
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            balance = fixtures.balanceOf(payer)
            if (balance == 500L) break
            Thread.sleep(250)
        }
        assertEquals(500L, balance) // consumer awarded cashback end-to-end
    }
}
