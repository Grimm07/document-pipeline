rootProject.name = "document-pipeline"

// Enable version catalog
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}


// Include all modules
include(
    "core-domain",
    "infra-db",
    "infra-storage",
    "infra-queue",
    "app-api",
    "app-worker"
)
