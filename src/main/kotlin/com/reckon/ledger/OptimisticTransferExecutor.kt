package com.reckon.ledger

import com.reckon.account.AccountRepository
import com.reckon.account.AccountType
import com.reckon.platform.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Optimistic-locking transfer: no row locks. Read balances+versions, write entries, then
 * compare-and-set both balances on their versions; if any CAS loses (concurrent writer bumped
 * the version), the whole attempt rolls back and retries with fresh versions. Same schema and
 * same correctness guarantees as the pessimistic TransferExecutor — different contention profile.
 * Implemented as a parallel strategy for benchmarking against the pessimistic default.
 */
@Service
class OptimisticTransferExecutor(
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    txManager: PlatformTransactionManager,
    @Value("\${reckon.ledger.optimistic-max-retries:20}") private val maxRetries: Int,
) {
    private val tx = TransactionTemplate(txManager)

    /** Returns the number of attempts taken (for benchmark observability). */
    fun execute(txnId: UUID, type: TxnType, from: UUID, to: UUID, amount: Long): Int {
        require(from != to) { "cannot transfer to self" }
        var attempts = 0
        while (true) {
            attempts++
            try {
                tx.executeWithoutResult {
                    val src = accounts.findById(from) ?: error("no account $from")
                    val dst = accounts.findById(to) ?: error("no account $to")
                    if (src.type in setOf(AccountType.USER_WALLET, AccountType.MERCHANT) && src.balance < amount) {
                        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "insufficient balance")
                    }
                    ledger.insertEntry(LedgerEntry(txnId, from, Direction.DEBIT, amount))
                    ledger.insertEntry(LedgerEntry(txnId, to, Direction.CREDIT, amount))
                    if (accounts.applyDeltaCas(from, -amount, src.version) == 0) throw RetryConflict()
                    if (accounts.applyDeltaCas(to, amount, dst.version) == 0) throw RetryConflict()
                    if (ledger.markCompletedIfPending(txnId) == 0) throw IllegalStateException("not PENDING")
                }
                return attempts
            } catch (e: RetryConflict) {
                if (attempts >= maxRetries) {
                    throw ApiException(
                        HttpStatus.CONFLICT,
                        "TOO_MUCH_CONTENTION",
                        "optimistic retries exhausted after $attempts attempts",
                    )
                }
                // loop: re-read fresh versions and try again
            }
        }
    }
    private class RetryConflict : RuntimeException()
}
