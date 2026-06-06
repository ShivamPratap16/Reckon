package com.reckon.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentEvent(
    val eventId: UUID,
    val transactionId: UUID,
    val type: String,
    val fromAccountId: UUID?,
    val toAccountId: UUID?,
    val amount: Long,
    val status: String,
)
