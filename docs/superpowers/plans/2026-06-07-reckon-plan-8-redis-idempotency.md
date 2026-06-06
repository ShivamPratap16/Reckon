# Reckon Plan 8 — Redis Idempotency Fast-Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add Redis as a **fast-path cache** in front of idempotency replay: a repeated request whose result is already known is served from Redis without touching Postgres. Postgres' `unique(initiator_id, idempotency_key)` constraint REMAINS the authoritative race guard — Redis is a best-effort accelerator that the system degrades gracefully without (if Redis is down, everything still works via the DB).

**Architecture:** On a money request, check the cache for a cached **terminal** result (COMPLETED/FAILED) keyed by `(initiator, idempotency_key)`. Cache hit + matching request hash → return the replay immediately (no DB). Cache miss → the existing DB-authoritative path runs (insert → on `DuplicateKeyException`, DB replay), and the terminal result is then written to the cache. Only immutable terminal results are cached (never in-flight PENDING), so a stale cache can never cause a wrong answer. All Redis calls are wrapped to degrade to DB-only on any Redis error.

**Tech Stack:** Existing + `spring-boot-starter-data-redis` (Lettuce), Redis Testcontainer. Redis is OPTIONAL at runtime (gated by `reckon.idempotency.cache.enabled`, graceful degradation).

**Builds on:** Plans 1–7 (`com.reckon`, `LedgerService.recordTransfer` with DB-based 4-way replay, 60 tests).

**Scope:** Wire the cache into `LedgerService.recordTransfer` (the P2P/merchant idempotency path). `AddMoneyService` keeps DB-only replay (it can reuse this cache later; noted, not required).

---

## Correctness invariant (do not violate)
Redis is NEVER the source of truth. The DB unique constraint is the only thing that prevents a double-debit under a race. The cache only short-circuits *terminal* replays. If the cache is empty, stale-but-only-for-terminal-results, or unavailable, correctness is unaffected — only speed changes.

---

## File Structure
```
build.gradle.kts                                          # MODIFY: spring-data-redis + testcontainers
docker-compose.yml                                        # MODIFY: redis service
src/main/resources/application.yml                        # MODIFY: spring.data.redis + reckon.idempotency.cache.{enabled,ttl-hours}
src/main/kotlin/com/reckon/idempotency/
├── IdempotencyCache.kt                                   # NEW: cache abstraction + cached-result type
└── RedisIdempotencyCache.kt                              # NEW: Redis impl, enabled-flag + graceful degradation
src/main/kotlin/com/reckon/ledger/LedgerService.kt        # MODIFY: consult + populate the cache
src/test/kotlin/com/reckon/support/RedisPostgresTestBase.kt   # NEW: singleton Postgres + Redis
src/test/kotlin/com/reckon/idempotency/IdempotencyCacheTest.kt # NEW: fast-path replay; no double-debit; degradation
```

---

## Task 1: Dependencies + config + compose
**Files:** Modify `build.gradle.kts`, `application.yml`, `docker-compose.yml`
- [ ] **Step 1: build.gradle.kts** — add:
```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-redis")
testImplementation("com.redis:testcontainers-redis:2.2.2")
```
If `com.redis:testcontainers-redis` fails to resolve, instead use a `GenericContainer` from the core testcontainers lib (already present) in the test base — see Task 4 (it shows the GenericContainer approach as the default; the `com.redis` lib is optional sugar). Prefer the GenericContainer approach to avoid a new dependency that might not resolve.
- [ ] **Step 2: application.yml** — add:
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 200ms
      lettuce:
        shutdown-timeout: 100ms
reckon:
  idempotency:
    cache:
      enabled: true       # tests without Redis set false; the Redis test sets true
      ttl-hours: 24
