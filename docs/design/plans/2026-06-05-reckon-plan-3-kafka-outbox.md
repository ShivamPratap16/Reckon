# Reckon Plan 3 — Kafka Transactional Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Reliably publish a `payment.completed` event for every committed transfer, with no lost events even if Kafka is down — by writing the event to an `outbox` table inside the same DB transaction as the ledger write, then publishing it to Kafka with a polling publisher.

**Architecture:** Transactional outbox pattern. `TransferExecutor.execute` (already the atomic money-writer) additionally inserts an `outbox` row in the same transaction — so the event commits atomically with the money movement (no dual-write problem). A scheduled `OutboxPublisher` polls unpublished rows (`FOR UPDATE SKIP LOCKED`), publishes to Kafka keyed by `aggregate_id` (ordering per transaction), and marks them published. Delivery is **at-least-once** (a crash after send but before marking re-sends — consumers will dedup in Plan 4).

**Tech Stack:** Existing + `spring-kafka`, Testcontainers Kafka. Topic `payment-events`.

**Builds on:** Plan 2 (`com.reckon`, `TransferExecutor.execute`, `LedgerRepository`, 31 tests).

**Out of scope (later plans):** consumers / `processed_events` dedup (Plan 4), saga (Plan 5). This plan only *produces* events reliably.

---

## File Structure

```
src/main/resources/db/migration/V3__outbox.sql          # NEW
build.gradle.kts                                          # MODIFY: spring-kafka + testcontainers kafka
src/main/resources/application.yml                        # MODIFY: kafka + outbox props
src/main/kotlin/com/reckon/
├── platform/KafkaConfig.kt                               # NEW: topic bean
└── outbox/
    ├── OutboxRepository.kt                               # NEW: append, fetchUnpublished, markPublished, recordFailure
    ├── OutboxEvent.kt                                    # NEW: row type + event-type constants
    └── OutboxPublisher.kt                                # NEW: scheduled poller -> KafkaTemplate
src/main/kotlin/com/reckon/ledger/TransferExecutor.kt     # MODIFY: append outbox event in the txn
src/test/kotlin/com/reckon/support/
├── PostgresTestBase.kt                                   # (unchanged)
└── KafkaPostgresTestBase.kt                              # NEW: Postgres + Kafka singletons for publisher tests
src/test/kotlin/com/reckon/outbox/
├── OutboxWriteTest.kt                                    # NEW: event written in txn, rolled back on failure (no Kafka)
└── OutboxPublisherTest.kt                                # NEW: publishes to Kafka + marks published (Kafka)
```

---

## Task 1: Outbox migration

**Files:** Create `src/main/resources/db/migration/V3__outbox.sql`

- [ ] **Step 1: Write migration**
```sql
CREATE TABLE outbox (
    id           bigserial PRIMARY KEY,
    event_id     uuid NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id uuid NOT NULL,
    event_type   text NOT NULL,
    payload      jsonb NOT NULL,
    published    boolean NOT NULL DEFAULT false,
    attempts     int NOT NULL DEFAULT 0,
    last_error   text NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz NULL
);
-- partial index so the poller never full-scans as the table grows:
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published = false;
```
- [ ] **Step 2: Verify it applies** — run any existing Testcontainers test, e.g. `./gradlew test --tests "com.reckon.support.HarnessTest"` → PASS (Flyway applies V3).
- [ ] **Step 3: Commit** `git add -A && git commit -m "feat: outbox table migration (V3)"`

---

## Task 2: Kafka + Testcontainers dependencies

**Files:** Modify `build.gradle.kts`

- [ ] **Step 1: Add dependencies** (keep existing block; add these lines)
```kotlin
implementation("org.springframework.kafka:spring-kafka")
testImplementation("org.testcontainers:kafka:1.20.4")
testImplementation("org.springframework.kafka:spring-kafka-test")
```
- [ ] **Step 2: Verify resolve/compile** — `./gradlew compileKotlin` → BUILD SUCCESSFUL (spring-kafka version is managed by the Spring Boot BOM).
- [ ] **Step 3: Commit** `git add -A && git commit -m "build: add spring-kafka + testcontainers kafka"`

---

## Task 3: Kafka config + app properties

**Files:** Create `src/main/kotlin/com/reckon/platform/KafkaConfig.kt`; Modify `application.yml`

