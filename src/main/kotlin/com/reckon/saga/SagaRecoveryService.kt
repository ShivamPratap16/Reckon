package com.reckon.saga

import com.reckon.account.SystemAccounts
import com.reckon.bank.BankStatus
import com.reckon.bank.SimulatedBank
import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.TransferExecutor
import com.reckon.ledger.TxnType
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class SagaRecoveryService(
    private val ledger: LedgerRepository,
    private val executor: TransferExecutor,
    private val bank: SimulatedBank,
    @Value("\${reckon.saga.recovery.stale-seconds}") private val staleSeconds: Long,
    @Value("\${reckon.saga.recovery.enabled:true}") private val enabled: Boolean,
    @Value("\${reckon.saga.recovery.max-attempts:3}") private val maxAttempts: Int,
) {
    /** Reconcile sagas left mid-flight. Returns number of transactions acted on. */
    fun recover(): Int {
        var acted = 0

        // 1) BANK_PENDING older than stale window -> reconcile against the bank
        for (txnId in ledger.findSagaTxnsOlderThan("BANK_PENDING", staleSeconds)) {
            try {
                when (bank.getStatus(txnId)) {
                    BankStatus.CHARGED -> {
                        ledger.markBankConfirmed(txnId)
                        completeStep3(txnId)
                        acted++
                    }
                    BankStatus.DECLINED -> {
                        // Definite no-charge: fail immediately
                        ledger.markSagaFailed(txnId, "BANK_DECLINED")
                        acted++
                    }
                    BankStatus.NOT_FOUND -> {
                        // Eventual consistency: bank may not yet have the record.
                        // Retry up to maxAttempts; only give up after that.
                        val attempts = ledger.incrementSagaAttempts(txnId)
                        if (attempts >= maxAttempts) {
                            // Compensating action (no-op if bank never charged)
                            bank.refund(txnId)
                            ledger.markSagaFailed(txnId, "BANK_UNRESOLVED")
                            acted++
                        }
                        // else: leave as BANK_PENDING — will be retried on next sweep
                    }
                }
            } catch (e: Exception) {
                // In production this would be logged/alerted. Swallow so one bad txn
                // doesn't abort the entire sweep.
            }
        }

        // 2) BANK_CONFIRMED but no ledger entries (crashed before Step 3) -> resume
        for (txnId in ledger.findSagaTxnsOlderThan("BANK_CONFIRMED", 0)) {
            try {
                if (ledger.hasNoEntries(txnId)) {
                    completeStep3(txnId)
                    acted++
                }
            } catch (e: Exception) {
                // Swallow per-txn failures; rest of sweep continues.
            }
        }

        return acted
    }

    private fun completeStep3(txnId: java.util.UUID) {
        val wallet = ledger.toAccountOf(txnId)
        val amount = ledger.amountOf(txnId)
        executor.execute(
            txnId,
            TxnType.ADD_MONEY,
            SystemAccounts.BANK_SETTLEMENT,
            wallet,
            amount,
            emitEvent = true,
            sagaGuard = true,
        )
    }

    @Scheduled(fixedDelayString = "\${reckon.saga.recovery.poll-ms:5000}")
    fun scheduled() {
        if (enabled) recover()
    }
}
