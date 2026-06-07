package com.reckon.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

annotation class CurrentUser

@Component
class JwtAuthFilter(private val jwt: JwtService) : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val header = req.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val userId = jwt.verify(header.removePrefix("Bearer "))
            if (userId != null) {
                val authToken = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                SecurityContextHolder.getContext().authentication = authToken
            }
        }
        chain.doFilter(req, res)
    }
}

@Component
class CurrentUserResolver : HandlerMethodArgumentResolver, WebMvcConfigurer {
    override fun supportsParameter(p: MethodParameter) =
        p.hasParameterAnnotation(CurrentUser::class.java) && p.parameterType == UUID::class.java
    override fun resolveArgument(p: MethodParameter, c: ModelAndViewContainer?, w: NativeWebRequest, b: WebDataBinderFactory?): Any? =
        SecurityContextHolder.getContext().authentication?.principal as? UUID
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) { resolvers.add(this) }
}

@Configuration
class SecurityConfig(private val jwtFilter: JwtAuthFilter) {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/auth/**").permitAll()
                it.requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
