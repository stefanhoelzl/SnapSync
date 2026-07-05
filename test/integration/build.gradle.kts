plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Test-only seam ↔ UI-state integration module (testing rule 3), now spanning the REAL upload/download
// execution edge via `:test:world`. Assembles the real `engine → status → presentation` stack over the
// world and asserts `UiState` AND world outcomes (objects landed, ledger COMPLETED, foreign photos
// imported). commonTest only — it exists so the tests may cross the `engine → presentation` boundary
// production forbids. Runs on JVM + `iosSimulatorArm64`.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosSimulatorArm64()
    sourceSets {
        commonTest.dependencies {
            implementation(project(":test:world"))
            implementation(project(":domain:presentation"))
            implementation(project(":domain:status"))
            implementation(project(":domain:permission"))
            implementation(project(":capability:config"))
            implementation(project(":capability:event-creation-ui"))
            implementation(project(":capability:push"))
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.orbit.test)
        }
    }
}
