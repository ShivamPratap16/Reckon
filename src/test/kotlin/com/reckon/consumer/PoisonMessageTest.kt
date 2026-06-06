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
class PoisonMessageTest : KafkaPostgresTestBase() {
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var fixtures: Fixtures
    @Value("\${reckon.outbox.topic}") lateinit var topic: String

    @Test fun `a malformed message does not block a subsequent valid one`() {
        val payer = fixtures.walletWith(0)
        val key = payer.toString()
        // same key -> same partition -> ordered: poison first, then valid
        kafka.send(topic, key, "this-is-not-json").get()
        val valid = """{"eventId":"${UUID.randomUUID()}","transactionId":"${UUID.randomUUID()}","type":"P2P",""" +
            """"fromAccountId":"$payer","toAccountId":"${UUID.randomUUID()}","amount":50000,"status":"COMPLETED"}"""
        kafka.send(topic, key, valid).get()

        // the valid message must still be processed (cashback 1% of 50000 = 500) despite the poison ahead of it
        var balance = 0L
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            balance = fixtures.balanceOf(payer)
            if (balance == 500L) break
            Thread.sleep(250)
        }
        assertEquals(500L, balance)   // poison was DLT'd / skipped, partition not blocked
    }
}
