package com.reckon.hold.model

import com.reckon.hold.enums.HoldStatus
import java.time.Instant
import java.util.UUID

data class Hold(
    val id: UUID,
    val payerAccountId: UUID,
    val payeeAccountId: UUID,
    val amount: Long,
    val capturedAmount: Long,
    val status: HoldStatus,
    val expiresAt: Instant,
)
