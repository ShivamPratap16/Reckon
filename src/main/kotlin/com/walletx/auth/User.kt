package com.walletx.auth

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class User(val id: UUID, val email: String, val passwordHash: String)

@Repository
class UserRepository(private val jdbc: JdbcTemplate) {

    fun create(email: String, passwordHash: String): User {
        val id = jdbc.queryForObject(
            "INSERT INTO users(email, password_hash) VALUES (?, ?) RETURNING id",
            UUID::class.java, email, passwordHash,
        )!!
        return User(id, email, passwordHash)
    }

    fun findByEmail(email: String): User? =
        jdbc.query(
            "SELECT id, email, password_hash FROM users WHERE email = ?",
            { rs, _ -> User(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("password_hash")) },
            email,
        ).firstOrNull()
}