```
(Merge into existing blocks.)
- [ ] **Step 3: docker-compose.yml** — add:
```yaml
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
```
- [ ] **Step 4: PostgresTestBase** — add `reckon.idempotency.cache.enabled=false` to its `@TestPropertySource` (so the 60 existing tests don't try to reach a non-existent Redis; they validate the DB-authoritative path). Verify `./gradlew compileKotlin`. **Commit** `git add -A && git commit -m "build: spring-data-redis + redis compose + idempotency cache config"`

---

## Task 2: IdempotencyCache abstraction + Redis impl
**Files:** Create `src/main/kotlin/com/reckon/idempotency/IdempotencyCache.kt`, `RedisIdempotencyCache.kt`
- [ ] **Step 1: `IdempotencyCache.kt`**
```kotlin
package com.reckon.idempotency

import java.util.UUID

/** A cached TERMINAL idempotency result (COMPLETED or FAILED). Never caches in-flight PENDING. */
data class CachedResult(val transactionId: UUID, val status: String, val requestHash: String, val failureCode: String?)

interface IdempotencyCache {
    fun get(initiatorId: UUID, idempotencyKey: String): CachedResult?
    fun put(initiatorId: UUID, idempotencyKey: String, result: CachedResult)
}
```
- [ ] **Step 2: `RedisIdempotencyCache.kt`** — enabled-flag + graceful degradation (any Redis failure → behave as cache miss / no-op, never throw)
```kotlin
package com.reckon.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class RedisIdempotencyCache(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    @Value("\${reckon.idempotency.cache.enabled:true}") private val enabled: Boolean,
    @Value("\${reckon.idempotency.cache.ttl-hours:24}") private val ttlHours: Long,
) : IdempotencyCache {
    private val log = LoggerFactory.getLogger(javaClass)
    private fun key(initiatorId: UUID, k: String) = "idem:$initiatorId:$k"

    override fun get(initiatorId: UUID, idempotencyKey: String): CachedResult? {
        if (!enabled) return null
        return try {
            redis.opsForValue().get(key(initiatorId, idempotencyKey))?.let { mapper.readValue(it, CachedResult::class.java) }
        } catch (e: Exception) { log.warn("idempotency cache GET degraded: {}", e.message); null }  // DB remains source of truth
    }

    override fun put(initiatorId: UUID, idempotencyKey: String, result: CachedResult) {
        if (!enabled) return
        try {
            redis.opsForValue().set(key(initiatorId, idempotencyKey), mapper.writeValueAsString(result), Duration.ofHours(ttlHours))
        } catch (e: Exception) { log.warn("idempotency cache PUT degraded: {}", e.message) }  // best-effort
    }
}
```
- [ ] **Step 3: Verify compiles**; **Commit** `git add -A && git commit -m "feat: idempotency cache abstraction + redis impl (graceful degradation)"`

---

## Task 3: Wire cache into LedgerService.recordTransfer
**Files:** Modify `src/main/kotlin/com/reckon/ledger/LedgerService.kt`
- [ ] **Step 1: Inject `IdempotencyCache` and add the fast path + populate.** The fast path runs BEFORE `insertPending`; on a cached terminal result with matching hash, return/throw the replay without touching the DB. After reaching a terminal state (COMPLETED or FAILED), populate the cache. The DB `DuplicateKeyException` replay path ALSO populates the cache from the DB row.

Concretely, modify `recordTransfer`:
```kotlin
// constructor: add `private val cache: com.reckon.idempotency.IdempotencyCache`

