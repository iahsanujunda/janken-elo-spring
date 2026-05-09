import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

buildscript {
    dependencies {
        classpath("org.flywaydb:flyway-database-postgresql:11.16.0")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.6"
    id("org.flywaydb.flyway") version "11.16.0"
    id("nu.studer.jooq") version "10.1"
}

group = "me.iahsanujunda"
version = "0.0.1-SNAPSHOT"
description = "janken-elo"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["jooq.version"] = "3.20.3"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.6"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    jooqGenerator("org.postgresql:postgresql:42.7.4")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active", "local"))
    val dotenv = file(".env")
    if (dotenv.exists()) {
        dotenv.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach {
                val (k, v) = it.split("=", limit = 2)
                environment(k.trim(), v.trim().trim('"', '\''))
            }
    }
}

flyway {
    url = (project.findProperty("flyway.url") as String?)
        ?: "jdbc:postgresql://localhost:54322/postgres"
    user = (project.findProperty("flyway.user") as String?)
        ?: "postgres"
    password = (project.findProperty("flyway.password") as String?)
        ?: "postgres"
    schemas = arrayOf("public")
    locations = arrayOf("filesystem:src/main/resources/db/migration")
    baselineOnMigrate = true
}

jooq {
    version.set("3.20.3")  // must match the jooq.version override above
    configurations {
        create("main") {
            jooqConfiguration.apply {
                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    url = "jdbc:postgresql://localhost:54322/postgres"
                    user = "postgres"
                    password = "postgres"
                }
                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"
                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        excludes = "flyway_schema_history"
                    }
                    target.apply {
                        packageName = "me.iahsanujunda.jankenelo.jooq"
                        directory = "build/generated-src/jooq/main"
                    }
                }
            }
        }
    }
}

tasks.register("migrationNew") {
    group = "database"
    description = "Create a new empty timestamped migration. Usage: ./gradlew migrationNew --name=add_matches_table"

    @Suppress("UnstableApiUsage")
    val migrationName = providers.gradleProperty("name").orElse(
        providers.systemProperty("name")
    )

    doLast {
        val name = migrationName.orNull
            ?: error("Missing migration name. Usage: ./gradlew migrationNew -Pname=add_matches_table")

        // Validate name: lowercase, underscores, no spaces or weird chars
        require(name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            "Migration name must be lowercase letters/digits/underscores only, starting with a letter. Got: $name"
        }

        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val filename = "V${timestamp}__${name}.sql"
        val migrationsDir = file("src/main/resources/db/migration")
        migrationsDir.mkdirs()
        val file = migrationsDir.resolve(filename)

        require(!file.exists()) { "Migration file already exists: $filename" }

        file.writeText(
            """
            -- Migration: $name
            -- Created: ${LocalDateTime.now()}

            """.trimIndent()
        )

        println("Created migration: ${file.relativeTo(projectDir)}")
    }
}

tasks.register("dbUp") {
    group = "database"
    description = "Apply pending migrations and regenerate jOOQ code (local dev)."
    dependsOn("flywayMigrate", "generateJooq")
    tasks.findByName("generateJooq")?.mustRunAfter("flywayMigrate")
}
