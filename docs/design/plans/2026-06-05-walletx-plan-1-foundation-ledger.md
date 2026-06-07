# WalletX Plan 1 — Foundation + Ledger/P2P Implementation Plan

> Implementation plan — each task is a small, independently-verifiable checklist of steps (`- [ ]`), built and tested incrementally.

**Goal:** Stand up the WalletX Spring Boot skeleton with Postgres + JWT auth, then build the double-entry ledger core and a fully correct, concurrency-safe P2P transfer endpoint end-to-end.

**Architecture:** Modular monolith (Kotlin + Spring Boot). Only the `ledger` module writes money tables. Balances are a transaction-consistent denormalization maintained atomically with append-only double-entry rows under fixed-order row locks. P2P is a single ACID transaction (no saga).

**Tech Stack:** Kotlin, Spring Boot 3.x (Web, Data JDBC/JdbcTemplate, Security), PostgreSQL, Flyway, Gradle (Kotlin DSL), JUnit 5, Testcontainers, jjwt, BCrypt.

**Covers spec stages:** §8 stage 1 (skeleton + auth) and §8 stage 2 (ledger + P2P). Stages 3–8 are separate plans.

**Scope note:** Idempotency (`request_hash` + 4-way replay), Kafka/outbox, saga, rewards, reconciliation, and load tests are OUT of this plan. Stage 2 here builds the *correct concurrent ledger write*; idempotency wraps it in Plan 2. To avoid rework, the `transactions` table is created with its full spec columns now, but only the columns this plan uses are populated.

---

## File Structure

```
walletx/
├── build.gradle.kts                      # deps, kotlin, spring boot, test
├── settings.gradle.kts
├── docker-compose.yml                    # local postgres for running the app
├── src/main/resources/
│   ├── application.yml                   # datasource, jwt secret
│   └── db/migration/
│       ├── V1__users_and_accounts.sql
│       └── V2__transactions_and_ledger.sql
├── src/main/kotlin/com/walletx/
│   ├── WalletxApplication.kt
│   ├── platform/
│   │   ├── Money.kt                      # Paisa value type + helpers
│   │   └── ApiError.kt                   # error model + @ControllerAdvice
│   ├── auth/
│   │   ├── User.kt                       # entity + repo
│   │   ├── JwtService.kt                 # issue/verify HS256
│   │   ├── AuthController.kt             # /auth/signup, /auth/login
│   │   ├── AuthService.kt
│   │   └── SecurityConfig.kt            # filter chain, JWT filter, CurrentUser
│   ├── account/
│   │   ├── Account.kt                    # entity (type, balance, version)
│   │   └── AccountRepository.kt          # incl. lockByIdsInOrder()
│   └── ledger/
│       ├── LedgerEntry.kt
│       ├── Transaction.kt
│       ├── LedgerRepository.kt
│       ├── LedgerService.kt              # THE money-writer: recordTransfer()
│       ├── TransferController.kt         # POST /transfers/p2p
│       └── dto.kt                        # P2pRequest, TransferResult
└── src/test/kotlin/com/walletx/
    ├── support/
    │   ├── PostgresTestBase.kt           # Testcontainers + Flyway
    │   └── Fixtures.kt                   # seed users/accounts
    ├── auth/AuthFlowTest.kt
    ├── ledger/LedgerServiceTest.kt       # DEEPEST coverage (stage 2 core)
    ├── ledger/LedgerConcurrencyTest.kt   # parallel-debit correctness
    └── ledger/TransferControllerTest.kt
```

---

## Task 1: Project skeleton (Gradle + Spring Boot boots)

**Files:**
- Create: `walletx/settings.gradle.kts`
- Create: `walletx/build.gradle.kts`
- Create: `walletx/src/main/kotlin/com/walletx/WalletxApplication.kt`
- Create: `walletx/src/main/resources/application.yml`
- Create: `walletx/docker-compose.yml`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "walletx"
```

- [ ] **Step 2: Create `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.walletx"
version = "0.1.0"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
}

