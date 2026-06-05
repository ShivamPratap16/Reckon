package com.walletx.ledger

import com.walletx.account.AccountRepository
import com.walletx.auth.CurrentUser
import com.walletx.platform.ApiException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/transfers")
class TransferController(
    private val ledger: LedgerService,
    private val accounts: AccountRepository,
) {
    @PostMapping("/p2p")
    fun p2p(@CurrentUser callerId: UUID?, @RequestBody req: P2pRequest): TransferResult {
        if (callerId == null)
            throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "authentication required")
        val from = accounts.findByOwner(callerId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "caller has no wallet")
        val to = accounts.findByOwner(req.toUserId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_PAYEE", "payee has no wallet")
        // TODO(Plan 2): replace String.hashCode() with a stable digest (e.g. SHA-256) before using requestHash for idempotency replay.
        val requestHash = listOf("P2P", from.id, to.id, req.amountPaisa).joinToString("|").hashCode().toString()
        val txnId = ledger.recordTransfer(
            TxnType.P2P, req.idempotencyKey, requestHash, callerId, from.id, to.id, req.amountPaisa)
        return TransferResult(txnId, "COMPLETED")
    }
}
