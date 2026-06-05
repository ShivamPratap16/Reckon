package com.walletx.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
abstract class PostgresTestBase {
    companion object {
        // Singleton container shared across ALL test classes in the JVM.
        // Started once, stopped via JVM shutdown hook — avoids the "HikariPool connections
        // stale after container restart" problem when multiple Spring contexts are created.
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("walletx"); withUsername("walletx"); withPassword("walletx")
            withReuse(false)
        }.also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", pg::getJdbcUrl)
            r.add("spring.datasource.username", pg::getUsername)
            r.add("spring.datasource.password", pg::getPassword)
        }
    }
}
