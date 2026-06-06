package com.reckon.hold

import com.reckon.account.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Separate bean so that @Transactional expireOne is called cross-bean (via Spring proxy),
 * avoiding the self-invocation proxy trap that would occur if called from HoldExpiryService
 * on the same bean instance. Mirror of the Plan 3/Plan 5 pattern.
 */
@Service
class HoldExpiryWorker(
    private val holds: HoldRepository,
    private val accounts: AccountRepository,
) {
    @Transactional
    fun expireOne(hold: Hold): Boolean {
        accounts.lockByIdsInOrder(listOf(hold.payerAccountId))
        if (holds.markClosed(hold.id, HoldStatus.EXPIRED) == 0) return false   // already closed by a racing capture/void
        accounts.releaseReserve(hold.payerAccountId, hold.amount)
        return true
    }
}
