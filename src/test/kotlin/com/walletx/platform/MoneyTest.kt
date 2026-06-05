package com.walletx.platform

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class MoneyTest {
    @Test fun `rupees converts to paisa`() {
        assertEquals(15000L, Paisa.ofRupees(150).value)
    }
    @Test fun `paisa rejects negative`() {
        assertThrows<IllegalArgumentException> { Paisa(-1) }
    }
    @Test fun `formats as rupee string`() {
        assertEquals("₹150.00", Paisa(15000).toDisplay())
    }
}
