package com.reckon.chaos

import com.reckon.ledger.LedgerRepository
import com.reckon.recon.ReconciliationService
import com.reckon.saga.AddMoneyService
import com.reckon.saga.SagaRecoveryService
import com.reckon.support.Fixtures
import eu.rekawek.toxiproxy.model.ToxicDirection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SagaChaosTest : ChaosTestBase() {
    @Autowired lateinit var saga: AddMoneyService
    @Autowired lateinit var recovery: SagaRecoveryService
    @Autowired lateinit var recon: ReconciliationService
    @Autowired lateinit var ledgerRepo: LedgerRepository
    @Autowired lateinit var fixtures: Fixtures

    @AfterEach
    fun clearToxics() {
        pgProxy.toxics().getAll().forEach { it.remove() }
    }

    @Test
    fun `add-money saga plus recovery lose zero money under db chaos`() {
        val wallets = (1..6).map { fixtures.walletWith(0) }

        wallets.forEachIndexed { i, w ->
            // Inject a reset toxic on every other wallet to cut the connection mid-saga
            if (i % 2 == 0) {
                pgProxy.toxics().resetPeer("rp", ToxicDirection.DOWNSTREAM, 100)
            }
            try {
                saga.addMoney("sc-$i-${UUID.randomUUID()}", UUID.randomUUID(), w, "ref", 50000)
            } catch (e: Exception) {
                // expected: chaos may strand this saga mid-flight
            }
            pgProxy.toxics().getAll().forEach { it.remove() }
            Thread.sleep(10)
        }

        // Heal the network, then run recovery to complete any stranded sagas
        repeat(3) { recovery.recover() }

        // INVARIANT: every wallet must be consistent — balance == sum(entries), never partial
        wallets.forEach { w ->
            assertEquals(
                ledgerRepo.sumEntries(w), fixtures.balanceOf(w),
                "balance != sum(entries) for wallet $w"
            )
            val bal = fixtures.balanceOf(w)
            assertTrue(bal == 0L || bal == 50000L, "wallet $w has a partial balance: $bal")
        }

        // Scoped reconciliation: no balance drift for these wallets
        val report = recon.run()
        val driftedHere = report.balanceDrifts.filter { it.accountId in wallets }
        assertTrue(driftedHere.isEmpty(), "balance drift under chaos: $driftedHere")
    }
}
