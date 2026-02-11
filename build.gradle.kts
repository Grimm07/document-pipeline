plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

allprojects {
    group = "org.example.pipeline"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
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
