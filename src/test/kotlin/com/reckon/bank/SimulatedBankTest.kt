package com.reckon.bank

import com.reckon.bank.enums.BankResult
import com.reckon.bank.enums.BankStatus
import com.reckon.bank.exception.BankTimeoutException
import com.reckon.bank.service.SimulatedBank
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals

class SimulatedBankTest {
    private val bank = SimulatedBank()

    @Test fun `debit is idempotent on transactionId`() {
        val id = UUID.randomUUID()
        assertEquals(BankResult.CHARGED, bank.debit(id, "ref", 1000))
        assertEquals(BankResult.CHARGED, bank.debit(id, "ref", 1000)) // charged once, same result
        assertEquals(BankStatus.CHARGED, bank.getStatus(id))
    }

    @Test fun `decline ref is declined and not charged`() {
        val id = UUID.randomUUID()
        assertEquals(BankResult.DECLINED, bank.debit(id, "BANK_DECLINE", 1000))
        assertEquals(BankStatus.DECLINED, bank.getStatus(id))
    }

    @Test fun `timeout records a charge but throws (charged-but-no-response)`() {
        val id = UUID.randomUUID()
        assertThrows<BankTimeoutException> { bank.debit(id, "BANK_TIMEOUT", 1000) }
        assertEquals(BankStatus.CHARGED, bank.getStatus(id)) // recovery will find it charged
    }
}
