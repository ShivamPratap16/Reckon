package com.reckon.recon.model

import java.util.UUID

data class ReservedDrift(val accountId: UUID, val storedReserved: Long, val computedReserved: Long)
