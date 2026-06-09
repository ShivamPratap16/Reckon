plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    jacoco
    id("com.diffplug.spotless") version "6.25.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "com.reckon"
version = "0.1.0"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
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
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:toxiproxy:1.20.4")

    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.testcontainers:kafka:1.20.4")
    testImplementation("org.springframework.kafka:spring-kafka-test")

    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.43.0")
}

ext["testcontainers.version"] = "1.20.4"

// Machine-specific Docker socket config (e.g. Docker Desktop path) belongs in
// ~/.testcontainers.properties, not here, to keep the build portable across machines and CI.
tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("api.version", "1.44")
}
kotlin { compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") } }

jacoco { toolVersion = "0.8.12" }

tasks.test { finalizedBy(tasks.jacocoTestReport) }
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

detekt {
    buildUponDefaultConfig = true
    baseline = file("config/detekt/baseline.xml")
    config.setFrom(files("config/detekt/detekt.yml"))
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.3.1").editorConfigOverride(
            mapOf(
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "max_line_length" to "160",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.3.1")
    }
}
