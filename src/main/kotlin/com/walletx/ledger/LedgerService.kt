package com.walletx.ledger

import com.walletx.account.AccountRepository
import com.walletx.account.AccountType
import com.walletx.platform.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LedgerService(
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val statusWriter: TxnStatusWriter,
) {
    /**
     * The ONLY money-writer for transfers. NOT itself @Transactional — it inserts the PENDING
     * header (auto-commit) then delegates to doTransfer (which IS @Transactional via Spring proxy).
     * Catching INSUFFICIENT_FUNDS persists FAILED status in its own nested transaction.
     */
    fun recordTransfer(
        type: TxnType, idempotencyKey: String, requestHash: String,
        initiatorId: UUID, from: UUID, to: UUID, amount: Long,
    ): UUID {
        require(amount > 0) { "amount must be positive" }
        require(from != to) { "cannot transfer to self" }

        // insertPending runs in auto-commit — PENDING header persists regardless of what follows
        val txnId = ledger.insertPending(type, idempotencyKey, requestHash, amount, initiatorId, from, to)

        try {
            doTransfer(txnId, from, to, amount)
            return txnId
        } catch (e: ApiException) {
            if (e.code == "INSUFFICIENT_FUNDS") statusWriter.failInOwnTxn(txnId, e.code)
            throw e
        }
    }

    /**
     * Atomic money movement. Public so Spring's proxy applies @Transactional.
     * lock rows (fixed order) -> check funds -> 2 entries -> update balances -> conditional status flip.
     */
    @Transactional
    fun doTransfer(txnId: UUID, from: UUID, to: UUID, amount: Long) {
        val locked = accounts.lockByIdsInOrder(listOf(from, to)).associateBy { it.id }
        val src = locked[from]!!

        if (src.type in setOf(AccountType.USER_WALLET, AccountType.MERCHANT) && src.balance < amount)
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "insufficient balance")

        ledger.insertEntry(LedgerEntry(txnId, from, Direction.DEBIT, amount))
        ledger.insertEntry(LedgerEntry(txnId, to, Direction.CREDIT, amount))
        accounts.applyDelta(from, -amount)
        accounts.applyDelta(to, amount)

        if (ledger.markCompletedIfPending(txnId) == 0)
            throw IllegalStateException("transaction $txnId no longer PENDING; aborting")
    }
}
