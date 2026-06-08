package com.reckon.observability

import com.reckon.ledger.enums.TxnType
import com.reckon.ledger.service.LedgerService
import com.reckon.platform.util.RequestHash
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import java.util.UUID
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService

    @Autowired lateinit var fixtures: Fixtures

    @Autowired lateinit var registry: MeterRegistry

    @Autowired lateinit var rest: TestRestTemplate

    @Test fun `transfer increments the transfers counter and records timing`() {
        val a = fixtures.walletWith(50000)
        val b = fixtures.walletWith(0)
        ledger.recordTransfer(TxnType.P2P, "m1", RequestHash.of("P2P", a, b, 10000), UUID.randomUUID(), a, b, 10000)
        val count = registry.find("reckon.transfers").tag("outcome", "COMPLETED").counter()?.count() ?: 0.0
        assertTrue(count >= 1.0, "transfers counter should have incremented")
        // exposed on the prometheus actuator endpoint
        val body = rest.getForEntity("/actuator/prometheus", String::class.java).body
            ?: error("Prometheus endpoint returned no body")
        assertTrue(body.contains("reckon_transfers"), "prometheus endpoint should expose reckon_transfers")
        assertTrue(body.contains("reckon_transfer_duration"), "should expose the transfer duration timer")
    }
}
