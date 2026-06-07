# Reckon Plan 13 — Tier 0: CI/CD, Quality Gates, Containerization Implementation Plan

> Implementation plan — each task is a small, independently-verifiable checklist of steps (`- [ ]`), built and tested incrementally.

**Goal:** Turn Reckon from "code on GitHub" into "a system that builds, tests, and runs" with enforced quality: a GitHub Actions CI pipeline (build + 81 tests + lint + coverage) on every push/PR, code-format + static-analysis gates, a coverage gate, a production multi-stage Docker image, a full-stack production compose for VM deployment, and fail-fast secret handling.

**What is buildable here vs. needs you (be honest):**
- **Buildable + verified in this repo:** CI workflow YAML, JaCoCo report+gate, Spotless/ktlint formatting, detekt static analysis (with baseline), multi-stage Dockerfile (image builds), `docker-compose.prod.yml` (parses + boots locally), fail-fast secret config, a `DEPLOY.md`.
- **Needs you (documented, not done here):** enabling **branch protection** on GitHub (repo admin — exact steps in Task 8), and the **actual cloud deploy** (your VM/account — `DEPLOY.md` gives the commands).

**Tech Stack:** GitHub Actions, JaCoCo, Spotless+ktlint, detekt, Docker. No app-logic changes (formatting + config + infra only).

**Builds on:** Plans 1–12 (`com.reckon`, 81 tests).

---

## Ordering note
Format (Spotless) FIRST so the whole codebase is consistent, THEN add detekt with a baseline, THEN CI that runs both. This avoids CI failing on pre-existing style. Every gate must end with `./gradlew build` green.

---

## Task 1: JaCoCo coverage report + gate
**Files:** Modify `build.gradle.kts`
- [ ] **Step 1:** Apply the `jacoco` plugin; make `test` finalize with `jacocoTestReport`; add a `jacocoTestCoverageVerification` with a modest, real threshold.
```kotlin
plugins { /* existing */ jacoco }

jacoco { toolVersion = "0.8.12" }

tasks.test { finalizedBy(tasks.jacocoTestReport) }
tasks.jacocoTestReport { dependsOn(tasks.test); reports { xml.required.set(true); html.required.set(true) } }

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.55".toBigDecimal() }   // see Step 2 — tune below measured
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
```
- [ ] **Step 2: Measure actual coverage first, then set the gate just below it.** Run `./gradlew test jacocoTestReport` and read `build/reports/jacoco/test/html/index.html` (or the XML) for the actual LINE coverage. Set `minimum` to a round number a few points BELOW the measured value (e.g. measured 68% → set 0.60), so the gate is meaningful but green. Report the measured number and the chosen threshold.
- [ ] **Step 3:** `./gradlew check` passes (test + coverage verification green). **Commit** `git add -A && git commit -m "build: jacoco coverage report + verification gate"`

---

## Task 2: Spotless + ktlint formatting
**Files:** Modify `build.gradle.kts`
- [ ] **Step 1:** Apply Spotless with ktlint for Kotlin + a license/import-order ruleset that won't fight the code.
```kotlin
plugins { /* existing */ id("com.diffplug.spotless") version "6.25.0" }

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.3.1").editorConfigOverride(mapOf(
            "ktlint_standard_no-wildcard-imports" to "disabled",      // repo uses some wildcard imports
            "ktlint_standard_filename" to "disabled",                 // multi-type files exist (e.g. dto.kt)
            "max_line_length" to "160",
        ))
    }
    kotlinGradle { target("*.gradle.kts"); ktlint("1.3.1") }
}
```
- [ ] **Step 2: Run `./gradlew spotlessApply`** — this reformats the codebase (expected large but mechanical diff). Then `./gradlew spotlessCheck` passes.
- [ ] **Step 3: Run the FULL suite** `./gradlew test` — must STILL be green (formatting is behavior-preserving; if any test broke, a reformat changed semantics — investigate, do not force).
- [ ] **Step 4: Commit** `git add -A && git commit -m "style: apply spotless/ktlint formatting across the codebase"`

---

