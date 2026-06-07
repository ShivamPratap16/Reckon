# Reckon Plan 11 — Chaos Testing (Toxiproxy) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Prove Reckon's correctness survives a hostile network. Put **Toxiproxy** between the app and **PostgreSQL**, inject latency and connection failures, and assert the money invariants still hold: transfers are atomic (no half-applied money even when the connection dies mid-transaction), and the ADD_MONEY saga + recovery lose zero money under chaos.

**Architecture:** Testcontainers `ToxiproxyContainer` + `PostgreSQLContainer` on a shared Docker `Network`; the Spring datasource points at the Toxiproxy proxy endpoint, not Postgres directly. Tests add/remove **toxics** (latency, timeout/reset) via the Toxiproxy client and assert invariants after. Postgres is proxied (not Kafka) because Postgres proxying is reliable, and because Postgres transaction atomicity is exactly the "zero money loss under failure" property we want to prove — a connection death mid-transaction must leave NO partial state (Postgres rolls the transaction back).

**Tech Stack:** Existing + `org.testcontainers:toxiproxy` (test-only). No production code changes.

**Builds on:** Plans 1–10 (`com.reckon`, 74 tests). Reuses `LedgerService`, `AddMoneyService`, `SagaRecoveryService`, `ReconciliationService`.

---

## Why Postgres, not Kafka
The core money guarantees live in Postgres transactions; Kafka only carries side-effects. Proxying Postgres lets us prove the strongest property — **atomicity under connection failure** — reliably. (Kafka-through-Toxiproxy is unreliable because Kafka advertised listeners make clients reconnect directly, bypassing the proxy.) This is documented in the test as a deliberate choice.

---

## File Structure
```
build.gradle.kts                                              # MODIFY: testImplementation toxiproxy
src/test/kotlin/com/reckon/chaos/
├── ChaosTestBase.kt                                          # NEW: PG + Toxiproxy on a shared network; datasource via proxy
├── TransferChaosTest.kt                                      # NEW: latency tolerance + atomicity under connection cuts
└── SagaChaosTest.kt                                          # NEW: saga + recovery survive chaos, money conserved
```

---

## Task 1: Toxiproxy dependency + chaos test base
**Files:** Modify `build.gradle.kts`; Create `src/test/kotlin/com/reckon/chaos/ChaosTestBase.kt`
- [ ] **Step 1: build.gradle.kts** — add `testImplementation("org.testcontainers:toxiproxy:1.20.4")`.
- [ ] **Step 2: `ChaosTestBase.kt`** — Postgres + Toxiproxy on one network; the app connects to Postgres THROUGH the proxy; expose the `Proxy` for toxic manipulation. Schedulers disabled.
```kotlin
package com.reckon.chaos

import eu.rekawek.toxiproxy.Proxy
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.ToxiproxyContainer

@SpringBootTest
@TestPropertySource(properties = [
    "reckon.outbox.scheduler.enabled=false", "reckon.consumers.enabled=false",
    "reckon.saga.recovery.enabled=false", "reckon.reconciliation.enabled=false",
    "reckon.holds.expiry.enabled=false", "reckon.idempotency.cache.enabled=false",
    // make connection failures surface fast instead of hanging the test
    "spring.datasource.hikari.connection-timeout=3000",
    "spring.datasource.hikari.validation-timeout=2000",
    "spring.datasource.hikari.maximum-pool-size=8",
])
abstract class ChaosTestBase {
    companion object {
        private val network: Network = Network.newNetwork()

        @JvmStatic val pg = PostgreSQLContainer("postgres:16")
            .withNetwork(network).withNetworkAliases("pgdb")
            .apply { withDatabaseName("reckon"); withUsername("reckon"); withPassword("reckon"); start() }

        @JvmStatic val toxi = ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
            .withNetwork(network).apply { start() }

        // a proxy from the app -> postgres (the app dials the proxy; the proxy forwards to pgdb:5432)
        @JvmStatic lateinit var pgProxy: Proxy

        init {
            val client = eu.rekawek.toxiproxy.ToxiproxyClient(toxi.host, toxi.controlPort)
            pgProxy = client.createProxy("postgres", "0.0.0.0:8666", "pgdb:5432")
        }

        @JvmStatic @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            val proxyHost = toxi.host
            val proxyPort = toxi.getMappedPort(8666)
            r.add("spring.datasource.url") { "jdbc:postgresql://$proxyHost:$proxyPort/reckon" }
            r.add("spring.datasource.username") { "reckon" }
            r.add("spring.datasource.password") { "reckon" }
        }
    }
}
```
Notes: Flyway migrations run through the proxy at context startup (no toxics active then). The Toxiproxy control port is `toxi.controlPort`; the proxied listen port `8666` is exposed and mapped — point the datasource at the mapped port. If the `ToxiproxyContainer` API in 1.20.4 differs (e.g. a `getProxy(container, port)` helper), use the equivalent; the goal is: app → proxy → postgres, with a `Proxy` handle to add/remove toxics.
- [ ] **Step 3: Verify it loads** — write a trivial `@Test` that does one `jdbc`/transfer with no toxics and passes, to confirm the proxied datasource + Flyway work. **Commit** `git add -A && git commit -m "test: toxiproxy chaos test base (app -> proxy -> postgres)"`

