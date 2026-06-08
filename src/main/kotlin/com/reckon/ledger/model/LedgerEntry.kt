package com.reckon.ledger.model

import com.reckon.ledger.enums.Direction
import java.util.UUID

data class LedgerEntry(val transactionId: UUID, val accountId: UUID, val direction: Direction, val amount: Long)
