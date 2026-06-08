package com.reckon.ledger.model

import java.util.UUID

data class ExistingTxn(
    val id: UUID,
    val status: String,
    val requestHash: String,
    val responseCode: Int?,
    val responseBody: String?,
    val failureReason: String?,
)
