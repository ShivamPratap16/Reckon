package com.reckon.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * JPA entity for the `users` table (simple CRUD — persisted via Spring Data JPA).
 * The money-critical tables (accounts, transactions, ledger_entries, outbox, …) deliberately
 * stay on JdbcTemplate/native SQL because they rely on FOR NO KEY UPDATE / SKIP LOCKED /
 * ON CONFLICT / conditional-update semantics that JPA hides.
 */
@Entity
@Table(name = "users")
class User(
    @Column(name = "email", nullable = false, unique = true)
    val email: String,
    @Column(name = "password_hash", nullable = false)
    val passwordHash: String,
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
)
