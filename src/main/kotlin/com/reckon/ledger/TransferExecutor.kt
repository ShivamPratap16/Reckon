package com.reckon.ledger

import com.reckon.account.AccountRepository
import com.reckon.account.AccountType
import com.reckon.outbox.EventType
import com.reckon.outbox.OutboxRepository
import com.reckon.platform.ApiException
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
class TransferExecutor(
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val outbox: OutboxRepository,
) {
    @Transactional
    fun execute(txnId: UUID, from: UUID, to: UUID, amount: Long) {
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
        val payload = """{"eventId":null,"transactionId":"$txnId","type":"P2P",""" +
            """"fromAccountId":"$from","toAccountId":"$to","amount":$amount,"status":"COMPLETED"}"""
        outbox.append(txnId, EventType.PAYMENT_COMPLETED, payload)
        // conditional status flip — recovery-vs-slow-request guard; throwing rolls back this whole txn
        if (ledger.markCompletedIfPending(txnId) == 0) {
            throw IllegalStateException("transaction $txnId no longer PENDING; aborting")
        }
    }
}