## Task 3: detekt static analysis (with baseline)
**Files:** Modify `build.gradle.kts`; Create `config/detekt/detekt.yml` (or use default) + a baseline
- [ ] **Step 1:** Apply detekt.
```kotlin
plugins { /* existing */ id("io.gitlab.arturbosch.detekt") version "1.23.7" }
detekt {
    buildUponDefaultConfig = true
    baseline = file("config/detekt/baseline.xml")
    config.setFrom(files("config/detekt/detekt.yml"))
}
```
- [ ] **Step 2:** Create `config/detekt/detekt.yml` with `build-upon-default-config: true` and a few sensible relaxations for a Spring app (e.g. allow `TooGenericExceptionCaught` in the degradation/recovery paths where catching `Exception` is intentional, allow longer methods in the saga). Keep it reasonable, not permissive-everything.
- [ ] **Step 3: Generate a baseline** so pre-existing findings don't block CI but NEW code is checked: `./gradlew detektBaseline` (creates `config/detekt/baseline.xml`). Then `./gradlew detekt` passes.
- [ ] **Step 4: Commit** `git add -A && git commit -m "build: detekt static analysis with baseline"`

---

## Task 4: GitHub Actions CI
**Files:** Create `.github/workflows/ci.yml`
- [ ] **Step 1: Write** — runs on push + PR; sets up JDK 21; runs spotlessCheck, detekt, build (compile + the 81 Testcontainers tests — the ubuntu runner has Docker), and uploads the JaCoCo report. Gradle caching for speed.
```yaml
name: CI
on:
  push: { branches: ["**"] }
  pull_request: { branches: ["**"] }
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts','**/gradle-wrapper.properties') }}
          restore-keys: gradle-${{ runner.os }}-
      - name: Spotless
        run: ./gradlew spotlessCheck
      - name: Detekt
        run: ./gradlew detekt
      - name: Build + test (Testcontainers needs Docker — present on ubuntu runners)
        run: ./gradlew build
      - name: Upload coverage
        if: always()
        uses: actions/upload-artifact@v4
        with: { name: jacoco-report, path: build/reports/jacoco/test/html }
```
Notes: `./gradlew build` runs `check` (test + jacoco verification + detekt + spotlessCheck) — the explicit Spotless/Detekt steps give clearer failure surfaces but are technically subsumed; keep them for readable CI output. Testcontainers on GitHub's ubuntu runner works (Docker preinstalled); the first run pulls images (postgres/kafka/redis/toxiproxy) so allow the 30-min timeout.
- [ ] **Step 2: Validate** the YAML parses (a quick `python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml'))"` or equivalent). The workflow truly proves itself only on push — note that in the report.
- [ ] **Step 3: Add a CI badge** to the top of `README.md`: `![CI](https://github.com/ShivamPratap16/Reckon/actions/workflows/ci.yml/badge.svg)`.
- [ ] **Step 4: Commit** `git add -A && git commit -m "ci: github actions — spotless, detekt, build+test, coverage"`

---

## Task 5: Fail-fast secret handling
**Files:** Modify `src/main/kotlin/com/reckon/auth/JwtService.kt` (or a config validator); `application.yml`
- [ ] **Step 1:** The JWT secret currently defaults to a dev value. Make the app **refuse to boot in a `prod` profile with the default secret.** Add a small `@Configuration` validator or an `@PostConstruct` check in `JwtService`: if the active profile contains `prod` AND the secret equals the known dev default (or is blank/too short), throw at startup with a clear message. Keep the dev default working for tests/local (non-prod).
```kotlin
// in JwtService init or a separate validator bean:
// if (environment.activeProfiles.contains("prod") && secret == DEV_DEFAULT) error("JWT secret must be overridden in prod")
```
Implement cleanly (inject `Environment`); ensure the 81 tests (no `prod` profile) are unaffected.
- [ ] **Step 2:** `application.yml` — document via comment that `JWT_SECRET`, DB creds, `KAFKA_BOOTSTRAP`, `REDIS_HOST` are env-overridable (they already are); add an `application-prod.yml` that sets `spring.profiles` expectations / sane prod logging.
- [ ] **Step 3:** `./gradlew test` green. **Commit** `git add -A && git commit -m "feat: fail-fast on default JWT secret under prod profile"`