fun recordTransfer(type: TxnType, idempotencyKey: String, requestHash: String,
                   initiatorId: UUID, from: UUID, to: UUID, amount: Long): TransferOutcome {
    if (amount <= 0) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "amount must be positive")
    if (from == to) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "cannot transfer to self")

    // FAST PATH: terminal result already cached?
    cache.get(initiatorId, idempotencyKey)?.let { cached ->
        if (cached.requestHash != requestHash)
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSE", "key reused with different request")
        return when (cached.status) {
            "COMPLETED" -> TransferOutcome(cached.transactionId, "COMPLETED", replayed = true)
            "FAILED"    -> throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, cached.failureCode ?: "FAILED", "replayed prior failure")
            else        -> TransferOutcome(cached.transactionId, cached.status, replayed = true)
        }
    }

    val txnId = try {
        ledger.insertPending(type, idempotencyKey, requestHash, amount, initiatorId, from, to)
    } catch (e: DuplicateKeyException) {
        return replay(initiatorId, idempotencyKey, requestHash)   // DB-authoritative; replay() also warms the cache (Step 2)
    }

    try {
        executor.execute(txnId, type, from, to, amount)
    } catch (e: Exception) {
        val reason = (e as? ApiException)?.code ?: "EXECUTION_ERROR"
        statusWriter.failInOwnTxn(txnId, reason)
        val code = (e as? ApiException)?.status?.value() ?: 500
        ledger.storeResponse(txnId, code, """{"transactionId":"$txnId","status":"FAILED","code":"$reason"}""")
        cache.put(initiatorId, idempotencyKey, com.reckon.idempotency.CachedResult(txnId, "FAILED", requestHash, reason))
        throw e
    }
    ledger.storeResponse(txnId, 200, """{"transactionId":"$txnId","status":"COMPLETED"}""")
    cache.put(initiatorId, idempotencyKey, com.reckon.idempotency.CachedResult(txnId, "COMPLETED", requestHash, null))
    return TransferOutcome(txnId, "COMPLETED", replayed = false)
}
```
- [ ] **Step 2: Warm the cache from the DB replay path** — in `replay(...)`, before returning/throwing, also `cache.put(...)` the terminal result so subsequent replays are fast:
```kotlin
private fun replay(initiatorId: UUID, idempotencyKey: String, requestHash: String): TransferOutcome {
    val existing = ledger.findByInitiatorAndKey(initiatorId, idempotencyKey)
        ?: throw ApiException(HttpStatus.CONFLICT, "IN_PROGRESS", "duplicate key, original not yet visible")
    if (existing.requestHash != requestHash)
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSE", "key reused with different request")
    return when (existing.status) {
        "COMPLETED" -> { cache.put(initiatorId, idempotencyKey, com.reckon.idempotency.CachedResult(existing.id, "COMPLETED", requestHash, null))
                         TransferOutcome(existing.id, "COMPLETED", replayed = true) }
        "FAILED"    -> { cache.put(initiatorId, idempotencyKey, com.reckon.idempotency.CachedResult(existing.id, "FAILED", requestHash, existing.failureReason))
                         throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, existing.failureReason ?: "FAILED", "replayed prior failure") }
        else        -> TransferOutcome(existing.id, "PENDING", replayed = true)   // in-flight: NOT cached
    }
}
```
- [ ] **Step 3: Verify compiles**; run `./gradlew test --tests "com.reckon.ledger.*" --tests "com.reckon.consumer.*"` → still green (cache disabled in PostgresTestBase → `get` returns null, `put` no-ops, DB path unchanged). **Commit** `git add -A && git commit -m "feat: redis fast-path in recordTransfer (DB remains source of truth)"`

---

## Task 4: Redis test base + fast-path tests
**Files:** Create `src/test/kotlin/com/reckon/support/RedisPostgresTestBase.kt`, `src/test/kotlin/com/reckon/idempotency/IdempotencyCacheTest.kt`
- [ ] **Step 1: `RedisPostgresTestBase.kt`** — singleton Postgres + Redis (GenericContainer), cache ENABLED
```kotlin
package com.reckon.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
@TestPropertySource(properties = [
    "reckon.outbox.scheduler.enabled=false", "reckon.consumers.enabled=false",
    "reckon.saga.recovery.enabled=false", "reckon.reconciliation.enabled=false",
    "reckon.idempotency.cache.enabled=true",
])
abstract class RedisPostgresTestBase {
    companion object {
        @JvmStatic val pg = PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("reckon"); withUsername("reckon"); withPassword("reckon"); start()
        }
        @JvmStatic val redis = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379); start() }

        @JvmStatic @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", pg::getJdbcUrl)
            r.add("spring.datasource.username", pg::getUsername)
            r.add("spring.datasource.password", pg::getPassword)
            r.add("spring.data.redis.host", redis::getHost)
            r.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }
}
```
- [ ] **Step 2: `IdempotencyCacheTest.kt`**
```kotlin
package com.reckon.idempotency

