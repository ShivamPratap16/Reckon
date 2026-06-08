package com.reckon.recon.model

import java.util.UUID

data class UnbalancedTxn(val transactionId: UUID, val net: Long)
