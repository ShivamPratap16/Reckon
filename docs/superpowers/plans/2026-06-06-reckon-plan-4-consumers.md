# Reckon Plan 4 — Idempotent Consumers (Exactly-Once Effect) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Consume `payment.completed` events and award cashback exactly once, even though Kafka delivers at-least-once. Achieve exactly-once *effect* by making consumers idempotent via a `processed_events(consumer_name, event_id)` dedup table, with the dedup insert and the side-effect committed in one transaction. Demonstrate two independent consumers (rewards + notifications) on the same event.

**Architecture:** Each consumer's handler runs in one DB transaction: `INSERT processed_events(consumer, event_id) ON CONFLICT DO NOTHING` → if 0 rows, already handled, skip; else perform the side-effect. Cashback is written **only through the ledger** as its own `CASHBACK` transaction (DEBIT `REWARDS_POOL`, CREDIT the payer's wallet) — preserving the "only the ledger writes money" boundary. The dedup insert and the cashback ledger write share one transaction, so a redelivered event can never double-pay.

**Tech Stack:** Existing + Kafka consumer (`@KafkaListener`). Reuses the Kafka Testcontainer from Plan 3.

**Builds on:** Plans 1–3 (`com.reckon`, `TransferExecutor.execute`, outbox publishing `payment.completed`, 35 tests).

**Out of scope:** saga/ADD_MONEY (Plan 5), reconciliation (Plan 6).

---

## Loop-safety (important design note)
Cashback writes a `CASHBACK` ledger transaction. To prevent a feedback loop (cashback → event → cashback), the cashback ledger write does **NOT** emit a `payment.completed` outbox event (Task 3 adds an `emitEvent` flag to `TransferExecutor.execute`), AND the consumer skips events whose payload `type` is `CASHBACK` (belt-and-suspenders).

---

## File Structure
```
src/main/resources/db/migration/V4__processed_events.sql   # NEW
src/main/resources/application.yml                          # MODIFY: kafka consumer props + reckon.consumers.enabled + rewards rate
src/main/kotlin/com/reckon/
├── consumer/
│   ├── ProcessedEventRepository.kt                         # NEW: markProcessed (ON CONFLICT DO NOTHING)
│   ├── PaymentEvent.kt                                     # NEW: parsed event DTO
│   ├── RewardsService.kt                                   # NEW: dedup + cashback (transactional)
│   ├── RewardsConsumer.kt                                  # NEW: @KafkaListener -> RewardsService
│   ├── NotificationsService.kt                             # NEW: dedup + (log) side-effect
│   └── NotificationsConsumer.kt                            # NEW: @KafkaListener -> NotificationsService
├── ledger/
│   ├── TransferExecutor.kt                                 # MODIFY: execute(type, emitEvent) param; payload uses real type
│   └── LedgerService.kt                                    # MODIFY: add recordCashback (joins caller's txn)
src/test/kotlin/com/reckon/consumer/
├── RewardsServiceTest.kt                                   # NEW: dedup -> exactly-once cashback (PostgresTestBase)
├── TwoConsumerDedupTest.kt                                 # NEW: rewards + notifications independent (PostgresTestBase)
└── ConsumerKafkaE2ETest.kt                                 # NEW: real Kafka produce -> cashback applied (KafkaPostgresTestBase)
```

---

## Task 1: processed_events migration
**Files:** Create `src/main/resources/db/migration/V4__processed_events.sql`
- [ ] **Step 1: Write**
```sql
CREATE TABLE processed_events (
    consumer_name text NOT NULL,
    event_id      uuid NOT NULL,
    processed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);
```
- [ ] **Step 2: Verify applies** — `./gradlew test --tests "com.reckon.support.HarnessTest"` → PASS.
- [ ] **Step 3: Commit** `git add -A && git commit -m "feat: processed_events table migration (V4)"`

---

