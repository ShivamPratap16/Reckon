package com.walletx.platform

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(val status: HttpStatus, val code: String, msg: String) : RuntimeException(msg)

data class ApiErrorBody(val code: String, val message: String)

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<ApiErrorBody> =
        ResponseEntity.status(e.status).body(ApiErrorBody(e.code, e.message ?: e.code))

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException::class)
    fun handleValidation(e: org.springframework.web.bind.MethodArgumentNotValidException): ResponseEntity<ApiErrorBody> {
        val msg = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST)
            .body(ApiErrorBody("VALIDATION_ERROR", msg.ifBlank { "validation failed" }))
    }
}
