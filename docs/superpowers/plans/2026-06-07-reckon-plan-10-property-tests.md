# Reckon Plan 10 — Property-Based Ledger Invariant Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Prove the ledger's core invariants hold under *arbitrary* operation sequences, not just hand-picked examples. Generate long randomized sequences of mixed operations (add-money, P2P transfer, authorize, capture, void) against a fresh set of accounts, then assert the invariants that must ALWAYS hold:
1. **Per account:** `balance == SUM(signed ledger entries)` (double-entry consistency).
2. **Per account:** `reserved_balance == SUM(amount of still-HELD holds for that account)`.
3. **No user/merchant wallet** ever goes negative.

**Why not jqwik:** jqwik's `@Property` runs on the JUnit Platform but NOT as Jupiter `@Test`, so Spring's `SpringExtension` won't `@Autowired`-inject beans or wire Testcontainers into property methods. We get the same value — random op sequences with invariant assertions — using seeded `kotlin.random.Random` inside standard Spring `@Test` methods (deterministic via fixed seeds, fast, DB-friendly). Documented as a deliberate choice.

**Tech Stack:** Existing only (no new deps). Reuses `AddMoneyService`, `LedgerService`, `AuthorizationService`, `LedgerRepository`, `HoldRepository`.

**Builds on:** Plans 1–9 (`com.reckon`, 73 tests).

---

## Scoping note (shared singleton Postgres)
Each randomized run creates its OWN fresh accounts (funded via the ledgered `addMoney` saga, so `balance == SUM(entries)` holds for them) and operates ONLY among those accounts. Assertions are **scoped to the run's own accounts** — never global — so other test classes' data (e.g. `walletWith`-seeded balances) cannot cause false failures.

---

## File Structure
```
src/test/kotlin/com/reckon/property/LedgerInvariantPropertyTest.kt   # NEW
```

---

## Task 1: Randomized invariant property test
**Files:** Create `src/test/kotlin/com/reckon/property/LedgerInvariantPropertyTest.kt`
- [ ] **Step 1: Write** (extends `PostgresTestBase`). A reusable `runRandomSequence(seed)` builds accounts, funds them via the saga, fires a long mix of random operations (each in try/catch — failures like INSUFFICIENT_FUNDS / INSUFFICIENT_AVAILABLE are expected and fine), then asserts the three invariants scoped to the run's accounts. Drive it from several fixed seeds for determinism + coverage.
```kotlin
package com.reckon.property

import com.reckon.account.AccountRepository
import com.reckon.hold.AuthorizationService
import com.reckon.hold.HoldStatus
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
        "SELECT COALESCE(SUM(amount),0) FROM holds WHERE payer_account_id=? AND status='HELD'", Long::class.java, id)!!

    /** Run a randomized sequence of mixed operations, then assert the invariants for THIS run's accounts. */
    private fun runRandomSequence(seed: Long) {
        val rnd = Random(seed)
        val wallets = (1..NUM_ACCOUNTS).map { fixtures.walletWith(0) }
        // fund each via the ledgered saga so balance == SUM(entries) starts true
        wallets.forEach { w ->
            saga.addMoney("prop-fund-$seed-${UUID.randomUUID()}", UUID.randomUUID(), w, "ref", (rnd.nextLong(5, 50) * 10_000))
        }
        val heldHolds = ArrayDeque<UUID>()   // hold ids currently HELD (best-effort; may be stale, hence try/catch)

        repeat(NUM_OPS) {
            try {
                when (rnd.nextInt(5)) {
                    0, 1 -> {  // P2P transfer
                        val from = wallets.random(rnd); val to = wallets.filter { it != from }.random(rnd)
                        ledger.recordTransfer(TxnType.P2P, "p-${UUID.randomUUID()}",
                            RequestHash.of("P2P", from, to, 1), UUID.randomUUID(), from, to, rnd.nextLong(1, 30_000))
                    }
                    2 -> {  // authorize a hold
                        val from = wallets.random(rnd); val to = wallets.filter { it != from }.random(rnd)
                        val h = auth.authorize("a-${UUID.randomUUID()}", UUID.randomUUID(), from, to, rnd.nextLong(1, 30_000), 600)
                        heldHolds.addLast(h)
                    }
                    3 -> {  // capture a held hold (full or partial)
                        val h = heldHolds.removeFirstOrNull() ?: return@repeat
                        val payer = auth.let { _ -> jdbc.queryForObject("SELECT payer_account_id FROM holds WHERE id=?", UUID::class.java, h)!! }
                        val amt = jdbc.queryForObject("SELECT amount FROM holds WHERE id=?", Long::class.java, h)!!
                        auth.capture(h, payer, if (rnd.nextBoolean()) null else (amt / 2).coerceAtLeast(1))
                    }
                    4 -> {  // void a held hold
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
```
- [ ] **Step 2: Run → PASS** (`./gradlew test --tests "com.reckon.property.LedgerInvariantPropertyTest"`). If a seed fails, that's either a real invariant bug (investigate — do NOT loosen the assertion) or a test-harness issue (e.g. a hold captured/voided by a stale id — the try/catch should absorb those). The invariants must hold for ALL seeds.
- [ ] **Step 3: Run FULL suite** `./gradlew test` → all green (Plans 1–9 + property).
- [ ] **Step 4: Commit** `git add -A && git commit -m "test: property-based ledger invariants over randomized operation sequences"`

---

## Task 2: README
**Files:** Modify `README.md`
- [ ] **Step 1:** Add a short "Property-based invariant testing" note: randomized sequences of mixed operations (transfer/authorize/capture/void) are run against fresh accounts and the core invariants (`balance == SUM(entries)`, `reserved == SUM(HELD holds)`, no negative wallet) are asserted to hold for every sequence and seed. Mention the deliberate choice of seeded-random-in-Spring-`@Test` over jqwik (Spring DI / Testcontainers integration).
- [ ] **Step 2: Run FULL suite** → green. **Commit** `git add -A && git commit -m "docs: property-based invariant testing"`

---

## Done criteria (Plan 10)
- `./gradlew test` green incl. `LedgerInvariantPropertyTest` (5 seeds × 120 mixed ops each).
- For every randomized sequence and seed, the invariants hold: per-account `balance == SUM(entries)`, `reserved == SUM(HELD holds)`, no negative wallet, no negative reserved.
- README documents the approach and the jqwik trade-off.

## What's next
- Plan 11: chaos testing (Toxiproxy) — inject latency/partitions; prove saga + recovery lose zero money.
- Plan 12: observability (OpenTelemetry tracing + Prometheus/Grafana + correlation IDs).
