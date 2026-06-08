package com.reckon.saga.dto.request

import jakarta.validation.constraints.Positive

data class AddMoneyRequest(val idempotencyKey: String, val bankRef: String, @field:Positive val amountPaisa: Long)
