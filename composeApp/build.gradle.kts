import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler) // Kotlin 2.x
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

        nativeDistributions {
            packageName = "MonitorDeProcesos"
            packageVersion = "1.0.0"

            val os = org.gradle.internal.os.OperatingSystem.current()
            when {
                os.isWindows -> {
                    targetFormats(
                        TargetFormat.Msi
                        // TargetFormat.Exe // si tu versión lo soporta
                    )
                }
                os.isLinux -> {
                    targetFormats(
                        TargetFormat.Deb,
                        TargetFormat.Rpm
                        // TargetFormat.AppImage // si tu versión lo soporta
                    )
                }
                else -> {
                    // Por si ejecutas el empaquetado en macOS
                    targetFormats(TargetFormat.Dmg)
                }
            }
        }
    }
}

