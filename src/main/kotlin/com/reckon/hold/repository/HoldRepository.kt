package com.reckon.hold.repository

import com.reckon.hold.enums.HoldStatus
import com.reckon.hold.model.Hold
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class HoldRepository(private val jdbc: JdbcTemplate) {
    fun insertHeld(idempotencyKey: String, initiatorId: UUID, payer: UUID, payee: UUID, amount: Long, expiresAt: Instant): UUID = jdbc.queryForObject(
        """INSERT INTO holds(idempotency_key, initiator_id, payer_account_id, payee_account_id, amount, status, expires_at)
           VALUES (?, ?, ?, ?, ?, 'HELD', ?) RETURNING id""",
        UUID::class.java,
        idempotencyKey,
        initiatorId,
        payer,
        payee,
        amount,
        java.sql.Timestamp.from(expiresAt),
    )!!

    private val mapper = { rs: java.sql.ResultSet, _: Int ->
        Hold(
            rs.getObject("id", UUID::class.java),
            rs.getObject("payer_account_id", UUID::class.java),
            rs.getObject("payee_account_id", UUID::class.java),
            rs.getLong("amount"),
            rs.getLong("captured_amount"),
            HoldStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("expires_at").toInstant(),
        )
    }

    fun find(id: UUID): Hold? = jdbc.query(
        "SELECT * FROM holds WHERE id = ?",
        mapper,
        id,
    ).firstOrNull()

    fun findByInitiatorAndKey(initiatorId: UUID, key: String): Hold? = jdbc.query(
        "SELECT * FROM holds WHERE initiator_id = ? AND idempotency_key = ?",
        mapper,
        initiatorId,
        key,
    ).firstOrNull()

    /** Guarded transition from HELD. Returns rows updated (0 = not HELD anymore). */
    fun markCaptured(id: UUID, capturedAmount: Long, captureTxnId: UUID): Int = jdbc.update(
        """UPDATE holds SET status='CAPTURED', captured_amount=?, capture_txn_id=?, updated_at=now()
           WHERE id=? AND status='HELD'""",
        capturedAmount,
        captureTxnId,
        id,
    )

    fun markClosed(id: UUID, status: HoldStatus): Int = jdbc.update(
        "UPDATE holds SET status=?, updated_at=now() WHERE id=? AND status='HELD'",
        status.name,
        id,
    )

    fun findExpired(now: Instant, limit: Int): List<Hold> = jdbc.query(
        "SELECT * FROM holds WHERE status='HELD' AND expires_at < ? ORDER BY expires_at LIMIT ?",
        mapper,
        java.sql.Timestamp.from(now),
        limit,
    )
}
