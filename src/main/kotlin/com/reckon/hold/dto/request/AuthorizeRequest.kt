package com.reckon.hold.dto.request

import jakarta.validation.constraints.Positive
import java.util.UUID

data class AuthorizeRequest(val idempotencyKey: String, val toUserId: UUID, @field:Positive val amountPaisa: Long, val ttlSeconds: Long = 600)
