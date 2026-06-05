package com.reckon.ledger

import com.reckon.platform.ApiException
import com.reckon.platform.RequestHash
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class IdempotencyTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var repo: LedgerRepository

    private fun transfer(key: String, from: UUID, to: UUID, amount: Long, initiator: UUID = UUID.randomUUID()) =
        ledger.recordTransfer(TxnType.P2P, key, RequestHash.of("P2P", from, to, amount), initiator, from, to, amount)

    @Test fun `retry with same key and request does not double-debit and replays`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val first = transfer("dup-1", a, b, 20000, initiator)
        val second = transfer("dup-1", a, b, 20000, initiator)   // identical retry

        assertEquals(first.transactionId, second.transactionId)  // same txn replayed
        assertFalse(first.replayed); assertTrue(second.replayed)
        assertEquals(30000, fixtures.balanceOf(a))               // debited ONCE, not twice
        assertEquals(20000, fixtures.balanceOf(b))
    }

    @Test fun `same key different amount is rejected 422`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        transfer("dup-2", a, b, 20000, initiator)
        val ex = assertThrows<ApiException> { transfer("dup-2", a, b, 99999, initiator) }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status)
        assertEquals("IDEMPOTENCY_KEY_REUSE", ex.code)
    }

    @Test fun `in-flight PENDING with same key returns 409`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val hash = RequestHash.of("P2P", a, b, 20000)
        // simulate an in-flight first attempt: a PENDING row exists but execute hasn't completed
        repo.insertPending(TxnType.P2P, "dup-3", hash, 20000, initiator, a, b)
        val ex = assertThrows<ApiException> {
            ledger.recordTransfer(TxnType.P2P, "dup-3", hash, initiator, a, b, 20000)
        }
        assertEquals(HttpStatus.CONFLICT, ex.status)
        assertEquals("IN_PROGRESS", ex.code)
    }

    @Test fun `retry of a failed transfer replays the failure`() {
        val a = fixtures.walletWith(100); val b = fixtures.walletWith(0)   // insufficient
        val initiator = UUID.randomUUID()
        val first = assertThrows<ApiException> { transfer("dup-4", a, b, 99999, initiator) }
        assertEquals("INSUFFICIENT_FUNDS", first.code)
        val second = assertThrows<ApiException> { transfer("dup-4", a, b, 99999, initiator) }
        assertEquals("INSUFFICIENT_FUNDS", second.code)             // same failure replayed
        assertEquals(100, fixtures.balanceOf(a))                    // still untouched
    }

    @Test fun `different initiators may reuse the same key independently`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val c = fixtures.walletWith(50000)
        transfer("shared-key", a, b, 10000, UUID.randomUUID())
        // a different initiator using the SAME key string must NOT collide (scoped by initiator)
        val other = transfer("shared-key", c, b, 10000, UUID.randomUUID())
        assertFalse(other.replayed)
        assertEquals(40000, fixtures.balanceOf(c))
    }
}
