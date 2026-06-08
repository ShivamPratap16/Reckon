package com.reckon.platform.util

import java.security.MessageDigest
import java.util.UUID

object RequestHash {
    fun of(type: String, from: UUID, to: UUID, amount: Long): String {
        val payload = "$type|$from|$to|$amount"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
