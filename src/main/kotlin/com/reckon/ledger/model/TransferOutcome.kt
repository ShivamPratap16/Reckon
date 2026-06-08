package com.reckon.ledger.model

import java.util.UUID

data class TransferOutcome(val transactionId: UUID, val status: String, val replayed: Boolean)
