package com.reckon.account

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

enum class AccountType { USER_WALLET, BANK_SETTLEMENT, REWARDS_POOL, MERCHANT }

data class Account(val id: UUID, val ownerId: UUID?, val type: AccountType, val balance: Long, val version: Long)

object SystemAccounts {
    val BANK_SETTLEMENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val REWARDS_POOL: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
}

@Repository
class AccountRepository(private val jdbc: JdbcTemplate) {

    private val mapper = { rs: java.sql.ResultSet, _: Int ->
        Account(
            rs.getObject("id", UUID::class.java),
            rs.getObject("owner_id", UUID::class.java),
            AccountType.valueOf(rs.getString("type")),
            rs.getLong("balance"),
            rs.getLong("version"),
        )
    }

    fun createWallet(ownerId: UUID): Account {
        val id = jdbc.queryForObject(
            "INSERT INTO accounts(owner_id, type) VALUES (?, 'USER_WALLET') RETURNING id",
            UUID::class.java, ownerId,
        )!!
        return Account(id, ownerId, AccountType.USER_WALLET, 0, 0)
    }

    fun findByOwner(ownerId: UUID): Account? = jdbc.query(
        "SELECT id, owner_id, type, balance, version FROM accounts WHERE owner_id = ? AND type='USER_WALLET'",
        mapper, ownerId,
    ).firstOrNull()

    fun findById(id: UUID): Account? = jdbc.query(
        "SELECT id, owner_id, type, balance, version FROM accounts WHERE id = ?", mapper, id,
    ).firstOrNull()

    /**
     * Lock the given accounts FOR UPDATE in ASCENDING id order (deadlock-safe).
     * Must be called inside a transaction.
     */
    fun lockByIdsInOrder(ids: List<UUID>): List<Account> {
        val ordered = ids.distinct().sorted()
        return ordered.map { id ->
            jdbc.query(
                // FOR NO KEY UPDATE instead of FOR UPDATE: prevents concurrent writers but still allows
                // KEY SHARE locks taken by FK reference checks (e.g. ledger_entries.account_id → accounts).
                // This avoids deadlocks between our writer lock and FK-share locks from concurrent inserts.
                "SELECT id, owner_id, type, balance, version FROM accounts WHERE id = ? FOR NO KEY UPDATE",
                mapper, id,
            ).firstOrNull() ?: throw IllegalStateException("account not found: $id")
        }
    }

    /** Apply a signed delta to balance and bump version. Returns rows updated. */
    fun applyDelta(id: UUID, delta: Long): Int = jdbc.update(
        "UPDATE accounts SET balance = balance + ?, version = version + 1, updated_at = now() WHERE id = ?",
        delta, id,
    )
}