## Task 2: ProcessedEventRepository + PaymentEvent DTO
**Files:** Create `src/main/kotlin/com/reckon/consumer/ProcessedEventRepository.kt`, `PaymentEvent.kt`
- [ ] **Step 1: `ProcessedEventRepository.kt`**
```kotlin
package com.reckon.consumer

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ProcessedEventRepository(private val jdbc: JdbcTemplate) {
    /** Returns true if THIS call claimed the event (first time); false if already processed. */
    fun markProcessed(consumer: String, eventId: UUID): Boolean =
        jdbc.update(
            "INSERT INTO processed_events(consumer_name, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            consumer, eventId,
        ) == 1
}
```
- [ ] **Step 2: `PaymentEvent.kt`** (Jackson-parsed; tolerate unknown fields)
```kotlin
package com.reckon.consumer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentEvent(
    val eventId: UUID,
    val transactionId: UUID,
    val type: String,
    val fromAccountId: UUID?,
    val toAccountId: UUID?,
    val amount: Long,
    val status: String,
)
```
- [ ] **Step 3: Verify compiles**; **Commit** `git add -A && git commit -m "feat: processed-event repo (ON CONFLICT dedup) + payment event dto"`

---

## Task 3: Parameterize TransferExecutor (emitEvent + real type in payload)
**Files:** Modify `src/main/kotlin/com/reckon/ledger/TransferExecutor.kt`, `LedgerService.kt`
- [ ] **Step 1: Change `execute` signature** to take the txn `type` and an `emitEvent` flag (default true), and build the payload with the real type:
```kotlin
@Transactional
fun execute(txnId: UUID, type: TxnType, from: UUID, to: UUID, amount: Long, emitEvent: Boolean = true) {
    val locked = accounts.lockByIdsInOrder(listOf(from, to)).associateBy { it.id }
    val src = locked[from] ?: error("source account not found: $from")
    if (src.type in setOf(AccountType.USER_WALLET, AccountType.MERCHANT) && src.balance < amount)
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "insufficient balance")
    ledger.insertEntry(LedgerEntry(txnId, from, Direction.DEBIT, amount))
    ledger.insertEntry(LedgerEntry(txnId, to, Direction.CREDIT, amount))
    accounts.applyDelta(from, -amount)
    accounts.applyDelta(to, amount)
    if (emitEvent) {
        val payload = """{"eventId":null,"transactionId":"$txnId","type":"${type.name}",""" +
            """"fromAccountId":"$from","toAccountId":"$to","amount":$amount,"status":"COMPLETED"}"""
        outbox.append(txnId, com.reckon.outbox.EventType.PAYMENT_COMPLETED, payload)
    }
    if (ledger.markCompletedIfPending(txnId) == 0)
        throw IllegalStateException("transaction $txnId no longer PENDING; aborting")
}
```
- [ ] **Step 2: Update the existing call site** in `LedgerService.recordTransfer`: `executor.execute(txnId, type, from, to, amount)` (emitEvent defaults true — P2P still emits). 
- [ ] **Step 3: Add `recordCashback` to `LedgerService`** — runs INSIDE the caller's (consumer's) transaction so dedup + cashback are atomic; emits NO event:
```kotlin
/** Record a CASHBACK transaction (REWARDS_POOL -> wallet). Must be called within the
 *  caller's transaction (e.g. the consumer's) so it commits atomically with the dedup mark.
 *  Emits no outbox event (prevents a cashback feedback loop). */
fun recordCashback(sourceEventId: UUID, toAccount: UUID, amount: Long) {
    val txnId = ledger.insertPending(
        TxnType.CASHBACK, "cashback:$sourceEventId", "-", amount,
        com.reckon.account.SystemAccounts.REWARDS_POOL, com.reckon.account.SystemAccounts.REWARDS_POOL, toAccount)
    executor.execute(txnId, TxnType.CASHBACK, com.reckon.account.SystemAccounts.REWARDS_POOL, toAccount, amount, emitEvent = false)
}
```
Note: `insertPending`'s signature is `(type, idempotencyKey, requestHash, amount, initiatorId, from, to)`. Here initiator = `REWARDS_POOL` (a non-null system id, so the `unique(initiator_id, idempotency_key)` constraint also guards against a duplicate cashback for the same source event — defense in depth beyond `processed_events`). `from = REWARDS_POOL`, `to = toAccount`.
- [ ] **Step 4: Verify compiles**; run `./gradlew test --tests "com.reckon.ledger.*" --tests "com.reckon.outbox.OutboxWriteTest"` → still green (P2P still emits its event). **Commit** `git add -A && git commit -m "feat: executor emitEvent flag + ledger recordCashback (no event, joins caller txn)"`

