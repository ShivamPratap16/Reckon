package com.reckon.idempotency

import com.reckon.ledger.enums.TxnType
import com.reckon.ledger.service.LedgerService
import com.reckon.platform.exception.ApiException
import com.reckon.platform.util.RequestHash
import com.reckon.support.Fixtures
import com.reckon.support.RedisPostgresTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdempotencyCacheTest : RedisPostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService

    @Autowired lateinit var fixtures: Fixtures

    @Autowired lateinit var redis: StringRedisTemplate

    private fun transfer(key: String, from: UUID, to: UUID, amt: Long, initiator: UUID) =
        ledger.recordTransfer(TxnType.P2P, key, RequestHash.of("P2P", from, to, amt), initiator, from, to, amt)

    @Test fun `completed result is cached and a retry replays from cache without double-debit`() {
        val a = fixtures.walletWith(50000)
        val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val first = transfer("redis-1", a, b, 20000, initiator)
        // cache now holds the terminal result
        assertNotNull(redis.opsForValue().get("idem:$initiator:redis-1"), "expected cached entry")
        val second = transfer("redis-1", a, b, 20000, initiator) // served from cache
        assertEquals(first.transactionId, second.transactionId)
        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(30000, fixtures.balanceOf(a)) // debited ONCE
        assertEquals(20000, fixtures.balanceOf(b))
    }

    @Test fun `cached key reused with a different request is rejected 422`() {
        val a = fixtures.walletWith(50000)
        val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        transfer("redis-2", a, b, 20000, initiator)
        val ex = assertThrows<ApiException> { transfer("redis-2", a, b, 99999, initiator) } // different amount -> different hash
        assertEquals("IDEMPOTENCY_KEY_REUSE", ex.code)
    }

    @Test fun `a fresh key under the same initiator is not falsely served from cache`() {
        val a = fixtures.walletWith(50000)
        val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        transfer("redis-3a", a, b, 10000, initiator)
        val other = transfer("redis-3b", a, b, 10000, initiator) // different key -> cache miss -> real transfer
        assertFalse(other.replayed)
        assertEquals(30000, fixtures.balanceOf(a)) // two distinct debits of 10000
    }
}
