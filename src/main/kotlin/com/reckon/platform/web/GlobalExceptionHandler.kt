package com.reckon.platform.web

import com.reckon.platform.dto.ApiErrorBody
import com.reckon.platform.exception.ApiException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<ApiErrorBody> = ResponseEntity.status(e.status).body(ApiErrorBody(e.code, e.message ?: e.code))

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException::class)
    fun handleValidation(e: org.springframework.web.bind.MethodArgumentNotValidException): ResponseEntity<ApiErrorBody> {
        val msg = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST)
            .body(ApiErrorBody("VALIDATION_ERROR", msg.ifBlank { "validation failed" }))
    }
}
