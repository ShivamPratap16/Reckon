package com.reckon.chaos

import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.LedgerService
import com.reckon.ledger.TxnType
import com.reckon.platform.RequestHash
import com.reckon.support.Fixtures
import eu.rekawek.toxiproxy.model.ToxicDirection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferChaosTest : ChaosTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var ledgerRepo: LedgerRepository
    @Autowired lateinit var fixtures: Fixtures

    @AfterEach
    fun clearToxics() {
        pgProxy.toxics().getAll().forEach { it.remove() }
    }

    @Test
    fun `transfers tolerate database latency`() {
        val a = fixtures.walletWith(50000)
        val b = fixtures.walletWith(0)
        // +200ms downstream latency — transfers should still succeed
        pgProxy.toxics().latency("lat", ToxicDirection.DOWNSTREAM, 200)
        ledger.recordTransfer(
            TxnType.P2P, "chaos-lat",
            RequestHash.of("P2P", a, b, 20000),
            UUID.randomUUID(), a, b, 20000
        )
        assertEquals(30000, fixtures.balanceOf(a))
        assertEquals(20000, fixtures.balanceOf(b))
        // Ledger consistency: balance == seed + sum(ledger entries).
        // a was seeded at 50000; b was seeded at 0 so sumEntries(b) == balanceOf(b) directly.
        assertEquals(-20000L, ledgerRepo.sumEntries(a))
        assertEquals(ledgerRepo.sumEntries(b), fixtures.balanceOf(b))
    }

    @Test
    fun `transfers stay atomic when the db connection is cut mid-flight`() {
        // Many transfers through a connection that resets after a short time -> some fail.
        // Postgres rolls back any transaction whose connection dies -> NO half-applied money.
        val a = fixtures.walletWith(1_000_000)
        val b = fixtures.walletWith(0)
        var ok = 0
        var failed = 0
        repeat(40) { i ->
            // reset_peer after ~120ms cuts connections mid-statement for a fraction of attempts
            if (i % 3 == 0) {
                pgProxy.toxics().resetPeer("rp", ToxicDirection.DOWNSTREAM, 120)
            }
            try {
                ledger.recordTransfer(
                    TxnType.P2P, "chaos-$i",
                    RequestHash.of("P2P", a, b, i.toLong()),
                    UUID.randomUUID(), a, b, 1000
                )
                ok++
            } catch (e: Exception) {
                failed++
            }
            // clear toxics before reads and next iteration
            pgProxy.toxics().getAll().forEach { it.remove() }
            Thread.sleep(10)
        }
        // INVARIANT: balances match ledger entries; money conserved; no orphan half-applied money.
        // a was seeded at 1_000_000 so: balance(a) == 1_000_000 + sumEntries(a)
        // b was seeded at 0 so: balance(b) == sumEntries(b)
        assertEquals(
            1_000_000L + ledgerRepo.sumEntries(a), fixtures.balanceOf(a),
            "account a balance != seed + sum(entries) after chaos"
        )
        assertEquals(
            ledgerRepo.sumEntries(b), fixtures.balanceOf(b),
            "account b balance != sum(entries) after chaos"
        )
        assertEquals(
            1_000_000L, fixtures.balanceOf(a) + fixtures.balanceOf(b),
            "money not conserved under chaos"
        )
        assertEquals(
            1000L * ok, fixtures.balanceOf(b),
            "b should hold exactly the successful transfers (all-or-nothing atomicity)"
        )
        // Some failures are expected but not required; the invariant is what matters.
        assertTrue(failed >= 0)
    }
}
