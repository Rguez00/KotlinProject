plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler) // NECESARIO con Kotlin 2.x
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // Compose Desktop
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)

                // Módulos del proyecto
                implementation(project(":core-domain"))
                implementation(project(":core-data"))
            }
        }
        val jvmTest by getting
    }
}

compose.desktop {
    application {
        mainClass = "org.example.project.MainKt"
    }
}
