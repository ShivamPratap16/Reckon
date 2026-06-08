package com.reckon.hold.service

import com.reckon.account.repository.AccountRepository
import com.reckon.hold.enums.HoldStatus
import com.reckon.hold.repository.HoldRepository
import com.reckon.ledger.enums.TxnType
import com.reckon.ledger.repository.LedgerRepository
import com.reckon.ledger.service.TransferExecutor
import com.reckon.platform.exception.ApiException
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
        // Idempotency: check for existing hold before locking (avoids DuplicateKeyException inside a txn which aborts it)
        holds.findByInitiatorAndKey(initiatorId, idempotencyKey)?.let { return it.id }
        accounts.lockByIdsInOrder(listOf(payer)) // lock payer row for the available-funds check + reserve
        // Re-check after lock in case a concurrent authorize with same key just committed
        holds.findByInitiatorAndKey(initiatorId, idempotencyKey)?.let { return it.id }
        val holdId = holds.insertHeld(
            idempotencyKey,
            initiatorId,
            payer,
            payee,
            amount,
            Instant.now().plus(ttlSeconds, ChronoUnit.SECONDS),
        )
        if (accounts.reserveIfAvailable(payer, amount) == 0) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_AVAILABLE", "insufficient available balance")
        }
        return holdId
    }

    /** Settle up to the held amount; release any uncaptured remainder. */
    @Transactional
    fun capture(holdId: UUID, callerWalletId: UUID, captureAmount: Long?): UUID {
        val hold = holds.find(holdId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_HOLD", "hold not found")
        if (hold.status != HoldStatus.HELD) throw ApiException(HttpStatus.CONFLICT, "HOLD_NOT_HELD", "hold is ${hold.status}")
        if (callerWalletId != hold.payerAccountId && callerWalletId != hold.payeeAccountId) {
            throw ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "NOT_HOLD_PARTY", "caller is not a party to this hold")
        }
        val toCapture = captureAmount ?: hold.amount
        if (toCapture <= 0 || toCapture > hold.amount) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CAPTURE", "capture must be in (0, amount]")
        }
        accounts.lockByIdsInOrder(listOf(hold.payerAccountId, hold.payeeAccountId))
        // Release the FULL reservation BEFORE the transfer; the transfer then debits only the captured amount.
        // This is safe ONLY because the whole method is one @Transactional: if the later markCaptured status
        // guard loses a race (returns 0) and throws, this release is rolled back along with the transaction.
        if (accounts.releaseReserve(hold.payerAccountId, hold.amount) == 0) {
            throw IllegalStateException("reservation missing for hold $holdId")
        }
        val captureTxn = ledger.insertPending(
            TxnType.PAY_MERCHANT,
            "capture:$holdId",
            "-",
            toCapture,
            hold.payerAccountId,
            hold.payerAccountId,
            hold.payeeAccountId,
        )
        executor.execute(captureTxn, TxnType.PAY_MERCHANT, hold.payerAccountId, hold.payeeAccountId, toCapture)
        if (holds.markCaptured(holdId, toCapture, captureTxn) == 0) {
            throw IllegalStateException("hold $holdId no longer HELD")
        }
        return captureTxn
    }

    /** Cancel a hold and release the reservation. */
    @Transactional
    fun void(holdId: UUID, callerWalletId: UUID) {
        val hold = holds.find(holdId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_HOLD", "hold not found")
        if (hold.status != HoldStatus.HELD) throw ApiException(HttpStatus.CONFLICT, "HOLD_NOT_HELD", "hold is ${hold.status}")
        if (callerWalletId != hold.payerAccountId && callerWalletId != hold.payeeAccountId) {
            throw ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "NOT_HOLD_PARTY", "caller is not a party to this hold")
        }
        accounts.lockByIdsInOrder(listOf(hold.payerAccountId))
        if (holds.markClosed(holdId, HoldStatus.VOIDED) == 0) {
            throw IllegalStateException("hold $holdId no longer HELD")
        }
        if (accounts.releaseReserve(hold.payerAccountId, hold.amount) == 0) {
            throw IllegalStateException("reservation missing for hold $holdId")
        }
    }
}
