plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// MIGRATION STEP 4: the Ktor clients (HttpDeviceFilesSource, HttpLeaveNotifier) moved to
// `:adapter:generic`; the iOS adapters (darwinHttpClient, IosJoinedEventMarker) to
// `:adapter:ios:ext-safe`. STEP 5: the reconciliation moved to :domain feature/upload and the
// leave use-case to feature/membership — this module is sourceless; the skeleton dies at step 6.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(libs.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
