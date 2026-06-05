package com.walletx.ledger

import com.walletx.platform.ApiException
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
    ): UUID {
        // Validate FIRST — before creating any row, so obviously-invalid input never creates a PENDING row
        if (amount <= 0) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "amount must be positive")
        if (from == to) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "cannot transfer to self")

        // insertPending runs in auto-commit — PENDING header persists regardless of what follows
        val txnId = ledger.insertPending(type, idempotencyKey, requestHash, amount, initiatorId, from, to)

        try {
            executor.execute(txnId, from, to, amount)
            return txnId
        } catch (e: Exception) {
            val reason = (e as? ApiException)?.code ?: "EXECUTION_ERROR"
            statusWriter.failInOwnTxn(txnId, reason)
            throw e
        }
    }
}
