package com.reckon.saga

import com.reckon.bank.BankStatus
import com.reckon.bank.SimulatedBank
import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.TransferExecutor
import com.reckon.ledger.TxnType
import com.reckon.account.SystemAccounts
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
) {
    /** Reconcile sagas left mid-flight. Returns number of transactions acted on. */
    fun recover(): Int {
        var acted = 0
        // 1) BANK_PENDING older than stale window -> reconcile against the bank
        for (txnId in ledger.findSagaTxnsOlderThan("BANK_PENDING", staleSeconds)) {
            when (bank.getStatus(txnId)) {
                BankStatus.CHARGED -> { ledger.markBankConfirmed(txnId); completeStep3(txnId); acted++ }
                BankStatus.DECLINED, BankStatus.NOT_FOUND -> { ledger.markSagaFailed(txnId, "BANK_UNRESOLVED"); acted++ }
            }
        }
        // 2) BANK_CONFIRMED but no ledger entries (crashed before Step 3) -> resume
        for (txnId in ledger.findSagaTxnsOlderThan("BANK_CONFIRMED", 0)) {
            if (ledger.hasNoEntries(txnId)) { completeStep3(txnId); acted++ }
        }
        return acted
    }

    private fun completeStep3(txnId: java.util.UUID) {
        val wallet = ledger.toAccountOf(txnId)
        val amount = ledger.amountOf(txnId)
        executor.execute(txnId, TxnType.ADD_MONEY, SystemAccounts.BANK_SETTLEMENT, wallet, amount,
            emitEvent = true, sagaGuard = true)
    }

    @Scheduled(fixedDelayString = "\${reckon.saga.recovery.poll-ms:5000}")
    fun scheduled() { if (enabled) recover() }
}
