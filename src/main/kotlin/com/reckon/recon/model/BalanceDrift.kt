package com.reckon.recon.model

import java.util.UUID

data class BalanceDrift(val accountId: UUID, val storedBalance: Long, val computedBalance: Long)
