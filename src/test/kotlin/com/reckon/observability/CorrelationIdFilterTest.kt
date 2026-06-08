package com.reckon.observability

import com.reckon.platform.constant.CORRELATION_ID_HEADER
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
