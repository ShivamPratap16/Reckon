package com.reckon.hold

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class HoldExpiryService(
    private val holds: HoldRepository,
    private val worker: HoldExpiryWorker,
    @Value("\${reckon.holds.expiry.enabled:true}") private val enabled: Boolean,
    @Value("\${reckon.holds.expiry.batch-size:100}") private val batchSize: Int,
) {
    /** Release reservations for holds past expiry. Each hold released in its own transaction (via cross-bean worker). */
    fun expireDue(now: Instant): Int {
        var n = 0
        for (hold in holds.findExpired(now, batchSize)) {
            if (worker.expireOne(hold)) n++
        }
        return n
    }

    @Scheduled(fixedDelayString = "\${reckon.holds.expiry.poll-ms:30000}")
    fun scheduled() {
        if (enabled) expireDue(Instant.now())
    }
}
