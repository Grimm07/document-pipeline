plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Domain module
    implementation(projects.coreDomain)

    // Coroutines for async file operations
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
