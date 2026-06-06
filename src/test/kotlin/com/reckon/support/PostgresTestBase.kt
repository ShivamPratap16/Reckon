package com.reckon.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
@TestPropertySource(properties = ["reckon.outbox.scheduler.enabled=false", "reckon.consumers.enabled=false", "reckon.saga.recovery.enabled=false", "reckon.saga.recovery.stale-seconds=0", "reckon.reconciliation.enabled=false", "reckon.idempotency.cache.enabled=false", "reckon.holds.expiry.enabled=false"])
abstract class PostgresTestBase {
    companion object {
        // Singleton container shared across ALL test classes in the JVM.
        // Started once, stopped via JVM shutdown hook — avoids the "HikariPool connections
        // stale after container restart" problem when multiple Spring contexts are created.
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("reckon"); withUsername("reckon"); withPassword("reckon")
            withReuse(false)
        }.also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", pg::getJdbcUrl)
            r.add("spring.datasource.username", pg::getUsername)
            r.add("spring.datasource.password", pg::getPassword)
            // Increase pool so concurrency tests don't starve under 16-thread load
            r.add("spring.datasource.hikari.maximum-pool-size") { "20" }
            r.add("spring.datasource.hikari.connection-timeout") { "10000" }
        }
    }
}
