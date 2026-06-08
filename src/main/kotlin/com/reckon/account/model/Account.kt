package com.reckon.account.model

import com.reckon.account.enums.AccountType
import java.util.UUID

data class Account(val id: UUID, val ownerId: UUID?, val type: AccountType, val balance: Long, val version: Long, val reserved: Long = 0)
