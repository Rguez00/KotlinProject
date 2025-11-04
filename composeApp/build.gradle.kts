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
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                // si los iconos molestan, comenta la siguiente línea
                implementation(compose.materialIconsExtended)

                // ⚠️ Forma clásica, NO type-safe accessor
                implementation(project(":core-domain"))
            }
        }
        val jvmTest by getting
    }
}

compose.desktop {
    application { mainClass = "org.example.project.MainKt" }
}
