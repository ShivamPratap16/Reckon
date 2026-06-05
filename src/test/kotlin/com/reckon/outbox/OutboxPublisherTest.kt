package com.reckon.outbox

import com.reckon.support.KafkaPostgresTestBase
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.utils.KafkaTestUtils
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboxPublisherTest : KafkaPostgresTestBase() {
    @Autowired lateinit var publisher: OutboxPublisher
    @Autowired lateinit var outbox: OutboxRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Value("\${spring.kafka.bootstrap-servers}") lateinit var bootstrap: String
    @Value("\${reckon.outbox.topic}") lateinit var topic: String

    @Test fun `publishBatch sends unpublished events to kafka and marks them published`() {
        val aggregate = UUID.randomUUID()
        outbox.append(aggregate, EventType.PAYMENT_COMPLETED,
            """{"eventId":null,"transactionId":"$aggregate","status":"COMPLETED"}""")

        // consumer subscribed before publish
        val props = KafkaTestUtils.consumerProps(bootstrap, "test-group", "true")
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        val consumer = DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer())
            .createConsumer()
        consumer.subscribe(listOf(topic))

        val published = publisher.publishBatch()
        assertEquals(1, published)

        val records = consumer.poll(Duration.ofSeconds(10))
        consumer.close()
        assertEquals(1, records.count())
        val record = records.iterator().next()
        assertEquals(aggregate.toString(), record.key())                 // partition key = aggregateId
        assertTrue(record.value().contains("\"eventId\"") && !record.value().contains("null"))  // envelope filled real eventId (not null)

        // row marked published, won't be re-sent
        val unpublished = jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND published = false", Long::class.java, aggregate)
        assertEquals(0L, unpublished)
        assertEquals(0, publisher.publishBatch())                        // nothing left to publish
    }
}
