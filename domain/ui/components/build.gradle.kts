plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            // The ONLY module allowed to depend on Material 3 (design.md §5).
            implementation(compose.material3)
        }
    }
}
