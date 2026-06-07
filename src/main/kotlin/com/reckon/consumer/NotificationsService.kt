package com.reckon.consumer

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationsService(private val processed: ProcessedEventRepository) {
    companion object {
        const val CONSUMER = "notifications"
    }

    /** Idempotent notification: dedup keyed by (notifications, eventId), independent of rewards. */
    @Transactional
    fun notify(event: PaymentEvent): Boolean {
        if (!processed.markProcessed(CONSUMER, event.eventId)) return false // already notified
        // real side-effect would push a notification; here we just record it via the dedup row
        return true
    }
}
