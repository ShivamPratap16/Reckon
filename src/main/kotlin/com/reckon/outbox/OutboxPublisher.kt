package com.reckon.outbox

import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OutboxPublisher(
    private val outbox: OutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    @Value("\${reckon.outbox.topic}") private val topic: String,
    @Value("\${reckon.outbox.batch-size}") private val batchSize: Int,
) {
    /**
     * Claim a batch of unpublished events and publish them. Runs in a transaction so the
     * FOR UPDATE SKIP LOCKED row locks are held while publishing (prevents two instances
     * double-claiming). At-least-once: a crash after send() but before markPublished re-sends.
     * Kafka key = aggregateId so all events for one transaction land on one partition, in order.
     */
    @Transactional
    fun publishBatch(): Int {
        val rows = outbox.fetchUnpublished(batchSize)
        for (row in rows) {
            try {
                kafka.send(topic, row.aggregateId.toString(), envelope(row)).get()
                outbox.markPublished(row.id)
            } catch (e: Exception) {
                outbox.recordFailure(row.id, e.message ?: e.javaClass.simpleName)
            }
        }
        return rows.size
    }

    /** Wrap the stored payload with the authoritative event_id (consumer dedup key in Plan 4). */
    private fun envelope(row: OutboxRow): String =
        row.payload.replaceFirst("\"eventId\":null", "\"eventId\":\"${row.eventId}\"")

    @Scheduled(fixedDelayString = "\${reckon.outbox.poll-ms:1000}")
    fun scheduledPublish() {
        if (schedulerEnabled) publishBatch()
    }

    @Value("\${reckon.outbox.scheduler.enabled:true}")
    private var schedulerEnabled: Boolean = true
}
