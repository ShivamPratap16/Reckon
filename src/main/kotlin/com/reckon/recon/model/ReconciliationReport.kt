package com.reckon.recon.model

import java.util.UUID

data class ReconciliationReport(
    val unbalancedTransactions: List<UnbalancedTxn>,
    val balanceDrifts: List<BalanceDrift>,
    val stuckPending: List<UUID>,
    val reservedDrifts: List<ReservedDrift> = emptyList(),
) {
    val clean: Boolean get() = unbalancedTransactions.isEmpty() && balanceDrifts.isEmpty() && stuckPending.isEmpty() && reservedDrifts.isEmpty()
}
