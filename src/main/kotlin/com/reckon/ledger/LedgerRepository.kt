package com.reckon.ledger

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class LedgerRepository(private val jdbc: JdbcTemplate) {

    /** Insert the PENDING transaction header. Returns the new id. */
    fun insertPending(type: TxnType, idempotencyKey: String, requestHash: String,
                      amount: Long, initiatorId: UUID, from: UUID, to: UUID): UUID =
        jdbc.queryForObject(
            """INSERT INTO transactions(type, status, idempotency_key, request_hash, amount,
                   initiator_id, from_account_id, to_account_id)
               VALUES (?, 'PENDING', ?, ?, ?, ?, ?, ?) RETURNING id""",
            UUID::class.java,
            type.name, idempotencyKey, requestHash, amount, initiatorId, from, to,
        )!!

    fun insertEntry(e: LedgerEntry) = jdbc.update(
        "INSERT INTO ledger_entries(transaction_id, account_id, direction, amount) VALUES (?, ?, ?, ?)",
        e.transactionId, e.accountId, e.direction.name, e.amount,
    )

    /** Conditional status flip (optimistic state transition). Returns rows updated. */
    fun markCompletedIfPending(txnId: UUID): Int = jdbc.update(
        "UPDATE transactions SET status='COMPLETED', updated_at=now() WHERE id=? AND status='PENDING'",
        txnId,
    )

    fun markFailed(txnId: UUID, reason: String) = jdbc.update(
        "UPDATE transactions SET status='FAILED', failure_reason=?, updated_at=now() WHERE id=? AND status='PENDING'",
        reason, txnId,
    )

    fun sumEntries(accountId: UUID): Long = jdbc.queryForObject(
        """SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END),0)
           FROM ledger_entries WHERE account_id = ?""",
        Long::class.java, accountId,
    )!!

    fun status(txnId: UUID): TxnStatus = TxnStatus.valueOf(
        jdbc.queryForObject("SELECT status FROM transactions WHERE id=?", String::class.java, txnId)!!
    )

    fun findByInitiatorAndKey(initiatorId: UUID, idempotencyKey: String): ExistingTxn? =
        jdbc.query(
            """SELECT id, status, request_hash, response_code, response_body, failure_reason
               FROM transactions WHERE initiator_id = ? AND idempotency_key = ?""",
            { rs, _ -> ExistingTxn(
                rs.getObject("id", UUID::class.java),
                rs.getString("status"),
                rs.getString("request_hash"),
                rs.getObject("response_code") as Int?,
                rs.getString("response_body"),
                rs.getString("failure_reason"),
            ) },
            initiatorId, idempotencyKey,
        ).firstOrNull()

    fun storeResponse(txnId: UUID, code: Int, body: String) {
        jdbc.update(
            "UPDATE transactions SET response_code = ?, response_body = ?::jsonb, updated_at = now() WHERE id = ?",
            code, body, txnId,
        )
    }
}

data class ExistingTxn(
    val id: UUID,
    val status: String,
    val requestHash: String,
    val responseCode: Int?,
    val responseBody: String?,
    val failureReason: String?,
)
