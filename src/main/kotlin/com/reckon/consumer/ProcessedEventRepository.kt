package com.reckon.consumer

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ProcessedEventRepository(private val jdbc: JdbcTemplate) {
    /** Returns true if THIS call claimed the event (first time); false if already processed. */
    fun markProcessed(consumer: String, eventId: UUID): Boolean =
        jdbc.update(
            "INSERT INTO processed_events(consumer_name, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            consumer, eventId,
        ) == 1
}
