package com.reckon.hold

import com.reckon.account.AccountRepository
import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.TransferExecutor
import com.reckon.ledger.TxnType
import com.reckon.platform.ApiException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class AuthorizationService(
    private val accounts: AccountRepository,
    private val holds: HoldRepository,
    private val ledger: LedgerRepository,
    private val executor: TransferExecutor,
) {
    /** Reserve funds. No money moves. Idempotent on (initiator, key). */
    @Transactional
    fun authorize(idempotencyKey: String, initiatorId: UUID, payer: UUID, payee: UUID, amount: Long, ttlSeconds: Long): UUID {
        if (amount <= 0) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "amount must be positive")
        accounts.lockByIdsInOrder(listOf(payer))   // lock payer row for the available-funds check + reserve
        val holdId = try {
            holds.insertHeld(idempotencyKey, initiatorId, payer, payee, amount,
                Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS))
        } catch (e: DuplicateKeyException) {
            return holds.findByInitiatorAndKey(initiatorId, idempotencyKey)!!.id   // idempotent replay
        }
        if (accounts.reserveIfAvailable(payer, amount) == 0) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_AVAILABLE", "insufficient available balance")
        }
        return holdId
    }

    /** Settle up to the held amount; release any uncaptured remainder. */
    @Transactional
    fun capture(holdId: UUID, captureAmount: Long?): UUID {
        val hold = holds.find(holdId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_HOLD", "hold not found")
        if (hold.status != HoldStatus.HELD) throw ApiException(HttpStatus.CONFLICT, "HOLD_NOT_HELD", "hold is ${hold.status}")
        val toCapture = captureAmount ?: hold.amount
        if (toCapture <= 0 || toCapture > hold.amount)
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CAPTURE", "capture must be in (0, amount]")
        accounts.lockByIdsInOrder(listOf(hold.payerAccountId, hold.payeeAccountId))
        // release the FULL reservation; the actual transfer then debits the captured amount
        if (accounts.releaseReserve(hold.payerAccountId, hold.amount) == 0)
            throw IllegalStateException("reservation missing for hold $holdId")
        val captureTxn = ledger.insertPending(TxnType.PAY_MERCHANT, "capture:$holdId", "-", toCapture,
            hold.payerAccountId, hold.payerAccountId, hold.payeeAccountId)
        executor.execute(captureTxn, TxnType.PAY_MERCHANT, hold.payerAccountId, hold.payeeAccountId, toCapture)
        if (holds.markCaptured(holdId, toCapture, captureTxn) == 0)
            throw IllegalStateException("hold $holdId no longer HELD")
        return captureTxn
    }

    /** Cancel a hold and release the reservation. */
    @Transactional
    fun void(holdId: UUID) {
        val hold = holds.find(holdId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_HOLD", "hold not found")
        if (hold.status != HoldStatus.HELD) throw ApiException(HttpStatus.CONFLICT, "HOLD_NOT_HELD", "hold is ${hold.status}")
        accounts.lockByIdsInOrder(listOf(hold.payerAccountId))
        accounts.releaseReserve(hold.payerAccountId, hold.amount)
        if (holds.markClosed(holdId, HoldStatus.VOIDED) == 0) throw IllegalStateException("hold $holdId no longer HELD")
    }
}
