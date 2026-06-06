package com.reckon.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest
@TestPropertySource(properties = ["reckon.outbox.scheduler.enabled=false", "reckon.consumers.enabled=false"])
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
