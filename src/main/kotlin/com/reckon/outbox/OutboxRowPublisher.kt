package com.reckon.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

/**
 * Publishes ONE outbox row in its OWN transaction (REQUIRES_NEW) so per-row success/failure
 * is durable and isolated: a send failure on one row cannot roll back another row's markPublished.
 * Lives in a separate bean so the @Transactional proxy actually engages when called per-row
 * from OutboxPublisher (a same-bean self-call would bypass the proxy).
 */
@Service
class OutboxRowPublisher(
    private val outbox: OutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val mapper: ObjectMapper,
    @Value("\${reckon.outbox.topic}") private val topic: String,
    @Value("\${reckon.outbox.send-timeout-ms:10000}") private val sendTimeoutMs: Long,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun publish(row: OutboxRow) {
        try {
            kafka.send(topic, row.aggregateId.toString(), envelope(row))
                .get(sendTimeoutMs, TimeUnit.MILLISECONDS)       // bounded — no indefinite stall
            outbox.markPublished(row.id)
        } catch (e: Exception) {
            outbox.recordFailure(row.id, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Inject the authoritative event_id into the stored payload JSON (robust — no string-replace). */
    private fun envelope(row: OutboxRow): String {
        val node = mapper.readTree(row.payload) as ObjectNode
        node.put("eventId", row.eventId.toString())
        return mapper.writeValueAsString(node)
    }
}
