plugins {
    id("pipeline-conventions")
    alias(libs.plugins.kotlin.serialization)
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

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
