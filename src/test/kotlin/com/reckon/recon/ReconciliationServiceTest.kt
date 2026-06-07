package com.reckon.recon

import com.reckon.account.SystemAccounts
import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.TxnType
import com.reckon.saga.AddMoneyService
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconciliationServiceTest : PostgresTestBase() {
    @Autowired lateinit var recon: ReconciliationService

    @Autowired lateinit var saga: AddMoneyService

    @Autowired lateinit var fixtures: Fixtures

    @Autowired lateinit var ledger: LedgerRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `a fully ledgered system passes all audits`() {
        // fund a wallet via the saga (writes balanced entries + balance atomically)
        val wallet = fixtures.walletWith(0)
        saga.addMoney("recon-clean-${UUID.randomUUID()}", UUID.randomUUID(), wallet, "ref", 50000)
        val report = recon.run()
        // The shared DB may have other test accounts seeded via raw SQL (fixtures.walletWith(nonzero))
        // that have no matching ledger entries — those are intentionally scoped to other test flows.
        // We verify that OUR ledger-funded wallet is NOT in the drift or unbalanced lists.
        assertTrue(report.unbalancedTransactions.isEmpty(), "unbalanced: ${report.unbalancedTransactions}")
        assertTrue(
            report.balanceDrifts.none { it.accountId == wallet },
            "expected no drift for the saga-funded wallet $wallet, but found drift: ${report.balanceDrifts.find { it.accountId == wallet }}",
        )
    }

    @Test fun `a corrupted balance is detected as drift`() {
        val wallet = fixtures.walletWith(0)
        saga.addMoney("recon-drift-${UUID.randomUUID()}", UUID.randomUUID(), wallet, "ref", 50000)
        // corrupt the stored balance WITHOUT a matching ledger entry -> drift
        jdbc.update("UPDATE accounts SET balance = balance + 999 WHERE id = ?", wallet)
        try {
            val report = recon.run()
            val drift = report.balanceDrifts.find { it.accountId == wallet }
            assertTrue(drift != null, "expected drift for $wallet, got ${report.balanceDrifts}")
            assertEquals(999, drift.storedBalance - drift.computedBalance)
            assertFalse(report.clean)
        } finally {
            // Restore the balance so the shared DB isn't globally dirty for other tests
            jdbc.update("UPDATE accounts SET balance = balance - 999 WHERE id = ?", wallet)
        }
    }

    @Test fun `an unbalanced transaction is detected`() {
        // craft a transaction with a single DEBIT entry (does not net to zero)
        val wallet = fixtures.walletWith(0)
        val txnId = ledger.insertPending(
            TxnType.P2P,
            "recon-unbal-${UUID.randomUUID()}",
            "-",
            100,
            UUID.randomUUID(),
            wallet,
            SystemAccounts.REWARDS_POOL,
        )
        jdbc.update(
            "INSERT INTO ledger_entries(transaction_id, account_id, direction, amount) VALUES (?,?,?,?)",
            txnId,
            wallet,
            "DEBIT",
            100,
        ) // lone debit, no matching credit
        try {
            val report = recon.run()
            assertTrue(
                report.unbalancedTransactions.any { it.transactionId == txnId },
                "expected unbalanced txn $txnId, got ${report.unbalancedTransactions}",
            )
        } finally {
            // Clean up: remove the crafted entry and transaction so the DB stays globally clean
            jdbc.update("DELETE FROM ledger_entries WHERE transaction_id = ?", txnId)
            jdbc.update("DELETE FROM transactions WHERE id = ?", txnId)
        }
    }

    @Test fun `a stuck pending transaction is detected`() {
        val wallet = fixtures.walletWith(0)
        val txnId = ledger.insertPending(
            TxnType.P2P,
            "recon-stuck-${UUID.randomUUID()}",
            "-",
            100,
            UUID.randomUUID(),
            wallet,
            SystemAccounts.REWARDS_POOL,
        )
        // make it old so it crosses the stale threshold regardless of config
        jdbc.update("UPDATE transactions SET created_at = now() - interval '2 days' WHERE id = ?", txnId)
        try {
            val report = recon.run()
            assertTrue(report.stuckPending.contains(txnId), "expected $txnId in stuck list ${report.stuckPending}")
        } finally {
            jdbc.update("DELETE FROM ledger_entries WHERE transaction_id = ?", txnId)
            jdbc.update("DELETE FROM transactions WHERE id = ?", txnId)
        }
    }
}
