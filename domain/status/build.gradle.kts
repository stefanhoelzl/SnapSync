plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    sourceSets {
        commonMain.dependencies {
            // Implementation scope on purpose: status is a consumer of the engine's ledger and
            // of permission, but neither may leak through to status's own consumers.
            implementation(project(":domain:engine"))
            implementation(project(":domain:permission"))
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
