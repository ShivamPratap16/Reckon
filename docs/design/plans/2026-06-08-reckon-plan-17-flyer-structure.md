# Reckon Plan 17 — Flyer-Style Package Restructure

> Pure structural refactor: package-by-feature → sub-packages by responsibility, one public type per file. NO behavior change. The 81 tests must stay green; the only changes are file moves + `package`/`import` lines.

**Goal:** Mirror the `flyer` codebase convention. Every feature package gets layer sub-packages; every data class, enum, exception, DTO, and util gets its own file in the right sub-package. Nothing functional changes.

## Convention (sub-packages per feature, create only those needed)
- `controller/` — `@RestController`
- `listener/` — `@KafkaListener` classes (consumer feature)
- `service/` — `@Service` / business logic / executors / workers / validators
- `repository/` — `@Repository`
- `config/` — `@Configuration`, security, filters
- `model/` — domain data classes / entities (one per file)
- `enums/` — enums (one per file)
- `dto/request/` — request DTOs (one per file)
- `dto/response/` — response DTOs (one per file)
- `exception/` — exception classes
- `util/` — utility objects
- `constant/` — constant holders (objects / top-level const vals)

`ReckonApplication.kt` stays at root `com.reckon` (component scan root — unaffected).

## Complete mapping (current file → new file(s) + package)

### account → `com.reckon.account.*`
- `account/Account.kt` splits into:
  - `account/model/Account.kt` — `data class Account` (pkg `com.reckon.account.model`)
  - `account/enums/AccountType.kt` — `enum AccountType` (pkg `...account.enums`)
  - `account/constant/SystemAccounts.kt` — `object SystemAccounts` (pkg `...account.constant`)
  - `account/repository/AccountRepository.kt` — `@Repository AccountRepository` (pkg `...account.repository`)

### auth → `com.reckon.auth.*`
- `auth/AuthController.kt` → `auth/controller/AuthController.kt`; extract:
  - `auth/dto/request/AuthRequest.kt`
  - `auth/dto/response/AuthResponse.kt`
- `auth/AuthService.kt` → `auth/service/AuthService.kt`
- `auth/JwtService.kt` → `auth/service/JwtService.kt`
- `auth/JwtSecretValidator.kt` → `auth/config/JwtSecretValidator.kt`
- `auth/SecurityConfig.kt` splits into (pkg `...auth.config`):
  - `auth/config/SecurityConfig.kt` — `@Configuration SecurityConfig`
  - `auth/config/JwtAuthFilter.kt` — the filter
  - `auth/config/CurrentUserResolver.kt` — the resolver
  - `auth/config/CurrentUser.kt` — the `@CurrentUser` annotation
- `auth/User.kt` splits into:
  - `auth/model/User.kt` — `data class User`
  - `auth/repository/UserRepository.kt` — `@Repository UserRepository`

### bank → `com.reckon.bank.*`
- `bank/BankTypes.kt` splits into:
  - `bank/enums/BankResult.kt`
  - `bank/enums/BankStatus.kt`
  - `bank/exception/BankTimeoutException.kt`
- `bank/SimulatedBank.kt` → `bank/service/SimulatedBank.kt`

### consumer → `com.reckon.consumer.*`
- `consumer/PaymentEvent.kt` → `consumer/model/PaymentEvent.kt`
- `consumer/ProcessedEventRepository.kt` → `consumer/repository/ProcessedEventRepository.kt`
- `consumer/RewardsService.kt` → `consumer/service/RewardsService.kt`
- `consumer/NotificationsService.kt` → `consumer/service/NotificationsService.kt`
- `consumer/RewardsConsumer.kt` → `consumer/listener/RewardsConsumer.kt`
- `consumer/NotificationsConsumer.kt` → `consumer/listener/NotificationsConsumer.kt`

### hold → `com.reckon.hold.*`
- `hold/Hold.kt` splits into:
  - `hold/model/Hold.kt` — `data class Hold`
  - `hold/enums/HoldStatus.kt`
- `hold/HoldRepository.kt` → `hold/repository/HoldRepository.kt`
- `hold/AuthorizationService.kt` → `hold/service/AuthorizationService.kt`
- `hold/HoldExpiryService.kt` → `hold/service/HoldExpiryService.kt`
- `hold/HoldExpiryWorker.kt` → `hold/service/HoldExpiryWorker.kt`
- `hold/PaymentsController.kt` → `hold/controller/PaymentsController.kt`; extract:
  - `hold/dto/request/AuthorizeRequest.kt`
  - `hold/dto/request/CaptureRequest.kt`
  - `hold/dto/response/HoldResponse.kt`

### idempotency → `com.reckon.idempotency.*`
- `idempotency/IdempotencyCache.kt` splits into:
  - `idempotency/model/CachedResult.kt` — `data class CachedResult`
  - `idempotency/service/IdempotencyCache.kt` — the interface