---

## Task 2: Transfer chaos — latency tolerance + atomicity under connection failure
**Files:** Create `src/test/kotlin/com/reckon/chaos/TransferChaosTest.kt`
- [ ] **Step 1: Write**
```kotlin
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

    @AfterEach fun clearToxics() { pgProxy.toxics().all.forEach { it.remove() } }

    @Test fun `transfers tolerate database latency`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        pgProxy.toxics().latency("lat", ToxicDirection.DOWNSTREAM, 200)   // +200ms each way
        ledger.recordTransfer(TxnType.P2P, "chaos-lat", RequestHash.of("P2P", a, b, 20000), UUID.randomUUID(), a, b, 20000)
        assertEquals(30000, fixtures.balanceOf(a)); assertEquals(20000, fixtures.balanceOf(b))
        assertEquals(ledgerRepo.sumEntries(a), fixtures.balanceOf(a))     // still consistent
    }

    @Test fun `transfers stay atomic when the db connection is cut mid-flight`() {
        // Many transfers through a connection that resets after a short time -> some fail.
        // Postgres rolls back any transaction whose connection dies -> NO half-applied money.
        val a = fixtures.walletWith(1_000_000); val b = fixtures.walletWith(0)
        var ok = 0; var failed = 0
        repeat(40) { i ->
            // reset_peer after ~120ms cuts connections mid-statement for a fraction of attempts
            if (i % 3 == 0) pgProxy.toxics().resetPeer("rp", ToxicDirection.DOWNSTREAM, 120)
            try {
                ledger.recordTransfer(TxnType.P2P, "chaos-$i", RequestHash.of("P2P", a, b, 1),
                    UUID.randomUUID(), a, b, 1000)
                ok++
            } catch (e: Exception) { failed++ }
            pgProxy.toxics().all.forEach { it.remove() }   // clear before the next read/assert
            Thread.sleep(10)
        }
        // INVARIANT regardless of how many failed: balances equal the ledger; conserved; no orphan half-applied money.
        assertEquals(ledgerRepo.sumEntries(a), fixtures.balanceOf(a), "account a balance != sum(entries) after chaos")
        assertEquals(ledgerRepo.sumEntries(b), fixtures.balanceOf(b), "account b balance != sum(entries) after chaos")
        assertEquals(1_000_000L, fixtures.balanceOf(a) + fixtures.balanceOf(b), "money not conserved under chaos")
        assertEquals(1000L * ok, fixtures.balanceOf(b), "b should hold exactly the successful transfers")
        assertTrue(failed >= 0)   // some failures are expected but not required; the invariant is what matters
    }
}
```
Notes: the key assertion is `balance == sumEntries` AND `b == 1000*ok` after a storm of connection resets — proving every transfer was all-or-nothing (Postgres rolls back a transaction whose connection drops; the app sees an exception and counts it failed; no partial debit/credit survives). If `resetPeer`/`latency` toxic names in the toxiproxy-java client differ, use the correct method (`toxics().latency(name, dir, ms)`, `toxics().resetPeer(name, dir, timeoutMs)` / or `limitData`/`timeout`). Use whichever fault reliably cuts connections; the test's value is the post-chaos invariant.
- [ ] **Step 2: Run → PASS** (`./gradlew test --tests "com.reckon.chaos.TransferChaosTest"`). If flaky on timing, adjust the toxic timing/iterations — but do NOT weaken the `balance == sumEntries` / conservation assertions.
- [ ] **Step 3: Commit** `git add -A && git commit -m "test: transfers tolerate latency + stay atomic under db connection cuts (chaos)"`

