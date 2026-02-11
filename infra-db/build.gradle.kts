plugins {
    id("pipeline-conventions")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Domain module
    implementation(project(":core-domain"))

    // Database
    implementation(libs.bundles.exposed)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.bundles.flyway)

    // Serialization for JSONB
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

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
