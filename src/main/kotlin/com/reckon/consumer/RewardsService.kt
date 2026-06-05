package com.reckon.consumer

import com.reckon.ledger.LedgerService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RewardsService(
    private val processed: ProcessedEventRepository,
    private val ledger: LedgerService,
    @Value("\${reckon.rewards.cashback-bps}") private val cashbackBps: Long,
) {
    companion object { const val CONSUMER = "rewards" }

    /** Idempotent: dedup mark + cashback in ONE transaction. Redelivery is a no-op. */
    @Transactional
    fun award(event: PaymentEvent) {
        if (event.type == "CASHBACK") return                       // never cashback-on-cashback (loop guard)
        if (!processed.markProcessed(CONSUMER, event.eventId)) return  // already handled -> skip
        val payer = event.fromAccountId ?: return
        val cashback = event.amount * cashbackBps / 10_000          // bps of amount, integer paisa
        if (cashback <= 0) return
        ledger.recordCashback(event.eventId, payer, cashback)
    }
}
