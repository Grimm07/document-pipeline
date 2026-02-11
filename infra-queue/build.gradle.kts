plugins {
    id("pipeline-conventions")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Domain module
    implementation(project(":core-domain"))

    // RabbitMQ
    implementation(libs.rabbitmq.client)

    // Serialization for message payloads
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.kotest.extensions.testcontainers)
}
