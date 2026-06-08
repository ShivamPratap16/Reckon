package com.reckon.idempotency.model

import java.util.UUID

/** A cached TERMINAL idempotency result (COMPLETED or FAILED). Never caches in-flight PENDING. */
data class CachedResult(val transactionId: UUID, val status: String, val requestHash: String, val failureCode: String?)
