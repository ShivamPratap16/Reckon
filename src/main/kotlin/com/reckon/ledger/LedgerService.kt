package com.reckon.ledger

import com.reckon.platform.ApiException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class LedgerService(
    private val ledger: LedgerRepository,
    private val executor: TransferExecutor,
    private val statusWriter: TxnStatusWriter,
) {
    /**
     * The ONLY money-writer for transfers. NOT itself @Transactional — it inserts the PENDING
     * header (auto-commit, persists for recovery legibility) then delegates to executor.execute,
     * which IS @Transactional via a cross-bean proxy call (no self-invocation trap here).
     * Any failure marks the transaction FAILED in its own REQUIRES_NEW transaction via statusWriter.
     */
    fun recordTransfer(
        type: TxnType, idempotencyKey: String, requestHash: String,
        initiatorId: UUID, from: UUID, to: UUID, amount: Long,
    ): TransferOutcome {
        if (amount <= 0) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "amount must be positive")
        if (from == to) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "cannot transfer to self")

        val txnId = try {
            ledger.insertPending(type, idempotencyKey, requestHash, amount, initiatorId, from, to)
        } catch (e: DuplicateKeyException) {
            return replay(initiatorId, idempotencyKey, requestHash)
        }

        try {
            executor.execute(txnId, from, to, amount)
        } catch (e: Exception) {
            val reason = (e as? ApiException)?.code ?: "EXECUTION_ERROR"
            statusWriter.failInOwnTxn(txnId, reason)
            val code = (e as? ApiException)?.status?.value() ?: 500
            ledger.storeResponse(txnId, code, """{"transactionId":"$txnId","status":"FAILED","code":"$reason"}""")
            throw e
        }
        ledger.storeResponse(txnId, 200, """{"transactionId":"$txnId","status":"COMPLETED"}""")
        return TransferOutcome(txnId, "COMPLETED", replayed = false)
    }

    /** Apply the 4-way replay decision for a duplicate (initiator, key). */
    private fun replay(initiatorId: UUID, idempotencyKey: String, requestHash: String): TransferOutcome {
        val existing = ledger.findByInitiatorAndKey(initiatorId, idempotencyKey)
            ?: throw ApiException(HttpStatus.CONFLICT, "IN_PROGRESS", "duplicate key, original not yet visible")
        if (existing.requestHash != requestHash)
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSE",
                "idempotency key reused with different request")
        return when (existing.status) {
            "COMPLETED" -> TransferOutcome(existing.id, "COMPLETED", replayed = true)
            "FAILED"    -> throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                              existing.responseBody?.let { extractCode(it) } ?: "FAILED",
                              "replayed prior failure")
            else        -> throw ApiException(HttpStatus.CONFLICT, "IN_PROGRESS",
                              "original request still in progress") // PENDING
        }
    }

    private fun extractCode(body: String): String =
        Regex("\"code\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "FAILED"
}
