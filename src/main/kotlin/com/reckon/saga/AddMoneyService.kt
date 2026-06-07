package com.reckon.saga

import com.reckon.account.AccountRepository
import com.reckon.account.SystemAccounts
import com.reckon.bank.BankResult
import com.reckon.bank.BankTimeoutException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import com.reckon.bank.SimulatedBank
import com.reckon.ledger.*
import com.reckon.platform.ApiException
import com.reckon.platform.RequestHash
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AddMoneyService(
    private val ledger: LedgerRepository,
    private val executor: TransferExecutor,
    private val bank: SimulatedBank,
    private val accounts: AccountRepository,
) {
    /** ADD_MONEY saga. Returns the outcome (COMPLETED, FAILED, or PENDING if the bank timed out). */
    fun addMoney(idempotencyKey: String, initiatorId: UUID, walletId: UUID, bankRef: String, amount: Long): TransferOutcome {
        if (amount <= 0) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "amount must be positive")
        val requestHash = RequestHash.of("ADD_MONEY", SystemAccounts.BANK_SETTLEMENT, walletId, amount)

        // Step 1 (local): PENDING ADD_MONEY, saga_state=BANK_PENDING [+ idempotency replay]
        val txnId = try {
            val id = ledger.insertPending(
                TxnType.ADD_MONEY,
                idempotencyKey,
                requestHash,
                amount,
                initiatorId,
                SystemAccounts.BANK_SETTLEMENT,
                walletId,
            )
            ledger.setSagaState(id, "BANK_PENDING")
            id
        } catch (e: DuplicateKeyException) {
            return replay(initiatorId, idempotencyKey, requestHash)
        }

        // Step 2 (remote): idempotent bank debit
        val result = try {
            bank.debit(txnId, bankRef, amount)
        } catch (e: CallNotPermittedException) {
            // breaker open: bank not contacted, nothing charged -> leave BANK_PENDING for recovery
            return TransferOutcome(txnId, "PENDING", replayed = false)
        } catch (e: BankTimeoutException) {
            // leave BANK_PENDING — recovery will reconcile against the bank
            return TransferOutcome(txnId, "PENDING", replayed = false)
        }
        if (result == BankResult.DECLINED) {
            ledger.markSagaFailed(txnId, "BANK_DECLINED")
            return TransferOutcome(txnId, "FAILED", replayed = false)
        }
        ledger.markBankConfirmed(txnId)

        // Step 3 (local): credit wallet from settlement, complete (saga guard)
        executor.execute(
            txnId,
            TxnType.ADD_MONEY,
            SystemAccounts.BANK_SETTLEMENT,
            walletId,
            amount,
            emitEvent = true,
            sagaGuard = true,
        )
        return TransferOutcome(txnId, "COMPLETED", replayed = false)
    }

    private fun replay(initiatorId: UUID, idempotencyKey: String, requestHash: String): TransferOutcome {
        val existing = ledger.findByInitiatorAndKey(initiatorId, idempotencyKey)
            ?: throw ApiException(HttpStatus.CONFLICT, "IN_PROGRESS", "duplicate key, original not yet visible")
        if (existing.requestHash != requestHash) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSE", "key reused with different request")
        }
        return when (existing.status) {
            "COMPLETED" -> TransferOutcome(existing.id, "COMPLETED", replayed = true)
            "FAILED" -> throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, existing.failureReason ?: "FAILED", "replayed prior failure")
            else -> TransferOutcome(existing.id, "PENDING", replayed = true) // still in flight
        }
    }
}
