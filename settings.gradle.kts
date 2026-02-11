rootProject.name = "document-pipeline"

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