import com.reckon.ledger.LedgerService
import com.reckon.ledger.TxnType
import com.reckon.platform.ApiException
import com.reckon.platform.RequestHash
import com.reckon.support.Fixtures
import com.reckon.support.RedisPostgresTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdempotencyCacheTest : RedisPostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var redis: StringRedisTemplate

    private fun transfer(key: String, from: UUID, to: UUID, amt: Long, initiator: UUID) =
        ledger.recordTransfer(TxnType.P2P, key, RequestHash.of("P2P", from, to, amt), initiator, from, to, amt)

    @Test fun `completed result is cached and a retry replays from cache without double-debit`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        val first = transfer("redis-1", a, b, 20000, initiator)
        // cache now holds the terminal result
        assertNotNull(redis.opsForValue().get("idem:$initiator:redis-1"), "expected cached entry")
        val second = transfer("redis-1", a, b, 20000, initiator)   // served from cache
        assertEquals(first.transactionId, second.transactionId)
        assertFalse(first.replayed); assertTrue(second.replayed)
        assertEquals(30000, fixtures.balanceOf(a))   // debited ONCE
        assertEquals(20000, fixtures.balanceOf(b))
    }

    @Test fun `cached key reused with a different request is rejected 422`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        transfer("redis-2", a, b, 20000, initiator)
        val ex = assertThrows<ApiException> { transfer("redis-2", a, b, 99999, initiator) }   // different amount -> different hash
        assertEquals("IDEMPOTENCY_KEY_REUSE", ex.code)
    }

    @Test fun `a fresh key under the same initiator is not falsely served from cache`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val initiator = UUID.randomUUID()
        transfer("redis-3a", a, b, 10000, initiator)
        val other = transfer("redis-3b", a, b, 10000, initiator)   // different key -> cache miss -> real transfer
        assertFalse(other.replayed)
        assertEquals(30000, fixtures.balanceOf(a))   // two distinct debits of 10000
    }
}
```
- [ ] **Step 3: Run → PASS** (`./gradlew test --tests "com.reckon.idempotency.IdempotencyCacheTest"`). First run pulls `redis:7-alpine`.
- [ ] **Step 4: Run FULL suite** `./gradlew test` → all green (63 tests: 60 prior + 3 new). Confirm the DB-disabled-cache tests (PostgresTestBase, cache.enabled=false) still pass — proving graceful degradation (idempotency works WITHOUT Redis).
- [ ] **Step 5: Commit** `git add -A && git commit -m "test: redis idempotency fast-path (cached replay, key-reuse, no double-debit) + degradation"`

---

## Task 5: README + tools coherence
**Files:** Modify `README.md`
- [ ] **Step 1:** Add a short note to the README: idempotency is DB-authoritative (`unique(initiator_id, idempotency_key)`) with a **Redis fast-path cache** for terminal-result replays that **degrades gracefully** (Redis down → DB-only, correctness unchanged). Add Redis to the tech-stack line. Note the cache only stores immutable terminal results, so it can never serve a wrong answer.
- [ ] **Step 2: Run FULL suite** `./gradlew test` → green.
- [ ] **Step 3: Commit** `git add -A && git commit -m "docs: document redis idempotency fast-path + degradation"`

---

## Done criteria (Plan 8)
- `./gradlew test` green: IdempotencyCacheTest (3) + all 60 prior tests pass.
- A completed transfer's result is cached in Redis; a retry replays from the cache (no DB insert) with NO double-debit; a cached key reused with a different request → 422.
- The DB `unique` constraint remains the authoritative race guard; with the cache disabled or Redis unavailable, idempotency still works (graceful degradation, proven by the 60 cache-disabled tests).
- README + tools list now truthfully include Redis (as a fast-path, not the source of truth).
