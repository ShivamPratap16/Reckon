package com.reckon.outbox.model

import java.util.UUID

data class OutboxRow(val id: Long, val eventId: UUID, val aggregateId: UUID, val eventType: String, val payload: String)
