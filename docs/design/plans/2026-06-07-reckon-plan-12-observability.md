# Reckon Plan 12 — Observability (Metrics, Tracing, Correlation IDs) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make Reckon observable like a production service: custom business **metrics** (Prometheus), **distributed tracing** (OpenTelemetry via Micrometer Tracing) with a span around money operations, and a **correlation/request ID** threaded through logs. Ship a runnable Prometheus + Grafana stack with a pre-built dashboard.

**What is test-verifiable vs. runnable artifact (be honest):**
- **Unit-testable (asserted in `./gradlew test`):** custom metrics appear and increment on `/actuator/prometheus`; the correlation-ID filter sets MDC + echoes a response header; an OTel span is recorded around a transfer (via an in-memory span exporter in the test).
- **Runnable artifacts (not unit-tested):** the Prometheus scrape config, the Grafana dashboard JSON, and the docker-compose to run them. The README documents how to run the stack and what the dashboard shows; a screenshot can be added after running locally.

**Tech Stack:** Existing + `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp` (runtime), and `io.opentelemetry:opentelemetry-sdk-testing` (test). Actuator already present.

**Builds on:** Plans 1–11 (`com.reckon`, 77 tests). Touches `LedgerService` (instrumentation) — reviewed carefully.

---

## File Structure
```
build.gradle.kts                                              # MODIFY: metrics + tracing deps
src/main/resources/application.yml                            # MODIFY: management metrics/tracing/prometheus exposure
src/main/kotlin/com/reckon/
├── platform/CorrelationIdFilter.kt                           # NEW: request-id -> MDC + response header
├── platform/Metrics.kt                                       # NEW: typed metric names/helpers (counters, timer)
└── ledger/LedgerService.kt                                   # MODIFY: count + time transfers; @Observed span
src/main/resources/logback-spring.xml                         # NEW: pattern incl. traceId + correlationId
observability/
├── docker-compose.observability.yml                          # NEW: prometheus + grafana
├── prometheus.yml                                            # NEW: scrape /actuator/prometheus
├── grafana-datasource.yml                                    # NEW: prometheus datasource provisioning
└── grafana-dashboard-reckon.json                             # NEW: TPS, latency p95/p99, outbox lag, recon, cashback
src/test/kotlin/com/reckon/observability/
├── MetricsTest.kt                                            # NEW: transfer counter/timer on /actuator/prometheus
├── CorrelationIdFilterTest.kt                                # NEW: header echoed + MDC set
└── TracingTest.kt                                            # NEW: a span is recorded around a transfer
```

---

## Task 1: Dependencies + management config
**Files:** Modify `build.gradle.kts`, `application.yml`
- [ ] **Step 1: build.gradle.kts** — add:
```kotlin
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("io.micrometer:micrometer-tracing-bridge-otel")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
```
(Versions managed by the Spring Boot BOM where possible; if `opentelemetry-sdk-testing` needs a version, use `1.43.0`.)
- [ ] **Step 2: application.yml** — expose metrics + prometheus, enable tracing (sample everything in dev), and OTLP endpoint (off by default unless a collector is set):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics    # (was: health)
  metrics:
    tags:
      application: reckon
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:}   # empty => no export (tests/local don't need a collector)
```
Note: `/actuator/prometheus` must be PUBLIC for scraping — in `SecurityConfig`, permit `/actuator/health` AND `/actuator/prometheus` (Task tip: change `requestMatchers("/actuator/health")` to `requestMatchers("/actuator/health","/actuator/prometheus")`).
- [ ] **Step 3: Verify compiles** (`./gradlew compileKotlin`); **Commit** `git add -A && git commit -m "build: micrometer prometheus + otel tracing deps + management exposure"`

---

## Task 2: Correlation ID filter
**Files:** Create `src/main/kotlin/com/reckon/platform/CorrelationIdFilter.kt`
- [ ] **Step 1: Implement** — read `X-Correlation-Id` (or generate one), put in MDC, echo on the response, clear in finally. Ordered before security.
```kotlin
package com.reckon.platform

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

