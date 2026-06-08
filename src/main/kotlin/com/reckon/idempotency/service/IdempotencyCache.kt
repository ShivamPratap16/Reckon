package com.reckon.idempotency.service

import com.reckon.idempotency.model.CachedResult
import java.util.UUID

interface IdempotencyCache {
    fun get(initiatorId: UUID, idempotencyKey: String): CachedResult?
    fun put(initiatorId: UUID, idempotencyKey: String, result: CachedResult)
}
