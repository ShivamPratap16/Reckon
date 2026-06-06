package com.reckon.saga

import com.reckon.account.SystemAccounts
import com.reckon.ledger.LedgerRepository
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

class AddMoneySagaTest : PostgresTestBase() {
    @Autowired lateinit var saga: AddMoneyService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun settlementBalance() = jdbc.queryForObject(
        "SELECT balance FROM accounts WHERE id=?", Long::class.java, SystemAccounts.BANK_SETTLEMENT)!!

    @Test fun `happy path charges bank and credits wallet, money conserved`() {
        val wallet = fixtures.walletWith(0)
        val settleBefore = settlementBalance()
        val out = saga.addMoney("am-1", UUID.randomUUID(), wallet, "ref", 50000)
        assertEquals("COMPLETED", out.status)
        assertEquals(50000, fixtures.balanceOf(wallet))                 // wallet credited
        assertEquals(settleBefore - 50000, settlementBalance())        // settlement debited (goes negative)
        val sagaState = jdbc.queryForObject("SELECT saga_state FROM transactions WHERE id=?", String::class.java, out.transactionId)
        assertEquals("DONE", sagaState)
    }

    @Test fun `declined bank fails the saga without crediting the wallet`() {
        val wallet = fixtures.walletWith(0)
        val out = saga.addMoney("am-2", UUID.randomUUID(), wallet, "BANK_DECLINE", 50000)
        assertEquals("FAILED", out.status)
        assertEquals(0, fixtures.balanceOf(wallet))
        val row = jdbc.queryForMap("SELECT status, saga_state FROM transactions WHERE id=?", out.transactionId)
        assertEquals("FAILED", row["status"]); assertEquals("BANK_FAILED", row["saga_state"])
    }

    @Test fun `idempotent add-money does not double credit`() {
        val wallet = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val first = saga.addMoney("am-3", initiator, wallet, "ref", 50000)
        val second = saga.addMoney("am-3", initiator, wallet, "ref", 50000)   // retry
        assertEquals(first.transactionId, second.transactionId)
        assertEquals(50000, fixtures.balanceOf(wallet))    // credited once
    }

    @Test fun `add-money grants no cashback (loading is not a spend)`() {
        // ADD_MONEY emits payment.completed, but RewardsService only awards P2P/PAY_MERCHANT.
        // Verify by calling RewardsService directly with an ADD_MONEY event -> no cashback.
        // (covered more directly in consumer tests; here assert wallet equals exactly the added amount)
        val wallet = fixtures.walletWith(0)
        saga.addMoney("am-4", UUID.randomUUID(), wallet, "ref", 50000)
        assertEquals(50000, fixtures.balanceOf(wallet))    // exactly the deposit, no extra cashback
    }
}
