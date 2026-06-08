package com.reckon.outbox.repository

import com.reckon.outbox.model.OutboxRow
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class OutboxRepository(private val jdbc: JdbcTemplate) {

    /** Append an event. MUST be called inside the caller's transaction (e.g. the transfer txn). */
    fun append(aggregateId: UUID, eventType: String, payloadJson: String) {
        jdbc.update(
            "INSERT INTO outbox(aggregate_id, event_type, payload) VALUES (?, ?, ?::jsonb)",
            aggregateId,
            eventType,
            payloadJson,
        )
    }

    /** Claim a batch of unpublished rows, skipping rows locked by another publisher instance. */
    fun fetchUnpublished(limit: Int): List<OutboxRow> = jdbc.query(
        """SELECT id, event_id, aggregate_id, event_type, payload
           FROM outbox WHERE published = false
           ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
        { rs, _ ->
            OutboxRow(
                rs.getLong("id"),
                rs.getObject("event_id", UUID::class.java),
                rs.getObject("aggregate_id", UUID::class.java),
                rs.getString("event_type"),
                rs.getString("payload"),
            )
        },
        limit,
    )

    fun markPublished(id: Long) = jdbc.update(
        "UPDATE outbox SET published = true, published_at = now() WHERE id = ?",
        id,
    )

    fun recordFailure(id: Long, error: String) = jdbc.update(
        "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
        error.take(500),
        id,
    )
}
