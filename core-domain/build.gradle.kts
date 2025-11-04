plugins {
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
