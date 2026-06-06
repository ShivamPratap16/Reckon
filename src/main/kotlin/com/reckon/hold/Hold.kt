package com.reckon.hold

import java.time.Instant
import java.util.UUID

enum class HoldStatus { HELD, CAPTURED, VOIDED, EXPIRED }

data class Hold(
    val id: UUID, val payerAccountId: UUID, val payeeAccountId: UUID,
    val amount: Long, val capturedAmount: Long, val status: HoldStatus, val expiresAt: Instant,
)
