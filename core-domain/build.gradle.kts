plugins {
    // usa el mismo alias que en los otros módulos
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting
        val jvmTest by getting
    }
}

// (si hiciera falta)
repositories { mavenCentral() }
