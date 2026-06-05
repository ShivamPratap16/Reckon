package com.reckon.ledger

import com.reckon.platform.ApiException
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

class LedgerServiceTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var repo: LedgerRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var executor: TransferExecutor

    @Test fun `successful transfer moves money and balances net to zero`() {
        val a = fixtures.walletWith(50000)   // ₹500
        val b = fixtures.walletWith(0)
        ledger.recordTransfer(TxnType.P2P, "k1", "h1", UUID.randomUUID(), a, b, 20000)

        assertEquals(30000, fixtures.balanceOf(a))
        assertEquals(20000, fixtures.balanceOf(b))
    }

    @Test fun `transfer writes exactly two entries summing to zero`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val txn = ledger.recordTransfer(TxnType.P2P, "k2", "h2", UUID.randomUUID(), a, b, 10000)

        val net = jdbc.queryForObject(
            """SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
               FROM ledger_entries WHERE transaction_id=?""", Long::class.java, txn)
        assertEquals(0L, net)
    }

    @Test fun `denormalized balance equals sum of entries`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        ledger.recordTransfer(TxnType.P2P, "k3", "h3", UUID.randomUUID(), a, b, 12345)
        // b starts at 0 so its entire history is in ledger entries: balance == sumEntries
        assertEquals(repo.sumEntries(b), fixtures.balanceOf(b))
        // a has a seeded balance not in ledger; verify the ledger-recorded delta matches
        assertEquals(50000L + repo.sumEntries(a), fixtures.balanceOf(a))
    }

    @Test fun `insufficient funds throws and moves no money and records FAILED`() {
        val a = fixtures.walletWith(100); val b = fixtures.walletWith(0)
        val ex = assertThrows<ApiException> {
            ledger.recordTransfer(TxnType.P2P, "k4", "h4", UUID.randomUUID(), a, b, 99999)
        }
        assertEquals("INSUFFICIENT_FUNDS", ex.code)
        assertEquals(100, fixtures.balanceOf(a))   // unchanged
        assertEquals(0, fixtures.balanceOf(b))
    }

    @Test fun `failed transfer writes no ledger entries`() {
        val a = fixtures.walletWith(100); val b = fixtures.walletWith(0)
        val idem = "no-entries-${java.util.UUID.randomUUID()}"
        org.junit.jupiter.api.assertThrows<com.reckon.platform.ApiException> {
            ledger.recordTransfer(TxnType.P2P, idem, "h", java.util.UUID.randomUUID(), a, b, 99999)
        }
        val entryCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries le JOIN transactions t ON t.id = le.transaction_id WHERE t.idempotency_key = ?",
            Long::class.java, idem)
        kotlin.test.assertEquals(0L, entryCount)
        val status = jdbc.queryForObject("SELECT status FROM transactions WHERE idempotency_key = ?", String::class.java, idem)
        kotlin.test.assertEquals("FAILED", status)
    }

    @Test fun `execute rolls back entries and balances when status flip guard trips`() {
        val a = fixtures.walletWith(50000)
        val b = fixtures.walletWith(0)
        // create a PENDING txn header, then move it OUT of PENDING so markCompletedIfPending returns 0
        val txnId = repo.insertPending(TxnType.P2P, "guard-${java.util.UUID.randomUUID()}", "h",
            20000, java.util.UUID.randomUUID(), a, b)
        jdbc.update("UPDATE transactions SET status='COMPLETED' WHERE id=?", txnId)

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            executor.execute(txnId, a, b, 20000)
        }
        // everything inside execute must have rolled back:
        kotlin.test.assertEquals(50000L, fixtures.balanceOf(a))   // unchanged
        kotlin.test.assertEquals(0L, fixtures.balanceOf(b))       // unchanged
        val entries = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id=?", Long::class.java, txnId)
        kotlin.test.assertEquals(0L, entries)                     // no orphaned entries
    }

    @Test fun `total system balance is conserved by a transfer`() {
        val before = jdbc.queryForObject("SELECT SUM(balance) FROM accounts", Long::class.java)!!
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val afterSeed = jdbc.queryForObject("SELECT SUM(balance) FROM accounts", Long::class.java)!!
        ledger.recordTransfer(TxnType.P2P, "k5", "h5", UUID.randomUUID(), a, b, 20000)
        val afterTransfer = jdbc.queryForObject("SELECT SUM(balance) FROM accounts", Long::class.java)!!
        // a transfer between existing accounts must not change the global sum
        assertEquals(afterSeed, afterTransfer)
        assert(afterSeed >= before)
    }
}
