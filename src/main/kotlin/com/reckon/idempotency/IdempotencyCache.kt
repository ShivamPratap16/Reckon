package com.reckon.idempotency

import java.util.UUID

/** A cached TERMINAL idempotency result (COMPLETED or FAILED). Never caches in-flight PENDING. */
data class CachedResult(val transactionId: UUID, val status: String, val requestHash: String, val failureCode: String?)

interface IdempotencyCache {
    fun get(initiatorId: UUID, idempotencyKey: String): CachedResult?
    fun put(initiatorId: UUID, idempotencyKey: String, result: CachedResult)
}
