package com.walletx.ledger

import com.walletx.auth.AuthRequest
import com.walletx.auth.AuthResponse
import com.walletx.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferControllerTest : PostgresTestBase() {
    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun signup(email: String): Pair<String, UUID> {
        val token = rest.postForEntity("/auth/signup", AuthRequest(email, "pw123456"), AuthResponse::class.java).body!!.token
        val id = jdbc.queryForObject("SELECT id FROM users WHERE email=?", UUID::class.java, email)!!
        return token to id
    }
    private fun fund(email: String, paisa: Long) =
        jdbc.update("UPDATE accounts SET balance=? WHERE owner_id=(SELECT id FROM users WHERE email=?)", paisa, email)

    private fun auth(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    @Test fun `authenticated p2p transfer succeeds`() {
        val (tokenA, _) = signup("p2pa@x.com"); val (_, idB) = signup("p2pb@x.com")
        fund("p2pa@x.com", 50000)
        val body = P2pRequest("idem-1", idB, 20000)
        val resp = rest.exchange("/transfers/p2p", HttpMethod.POST,
            HttpEntity(body, auth(tokenA)), TransferResult::class.java)
        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("COMPLETED", resp.body?.status)
        assertEquals(30000, jdbc.queryForObject(
            "SELECT balance FROM accounts WHERE owner_id=(SELECT id FROM users WHERE email='p2pa@x.com')", Long::class.java))
    }

    @Test fun `unauthenticated transfer is rejected`() {
        val (_, idB) = signup("p2pc@x.com")
        val resp = rest.postForEntity("/transfers/p2p", P2pRequest("idem-x", idB, 100), String::class.java)
        assertEquals(HttpStatus.FORBIDDEN, resp.statusCode)
    }

    @Test fun `insufficient funds returns 422`() {
        val (tokenA, _) = signup("p2pd@x.com"); val (_, idB) = signup("p2pe@x.com")
        fund("p2pd@x.com", 100)
        val resp = rest.exchange("/transfers/p2p", HttpMethod.POST,
            HttpEntity(P2pRequest("idem-2", idB, 99999), auth(tokenA)), String::class.java)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.statusCode)
    }
}
