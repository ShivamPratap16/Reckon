package com.reckon.ledger.dto.response

import java.util.UUID

data class TransferResult(val transactionId: UUID, val status: String)
