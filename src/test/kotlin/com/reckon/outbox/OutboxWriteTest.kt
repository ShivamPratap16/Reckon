package com.reckon.outbox

import com.reckon.ledger.enums.TxnType
import com.reckon.ledger.service.LedgerService
import com.reckon.platform.util.RequestHash
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals

@TestPropertySource(properties = ["reckon.outbox.scheduler.enabled=false"])
class OutboxWriteTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService

    @Autowired lateinit var fixtures: Fixtures

    @Autowired lateinit var jdbc: JdbcTemplate

    private fun outboxCountFor(txnId: UUID) = jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'payment.completed'",
        Long::class.java,
        txnId,
    )

    @Test fun `successful transfer writes exactly one outbox event in the same transaction`() {
        val a = fixtures.walletWith(50000)
        val b = fixtures.walletWith(0)
        val txn = ledger.recordTransfer(
            TxnType.P2P,
            "ob-1",
            RequestHash.of("P2P", a, b, 20000),
            UUID.randomUUID(),
            a,
            b,
            20000,
        ).transactionId
        assertEquals(1L, outboxCountFor(txn))
    }

    @Test fun `failed transfer writes no outbox event (rolled back with the txn)`() {
        val a = fixtures.walletWith(100)
        val b = fixtures.walletWith(0)
        val idem = "ob-2"
        assertThrows<com.reckon.platform.exception.ApiException> {
            ledger.recordTransfer(
                TxnType.P2P,
                idem,
                RequestHash.of("P2P", a, b, 99999),
                UUID.randomUUID(),
                a,
                b,
                99999,
            )
        }
        val cnt = jdbc.queryForObject(
            """SELECT COUNT(*) FROM outbox o JOIN transactions t ON t.id = o.aggregate_id
               WHERE t.idempotency_key = ?""",
            Long::class.java,
            idem,
        )
        assertEquals(0L, cnt)
    }
}
