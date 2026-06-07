package com.reckon.auth

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Refuses to boot under the `prod` profile if the JWT secret is the known dev default.
 * Non-prod profiles (local, test) are unaffected — they use the default without restriction.
 */
@Component
class JwtSecretValidator(@Value("\${reckon.jwt.secret}") private val secret: String, private val environment: Environment) {
    companion object {
        private const val DEV_DEFAULT = "dev-only-secret-change-me-must-be-at-least-256-bits-long-xxxxx"
        private const val MIN_SECRET_LENGTH = 32
    }

    @PostConstruct
    fun validate() {
        val isProd = environment.activeProfiles.contains("prod")
        if (!isProd) return

        check(secret != DEV_DEFAULT) {
            "FATAL: JWT secret is the dev default. Set JWT_SECRET env var before starting in prod."
        }
        check(secret.length >= MIN_SECRET_LENGTH) {
            "FATAL: JWT secret is too short (${secret.length} chars). Minimum $MIN_SECRET_LENGTH required in prod."
        }
    }
}
