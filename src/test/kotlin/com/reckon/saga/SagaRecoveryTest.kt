package com.reckon.saga

import com.reckon.account.SystemAccounts
import com.reckon.bank.SimulatedBank
import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.TransferExecutor
import com.reckon.ledger.TxnType
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

import kotlin.test.assertEquals

// stale-seconds=0 is set in PostgresTestBase so recovery acts immediately in tests
class SagaRecoveryTest : PostgresTestBase() {
    @Autowired lateinit var recovery: SagaRecoveryService
    @Autowired lateinit var saga: AddMoneyService
    @Autowired lateinit var bank: SimulatedBank
    @Autowired lateinit var ledger: LedgerRepository
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var executor: TransferExecutor

    @Test fun `recovers a bank-timeout saga left in BANK_PENDING (bank actually charged)`() {
        val wallet = fixtures.walletWith(0)
        // BANK_TIMEOUT: bank records a CHARGE but the call throws -> saga left BANK_PENDING
        val out = saga.addMoney("rec-1", UUID.randomUUID(), wallet, "BANK_TIMEOUT", 50000)
        assertEquals("PENDING", out.status)
        assertEquals(0, fixtures.balanceOf(wallet))           // not yet credited

        val acted = recovery.recover()                        // getStatus=CHARGED -> resume step 3
        assertEquals(1, acted)
        assertEquals(50000, fixtures.balanceOf(wallet))       // now credited
        assertEquals("DONE", jdbc.queryForObject(
            "SELECT saga_state FROM transactions WHERE id=?", String::class.java, out.transactionId))
    }

    @Test fun `recovers a saga that crashed after BANK_CONFIRMED before the ledger credit`() {
        val wallet = fixtures.walletWith(0)
        // Simulate crash: PENDING ADD_MONEY at saga_state=BANK_CONFIRMED with NO entries, bank charged.
        val txnId = ledger.insertPending(TxnType.ADD_MONEY, "rec-2", "-", 50000,
            UUID.randomUUID(), SystemAccounts.BANK_SETTLEMENT, wallet)
        ledger.setSagaState(txnId, "BANK_CONFIRMED")
        bank.debit(txnId, "ref", 50000)                       // bank was charged

        val acted = recovery.recover()                        // BANK_CONFIRMED + no entries -> resume
        assertEquals(1, acted)
        assertEquals(50000, fixtures.balanceOf(wallet))
        assertEquals("DONE", jdbc.queryForObject(
            "SELECT saga_state FROM transactions WHERE id=?", String::class.java, txnId))
    }

    @Test fun `recovery is idempotent (running twice does not double credit)`() {
        val wallet = fixtures.walletWith(0)
        saga.addMoney("rec-3", UUID.randomUUID(), wallet, "BANK_TIMEOUT", 50000)
        recovery.recover()
        recovery.recover()                                    // second run: nothing left to do
        assertEquals(50000, fixtures.balanceOf(wallet))       // credited exactly once
    }

    @Test fun `declined saga is failed immediately by recovery`() {
        val wallet = fixtures.walletWith(0)
        val txnId = ledger.insertPending(TxnType.ADD_MONEY, "rec-dec", "-", 50000,
            java.util.UUID.randomUUID(), SystemAccounts.BANK_SETTLEMENT, wallet)
        ledger.setSagaState(txnId, "BANK_PENDING")
        bank.debit(txnId, "BANK_DECLINE", 50000)        // bank records DECLINED
        recovery.recover()
        val row = jdbc.queryForMap("SELECT status, saga_state FROM transactions WHERE id=?", txnId)
        assertEquals("FAILED", row["status"]); assertEquals("BANK_FAILED", row["saga_state"])
        assertEquals(0, fixtures.balanceOf(wallet))     // no credit
    }

    @Test fun `unresolved (never-charged) saga retries then fails after max attempts`() {
        val wallet = fixtures.walletWith(0)
        val txnId = ledger.insertPending(TxnType.ADD_MONEY, "rec-unres", "-", 50000,
            java.util.UUID.randomUUID(), SystemAccounts.BANK_SETTLEMENT, wallet)
        ledger.setSagaState(txnId, "BANK_PENDING")
        // bank has NO record for this txn -> getStatus = NOT_FOUND
        recovery.recover()    // attempt 1
        recovery.recover()    // attempt 2
        // still pending before reaching max (3)
        assertEquals("BANK_PENDING",
            jdbc.queryForObject("SELECT saga_state FROM transactions WHERE id=?", String::class.java, txnId))
        recovery.recover()    // attempt 3 -> give up: refund (no-op) + FAILED
        assertEquals("BANK_FAILED",
            jdbc.queryForObject("SELECT saga_state FROM transactions WHERE id=?", String::class.java, txnId))
        assertEquals(0, fixtures.balanceOf(wallet))
    }

    @Test fun `concurrent step-3 (original vs recovery) credits exactly once`() {
        val wallet = fixtures.walletWith(0)
        // a BANK_CONFIRMED saga with no entries; two threads both try to complete step 3
        val txnId = ledger.insertPending(TxnType.ADD_MONEY, "rec-race", "-", 50000,
            java.util.UUID.randomUUID(), SystemAccounts.BANK_SETTLEMENT, wallet)
        ledger.setSagaState(txnId, "BANK_CONFIRMED")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ok = java.util.concurrent.atomic.AtomicInteger(0)
        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        val tasks = (1..2).map { Runnable {
            try {
                executor.execute(txnId, TxnType.ADD_MONEY, SystemAccounts.BANK_SETTLEMENT, wallet, 50000,
                    emitEvent = true, sagaGuard = true)
                ok.incrementAndGet()
            } catch (e: Exception) { failed.incrementAndGet() }
        } }
        tasks.forEach { pool.submit(it) }
        pool.shutdown(); pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1, ok.get(), "exactly one step-3 should succeed")
        assertEquals(1, failed.get(), "the other must fail cleanly")
        assertEquals(50000, fixtures.balanceOf(wallet))   // credited EXACTLY once
    }
}
