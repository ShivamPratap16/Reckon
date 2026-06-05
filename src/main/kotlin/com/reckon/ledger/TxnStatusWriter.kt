package com.reckon.ledger

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TxnStatusWriter(private val ledger: LedgerRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failInOwnTxn(txnId: UUID, reason: String) = ledger.markFailed(txnId, reason)
}
