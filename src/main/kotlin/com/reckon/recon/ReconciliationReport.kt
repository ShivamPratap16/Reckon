package com.reckon.recon

import java.util.UUID

data class UnbalancedTxn(val transactionId: UUID, val net: Long)
data class BalanceDrift(val accountId: UUID, val storedBalance: Long, val computedBalance: Long)
data class ReservedDrift(val accountId: UUID, val storedReserved: Long, val computedReserved: Long)

data class ReconciliationReport(
    val unbalancedTransactions: List<UnbalancedTxn>,
    val balanceDrifts: List<BalanceDrift>,
    val stuckPending: List<UUID>,
    val reservedDrifts: List<ReservedDrift> = emptyList(),
) {
    val clean: Boolean get() = unbalancedTransactions.isEmpty() && balanceDrifts.isEmpty() && stuckPending.isEmpty() && reservedDrifts.isEmpty()
}