- [ ] **Step 1: `application.yml`** — add under root:
```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
reckon:
  outbox:
    topic: payment-events
    scheduler:
      enabled: true       # disabled in tests so the poller doesn't fire unexpectedly
    batch-size: 100
```
(Merge these into the existing `spring:` / `reckon:` blocks rather than duplicating the keys.)

- [ ] **Step 2: `KafkaConfig.kt`** — declare the topic so it's auto-created in dev:
```kotlin
package com.reckon.platform

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaConfig(@Value("\${reckon.outbox.topic}") private val topic: String) {
    @Bean fun paymentEventsTopic(): NewTopic = TopicBuilder.name(topic).partitions(3).replicas(1).build()
}
```
- [ ] **Step 3: Verify compiles** — `./gradlew compileKotlin`.
- [ ] **Step 4: Commit** `git add -A && git commit -m "feat: kafka producer config + outbox topic + props"`

---

## Task 4: Outbox event type + repository

**Files:** Create `src/main/kotlin/com/reckon/outbox/OutboxEvent.kt`, `OutboxRepository.kt`

- [ ] **Step 1: `OutboxEvent.kt`**
```kotlin
package com.reckon.outbox

import java.util.UUID

object EventType { const val PAYMENT_COMPLETED = "payment.completed" }

data class OutboxRow(
    val id: Long,
    val eventId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
)
```
- [ ] **Step 2: `OutboxRepository.kt`**
```kotlin
package com.reckon.outbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class OutboxRepository(private val jdbc: JdbcTemplate) {

    /** Append an event. MUST be called inside the caller's transaction (e.g. the transfer txn). */
    fun append(aggregateId: UUID, eventType: String, payloadJson: String) {
        jdbc.update(
            "INSERT INTO outbox(aggregate_id, event_type, payload) VALUES (?, ?, ?::jsonb)",
            aggregateId, eventType, payloadJson,
        )
    }

    /** Claim a batch of unpublished rows, skipping rows locked by another publisher instance. */
    fun fetchUnpublished(limit: Int): List<OutboxRow> = jdbc.query(
        """SELECT id, event_id, aggregate_id, event_type, payload
           FROM outbox WHERE published = false
           ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
        { rs, _ -> OutboxRow(
            rs.getLong("id"),
            rs.getObject("event_id", UUID::class.java),
            rs.getObject("aggregate_id", UUID::class.java),
            rs.getString("event_type"),
            rs.getString("payload"),
        ) },
        limit,
    )

    fun markPublished(id: Long) = jdbc.update(
        "UPDATE outbox SET published = true, published_at = now() WHERE id = ?", id,
    )

    fun recordFailure(id: Long, error: String) = jdbc.update(
        "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?", error.take(500), id,
    )
}
```
- [ ] **Step 3: Verify compiles**; **Commit** `git add -A && git commit -m "feat: outbox event type + repository (append, fetch SKIP LOCKED, mark, fail)"`

---

## Task 5: Write the event inside the transfer transaction

**Files:** Modify `src/main/kotlin/com/reckon/ledger/TransferExecutor.kt`

- [ ] **Step 1: Inject `OutboxRepository` and append the event before the status flip**

In `TransferExecutor`, add `private val outbox: com.reckon.outbox.OutboxRepository` to the constructor. Inside `execute(...)`, AFTER the two `insertEntry` + `applyDelta` calls and BEFORE/with the `markCompletedIfPending` guard, append the event (same transaction):
```kotlin
val payload = """{"eventId":null,"transactionId":"$txnId","type":"P2P",""" +
    """"fromAccountId":"$from","toAccountId":"$to","amount":$amount,"status":"COMPLETED"}"""
outbox.append(txnId, com.reckon.outbox.EventType.PAYMENT_COMPLETED, payload)
if (ledger.markCompletedIfPending(txnId) == 0) {
    throw IllegalStateException("transaction $txnId no longer PENDING; aborting")
}
```
Key property: because `outbox.append` runs in the same `@Transactional execute`, the event row is committed atomically with the ledger entries — and if the status-flip guard throws, the event is rolled back too. (The `eventId` in the JSON payload is a placeholder; the authoritative dedup id is the `outbox.event_id` column, carried in the Kafka envelope in Task 6.)

- [ ] **Step 2: Verify compiles** (`./gradlew compileKotlin`).
- [ ] **Step 3: Commit** `git add -A && git commit -m "feat: append payment.completed to outbox within transfer transaction"`