tasks.withType<Test> { useJUnitPlatform() }
kotlin { compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") } }
```

- [ ] **Step 3: Create `WalletxApplication.kt`**

```kotlin
package com.walletx

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class WalletxApplication

fun main(args: Array<String>) {
    runApplication<WalletxApplication>(*args)
}
```

- [ ] **Step 4: Create `application.yml`**

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/walletx}
    username: ${DB_USER:walletx}
    password: ${DB_PASSWORD:walletx}
  flyway:
    enabled: true
walletx:
  jwt:
    secret: ${JWT_SECRET:dev-only-secret-change-me-must-be-at-least-256-bits-long-xxxxx}
    ttl-minutes: 60
```

- [ ] **Step 5: Create `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: walletx
      POSTGRES_USER: walletx
      POSTGRES_PASSWORD: walletx
    ports: ["5432:5432"]
```

- [ ] **Step 6: Verify it compiles**

Run: `cd walletx && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (Gradle wrapper is generated on first run; if `./gradlew` is missing, run `gradle wrapper` first).

- [ ] **Step 7: Commit**

```bash
git add walletx/
git commit -m "chore: spring boot + gradle skeleton"
```

---

## Task 2: Test harness (Postgres Testcontainers base)

**Files:**
- Create: `walletx/src/test/kotlin/com/walletx/support/PostgresTestBase.kt`

- [ ] **Step 1: Write `PostgresTestBase.kt`**

```kotlin
package com.walletx.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
abstract class PostgresTestBase {
    companion object {
        @Container
        @JvmStatic
        val pg = PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("walletx"); withUsername("walletx"); withPassword("walletx")
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", pg::getJdbcUrl)
            r.add("spring.datasource.username", pg::getUsername)
            r.add("spring.datasource.password", pg::getPassword)
        }
    }
}
```

- [ ] **Step 2: Add a trivial context-loads test to prove the harness works**

Create `walletx/src/test/kotlin/com/walletx/support/HarnessTest.kt`:

```kotlin
package com.walletx.support

import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class HarnessTest(private val jdbc: JdbcTemplate) : PostgresTestBase() {
    @Test
    fun `context loads and db reachable`() {
        val one = jdbc.queryForObject("SELECT 1", Int::class.java)
        assert(one == 1)
    }
}
```

Note: Spring injects `JdbcTemplate` via constructor in test classes annotated with `@SpringBootTest`.

- [ ] **Step 3: Run to verify it fails (no migrations yet / context may fail)**

Run: `./gradlew test --tests "com.walletx.support.HarnessTest"`
Expected: At this point it should PASS (no migrations required for `SELECT 1`; Flyway runs against an empty schema). If Flyway errors on no migrations, proceed to Task 3 which adds them, then re-run.

- [ ] **Step 4: Commit**

```bash
git add walletx/src/test
git commit -m "test: postgres testcontainers harness"
```

---

## Task 3: Schema migrations (full spec schema, Flyway)

**Files:**
- Create: `walletx/src/main/resources/db/migration/V1__users_and_accounts.sql`
- Create: `walletx/src/main/resources/db/migration/V2__transactions_and_ledger.sql`

- [ ] **Step 1: Write `V1__users_and_accounts.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   uuid NULL REFERENCES users(id),
    type       text NOT NULL,         -- USER_WALLET | BANK_SETTLEMENT | REWARDS_POOL | MERCHANT
    currency   text NOT NULL DEFAULT 'INR',
    balance    bigint NOT NULL DEFAULT 0,   -- PAISA
    version    bigint NOT NULL DEFAULT 0,
    status     text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT balance_nonneg_except_system
        CHECK (balance >= 0 OR type IN ('BANK_SETTLEMENT','REWARDS_POOL'))
);

-- seed system accounts (fixed UUIDs so app code can reference them)
INSERT INTO accounts (id, owner_id, type) VALUES
  ('00000000-0000-0000-0000-000000000001', NULL, 'BANK_SETTLEMENT'),
  ('00000000-0000-0000-0000-000000000002', NULL, 'REWARDS_POOL');
```

- [ ] **Step 2: Write `V2__transactions_and_ledger.sql`**

```sql
CREATE TABLE transactions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type            text NOT NULL,   -- ADD_MONEY | P2P | PAY_MERCHANT | CASHBACK
    status          text NOT NULL,   -- PENDING | COMPLETED | FAILED | COMPENSATED
    idempotency_key text NOT NULL,
    request_hash    text NOT NULL,
    amount          bigint NOT NULL,
    initiator_id    uuid NULL,
    from_account_id uuid NULL REFERENCES accounts(id),
    to_account_id   uuid NULL REFERENCES accounts(id),
    saga_state      text NULL,
    failure_reason  text NULL,
    response_code   int NULL,
    response_body   jsonb NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT amount_positive CHECK (amount > 0),
    CONSTRAINT uq_initiator_idem UNIQUE (initiator_id, idempotency_key)
);

CREATE TABLE ledger_entries (
    id             bigserial PRIMARY KEY,
    transaction_id uuid NOT NULL REFERENCES transactions(id),
    account_id     uuid NOT NULL REFERENCES accounts(id),
    direction      text NOT NULL,   -- DEBIT | CREDIT
    amount         bigint NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT entry_amount_positive CHECK (amount > 0),
    CONSTRAINT uq_txn_account_direction UNIQUE (transaction_id, account_id, direction)
);

