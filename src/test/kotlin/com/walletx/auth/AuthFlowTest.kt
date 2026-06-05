package com.walletx.auth

import com.walletx.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTest : PostgresTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Test fun `signup then login issues tokens and creates wallet`() {
        val signup = rest.postForEntity("/auth/signup",
            AuthRequest("a@x.com", "pw123456"), AuthResponse::class.java)
        assertEquals(HttpStatus.OK, signup.statusCode)
        assertNotNull(signup.body?.token)

        val login = rest.postForEntity("/auth/login",
            AuthRequest("a@x.com", "pw123456"), AuthResponse::class.java)
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
}
