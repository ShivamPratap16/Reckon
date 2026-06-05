package com.walletx.support

import com.walletx.account.AccountRepository
import com.walletx.auth.UserRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class Fixtures(
    private val users: UserRepository,
    private val accounts: AccountRepository,
    private val jdbc: JdbcTemplate,
) {
    /** Create a user + wallet seeded with the given paisa balance. Returns wallet account id. */
    fun walletWith(balancePaisa: Long, email: String = "u${UUID.randomUUID()}@x.com"): UUID {
        val u = users.create(email, "hash")
        val w = accounts.createWallet(u.id)
        jdbc.update("UPDATE accounts SET balance=? WHERE id=?", balancePaisa, w.id)
        return w.id
    }
    fun balanceOf(accountId: UUID): Long =
        jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?", Long::class.java, accountId)!!
}
