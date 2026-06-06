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

    fun setSagaState(txnId: java.util.UUID, state: String) =
        jdbc.update("UPDATE transactions SET saga_state = ?, updated_at = now() WHERE id = ?", state, txnId)

    /** Guarded: BANK_PENDING -> BANK_CONFIRMED. Returns rows updated. */
    fun markBankConfirmed(txnId: java.util.UUID): Int = jdbc.update(
        "UPDATE transactions SET saga_state='BANK_CONFIRMED', updated_at=now() WHERE id=? AND saga_state='BANK_PENDING'",
        txnId)

    fun markSagaFailed(txnId: java.util.UUID, reason: String): Int = jdbc.update(
        """UPDATE transactions SET status='FAILED', saga_state='BANK_FAILED', failure_reason=?, updated_at=now()
           WHERE id=? AND status='PENDING'""", reason, txnId)

    /** Saga completion guard: complete only if still BANK_CONFIRMED. Returns rows updated. */
    fun markCompletedIfBankConfirmed(txnId: java.util.UUID): Int = jdbc.update(
        """UPDATE transactions SET status='COMPLETED', saga_state='DONE', updated_at=now()
           WHERE id=? AND saga_state='BANK_CONFIRMED'""", txnId)

    /** Recovery scan: ADD_MONEY txns in a given saga_state older than N seconds. */
    fun findSagaTxnsOlderThan(sagaState: String, seconds: Long): List<java.util.UUID> = jdbc.query(
        """SELECT id FROM transactions
           WHERE type='ADD_MONEY' AND saga_state=? AND updated_at < now() - make_interval(secs => ?)""",
        { rs, _ -> rs.getObject("id", java.util.UUID::class.java) }, sagaState, seconds.toDouble())

    fun hasNoEntries(txnId: java.util.UUID): Boolean = jdbc.queryForObject(
        "SELECT NOT EXISTS(SELECT 1 FROM ledger_entries WHERE transaction_id=?)", Boolean::class.java, txnId)!!

    /** For the saga to know the wallet to credit during recovery. */
    fun toAccountOf(txnId: java.util.UUID): java.util.UUID = jdbc.queryForObject(
        "SELECT to_account_id FROM transactions WHERE id=?", java.util.UUID::class.java, txnId)!!

    fun amountOf(txnId: java.util.UUID): Long = jdbc.queryForObject(
        "SELECT amount FROM transactions WHERE id=?", Long::class.java, txnId)!!

    fun incrementSagaAttempts(txnId: java.util.UUID): Int = jdbc.queryForObject(
        "UPDATE transactions SET saga_attempts = saga_attempts + 1, updated_at = now() WHERE id = ? RETURNING saga_attempts",
        Int::class.java, txnId)!!
}

data class ExistingTxn(
    val id: UUID,
    val status: String,
    val requestHash: String,
    val responseCode: Int?,
    val responseBody: String?,
    val failureReason: String?,
)
