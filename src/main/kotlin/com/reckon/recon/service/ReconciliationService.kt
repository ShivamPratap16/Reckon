package com.reckon.recon.service

import com.reckon.recon.model.ReconciliationReport
import com.reckon.recon.repository.ReconciliationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ReconciliationService(
    private val repo: ReconciliationRepository,
    @Value("\${reckon.reconciliation.stale-seconds}") private val staleSeconds: Long,
    @Value("\${reckon.reconciliation.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Run all audits. Read-only — reports, never mutates (a drift is a bug to investigate, not hide). */
    fun run(): ReconciliationReport {
        val report = ReconciliationReport(
            unbalancedTransactions = repo.findUnbalancedTransactions(),
            balanceDrifts = repo.findBalanceDrifts(),
            stuckPending = repo.findStuckPending(staleSeconds),
            reservedDrifts = repo.findReservedDrifts(),
        )
        if (!report.clean) {
            log.error(
                "RECONCILIATION FAILED: {} unbalanced txns, {} balance drifts, {} stuck pending, {} reserved drifts",
                report.unbalancedTransactions.size,
                report.balanceDrifts.size,
                report.stuckPending.size,
                report.reservedDrifts.size,
            )
        } else {
            log.info("Reconciliation clean")
        }
        return report
    }

    @Scheduled(fixedDelayString = "\${reckon.reconciliation.poll-ms:60000}")
    fun scheduled() {
        if (enabled) run()
    }
}
