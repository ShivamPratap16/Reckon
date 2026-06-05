package com.walletx.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
abstract class PostgresTestBase {
    companion object {
        @Container
        @JvmStatic
        val pg = PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("walletx"); withUsername("walletx"); withPassword("walletx")
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", pg::getJdbcUrl)
            r.add("spring.datasource.username", pg::getUsername)
            r.add("spring.datasource.password", pg::getPassword)
        }
    }
}