---

## Task 6: Outbox publisher (scheduled poller → Kafka)

**Files:** Create `src/main/kotlin/com/reckon/outbox/OutboxPublisher.kt`

- [ ] **Step 1: Implement**
```kotlin
package com.reckon.outbox

import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OutboxPublisher(
    private val outbox: OutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    @Value("\${reckon.outbox.topic}") private val topic: String,
    @Value("\${reckon.outbox.batch-size}") private val batchSize: Int,
) {
    /**
     * Claim a batch of unpublished events and publish them. Runs in a transaction so the
     * FOR UPDATE SKIP LOCKED row locks are held while publishing (prevents two instances
     * double-claiming). At-least-once: a crash after send() but before markPublished re-sends.
     * Kafka key = aggregateId so all events for one transaction land on one partition, in order.
     */
    @Transactional
    fun publishBatch(): Int {
        val rows = outbox.fetchUnpublished(batchSize)
        for (row in rows) {
            try {
                kafka.send(topic, row.aggregateId.toString(), envelope(row)).get()
                outbox.markPublished(row.id)
            } catch (e: Exception) {
                outbox.recordFailure(row.id, e.message ?: e.javaClass.simpleName)
            }
        }
        return rows.size
    }

    /** Wrap the stored payload with the authoritative event_id (consumer dedup key in Plan 4). */
    private fun envelope(row: OutboxRow): String =
        row.payload.replaceFirst("\"eventId\":null", "\"eventId\":\"${row.eventId}\"")

    @Scheduled(fixedDelayString = "\${reckon.outbox.poll-ms:1000}")
    fun scheduledPublish() {
        if (schedulerEnabled) publishBatch()
    }

    @Value("\${reckon.outbox.scheduler.enabled:true}")
    private var schedulerEnabled: Boolean = true
}
```
Note: `.get()` on `kafka.send(...)` makes the publish synchronous so we only `markPublished` after the broker acks. In tests we set `reckon.outbox.scheduler.enabled=false` and call `publishBatch()` directly. Add `reckon.outbox.poll-ms` default via the `:1000` inline default (no yaml change needed).

- [ ] **Step 2: Verify compiles**; **Commit** `git add -A && git commit -m "feat: scheduled outbox publisher (SKIP LOCKED batch -> kafka, at-least-once)"`

---

## Task 7: Test — event written transactionally (no Kafka needed)

**Files:** Create `src/test/kotlin/com/reckon/outbox/OutboxWriteTest.kt`

