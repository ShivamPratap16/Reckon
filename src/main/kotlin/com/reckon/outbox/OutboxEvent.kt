package com.reckon.outbox

import java.util.UUID

object EventType { const val PAYMENT_COMPLETED = "payment.completed" }

data class OutboxRow(
    val id: Long,
    val eventId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
)
