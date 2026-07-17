plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// MIGRATION STEP 4: the Ktor clients (HttpDeviceFilesSource, HttpLeaveNotifier) moved to
// `:adapter:generic`; the iOS adapters (darwinHttpClient, IosJoinedEventMarker) to
// `:adapter:ios:ext-safe`. What remains is the platform-free reconciliation + leave use-cases.
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
