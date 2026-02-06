plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Domain module
    implementation(projects.coreDomain)

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
    testImplementation(libs.bundles.testcontainers)
}