- [ ] **Step 1: Write test** (extends `PostgresTestBase`; disables scheduler via property so the poller doesn't fire)
```kotlin
package com.reckon.outbox

import com.reckon.ledger.LedgerService
import com.reckon.ledger.TxnType
import com.reckon.platform.RequestHash
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
        Long::class.java, txnId)

    @Test fun `successful transfer writes exactly one outbox event in the same transaction`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val txn = ledger.recordTransfer(TxnType.P2P, "ob-1", RequestHash.of("P2P", a, b, 20000),
            UUID.randomUUID(), a, b, 20000).transactionId
        assertEquals(1L, outboxCountFor(txn))
    }

    @Test fun `failed transfer writes no outbox event (rolled back with the txn)`() {
        val a = fixtures.walletWith(100); val b = fixtures.walletWith(0)
        val idem = "ob-2"
        assertThrows<com.reckon.platform.ApiException> {
            ledger.recordTransfer(TxnType.P2P, idem, RequestHash.of("P2P", a, b, 99999),
                UUID.randomUUID(), a, b, 99999)
        }
        val cnt = jdbc.queryForObject(
            """SELECT COUNT(*) FROM outbox o JOIN transactions t ON t.id = o.aggregate_id
               WHERE t.idempotency_key = ?""", Long::class.java, idem)
        assertEquals(0L, cnt)
    }
}
```
- [ ] **Step 2: Run → PASS** (`./gradlew test --tests "com.reckon.outbox.OutboxWriteTest"`). This proves the outbox insert is atomic with the transfer (written on success, rolled back on failure).
- [ ] **Step 3: Commit** `git add -A && git commit -m "test: outbox event written transactionally, rolled back on failed transfer"`

---

## Task 8: Test — publisher delivers to Kafka and marks published

**Files:** Create `src/test/kotlin/com/reckon/support/KafkaPostgresTestBase.kt`, `src/test/kotlin/com/reckon/outbox/OutboxPublisherTest.kt`

- [ ] **Step 1: `KafkaPostgresTestBase.kt`** — singleton Postgres + Kafka, scheduler disabled so we drive `publishBatch()` manually
```kotlin
package com.reckon.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest
@TestPropertySource(properties = ["reckon.outbox.scheduler.enabled=false"])
abstract class KafkaPostgresTestBase {
    companion object {
        @JvmStatic val pg = PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("reckon"); withUsername("reckon"); withPassword("reckon"); start()
        }
        @JvmStatic val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1")).apply { start() }

        @JvmStatic @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", pg::getJdbcUrl)
            r.add("spring.datasource.username", pg::getUsername)
            r.add("spring.datasource.password", pg::getPassword)
            r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }
}
```
(Singleton pattern — containers started in the companion `apply { start() }`, reused across the JVM, never stopped; Ryuk cleans them up. Mirrors the existing `PostgresTestBase` singleton approach.)

- [ ] **Step 2: `OutboxPublisherTest.kt`**
```kotlin
package com.reckon.outbox

import com.reckon.support.KafkaPostgresTestBase
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.utils.KafkaTestUtils
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboxPublisherTest : KafkaPostgresTestBase() {
    @Autowired lateinit var publisher: OutboxPublisher
    @Autowired lateinit var outbox: OutboxRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Value("\${spring.kafka.bootstrap-servers}") lateinit var bootstrap: String
    @Value("\${reckon.outbox.topic}") lateinit var topic: String

    @Test fun `publishBatch sends unpublished events to kafka and marks them published`() {
        val aggregate = UUID.randomUUID()
        outbox.append(aggregate, EventType.PAYMENT_COMPLETED,
            """{"eventId":null,"transactionId":"$aggregate","status":"COMPLETED"}""")

        // consumer subscribed before publish
        val props = KafkaTestUtils.consumerProps(bootstrap, "test-group", "true")
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        val consumer = DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer())
            .createConsumer()
        consumer.subscribe(listOf(topic))

        val published = publisher.publishBatch()
        assertEquals(1, published)

        val records = consumer.poll(Duration.ofSeconds(10))
        consumer.close()
        assertEquals(1, records.count())
        val record = records.iterator().next()
        assertEquals(aggregate.toString(), record.key())                 // partition key = aggregateId
        assertTrue(record.value().contains("\"eventId\":\""))            // envelope filled real eventId (not null)

        // row marked published, won't be re-sent
        val unpublished = jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND published = false", Long::class.java, aggregate)
        assertEquals(0L, unpublished)
        assertEquals(0, publisher.publishBatch())                        // nothing left to publish
    }
}
```
- [ ] **Step 3: Run → PASS** (`./gradlew test --tests "com.reckon.outbox.OutboxPublisherTest"`). First run pulls the Kafka image — allow time.
- [ ] **Step 4: Run FULL suite** `./gradlew test` → all green (Plan 1+2 tests + new outbox tests). Confirm the scheduler being enabled-by-default doesn't break other tests (it's disabled in the two outbox test classes; for the rest the producer is lazy and the poller finds nothing or is harmless — if any non-outbox test flakes due to the scheduler, add `reckon.outbox.scheduler.enabled=false` to `PostgresTestBase` via `@TestPropertySource`).
- [ ] **Step 5: Commit** `git add -A && git commit -m "test: outbox publisher delivers to kafka (keyed by aggregate) and marks published"`

---

## Done criteria (Plan 3)

- `./gradlew test` green: outbox written transactionally (2), publisher → Kafka + mark (1), all prior tests pass.
- Every committed transfer leaves exactly one `payment.completed` outbox row, atomic with the ledger write (rolled back if the transfer fails).
- The polling publisher delivers unpublished events to Kafka keyed by `aggregate_id`, marks them published (no re-send), uses `FOR UPDATE SKIP LOCKED`, and records failures with `attempts`/`last_error`.
- Delivery is at-least-once by design (documented); consumer dedup arrives in Plan 4.

## What's next
- Plan 4: idempotent consumers — rewards `CASHBACK` (via ledger API, its own transaction) + notifications, with `processed_events(consumer_name, event_id)` dedup → exactly-once *effect*.
- Plan 5: ADD_MONEY saga + simulated idempotent bank + recovery.
