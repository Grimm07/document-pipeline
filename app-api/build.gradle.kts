plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("org.example.pipeline.api.ApplicationKt")
}

dependencies {
    // Project modules
    implementation(projects.coreDomain)
    implementation(projects.infraDb)
    implementation(projects.infraStorage)
    implementation(projects.infraQueue)

    // Ktor Server
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.serialization.kotlinx.json)

    // RabbitMQ (exposed through infra-queue, needed for Connection type in DI)
    implementation(libs.rabbitmq.client)

    // Koin DI
    implementation(libs.bundles.koin)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.bundles.testcontainers)
}
