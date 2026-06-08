package com.reckon.ledger.service

import com.reckon.account.enums.AccountType
import com.reckon.account.repository.AccountRepository
import com.reckon.ledger.enums.Direction
import com.reckon.ledger.enums.TxnType
import com.reckon.ledger.model.LedgerEntry
import com.reckon.ledger.repository.LedgerRepository
import com.reckon.outbox.constant.EventType
import com.reckon.outbox.repository.OutboxRepository
import com.reckon.platform.exception.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Executes the atomic money-moving body of a transfer in ONE database transaction.
 * Lives in a separate bean so the @Transactional proxy actually engages when called
 * from LedgerService (a same-bean self-call would silently bypass the proxy).
 */
@Service
class TransferExecutor(private val accounts: AccountRepository, private val ledger: LedgerRepository, private val outbox: OutboxRepository) {
    @Transactional
    fun execute(txnId: UUID, type: TxnType, from: UUID, to: UUID, amount: Long, emitEvent: Boolean = true, sagaGuard: Boolean = false) {
        // lock both rows in fixed id order (deadlock-safe); lock is held for the whole txn
        val locked = accounts.lockByIdsInOrder(listOf(from, to)).associateBy { it.id }
        val src = locked[from] ?: error("source account not found: $from")
        if (src.type in setOf(AccountType.USER_WALLET, AccountType.MERCHANT) && src.balance < amount) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "insufficient balance")
        }
        ledger.insertEntry(LedgerEntry(txnId, from, Direction.DEBIT, amount))
        ledger.insertEntry(LedgerEntry(txnId, to, Direction.CREDIT, amount))
        accounts.applyDelta(from, -amount)
        accounts.applyDelta(to, amount)
        if (emitEvent) {
            val payload = """{"eventId":null,"transactionId":"$txnId","type":"${type.name}",""" +
                """"fromAccountId":"$from","toAccountId":"$to","amount":$amount,"status":"COMPLETED"}"""
            outbox.append(txnId, EventType.PAYMENT_COMPLETED, payload)
        }
        val flipped = if (sagaGuard) ledger.markCompletedIfBankConfirmed(txnId) else ledger.markCompletedIfPending(txnId)
        if (flipped == 0) throw IllegalStateException("transaction $txnId not in the expected state; aborting")
    }
}
