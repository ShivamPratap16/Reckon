package com.reckon.auth.model

import java.util.UUID

data class User(val id: UUID, val email: String, val passwordHash: String)
