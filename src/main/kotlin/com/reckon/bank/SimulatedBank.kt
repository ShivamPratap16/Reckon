package com.reckon.bank

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process simulation of an external bank. Idempotent on transactionId: a repeated debit
 * with the same transactionId charges once and returns the same result. Test-deterministic
 * behavior via bankRef sentinels:
 *   - "BANK_DECLINE" -> DECLINED (no charge)
 *   - "BANK_TIMEOUT" -> records a CHARGE but throws BankTimeoutException (models "charged but
 *                       response lost" — the dangerous case the saga recovery must handle)
 *   - anything else  -> CHARGED
 */
@Component
class SimulatedBank {
    private enum class State { CHARGED, DECLINED }
    private val ledger = ConcurrentHashMap<UUID, State>()

    fun debit(transactionId: UUID, bankRef: String, amountPaisa: Long): BankResult {
        ledger[transactionId]?.let {
            // idempotent: already seen this txn
            return if (it == State.CHARGED) BankResult.CHARGED else BankResult.DECLINED
        }
        return when (bankRef) {
            "BANK_DECLINE" -> {
                ledger[transactionId] = State.DECLINED
                BankResult.DECLINED
            }
            "BANK_TIMEOUT" -> {
                ledger[transactionId] = State.CHARGED // charged, but caller won't hear back
                throw BankTimeoutException("no response from bank for $transactionId")
            }
            else -> {
                ledger[transactionId] = State.CHARGED
                BankResult.CHARGED
            }
        }
    }

    fun getStatus(transactionId: UUID): BankStatus = when (ledger[transactionId]) {
        State.CHARGED -> BankStatus.CHARGED
        State.DECLINED -> BankStatus.DECLINED
        null -> BankStatus.NOT_FOUND
    }

    /** Compensating action. */
    fun refund(transactionId: UUID) {
        ledger.remove(transactionId)
    }
}