CREATE INDEX idx_entries_account_id ON ledger_entries (account_id, id);
CREATE INDEX idx_entries_txn ON ledger_entries (transaction_id);
CREATE INDEX idx_txn_status_created ON transactions (status, created_at);
```

- [ ] **Step 3: Run the harness test to verify migrations apply**

Run: `./gradlew test --tests "com.walletx.support.HarnessTest"`
Expected: PASS. Testcontainers spins Postgres, Flyway applies V1+V2 cleanly.

- [ ] **Step 4: Commit**

```bash
git add walletx/src/main/resources/db
git commit -m "feat: full schema migrations (users, accounts, transactions, ledger)"
```

---

## Task 4: Money value type (Paisa)

**Files:**
- Create: `walletx/src/main/kotlin/com/walletx/platform/Money.kt`
- Test: `walletx/src/test/kotlin/com/walletx/platform/MoneyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.walletx.platform

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class MoneyTest {
    @Test fun `rupees converts to paisa`() {
        assertEquals(15000L, Paisa.ofRupees(150).value)
    }
    @Test fun `paisa rejects negative`() {
        assertThrows<IllegalArgumentException> { Paisa(-1) }
    }
    @Test fun `formats as rupee string`() {
        assertEquals("₹150.00", Paisa(15000).toDisplay())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.walletx.platform.MoneyTest"`
Expected: FAIL — `Paisa` unresolved reference.

- [ ] **Step 3: Implement `Money.kt`**

```kotlin
package com.walletx.platform

@JvmInline
value class Paisa(val value: Long) {
    init { require(value >= 0) { "Paisa must be non-negative, got $value" } }
    companion object { fun ofRupees(rupees: Long) = Paisa(rupees * 100) }
    fun toDisplay(): String = "₹%.2f".format(value / 100.0)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.walletx.platform.MoneyTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add walletx/src/main/kotlin/com/walletx/platform/Money.kt walletx/src/test/kotlin/com/walletx/platform/MoneyTest.kt
git commit -m "feat: Paisa integer-money value type"
```

---

## Task 5: Error model + ControllerAdvice

**Files:**
- Create: `walletx/src/main/kotlin/com/walletx/platform/ApiError.kt`

- [ ] **Step 1: Write `ApiError.kt`**

```kotlin
package com.walletx.platform

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(val status: HttpStatus, val code: String, msg: String) : RuntimeException(msg)

data class ApiErrorBody(val code: String, val message: String)

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<ApiErrorBody> =
        ResponseEntity.status(e.status).body(ApiErrorBody(e.code, e.message ?: e.code))
}
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add walletx/src/main/kotlin/com/walletx/platform/ApiError.kt
git commit -m "feat: api error model + global exception handler"
```

---

## Task 6: JWT service

**Files:**
- Create: `walletx/src/main/kotlin/com/walletx/auth/JwtService.kt`
- Test: `walletx/src/test/kotlin/com/walletx/auth/JwtServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.walletx.auth

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwtServiceTest {
    private val secret = "test-secret-that-is-definitely-at-least-256-bits-long-padding-xx"
    private val svc = JwtService(secret, ttlMinutes = 60)

    @Test fun `issued token verifies back to user id`() {
        val id = UUID.randomUUID()
        val token = svc.issue(id)
        assertEquals(id, svc.verify(token))
    }

    @Test fun `tampered token returns null`() {
        val token = svc.issue(UUID.randomUUID())
        assertNull(svc.verify(token + "x"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.walletx.auth.JwtServiceTest"`
Expected: FAIL — `JwtService` unresolved.

- [ ] **Step 3: Implement `JwtService.kt`**

```kotlin
package com.walletx.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import java.util.UUID

@Service
class JwtService(
    @Value("\${walletx.jwt.secret}") secret: String,
    @Value("\${walletx.jwt.ttl-minutes}") private val ttlMinutes: Long,
) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun issue(userId: UUID): String =
        Jwts.builder()
            .subject(userId.toString())
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + ttlMinutes * 60_000))
            .signWith(key)
            .compact()

    fun verify(token: String): UUID? = try {
        val sub = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).payload.subject
        UUID.fromString(sub)
    } catch (e: Exception) { null }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.walletx.auth.JwtServiceTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add walletx/src/main/kotlin/com/walletx/auth/JwtService.kt walletx/src/test/kotlin/com/walletx/auth/JwtServiceTest.kt
git commit -m "feat: jwt issue/verify service"
```

---

## Task 7: User entity + repository

**Files:**
- Create: `walletx/src/main/kotlin/com/walletx/auth/User.kt`

- [ ] **Step 1: Write `User.kt` (entity + JDBC repository)**

```kotlin
package com.walletx.auth

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class User(val id: UUID, val email: String, val passwordHash: String)

@Repository
class UserRepository(private val jdbc: JdbcTemplate) {

    fun create(email: String, passwordHash: String): User {
        val id = jdbc.queryForObject(
            "INSERT INTO users(email, password_hash) VALUES (?, ?) RETURNING id",
            UUID::class.java, email, passwordHash,
        )!!
        return User(id, email, passwordHash)
    }

    fun findByEmail(email: String): User? =
        jdbc.query(
            "SELECT id, email, password_hash FROM users WHERE email = ?",
            { rs, _ -> User(rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("password_hash")) },
            email,
        ).firstOrNull()
}
```

- [ ] **Step 2: Verify compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add walletx/src/main/kotlin/com/walletx/auth/User.kt
git commit -m "feat: user entity + repository"
```

---

## Task 8: Account entity + repository (with fixed-order locking)

**Files:**
- Create: `walletx/src/main/kotlin/com/walletx/account/Account.kt`

- [ ] **Step 1: Write `Account.kt`**

```kotlin
package com.walletx.account

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
                "SELECT id, owner_id, type, balance, version FROM accounts WHERE id = ? FOR UPDATE",
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
```

Note on `sorted()` for UUIDs: Kotlin's `UUID` is `Comparable`, giving a stable total order — sufficient for deadlock-safe lock ordering as long as ALL money paths use this same method.

- [ ] **Step 2: Verify compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add walletx/src/main/kotlin/com/walletx/account/Account.kt
git commit -m "feat: account entity + repository with fixed-order locking"
```

---

## Task 9: Auth service + controller + security filter

**Files:**
- Create: `walletx/src/main/kotlin/com/walletx/auth/AuthService.kt`
- Create: `walletx/src/main/kotlin/com/walletx/auth/AuthController.kt`
- Create: `walletx/src/main/kotlin/com/walletx/auth/SecurityConfig.kt`

- [ ] **Step 1: Write `AuthService.kt`**

```kotlin
package com.walletx.auth

import com.walletx.account.AccountRepository
import com.walletx.platform.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val users: UserRepository,
    private val accounts: AccountRepository,
    private val jwt: JwtService,
) {
    private val encoder = BCryptPasswordEncoder()

    @Transactional
    fun signup(email: String, password: String): String {
        if (users.findByEmail(email) != null)
            throw ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "email already registered")
        val user = users.create(email, encoder.encode(password))
        accounts.createWallet(user.id)       // every user gets a wallet
        return jwt.issue(user.id)
    }

    fun login(email: String, password: String): String {
        val user = users.findByEmail(email)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "invalid email or password")
        if (!encoder.matches(password, user.passwordHash))
            throw ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "invalid email or password")
        return jwt.issue(user.id)
    }
}
```

- [ ] **Step 2: Write `AuthController.kt`**

```kotlin
package com.walletx.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AuthRequest(@field:Email val email: String, @field:NotBlank val password: String)
data class AuthResponse(val token: String)

@RestController
@RequestMapping("/auth")
class AuthController(private val auth: AuthService) {
    @PostMapping("/signup")
    fun signup(@RequestBody req: AuthRequest) = AuthResponse(auth.signup(req.email, req.password))

    @PostMapping("/login")
    fun login(@RequestBody req: AuthRequest) = AuthResponse(auth.login(req.email, req.password))
}
```

- [ ] **Step 3: Write `SecurityConfig.kt` (JWT filter + CurrentUser resolver)**

```kotlin
package com.walletx.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

annotation class CurrentUser

@Component
class JwtAuthFilter(private val jwt: JwtService) : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val header = req.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val userId = jwt.verify(header.removePrefix("Bearer "))
            if (userId != null) {
                val authToken = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                SecurityContextHolder.getContext().authentication = authToken
            }
        }
        chain.doFilter(req, res)
    }
}

@Component
class CurrentUserResolver : HandlerMethodArgumentResolver, WebMvcConfigurer {
    override fun supportsParameter(p: MethodParameter) =
        p.hasParameterAnnotation(CurrentUser::class.java) && p.parameterType == UUID::class.java
    override fun resolveArgument(p: MethodParameter, c: ModelAndViewContainer?, w: NativeWebRequest, b: WebDataBinderFactory?): Any? =
        SecurityContextHolder.getContext().authentication?.principal as? UUID
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) { resolvers.add(this) }
}

@Configuration
class SecurityConfig(private val jwtFilter: JwtAuthFilter) {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/auth/**").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
```

- [ ] **Step 4: Verify compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add walletx/src/main/kotlin/com/walletx/auth/
git commit -m "feat: auth service, controller, jwt security filter chain"
```

---

## Task 10: Auth flow integration test

**Files:**
- Create: `walletx/src/test/kotlin/com/walletx/auth/AuthFlowTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run to verify (drives out any wiring bugs)**

Run: `./gradlew test --tests "com.walletx.auth.AuthFlowTest"`
Expected: PASS (3 tests). If `webEnvironment` conflicts with `PostgresTestBase`'s `@SpringBootTest`, override by annotating this class as shown (the closest annotation wins).

- [ ] **Step 3: Commit**

```bash
git add walletx/src/test/kotlin/com/walletx/auth/AuthFlowTest.kt
git commit -m "test: auth signup/login/duplicate/bad-password flow"
```

---

## Task 11: Ledger domain types + repository

**Files:**
- Create: `walletx/src/main/kotlin/com/walletx/ledger/LedgerEntry.kt`
- Create: `walletx/src/main/kotlin/com/walletx/ledger/LedgerRepository.kt`

- [ ] **Step 1: Write `LedgerEntry.kt`**

```kotlin
package com.walletx.ledger

import java.util.UUID

enum class Direction { DEBIT, CREDIT }
enum class TxnType { ADD_MONEY, P2P, PAY_MERCHANT, CASHBACK }
enum class TxnStatus { PENDING, COMPLETED, FAILED, COMPENSATED }

data class LedgerEntry(val transactionId: UUID, val accountId: UUID, val direction: Direction, val amount: Long)
```

- [ ] **Step 2: Write `LedgerRepository.kt`**

```kotlin
package com.walletx.ledger

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class LedgerRepository(private val jdbc: JdbcTemplate) {

    /** Insert the PENDING transaction header. Returns the new id. */
    fun insertPending(type: TxnType, idempotencyKey: String, requestHash: String,
                      amount: Long, initiatorId: UUID, from: UUID, to: UUID): UUID =
        jdbc.queryForObject(
            """INSERT INTO transactions(type, status, idempotency_key, request_hash, amount,
                   initiator_id, from_account_id, to_account_id)
               VALUES (?, 'PENDING', ?, ?, ?, ?, ?, ?) RETURNING id""",
            UUID::class.java,
            type.name, idempotencyKey, requestHash, amount, initiatorId, from, to,
        )!!

    fun insertEntry(e: LedgerEntry) = jdbc.update(
        "INSERT INTO ledger_entries(transaction_id, account_id, direction, amount) VALUES (?, ?, ?, ?)",
        e.transactionId, e.accountId, e.direction.name, e.amount,
    )

    /** Conditional status flip (optimistic state transition). Returns rows updated. */
    fun markCompletedIfPending(txnId: UUID): Int = jdbc.update(
        "UPDATE transactions SET status='COMPLETED', updated_at=now() WHERE id=? AND status='PENDING'",
        txnId,
    )

    fun markFailed(txnId: UUID, reason: String) = jdbc.update(
        "UPDATE transactions SET status='FAILED', failure_reason=?, updated_at=now() WHERE id=? AND status='PENDING'",
        reason, txnId,
    )

    fun sumEntries(accountId: UUID): Long = jdbc.queryForObject(
        """SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END),0)
           FROM ledger_entries WHERE account_id = ?""",
        Long::class.java, accountId,
    )!!

    fun status(txnId: UUID): TxnStatus = TxnStatus.valueOf(
        jdbc.queryForObject("SELECT status FROM transactions WHERE id=?", String::class.java, txnId)!!
    )
}
```

- [ ] **Step 3: Verify compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add walletx/src/main/kotlin/com/walletx/ledger/LedgerEntry.kt walletx/src/main/kotlin/com/walletx/ledger/LedgerRepository.kt
git commit -m "feat: ledger domain types + repository"
```

---

## Task 12: LedgerService.recordTransfer — the money-writer (DEEPEST coverage)

This is the stage-2 core that everything else leans on. It gets the most tests.

**Files:**
- Create: `walletx/src/main/kotlin/com/walletx/ledger/LedgerService.kt`
- Test: `walletx/src/test/kotlin/com/walletx/ledger/LedgerServiceTest.kt`
- Test: `walletx/src/test/kotlin/com/walletx/support/Fixtures.kt`

- [ ] **Step 1: Write `Fixtures.kt` test helper**

```kotlin
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
```

- [ ] **Step 2: Write the failing tests (the deep set)**

```kotlin
package com.walletx.ledger

import com.walletx.account.AccountRepository
import com.walletx.platform.ApiException
import com.walletx.support.Fixtures
import com.walletx.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

class LedgerServiceTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var fixtures: Fixtures
    @Autowired lateinit var repo: LedgerRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `successful transfer moves money and balances net to zero`() {
        val a = fixtures.walletWith(50000)   // ₹500
        val b = fixtures.walletWith(0)
        ledger.recordTransfer(TxnType.P2P, "k1", "h1", UUID.randomUUID(), a, b, 20000)

        assertEquals(30000, fixtures.balanceOf(a))
        assertEquals(20000, fixtures.balanceOf(b))
    }

    @Test fun `transfer writes exactly two entries summing to zero`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val txn = ledger.recordTransfer(TxnType.P2P, "k2", "h2", UUID.randomUUID(), a, b, 10000)

        val net = jdbc.queryForObject(
            """SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END)
               FROM ledger_entries WHERE transaction_id=?""", Long::class.java, txn)
        assertEquals(0L, net)
    }

    @Test fun `denormalized balance equals sum of entries`() {
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        ledger.recordTransfer(TxnType.P2P, "k3", "h3", UUID.randomUUID(), a, b, 12345)
        assertEquals(repo.sumEntries(a), fixtures.balanceOf(a))
        assertEquals(repo.sumEntries(b), fixtures.balanceOf(b))
    }

    @Test fun `insufficient funds throws and moves no money and records FAILED`() {
        val a = fixtures.walletWith(100); val b = fixtures.walletWith(0)
        val ex = assertThrows<ApiException> {
            ledger.recordTransfer(TxnType.P2P, "k4", "h4", UUID.randomUUID(), a, b, 99999)
        }
        assertEquals("INSUFFICIENT_FUNDS", ex.code)
        assertEquals(100, fixtures.balanceOf(a))   // unchanged
        assertEquals(0, fixtures.balanceOf(b))
    }

    @Test fun `total system balance is conserved by a transfer`() {
        val before = jdbc.queryForObject("SELECT SUM(balance) FROM accounts", Long::class.java)!!
        val a = fixtures.walletWith(50000); val b = fixtures.walletWith(0)
        val afterSeed = jdbc.queryForObject("SELECT SUM(balance) FROM accounts", Long::class.java)!!
        ledger.recordTransfer(TxnType.P2P, "k5", "h5", UUID.randomUUID(), a, b, 20000)
        val afterTransfer = jdbc.queryForObject("SELECT SUM(balance) FROM accounts", Long::class.java)!!
        // a transfer between existing accounts must not change the global sum
        assertEquals(afterSeed, afterTransfer)
        assert(afterSeed >= before)
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `./gradlew test --tests "com.walletx.ledger.LedgerServiceTest"`
Expected: FAIL — `LedgerService` / `recordTransfer` unresolved.

- [ ] **Step 4: Implement `LedgerService.kt`**

```kotlin
package com.walletx.ledger

import com.walletx.account.AccountRepository
import com.walletx.account.AccountType
import com.walletx.platform.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LedgerService(
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
) {
    /**
     * The ONLY money-writer for transfers. One atomic transaction:
     * lock rows (fixed order) -> check funds -> 2 entries -> update balances
     * -> conditional status flip. Returns the transaction id.
     */
    @Transactional
    fun recordTransfer(type: TxnType, idempotencyKey: String, requestHash: String,
                       initiatorId: UUID, from: UUID, to: UUID, amount: Long): UUID {
        require(amount > 0) { "amount must be positive" }
        require(from != to) { "cannot transfer to self" }

        val txnId = ledger.insertPending(type, idempotencyKey, requestHash, amount, initiatorId, from, to)

        // lock both rows in fixed id order (deadlock-safe)
        val locked = accounts.lockByIdsInOrder(listOf(from, to)).associateBy { it.id }
        val src = locked[from]!!

        // non-negative enforced for user/merchant accounts only
        if (src.type in setOf(AccountType.USER_WALLET, AccountType.MERCHANT) && src.balance < amount) {
            ledger.markFailed(txnId, "INSUFFICIENT_FUNDS")
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "insufficient balance")
        }

        ledger.insertEntry(LedgerEntry(txnId, from, Direction.DEBIT, amount))
        ledger.insertEntry(LedgerEntry(txnId, to, Direction.CREDIT, amount))
        accounts.applyDelta(from, -amount)
        accounts.applyDelta(to, amount)

        // conditional status flip — recovery-vs-slow-request guard
        if (ledger.markCompletedIfPending(txnId) == 0) {
            throw IllegalStateException("transaction $txnId no longer PENDING; aborting")
        }
        return txnId
    }
}
```

Note: `markFailed` runs and must persist even though we then throw. Because `markFailed` flips status to FAILED *before* the exception, and the exception rolls back the whole `@Transactional` method, the FAILED write would also roll back. **Fix in Step 5.**

- [ ] **Step 5: Fix the FAILED-must-persist problem with REQUIRES_NEW**

The insufficient-funds FAILED write must survive the rollback. Split it into a separate propagation. Update `LedgerService` to delegate the failure write to a helper bean:

Create `walletx/src/main/kotlin/com/walletx/ledger/TxnStatusWriter.kt`:

```kotlin
package com.walletx.ledger

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TxnStatusWriter(private val ledger: LedgerRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failInOwnTxn(txnId: UUID, reason: String) = ledger.markFailed(txnId, reason)
}
```

Then in `LedgerService`, inject `TxnStatusWriter` and replace the insufficient-funds branch:

```kotlin
// constructor: add `private val statusWriter: TxnStatusWriter`
if (src.type in setOf(AccountType.USER_WALLET, AccountType.MERCHANT) && src.balance < amount) {
    throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "insufficient balance")
}
```

And wrap the call site (in the controller, Task 13) OR catch within a non-transactional outer method. Simplest: make `recordTransfer` NOT itself `@Transactional`, and have it call a private `@Transactional` inner `doTransfer`, catching `ApiException("INSUFFICIENT_FUNDS")` to call `statusWriter.failInOwnTxn`. Implement that split:

```kotlin
fun recordTransfer(type: TxnType, idempotencyKey: String, requestHash: String,
                   initiatorId: UUID, from: UUID, to: UUID, amount: Long): UUID {
    val txnId = ledgerInsert(type, idempotencyKey, requestHash, amount, initiatorId, from, to)
    try {
        doTransfer(txnId, from, to, amount)
        return txnId
    } catch (e: ApiException) {
        if (e.code == "INSUFFICIENT_FUNDS") statusWriter.failInOwnTxn(txnId, e.code)
        throw e
    }
}

private fun ledgerInsert(type: TxnType, idem: String, hash: String, amount: Long,
                         initiator: UUID, from: UUID, to: UUID): UUID =
    ledger.insertPending(type, idem, hash, amount, initiator, from, to)

@Transactional
fun doTransfer(txnId: UUID, from: UUID, to: UUID, amount: Long) {
    val locked = accounts.lockByIdsInOrder(listOf(from, to)).associateBy { it.id }
    val src = locked[from]!!
    if (src.type in setOf(AccountType.USER_WALLET, AccountType.MERCHANT) && src.balance < amount)
        throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "insufficient balance")
    ledger.insertEntry(LedgerEntry(txnId, from, Direction.DEBIT, amount))
    ledger.insertEntry(LedgerEntry(txnId, to, Direction.CREDIT, amount))
    accounts.applyDelta(from, -amount)
    accounts.applyDelta(to, amount)
    if (ledger.markCompletedIfPending(txnId) == 0)
        throw IllegalStateException("transaction $txnId no longer PENDING")
}
```

Note: `insertPending` runs in its own implicit transaction (auto-commit) so the PENDING header persists before `doTransfer` opens its transaction — exactly what we want for crash-recovery legibility. `doTransfer` is `public` so Spring's proxy applies `@Transactional`.

- [ ] **Step 6: Run to verify all tests pass**

Run: `./gradlew test --tests "com.walletx.ledger.LedgerServiceTest"`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add walletx/src/main/kotlin/com/walletx/ledger/ walletx/src/test/kotlin/com/walletx/
git commit -m "feat: ledger recordTransfer money-writer with conditional status flip + FAILED-in-own-txn"
```

---

## Task 13: Concurrency correctness test (the proof in miniature)

**Files:**
- Test: `walletx/src/test/kotlin/com/walletx/ledger/LedgerConcurrencyTest.kt`

- [ ] **Step 1: Write the concurrency test**

```kotlin
package com.walletx.ledger

import com.walletx.support.Fixtures
import com.walletx.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerConcurrencyTest : PostgresTestBase() {
    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var fixtures: Fixtures

    @Test fun `100 concurrent debits never overdraw and conserve money`() {
        val a = fixtures.walletWith(10000)   // ₹100 -> exactly 100 transfers of ₹1
        val b = fixtures.walletWith(0)
        val pool = Executors.newFixedThreadPool(16)
        val ok = AtomicInteger(0); val failed = AtomicInteger(0)

        val tasks = (1..200).map { i ->   // 200 attempts on a balance that funds only 100
            Runnable {
                try {
                    ledger.recordTransfer(TxnType.P2P, "c$i", "h$i", UUID.randomUUID(), a, b, 100)
                    ok.incrementAndGet()
                } catch (e: Exception) { failed.incrementAndGet() }
            }
        }
        tasks.forEach { pool.submit(it) }
        pool.shutdown(); pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(100, ok.get())                    // exactly the funded number succeeded
        assertEquals(100, failed.get())                // the rest correctly rejected
        assertEquals(0, fixtures.balanceOf(a))         // never negative
        assertEquals(10000, fixtures.balanceOf(b))     // all money landed
        assertTrue(fixtures.balanceOf(a) >= 0)
    }
}
```

- [ ] **Step 2: Run to verify it passes**

Run: `./gradlew test --tests "com.walletx.ledger.LedgerConcurrencyTest"`
Expected: PASS. If it flakes, the lock ordering or the funds check is wrong — debug before proceeding (this test is the core correctness guarantee). Note: serialization on account `a` is expected and correct.

- [ ] **Step 3: Commit**

```bash
git add walletx/src/test/kotlin/com/walletx/ledger/LedgerConcurrencyTest.kt
git commit -m "test: 100 concurrent debits never overdraw, money conserved"
```

---

## Task 14: P2P transfer endpoint

**Files:**
- Create: `walletx/src/main/kotlin/com/walletx/ledger/dto.kt`
- Create: `walletx/src/main/kotlin/com/walletx/ledger/TransferController.kt`
- Test: `walletx/src/test/kotlin/com/walletx/ledger/TransferControllerTest.kt`

- [ ] **Step 1: Write `dto.kt`**

```kotlin
package com.walletx.ledger

import jakarta.validation.constraints.Positive
import java.util.UUID

data class P2pRequest(
    val idempotencyKey: String,
    val toUserId: UUID,
    @field:Positive val amountPaisa: Long,
)
data class TransferResult(val transactionId: UUID, val status: String)
```

- [ ] **Step 2: Write `TransferController.kt`**

```kotlin
package com.walletx.ledger

import com.walletx.account.AccountRepository
import com.walletx.auth.CurrentUser
import com.walletx.platform.ApiException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/transfers")
class TransferController(
    private val ledger: LedgerService,
    private val accounts: AccountRepository,
) {
    @PostMapping("/p2p")
    fun p2p(@CurrentUser callerId: UUID, @RequestBody req: P2pRequest): TransferResult {
        val from = accounts.findByOwner(callerId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_WALLET", "caller has no wallet")
        val to = accounts.findByOwner(req.toUserId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NO_PAYEE", "payee has no wallet")
        val requestHash = listOf("P2P", from.id, to.id, req.amountPaisa).joinToString("|").hashCode().toString()
        val txnId = ledger.recordTransfer(
            TxnType.P2P, req.idempotencyKey, requestHash, callerId, from.id, to.id, req.amountPaisa)
        return TransferResult(txnId, "COMPLETED")
    }
}
```

Note: `requestHash` here is the plumbing for Plan 2's idempotency replay; Plan 1 only stores it.

- [ ] **Step 3: Write the failing controller test**

```kotlin
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.walletx.ledger.TransferControllerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the FULL suite**

Run: `./gradlew test`
Expected: All tests PASS across platform, auth, ledger.

- [ ] **Step 6: Commit**

```bash
git add walletx/src/main/kotlin/com/walletx/ledger/ walletx/src/test/kotlin/com/walletx/ledger/TransferControllerTest.kt
git commit -m "feat: authenticated P2P transfer endpoint end-to-end"
```

---

## Done criteria (Plan 1)

- `./gradlew test` green: Money, JWT, auth flow, ledger service (5), concurrency (1), transfer controller (3).
- A user can sign up, log in, and send money to another user; balances and ledger entries are correct and concurrency-safe; overdraw is impossible; money is conserved.
- Schema is the full spec schema (idempotency/saga/outbox columns exist, populated in later plans).

## What's next (separate plans)

- **Plan 2 — Idempotency:** `request_hash` 4-way replay (completed/failed/in-flight/different-hash), `response_code/body` population, Redis fast-path, local-path stuck-PENDING recovery.
- **Plan 3 — Kafka + transactional outbox + polling publisher** (partition key = aggregate_id).
- **Plan 4 — Idempotent consumers** (rewards CASHBACK via ledger API, notifications), `processed_events` dedup.
- **Plan 5 — ADD_MONEY saga + simulated idempotent bank + compensation/recovery.**
- **Plan 6 — Reconciliation jobs.**
- **Plan 7 — Load tests + metrics + README proof/trade-offs.**
