package com.reckon.ledger

import com.reckon.account.SystemAccounts
import com.reckon.idempotency.CachedResult
import com.reckon.idempotency.IdempotencyCache
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
    private val cache: IdempotencyCache,
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

        // FAST PATH: terminal result already cached?
        cache.get(initiatorId, idempotencyKey)?.let { cached ->
            if (cached.requestHash != requestHash)
                throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSE", "key reused with different request")
            return when (cached.status) {
                "COMPLETED" -> TransferOutcome(cached.transactionId, "COMPLETED", replayed = true)
                "FAILED"    -> throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, cached.failureCode ?: "FAILED", "replayed prior failure")
                else        -> TransferOutcome(cached.transactionId, cached.status, replayed = true)
            }
        }

        val txnId = try {
            ledger.insertPending(type, idempotencyKey, requestHash, amount, initiatorId, from, to)
        } catch (e: DuplicateKeyException) {
            return replay(initiatorId, idempotencyKey, requestHash)   // DB-authoritative; replay() also warms the cache
        }

        try {
            executor.execute(txnId, type, from, to, amount)
        } catch (e: Exception) {
            val reason = (e as? ApiException)?.code ?: "EXECUTION_ERROR"
            statusWriter.failInOwnTxn(txnId, reason)
            val code = (e as? ApiException)?.status?.value() ?: 500
            ledger.storeResponse(txnId, code, """{"transactionId":"$txnId","status":"FAILED","code":"$reason"}""")
            cache.put(initiatorId, idempotencyKey, CachedResult(txnId, "FAILED", requestHash, reason))
            throw e
        }
        ledger.storeResponse(txnId, 200, """{"transactionId":"$txnId","status":"COMPLETED"}""")
        cache.put(initiatorId, idempotencyKey, CachedResult(txnId, "COMPLETED", requestHash, null))
        return TransferOutcome(txnId, "COMPLETED", replayed = false)
    }

    /** Record a CASHBACK transaction (REWARDS_POOL -> wallet). Must be called within the
     *  caller's transaction (e.g. the consumer's) so it commits atomically with the dedup mark.
     *  Emits no outbox event (prevents a cashback feedback loop). */
    fun recordCashback(sourceEventId: UUID, toAccount: UUID, amount: Long) {
        val txnId = try {
            ledger.insertPending(TxnType.CASHBACK, "cashback:$sourceEventId", "-", amount,
                SystemAccounts.REWARDS_POOL, SystemAccounts.REWARDS_POOL, toAccount)
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            return   // cashback for this source event already recorded (defense-in-depth no-op)
        }
        executor.execute(txnId, TxnType.CASHBACK, SystemAccounts.REWARDS_POOL, toAccount, amount, emitEvent = false)
    }

    /** Apply the 4-way replay decision for a duplicate (initiator, key). Also warms the cache for terminal results. */
    private fun replay(initiatorId: UUID, idempotencyKey: String, requestHash: String): TransferOutcome {
        val existing = ledger.findByInitiatorAndKey(initiatorId, idempotencyKey)
            ?: throw ApiException(HttpStatus.CONFLICT, "IN_PROGRESS", "duplicate key, original not yet visible")
        if (existing.requestHash != requestHash)
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSE",
                "idempotency key reused with different request")
        return when (existing.status) {
            "COMPLETED" -> {
                cache.put(initiatorId, idempotencyKey, CachedResult(existing.id, "COMPLETED", requestHash, null))
                TransferOutcome(existing.id, "COMPLETED", replayed = true)
            }
            "FAILED"    -> {
                cache.put(initiatorId, idempotencyKey, CachedResult(existing.id, "FAILED", requestHash, existing.failureReason))
                throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    existing.failureReason ?: "FAILED",
                    "replayed prior failure")
            }
            else        -> throw ApiException(HttpStatus.CONFLICT, "IN_PROGRESS",
                              "original request still in progress")   // PENDING: NOT cached
        }
    }

}