---

## Task 4: RewardsService (dedup + cashback)
**Files:** Create `src/main/kotlin/com/reckon/consumer/RewardsService.kt`; Modify `application.yml`
- [ ] **Step 1: `application.yml`** — add consumer config + rewards rate:
```yaml
spring:
  kafka:
    consumer:
      group-id: reckon
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
reckon:
  consumers:
    enabled: true        # listeners auto-start in prod; tests override to false unless they need Kafka
  rewards:
    cashback-bps: 100    # 100 basis points = 1%
```
(Merge into existing `spring:` / `reckon:` blocks.)
- [ ] **Step 2: `RewardsService.kt`**
```kotlin
package com.reckon.consumer

import com.reckon.ledger.LedgerService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RewardsService(
    private val processed: ProcessedEventRepository,
    private val ledger: LedgerService,
    @Value("\${reckon.rewards.cashback-bps}") private val cashbackBps: Long,
) {
    companion object { const val CONSUMER = "rewards" }

    /** Idempotent: dedup mark + cashback in ONE transaction. Redelivery is a no-op. */
    @Transactional
    fun award(event: PaymentEvent) {
        if (event.type == "CASHBACK") return                       // never cashback-on-cashback (loop guard)
        if (!processed.markProcessed(CONSUMER, event.eventId)) return  // already handled -> skip
        val payer = event.fromAccountId ?: return
        val cashback = event.amount * cashbackBps / 10_000          // bps of amount, integer paisa
        if (cashback <= 0) return
        ledger.recordCashback(event.eventId, payer, cashback)
    }
}
```
- [ ] **Step 3: Verify compiles**; **Commit** `git add -A && git commit -m "feat: rewards service (idempotent cashback via ledger)"`

---

## Task 5: RewardsService dedup test (the exactly-once proof)
**Files:** Create `src/test/kotlin/com/reckon/consumer/RewardsServiceTest.kt`
- [ ] **Step 1: Write** (extends `PostgresTestBase`; consumers disabled so no Kafka needed)
```kotlin
package com.reckon.consumer

import com.reckon.account.SystemAccounts
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals

@TestPropertySource(properties = ["reckon.consumers.enabled=false"])
class RewardsServiceTest : PostgresTestBase() {
    @Autowired lateinit var rewards: RewardsService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun event(payer: UUID, amount: Long, id: UUID = UUID.randomUUID(), type: String = "P2P") =
        PaymentEvent(id, UUID.randomUUID(), type, payer, UUID.randomUUID(), amount, "COMPLETED")

    @Test fun `cashback is applied once and is idempotent on redelivery`() {
        val payer = fixtures.walletWith(0)
        val rewardsPoolBefore = jdbc.queryForObject(
            "SELECT balance FROM accounts WHERE id = ?", Long::class.java, SystemAccounts.REWARDS_POOL)!!
        val e = event(payer, 20000)        // ₹200 -> 1% = 200 paisa cashback

        rewards.award(e)
        rewards.award(e)                   // redelivery (same eventId) -> must be a no-op
        rewards.award(e)                   // and again

        assertEquals(200, fixtures.balanceOf(payer))   // credited EXACTLY once
        val rewardsPoolAfter = jdbc.queryForObject(
            "SELECT balance FROM accounts WHERE id = ?", Long::class.java, SystemAccounts.REWARDS_POOL)!!
        assertEquals(rewardsPoolBefore - 200, rewardsPoolAfter)   // pool debited exactly once (may go negative)
        // exactly one CASHBACK transaction for this payer
        val cashbackTxns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE type='CASHBACK' AND to_account_id=?", Long::class.java, payer)
        assertEquals(1L, cashbackTxns)
    }

    @Test fun `cashback event type is skipped (no loop)`() {
        val payer = fixtures.walletWith(0)
        rewards.award(event(payer, 20000, type = "CASHBACK"))
        assertEquals(0, fixtures.balanceOf(payer))   // no cashback on a cashback event
    }
}
```
- [ ] **Step 2: Run → PASS** (`./gradlew test --tests "com.reckon.consumer.RewardsServiceTest"`). This is the exactly-once-effect proof: 3 deliveries → 1 cashback.
- [ ] **Step 3: Commit** `git add -A && git commit -m "test: rewards cashback exactly-once under redelivery"`