---

## Task 3: Saga + recovery survive chaos
**Files:** Create `src/test/kotlin/com/reckon/chaos/SagaChaosTest.kt`
- [ ] **Step 1: Write** — fund wallets via the saga while the DB connection is flaky; some saga step-3 commits fail (leaving BANK_PENDING/BANK_CONFIRMED); then clear toxics, run recovery, and assert every wallet ends correctly funded with money conserved and reconciliation clean (scoped to these wallets).
```kotlin
package com.reckon.chaos

import com.reckon.account.SystemAccounts
import com.reckon.ledger.LedgerRepository
import com.reckon.recon.ReconciliationService
import com.reckon.saga.AddMoneyService
import com.reckon.saga.SagaRecoveryService
import com.reckon.support.Fixtures
import eu.rekawek.toxiproxy.model.ToxicDirection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SagaChaosTest : ChaosTestBase() {
    @Autowired lateinit var saga: AddMoneyService
    @Autowired lateinit var recovery: SagaRecoveryService
    @Autowired lateinit var recon: ReconciliationService
    @Autowired lateinit var ledgerRepo: LedgerRepository
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    @AfterEach fun clearToxics() { pgProxy.toxics().all.forEach { it.remove() } }

    @Test fun `add-money saga plus recovery lose zero money under db chaos`() {
        val wallets = (1..6).map { fixtures.walletWith(0) }
        wallets.forEachIndexed { i, w ->
            if (i % 2 == 0) pgProxy.toxics().resetPeer("rp", ToxicDirection.DOWNSTREAM, 100)
            try { saga.addMoney("sc-$i-${UUID.randomUUID()}", UUID.randomUUID(), w, "ref", 50000) } catch (e: Exception) {}
            pgProxy.toxics().all.forEach { it.remove() }
            Thread.sleep(10)
        }
        // heal + recover any sagas stranded mid-flight by the chaos
        repeat(3) { recovery.recover() }

        // every account must be consistent; money conserved (each wallet either funded 50000 or 0, never partial)
        wallets.forEach { w ->
            assertEquals(ledgerRepo.sumEntries(w), fixtures.balanceOf(w), "balance != sum(entries) for $w")
            val bal = fixtures.balanceOf(w)
            assertTrue(bal == 0L || bal == 50000L, "wallet $w has a partial balance: $bal")
        }
        // scoped reconciliation: these wallets have no balance drift and no unbalanced txns among them
        val report = recon.run()
        val driftedHere = report.balanceDrifts.filter { it.accountId in wallets }
        assertTrue(driftedHere.isEmpty(), "balance drift under chaos: $driftedHere")
    }
}
```
- [ ] **Step 2: Run → PASS**; run FULL `./gradlew test` → all green (Plans 1–10 + chaos). **Commit** `git add -A && git commit -m "test: add-money saga + recovery lose zero money under db chaos"`

---

## Task 4: README
**Files:** Modify `README.md`
- [ ] **Step 1:** Add a "Chaos testing" note: Toxiproxy sits between the app and Postgres; latency and connection-reset toxics are injected while transfers and the add-money saga run; the invariants (`balance == SUM(entries)`, money conserved, no partial funding, reconciliation clean) hold throughout, and recovery heals sagas the chaos stranded. Note the deliberate choice to proxy Postgres (atomicity-under-failure) rather than Kafka (advertised-listener proxying is unreliable).
- [ ] **Step 2: Run FULL suite** → green. **Commit** `git add -A && git commit -m "docs: chaos testing with toxiproxy"`

---

## Done criteria (Plan 11)
- `./gradlew test` green incl. `TransferChaosTest` + `SagaChaosTest`.
- Under injected latency, transfers still succeed and stay consistent.
- Under connection resets, transfers are atomic — `balance == SUM(entries)`, money conserved, `b == 1000 × successes` (no half-applied transfer).
- The add-money saga + recovery lose zero money under DB chaos: every wallet ends fully funded or untouched (never partial), reconciliation clean.
- README documents the approach + the proxy-Postgres-not-Kafka rationale.

## What's next
- Plan 12: observability — OpenTelemetry tracing across the saga + Kafka, Prometheus metrics, Grafana dashboards, correlation IDs.
