plugins {
    id("pipeline-conventions")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
    application
}

application {
    mainClass.set("org.example.pipeline.worker.ApplicationKt")
}

dependencies {
    // Project modules
    implementation(project(":core-domain"))
    implementation(project(":infra-db"))
    implementation(project(":infra-storage"))
    implementation(project(":infra-queue"))

    // Ktor Client for ML service calls
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.config.yaml)

    // RabbitMQ (exposed through infra-queue, but needed for types)
    implementation(libs.rabbitmq.client)

    // Koin DI
    implementation(libs.koin.core)
    implementation(libs.koin.logger.slf4j)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    // Observability
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
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.bundles.ktor.server)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.bundles.testcontainers)
}

jib {
    from { image = "eclipse-temurin:21-jre-alpine" }
    to {
        image = "ghcr.io/grimm07/document-pipeline-worker"
        tags = setOf("latest", System.getenv("GIT_SHA") ?: "dev")
    }
    container {
        jvmFlags = listOf("-XX:MaxRAMPercentage=75.0")
        mainClass = "org.example.pipeline.worker.ApplicationKt"
        ports = listOf("8081")
        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
