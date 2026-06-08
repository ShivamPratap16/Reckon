package com.reckon.hold.controller

import com.reckon.account.repository.AccountRepository
import com.reckon.auth.config.CurrentUser
import com.reckon.hold.dto.request.AuthorizeRequest
import com.reckon.hold.dto.request.CaptureRequest
import com.reckon.hold.dto.response.HoldResponse
import com.reckon.hold.service.AuthorizationService
import com.reckon.platform.exception.ApiException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/payments")
class PaymentsController(private val auth: AuthorizationService, private val accounts: AccountRepository) {
    @PostMapping("/authorize")
    fun authorize(@CurrentUser caller: UUID, @Valid @RequestBody req: AuthorizeRequest): HoldResponse {
        val payer = accounts.findByOwner(caller) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "no wallet")
        val payee = accounts.findByOwner(req.toUserId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_PAYEE", "no payee")
        return HoldResponse(auth.authorize(req.idempotencyKey, caller, payer.id, payee.id, req.amountPaisa, req.ttlSeconds), "HELD")
    }

    @PostMapping("/{holdId}/capture")
    fun capture(@CurrentUser caller: UUID, @PathVariable holdId: UUID, @RequestBody req: CaptureRequest): HoldResponse {
        val wallet = accounts.findByOwner(caller) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "no wallet")
        auth.capture(holdId, wallet.id, req.amountPaisa)
        return HoldResponse(holdId, "CAPTURED")
    }

    @PostMapping("/{holdId}/void")
    fun void(@CurrentUser caller: UUID, @PathVariable holdId: UUID): HoldResponse {
        val wallet = accounts.findByOwner(caller) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "no wallet")
        auth.void(holdId, wallet.id)
        return HoldResponse(holdId, "VOIDED")
    }
}
