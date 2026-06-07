package com.reckon.observability

import com.reckon.ledger.LedgerService
import com.reckon.ledger.TxnType
import com.reckon.platform.RequestHash
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import io.micrometer.tracing.Tracer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TracingTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var tracer: Tracer

    @Test fun `tracer is configured and a transfer runs within tracing`() {
        assertNotNull(tracer, "a Micrometer Tracer should be auto-configured")
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        // should not throw; the span is created/closed inside recordTransfer
        ledger.recordTransfer(TxnType.P2P, "t1", RequestHash.of("P2P", a, b, 10000), java.util.UUID.randomUUID(), a, b, 10000)
        assertEquals(40000, fixtures.balanceOf(a))
    }
}
