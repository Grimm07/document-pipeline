plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "org.example.pipeline"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }

    afterEvaluate {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.time.ExperimentalTime")
            }
        }

        tasks.withType<JavaCompile> {
            sourceCompatibility = "21"
            targetCompatibility = "21"
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    }
}
