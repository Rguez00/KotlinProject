plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":core-domain"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1") // <-- AÑADIR
            }
        }
        val jvmTest by getting
    }
}

// Si dá error de repositorios, asegúrate de tener:
repositories { mavenCentral() }
