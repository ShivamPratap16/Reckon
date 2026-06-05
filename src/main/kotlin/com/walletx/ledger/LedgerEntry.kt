package com.walletx.ledger

import java.util.UUID

enum class Direction { DEBIT, CREDIT }
enum class TxnType { ADD_MONEY, P2P, PAY_MERCHANT, CASHBACK }
enum class TxnStatus { PENDING, COMPLETED, FAILED, COMPENSATED }

data class LedgerEntry(val transactionId: UUID, val accountId: UUID, val direction: Direction, val amount: Long)
