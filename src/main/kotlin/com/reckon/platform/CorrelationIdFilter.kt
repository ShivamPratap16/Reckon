package com.reckon.platform

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

const val CORRELATION_ID_HEADER = "X-Correlation-Id"
const val CORRELATION_ID_MDC = "correlationId"

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val correlationId = req.getHeader(CORRELATION_ID_HEADER)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        MDC.put(CORRELATION_ID_MDC, correlationId)
        res.setHeader(CORRELATION_ID_HEADER, correlationId)
        try {
            chain.doFilter(req, res)
        } finally {
            MDC.remove(CORRELATION_ID_MDC)
        }
    }
}
