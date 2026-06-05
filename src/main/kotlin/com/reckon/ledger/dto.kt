package com.reckon.ledger

import jakarta.validation.constraints.Positive
import java.util.UUID

data class P2pRequest(
    val idempotencyKey: String,
    val toUserId: UUID,
    @field:Positive val amountPaisa: Long,
)
data class TransferResult(val transactionId: UUID, val status: String)
