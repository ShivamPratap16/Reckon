package com.reckon.outbox.service

import com.reckon.outbox.repository.OutboxRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// NOTE: Permanently-failing rows (e.g. bad payload) are retried every poll with no dead-letter
// cap yet — known limitation. The `attempts` column is recorded for future DLQ logic.
@Component
class OutboxPublisher(
    private val outbox: OutboxRepository,
    private val rowPublisher: OutboxRowPublisher,
    @Value("\${reckon.outbox.batch-size}") private val batchSize: Int,
    @Value("\${reckon.outbox.scheduler.enabled:true}") private val schedulerEnabled: Boolean,
) {
    /** Claim a batch and publish each row in its own transaction. At-least-once: an un-marked
     *  row (crash between send and mark) is re-sent next poll; duplicates are deduped downstream. */
    fun publishBatch(): Int {
        val rows = outbox.fetchUnpublished(batchSize)
        rows.forEach { rowPublisher.publish(it) }
        return rows.size
    }

    @Scheduled(fixedDelayString = "\${reckon.outbox.poll-ms:1000}")
    fun scheduledPublish() {
        if (schedulerEnabled) publishBatch()
    }
}
