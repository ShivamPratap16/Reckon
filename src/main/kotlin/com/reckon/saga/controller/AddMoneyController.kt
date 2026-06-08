package com.reckon.saga.controller

import com.reckon.account.repository.AccountRepository
import com.reckon.auth.config.CurrentUser
import com.reckon.ledger.dto.response.TransferResult
import com.reckon.platform.exception.ApiException
import com.reckon.saga.dto.request.AddMoneyRequest
import com.reckon.saga.service.AddMoneyService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/wallet")
class AddMoneyController(private val saga: AddMoneyService, private val accounts: AccountRepository) {
    @PostMapping("/add-money")
    fun addMoney(@CurrentUser callerId: UUID, @Valid @RequestBody req: AddMoneyRequest): ResponseEntity<TransferResult> {
        val wallet = accounts.findByOwner(callerId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "caller has no wallet")
        val outcome = saga.addMoney(req.idempotencyKey, callerId, wallet.id, req.bankRef, req.amountPaisa)
        return ResponseEntity.ok()
            .header("Idempotent-Replayed", outcome.replayed.toString())
            .body(TransferResult(outcome.transactionId, outcome.status))
    }
}
