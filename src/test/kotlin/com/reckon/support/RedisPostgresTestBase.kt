package com.reckon.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
@TestPropertySource(
    properties = [
        "reckon.outbox.scheduler.enabled=false", "reckon.consumers.enabled=false",
        "reckon.saga.recovery.enabled=false", "reckon.reconciliation.enabled=false",
        "reckon.idempotency.cache.enabled=true",
    ],
)
abstract class RedisPostgresTestBase {
    companion object {
        @JvmStatic val pg = PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("reckon")
            withUsername("reckon")
            withPassword("reckon")
            start()
        }

        @JvmStatic val redis = GenericContainer<Nothing>("redis:7-alpine").apply {
            withExposedPorts(6379)
            start()
        }

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
