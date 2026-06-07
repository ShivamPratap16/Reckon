package com.reckon.property

import com.reckon.account.AccountRepository
import com.reckon.hold.AuthorizationService
import com.reckon.ledger.LedgerRepository
import com.reckon.ledger.LedgerService
import com.reckon.ledger.TxnType
import com.reckon.platform.RequestHash
import com.reckon.saga.AddMoneyService
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerInvariantPropertyTest : PostgresTestBase() {
    @Autowired lateinit var saga: AddMoneyService

    @Autowired lateinit var ledger: LedgerService

    @Autowired lateinit var ledgerRepo: LedgerRepository

    @Autowired lateinit var auth: AuthorizationService

    @Autowired lateinit var accounts: AccountRepository

    @Autowired lateinit var fixtures: Fixtures

    @Autowired lateinit var jdbc: JdbcTemplate

    private val NUM_ACCOUNTS = 5
    private val NUM_OPS = 120

    private fun balance(id: UUID) = fixtures.balanceOf(id)
    private fun reserved(id: UUID) = jdbc.queryForObject("SELECT reserved_balance FROM accounts WHERE id=?", Long::class.java, id)!!
    private fun heldSum(id: UUID) = jdbc.queryForObject(
        "SELECT COALESCE(SUM(amount),0) FROM holds WHERE payer_account_id=? AND status='HELD'",
        Long::class.java,
        id,
    )!!

    /** Run a randomized sequence of mixed operations, then assert the invariants for THIS run's accounts. */
    private fun runRandomSequence(seed: Long) {
        val rnd = Random(seed)
        val wallets = (1..NUM_ACCOUNTS).map { fixtures.walletWith(0) }
        // fund each via the ledgered saga so balance == SUM(entries) starts true
        wallets.forEach { w ->
            saga.addMoney("prop-fund-$seed-${UUID.randomUUID()}", UUID.randomUUID(), w, "ref", (rnd.nextLong(5, 50) * 10_000))
        }
        val heldHolds = ArrayDeque<UUID>() // hold ids currently HELD (best-effort; may be stale, hence try/catch)

        repeat(NUM_OPS) {
            try {
                when (rnd.nextInt(5)) {
                    0, 1 -> { // P2P transfer
                        val from = wallets.random(rnd)
                        val to = wallets.filter { it != from }.random(rnd)
                        ledger.recordTransfer(
                            TxnType.P2P,
                            "p-${UUID.randomUUID()}",
                            RequestHash.of("P2P", from, to, 1),
                            UUID.randomUUID(),
                            from,
                            to,
                            rnd.nextLong(1, 30_000),
                        )
                    }
                    2 -> { // authorize a hold
                        val from = wallets.random(rnd)
                        val to = wallets.filter { it != from }.random(rnd)
                        val h = auth.authorize("a-${UUID.randomUUID()}", UUID.randomUUID(), from, to, rnd.nextLong(1, 30_000), 600)
                        heldHolds.addLast(h)
                    }
                    3 -> { // capture a held hold (full or partial)
                        val h = heldHolds.removeFirstOrNull() ?: return@repeat
                        val payer = jdbc.queryForObject("SELECT payer_account_id FROM holds WHERE id=?", UUID::class.java, h)!!
                        val amt = jdbc.queryForObject("SELECT amount FROM holds WHERE id=?", Long::class.java, h)!!
                        auth.capture(h, payer, if (rnd.nextBoolean()) null else (amt / 2).coerceAtLeast(1))
                    }
                    4 -> { // void a held hold
                        val h = heldHolds.removeFirstOrNull() ?: return@repeat
                        val payer = jdbc.queryForObject("SELECT payer_account_id FROM holds WHERE id=?", UUID::class.java, h)!!
                        auth.void(h, payer)
                    }
                }
            } catch (e: Exception) { /* expected: insufficient funds/available, stale hold, etc. Invariants must still hold. */ }
        }

        // INVARIANTS (scoped to this run's accounts)
        wallets.forEach { w ->
            assertEquals(balance(w), ledgerRepo.sumEntries(w), "balance != SUM(entries) for $w (seed=$seed)")
            assertEquals(heldSum(w), reserved(w), "reserved != SUM(HELD holds) for $w (seed=$seed)")
            assertTrue(balance(w) >= 0, "user wallet went negative: $w (seed=$seed)")
            assertTrue(reserved(w) >= 0, "reserved negative: $w (seed=$seed)")
        }
    }

    @Test fun `invariants hold across randomized operation sequences`() {
        listOf(1L, 7L, 42L, 1337L, 90210L).forEach { seed -> runRandomSequence(seed) }
    }
}
