plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(projects.coreDomain)
            }
        }
        val jvmTest by getting
    }
}
