package com.reckon.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import java.util.UUID

@Service
class JwtService(@Value("\${reckon.jwt.secret}") secret: String, @Value("\${reckon.jwt.ttl-minutes}") private val ttlMinutes: Long) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun issue(userId: UUID): String = Jwts.builder()
        .subject(userId.toString())
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + ttlMinutes * 60_000))
        .signWith(key)
        .compact()

    fun verify(token: String): UUID? = try {
        val sub = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).payload.subject
        UUID.fromString(sub)
    } catch (e: Exception) {
        null
    }
}
