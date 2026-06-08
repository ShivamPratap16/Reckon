package com.reckon.ledger.controller

import com.reckon.account.repository.AccountRepository
import com.reckon.auth.config.CurrentUser
import com.reckon.ledger.dto.request.P2pRequest
import com.reckon.ledger.dto.response.TransferResult
import com.reckon.ledger.enums.TxnType
import com.reckon.ledger.service.LedgerService
import com.reckon.platform.exception.ApiException
import com.reckon.platform.util.RequestHash
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/transfers")
class TransferController(private val ledger: LedgerService, private val accounts: AccountRepository) {
    @PostMapping("/p2p")
    fun p2p(@CurrentUser callerId: UUID?, @RequestBody req: P2pRequest): ResponseEntity<TransferResult> {
        if (callerId == null) {
            throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "authentication required")
        }
        val from = accounts.findByOwner(callerId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "caller has no wallet")
        val to = accounts.findByOwner(req.toUserId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_PAYEE", "payee has no wallet")
        val requestHash = RequestHash.of("P2P", from.id, to.id, req.amountPaisa)
        val outcome = ledger.recordTransfer(
            TxnType.P2P,
            req.idempotencyKey,
            requestHash,
            callerId,
            from.id,
            to.id,
            req.amountPaisa,
        )
        return ResponseEntity.ok()
            .header("Idempotent-Replayed", outcome.replayed.toString())
            .body(TransferResult(outcome.transactionId, outcome.status))
    }
}