---

## Task 6: Rewards + Notifications consumers (@KafkaListener) + second-consumer dedup test
**Files:** Create `RewardsConsumer.kt`, `NotificationsService.kt`, `NotificationsConsumer.kt`, `TwoConsumerDedupTest.kt`
- [ ] **Step 1: `RewardsConsumer.kt`**
```kotlin
package com.reckon.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class RewardsConsumer(private val rewards: RewardsService, private val mapper: ObjectMapper) {
    @KafkaListener(
        topics = ["\${reckon.outbox.topic}"], groupId = "rewards",
        autoStartup = "\${reckon.consumers.enabled:true}")
    fun onMessage(payload: String) {
        rewards.award(mapper.readValue(payload, PaymentEvent::class.java))
    }
}
```
- [ ] **Step 2: `NotificationsService.kt`** (second independent consumer; side-effect = record only, demonstrating dedup with a different `consumer_name`)
```kotlin
package com.reckon.consumer

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationsService(private val processed: ProcessedEventRepository) {
    companion object { const val CONSUMER = "notifications" }
    /** Idempotent notification: dedup keyed by (notifications, eventId), independent of rewards. */
    @Transactional
    fun notify(event: PaymentEvent): Boolean {
        if (!processed.markProcessed(CONSUMER, event.eventId)) return false  // already notified
        // real side-effect would push a notification; here we just record it via the dedup row
        return true
    }
}
```
- [ ] **Step 3: `NotificationsConsumer.kt`**
```kotlin
package com.reckon.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class NotificationsConsumer(private val notifications: NotificationsService, private val mapper: ObjectMapper) {
    @KafkaListener(
        topics = ["\${reckon.outbox.topic}"], groupId = "notifications",
        autoStartup = "\${reckon.consumers.enabled:true}")
    fun onMessage(payload: String) {
        notifications.notify(mapper.readValue(payload, PaymentEvent::class.java))
    }
}
```
- [ ] **Step 4: `TwoConsumerDedupTest.kt`** — same event processed independently by both consumers, each once
```kotlin
package com.reckon.consumer

import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@TestPropertySource(properties = ["reckon.consumers.enabled=false"])
class TwoConsumerDedupTest : PostgresTestBase() {
    @Autowired lateinit var rewards: RewardsService
    @Autowired lateinit var notifications: NotificationsService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `rewards and notifications dedup the same event independently`() {
        val payer = fixtures.walletWith(0)
        val eventId = UUID.randomUUID()
        val e = PaymentEvent(eventId, UUID.randomUUID(), "P2P", payer, UUID.randomUUID(), 20000, "COMPLETED")

        rewards.award(e)
        assertTrue(notifications.notify(e))      // first notification succeeds
        assertFalse(notifications.notify(e))     // redelivery deduped
        rewards.award(e)                         // rewards redelivery deduped

        assertEquals(200, fixtures.balanceOf(payer))   // cashback once
        val rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Long::class.java, eventId)
        assertEquals(2L, rows)   // one row per consumer (rewards + notifications) for the SAME event
    }
}
```
- [ ] **Step 5: Run → PASS** (`./gradlew test --tests "com.reckon.consumer.TwoConsumerDedupTest" --tests "com.reckon.consumer.RewardsServiceTest"`).
- [ ] **Step 6: Commit** `git add -A && git commit -m "feat: rewards + notifications kafka consumers; test independent dedup per consumer"`