const val CORRELATION_ID_HEADER = "X-Correlation-Id"
const val CORRELATION_ID_MDC = "correlationId"

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val correlationId = req.getHeader(CORRELATION_ID_HEADER)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        MDC.put(CORRELATION_ID_MDC, correlationId)
        res.setHeader(CORRELATION_ID_HEADER, correlationId)
        try { chain.doFilter(req, res) } finally { MDC.remove(CORRELATION_ID_MDC) }
    }
}
```
- [ ] **Step 2: Verify compiles**; **Commit** `git add -A && git commit -m "feat: correlation-id filter (MDC + response header)"`

---

## Task 3: Business metrics + transfer span
**Files:** Create `src/main/kotlin/com/reckon/platform/Metrics.kt`; Modify `src/main/kotlin/com/reckon/ledger/LedgerService.kt`
- [ ] **Step 1: `Metrics.kt`** — a small component wrapping a `MeterRegistry` for the transfer counter + timer
```kotlin
package com.reckon.platform

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class Metrics(private val registry: MeterRegistry) {
    /** Count a completed/failed/replayed transfer by type+outcome. */
    fun transfer(type: String, outcome: String) =
        registry.counter("reckon.transfers", "type", type, "outcome", outcome).increment()

    fun <T> timeTransfer(block: () -> T): T = Timer.builder("reckon.transfer.duration")
        .publishPercentiles(0.5, 0.95, 0.99).register(registry)
        .recordCallable(block)!!
}
```
- [ ] **Step 2: Instrument `LedgerService.recordTransfer`** — wrap the body in `metrics.timeTransfer { ... }` and increment the counter on each outcome (COMPLETED, FAILED, replayed). Inject `Metrics` and (for the span) annotate the method `@io.micrometer.observation.annotation.Observed(name = "reckon.transfer")` OR create a manual span. SIMPLEST + testable: use `@Observed` (Spring Boot auto-configures an `ObservedAspect` if `aspectjweaver` is present — if it's NOT, do a manual span via an injected `io.micrometer.tracing.Tracer`). To avoid adding AOP, prefer the manual `Tracer` span:
```kotlin
// constructor: add `private val metrics: Metrics, private val tracer: io.micrometer.tracing.Tracer`
fun recordTransfer(...): TransferOutcome {
    val span = tracer.nextSpan().name("reckon.transfer").tag("type", type.name).start()
    return try {
        tracer.withSpan(span).use {
            metrics.timeTransfer {
                ... existing body ...
                // on COMPLETED: metrics.transfer(type.name, "COMPLETED"); on cache/replay: "REPLAYED"
            }
        }
    } catch (e: Exception) {
        metrics.transfer(type.name, "FAILED"); span.tag("error", e.javaClass.simpleName); throw e
    } finally { span.end() }
}
```
Keep the existing logic identical — only ADD counter/timer/span around it. Make sure all return paths (fast-path replay, DB replay, completed) increment an appropriate counter.
- [ ] **Step 3: Verify compiles**; run `./gradlew test --tests "com.reckon.ledger.*"` → still green (instrumentation must not change behavior). **Commit** `git add -A && git commit -m "feat: transfer metrics (counter + percentile timer) + tracing span"`

---

## Task 4: Logback pattern with traceId + correlationId
**Files:** Create `src/main/resources/logback-spring.xml`
- [ ] **Step 1: Write** — console pattern including `%X{correlationId}` and the Micrometer-populated `traceId`/`spanId` MDC keys
```xml
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [corr=%X{correlationId:-} trace=%X{traceId:-} span=%X{spanId:-}] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
```
- [ ] **Step 2: Verify** the app still boots in tests (`./gradlew test --tests "com.reckon.support.HarnessTest"`). **Commit** `git add -A && git commit -m "feat: structured log pattern with correlationId + traceId"`

---

## Task 5: Metrics + correlation + tracing tests
**Files:** Create `src/test/kotlin/com/reckon/observability/{MetricsTest,CorrelationIdFilterTest,TracingTest}.kt`
- [ ] **Step 1: `MetricsTest.kt`** — do a transfer, then assert the counter/timer show up on `/actuator/prometheus` (HTTP) and the registry
```kotlin
package com.reckon.observability

import com.reckon.ledger.LedgerService
import com.reckon.ledger.TxnType
import com.reckon.platform.RequestHash
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
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        ledger.recordTransfer(TxnType.P2P, "m1", RequestHash.of("P2P", a, b, 10000), UUID.randomUUID(), a, b, 10000)
        val count = registry.find("reckon.transfers").tag("outcome", "COMPLETED").counter()?.count() ?: 0.0
        assertTrue(count >= 1.0, "transfers counter should have incremented")
        // exposed on the prometheus actuator endpoint
        val body = rest.getForObject("/actuator/prometheus", String::class.java)
        assertTrue(body.contains("reckon_transfers"), "prometheus endpoint should expose reckon_transfers")
        assertTrue(body.contains("reckon_transfer_duration"), "should expose the transfer duration timer")
    }
}
```
- [ ] **Step 2: `CorrelationIdFilterTest.kt`** — header echoed when provided; generated when absent
```kotlin
package com.reckon.observability

import com.reckon.platform.CORRELATION_ID_HEADER
import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorrelationIdFilterTest : PostgresTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Test fun `provided correlation id is echoed back`() {
        val headers = HttpHeaders().apply { set(CORRELATION_ID_HEADER, "test-corr-123") }
        val resp = rest.exchange("/actuator/health", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertEquals("test-corr-123", resp.headers.getFirst(CORRELATION_ID_HEADER))
    }

    @Test fun `correlation id is generated when absent`() {
        val resp = rest.getForEntity("/actuator/health", String::class.java)
        assertNotNull(resp.headers.getFirst(CORRELATION_ID_HEADER), "a correlation id should be generated")
    }
}
```
- [ ] **Step 3: `TracingTest.kt`** — register an in-memory OTel span exporter as a test bean and assert a `reckon.transfer` span is recorded. If wiring an in-memory exporter into the Spring/Micrometer-OTel bridge proves complex, fall back to asserting the `Tracer` bean is present AND a span's `currentSpan()` is non-null during a transfer (use a simpler check). Prefer the exporter assertion; if it's brittle, use the simpler `Tracer`-present + span-created assertion and note it.
```kotlin
package com.reckon.observability

