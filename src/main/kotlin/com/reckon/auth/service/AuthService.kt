package com.reckon.auth.service

import com.reckon.account.repository.AccountRepository
import com.reckon.auth.entity.User
import com.reckon.auth.repository.UserRepository
import com.reckon.platform.exception.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(private val users: UserRepository, private val accounts: AccountRepository, private val jwt: JwtService) {
    private val encoder = BCryptPasswordEncoder()

    @Transactional
    fun signup(email: String, password: String): String {
        if (users.findByEmail(email) != null) {
            throw ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "email already registered")
        }
        // saveAndFlush (not save): this runs in the same @Transactional as the JdbcTemplate
        // wallet insert below, which has a FK to users.id. JPA defers INSERTs until flush, so we
        // must flush the user row to the DB before the raw-JDBC accounts insert references it.
        val user = users.saveAndFlush(User(email = email, passwordHash = encoder.encode(password)))
        accounts.createWallet(user.id!!) // every user gets a wallet
        return jwt.issue(user.id!!)
    }

    fun login(email: String, password: String): String {
        val user = users.findByEmail(email)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "invalid email or password")
        if (!encoder.matches(password, user.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "invalid email or password")
        }
        return jwt.issue(user.id!!)
    }
}
