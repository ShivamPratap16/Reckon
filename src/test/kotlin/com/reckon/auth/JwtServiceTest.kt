package com.reckon.auth

import com.reckon.auth.service.JwtService
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwtServiceTest {
    private val secret = "test-secret-that-is-definitely-at-least-256-bits-long-padding-xx"
    private val svc = JwtService(secret, ttlMinutes = 60)

    @Test fun `issued token verifies back to user id`() {
        val id = UUID.randomUUID()
        val token = svc.issue(id)
        assertEquals(id, svc.verify(token))
    }

    @Test fun `tampered token returns null`() {
        val token = svc.issue(UUID.randomUUID())
        assertNull(svc.verify(token + "x"))
    }
}
