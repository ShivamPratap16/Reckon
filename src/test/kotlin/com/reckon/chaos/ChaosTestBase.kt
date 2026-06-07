package com.reckon.chaos

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.ToxiproxyContainer
import org.testcontainers.images.AbstractImagePullPolicy
import org.testcontainers.images.ImageData
import org.testcontainers.utility.DockerImageName

/**
 * Chaos test base: routes the app's Postgres connection through Toxiproxy.
 *
 * Architecture:
 *   App → Toxiproxy (mapped port) → pgdb:5432 (Docker network alias)
 *
 * The PostgreSQL container and ToxiproxyContainer share a Docker Network so
 * Toxiproxy can reach Postgres by the alias "pgdb". Spring's datasource URL
 * points at the mapped Toxiproxy port, not Postgres directly. Flyway runs at
 * context startup with no toxics active. Tests add toxics via [pgProxy] and
 * remove them in @AfterEach.
 *
 * Postgres is proxied (not Kafka) because Postgres transaction atomicity is the
 * "zero money loss under failure" property we want to prove. Kafka advertised-
 * listener proxying is unreliable (clients reconnect directly, bypassing the proxy).
 */
@SpringBootTest
@TestPropertySource(
    properties = [
        "reckon.outbox.scheduler.enabled=false",
        "reckon.consumers.enabled=false",
        "reckon.saga.recovery.enabled=false",
        "reckon.reconciliation.enabled=false",
        "reckon.holds.expiry.enabled=false",
        "reckon.idempotency.cache.enabled=false",
        // Surface connection failures fast instead of hanging the test suite
        "spring.datasource.hikari.connection-timeout=3000",
        "spring.datasource.hikari.validation-timeout=2000",
        "spring.datasource.hikari.maximum-pool-size=8",
    ]
)
abstract class ChaosTestBase {
    companion object {
        private val network: Network = Network.newNetwork()

        @JvmStatic
        val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withNetwork(network)
            .withNetworkAliases("pgdb")
            .apply {
                withDatabaseName("reckon")
                withUsername("reckon")
                withPassword("reckon")
                start()
            }

        // Always use the locally cached image — avoids ghcr.io TLS timeouts in restricted networks.
        private val localOnly = object : AbstractImagePullPolicy() {
            override fun shouldPullCached(name: DockerImageName, data: ImageData) = false
        }

        @JvmStatic
        val toxi: ToxiproxyContainer = ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
            .withNetwork(network)
            .withImagePullPolicy(localOnly)
            .apply { start() }

        /**
         * A proxy handle for the Postgres upstream. The [ToxiproxyContainer.getProxy] helper
         * wires up network aliases and allocates a port in the container's exposed range (8666–8999).
         * Use this handle in tests to add/remove toxics.
         */
        @JvmStatic
        val pgProxy: ToxiproxyContainer.ContainerProxy = toxi.getProxy(pg, 5432)

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            // Point the app at Toxiproxy's mapped port, not Postgres directly.
            r.add("spring.datasource.url") {
                "jdbc:postgresql://${pgProxy.containerIpAddress}:${pgProxy.proxyPort}/reckon"
            }
            r.add("spring.datasource.username") { "reckon" }
            r.add("spring.datasource.password") { "reckon" }
        }
    }
}
