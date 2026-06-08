package com.reckon.recon.repository

import com.reckon.recon.model.BalanceDrift
import com.reckon.recon.model.ReservedDrift
import com.reckon.recon.model.UnbalancedTxn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReconciliationRepository(private val jdbc: JdbcTemplate) {

    /** Transactions whose entries do NOT net to zero (a broken double-entry invariant). */
    fun findUnbalancedTransactions(): List<UnbalancedTxn> = jdbc.query(
        """SELECT transaction_id,
                  SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
           FROM ledger_entries
           GROUP BY transaction_id
           HAVING SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) <> 0""",
        { rs, _ -> UnbalancedTxn(rs.getObject("transaction_id", UUID::class.java), rs.getLong("net")) },
    )

    /** Accounts whose stored balance disagrees with the sum of their ledger entries. */
    fun findBalanceDrifts(): List<BalanceDrift> = jdbc.query(
        """SELECT a.id, a.balance,
                  COALESCE(SUM(CASE le.direction WHEN 'CREDIT' THEN le.amount WHEN 'DEBIT' THEN -le.amount END), 0) AS computed
           FROM accounts a
           LEFT JOIN ledger_entries le ON le.account_id = a.id
           GROUP BY a.id, a.balance
           HAVING a.balance <> COALESCE(SUM(CASE le.direction WHEN 'CREDIT' THEN le.amount WHEN 'DEBIT' THEN -le.amount END), 0)""",
        { rs, _ -> BalanceDrift(rs.getObject("id", UUID::class.java), rs.getLong("balance"), rs.getLong("computed")) },
    )

    /** Accounts whose reserved_balance disagrees with the sum of their outstanding HELD holds. */
    fun findReservedDrifts(): List<ReservedDrift> = jdbc.query(
        """SELECT a.id, a.reserved_balance,
                  COALESCE((SELECT SUM(h.amount) FROM holds h WHERE h.payer_account_id = a.id AND h.status='HELD'),0) AS computed
           FROM accounts a
           WHERE a.reserved_balance <> COALESCE((SELECT SUM(h.amount) FROM holds h WHERE h.payer_account_id=a.id AND h.status='HELD'),0)""",
        { rs, _ -> ReservedDrift(rs.getObject("id", java.util.UUID::class.java), rs.getLong("reserved_balance"), rs.getLong("computed")) },
    )

    /** Transactions stuck in PENDING with no entries, older than the given window. */
    fun findStuckPending(staleSeconds: Long): List<UUID> = jdbc.query(
        """SELECT t.id FROM transactions t
           WHERE t.status = 'PENDING'
             AND NOT EXISTS (SELECT 1 FROM ledger_entries le WHERE le.transaction_id = t.id)
             AND t.created_at < now() - make_interval(secs => ?)""",
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        staleSeconds.toDouble(),
    )
}
