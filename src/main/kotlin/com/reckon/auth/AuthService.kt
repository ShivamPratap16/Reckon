package com.reckon.auth

import com.reckon.account.AccountRepository
import com.reckon.platform.ApiException
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
        val user = users.create(email, encoder.encode(password))
        accounts.createWallet(user.id) // every user gets a wallet
        return jwt.issue(user.id)
    }

    fun login(email: String, password: String): String {
        val user = users.findByEmail(email)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "invalid email or password")
        if (!encoder.matches(password, user.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "invalid email or password")
        }
        return jwt.issue(user.id)
    }
}