---

## Task 6: Production Dockerfile + .dockerignore
**Files:** Create `Dockerfile`, `.dockerignore`
- [ ] **Step 1: `Dockerfile`** — multi-stage: build the bootJar with Gradle+JDK21, run on a slim JRE; non-root user; healthcheck hitting `/actuator/health`.
```dockerfile
# --- build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies || true
COPY src ./src
COPY config ./config
RUN ./gradlew --no-daemon bootJar -x test

# --- runtime stage ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd -r -u 1001 reckon
COPY --from=build /app/build/libs/reckon-0.1.0.jar app.jar
USER reckon
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=10 CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
```
- [ ] **Step 2: `.dockerignore`** — exclude `build/`, `.git`, `.gradle`, `docs/`, IDE files, etc.
- [ ] **Step 3: BUILD the image** — `docker build -t reckon:local .` must SUCCEED. (This actually compiles + packages the app in Docker.) Report success. If the build-stage dependency caching line is flaky, simplify it (just `COPY` then `bootJar`).
- [ ] **Step 4: Commit** `git add -A && git commit -m "build: production multi-stage Dockerfile + dockerignore"`

---

## Task 7: Production compose + DEPLOY.md
**Files:** Create `docker-compose.prod.yml`, `DEPLOY.md`
- [ ] **Step 1: `docker-compose.prod.yml`** — the full stack for a single VM: the app image + Postgres + Kafka + Redis, with the app wired to them via env (`DB_URL`, `KAFKA_BOOTSTRAP`, `REDIS_HOST`), `prod` profile active, `JWT_SECRET` from an env var, depends_on with health conditions, restart policies, named volumes for Postgres.
- [ ] **Step 2: `docker compose -f docker-compose.prod.yml config`** parses cleanly. (Optionally bring it up locally to smoke-test the app boots against real deps — if you do and it works, note it; if Kafka/Redis startup ordering is flaky, rely on `depends_on: condition: service_healthy`.)
- [ ] **Step 3: `DEPLOY.md`** — honest deploy guide: prerequisites (a VM with Docker), `export JWT_SECRET=...`, `docker compose -f docker-compose.prod.yml up -d`, how to verify (`/actuator/health`), run the load test against it, and the observability stack. Note the managed-services alternative (RDS/MSK/ElastiCache) and why a single-VM compose is the pragmatic portfolio deploy.
- [ ] **Step 4: Commit** `git add -A && git commit -m "feat: production compose (app+pg+kafka+redis) + DEPLOY.md"`

---

## Task 8: Branch-protection + CI docs (for the human)
**Files:** Create `docs/CONTRIBUTING.md` (or append to DEPLOY/README)
- [ ] **Step 1:** Document the exact branch-protection settings to enable on GitHub (Settings → Branches → add rule for `main`): require PR before merge, require the `build` status check to pass, require linear history, no direct pushes. Note this needs repo-admin and is a click-through (or `gh api` if a token is available). Also document the contribution flow (branch → PR → CI green → review → squash/merge).
- [ ] **Step 2: Commit** `git add -A && git commit -m "docs: branch protection + contribution workflow"`

---

## Done criteria (Plan 13)
- `./gradlew build` runs spotlessCheck + detekt + the 81 tests + JaCoCo verification, all green.
- `.github/workflows/ci.yml` is valid and will run the full gate on push/PR; README has a CI badge.
- `docker build -t reckon:local .` succeeds (app packaged in a slim non-root JRE image with a healthcheck).
- `docker-compose.prod.yml` parses (full stack); `DEPLOY.md` documents the VM deploy.
- App fails fast on the default JWT secret under the `prod` profile; tests (non-prod) unaffected.
- `CONTRIBUTING.md` documents branch protection (the one human step) + the PR workflow.

## What's next
- Plan 14 (Tier 1): ops hardening — readiness/liveness, graceful shutdown, circuit breaker, DLQ replay, backup/restore drill, alerting.
- Plans 15 (security), 16 (scale).
