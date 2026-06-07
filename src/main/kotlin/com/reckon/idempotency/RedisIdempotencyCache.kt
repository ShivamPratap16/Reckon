package com.reckon.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class RedisIdempotencyCache(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    @Value("\${reckon.idempotency.cache.enabled:true}") private val enabled: Boolean,
    @Value("\${reckon.idempotency.cache.ttl-hours:24}") private val ttlHours: Long,
) : IdempotencyCache {
    private val log = LoggerFactory.getLogger(javaClass)
    private fun key(initiatorId: UUID, k: String) = "idem:$initiatorId:$k"

    override fun get(initiatorId: UUID, idempotencyKey: String): CachedResult? {
        if (!enabled) return null
        return try {
            redis.opsForValue().get(key(initiatorId, idempotencyKey))
                ?.let { mapper.readValue(it, CachedResult::class.java) }
        } catch (e: Exception) {
            log.warn("idempotency cache GET degraded: {}", e.message)
            null // DB remains source of truth
        }
    }

    override fun put(initiatorId: UUID, idempotencyKey: String, result: CachedResult) {
        if (!enabled) return
        try {
            redis.opsForValue().set(
                key(initiatorId, idempotencyKey),
                mapper.writeValueAsString(result),
                Duration.ofHours(ttlHours),
            )
        } catch (e: Exception) {
            log.warn("idempotency cache PUT degraded: {}", e.message) // best-effort
        }
    }
}