- `idempotency/RedisIdempotencyCache.kt` → `idempotency/service/RedisIdempotencyCache.kt`

### ledger → `com.reckon.ledger.*`
- `ledger/LedgerEntry.kt` splits into:
  - `ledger/enums/Direction.kt`, `ledger/enums/TxnType.kt`, `ledger/enums/TxnStatus.kt`
  - `ledger/model/LedgerEntry.kt` — `data class LedgerEntry`
- `ledger/LedgerRepository.kt` → `ledger/repository/LedgerRepository.kt`; extract `data class ExistingTxn` → `ledger/model/ExistingTxn.kt`
- `ledger/LedgerService.kt` → `ledger/service/LedgerService.kt`
- `ledger/TransferExecutor.kt` → `ledger/service/TransferExecutor.kt`
- `ledger/OptimisticTransferExecutor.kt` → `ledger/service/OptimisticTransferExecutor.kt`
- `ledger/TxnStatusWriter.kt` → `ledger/service/TxnStatusWriter.kt`
- `ledger/TransferController.kt` → `ledger/controller/TransferController.kt`
- `ledger/TransferOutcome.kt` → `ledger/model/TransferOutcome.kt`
- `ledger/dto.kt` splits into:
  - `ledger/dto/request/P2pRequest.kt`
  - `ledger/dto/response/TransferResult.kt`

### outbox → `com.reckon.outbox.*`
- `outbox/OutboxEvent.kt` splits into:
  - `outbox/constant/EventType.kt` — `object EventType`
  - `outbox/model/OutboxRow.kt` — `data class OutboxRow`
- `outbox/OutboxRepository.kt` → `outbox/repository/OutboxRepository.kt`
- `outbox/OutboxPublisher.kt` → `outbox/service/OutboxPublisher.kt`
- `outbox/OutboxRowPublisher.kt` → `outbox/service/OutboxRowPublisher.kt`

### recon → `com.reckon.recon.*`
- `recon/ReconciliationReport.kt` splits into (pkg `...recon.model`):
  - `recon/model/ReconciliationReport.kt`, `recon/model/UnbalancedTxn.kt`, `recon/model/BalanceDrift.kt`, `recon/model/ReservedDrift.kt`
- `recon/ReconciliationRepository.kt` → `recon/repository/ReconciliationRepository.kt`
- `recon/ReconciliationService.kt` → `recon/service/ReconciliationService.kt`

### saga → `com.reckon.saga.*`
- `saga/AddMoneyController.kt` → `saga/controller/AddMoneyController.kt`; extract `saga/dto/request/AddMoneyRequest.kt`
- `saga/AddMoneyService.kt` → `saga/service/AddMoneyService.kt`
- `saga/SagaRecoveryService.kt` → `saga/service/SagaRecoveryService.kt`

### platform (cross-cutting) → `com.reckon.platform.*`
- `platform/ApiError.kt` splits into:
  - `platform/exception/ApiException.kt`
  - `platform/dto/ApiErrorBody.kt` (or `platform/error/`) — keep as `platform.dto`
  - `platform/web/GlobalExceptionHandler.kt` — the `@RestControllerAdvice`
- `platform/CorrelationIdFilter.kt` splits into:
  - `platform/filter/CorrelationIdFilter.kt`
  - `platform/constant/CorrelationId.kt` — the two `const val`s
- `platform/KafkaConfig.kt` → `platform/config/KafkaConfig.kt`
- `platform/Metrics.kt` → `platform/metrics/Metrics.kt`
- `platform/Money.kt` → `platform/model/Paisa.kt` (the `Paisa` value class)
- `platform/RequestHash.kt` → `platform/util/RequestHash.kt`

## Execution rules
1. Do it **feature by feature** in this order: platform (shared, others depend on it) → account → auth → bank → ledger → outbox → consumer → idempotency → hold → saga → recon.
2. For each moved/renamed type: update its `package` line, move the file with `git mv` (or create new + delete old), and fix EVERY `import` across `src/main` AND `src/test` that referenced it.
3. After EACH feature: run `./gradlew compileKotlin compileTestKotlin` and fix all errors before moving on (localizes breakage).
4. Spotless: run `./gradlew spotlessApply` before final commit so `spotlessCheck` passes.
5. detekt: a baseline exists; if new files trip new findings, regenerate the baseline (`./gradlew detektBaseline`) — do NOT relax rules.
6. **The only allowed diff is moves + package/import lines** (and splitting multi-type files). NO logic edits. If a test references a type by its old package, update the import only.
7. FINAL gate: `./gradlew build` fully green (81 tests, spotless, detekt, jacoco). Test COUNT must remain 81 — none deleted/skipped.

## Done criteria
- Every data class, enum, exception, DTO, util lives in its own file under the correct sub-package; no data class/enum remains co-located in a controller/service/repository file.
- `./gradlew build` green; 81 tests pass; diff is moves + package/import only.
