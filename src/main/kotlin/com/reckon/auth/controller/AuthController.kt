package com.reckon.auth.controller

import com.reckon.auth.dto.request.AuthRequest
import com.reckon.auth.dto.response.AuthResponse
import com.reckon.auth.service.AuthService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(private val auth: AuthService) {
    @PostMapping("/signup")
    fun signup(@Valid @RequestBody req: AuthRequest) = AuthResponse(auth.signup(req.email, req.password))

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: AuthRequest) = AuthResponse(auth.login(req.email, req.password))
}
