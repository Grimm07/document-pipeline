plugins {
    id("pipeline-conventions")
    alias(libs.plugins.kotlin.serialization)
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

    // Koin DI
    implementation(libs.bundles.koin)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

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

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