import com.reckon.ledger.LedgerService
import com.reckon.ledger.TxnType
import com.reckon.platform.RequestHash
import com.reckon.support.Fixtures
import com.reckon.support.PostgresTestBase
import io.micrometer.tracing.Tracer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertNotNull

class TracingTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var tracer: Tracer

    @Test fun `tracer is configured and a transfer runs within tracing`() {
        assertNotNull(tracer, "a Micrometer Tracer should be auto-configured")
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        // should not throw; the span is created/closed inside recordTransfer
        ledger.recordTransfer(TxnType.P2P, "t1", RequestHash.of("P2P", a, b, 10000), UUID.randomUUID(), a, b, 10000)
        assertEquals(40000, fixtures.balanceOf(a))
    }
}
```
(Adjust imports; add `import kotlin.test.assertEquals`.)
- [ ] **Step 4: Run → PASS** (`./gradlew test --tests "com.reckon.observability.*"`). Then FULL `./gradlew test` → green (Plans 1–11 + observability). If the OTel tracing bridge changes the metrics/registry behavior in other tests, ensure the registry used in tests is a real (SimpleMeterRegistry or Prometheus) one — Spring Boot provides one by default.
- [ ] **Step 5: Commit** `git add -A && git commit -m "test: business metrics on prometheus endpoint + correlation id + tracing"`

---

## Task 6: Prometheus + Grafana stack + dashboard
**Files:** Create `observability/docker-compose.observability.yml`, `prometheus.yml`, `grafana-datasource.yml`, `grafana-dashboard-reckon.json`
- [ ] **Step 1: `prometheus.yml`** — scrape the app
```yaml
global: { scrape_interval: 5s }
scrape_configs:
  - job_name: reckon
    metrics_path: /actuator/prometheus
    static_configs: [ { targets: ["host.docker.internal:8080"] } ]
```
- [ ] **Step 2: `docker-compose.observability.yml`** — prometheus + grafana with provisioned datasource + dashboard
```yaml
services:
  prometheus:
    image: prom/prometheus:v2.54.1
    volumes: [ "./prometheus.yml:/etc/prometheus/prometheus.yml:ro" ]
    ports: [ "9090:9090" ]
  grafana:
    image: grafana/grafana:11.2.0
    environment: { GF_AUTH_ANONYMOUS_ENABLED: "true", GF_AUTH_ANONYMOUS_ORG_ROLE: "Admin" }
    ports: [ "3000:3000" ]
    volumes:
      - "./grafana-datasource.yml:/etc/grafana/provisioning/datasources/ds.yml:ro"
      - "./grafana-dashboard-reckon.json:/var/lib/grafana/dashboards/reckon.json:ro"
```
- [ ] **Step 3: `grafana-datasource.yml`** — point Grafana at Prometheus
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```
- [ ] **Step 4: `grafana-dashboard-reckon.json`** — a minimal valid Grafana dashboard with panels: transfer rate (`rate(reckon_transfers_total[1m])` by outcome), transfer latency p95/p99 (`histogram_quantile(0.95, ...reckon_transfer_duration...)`), JVM/HTTP basics. Keep it a valid dashboard JSON (Grafana schema). It does not need to be elaborate — 3–4 panels with the queries above.
- [ ] **Step 5:** `docker compose -f observability/docker-compose.observability.yml config` parses. **Commit** `git add -A && git commit -m "feat: prometheus + grafana stack with reckon dashboard"`

---

## Task 7: README
**Files:** Modify `README.md`
- [ ] **Step 1:** Add an "Observability" section: custom Prometheus metrics (`reckon_transfers`, `reckon_transfer_duration` p50/p95/p99) on `/actuator/prometheus`; OpenTelemetry tracing (span per transfer, OTLP export via `OTEL_EXPORTER_OTLP_ENDPOINT`); correlation IDs (`X-Correlation-Id`) in logs/responses. Document running the stack: `docker compose -f observability/docker-compose.observability.yml up`, app exposes metrics, Grafana at :3000 with the pre-built dashboard. Be honest: dashboards are committed artifacts; add a screenshot after a local run.
- [ ] **Step 2: Run FULL suite** → green. **Commit** `git add -A && git commit -m "docs: observability (metrics, tracing, correlation ids, grafana)"`

---

## Done criteria (Plan 12)
- `./gradlew test` green incl. MetricsTest, CorrelationIdFilterTest, TracingTest.
- `/actuator/prometheus` exposes `reckon_transfers` + `reckon_transfer_duration`; the counter increments per transfer; the timer records percentiles.
- A correlation id is read-or-generated, set in MDC, and echoed on responses; logs include `correlationId`/`traceId`.
- A tracing span wraps each transfer (Tracer auto-configured; OTLP export gated by env).
- The Prometheus+Grafana compose + dashboard JSON are committed and parse; README documents how to run them.

## This completes the advanced roadmap
With Plan 12, Reckon has: hold/capture payments, property-based invariant tests, chaos testing, and full observability — on top of the 8-phase core.