---

## Task 7: End-to-end Kafka test (real produce → cashback applied)
**Files:** Create `src/test/kotlin/com/reckon/consumer/ConsumerKafkaE2ETest.kt`
- [ ] **Step 1: Write** (extends `KafkaPostgresTestBase`; enables consumers; sends a real message and awaits the cashback)
```kotlin
package com.reckon.consumer

import com.reckon.support.Fixtures
import com.reckon.support.KafkaPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals

@TestPropertySource(properties = ["reckon.consumers.enabled=true"])
class ConsumerKafkaE2ETest : KafkaPostgresTestBase() {
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var rewards: RewardsService     // not used directly; ensures context wired
    @Value("\${reckon.outbox.topic}") lateinit var topic: String

    @Test fun `payment event published to kafka results in cashback via the consumer`() {
        val payer = fixtures.walletWith(0)
        val eventId = UUID.randomUUID()
        val payload = """{"eventId":"$eventId","transactionId":"${UUID.randomUUID()}","type":"P2P",""" +
            """"fromAccountId":"$payer","toAccountId":"${UUID.randomUUID()}","amount":50000,"status":"COMPLETED"}"""

        kafka.send(topic, payer.toString(), payload).get()

        // await async consumption: cashback = 1% of 50000 = 500 paisa
        var balance = 0L
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            balance = fixtures.balanceOf(payer)
            if (balance == 500L) break
            Thread.sleep(250)
        }
        assertEquals(500L, balance)   // consumer awarded cashback end-to-end
    }
}
```
Note: do NOT use `Date`/`System.nanoTime` for logic other than this poll deadline; `System.currentTimeMillis()` for a test timeout is fine.
- [ ] **Step 2: Run → PASS** (`./gradlew test --tests "com.reckon.consumer.ConsumerKafkaE2ETest"`). Uses the Kafka container; allow time.
- [ ] **Step 3: Run FULL suite** `./gradlew test` → all green (Plans 1–3 + new consumer tests). Confirm `reckon.consumers.enabled=false` is set wherever needed (PostgresTestBase should already disable the scheduler; ALSO disable consumers there so listeners don't try to start against a dead broker — add `reckon.consumers.enabled=false` to PostgresTestBase's `@TestPropertySource` alongside the scheduler one).
- [ ] **Step 4: Commit** `git add -A && git commit -m "test: end-to-end kafka payment event -> consumer cashback"`

---

## Done criteria (Plan 4)
- `./gradlew test` green: RewardsServiceTest (2), TwoConsumerDedupTest (1), ConsumerKafkaE2ETest (1), all prior tests pass.
- A redelivered `payment.completed` awards cashback exactly once (dedup + cashback in one transaction); cashback is a proper `CASHBACK` ledger transaction (no balance drift, `REWARDS_POOL` debited once, may go negative).
- Two consumers (rewards, notifications) dedup the same event independently via `(consumer_name, event_id)`.
- No cashback feedback loop (cashback emits no event; CASHBACK-typed events are skipped).
- **"At-least-once delivery + idempotent consumer = effectively-once"** — demonstrated end to end.

## What's next
- Plan 5: ADD_MONEY saga + simulated idempotent bank + compensation/recovery.
- Plan 6: reconciliation jobs (sum-to-zero, balance integrity, stuck-PENDING).
- Plan 7: k6 load test (incl. duplicate-key retries) + pessimistic-vs-optimistic benchmark.
