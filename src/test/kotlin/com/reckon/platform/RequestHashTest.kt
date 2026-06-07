package com.reckon.platform

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RequestHashTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val b = UUID.fromString("00000000-0000-0000-0000-0000000000bb")

    @Test fun `same inputs produce same hash`() {
        assertEquals(RequestHash.of("P2P", a, b, 100), RequestHash.of("P2P", a, b, 100))
    }

    @Test fun `different amount produces different hash`() {
        assertNotEquals(RequestHash.of("P2P", a, b, 100), RequestHash.of("P2P", a, b, 200))
    }

    @Test fun `is stable hex string (deterministic across calls)`() {
        // SHA-256 hex is 64 chars
        assertEquals(64, RequestHash.of("P2P", a, b, 100).length)
    }
}
