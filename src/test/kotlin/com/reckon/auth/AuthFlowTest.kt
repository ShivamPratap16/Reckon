package com.reckon.auth

import com.reckon.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTest : PostgresTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `signup then login issues tokens and creates wallet`() {
        val signup = rest.postForEntity(
            "/auth/signup",
            AuthRequest("a@x.com", "pw123456"),
            AuthResponse::class.java,
        )
        assertEquals(HttpStatus.OK, signup.statusCode)
        assertNotNull(signup.body?.token)

        val walletCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM accounts WHERE owner_id = (SELECT id FROM users WHERE email = ?) AND type = 'USER_WALLET'",
            Int::class.java,
            "a@x.com",
        )
        kotlin.test.assertEquals(1, walletCount)

        val login = rest.postForEntity(
            "/auth/login",
            AuthRequest("a@x.com", "pw123456"),
            AuthResponse::class.java,
        )
        assertEquals(HttpStatus.OK, login.statusCode)
        assertNotNull(login.body?.token)
    }

    @Test fun `duplicate signup is rejected`() {
        rest.postForEntity("/auth/signup", AuthRequest("b@x.com", "pw123456"), AuthResponse::class.java)
        val dup = rest.postForEntity("/auth/signup", AuthRequest("b@x.com", "pw123456"), String::class.java)
        assertEquals(HttpStatus.CONFLICT, dup.statusCode)
    }

    @Test fun `wrong password is unauthorized`() {
        rest.postForEntity("/auth/signup", AuthRequest("c@x.com", "pw123456"), AuthResponse::class.java)
        val bad = rest.postForEntity("/auth/login", AuthRequest("c@x.com", "wrong"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, bad.statusCode)
    }

    @Test fun `signup with blank password is rejected with 400`() {
        val resp = rest.postForEntity("/auth/signup", AuthRequest("valid@x.com", ""), String::class.java)
        kotlin.test.assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, resp.statusCode)
    }
}
