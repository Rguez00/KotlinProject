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

        // Flags de runtime para distribuciones
        jvmArgs += listOf(
            "-Dskiko.render.api=OPENGL",
            "-Dskiko.vsync.enabled=true",
            "-Dmonitor.fastMode=true",
            "-Dmonitor.noLogs=true",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8"
        )

        nativeDistributions {
            packageName = "MonitorDeProcesos"
            packageVersion = "1.0.0"
            vendor = "ProyectoProcesos"

            val os = org.gradle.internal.os.OperatingSystem.current()
            when {
                os.isWindows -> {
                    targetFormats(TargetFormat.Msi)
                    windows {
                        console = false      // 👈 sin ventana de consola
                        // perUser = true     // (opcional) instalar en perfil de usuario
                    }
                }
                os.isLinux -> {
                    targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
                }
                else -> {
                    targetFormats(TargetFormat.Dmg)
                }
            }
        }
    }
}
