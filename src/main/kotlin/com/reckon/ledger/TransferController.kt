package com.reckon.ledger

import com.reckon.account.AccountRepository
import com.reckon.auth.CurrentUser
import com.reckon.platform.ApiException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
    fun p2p(@CurrentUser callerId: UUID?, @RequestBody req: P2pRequest): ResponseEntity<TransferResult> {
        if (callerId == null)
            throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "authentication required")
        val from = accounts.findByOwner(callerId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "caller has no wallet")
        val to = accounts.findByOwner(req.toUserId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_PAYEE", "payee has no wallet")
        val requestHash = com.reckon.platform.RequestHash.of("P2P", from.id, to.id, req.amountPaisa)
        val outcome = ledger.recordTransfer(
            TxnType.P2P, req.idempotencyKey, requestHash, callerId, from.id, to.id, req.amountPaisa)
        return ResponseEntity.ok()
            .header("Idempotent-Replayed", outcome.replayed.toString())
            .body(TransferResult(outcome.transactionId, outcome.status))
    }
}
