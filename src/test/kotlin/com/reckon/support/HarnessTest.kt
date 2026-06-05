package com.reckon.support

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

class HarnessTest : PostgresTestBase() {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `context loads and db reachable`() {
        val one = jdbc.queryForObject("SELECT 1", Int::class.java)
        assertEquals(1, one)
    }
}
