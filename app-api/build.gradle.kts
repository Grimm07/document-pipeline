plugins {
    id("pipeline-conventions")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
    application
}

application {
    mainClass.set("org.example.pipeline.api.ApplicationKt")
}

dependencies {
    // Project modules
    implementation(project(":core-domain"))
    implementation(project(":infra-db"))
    implementation(project(":infra-storage"))
    implementation(project(":infra-queue"))

    // Ktor Server
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)

    // RabbitMQ (exposed through infra-queue, needed for Connection type in DI)
    implementation(libs.rabbitmq.client)

    // Validation
    implementation(libs.konform)

    // Koin DI
    implementation(libs.bundles.koin)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    // Rate limiting
    implementation(libs.ktor.server.rate.limit)

    // Observability
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.call.id)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.logstash.logback.encoder)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.bundles.testcontainers)
}

jib {
    from { image = "eclipse-temurin:21-jre-alpine" }
    to {
        image = "ghcr.io/grimm07/document-pipeline-api"
        tags = setOf("latest", System.getenv("GIT_SHA") ?: "dev")
    }
    container {
        jvmFlags = listOf("-XX:MaxRAMPercentage=75.0")
        mainClass = "org.example.pipeline.api.ApplicationKt"
        ports = listOf("8080")
        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
