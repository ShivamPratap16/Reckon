package com.reckon.saga

import com.reckon.account.AccountRepository
import com.reckon.auth.CurrentUser
import com.reckon.ledger.TransferResult
import com.reckon.platform.ApiException
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class AddMoneyRequest(val idempotencyKey: String, val bankRef: String, @field:Positive val amountPaisa: Long)

@RestController
@RequestMapping("/wallet")
class AddMoneyController(private val saga: AddMoneyService, private val accounts: AccountRepository) {
    @PostMapping("/add-money")
    fun addMoney(@CurrentUser callerId: UUID, @jakarta.validation.Valid @RequestBody req: AddMoneyRequest): ResponseEntity<TransferResult> {
        val wallet = accounts.findByOwner(callerId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "caller has no wallet")
        val outcome = saga.addMoney(req.idempotencyKey, callerId, wallet.id, req.bankRef, req.amountPaisa)
        return ResponseEntity.ok()
            .header("Idempotent-Replayed", outcome.replayed.toString())
            .body(TransferResult(outcome.transactionId, outcome.status))
    }
}
