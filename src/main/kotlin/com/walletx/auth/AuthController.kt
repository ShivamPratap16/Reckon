package com.walletx.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AuthRequest(@field:Email val email: String, @field:NotBlank val password: String)
data class AuthResponse(val token: String)

@RestController
@RequestMapping("/auth")
class AuthController(private val auth: AuthService) {
    @PostMapping("/signup")
    fun signup(@RequestBody req: AuthRequest) = AuthResponse(auth.signup(req.email, req.password))

    @PostMapping("/login")
    fun login(@RequestBody req: AuthRequest) = AuthResponse(auth.login(req.email, req.password))
}
