package com.reckon.hold

import com.reckon.account.AccountRepository
import com.reckon.auth.CurrentUser
import com.reckon.platform.ApiException
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class AuthorizeRequest(val idempotencyKey: String, val toUserId: UUID, @field:Positive val amountPaisa: Long, val ttlSeconds: Long = 600)
data class CaptureRequest(val amountPaisa: Long? = null)
data class HoldResponse(val holdId: UUID, val status: String)

@RestController
@RequestMapping("/payments")
class PaymentsController(private val auth: AuthorizationService, private val accounts: AccountRepository) {
    @PostMapping("/authorize")
    fun authorize(@CurrentUser caller: UUID, @jakarta.validation.Valid @RequestBody req: AuthorizeRequest): HoldResponse {
        val payer = accounts.findByOwner(caller) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "no wallet")
        val payee = accounts.findByOwner(req.toUserId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_PAYEE", "no payee")
        return HoldResponse(auth.authorize(req.idempotencyKey, caller, payer.id, payee.id, req.amountPaisa, req.ttlSeconds), "HELD")
    }
    @PostMapping("/{holdId}/capture")
    fun capture(@CurrentUser caller: UUID, @PathVariable holdId: UUID, @RequestBody req: CaptureRequest): HoldResponse {
        val wallet = accounts.findByOwner(caller) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "no wallet")
        auth.capture(holdId, wallet.id, req.amountPaisa); return HoldResponse(holdId, "CAPTURED")
    }
    @PostMapping("/{holdId}/void")
    fun void(@CurrentUser caller: UUID, @PathVariable holdId: UUID): HoldResponse {
        val wallet = accounts.findByOwner(caller) ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "no wallet")
        auth.void(holdId, wallet.id); return HoldResponse(holdId, "VOIDED")
    }
}
